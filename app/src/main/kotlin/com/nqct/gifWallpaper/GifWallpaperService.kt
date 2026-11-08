package com.nqct.gifWallpaper

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
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

// GIF 状态机动态壁纸服务（android-gif-drawable 自播 + 定时器驱动状态切换）。
//
// 【架构】
//   - 每个动画一个 AnimRuntime，含每个资源引用一个独立的 GifDrawable 实例。
//   - GifDrawable 由 start() 自己推进帧，不做每帧 seek。
//   - 状态切换由定时器驱动（时长 = 一圈实际时长，含用户自定义速度）。
//   - 切换时：旧 AnimRuntime.stop() → 新 AnimRuntime.reset()（seekToFrame(0)）→ start()
//
// 【内存策略】全量预加载 + trim 时释放非当前。trim 释放后切回时自动异步重载。
//
// 【日志】普通（i / w）、性能（perf）、错误（e）三类。
//   卡顿时输出当前帧位置；内存超限时输出各动画实例占用。
class GifWallpaperService : WallpaperService() {

    @Volatile private var currentEngine: GifEngine? = null

    override fun onCreateEngine(): Engine = GifEngine().also { currentEngine = it }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        currentEngine?.handleTrimMemory(level)
    }

    private companion object {
        // TRIM_MEMORY_MODERATE 自 API 34 起 deprecated，但系统仍会回调此级别。
        // 集中一处 @Suppress，方便未来复查。
        @Suppress("DEPRECATION")
        val TRIM_LEVEL_RELEASE: Int = ComponentCallbacks2.TRIM_MEMORY_MODERATE
    }

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

        private val animRuntimeCache = mutableMapOf<String, AnimRuntime>()
        private var currentRuntime: AnimRuntime? = null
        private var currentRenderStateId: String = ""

        private var pendingReloadAnimId: String? = null
        private var pendingReloadStateId: String? = null
        private var pendingReloadGen = 0

        private var loaderThread: Thread? = null
        private var prefs: SharedPreferences? = null

        // ==================== 周期定时器 ====================
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
        private var lastStatusLogTime = 0L
        private var currentMemMb = 0
        private var loadErrorMsg = ""

        private var infoMsg: String = ""
        private var infoMsgUntil: Long = 0L

        @Volatile private var enterStateCount = 0
        @Volatile private var cycleTimerCount = 0

        private val debugRenderer = DebugOverlayRenderer()
        private val memInfo = Debug.MemoryInfo()

        // ==================== 并发 & 内存策略 ====================
        @Volatile private var loadGeneration = 0
        private var needsResetOnVisible = false
        private val LOW_MEM_AVAIL_MB = 150L

        // ==================== 渲染循环 ====================

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

            LogBuffer.i("Engine 创建 (gravity=${gravitySensor != null}, accel=${accelSensor != null})")
        }

        override fun onDestroy() {
            super.onDestroy()
            LogBuffer.i("Engine 销毁")
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            unregisterSensors()
            cancelCycleTimer()
            releaseAllRuntimes()
        }

        private fun readGlobalPrefs() {
            val p = prefs ?: return
            userShowDebug = p.getBoolean("showDebug", false)
            limitDrawTime = p.getInt("limitDraw", 20)
            limitMemory = p.getInt("limitMem", 150)
            enableOrientation = p.getBoolean("enableOrientation", true)
            enableShake = p.getBoolean("enableShake", true)
            enableClick = p.getBoolean("enableClick", true)
            motionTracker.setShakeThreshold(p.getFloat("shakeSensitivity", 2.4f))
            motionTracker.setShakeCooldown(p.getLong("shakeCooldownMs", 1200L))
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
                "enableOrientation" -> handler.post {
                    enableOrientation = sharedPreferences.getBoolean("enableOrientation", true)
                    if (!sensorsRegistered && visible) registerSensorsIfNeeded()
                }
                "enableShake" -> handler.post {
                    enableShake = sharedPreferences.getBoolean("enableShake", true)
                    if (!sensorsRegistered && visible) registerSensorsIfNeeded()
                }
                "enableClick" -> handler.post {
                    enableClick = sharedPreferences.getBoolean("enableClick", true)
                }
                "shakeSensitivity" -> handler.post {
                    motionTracker.setShakeThreshold(sharedPreferences.getFloat("shakeSensitivity", 2.4f))
                }
                "shakeCooldownMs" -> handler.post {
                    motionTracker.setShakeCooldown(sharedPreferences.getLong("shakeCooldownMs", 1200L))
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
            if (level >= TRIM_LEVEL_RELEASE) {
                LogBuffer.w("onTrimMemory($level)：释放非当前动画实例")
                releaseNonCurrentRuntimes()
            }
        }

        // ==================== 后台加载 ====================

        private fun loadInBackground() {
            val gen = ++loadGeneration
            loaderThread?.interrupt()
            loaderThread = Thread {
                try {
                    LogBuffer.i("开始加载配置")
                    var cfg = ProjectConfig()
                    var cfgMsg: String? = null
                    when (val r = ProjectConfigStore.load(this@GifWallpaperService)) {
                        is ProjectConfigStore.LoadResult.Ok -> cfg = r.config
                        is ProjectConfigStore.LoadResult.Corrupted -> {
                            cfgMsg = "配置损坏: ${r.message}\n(未应用，等待用户处理)"
                            LogBuffer.e("配置损坏: ${r.message}")
                        }
                    }

                    val dir = ProjectConfigStore.animDir(this@GifWallpaperService)
                    val newCache = mutableMapOf<String, AnimRuntime>()
                    val sb = StringBuilder()
                    if (cfgMsg != null) sb.append(cfgMsg).append("\n")
                    if (cfg.states.isEmpty()) sb.append("状态为空\n")

                    for ((animId, anim) in cfg.animations) {
                        if (gen != loadGeneration) return@Thread
                        if (Thread.currentThread().isInterrupted) return@Thread
                        val instances = mutableListOf<AnimResourceInstance>()
                        for (ref in anim.resources) {
                            val resource = cfg.resources[ref.resourceId] ?: continue
                            val file = File(dir, resource.fileName)
                            // 传入 ResourceItem，让其按用户配置的速度应用
                            val inst = AnimResourceInstance.fromFile(resource.id, ref, file, resource)
                            if (inst.gifDrawable == null) {
                                sb.append("解码失败: ${resource.id}\n")
                                LogBuffer.e("解码失败: ${resource.id}")
                            }
                            instances.add(inst)
                        }
                        if (instances.isNotEmpty()) {
                            val runtime = AnimRuntime(animId, instances)
                            newCache[animId] = runtime
                            LogBuffer.i(
                                "动画 $animId 预加载 ${instances.size} 个实例，"
                                + "内存约 ${runtime.allocationBytes / 1024 / 1024}MB, "
                                + "一圈时长 ${runtime.totalDurationMs}ms"
                            )
                        }
                    }

                    if (gen != loadGeneration) return@Thread

                    handler.post {
                        if (destroyed || gen != loadGeneration) return@post
                        releaseAllRuntimes()
                        animRuntimeCache.putAll(newCache)

                        config = cfg
                        loadErrorMsg = sb.toString()
                        readGlobalPrefs()
                        forceShowDebug = false
                        engineStartTime = SystemClock.elapsedRealtime()

                        playback.reset()
                        enterState(playback.currentStateId(), "加载完成")

                        ready = true
                        registerSensorsIfNeeded()
                        if (visible) {
                            handler.removeCallbacks(drawRunnable)
                            lastFrameTime = 0L
                            handler.post(drawRunnable)
                        }
                        LogBuffer.i("加载完成: ${cfg.states.size} 状态, ${cfg.animations.size} 动画")
                    }
                } catch (e: Exception) {
                    if (gen != loadGeneration) return@Thread
                    LogBuffer.e("加载异常", e)
                    e.printStackTrace()
                    handler.post {
                        if (destroyed || gen != loadGeneration) return@post
                        loadErrorMsg = "加载异常:\n${e.message}"
                        ready = true
                        if (visible) {
                            handler.removeCallbacks(drawRunnable)
                            lastFrameTime = 0L
                            handler.post(drawRunnable)
                        }
                    }
                }
            }.also { it.start() }
        }

        private fun releaseAllRuntimes() {
            var totalBytes = 0L
            for (rt in animRuntimeCache.values) {
                totalBytes += rt.allocationBytes
                rt.recycle()
            }
            animRuntimeCache.clear()
            currentRuntime = null
            pendingReloadAnimId = null
            pendingReloadStateId = null
            if (totalBytes > 0) {
                LogBuffer.i("释放全部运行时实例: ${totalBytes / 1024 / 1024}MB")
            }
        }

        private fun releaseNonCurrentRuntimes() {
            val keepAnimId = config.states[currentRenderStateId]?.animationId ?: ""
            val pending = pendingReloadAnimId
            val toRemove = animRuntimeCache.keys.filter { it != keepAnimId && it != pending }
            for (id in toRemove) {
                animRuntimeCache.remove(id)?.recycle()
            }
            if (toRemove.isNotEmpty()) {
                LogBuffer.w("释放 ${toRemove.size} 个非当前动画实例")
            }
        }

        // ==================== 状态切换 ====================

        private fun enterState(stateId: String, reason: String) {
            val t0 = SystemClock.elapsedRealtime()
            enterStateCount++
            cancelCycleTimer()
            currentRuntime?.stop()

            val prevId = currentRenderStateId
            currentRenderStateId = stateId
            val animId = config.states[stateId]?.animationId ?: ""

            val cached = if (animId.isNotEmpty()) animRuntimeCache[animId] else null
            if (animId.isNotEmpty() && cached == null) {
                currentRuntime = null
                loadErrorMsg = ""
                startReloadAnimation(animId, stateId)
                if (prevId != stateId) {
                    LogBuffer.i("状态切换: ${prevId.ifEmpty { "(初始)" }} -> $stateId ($reason)，动画缺失需重载")
                }
                return
            }

            currentRuntime = cached
            loadErrorMsg = if (cached == null || cached.instances.isEmpty()) {
                "无可用动画: state=$stateId"
            } else ""

            currentRuntime?.reset()
            if (visible) currentRuntime?.start()

            lastFrameTime = 0L
            scheduleCycleTimer()

            if (prevId != stateId) {
                LogBuffer.i("状态切换: ${prevId.ifEmpty { "(初始)" }} -> $stateId ($reason)")
            }

            val cost = SystemClock.elapsedRealtime() - t0
            if (cost > 50) {
                LogBuffer.perf("状态切换耗时 ${cost}ms: ${prevId.ifEmpty { "(初始)" }} -> $stateId")
            }
        }

        private fun startReloadAnimation(animId: String, stateId: String) {
            if (pendingReloadAnimId == animId) {
                LogBuffer.d("已有相同动画在重载中: $animId")
                return
            }
            pendingReloadAnimId = animId
            pendingReloadStateId = stateId
            val gen = ++pendingReloadGen
            val startMs = SystemClock.elapsedRealtime()

            setInfoMessage("⟳ 正在重新加载动画: $animId", 5000L)
            LogBuffer.w("动画被系统释放，开始异步重载: $animId")

            Thread {
                val anim = config.animations[animId]
                val instances = mutableListOf<AnimResourceInstance>()
                if (anim != null) {
                    val dir = ProjectConfigStore.animDir(this@GifWallpaperService)
                    for (ref in anim.resources) {
                        val resource = config.resources[ref.resourceId] ?: continue
                        val file = File(dir, resource.fileName)
                        val inst = AnimResourceInstance.fromFile(resource.id, ref, file, resource)
                        instances.add(inst)
                    }
                }
                handler.post {
                    if (destroyed || gen != pendingReloadGen) return@post
                    pendingReloadAnimId = null
                    pendingReloadStateId = null
                    val cost = SystemClock.elapsedRealtime() - startMs

                    val ok = instances.isNotEmpty() && instances.any { it.gifDrawable != null }
                    if (!ok) {
                        setInfoMessage("✗ 重新加载失败: $animId", 8000L)
                        LogBuffer.e("动画重新加载失败: $animId (耗时 ${cost}ms)")
                        return@post
                    }

                    val runtime = AnimRuntime(animId, instances)
                    animRuntimeCache[animId] = runtime
                    LogBuffer.i("动画重新加载完成: $animId (${runtime.allocationBytes / 1024 / 1024}MB, 耗时 ${cost}ms)")
                    if (cost > 100) {
                        LogBuffer.perf("重载耗时较长 ${cost}ms: $animId")
                    }

                    if (currentRenderStateId == stateId) {
                        currentRuntime = runtime
                        loadErrorMsg = ""
                        setInfoMessage("✓ 已重新加载: $animId", 5000L)
                        runtime.reset()
                        if (visible) runtime.start()
                        lastFrameTime = 0L
                        scheduleCycleTimer()
                    } else {
                        setInfoMessage("✓ 已重新加载: $animId（当前已切走）", 3000L)
                    }
                }
            }.start()
        }

        private fun setInfoMessage(msg: String, durationMs: Long) {
            infoMsg = msg
            infoMsgUntil = SystemClock.elapsedRealtime() + durationMs
        }

        private fun scheduleCycleTimer() {
            cancelCycleTimer()
            if (!visible || destroyed) return
            val duration = currentRuntime?.totalDurationMs ?: 0L
            if (duration <= 0) return

            val runnable = Runnable {
                cycleTimerCount++
                if (destroyed || !visible) return@Runnable
                val nextId = playback.onCycleComplete()
                if (nextId != currentRenderStateId) {
                    enterState(nextId, "周期完成")
                } else {
                    currentRuntime?.reset()
                    currentRuntime?.start()
                    scheduleCycleTimer()
                }
            }
            cycleTimerRunnable = runnable
            handler.postDelayed(runnable, duration)
        }

        private fun cancelCycleTimer() {
            cycleTimerRunnable?.let { handler.removeCallbacks(it) }
            cycleTimerRunnable = null
        }

        // ==================== 传感器 ====================

        private fun registerSensorsIfNeeded() {
            if (sensorsRegistered || destroyed) return
            val needGravity = enableOrientation || enableShake || userShowDebug
            val needAccel = enableShake || userShowDebug
            if (!needGravity && !needAccel) return
            if (needGravity && gravitySensor != null) {
                sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL)
                gravityRegistered = true
            }
            if (needAccel && accelSensor != null) {
                sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
                accelRegistered = true
            }
            sensorsRegistered = gravityRegistered || accelRegistered
            LogBuffer.i("传感器注册: gravity=$gravityRegistered, accel=$accelRegistered")
        }

        private fun unregisterSensors() {
            if (sensorsRegistered) {
                sensorManager.unregisterListener(this)
                sensorsRegistered = false
                gravityRegistered = false
                accelRegistered = false
                LogBuffer.i("传感器注销")
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
                    LogBuffer.i("摇一摇触发 (g=${"%.2f".format(s.gForce)}, 方向=${s.postureText})")
                    if (playback.handleEvent(EventType.SHAKE, "", s.orientation)) {
                        enterState(playback.currentStateId(), "摇一摇")
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
            LogBuffer.i("方向变化: ${newOrientation.label}")
            if (playback.handleEvent(EventType.ORIENTATION, newOrientation.name, newOrientation)) {
                enterState(playback.currentStateId(), "方向=${newOrientation.label}")
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
                    LogBuffer.i("点击触发 (${s.x.toInt()}, ${s.y.toInt()})")
                    if (playback.handleEvent(EventType.CLICK, "", motionTracker.state.orientation)) {
                        enterState(playback.currentStateId(), "点击")
                    }
                }
            }
        }

        // ==================== 可见性 ====================

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            LogBuffer.i("可见性: $visible")
            if (visible) {
                registerSensorsIfNeeded()
                if (needsResetOnVisible) {
                    needsResetOnVisible = false
                    playback.reset()
                    enterState(playback.currentStateId(), "恢复可见")
                } else {
                    currentRuntime?.start()
                    scheduleCycleTimer()
                }
                lastFrameTime = 0L
                if (ready) {
                    handler.removeCallbacks(drawRunnable)
                    handler.post(drawRunnable)
                }
            } else {
                handler.removeCallbacks(drawRunnable)
                cancelCycleTimer()
                currentRuntime?.stop()
                unregisterSensors()
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            if (ready && visible && !destroyed) drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            LogBuffer.i("Surface 销毁")
            destroyed = true
            visible = false
            ready = false
            handler.removeCallbacks(drawRunnable)
            cancelCycleTimer()
            loaderThread?.interrupt()
            loaderThread = null
            unregisterSensors()
            releaseAllRuntimes()
        }

        // ==================== 渲染 ====================

        private fun drawFrame(): Long {
            val holder = surfaceHolder
            val now = SystemClock.elapsedRealtime()

            val deltaMs = if (lastFrameTime > 0) now - lastFrameTime else 0L
            frameIntervalMs = deltaMs
            lastFrameTime = now

            // 卡顿检测：帧间隔超过 100ms 视为异常，输出当前状态与帧位置
            if (deltaMs > 100) {
                val inst = currentRuntime?.instances?.firstOrNull()
                val frameIdx = try { inst?.gifDrawable?.currentFrameIndex ?: -1 } catch (_: Throwable) { -1 }
                val frameTotal = inst?.frameCount ?: 0
                val frameInfo = if (frameIdx >= 0 && frameTotal > 0) "${frameIdx + 1}/$frameTotal" else "未知"
                LogBuffer.perf(
                    "卡顿: 帧间隔 ${deltaMs}ms, state=$currentRenderStateId, 帧=$frameInfo"
                )
            }

            if (now - lastMemoryUpdateTime > 1000) {
                Debug.getMemoryInfo(memInfo)
                currentMemMb = memInfo.totalPss / 1024
                val warmup = (now - engineStartTime) < 3000
                if (!warmup && loadErrorMsg.isEmpty() && currentMemMb > limitMemory) {
                    forceShowDebug = true
                    // 列出每个动画实例的占用
                    val breakdown = if (animRuntimeCache.isEmpty()) "（无实例）"
                    else animRuntimeCache.entries.joinToString(", ") { (id, rt) ->
                        "$id=${rt.allocationBytes / 1024 / 1024}MB"
                    }
                    LogBuffer.perf(
                        "内存超限: ${currentMemMb}MB > ${limitMemory}MB, state=$currentRenderStateId\n" +
                        "  实例占用: $breakdown"
                    )
                }
                lastMemoryUpdateTime = now
            }

            if (now - lastStatusLogTime > 5000) {
                lastStatusLogTime = now
                val rt = currentRuntime
                LogBuffer.i(
                    "运行: state=$currentRenderStateId, 帧耗时=${"%.1f".format(drawTimeMs)}ms, "
                    + "内存=${currentMemMb}MB, 实例内存=${(rt?.allocationBytes ?: 0L) / 1024 / 1024}MB, "
                    + "帧间隔=${frameIntervalMs}ms"
                )
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

                val rt = currentRuntime
                if (rt != null && rt.instances.isNotEmpty()) {
                    val screenW = canvas.width
                    val screenH = canvas.height
                    for (inst in rt.instances) {
                        val info = inst.info ?: continue
                        val bounds = computeBounds(inst.ref, info, screenW, screenH)
                        inst.setBounds(
                            bounds.left.toInt(), bounds.top.toInt(),
                            bounds.right.toInt(), bounds.bottom.toInt()
                        )
                        inst.draw(canvas)
                    }
                    animDrawEndTime = System.nanoTime()

                    val needShowDebug = userShowDebug || forceShowDebug || loadErrorMsg.isNotEmpty()
                    || (now < infoMsgUntil)
                    if (needShowDebug) {
                        val alarm = forceShowDebug || loadErrorMsg.isNotEmpty()
                        debugRenderer.drawDebugInfo(canvas, buildDebugState(now, alarm))
                    }
                } else {
                    debugRenderer.drawErrorInfo(canvas, loadErrorMsg)
                    debugRenderer.drawDebugInfo(canvas, buildDebugState(now, true))
                    animDrawEndTime = System.nanoTime()
                }

                return computeNextFrameDelay()
            } catch (e: Exception) {
                LogBuffer.e("drawFrame 异常", e)
                return 33L
            } finally {
                if (canvas != null) {
                    drawTimeMs = (animDrawEndTime - drawStartTime) / 1_000_000f
                    val warmup = (now - engineStartTime) < 3000
                    if (!warmup && loadErrorMsg.isEmpty() && drawTimeMs > limitDrawTime) {
                        forceShowDebug = true
                        LogBuffer.perf(
                            "帧耗时超限: ${"%.1f".format(drawTimeMs)}ms > ${limitDrawTime}ms, state=$currentRenderStateId"
                        )
                    }
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
        }

        // 下一帧调度：直接用当前动画期望的每帧时长（受用户速度配置影响）。
        // 与定时器时长（totalDurationMs）源自同一份 ResourceItem 数据，严格一致。
        // 上限放宽到 200ms，容纳用户配置的大帧间隔（如 100ms/帧）。
        private fun computeNextFrameDelay(): Long {
            val rt = currentRuntime ?: return 33L
            return rt.frameIntervalMs().coerceIn(8L, 200L)
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
            val primaryInst = currentRuntime?.instances?.firstOrNull()
            val frameIdx = try {
                primaryInst?.gifDrawable?.currentFrameIndex ?: -1
            } catch (_: Throwable) { -1 }
            val frameTotal = primaryInst?.frameCount ?: 0

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

            val infoNow = if (now < infoMsgUntil) infoMsg else ""

            return DebugOverlayRenderer.DebugState(
                stateName = if (state != null) formatStateDisplay(state) else playback.currentStateId().ifEmpty { "None" },
                currentPlayCount = playback.completedCycles(),
                targetSelfPlay = playback.targetCycles(),
                frameIndex = frameIdx,
                frameCount = frameTotal,
                drawTimeMs = drawTimeMs,
                limitDrawTime = limitDrawTime,
                currentMemMb = currentMemMb,
                limitMemory = limitMemory,
                frameIntervalMs = frameIntervalMs,
                isAlarm = isAlarm,
                hasError = loadErrorMsg.isNotEmpty(),
                frameIndexIsEstimate = false,
                animationDisplay = if (anim != null) formatAnimDisplay(anim) else "无",
                postureText = motion.postureText,
                lastShakeText = motion.lastShakeText,
                gForce = motion.gForce,
                shakeThresholdG = motionTracker.shakeThresholdG,
                sensorStatus = sensorStatus,
                isShaking = motionTracker.isShaking(),
                isTouching = touch.isTouching,
                enterStateCount = enterStateCount,
                cycleTimerCount = cycleTimerCount,
                infoMessage = infoNow
            )
        }
    }
}
