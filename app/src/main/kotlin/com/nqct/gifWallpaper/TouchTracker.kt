package com.nqct.gifWallpaper

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 触摸状态快照。
//
// 【清理说明】原 lastSwipeText 与 GestureDetector.onFling 无消费方，已移除。
// 只保留点击检测 + 坐标 + 按压时长。
data class TouchState(
    val isTouching: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val downElapsedMs: Long = 0L,
    val lastClickText: String = "无"
)

// 实时坐标 + 按压时长 + 点击判定。
class TouchTracker(
    context: Context,
    private val clickSlopPx: Float = 60f,
    private val clickMaxDurationMs: Long = 800L
) {

    @Volatile
    var state: TouchState = TouchState()
        private set

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var downX = 0f
    private var downY = 0f

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                state = state.copy(
                    isTouching = true,
                    x = event.x,
                    y = event.y,
                    downElapsedMs = SystemClock.elapsedRealtime()
                )
            }
            MotionEvent.ACTION_MOVE -> {
                state = state.copy(x = event.x, y = event.y)
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val dt = SystemClock.elapsedRealtime() - state.downElapsedMs
                val isClick = dx * dx + dy * dy < clickSlopPx * clickSlopPx &&
                        dt < clickMaxDurationMs
                state = state.copy(
                    isTouching = false,
                    x = event.x,
                    y = event.y,
                    lastClickText = if (isClick) {
                        "(${Math.round(event.x)}, ${Math.round(event.y)})  ${timeFormat.format(Date())}"
                    } else state.lastClickText
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                state = state.copy(isTouching = false)
            }
        }
    }
}