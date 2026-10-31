package com.nqct.gifWallpaper

import android.content.Context
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

    inner class GifEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())

        @Volatile private var visible = false
        @Volatile private var destroyed = false
        @Volatile private var ready = false

        private var anim1: AnimatedImageDrawable? = null
        private var anim2: AnimatedImageDrawable? = null
        private var info1: GifInfo? = null
        private var info2: GifInfo? = null

        private var isPlayingSecond = false

        // 计时/计圈相关
        private var minIntervalMs = 15_000L
        private var maxIntervalMs = 45_000L
        private var currentLoops = 0
        private var targetLoops = 3

        private var pausedAt = 0L
        private var loaderThread: Thread? = null

        // ======== 调试代码开始 ========
        private var lastFrameTime = 0L
        private var frameIntervalMs = 0L
        private var drawTimeMs = 0f
        private var lastMemoryUpdateTime = 0L
        private var memoryText = "内存: 计算中..."
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
        private val memInfo = Debug.MemoryInfo()
        // ======== 调试代码结束 ========

        // 监听主 GIF 的每一圈结束
        private val anim1Callback = object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                super.onAnimationEnd(drawable)
                if (isPlayingSecond || destroyed || !visible) return

                currentLoops++
                if (currentLoops >= targetLoops) {
                    // 达到目标圈数，切换到副 GIF
                    if (anim2 != null) {
                        isPlayingSecond = true
                        anim1?.stop()
                        anim2?.start()
                    } else {
                        // 如果没设副 GIF，继续重置圈数循环
                        currentLoops = 0
                        calculateTargetLoops()
                        anim1?.start()
                    }
                } else {
                    // 还没到目标圈数，继续播下一圈
                    anim1?.start()
                }
            }
        }

        // 监听副 GIF 播放完毕
        private val anim2Callback = object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                super.onAnimationEnd(drawable)
                if (isPlayingSecond && !destroyed) {
                    switchToMainGifInternal()
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

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loadInBackground()
        }

        private fun loadInBackground() {
            loaderThread?.interrupt()
            loaderThread = Thread {
                try {
                    val prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
                    var minSec = prefs.getInt("minTime", 15)
                    var maxSec = prefs.getInt("maxTime", 45)
                    if (minSec <= 0) minSec = 1
                    if (maxSec <= 0) maxSec = 1
                    if (minSec > maxSec) {
                        val t = minSec; minSec = maxSec; maxSec = t
                    }

                    val file1 = File(filesDir, "gif1.gif")
                    val file2 = File(filesDir, "gif2.gif")

                    val a1 = decodeAnimatedDrawable(file1)
                    // 关键：主 GIF 也改为单次播放，交给回调控制圈数
                    a1?.repeatCount = 1 
                    
                    val a2 = decodeAnimatedDrawable(file2)
                    a2?.repeatCount = 1
                    
                    val i1 = GifMetadataParser.parse(file1)
                    val i2 = GifMetadataParser.parse(file2)

                    handler.post {
                        if (destroyed) return@post
                        minIntervalMs = minSec * 1000L
                        maxIntervalMs = maxSec * 1000L
                        
                        anim1 = a1
                        anim2 = a2
                        info1 = i1
                        info2 = i2

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            anim1?.registerAnimationCallback(anim1Callback)
                            anim2?.registerAnimationCallback(anim2Callback)
                        }

                        switchToMainGifInternal()
                        
                        ready = true
                        if (visible) {
                            handler.removeCallbacks(drawRunnable)
                            handler.post(drawRunnable)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.also { it.start() }
        }

        private fun decodeAnimatedDrawable(file: File): AnimatedImageDrawable? {
            if (!file.exists() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            return try {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        private fun switchToMainGifInternal() {
            isPlayingSecond = false
            currentLoops = 0
            calculateTargetLoops()
            anim2?.stop()
            anim1?.start()
            pausedAt = 0L
        }

        /** 把原本的毫秒时间，换算成主 GIF 需要播放的圈数 */
        private fun calculateTargetLoops() {
            val totalDuration = info1?.totalDelayMs ?: 0
            if (totalDuration > 0) {
                // 例如原设定 15~45秒，主GIF一圈6秒，则圈数=3~8圈
                val minLoops = Math.round(minIntervalMs.toFloat() / totalDuration).coerceAtLeast(1)
                val maxLoops = Math.round(maxIntervalMs.toFloat() / totalDuration).coerceAtLeast(minLoops)
                targetLoops = if (maxLoops > minLoops) (minLoops..maxLoops).random() else minLoops
            } else {
                targetLoops = 3 // 解析失败时的兜底圈数
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                if (isPlayingSecond) anim2?.start() else anim1?.start()
                
                if (ready) {
                    handler.removeCallbacks(drawRunnable)
                    handler.post(drawRunnable)
                }
            } else {
                pausedAt = SystemClock.elapsedRealtime()
                anim1?.stop()
                anim2?.stop()
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            if (ready && visible && !destroyed) {
                drawFrame()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            destroyed = true
            visible = false
            ready = false
            handler.removeCallbacks(drawRunnable)
            loaderThread?.interrupt()
            loaderThread = null
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                anim1?.unregisterAnimationCallback(anim1Callback)
                anim2?.unregisterAnimationCallback(anim2Callback)
            }
            anim1?.stop()
            anim2?.stop()
            anim1 = null
            anim2 = null
            info1 = null
            info2 = null
        }

        private fun drawFrame(): Long {
            val holder = surfaceHolder
            val a1 = anim1
            val a2 = anim2
            val i1 = info1
            val i2 = info2

            val now = SystemClock.elapsedRealtime()
            val currentAnim = if (isPlayingSecond) a2 else a1
            val currentInfo = if (isPlayingSecond) i2 else i1

            if (currentAnim == null) return 33L

            var canvas: Canvas? = null

            // ======== 调试代码开始 ========
            val drawStartTime = System.nanoTime()
            if (lastFrameTime > 0) frameIntervalMs = now - lastFrameTime
            lastFrameTime = now
            // ======== 调试代码结束 ========

            try {
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                } ?: return 33L
                
                canvas.drawColor(Color.BLACK) 

                val screenW = canvas.width
                val screenH = canvas.height
                val dw = currentAnim.intrinsicWidth
                val dh = currentAnim.intrinsicHeight

                if (dw > 0 && dh > 0) {
                    val scale = minOf(screenW / dw.toFloat(), screenH / dh.toFloat())
                    val w = (dw * scale).toInt()
                    val h = (dh * scale).toInt()
                    val left = (screenW - w) / 2
                    val top = (screenH - h) / 2
                    
                    currentAnim.setBounds(left, top, left + w, top + h)
                    currentAnim.draw(canvas)
                }

                // ======== 调试代码开始 ========
                drawDebugInfo(canvas, currentAnim, currentInfo)
                // ======== 调试代码结束 ========

                return currentInfo?.frameIntervalMs(0) ?: 33L

            } catch (e: Exception) {
                e.printStackTrace()
                return 33L
            } finally {
                if (canvas != null) {
                    // ======== 调试代码开始 ========
                    val drawEndTime = System.nanoTime()
                    drawTimeMs = (drawEndTime - drawStartTime) / 1_000_000f
                    // ======== 调试代码结束 ========
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // ======== 调试代码开始 ========
        private fun drawDebugInfo(canvas: Canvas, drawable: AnimatedImageDrawable, info: GifInfo?) {
            val now = SystemClock.elapsedRealtime()
            
            if (now - lastMemoryUpdateTime > 1000) {
                Debug.getMemoryInfo(memInfo)
                val totalPss = memInfo.totalPss / 1024
                val javaPss = memInfo.dalvikPss / 1024
                val nativePss = memInfo.nativePss / 1024
                val graphicsPss = memInfo.otherPss / 1024

                val playMode = if (isPlayingSecond) "副" else "主"
                val loopsText = if (isPlayingSecond) "单次播放中" else "第 ${currentLoops + 1}/${targetLoops} 圈"

                memoryText = String.format(
                    Locale.US,
                    "状态: %s GIF  (%s)\n渲染耗时: %.2f ms\n刷新间隔: %d ms\n--- 进程实际内存 (PSS) ---\n总计: %d MB\nJava: %d MB | Native: %d MB | 图形/其他: %d MB",
                    playMode, loopsText,
                    drawTimeMs,
                    frameIntervalMs,
                    totalPss, javaPss, nativePss, graphicsPss
                )
                lastMemoryUpdateTime = now
            }

            if (memoryText.isEmpty()) return

            val lines = memoryText.split("\n")
            var maxWidth = 0f
            for (line in lines) {
                val w = debugTextPaint.measureText(line)
                if (w > maxWidth) maxWidth = w
            }
            val lineHeight = debugTextPaint.textSize * 1.2f
            val totalHeight = lines.size * lineHeight
            val padding = 40f
            
            val centerX = canvas.width / 2f
            val centerY = canvas.height / 2f
            
            val rect = RectF(
                centerX - maxWidth / 2 - padding,
                centerY - totalHeight / 2 - padding,
                centerX + maxWidth / 2 + padding,
                centerY + totalHeight / 2 + padding
            )
            canvas.drawRoundRect(rect, 20f, 20f, debugBgPaint)

            var textY = centerY - totalHeight / 2 + debugTextPaint.textSize
            for (line in lines) {
                canvas.drawText(line, centerX, textY, debugTextPaint)
                textY += lineHeight
            }
        }
        // ======== 调试代码结束 ========
    }
}