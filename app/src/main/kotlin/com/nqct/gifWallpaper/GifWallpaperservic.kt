package com.nqct.gifWallpaper

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class GifWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = GifEngine()

    inner class GifEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        
        private var movie1: Movie? = null
        private var movie2: Movie? = null
        
        private var isPlayingSecond = false
        private var currentMovieStartTime = 0L

        private var minIntervalMs = 15000L
        private var maxIntervalMs = 45000L
        private var nextTriggerTime = 0L

        private val drawRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, 16)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loadGifsAndSettings()
        }

        private fun loadGifsAndSettings() {
            try {
                val prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
                val minSec = prefs.getInt("minTime", 15)
                val maxSec = prefs.getInt("maxTime", 45)
                minIntervalMs = minSec * 1000L
                maxIntervalMs = maxSec * 1000L

                val file1 = File(filesDir, "gif1.gif")
                val file2 = File(filesDir, "gif2.gif")

                if (file1.exists()) movie1 = Movie.decodeFile(file1.absolutePath)
                if (file2.exists()) movie2 = Movie.decodeFile(file2.absolutePath)

                isPlayingSecond = false
                currentMovieStartTime = System.currentTimeMillis()
                scheduleNextTrigger()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun scheduleNextTrigger() {
            val randomDelay = (minIntervalMs..maxIntervalMs).random()
            nextTriggerTime = System.currentTimeMillis() + randomDelay
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                currentMovieStartTime = System.currentTimeMillis()
                if (!isPlayingSecond) scheduleNextTrigger()
                handler.post(drawRunnable)
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunnable)
            movie1 = null
            movie2 = null
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val now = System.currentTimeMillis()

                if (!isPlayingSecond && movie2 != null && now >= nextTriggerTime) {
                    isPlayingSecond = true
                    currentMovieStartTime = now
                }

                val currentMovie = if (isPlayingSecond) movie2 else movie1
                
                if (currentMovie != null) {
                    val elapsed = now - currentMovieStartTime
                    val duration = currentMovie.duration()
                    
                    if (isPlayingSecond) {
                        if (elapsed >= duration) {
                            isPlayingSecond = false
                            currentMovieStartTime = now
                            scheduleNextTrigger()
                        } else {
                            currentMovie.setTime(elapsed.toInt())
                        }
                    } else {
                        val loopTime = if (duration > 0) elapsed % duration else 0
                        currentMovie.setTime(loopTime.toInt())
                    }

                    drawMovieCentered(canvas, currentMovie)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        private fun drawMovieCentered(canvas: Canvas, movie: Movie) {
            val screenW = canvas.width
            val screenH = canvas.height
            val movieW = movie.width().toFloat()
            val movieH = movie.height().toFloat()

            if (movieW <= 0 || movieH <= 0) return

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