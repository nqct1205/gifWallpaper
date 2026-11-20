package com.nqct.gifWallpaper

import android.graphics.Canvas
import pl.droidsonroids.gif.GifDrawable
import java.io.File

// 单个资源引用的运行时实例。
class PlayInstance(
    val ref: AnimationResourceRef,
    val gifDrawable: GifDrawable?,
    val info: GifInfo?,
    private val speedFactor: Float,
    private val frameIntervalMs: Long,
    private val totalDurationMs: Long,
    private val startDelayMs: Long
) {
    var hasStarted = false
        private set
    var hasStopped = false
        private set

    val endMs: Long get() = startDelayMs + totalDurationMs
    val frameCount: Int get() = gifDrawable?.numberOfFrames ?: info?.frameCount ?: 0
    val allocationBytes: Long
        get() = try { gifDrawable?.allocationByteCount ?: 0L } catch (_: Throwable) { 0L }

    fun getFrameIntervalMs(): Long = frameIntervalMs
    fun getTotalDurationMs(): Long = totalDurationMs
    fun getStartDelayMs(): Long = startDelayMs

    fun syncPlayback(elapsedMs: Long) {
        val shouldBePlaying = elapsedMs >= startDelayMs && elapsedMs < endMs
        if (shouldBePlaying && !hasStarted) {
            try { gifDrawable?.start() } catch (_: Throwable) {}
            hasStarted = true
            hasStopped = false
        } else if (!shouldBePlaying && hasStarted && !hasStopped) {
            try { gifDrawable?.stop() } catch (_: Throwable) {}
            hasStopped = true
        }
    }

    fun shouldDraw(elapsedMs: Long): Boolean {
        if (gifDrawable == null) return false
        return elapsedMs >= startDelayMs && elapsedMs < endMs
    }

    fun draw(canvas: Canvas) {
        try { gifDrawable?.draw(canvas) } catch (_: Throwable) {}
    }

    fun setBounds(l: Int, t: Int, r: Int, b: Int) {
        try { gifDrawable?.setBounds(l, t, r, b) } catch (_: Throwable) {}
    }

    fun reset() {
        hasStarted = false
        hasStopped = false
        try { gifDrawable?.stop() } catch (_: Throwable) {}
        try { gifDrawable?.seekToFrame(0) } catch (_: Throwable) {}
        if (speedFactor != 1f) {
            try { gifDrawable?.setSpeed(speedFactor) } catch (_: Throwable) {}
        }
    }

    fun stop() {
        try { gifDrawable?.stop() } catch (_: Throwable) {}
        hasStarted = false
        hasStopped = true
    }

    fun recycle() {
        try { gifDrawable?.recycle() } catch (_: Throwable) {}
    }

    companion object {
        fun fromFile(
            resourceId: String,
            ref: AnimationResourceRef,
            file: File,
            resourceItem: ResourceItem? = null
        ): PlayInstance {
            val gd = try {
                if (file.exists() && file.length() > 0) GifDrawable(file) else null
            } catch (e: Throwable) {
                LogBuffer.e("GifDrawable 加载失败: $resourceId", e)
                null
            }
            val info = GifMetadataParser.parse(file)

            val factor = resourceItem?.speedFactor() ?: 1f
            val frameInterval = resourceItem?.targetFrameIntervalMs()
                ?: info?.let {
                    if (it.frameCount > 0) (it.totalDelayMs.toLong() / it.frameCount).coerceAtLeast(1L) else 33L
                } ?: 33L
            val totalDuration = resourceItem?.targetTotalDurationMs()
                ?: info?.totalDelayMs?.toLong()
                ?: 0L
            val startDelay = ref.startDelayMs.toLong().coerceAtLeast(0L)

            if (gd != null) {
                try { gd.seekToFrame(0) } catch (_: Throwable) {}
                if (factor != 1f) {
                    try { gd.setSpeed(factor) } catch (_: Throwable) {}
                }
            }
            return PlayInstance(ref, gd, info, factor, frameInterval, totalDuration, startDelay)
        }
    }
}

// 一个动画的运行时。
//
// 【整圈时长优先用用户配置的 endTimeMs】
//   configuredEndTimeMs > 0 → 用 endTimeMs（用户自定义，单/多资源均支持）
//   否则                    → autoTotalDurationMs = max(所有资源.endMs)
//
// 用户自定义 endTimeMs 时，某些资源可能被截断（endMs > endTimeMs），
// 也可能出现空窗（endMs < endTimeMs）。两种情况都是预期行为。
class AnimRuntime(
    val animationId: String,
    val instances: List<PlayInstance>,
    configuredEndTimeMs: Long = 0L
) {
    private val userEndTimeMs: Long =
        if (configuredEndTimeMs > 0) configuredEndTimeMs else 0L

    // 自动整圈时长 = 最晚结束的资源。
    val autoTotalDurationMs: Long
        get() = instances.maxOfOrNull { it.endMs } ?: 0L

    // 实际生效的整圈时长。
    val totalDurationMs: Long
        get() = if (userEndTimeMs > 0) userEndTimeMs else autoTotalDurationMs

    // 是否使用了用户自定义整圈时长。
    val isUsingUserEndTime: Boolean
        get() = userEndTimeMs > 0

    // 绘制频率。取最短的帧间隔。
    fun frameIntervalMs(): Long =
        (instances.minOfOrNull { it.getFrameIntervalMs() } ?: 33L).coerceIn(8L, 200L)

    val allocationBytes: Long
        get() = instances.sumOf { it.allocationBytes }

    fun syncPlayback(elapsedMs: Long) {
        instances.forEach { it.syncPlayback(elapsedMs) }
    }

    // 是否所有资源都播完（用户自定义 endTime 时，以用户设定为准）。
    fun allFinished(elapsedMs: Long): Boolean {
        if (userEndTimeMs > 0) return elapsedMs >= userEndTimeMs
        return instances.all { elapsedMs >= it.endMs }
    }

    fun reset() {
        instances.forEach { it.reset() }
    }

    fun stopAll() {
        instances.forEach { it.stop() }
    }

    fun recycle() {
        instances.forEach { it.recycle() }
    }
}