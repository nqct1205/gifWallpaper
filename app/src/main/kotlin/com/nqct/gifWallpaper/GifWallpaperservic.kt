package com.nqct.gifWallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
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
import java.util.Locale

class GifWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = GifEngine()

    inner class GifEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())

        @Volatile private var visible = false
        @Volatile private var destroyed = false
        @Volatile private var ready = false

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

        // ======== 调试与监控参数 ========
        private var userShowDebug = false
        private var limitDrawTime = 20
        private var limitMemory = 150
        private var forceShowDebug = false 
        private var engineStartTime = 0L // 记录启动时间，用于 5 秒预热豁免

        private var lastFrameTime = 0L
        private var frameIntervalMs = 0L
        private var drawTimeMs = 0f
        private var lastMemoryUpdateTime = 0L
        private var currentMemMb = 0
        private var memoryText = ""
        private var loadErrorMsg = ""
        
        private val debugTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        private val debugBgPaint = Paint().apply {
            color = Color.parseColor("#B3000000")
            style = Paint.Style.FILL
        }
        private val errorTextPaint = Paint().apply {
            color = Color.RED
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        private val memInfo = Debug.MemoryInfo()
        // ==============================

        private val animCallback = object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                super.onAnimationEnd(drawable)
                if (destroyed || !visible || currentStateNode == null) return

                currentPlayCount++
                if (currentPlayCount < targetSelfPlay) {
                    currentLoopStartTime = SystemClock.elapsedRealtime()
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

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
            prefs?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == "showDebug" || key == "limitDraw" || key == "limitMem") {
                handler.post {
                    userShowDebug = sharedPreferences?.getBoolean("showDebug", false) ?: false
                    limitDrawTime = sharedPreferences?.getInt("limitDraw", 20) ?: 20
                    limitMemory = sharedPreferences?.getInt("limitMem", 150) ?: 150
                    
                    // 用户主动修改了设置，重置强制报警状态（让它能继续重新监测）
                    forceShowDebug = false 
                }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loadInBackground()
        }

        private fun loadInBackground() {
            loaderThread?.interrupt()
            loaderThread = Thread {
                try {
                    val uShowDebug = prefs?.getBoolean("showDebug", false) ?: false
                    val lDraw = prefs?.getInt("limitDraw", 20) ?: 20
                    val lMem = prefs?.getInt("limitMem", 150) ?: 150

                    val sm = StateMachine.load(this@GifWallpaperService)
                    val animDir = File(filesDir, "animations")
                    if (!animDir.exists()) animDir.mkdirs()
                    val files = animDir.listFiles { file -> file.extension.lowercase() == "gif" } ?: emptyArray()

                    val newAnimCache = mutableMapOf<String, AnimatedImageDrawable>()
                    val newInfoCache = mutableMapOf<String, GifInfo>()
                    val usedIds = sm.states.map { it.id }.toSet()
                    
                    val sb = StringBuilder()
                    if (sm.states.isEmpty()) sb.append("JSON状态为空\n")

                    for (file in files) {
                        val id = file.nameWithoutExtension
                        if (id in usedIds) {
                            val a = decodeAnimatedDrawable(file)
                            if (a != null) {
                                newAnimCache[id] = a
                            } else {
                                sb.append("解码失败: $id\n")
                            }
                            GifMetadataParser.parse(file)?.let { newInfoCache[id] = it }
                        }
                    }

                    handler.post {
                        if (destroyed) return@post
                        
                        // 【大坑】AnimatedImageDrawable 的回调注册必须在有 Looper 的主线程执行！
                        for (anim in newAnimCache.values) {
                            anim.repeatCount = 0 // 【坑点】系统 API 中 repeatCount=0 代表播放 1 次
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

                        if (sm.initialStateId.isNotEmpty()) {
                            forceState(sm.initialStateId)
                        } else if (sm.states.isNotEmpty()) {
                            forceState(sm.states[0].id)
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
                    e.printStackTrace()
                    handler.post {
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

        private fun decodeAnimatedDrawable(file: File): AnimatedImageDrawable? {
            if (!file.exists() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            return try {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable
            } catch (e: Exception) {
                null
            }
        }

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
            } else {
                node.minSelfPlay.coerceAtLeast(1)
            }

            currentLoopStartTime = SystemClock.elapsedRealtime()
            currentAnim?.stop()
            currentAnim?.start()
        }

        private fun switchToNextState() {
            val node = currentStateNode ?: return
            if (node.rules.isEmpty()) {
                forceState(node.id)
                return
            }

            val totalWeight = node.rules.sumOf { it.weight.coerceAtLeast(0) }
            if (totalWeight <= 0) {
                forceState(node.id)
                return
            }

            var randomVal = (1..totalWeight).random()
            var selectedTarget = node.id
            for (rule in node.rules) {
                randomVal -= rule.weight
                if (randomVal <= 0) {
                    selectedTarget = rule.targetId
                    break
                }
            }

            forceState(selectedTarget)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                currentAnim?.start()
                if (ready) {
                    handler.removeCallbacks(drawRunnable)
                    handler.post(drawRunnable)
                }
            } else {
                currentAnim?.stop()
                handler.removeCallbacks(drawRunnable)
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
            for (anim in animCache.values) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    anim.unregisterAnimationCallback(animCallback)
                }
                anim.stop()
            }
            animCache.clear()
            infoCache.clear()
            currentStateNode = null
            currentAnim = null
            currentInfo = null
        }

        private fun drawFrame(): Long {
            val holder = surfaceHolder
            val anim = currentAnim
            val info = currentInfo
            val now = SystemClock.elapsedRealtime()

            if (lastFrameTime > 0) frameIntervalMs = now - lastFrameTime
            lastFrameTime = now

            // 内存检查：每 1 秒执行一次，避免频繁调用 Debug.getMemoryInfo 导致卡顿
            if (now - lastMemoryUpdateTime > 1000) {
                Debug.getMemoryInfo(memInfo)
                currentMemMb = memInfo.totalPss / 1024
                
                // 【机制】开机/刚启动壁纸的前 3 秒存在系统预热，不计入内存阈值检测
                val isWarmup = (now - engineStartTime) < 3000
                if (!isWarmup && loadErrorMsg.isEmpty()) {
                    if (currentMemMb > limitMemory) {
                        forceShowDebug = true
                    }
                }
                lastMemoryUpdateTime = now
            }

            var canvas: Canvas? = null
            val drawStartTime = System.nanoTime() // 记录渲染开始时间

            try {
                // 强制启用硬件加速画布
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
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
                    
                    val needShowDebug = userShowDebug || forceShowDebug || loadErrorMsg.isNotEmpty()
                    if (needShowDebug) {
                        drawDebugInfo(canvas, info, forceShowDebug)
                    }
                    
                } else {
                    drawErrorInfo(canvas)
                    drawDebugInfo(canvas, info, true)
                }

                return info?.frameIntervalMs(0) ?: 33L
            } catch (e: Exception) {
                return 33L
            } finally {
                if (canvas != null) {
                    val drawEndTime = System.nanoTime()
                    // 计算这一帧的纯渲染耗时（不包含帧间等待）
                    drawTimeMs = (drawEndTime - drawStartTime) / 1_000_000f
                    
                    // 【核心修复】逐帧死盯纯渲染耗时。启动预热豁免 3 秒。
                    // 只要超时，立刻触发强制显示（forceShowDebug = true），且不会自动隐藏。
                    val isWarmup = (now - engineStartTime) < 3000
                    if (!isWarmup && loadErrorMsg.isEmpty()) {
                        if (drawTimeMs > limitDrawTime) {
                            forceShowDebug = true
                        }
                    }
                    
                    try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) {}
                }
            }
        }

        /** 辅助方法：自动计算宽度并换行绘制文本 */
        private fun drawWrappedText(canvas: Canvas, text: String, paint: Paint, centerX: Float, startY: Float, maxWidth: Float): Float {
            var currentY = startY
            val lines = text.split("\n")
            for (paragraph in lines) {
                var start = 0
                while (start < paragraph.length) {
                    val count = paint.breakText(paragraph, start, paragraph.length, true, maxWidth, null)
                    if (count <= 0) break
                    val subText = paragraph.substring(start, start + count)
                    canvas.drawText(subText, centerX, currentY, paint)
                    currentY += paint.textSize * 1.2f
                    start += count
                }
                if (paragraph.isEmpty()) {
                    currentY += paint.textSize * 1.2f
                }
            }
            return currentY
        }

        private fun drawErrorInfo(canvas: Canvas) {
            if (loadErrorMsg.isEmpty()) return
            
            val maxWidth = canvas.width * 0.9f
            val centerX = canvas.width / 2f
            val centerY = canvas.height / 2f * 0.6f 
            
            val lines = loadErrorMsg.split("\n")
            var totalHeight = 0f
            for (line in lines) {
                var start = 0
                while (start < line.length) {
                    val count = errorTextPaint.breakText(line, start, line.length, true, maxWidth, null)
                    totalHeight += errorTextPaint.textSize * 1.2f
                    start += count.coerceAtLeast(1)
                }
                if (line.isEmpty()) totalHeight += errorTextPaint.textSize * 1.2f
            }
            
            val padding = 40f
            val rect = RectF(centerX - maxWidth / 2 - padding, centerY - totalHeight / 2 - padding, centerX + maxWidth / 2 + padding, centerY + totalHeight / 2 + padding)
            canvas.drawRoundRect(rect, 20f, 20f, debugBgPaint)
            
            val startY = centerY - totalHeight / 2 + errorTextPaint.textSize
            drawWrappedText(canvas, loadErrorMsg, errorTextPaint, centerX, startY, maxWidth)
        }

        private fun drawDebugInfo(canvas: Canvas, info: GifInfo?, isAlarm: Boolean) {
            val now = SystemClock.elapsedRealtime()
            val stateName = currentStateNode?.id ?: "None"
            val warnSign = if (isAlarm) " [⚠ 超阈值]" else ""
            
            // 根据总时长和已播放时间推算当前帧
            var frameText = ""
            if (info != null && info.frameCount > 0 && info.totalDelayMs > 0) {
                val elapsedInLoop = (now - currentLoopStartTime) % info.totalDelayMs
                var frameIdx = info.frameEndTimes.indexOfFirst { it > elapsedInLoop }
                if (frameIdx == -1) frameIdx = info.frameCount - 1
                frameText = " 第 ${frameIdx + 1}/${info.frameCount} 帧"
            }

            memoryText = String.format(
                Locale.US, "状态: %s (第 %d/%d 圈%s)%s\n当前帧耗时: %.2f ms (限 %d ms)\n内存总计: %d MB (限 %d MB)\n刷新间隔: %d ms",
                stateName, currentPlayCount + 1, targetSelfPlay, frameText, warnSign, 
                drawTimeMs, limitDrawTime, 
                currentMemMb, limitMemory, frameIntervalMs
            )
            
            if (memoryText.isEmpty()) return
            
            val maxWidth = canvas.width * 0.9f
            val centerX = canvas.width / 2f
            // 如果有红字报错，就往下挪一点，防止重叠
            val centerY = if (loadErrorMsg.isNotEmpty()) canvas.height / 2f * 1.4f else canvas.height / 2f
            
            val lines = memoryText.split("\n")
            var totalHeight = 0f
            for (line in lines) {
                var start = 0
                while (start < line.length) {
                    val count = debugTextPaint.breakText(line, start, line.length, true, maxWidth, null)
                    totalHeight += debugTextPaint.textSize * 1.2f
                    start += count.coerceAtLeast(1)
                }
                if (line.isEmpty()) totalHeight += debugTextPaint.textSize * 1.2f
            }
            
            val padding = 40f
            val rect = RectF(centerX - maxWidth / 2 - padding, centerY - totalHeight / 2 - padding, centerX + maxWidth / 2 + padding, centerY + totalHeight / 2 + padding)
            
            val oldColor = debugBgPaint.color
            if (isAlarm) debugBgPaint.color = Color.parseColor("#99000000")
            canvas.drawRoundRect(rect, 20f, 20f, debugBgPaint)
            debugBgPaint.color = oldColor
            
            val startY = centerY - totalHeight / 2 + debugTextPaint.textSize
            if (isAlarm) {
                val tmp = Paint(errorTextPaint)
                tmp.color = Color.WHITE
                drawWrappedText(canvas, memoryText, tmp, centerX, startY, maxWidth)
            } else {
                drawWrappedText(canvas, memoryText, debugTextPaint, centerX, startY, maxWidth)
            }
        }
    }
}