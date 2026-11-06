package com.nqct.gifWallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale

/**
 * 壁纸调试信息 / 错误信息的绘制器（无状态、无业务依赖）。
 */
class DebugOverlayRenderer {

    /**
     * 调试面板数据快照。
     *
     * 【显示文本说明】[stateName] 与 [animationDisplay] 由调用方传入**已格式化**的字符串，
     * 本类不做名字/id 拼接（保持绘制器与数据模型解耦）。
     * 调用方（[GifWallpaperService.buildDebugState]）使用
     * `formatStateDisplay` / `formatAnimDisplay` 生成"名字 (id)"格式。
     */
    data class DebugState(
        /** 状态的显示文本：名字 (id)；无状态时为 "None" */
        val stateName: String,
        val currentPlayCount: Int,
        val targetSelfPlay: Int,
        val frameIndex: Int,
        val frameCount: Int,
        val drawTimeMs: Float,
        val limitDrawTime: Int,
        val currentMemMb: Int,
        val limitMemory: Int,
        val frameIntervalMs: Long,
        val isAlarm: Boolean,
        val hasError: Boolean,
        /** 帧索引是否为估算值（基于时间轴推算，非精确帧回调） */
        val frameIndexIsEstimate: Boolean = true,
        /** 动画的显示文本：名字 (id)；无动画时为 "无" */
        val animationDisplay: String = "",
        /** 传感器姿态文本 */
        val postureText: String = "",
        /** 摇一摇冷却中 */
        val isShaking: Boolean = false,
        /** 当前触摸中 */
        val isTouching: Boolean = false
    )

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

    /** 绘制红字错误面板（画布中央偏上）。 */
    fun drawErrorInfo(canvas: Canvas, errorMsg: String) {
        if (errorMsg.isBlank()) return

        val maxWidth = canvas.width * 0.9f
        val centerX = canvas.width / 2f
        val centerY = canvas.height / 2f * 0.6f

        val lines = errorMsg.split("\n")
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
        val rect = RectF(
            centerX - maxWidth / 2 - padding,
            centerY - totalHeight / 2 - padding,
            centerX + maxWidth / 2 + padding,
            centerY + totalHeight / 2 + padding
        )
        canvas.drawRoundRect(rect, 20f, 20f, debugBgPaint)

        val startY = centerY - totalHeight / 2 + errorTextPaint.textSize
        drawWrappedText(canvas, errorMsg, errorTextPaint, centerX, startY, maxWidth)
    }

    /** 绘制调试信息面板。 */
    fun drawDebugInfo(canvas: Canvas, s: DebugState) {
        val warnSign = if (s.isAlarm) " [⚠ 超阈值]" else ""

        val estimateMark = if (s.frameIndexIsEstimate) " (估算)" else ""
        val frameText = if (s.frameIndex >= 0 && s.frameCount > 0) {
            " 第 ${s.frameIndex + 1}/${s.frameCount} 帧$estimateMark"
        } else ""

        val sensorText = buildString {
            if (s.postureText.isNotEmpty()) append("\n姿态: ${s.postureText}")
            if (s.isShaking) append("  [摇一摇冷却中]")
            if (s.isTouching) append("  [触摸中]")
        }

        val memoryText = String.format(
            Locale.US,
            "状态: %s (第 %d/%d 圈%s)%s\n动画: %s\n当前帧耗时: %.2f ms (限 %d ms)\n内存总计: %d MB (限 %d MB)\n刷新间隔: %d ms%s",
            s.stateName, s.currentPlayCount + 1, s.targetSelfPlay, frameText, warnSign,
            s.animationDisplay.ifEmpty { "无" },
            s.drawTimeMs, s.limitDrawTime,
            s.currentMemMb, s.limitMemory, s.frameIntervalMs,
            sensorText
        )

        val maxWidth = canvas.width * 0.9f
        val centerX = canvas.width / 2f
        val centerY = if (s.hasError) canvas.height / 2f * 1.4f else canvas.height / 2f

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
        val rect = RectF(
            centerX - maxWidth / 2 - padding,
            centerY - totalHeight / 2 - padding,
            centerX + maxWidth / 2 + padding,
            centerY + totalHeight / 2 + padding
        )

        val oldColor = debugBgPaint.color
        if (s.isAlarm) debugBgPaint.color = Color.parseColor("#CC1A1A1A")
        canvas.drawRoundRect(rect, 20f, 20f, debugBgPaint)
        debugBgPaint.color = oldColor

        val startY = centerY - totalHeight / 2 + debugTextPaint.textSize
        drawWrappedText(canvas, memoryText, debugTextPaint, centerX, startY, maxWidth)
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        centerX: Float,
        startY: Float,
        maxWidth: Float
    ): Float {
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
}