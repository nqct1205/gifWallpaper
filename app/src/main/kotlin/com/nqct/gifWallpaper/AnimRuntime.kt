package com.nqct.gifWallpaper

import android.graphics.Canvas
import pl.droidsonroids.gif.GifDrawable
import java.io.File

// 单个资源运行时实例。
//
// 【关键设计】
//   - 期望帧间隔与期望总时长在构造时一次算定，来自同一个 ResourceItem，
//     严格满足：expectedFrameIntervalMs * frameCount ≈ expectedTotalDurationMs。
//   - draw 频率用 expectedFrameIntervalMs（computeNextFrameDelay 直接返回）。
//   - 定时器时长用 expectedTotalDurationMs（scheduleCycleTimer 直接用）。
//   - GifDrawable.setSpeed(speedFactor) 让内部按此速率推进。
//   三者源自同一份数据，不会漂移。
//
// 【setSpeed 保护】seekToFrame 之后重新 setSpeed，防止某些实现把它重置。
class AnimResourceInstance(
    val ref: AnimationResourceRef,
    val gifDrawable: GifDrawable?,
    val info: GifInfo?,
    // GifDrawable.setSpeed 用的速度因子
    private val speedFactor: Float,
    // 期望的每帧时长（用于 draw 频率）
    private val expectedFrameIntervalMs: Long,
    // 期望的一圈时长（用于定时器）
    private val expectedTotalDurationMs: Long
) {
    val frameCount: Int
        get() = gifDrawable?.numberOfFrames ?: info?.frameCount ?: 0

    // 一圈时长。定时器用它。
    val totalDurationMs: Long
        get() = expectedTotalDurationMs.coerceAtLeast(1L)

    // 每帧时长。draw 频率用它。
    fun frameIntervalMs(): Long = expectedFrameIntervalMs.coerceAtLeast(1L)

    val allocationBytes: Long
        get() = try { gifDrawable?.allocationByteCount ?: 0L } catch (_: Throwable) { 0L }

    fun start() {
        try { gifDrawable?.start() } catch (e: Throwable) { LogBuffer.e("实例 start 失败", e) }
    }

    fun stop() {
        try { gifDrawable?.stop() } catch (_: Throwable) {}
    }

    // 停止 + 回到第 0 帧 + 重新应用速度。
    // 【关键】seekToFrame 后再次 setSpeed，防止某些实现重置速度因子。
    fun reset() {
        try { gifDrawable?.stop() } catch (_: Throwable) {}
        try { gifDrawable?.seekToFrame(0) } catch (e: Throwable) {
            LogBuffer.e("seekToFrame(0) 失败", e)
        }
        if (speedFactor != 1f) {
            try { gifDrawable?.setSpeed(speedFactor) } catch (e: Throwable) {
                LogBuffer.e("reset 时 setSpeed 失败 factor=$speedFactor", e)
            }
        }
    }

    fun draw(canvas: Canvas) {
        try { gifDrawable?.draw(canvas) } catch (_: Throwable) {}
    }

    fun setBounds(l: Int, t: Int, r: Int, b: Int) {
        try { gifDrawable?.setBounds(l, t, r, b) } catch (_: Throwable) {}
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
        ): AnimResourceInstance {
            val gd = try {
                if (file.exists() && file.length() > 0) GifDrawable(file) else null
            } catch (e: Throwable) {
                LogBuffer.e("GifDrawable 加载失败: $resourceId", e)
                null
            }
            val info = GifMetadataParser.parse(file)

            val factor = resourceItem?.speedFactor() ?: 1f
            val expInterval = resourceItem?.targetFrameIntervalMs()
                ?: info?.let {
                    if (it.frameCount > 0) (it.totalDelayMs.toLong() / it.frameCount).coerceAtLeast(1L) else 33L
                } ?: 33L
            val expDuration = resourceItem?.targetTotalDurationMs()
                ?: info?.totalDelayMs?.toLong()
                ?: 0L

            if (gd != null) {
                // 先 seek 到第 0 帧，再 setSpeed（顺序反过来也测过，这一步是保险）
                try { gd.seekToFrame(0) } catch (_: Throwable) {}
                if (factor != 1f) {
                    try {
                        gd.setSpeed(factor)
                        LogBuffer.d("setSpeed($factor) 应用到 $resourceId")
                    } catch (e: Throwable) {
                        LogBuffer.e("setSpeed 失败: $resourceId, factor=$factor", e)
                    }
                }
            }
            return AnimResourceInstance(ref, gd, info, factor, expInterval, expDuration)
        }
    }
}

// 动画运行时：一个动画的所有资源实例。
class AnimRuntime(
    val animationId: String,
    val instances: List<AnimResourceInstance>
) {
    // 一圈时长。多个实例时以第一个为准（假定动画内各资源长度一致）。
    val totalDurationMs: Long
        get() = instances.firstOrNull()?.totalDurationMs ?: 0L

    // 每帧时长。draw 频率用。
    fun frameIntervalMs(): Long =
        instances.firstOrNull()?.frameIntervalMs() ?: 33L

    val allocationBytes: Long
        get() = instances.sumOf { it.allocationBytes }

    fun start() { instances.forEach { it.start() } }
    fun stop() { instances.forEach { it.stop() } }
    fun reset() { instances.forEach { it.reset() } }
    fun recycle() { instances.forEach { it.recycle() } }
}