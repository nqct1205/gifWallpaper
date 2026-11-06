package com.nqct.gifWallpaper

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 吸色对话框。
 *
 * 【用途】显示一张静态位图（GIF 第一帧），用户拖动准星选择颜色，点“确定”回调。
 *
 * 【坐标映射】准星位置以归一化坐标 (0..1) 存储，映射到位图像素坐标取色，
 * 因此 View 大小变化（如旋转）不影响已选位置。
 *
 * 【前提】传入的 [Bitmap] 必须是软件位图（config 非 HARDWARE），
 * 否则 [Bitmap.getPixel] 会抛异常。调用方（[AnimationEditPageBuilder]）在解码时
 * 已设置 `ImageDecoder.ALLOCATOR_SOFTWARE`。
 */
object ColorPickerDialog {

    fun show(
        context: Context,
        bitmap: Bitmap,
        onPicked: (Int) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
        }

        root.addView(TextView(context).apply {
            text = "拖动准星到目标位置，然后点确定"
            textSize = 14f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        })

        val picker = ColorPickerView(context, bitmap).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(picker)

        // 当前颜色预览
        val infoRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 0)
        }
        val colorPreview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(40, 40)
            setBackgroundColor(picker.getPickedColor())
        }
        val colorText = TextView(context).apply {
            text = formatArgbColor(picker.getPickedColor())
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(10, 0, 0, 0)
        }
        picker.onColorChanged = { c ->
            colorPreview.setBackgroundColor(c)
            colorText.text = formatArgbColor(c)
        }
        infoRow.addView(colorPreview)
        infoRow.addView(colorText)
        root.addView(infoRow)

        // 底部按钮
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }
        btnRow.addView(Button(context).apply {
            text = "取消"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(Button(context).apply {
            text = "确定"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                onPicked(picker.getPickedColor())
                dialog.dismiss()
            }
        })
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.95f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.85f).toInt()
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        dialog.show()
    }

    /**
     * 自定义 View：显示位图 + 可拖动准星。
     *
     * - 位图按 fitCenter 缩放绘制，`destRect` 记录实际绘制区域。
     * - 准星位置归一化为 pickNx/pickNy (0..1)，对应位图像素坐标。
     * - 触摸按下 / 移动均更新准星，并触发 [onColorChanged]。
     */
    private class ColorPickerView(
        context: Context,
        private val bitmap: Bitmap
    ) : View(context) {

        var onColorChanged: ((Int) -> Unit)? = null

        private var pickNx = 0.5f
        private var pickNy = 0.5f

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val crossShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
            alpha = 150
        }

        private val destRect = RectF()
        private var offsetX = 0f
        private var offsetY = 0f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            if (bw <= 0f || bh <= 0f) return
            val scale = minOf(w / bw, h / bh)
            val dw = bw * scale
            val dh = bh * scale
            offsetX = (w - dw) / 2f
            offsetY = (h - dh) / 2f
            destRect.set(offsetX, offsetY, offsetX + dw, offsetY + dh)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (destRect.isEmpty) return
            canvas.drawBitmap(bitmap, null, destRect, bitmapPaint)
            val cx = offsetX + destRect.width() * pickNx
            val cy = offsetY + destRect.height() * pickNy
            canvas.drawCircle(cx, cy, 24f, crossShadowPaint)
            canvas.drawCircle(cx, cy, 24f, crossPaint)
            canvas.drawLine(cx - 36f, cy, cx + 36f, cy, crossShadowPaint)
            canvas.drawLine(cx, cy - 36f, cx, cy + 36f, crossShadowPaint)
            canvas.drawLine(cx - 36f, cy, cx + 36f, cy, crossPaint)
            canvas.drawLine(cx, cy - 36f, cx, cy + 36f, crossPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (destRect.isEmpty) return false
                    pickNx = ((event.x - offsetX) / destRect.width()).coerceIn(0f, 1f)
                    pickNy = ((event.y - offsetY) / destRect.height()).coerceIn(0f, 1f)
                    invalidate()
                    onColorChanged?.invoke(getPickedColor())
                    return true
                }
            }
            return true
        }

        /** 读取准星位置对应的位图像素颜色。 */
        fun getPickedColor(): Int {
            val px = (pickNx * (bitmap.width - 1)).toInt().coerceIn(0, bitmap.width - 1)
            val py = (pickNy * (bitmap.height - 1)).toInt().coerceIn(0, bitmap.height - 1)
            return bitmap.getPixel(px, py)
        }
    }
}