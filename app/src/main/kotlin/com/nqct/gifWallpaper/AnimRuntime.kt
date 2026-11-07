package com.nqct.gifWallpaper

import android.graphics.Canvas
import pl.droidsonroids.gif.GifDrawable
import java.io.File

// 单个资源运行时实例：持有一个独立的 GifDrawable。
//
// 设计原则：
//   - 每个实例独立的 GifDrawable，绝不共享（避免进度同步）。
//   - 由 GifDrawable.start() 自己推进帧，不做手动 seek。
//   - 状态切换时 seekToFrame(0) 确保从第一帧开始，一次 seek 就够。
//
// 若 JNI 加载失败或文件损坏，gifDrawable 为 null，此时该实例降级为空（不绘制）。
class AnimResourceInstance(
    val ref: AnimationResourceRef,
    val gifDrawable: GifDrawable?,
    val info: GifInfo?
) {
    val frameCount: Int
        get() = gifDrawable?.numberOfFrames ?: info?.frameCount ?: 0

    // 一轮播放时长。优先 GifDrawable，兜底 GifInfo。
    val totalDurationMs: Long
        get() = gifDrawable?.duration?.toLong()?.takeIf { it > 0 }
            ?: info?.totalDelayMs?.toLong()?.takeIf { it > 0 }
            ?: 0L

    // 预估内存占用（字节），用于日志。allocationByteCount 已是 Long，无需再转。
    val allocationBytes: Long
        get() = try { gifDrawable?.allocationByteCount ?: 0L } catch (_: Throwable) { 0L }

    fun start() {
        try { gifDrawable?.start() } catch (e: Throwable) { LogBuffer.e("实例 start 失败", e) }
    }

    fun stop() {
        try { gifDrawable?.stop() } catch (_: Throwable) {}
    }

    // 停止 + 跳转到第 0 帧。下一帧 draw 就是第一帧。
    // 这是"闪最后一帧"根治的关键：一次性 seek 到 0，不用每帧 seek。
    fun reset() {
        try { gifDrawable?.stop() } catch (_: Throwable) {}
        try { gifDrawable?.seekToFrame(0) } catch (e: Throwable) { LogBuffer.e("seekToFrame(0) 失败", e) }
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
        // 从磁盘构建实例。预 seek 到第 0 帧，让首次绘制快。
        fun fromFile(resourceId: String, ref: AnimationResourceRef, file: File): AnimResourceInstance {
            val gd = try {
                if (file.exists() && file.length() > 0) GifDrawable(file) else null
            } catch (e: Throwable) {
                LogBuffer.e("GifDrawable 加载失败: $resourceId", e)
                null
            }
            if (gd != null) {
                try { gd.seekToFrame(0) } catch (_: Throwable) {}
            }
            val info = GifMetadataParser.parse(file)
            return AnimResourceInstance(ref, gd, info)
        }
    }
}

// 动画运行时：一个动画的所有资源实例。
//
// 同一动画内的多个资源假定长度一致（用户复用同一动画）。
// 以第一个资源的时长作为"一圈"的时长。
class AnimRuntime(
    val animationId: String,
    val instances: List<AnimResourceInstance>
) {
    val totalDurationMs: Long
        get() = instances.firstOrNull()?.totalDurationMs ?: 0L

    val allocationBytes: Long
        get() = instances.sumOf { it.allocationBytes }

    fun start() { instances.forEach { it.start() } }
    fun stop() { instances.forEach { it.stop() } }
    fun reset() { instances.forEach { it.reset() } }
    fun recycle() { instances.forEach { it.recycle() } }
}