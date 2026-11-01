package com.nqct.gifWallpaper

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

/**
 * GIF 状态机动态壁纸服务。
 *
 * 【内存策略（用户拍板）】
 *  - 平时不主动释放（切后台不释放、回来不重解码、不卡）。
 *  - 不可见时若系统内存紧张（lowMemory 或 availMem < 150MB）：只保留默认状态。
 *  - 可见时系统内存告急（TRIM_MEMORY_MODERATE 及以上）：只释放非当前状态，保当前显示。
 *  - `needsResetOnVisible` 仅在"当前动画被实际释放"时置 true，恢复可见时从默认重播。
 */
class GifWallpaperService : WallpaperService() {

    @Volatile private var currentEngine: GifEngine? = null

    override fun onCreateEngine(): Engine = GifEngine().also { currentEngine = it }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        currentEngine?.handleTrimMemory(level)
    }

    inner class GifEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        // ======================== 线程与生命周期 ========================
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var visible = false
        @Volatile private var destroyed = false
        @Volatile private var ready = false

        // ======================== 缓存 ========================
        private var stateMachine = StateMachine()
        private var animCache = mutableMapOf<String, AnimatedImageDrawable>()
        private var infoCache = mutableMapOf<String, GifInfo>()

        private var currentStateNode: StateNode? = null
        private var currentAnim: AnimatedImageDrawable? = null
        private var currentInfo: GifInfo? = null

        private var currentPlayCount = 0
        private var targetSelfPlay = 1
        private var currentLoopStartTime = 0L

        private var loaderThread: Thread? = null
        private var prefs: SharedPreferences? = null

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

        // ======================== 并发保护 & 内存策略 ========================
        @Volatile private var loadGeneration = 0
        private var needsResetOnVisible = false

        // 【修复】inner class 不能有 companion object，改为实例字段
        /** 不可见时剩余可用内存低于此值(MB)视为紧张，触发"只保留默认"释放 */
        private val LOW_MEM_AVAIL_MB = 150L

        // ======================== 回调 ========================

        private val animCallback = object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                super.onAnimationEnd(drawable)
                if (destroyed || !visible || currentStateNode == null) return

                currentPlayCount++
                if (currentPlayCount < targetSelfPlay) {
                    currentLoopStartTime = SystemClock.elapsedRealtime()
                    currentAnim?.setVisible(true, true)
                    currentAnim?.start()
                    return
                }
                switchToNextState()
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
            prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
            prefs?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
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

        // ======================== onTrimMemory ========================

        /**
         * 可见时系统内存告急。
         * 用户拍板：即便 COMPLETE 级别也保住"当前正在显示的"，只释放非当前。
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

                    // 加载 stateMachine（壁纸启动策略：损坏 → 留空 + 错误画面）
                    var sm = StateMachine()
                    var smCorruptedMsg: String? = null
                    when (val r = StateMachine.load(this@GifWallpaperService)) {
                        is LoadResult.Ok -> sm = r.sm
                        is LoadResult.Corrupted -> {
                            smCorruptedMsg = "配置损坏: ${r.message}\n(未应用，等待用户处理)"
                            sm = StateMachine()
                        }
                    }

                    val animDir = File(filesDir, "animations")
                    if (!animDir.exists()) animDir.mkdirs()
                    val files = animDir.listFiles { f -> f.extension.lowercase() == "gif" } ?: emptyArray()

                    val newAnimCache = mutableMapOf<String, AnimatedImageDrawable>()
                    val newInfoCache = mutableMapOf<String, GifInfo>()
                    val usedIds = sm.states.map { it.id }.toSet()

                    val sb = StringBuilder()
                    if (smCorruptedMsg != null) sb.append(smCorruptedMsg).append("\n")
                    if (sm.states.isEmpty()) sb.append("JSON 状态为空\n")

                    // 兜底：JSON 里没有任何状态时，退化为"所有磁盘文件都视为 state"
                    val effectiveIds = if (usedIds.isEmpty()) {
                        files.map { it.nameWithoutExtension }.toSet()
                    } else usedIds

                    for (file in files) {
                        if (gen != loadGeneration) return@Thread
                        if (Thread.currentThread().isInterrupted) return@Thread
                        val id = file.nameWithoutExtension
                        if (id in effectiveIds) {
                            val a = decodeAnimatedDrawable(file)
                            if (a != null) newAnimCache[id] = a else sb.append("解码失败: $id\n")
                            GifMetadataParser.parse(file)?.let { newInfoCache[id] = it }
                        }
                    }

                    if (gen != loadGeneration) return@Thread

                    handler.post {
                        if (destroyed || gen != loadGeneration) return@post

                        cleanupAnimCallbacks()
                        animCache.clear()
                        infoCache.clear()

                        for (anim in newAnimCache.values) {
                            anim.repeatCount = 0
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                anim.registerAnimationCallback(animCallback)
                            }
                        }

                        stateMachine = sm
                        animCache = newAnimCache
                        infoCache = newInfoCache
                        loadErrorMsg = sb.toString()

                        userShowDebug = uShowDebug
                        limitDrawTime = lDraw
                        limitMemory = lMem
                        forceShowDebug = false
                        engineStartTime = SystemClock.elapsedRealtime()

                        val startId = sm.initialStateId.takeIf { it.isNotEmpty() }
                            ?: sm.states.firstOrNull()?.id
                            ?: effectiveIds.firstOrNull()

                        if (startId != null && animCache.containsKey(startId)) {
                            forceState(startId)
                        } else if (animCache.isNotEmpty()) {
                            forceState(animCache.keys.first())
                        } else {
                            currentAnim = null
                            loadErrorMsg += "无任何有效状态"
                        }

                        ready = true
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

        /** 注销所有动画回调并停止播放（仅主线程调用）。 */
        private fun cleanupAnimCallbacks() {
            for (anim in animCache.values) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { anim.unregisterAnimationCallback(animCallback) } catch (_: Exception) {}
                }
                try { anim.stop() } catch (_: Exception) {}
            }
        }

        // ======================== 解码（含 setTargetSize） ========================

        private fun decodeAnimatedDrawable(file: File): AnimatedImageDrawable? {
            if (!file.exists() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            return try {
                val dm = resources.displayMetrics
                val maxDim = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
                val source = ImageDecoder.createSource(file)
                // 【修复】onHeaderDecoded 第三参数是非空 ImageDecoder.Source
                val listener = object : ImageDecoder.OnHeaderDecodedListener {
                    override fun onHeaderDecoded(
                        decoder: ImageDecoder,
                        info: ImageDecoder.ImageInfo,
                        src: ImageDecoder.Source
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

        private fun forceState(stateId: String) {
            val node = stateMachine.getState(stateId)
            if (node == null) {
                currentStateNode = null
                currentAnim = null
                loadErrorMsg = "状态机无此节点: $stateId\n(文件可能被删，或JSON错误)"
                return
            }
            currentStateNode = node

            val a = animCache[stateId]
            if (a == null) {
                currentAnim = null
                loadErrorMsg = "未加载: $stateId.gif\n(可能文件损坏或低于API 28)"
                return
            }

            currentAnim = a
            currentInfo = infoCache[stateId]
            loadErrorMsg = ""

            currentPlayCount = 0
            targetSelfPlay = if (node.maxSelfPlay > node.minSelfPlay) {
                (node.minSelfPlay..node.maxSelfPlay).random()
            } else node.minSelfPlay.coerceAtLeast(1)

            currentLoopStartTime = SystemClock.elapsedRealtime()
            currentAnim?.stop()
            currentAnim?.setVisible(true, true)
            if (visible) currentAnim?.start()
        }

        /** 切换到目标；若目标不在 cache 中，异步重载后再切。 */
        private fun switchTo(targetId: String) {
            if (animCache[targetId] == null) {
                currentAnim?.stop()
                reloadStateAsync(targetId) {
                    if (!destroyed) forceState(targetId)
                }
            } else {
                forceState(targetId)
            }
        }

        private fun switchToNextState() {
            val node = currentStateNode ?: return

            if (node.rules.isEmpty()) { switchTo(node.id); return }
            val totalWeight = node.rules.sumOf { it.weight.coerceAtLeast(0) }
            if (totalWeight <= 0) { switchTo(node.id); return }

            var randomVal = (1..totalWeight).random()
            var selectedTarget = node.id
            for (rule in node.rules) {
                randomVal -= rule.weight
                if (randomVal <= 0) { selectedTarget = rule.targetId; break }
            }
            switchTo(selectedTarget)
        }

        // ======================== 释放 & 重载 ========================

        private fun isMemoryCramped(): Boolean {
            return try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                mi.lowMemory || mi.availMem < LOW_MEM_AVAIL_MB * 1024L * 1024L
            } catch (e: Exception) {
                false // 判断失败视为充足（保守，不误杀用户体验）
            }
        }

        private fun removeAnimFromCache(id: String) {
            animCache.remove(id)?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { it.unregisterAnimationCallback(animCallback) } catch (_: Exception) {}
                }
                try { it.stop() } catch (_: Exception) {}
            }
        }

        /** 释放所有非当前状态的动画（保住当前显示）。 */
        private fun releaseNonCurrentCaches() {
            val keepId = currentStateNode?.id
            val toRemove = animCache.keys.filter { it != keepId }.toList()
            for (id in toRemove) removeAnimFromCache(id)
        }

        /**
         * 不可见时：若内存紧张，只保留默认状态。
         * 若当前动画恰好就是默认，则续上；否则标记 needsResetOnVisible。
         */
        private fun maybeReleaseOnInvisible() {
            if (!isMemoryCramped()) return
            val defaultId = stateMachine.initialStateId.ifEmpty {
                stateMachine.states.firstOrNull()?.id ?: ""
            }
            if (defaultId.isEmpty()) return
            val toRemove = animCache.keys.filter { it != defaultId }.toList()
            if (toRemove.isEmpty()) return
            for (id in toRemove) removeAnimFromCache(id)

            if (currentStateNode?.id != defaultId) {
                currentAnim = null
                currentInfo = null
                needsResetOnVisible = true
            }
        }

        /** 异步从磁盘重载单个状态，完成后回调 onLoaded（主线程）。 */
        private fun reloadStateAsync(id: String, onLoaded: () -> Unit) {
            Thread {
                try {
                    val file = File(filesDir, "$id.gif")
                    val a = decodeAnimatedDrawable(file)
                    if (a == null) {
                        handler.post {
                            if (destroyed) return@post
                            loadErrorMsg = "重载失败: $id.gif\n(可能文件损坏)"
                        }
                        return@Thread
                    }
                    val info = GifMetadataParser.parse(file)
                    handler.post {
                        if (destroyed) return@post
                        a.repeatCount = 0
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            a.registerAnimationCallback(animCallback)
                        }
                        animCache[id] = a
                        if (info != null) infoCache[id] = info
                        onLoaded()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }

        // ======================== 可见性 ========================

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                if (needsResetOnVisible) {
                    needsResetOnVisible = false
                    val defaultId = stateMachine.initialStateId.ifEmpty {
                        stateMachine.states.firstOrNull()?.id ?: ""
                    }
                    if (defaultId.isNotEmpty()) {
                        if (animCache[defaultId] == null) {
                            reloadStateAsync(defaultId) {
                                if (!destroyed && visible) forceState(defaultId)
                            }
                        } else {
                            forceState(defaultId)
                        }
                    }
                } else {
                    currentAnim?.start()
                }
                if (ready) {
                    handler.removeCallbacks(drawRunnable)
                    handler.post(drawRunnable)
                }
            } else {
                currentAnim?.stop()
                handler.removeCallbacks(drawRunnable)
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
            cleanupAnimCallbacks()
            animCache.clear()
            infoCache.clear()
            currentStateNode = null
            currentAnim = null
            currentInfo = null
        }

        // ======================== 渲染 ========================

        private fun drawFrame(): Long {
            val holder = surfaceHolder
            val anim = currentAnim
            val info = currentInfo
            val now = SystemClock.elapsedRealtime()

            if (lastFrameTime > 0) frameIntervalMs = now - lastFrameTime
            lastFrameTime = now

            // 内存检查（每秒一次）
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
                canvas.drawColor(Color.BLACK)

                if (anim != null) {
                    val screenW = canvas.width
                    val screenH = canvas.height
                    val dw = anim.intrinsicWidth
                    val dh = anim.intrinsicHeight
                    if (dw > 0 && dh > 0) {
                        val scale = minOf(screenW / dw.toFloat(), screenH / dh.toFloat())
                        val w = (dw * scale).toInt()
                        val h = (dh * scale).toInt()
                        val left = (screenW - w) / 2
                        val top = (screenH - h) / 2
                        anim.setBounds(left, top, left + w, top + h)
                        anim.draw(canvas)
                    }
                    animDrawEndTime = System.nanoTime()

                    val needShowDebug = userShowDebug || forceShowDebug || loadErrorMsg.isNotEmpty()
                    if (needShowDebug) {
                        val alarm = forceShowDebug || loadErrorMsg.isNotEmpty()
                        debugRenderer.drawDebugInfo(canvas, buildDebugState(info, now, alarm))
                    }
                } else {
                    debugRenderer.drawErrorInfo(canvas, loadErrorMsg)
                    debugRenderer.drawDebugInfo(canvas, buildDebugState(info, now, true))
                    animDrawEndTime = System.nanoTime()
                }

                // 精确帧率：按 GIF 内部时间轴推算下次重绘延迟
                return if (info != null && info.frameCount > 0 && info.totalDelayMs > 0) {
                    val elapsedInLoop = ((now - currentLoopStartTime) % info.totalDelayMs).toInt()
                    info.nextFrameDelayMs(elapsedInLoop, info.totalDelayMs)
                } else {
                    info?.frameIntervalMs(0) ?: 33L
                }
            } catch (e: Exception) {
                return 33L
            } finally {
                if (canvas != null) {
                    // drawTimeMs 只算"动画绘制"部分（调试面板不计入阈值）
                    drawTimeMs = (animDrawEndTime - drawStartTime) / 1_000_000f
                    val warmup = (now - engineStartTime) < 3000
                    if (!warmup && loadErrorMsg.isEmpty() && drawTimeMs > limitDrawTime) {
                        forceShowDebug = true
                    }
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
        }

        private fun buildDebugState(
            info: GifInfo?,
            now: Long,
            isAlarm: Boolean
        ): DebugOverlayRenderer.DebugState {
            val frameIdx: Int = if (info != null && info.frameCount > 0 && info.totalDelayMs > 0) {
                val elapsedInLoop = (now - currentLoopStartTime) % info.totalDelayMs
                var idx = info.frameEndTimes.indexOfFirst { it > elapsedInLoop }
                if (idx == -1) idx = info.frameCount - 1
                idx
            } else -1

            return DebugOverlayRenderer.DebugState(
                stateName = currentStateNode?.id ?: "None",
                currentPlayCount = currentPlayCount,
                targetSelfPlay = targetSelfPlay,
                frameIndex = frameIdx,
                frameCount = info?.frameCount ?: 0,
                drawTimeMs = drawTimeMs,
                limitDrawTime = limitDrawTime,
                currentMemMb = currentMemMb,
                limitMemory = limitMemory,
                frameIntervalMs = frameIntervalMs,
                isAlarm = isAlarm,
                hasError = loadErrorMsg.isNotEmpty()
            )
        }
    }
}