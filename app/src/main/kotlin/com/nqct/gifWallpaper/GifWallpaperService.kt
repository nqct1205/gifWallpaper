package com.nqct.gifWallpaper

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.io.File

// GIF 状态机动态壁纸服务。
//
// 【核心设计（本轮重大变更）】
// 状态切换由**定时器**驱动，不再依赖 AnimatedImageDrawable.onAnimationEnd。
//
// 原因：AnimatedImageDrawable 在 repeatCount = 0（播一遍）播完后，
// start() 的重启行为在多实例场景下不可靠（实测播完后再 start() 不会重启，
// 多次 enterState 反复 stop/start 后甚至永远停第一帧）。
// onAnimationEnd 的回调也在这类状态下存在时序不可控问题。
//
// 新方案：
//   - 动画一律 repeatCount = REPEAT_INFINITE，动画本身永远循环播放，视觉不卡。
//   - 状态切换用 handler.postDelayed 定时器：时长 = GifInfo.totalDelayMs（一轮）。
//   - 定时器每轮触发 playback.onCycleComplete()：未达目标圈数则原地继续
//     （动画一直在播，不需要重启，仅重启定时器）；达到则切换状态。
//   - onAnimationEnd 仅保留用于调试计数（onAnimationEndCount），不驱动任何逻辑。
//
// 【全局开关】来自 SharedPreferences "PetPrefs"：
//   enableOrientation / enableShake / enableClick：是否启用对应主事件
//   shakeSensitivity：摇一摇阈值（g）
//   showDebug / limitDraw / limitMem：调试
//
// 【传感器注册策略】
//   GRAVITY: enableOrientation || enableShake || userShowDebug
//   ACCEL:   enableShake || userShowDebug
//   开关只拦截触发行为，不影响注册（条件判定可能仍需要方向）。
//   不可见时统一注销。
//
// 【多实例】同一动画内每个资源引用一个独立 AnimatedImageDrawable。
class GifWallpaperService : WallpaperService() {

    @Volatile private var currentEngine: GifEngine? = null

