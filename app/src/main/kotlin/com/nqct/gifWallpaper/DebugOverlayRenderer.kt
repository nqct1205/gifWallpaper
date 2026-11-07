package com.nqct.gifWallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale

// 壁纸调试信息 / 错误信息的绘制器（无状态、无业务依赖）。
// 显示文本（stateName / animationDisplay / infoMessage）由调用方传入已格式化的字符串。
class DebugOverlayRenderer {

    data class DebugState(
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
        val frameIndexIsEstimate: Boolean = true,
        val animationDisplay: String = "",
        val postureText: String = "",
        val lastShakeText: String = "无",
        val gForce: Float = 0f,
        val shakeThresholdG: Float = 2.4f,
        val sensorStatus: String = "未注册",
        val isShaking: Boolean = false,
        val isTouching: Boolean = false,
        val enterStateCount: Int = 0,
        val cycleTimerCount: Int = 0,
        // 临时信息提示（如"正在重新加载动画"），非空时显示在面板顶部。
        // 调用方负责在合适时间后清空（传空串）。
        val infoMessage: String = ""
    )

    private val debugTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val infoTextPaint = Paint().apply {
        color = Color.parseColor("#FFD54F")
        textSize = 32f
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

    // 绘制红字错误面板（画布中央偏上）。
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

    // 绘制调试信息面板。
    // infoMessage 非空时显示在最顶部一行（黄色高亮）。
    fun drawDebugInfo(canvas: Canvas, s: DebugState) {
        val warnSign = if (s.isAlarm) " [⚠ 超阈值]" else ""

        val estimateMark = if (s.frameIndexIsEstimate) " (估算)" else ""
        val frameText = if (s.frameIndex >= 0 && s.frameCount > 0) {
            " 第 ${s.frameIndex + 1}/${s.frameCount} 帧$estimateMark"
        } else ""

        val sensorLine = buildString {
            append("\n姿态: ").append(s.postureText.ifEmpty { "未知" })
            if (s.isShaking) append("  [摇一摇冷却中]")
            if (s.isTouching) append("  [触摸中]")
            append("\n最近摇一摇: ").append(s.lastShakeText)
            append("  |  当前 ").append(String.format(Locale.US, "%.2f", s.gForce)).append("g")
            append(" (阈值 ").append(String.format(Locale.US, "%.1f", s.shakeThresholdG)).append("g)")
            append("\n传感器: ").append(s.sensorStatus)
            append("\n计数: enter=").append(s.enterStateCount)
                .append("  timer=").append(s.cycleTimerCount)
        }

        val memoryText = String.format(
            Locale.US,
            "状态: %s (第 %d/%d 圈%s)%s\n动画: %s\n当前帧耗时: %.2f ms (限 %d ms)\n内存总计: %d MB (限 %d MB)\n刷新间隔: %d ms%s",
            s.stateName, s.currentPlayCount + 1, s.targetSelfPlay, frameText, warnSign,
            s.animationDisplay.ifEmpty { "无" },
            s.drawTimeMs, s.limitDrawTime,
            s.currentMemMb, s.limitMemory, s.frameIntervalMs,
            sensorLine
        )

        val maxWidth = canvas.width * 0.9f
        val centerX = canvas.width / 2f
        val centerY = if (s.hasError) canvas.height / 2f * 1.4f else canvas.height / 2f

        // 计算整体高度（含 infoMessage 行）
        var totalHeight = 0f
        val infoLines = if (s.infoMessage.isNotEmpty()) s.infoMessage.split("\n") else emptyList()
        for (line in infoLines) {
            var start = 0
            while (start < line.length) {
                val count = infoTextPaint.breakText(line, start, line.length, true, maxWidth, null)
                totalHeight += infoTextPaint.textSize * 1.2f
                start += count.coerceAtLeast(1)
            }
            if (line.isEmpty()) totalHeight += infoTextPaint.textSize * 1.2f
        }
        for (line in memoryText.split("\n")) {
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

        var currentY = centerY - totalHeight / 2 + infoTextPaint.textSize
        if (infoLines.isNotEmpty()) {
            currentY = drawWrappedText(canvas, s.infoMessage, infoTextPaint, centerX, currentY, maxWidth)
        }
        drawWrappedText(canvas, memoryText, debugTextPaint, centerX, currentY, maxWidth)
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