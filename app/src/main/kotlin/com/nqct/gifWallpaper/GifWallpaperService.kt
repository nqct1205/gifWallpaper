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

/**
 * GIF 状态机动态壁纸服务。
 *
 * 【分层】
 *  - [PlaybackController]：纯状态逻辑（周期、权重转移、事件切换）。
 *  - [GifEngine]：传感器接入、Drawable 生命周期、渲染、内存策略。
 *
 * 【多实例策略】同一动画内每个 [AnimationResourceRef] 拥有**独立的**
 * AnimatedImageDrawable 实例。原因：同一资源被同一动画引用多次时，若共享实例，
 * 播放进度会被强制同步，无法各自定位/缩放。代价是内存增加。
 * 后续可改为“共享解码数据 + 手动帧索引”（如 GifTexImage2D），当前先用多实例。
 *
 * 【多资源同步】同一动画内各资源假定长度一致（用户复用同一动画）。
 * 以第一个资源（主实例）的 onAnimationEnd 作为一圈结束信号；其他实例不注册回调。
 * 若长度不一致，按最短者计（主实例播完即视为一圈结束）。
 *
 * 【repeatCount】所有 drawable 设为 0（只播一遍）。理由：repeatCount 是“重复次数”，
 * 0 表示播 1 遍；外部 [PlaybackController] 按圈计数，两者 1:1 对应。
 *
 * 【背景色】每帧以当前状态所引用动画的 [AnimationItem.backgroundColor] 铺底；
 * 空串或解析失败回退黑色。见 [resolveCurrentBackgroundColor]。
 *
 * 【调试面板显示】状态与动画使用"名字 (id)"格式，见 [buildDebugState]。
 *
 * 【内存策略】
 *  - 不可见时若内存紧张（lowMemory 或 availMem < 150MB）：只保留当前动画，标记需重置。
 *  - 可见时 TRIM_MEMORY_MODERATE 及以上：释放非当前动画实例。
 *  - 恢复可见时若被释放过，从默认状态重播。
 */
class GifWallpaperService : WallpaperService() {

    @Volatile private var currentEngine: GifEngine? = null