    override fun onCreateEngine(): Engine = GifEngine().also { currentEngine = it }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        currentEngine?.handleTrimMemory(level)
    }

    private data class RenderInstance(
        val ref: AnimationResourceRef,
        val drawable: AnimatedImageDrawable,
        val info: GifInfo?
    )

    inner class GifEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener,
        SensorEventListener {

        // ==================== 线程与生命周期 ====================
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var visible = false
        @Volatile private var destroyed = false
        @Volatile private var ready = false

        // ==================== 数据 ====================
        private var config: ProjectConfig = ProjectConfig()
        private val playback = PlaybackController { config }

        private val animRuntimeCache = mutableMapOf<String, List<RenderInstance>>()
        private var currentRuntime: List<RenderInstance> = emptyList()
        private var currentRenderStateId: String = ""

        private var loaderThread: Thread? = null
        private var prefs: SharedPreferences? = null

        // ==================== 周期定时器（核心） ====================
        private var cycleTimerRunnable: Runnable? = null

        // ==================== 全局开关 ====================
        private var enableOrientation = true
        private var enableShake = true
        private var enableClick = true

        // ==================== 传感器 ====================
        private val sensorManager by lazy {
            getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }
        private val motionTracker = MotionTracker()
        private val touchTracker by lazy { TouchTracker(this@GifWallpaperService, 60f, 800L) }
        private var gravitySensor: Sensor? = null
        private var accelSensor: Sensor? = null
        private var sensorsRegistered = false
        private var gravityRegistered = false
        private var accelRegistered = false
        private var lastHandledShakeElapsed = 0L
        private var lastHandledClickText = ""

        // ==================== 调试 ====================
        private var userShowDebug = false
        private var limitDrawTime = 20
        private var limitMemory = 150
        private var forceShowDebug = false
        private var engineStartTime = 0L

        private var lastFrameTime = 0L
        private var frameIntervalMs = 0L
        private var drawTimeMs = 0f
        private var lastMemoryUpdateTime = 0L
        private var currentMemMb = 0
        private var loadErrorMsg = ""

        // 调试计数器：用于诊断
        @Volatile private var onAnimationEndCount = 0
        @Volatile private var enterStateCount = 0
        @Volatile private var cycleTimerCount = 0

        private val debugRenderer = DebugOverlayRenderer()
        private val memInfo = Debug.MemoryInfo()

        // ==================== 并发 & 内存策略 ====================
        @Volatile private var loadGeneration = 0
        private var needsResetOnVisible = false
        private val LOW_MEM_AVAIL_MB = 150L

        // ==================== 动画回调（仅调试计数，不驱动逻辑） ====================
        private val animCallback = object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                onAnimationEndCount++
            }
        }

        private val drawRunnable = object : Runnable {
            override fun run() {
                if (destroyed || !visible || !ready) return
                val nextDelay = drawFrame()
                if (destroyed || !visible) return
                handler.postDelayed(this, nextDelay)
            }
        }

        // ==================== 生命周期 ====================

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
            prefs?.registerOnSharedPreferenceChangeListener(this)
            readGlobalPrefs()

            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            motionTracker.onOrientationChanged = { newOrientation ->
                handler.post { handleOrientationChanged(newOrientation) }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            unregisterSensors()
            cancelCycleTimer()
            cleanupAnimCallbacks()
        }

        private fun readGlobalPrefs() {
            val p = prefs ?: return
            userShowDebug = p.getBoolean("showDebug", false)
            limitDrawTime = p.getInt("limitDraw", 20)
            limitMemory = p.getInt("limitMem", 150)
            enableOrientation = p.getBoolean("enableOrientation", true)
            enableShake = p.getBoolean("enableShake", true)
            enableClick = p.getBoolean("enableClick", true)
            val sensitivity = p.getFloat("shakeSensitivity", 2.4f)
            motionTracker.setShakeThreshold(sensitivity)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (sharedPreferences == null) return
            when (key) {
                "showDebug", "limitDraw", "limitMem" -> {
                    handler.post {
                        userShowDebug = sharedPreferences.getBoolean("showDebug", false)
                        limitDrawTime = sharedPreferences.getInt("limitDraw", 20)
                        limitMemory = sharedPreferences.getInt("limitMem", 150)
                        forceShowDebug = false
                        if (userShowDebug && !sensorsRegistered && visible) {
                            registerSensorsIfNeeded()
                        }
                    }
                }
                "enableOrientation" -> {
                    handler.post {
                        enableOrientation = sharedPreferences.getBoolean("enableOrientation", true)
                        if (!sensorsRegistered && visible) registerSensorsIfNeeded()
                    }
                }
                "enableShake" -> {
                    handler.post {
                        enableShake = sharedPreferences.getBoolean("enableShake", true)
                        if (!sensorsRegistered && visible) registerSensorsIfNeeded()
                    }
                }
                "enableClick" -> {
                    handler.post {
                        enableClick = sharedPreferences.getBoolean("enableClick", true)
                    }
                }
                "shakeSensitivity" -> {
                    handler.post {
                        val v = sharedPreferences.getFloat("shakeSensitivity", 2.4f)
                        motionTracker.setShakeThreshold(v)
                    }
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            destroyed = false
            visible = false
            ready = false
            loadInBackground()
        }

        fun handleTrimMemory(level: Int) {
            if (destroyed) return
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                releaseNonCurrentCaches()
            }
        }

        // ==================== 后台加载 ====================

        private fun loadInBackground() {
            val gen = ++loadGeneration
            loaderThread?.interrupt()
            loaderThread = Thread {
                try {
                    var cfg = ProjectConfig()
                    var cfgMsg: String? = null
                    when (val r = ProjectConfigStore.load(this@GifWallpaperService)) {
                        is ProjectConfigStore.LoadResult.Ok -> cfg = r.config
                        is ProjectConfigStore.LoadResult.Corrupted -> {
                            cfgMsg = "配置损坏: ${r.message}\n(未应用，等待用户处理)"
                        }
                    }

                    val dir = ProjectConfigStore.animDir(this@GifWallpaperService)
                    val newCache = mutableMapOf<String, List<RenderInstance>>()
                    val sb = StringBuilder()
                    if (cfgMsg != null) sb.append(cfgMsg).append("\n")
                    if (cfg.states.isEmpty()) sb.append("状态为空\n")

                    for ((animId, anim) in cfg.animations) {
                        if (gen != loadGeneration) return@Thread
                        if (Thread.currentThread().isInterrupted) return@Thread
                        val instances = mutableListOf<RenderInstance>()
                        for (ref in anim.resources) {
                            val resource = cfg.resources[ref.resourceId] ?: continue
                            val file = File(dir, resource.fileName)
                            val drawable = decodeAnimatedDrawable(file) ?: run {
                                sb.append("解码失败: ${resource.id}\n"); null
                            } ?: continue
                            // 无限循环：动画永久播，不依赖 repeatCount=0 的重启
                            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            instances.add(RenderInstance(ref, drawable, resource.info))
                        }
                        if (instances.isNotEmpty()) newCache[animId] = instances
                    }

                    if (gen != loadGeneration) return@Thread

                    handler.post {
                        if (destroyed || gen != loadGeneration) return@post
                        cleanupAnimCallbacks()
                        animRuntimeCache.clear()

                        // 仅主实例注册回调（用于调试计数）
                        for ((_, instances) in newCache) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                try {
                                    instances.firstOrNull()?.drawable?.registerAnimationCallback(animCallback)
                                } catch (_: Exception) {}
                            }
                        }

                        config = cfg
                        animRuntimeCache.putAll(newCache)
                        loadErrorMsg = sb.toString()
                        readGlobalPrefs()
                        forceShowDebug = false
                        engineStartTime = SystemClock.elapsedRealtime()

                        playback.reset()
                        enterState(playback.currentStateId())

                        ready = true
                        registerSensorsIfNeeded()
                        if (visible) {
                            handler.removeCallbacks(drawRunnable)
                            handler.post(drawRunnable)
                        }
                    }
                } catch (e: Exception) {
                    if (gen != loadGeneration) return@Thread
                    e.printStackTrace()
                    handler.post {
                        if (destroyed || gen != loadGeneration) return@post
                        loadErrorMsg = "加载异常:\n${e.message}"
                        ready = true
                        if (visible) {
                            handler.removeCallbacks(drawRunnable)
                            handler.post(drawRunnable)
                        }
                    }
                }
            }.also { it.start() }
        }

        private fun cleanupAnimCallbacks() {
            for (instances in animRuntimeCache.values) {
                for (inst in instances) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try { inst.drawable.unregisterAnimationCallback(animCallback) } catch (_: Exception) {}
                    }
                    try { inst.drawable.stop() } catch (_: Exception) {}
                }
            }
        }

        // ==================== 解码 ====================

        private fun decodeAnimatedDrawable(file: File): AnimatedImageDrawable? {
            if (!file.exists() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            return try {
                val dm = resources.displayMetrics
                val maxDim = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
                val source = ImageDecoder.createSource(file)
                val listener = object : ImageDecoder.OnHeaderDecodedListener {
                    override fun onHeaderDecoded(
                        decoder: ImageDecoder, info: ImageDecoder.ImageInfo, src: ImageDecoder.Source
                    ) {
                        val w = info.size.width
                        val h = info.size.height
                        val longSide = maxOf(w, h)
                        if (longSide > maxDim && longSide > 0) {
                            val s = maxDim.toFloat() / longSide
                            decoder.setTargetSize(
                                (w * s).toInt().coerceAtLeast(1),
                                (h * s).toInt().coerceAtLeast(1)
                            )
                        }
                    }
                }
                ImageDecoder.decodeDrawable(source, listener) as? AnimatedImageDrawable
            } catch (e: Exception) {
                null
            }
        }

        // ==================== 状态切换（核心） ====================

        private fun enterState(stateId: String) {
            enterStateCount++
            cancelCycleTimer()
            currentRenderStateId = stateId
            val state = config.states[stateId]
            val animId = state?.animationId ?: ""
            currentRuntime = animRuntimeCache[animId] ?: emptyList()
            loadErrorMsg = if (currentRuntime.isEmpty()) {
                "无可用动画实例: state=$stateId"
            } else ""

            // stop + setVisible 让 drawable 从第一帧开始（即使之前播过）
            for (inst in currentRuntime) {
                try { inst.drawable.stop() } catch (_: Exception) {}
                try { inst.drawable.setVisible(true, true) } catch (_: Exception) {}
            }
            if (visible) {
                for (inst in currentRuntime) {
                    try { inst.drawable.start() } catch (_: Exception) {}
                }
            }

            scheduleNextCycle()
        }

        // 计划下一轮定时器。时长 = GifInfo.totalDelayMs（一轮）。
        // 到时时调用 onCycleComplete：
        //   - 未达目标圈数：只重启定时器（动画一直在播，不用重启）
        //   - 达到目标：切换状态（enterState 会取消旧定时器并启动新的）
        private fun scheduleNextCycle() {
            cancelCycleTimer()
            if (!visible || destroyed) return
            val totalDelay = currentRuntime.firstOrNull()?.info?.totalDelayMs ?: 0
            if (totalDelay <= 0) return

            val runnable = Runnable {
                cycleTimerCount++
                if (destroyed || !visible) return@Runnable
                val nextId = playback.onCycleComplete()
                if (nextId != currentRenderStateId) {
                    enterState(nextId)
                } else {
                    // 原地重播：动画没变，只重启定时器
                    scheduleNextCycle()
                }
            }
            cycleTimerRunnable = runnable
            handler.postDelayed(runnable, totalDelay.toLong())
        }

        private fun cancelCycleTimer() {
            cycleTimerRunnable?.let { handler.removeCallbacks(it) }
            cycleTimerRunnable = null
        }

        // ==================== 释放 ====================

        private fun isMemoryCramped(): Boolean {
            return try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                mi.lowMemory || mi.availMem < LOW_MEM_AVAIL_MB * 1024L * 1024L
            } catch (e: Exception) {
                false
            }
        }

        private fun releaseAnimRuntime(animId: String) {
            animRuntimeCache.remove(animId)?.forEach { inst ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { inst.drawable.unregisterAnimationCallback(animCallback) } catch (_: Exception) {}
                }
                try { inst.drawable.stop() } catch (_: Exception) {}
            }
        }

        private fun releaseNonCurrentCaches() {
            val keepAnimId = config.states[currentRenderStateId]?.animationId ?: ""
            val toRemove = animRuntimeCache.keys.filter { it != keepAnimId }.toList()
            for (id in toRemove) releaseAnimRuntime(id)
        }

        private fun maybeReleaseOnInvisible() {
            if (!isMemoryCramped()) return
            val defaultAnimId = config.states[config.defaultStateId]?.animationId ?: ""
            if (defaultAnimId.isEmpty()) return
            val toRemove = animRuntimeCache.keys.filter { it != defaultAnimId }.toList()
            if (toRemove.isEmpty()) return
            for (id in toRemove) releaseAnimRuntime(id)
            if (config.states[currentRenderStateId]?.animationId != defaultAnimId) {
                currentRuntime = emptyList()
                needsResetOnVisible = true
            }
        }

        // ==================== 传感器注册 ====================

        private fun registerSensorsIfNeeded() {
            if (sensorsRegistered || destroyed) return

            val needGravity = enableOrientation || enableShake || userShowDebug
            val needAccel = enableShake || userShowDebug

            if (!needGravity && !needAccel) return

            if (needGravity && gravitySensor != null) {
                sensorManager.registerListener(
                    this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL
                )
                gravityRegistered = true
            }
            if (needAccel && accelSensor != null) {
                sensorManager.registerListener(
                    this, accelSensor, SensorManager.SENSOR_DELAY_UI
                )
                accelRegistered = true
            }
            sensorsRegistered = gravityRegistered || accelRegistered
        }

        private fun unregisterSensors() {
            if (sensorsRegistered) {
                sensorManager.unregisterListener(this)
                sensorsRegistered = false
                gravityRegistered = false
                accelRegistered = false
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            motionTracker.onSensorChanged(event)
            if (!visible || destroyed) return

            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val s = motionTracker.state
                if (s.lastShakeElapsedMs > lastHandledShakeElapsed) {
                    lastHandledShakeElapsed = s.lastShakeElapsedMs
                    if (!enableShake) return
                    val hasShakeRule = config.eventRules.any { it.type == EventType.SHAKE }
                    if (!hasShakeRule) return
                    if (playback.handleEvent(EventType.SHAKE, "", s.orientation)) {
                        enterState(playback.currentStateId())
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        private fun handleOrientationChanged(newOrientation: DeviceOrientation) {
            if (destroyed || !visible) return
            if (newOrientation == DeviceOrientation.UNKNOWN) return
            if (!enableOrientation) return
            val hasOrientationRule = config.eventRules.any { it.type == EventType.ORIENTATION }
            if (!hasOrientationRule) return
            if (playback.handleEvent(EventType.ORIENTATION, newOrientation.name, newOrientation)) {
                enterState(playback.currentStateId())
            }
        }

        // ==================== 触摸 ====================

        override fun onTouchEvent(event: MotionEvent) {
            touchTracker.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val s = touchTracker.state
                if (s.lastClickText != lastHandledClickText && s.lastClickText != "无") {
                    lastHandledClickText = s.lastClickText
                    if (!enableClick) return
                    val hasClickRule = config.eventRules.any { it.type == EventType.CLICK }
                    if (!hasClickRule) return
                    if (playback.handleEvent(EventType.CLICK, "", motionTracker.state.orientation)) {
                        enterState(playback.currentStateId())
                    }
                }
            }
        }

        // ==================== 可见性 ====================

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                registerSensorsIfNeeded()
                if (needsResetOnVisible) {
                    needsResetOnVisible = false
                    playback.reset()
                    enterState(playback.currentStateId())
                } else {
                    for (inst in currentRuntime) {
                        try { inst.drawable.start() } catch (_: Exception) {}
                    }
                    // 恢复定时器（进入不可见时被取消）
                    scheduleNextCycle()
                }
                if (ready) {
                    handler.removeCallbacks(drawRunnable)
                    handler.post(drawRunnable)
                }
            } else {
                for (inst in currentRuntime) {
                    try { inst.drawable.stop() } catch (_: Exception) {}
                }
                handler.removeCallbacks(drawRunnable)
                cancelCycleTimer()
                unregisterSensors()
                maybeReleaseOnInvisible()
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            if (ready && visible && !destroyed) drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            destroyed = true
            visible = false
            ready = false
            handler.removeCallbacks(drawRunnable)
            cancelCycleTimer()
            loaderThread?.interrupt()
            loaderThread = null
            unregisterSensors()
            cleanupAnimCallbacks()
            animRuntimeCache.clear()
            currentRuntime = emptyList()
        }

        // ==================== 渲染 ====================

        private fun drawFrame(): Long {
            val holder = surfaceHolder
            val now = SystemClock.elapsedRealtime()

            if (lastFrameTime > 0) frameIntervalMs = now - lastFrameTime
            lastFrameTime = now

            if (now - lastMemoryUpdateTime > 1000) {
                Debug.getMemoryInfo(memInfo)
                currentMemMb = memInfo.totalPss / 1024
                val warmup = (now - engineStartTime) < 3000
                if (!warmup && loadErrorMsg.isEmpty() && currentMemMb > limitMemory) {
                    forceShowDebug = true
                }
                lastMemoryUpdateTime = now
            }

            var canvas: Canvas? = null
            val drawStartTime = System.nanoTime()
            var animDrawEndTime = 0L

            try {
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else holder.lockCanvas()
                if (canvas == null) return 33L
                canvas.drawColor(resolveCurrentBackgroundColor())

                if (currentRuntime.isNotEmpty()) {
                    val screenW = canvas.width
                    val screenH = canvas.height
                    for (inst in currentRuntime) {
                        val info = inst.info ?: continue
                        val bounds = computeBounds(inst.ref, info, screenW, screenH)
                        inst.drawable.setBounds(
                            bounds.left.toInt(), bounds.top.toInt(),
                            bounds.right.toInt(), bounds.bottom.toInt()
                        )
                        inst.drawable.draw(canvas)
                    }
                    animDrawEndTime = System.nanoTime()

                    val needShowDebug = userShowDebug || forceShowDebug || loadErrorMsg.isNotEmpty()
                    if (needShowDebug) {
                        val alarm = forceShowDebug || loadErrorMsg.isNotEmpty()
                        debugRenderer.drawDebugInfo(canvas, buildDebugState(now, alarm))
                    }
                } else {
                    debugRenderer.drawErrorInfo(canvas, loadErrorMsg)
                    debugRenderer.drawDebugInfo(canvas, buildDebugState(now, true))
                    animDrawEndTime = System.nanoTime()
                }

                val primaryInfo = currentRuntime.firstOrNull()?.info
                return if (primaryInfo != null && primaryInfo.frameCount > 0 && primaryInfo.totalDelayMs > 0) {
                    val elapsed = ((now - playback.stateEnterTimeMs()) % primaryInfo.totalDelayMs).toInt()
                    primaryInfo.nextFrameDelayMs(elapsed, primaryInfo.totalDelayMs)
                } else {
                    primaryInfo?.frameIntervalMs(0) ?: 33L
                }
            } catch (e: Exception) {
                return 33L
            } finally {
                if (canvas != null) {
                    drawTimeMs = (animDrawEndTime - drawStartTime) / 1_000_000f
                    val warmup = (now - engineStartTime) < 3000
                    if (!warmup && loadErrorMsg.isEmpty() && drawTimeMs > limitDrawTime) {
                        forceShowDebug = true
                    }
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
        }

        private fun resolveCurrentBackgroundColor(): Int {
            val animId = config.states[currentRenderStateId]?.animationId ?: return Color.BLACK
            val anim = config.animations[animId] ?: return Color.BLACK
            return parseColorSafe(anim.backgroundColor) ?: Color.BLACK
        }

        private fun computeBounds(
            ref: AnimationResourceRef, info: GifInfo, screenW: Int, screenH: Int
        ): RectF {
            val origW = info.width.toFloat().coerceAtLeast(1f)
            val origH = info.height.toFloat().coerceAtLeast(1f)

            val (scaledW, scaledH) = when (ref.scaleMode) {
                ScaleMode.PERCENT -> {
                    val s = (ref.scaleValue / 100f).coerceAtLeast(0.01f)
                    origW * s to origH * s
                }
                ScaleMode.PIXEL -> {
                    val targetW = ref.scaleValue.coerceAtLeast(1f)
                    targetW to origH * (targetW / origW)
                }
            }

            val (ax, ay) = ref.anchor.normalized()
            val (tx, ty) = ref.targetAnchor.normalized()

            val targetX = screenW * tx + ref.offsetX
            val targetY = screenH * ty + ref.offsetY

            val left = targetX - scaledW * ax
            val top = targetY - scaledH * ay
            return RectF(left, top, left + scaledW, top + scaledH)
        }

        private fun buildDebugState(now: Long, isAlarm: Boolean): DebugOverlayRenderer.DebugState {
            val primaryInfo = currentRuntime.firstOrNull()?.info
            val frameIdx: Int = if (primaryInfo != null && primaryInfo.frameCount > 0 && primaryInfo.totalDelayMs > 0) {
                val elapsed = (now - playback.stateEnterTimeMs()) % primaryInfo.totalDelayMs
                var idx = primaryInfo.frameEndTimes.indexOfFirst { it > elapsed }
                if (idx == -1) idx = primaryInfo.frameCount - 1
                idx
            } else -1

            val motion = motionTracker.state
            val touch = touchTracker.state

            val state = config.states[playback.currentStateId()]
            val anim = state?.let { config.animations[it.animationId] }

            val sensorStatus = when {
                gravityRegistered && accelRegistered -> "GRAVITY+ACCEL"
                gravityRegistered -> "仅GRAVITY"
                accelRegistered -> "仅ACCEL"
                else -> "未注册"
            }

            return DebugOverlayRenderer.DebugState(
                stateName = if (state != null) {
                    formatStateDisplay(state)
                } else {
                    playback.currentStateId().ifEmpty { "None" }
                },
                currentPlayCount = playback.completedCycles(),
                targetSelfPlay = playback.targetCycles(),
                frameIndex = frameIdx,
                frameCount = primaryInfo?.frameCount ?: 0,
                drawTimeMs = drawTimeMs,
                limitDrawTime = limitDrawTime,
                currentMemMb = currentMemMb,
                limitMemory = limitMemory,
                frameIntervalMs = frameIntervalMs,
                isAlarm = isAlarm,
                hasError = loadErrorMsg.isNotEmpty(),
                frameIndexIsEstimate = true,
                animationDisplay = if (anim != null) formatAnimDisplay(anim) else "无",
                postureText = motion.postureText,
                lastShakeText = motion.lastShakeText,
                gForce = motion.gForce,
                shakeThresholdG = motionTracker.shakeThresholdG,
                sensorStatus = sensorStatus,
                isShaking = motionTracker.isShaking(),
                isTouching = touch.isTouching,
                onAnimationEndCount = onAnimationEndCount,
                enterStateCount = enterStateCount,
                cycleTimerCount = cycleTimerCount
            )
        }
    }
}