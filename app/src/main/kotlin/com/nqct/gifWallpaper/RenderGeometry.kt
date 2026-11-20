package com.nqct.gifWallpaper

import android.graphics.Color
import android.graphics.RectF

// 渲染几何与颜色的纯计算。
//
// 【职责边界】只做坐标/颜色计算，无状态、不持有 Canvas、不依赖 Service 生命周期。
// 从 GifWallpaperService 的 computeBounds / resolveCurrentBackgroundColor 抽出，
// 便于脱离壁纸服务单独验证（输入相同必得相同输出）。
object RenderGeometry {

    // 计算某资源引用在屏幕上的绘制矩形。
    // 纯函数：相同 ref/info/screenW/screenH 必得相同 RectF。
    fun computeBounds(
        ref: AnimationResourceRef,
        info: GifInfo,
        screenW: Int,
        screenH: Int
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

    // 解析当前状态对应动画的背景色。无动画或无有效颜色时返回黑色。
    fun resolveBackgroundColor(config: ProjectConfig, stateId: String): Int {
        val animId = config.states[stateId]?.animationId ?: return Color.BLACK
        val anim = config.animations[animId] ?: return Color.BLACK
        return parseColorSafe(anim.backgroundColor) ?: Color.BLACK
    }
}