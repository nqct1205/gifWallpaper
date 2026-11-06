package com.nqct.gifWallpaper

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 触摸状态快照。downElapsedMs 保留按下时刻的 elapsedRealtime，
 * 调用方可用 (now - downElapsedMs) 实时计算按压时长。
 */
data class TouchState(
    val isTouching: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val downElapsedMs: Long = 0L,
    val lastClickText: String = "无",
    val lastSwipeText: String = "无"
)

/**
 * 实时坐标 + 按压时长 + 点击判定 + 滑动方向判定。
 *
 * 使用方式：
 *   touchTracker.onTouchEvent(event)   // 在 Engine.onTouchEvent 里调用
 *   val s = touchTracker.state         // 渲染线程读快照
 *
 * 判定阈值：clickSlopPx 点击允许的最大位移；clickMaxDurationMs 点击允许的最长按压。
 */
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

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val direction = if (abs(dx) > abs(dy)) {
                    if (dx > 0) "向右" else "向左"
                } else {
                    if (dy > 0) "向下" else "向上"
                }
                state = state.copy(lastSwipeText = "$direction  ${timeFormat.format(Date())}")
                return true
            }
        }
    )

    fun onTouchEvent(event: MotionEvent) {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                state = state.copy(
                    isTouching = true, x = event.x, y = event.y,
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
                    isTouching = false, x = event.x, y = event.y,
                    lastClickText = if (isClick) {
                        "(${Math.round(event.x)}, ${Math.round(event.y)})  ${timeFormat.format(Date())}"
                    } else state.lastClickText
                )
            }
            MotionEvent.ACTION_CANCEL -> state = state.copy(isTouching = false)
        }
    }
}