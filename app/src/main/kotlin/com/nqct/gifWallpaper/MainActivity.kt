package com.nqct.gifWallpaper

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class GifWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = GifEngine()

    inner class GifEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())

        @Volatile private var visible = false
        @Volatile private var destroyed = false
        @Volatile private var ready = false

        private var movie1: Movie? = null
        private var movie2: Movie? = null
        private var info1: GifInfo? = null
        private var info2: GifInfo? = null

        private var isPlayingSecond = false

        /** 全部使用 elapsedRealtime 基准 */
        private var currentMovieStartTime = 0L
        private var nextTriggerTime = 0L
        private var pausedAt = 0L

        private var minIntervalMs = 15_000L
        private var maxIntervalMs = 45_000L

        private var loaderThread: Thread? = null

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

                    val m1 = if (file1.exists()) Movie.decodeFile(file1.absolutePath) else null
                    val m2 = if (file2.exists()) Movie.decodeFile(file2.absolutePath) else null
                    val i1 = GifMetadataParser.parse(file1)
                    val i2 = GifMetadataParser.parse(file2)

                    handler.post {
                        if (destroyed) return@post
                        minIntervalMs = minSec * 1000L
                        maxIntervalMs = maxSec * 1000L
                        movie1 = m1
                        movie2 = m2
                        info1 = i1
                        info2 = i2

                        isPlayingSecond = false
                        val now = SystemClock.elapsedRealtime()
                        currentMovieStartTime = now
                        pausedAt = 0L
                        scheduleNextTrigger(now)

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

        private fun scheduleNextTrigger(now: Long) {
            val lo = minIntervalMs.coerceAtLeast(1_000L)
            val hi = maxIntervalMs.coerceAtLeast(lo)
            nextTriggerTime = now + if (hi > lo) (lo..hi).random() else lo
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                val now = SystemClock.elapsedRealtime()
                if (pausedAt > 0L) {
                    val delta = now - pausedAt
                    if (currentMovieStartTime > 0L) currentMovieStartTime += delta
                    if (nextTriggerTime > 0L) nextTriggerTime += delta
                    pausedAt = 0L
                }
                if (ready) {
                    handler.removeCallbacks(drawRunnable)
                    handler.post(drawRunnable)
                }
            } else {
                pausedAt = SystemClock.elapsedRealtime()
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
            movie1 = null
            movie2 = null
            info1 = null
            info2 = null
        }

        /** @return 距离下一次重绘的延迟（毫秒） */
        private fun drawFrame(): Long {
            val holder = surfaceHolder

            // 一次性捕获到局部 val：解决 smart cast，且保证同帧一致快照
            val m1 = movie1
            val m2 = movie2
            val i1 = info1
            val i2 = info2

            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return defaultInterval()
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val now = SystemClock.elapsedRealtime()

                // 1. 触发副 GIF
                if (!isPlayingSecond && m2 != null &&
                    nextTriggerTime > 0L && now >= nextTriggerTime) {
                    isPlayingSecond = true
                    currentMovieStartTime = now
                }

                // 2. 副 GIF 播放结束 → 回到主 GIF
                if (isPlayingSecond && m2 != null) {
                    val d = resolveDuration(m2, i2)
                    if (d > 0 && now - currentMovieStartTime >= d) {
                        isPlayingSecond = false
                        currentMovieStartTime = now
                        scheduleNextTrigger(now)
                    }
                }

                val movie: Movie? = if (isPlayingSecond) m2 else m1
                val info: GifInfo? = if (isPlayingSecond) i2 else i1

                if (movie == null) return defaultInterval()

                val duration = resolveDuration(movie, info)
                val elapsed = (now - currentMovieStartTime).coerceAtLeast(0L)

                val time = if (duration > 0) (elapsed % duration).toInt() else 0
                movie.setTime(time)

                drawMovieCentered(canvas, movie)

                return info?.frameIntervalMs(duration) ?: defaultInterval()
            } catch (e: Exception) {
                e.printStackTrace()
                return defaultInterval()
            } finally {
                canvas?.let {
                    try {
                        holder.unlockCanvasAndPost(it)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        private fun resolveDuration(movie: Movie, info: GifInfo?): Int {
            val d = movie.duration()
            if (d > 0) return d
            return info?.totalDelayMs ?: 0
        }

        private fun defaultInterval(): Long = GifInfo.DEFAULT_FRAME_INTERVAL_MS

        private fun drawMovieCentered(canvas: Canvas, movie: Movie) {
            val screenW = canvas.width
            val screenH = canvas.height
            val movieW = movie.width().toFloat()
            val movieH = movie.height().toFloat()

            if (movieW <= 0f || movieH <= 0f) return

            val scale = minOf(screenW / movieW, screenH / movieH)

            canvas.save()
            canvas.translate(screenW / 2f, screenH / 2f)
            canvas.scale(scale, scale)
            canvas.translate(-movieW / 2f, -movieH / 2f)
            movie.draw(canvas, 0f, 0f)
            canvas.restore()
        }
    }
}