    override fun onCreateEngine(): Engine = GifEngine().also { currentEngine = it }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        currentEngine?.handleTrimMemory(level)
    }

    /**
     * 渲染实例：每个资源引用对应一个独立的 AnimatedImageDrawable。
     *
     * 放在 [GifEngine] 外部，因为 Kotlin 的 inner class 内部不允许声明嵌套类/数据类。
     */
    private data class RenderInstance(
        val ref: AnimationResourceRef,
        val drawable: AnimatedImageDrawable,
        val info: GifInfo?
    )

    inner class GifEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener,
        SensorEventListener {

        // ======================== 线程与生命周期 ========================
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var visible = false
        @Volatile private var destroyed = false
        @Volatile private var ready = false

        // ======================== 数据 ========================
        private var config: ProjectConfig = ProjectConfig()
        private val playback = PlaybackController { config }

        private val animRuntimeCache = mutableMapOf<String, List<RenderInstance>>()
        private var currentRuntime: List<RenderInstance> = emptyList()
        private var currentRenderStateId: String = ""

        private var loaderThread: Thread? = null
        private var prefs: SharedPreferences? = null

        // ======================== 传感器 ========================
        private val sensorManager by lazy {
            getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }
        private val motionTracker = MotionTracker()
        private val touchTracker by lazy { TouchTracker(this@GifWallpaperService, 60f, 800L) }
        private var gravitySensor: Sensor? = null
        private var accelSensor: Sensor? = null
        private var sensorsRegistered = false
        private var lastOrientationParam: String? = null
        private var lastHandledShakeElapsed = 0L
        private var lastHandledClickText = ""

        // ======================== 调试 ========================
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

        private val debugRenderer = DebugOverlayRenderer()
        private val memInfo = Debug.MemoryInfo()

        // ======================== 并发 & 内存策略 ========================
        @Volatile private var loadGeneration = 0
        private var needsResetOnVisible = false
        private val LOW_MEM_AVAIL_MB = 150L

        // ======================== 回调 ========================

        private val animCallback = object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                if (destroyed || !visible) return
                val nextId = playback.onCycleComplete()
                if (nextId != currentRenderStateId) {
                    enterState(nextId)
                } else {
                    restartCurrentAnimation()
                }
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

        // ======================== 生命周期 ========================

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
            prefs?.registerOnSharedPreferenceChangeListener(this)
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            unregisterSensors()
            cleanupAnimCallbacks()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == "showDebug" || key == "limitDraw" || key == "limitMem") {
                handler.post {
                    userShowDebug = sharedPreferences?.getBoolean("showDebug", false) ?: false
                    limitDrawTime = sharedPreferences?.getInt("limitDraw", 20) ?: 20
                    limitMemory = sharedPreferences?.getInt("limitMem", 150) ?: 150
                    forceShowDebug = false
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

        /**
         * 可见时系统内存告急。
         *
         * 关于 [ComponentCallbacks2.TRIM_MEMORY_MODERATE] 的 deprecated 警告：
         * API 34 起该常量被标记为 deprecated。系统仍会回调此级别，
         * WallpaperService 场景下没有更精确的替代信号，故继续使用。
         *
         * 【刻意保留此警告】有意为之：留着编译器提示，作为未来复查的锚点。
         * 若某天升级 compileSdk 或调整内存策略，这行警告会主动提醒回来看此处。
         */
        fun handleTrimMemory(level: Int) {
            if (destroyed) return
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                releaseNonCurrentCaches()
            }
        }

        // ======================== 后台加载 ========================

        private fun loadInBackground() {
            val gen = ++loadGeneration
            loaderThread?.interrupt()
            loaderThread = Thread {
                try {
                    val uShowDebug = prefs?.getBoolean("showDebug", false) ?: false
                    val lDraw = prefs?.getInt("limitDraw", 20) ?: 20
                    val lMem = prefs?.getInt("limitMem", 150) ?: 150

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
                            drawable.repeatCount = 0
                            instances.add(RenderInstance(ref, drawable, resource.info))
                        }
                        if (instances.isNotEmpty()) newCache[animId] = instances
                    }

                    if (gen != loadGeneration) return@Thread

                    handler.post {
                        if (destroyed || gen != loadGeneration) return@post
                        cleanupAnimCallbacks()
                        animRuntimeCache.clear()

                        for ((_, instances) in newCache) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                instances.firstOrNull()?.drawable?.registerAnimationCallback(animCallback)
                            }
                        }

                        config = cfg
                        animRuntimeCache.putAll(newCache)
                        loadErrorMsg = sb.toString()
                        userShowDebug = uShowDebug
                        limitDrawTime = lDraw
                        limitMemory = lMem
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

        // ======================== 解码 ========================

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

        // ======================== 状态切换 ========================

        private fun enterState(stateId: String) {
            currentRenderStateId = stateId
            val state = config.states[stateId]
            val animId = state?.animationId ?: ""
            currentRuntime = animRuntimeCache[animId] ?: emptyList()
            loadErrorMsg = if (currentRuntime.isEmpty()) {
                "无可用动画实例: state=$stateId"
            } else ""

            for (inst in currentRuntime) {
                try { inst.drawable.stop() } catch (_: Exception) {}
                inst.drawable.setVisible(true, true)
            }
            if (visible) {
                for (inst in currentRuntime) {
                    try { inst.drawable.start() } catch (_: Exception) {}
                }
            }
        }

        private fun restartCurrentAnimation() {
            for (inst in currentRuntime) {
                try { inst.drawable.stop() } catch (_: Exception) {}
                inst.drawable.setVisible(true, true)
                if (visible) {
                    try { inst.drawable.start() } catch (_: Exception) {}
                }
            }
        }

        // ======================== 释放 ========================

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

        // ======================== 传感器 ========================

        private fun registerSensorsIfNeeded() {
            if (sensorsRegistered || destroyed) return
            val hasOrientationRule = config.eventRules.any { it.type == EventType.ORIENTATION }
            val hasShakeRule = config.eventRules.any { it.type == EventType.SHAKE }
            if (!hasOrientationRule && !hasShakeRule) return

            if (hasOrientationRule && gravitySensor != null) {
                sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            if (hasShakeRule && accelSensor != null) {
                sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
            }
            sensorsRegistered = true
        }

        private fun unregisterSensors() {
            if (sensorsRegistered) {
                sensorManager.unregisterListener(this)
                sensorsRegistered = false
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            motionTracker.onSensorChanged(event)
            if (!visible || destroyed) return

            if (event.sensor.type == Sensor.TYPE_GRAVITY) {
                val s = motionTracker.state
                val param = when {
                    s.gz > 0.7f -> EventRule.ORIENTATION_FORWARD
                    s.gz < -0.7f -> EventRule.ORIENTATION_REVERSE
                    else -> null
                }
                if (param != null && param != lastOrientationParam) {
                    lastOrientationParam = param
                    if (playback.handleEvent(EventType.ORIENTATION, param)) {
                        enterState(playback.currentStateId())
                    }
                }
            }

            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val s = motionTracker.state
                if (s.lastShakeElapsedMs > lastHandledShakeElapsed) {
                    lastHandledShakeElapsed = s.lastShakeElapsedMs
                    if (playback.handleEvent(EventType.SHAKE, "")) {
                        enterState(playback.currentStateId())
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        // ======================== 触摸 ========================

        override fun onTouchEvent(event: MotionEvent) {
            touchTracker.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val s = touchTracker.state
                if (s.lastClickText != lastHandledClickText && s.lastClickText != "无") {
                    lastHandledClickText = s.lastClickText
                    if (playback.handleEvent(EventType.CLICK, "")) {
                        enterState(playback.currentStateId())
                    }
                }
            }
        }

        // ======================== 可见性 ========================

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
            loaderThread?.interrupt()
            loaderThread = null
            unregisterSensors()
            cleanupAnimCallbacks()
            animRuntimeCache.clear()
            currentRuntime = emptyList()
        }

        // ======================== 渲染 ========================

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

        /**
         * 解析当前状态所引用动画的背景填充色。
         * 无背景色 / 解析失败时回退为黑色。
         */
        private fun resolveCurrentBackgroundColor(): Int {
            val animId = config.states[currentRenderStateId]?.animationId ?: return Color.BLACK
            val anim = config.animations[animId] ?: return Color.BLACK
            return parseColorSafe(anim.backgroundColor) ?: Color.BLACK
        }

        /**
         * 计算单个资源的绘制矩形。
         * anchor：GIF 上的点；targetAnchor：屏幕上的点；offset：微调。
         */
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

        /**
         * 构造调试面板数据。
         *
         * 【显示格式】状态与动画均使用"名字 (id)"格式，由 [formatStateDisplay] /
         * [formatAnimDisplay] 生成；无状态/无动画时给出明确文本。
         */
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

            // 当前状态对象与它引用的动画对象
            val state = config.states[playback.currentStateId()]
            val anim = state?.let { config.animations[it.animationId] }

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
                isShaking = motionTracker.isShaking(),
                isTouching = touch.isTouching
            )
        }
    }
}