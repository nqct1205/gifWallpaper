package com.nqct.gifWallpaper

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// 可拖动排序的纵向列表。
//
// 【边界限制（本轮新增）】
//   - 被拖项在视觉上不能超出第一个/最后一个 item 的中心位置。
//   - 拖到顶端时向上不再移动，拖到底端时向下不再移动。
//   - 自动滚动到 ScrollView 顶/底后自动停止，不再无限累积偏移。
//
// 【P1-2 修复】itemTops/itemHeights 在 startDrag 时重新采集。
// 【P1-3 修复】自动滚动累积偏移补偿进 dy，被拖项始终跟手。
class ReorderableColumn @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    init {
        orientation = VERTICAL
    }

    private var scrollView: ScrollView? = null
    private var itemViews: List<View> = emptyList()
    private var itemTops: IntArray = IntArray(0)
    private var itemHeights: IntArray = IntArray(0)
    private var onReorderCallback: ((Int, Int) -> Unit)? = null

    // ==================== 拖动状态 ====================
    private var dragIndex = -1
    private var currentTargetIndex = -1
    private var dragStartRawY = 0f
    private var lastRawY = 0f
    private var autoScrollOffset = 0f

    // 自动滚动
    private var autoScrollDirection = 0
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (dragIndex < 0 || autoScrollDirection == 0) return
            val sv = scrollView ?: return
            val step = (6 * resources.displayMetrics.density).toInt().coerceAtLeast(2)
            val oldScrollY = sv.scrollY
            sv.scrollBy(0, autoScrollDirection * step)
            val actualDelta = sv.scrollY - oldScrollY
            // ScrollView 已到顶/底，实际滚动量为 0，停止自动滚动
            if (actualDelta == 0) {
                autoScrollDirection = 0
                return
            }
            autoScrollOffset += actualDelta
            updateDrag(lastRawY)
            postDelayed(this, 16)
        }
    }

    // ==================== 公共 API ====================

    fun <T> setItems(
        items: List<T>,
        scrollView: ScrollView,
        itemBuilder: (item: T, index: Int, handle: View) -> View,
        onReorder: (fromIndex: Int, toIndex: Int) -> Unit
    ) {
        removeAllViews()
        this.scrollView = scrollView
        this.onReorderCallback = onReorder

        val handles = mutableListOf<View>()
        val views = mutableListOf<View>()

        items.forEachIndexed { index, item ->
            val handle = createHandleView()
            handles.add(handle)
            val view = itemBuilder(item, index, handle)
            views.add(view)
            addView(view, LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ))
        }

        itemViews = views

        post {
            if (itemViews.isEmpty()) return@post
            recalcItemBounds()
            handles.forEachIndexed { i, h -> attachHandleTouch(h, i) }
        }
    }

    // ==================== 尺寸采集 ====================

    private fun recalcItemBounds(): Boolean {
        if (itemViews.isEmpty()) return false
        val tops = IntArray(itemViews.size) { itemViews[it].top }
        val heights = IntArray(itemViews.size) { itemViews[it].height }
        if (heights.all { it <= 0 }) return false
        itemTops = tops
        itemHeights = heights
        return true
    }

    // ==================== Handle 视图 ====================

    private fun createHandleView(): View {
        return TextView(context).apply {
            text = "☰"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            setTextColor(Color.parseColor("#9E9E9E"))
        }
    }

    // ==================== 触摸处理 ====================

    private fun attachHandleTouch(handle: View, index: Int) {
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handle.parent?.requestDisallowInterceptTouchEvent(true)
                    startDrag(index, event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragIndex == index) {
                        updateDrag(event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handle.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragIndex == index) {
                        finishDrag()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startDrag(index: Int, rawY: Float) {
        if (index !in itemViews.indices) return
        if (!recalcItemBounds()) return
        dragIndex = index
        currentTargetIndex = index
        dragStartRawY = rawY
        lastRawY = rawY
        autoScrollOffset = 0f

        itemViews[index].apply {
            elevation = 16f * resources.displayMetrics.density
            scaleX = 1.03f
            scaleY = 1.03f
            alpha = 0.95f
        }
    }

    // 计算 dy 上下边界：被拖项中心不能越出第一个/最后一个 item 的中心。
    private fun computeDyBounds(): Pair<Float, Float> {
        if (dragIndex !in itemViews.indices) return -Float.MAX_VALUE to Float.MAX_VALUE
        val dragCenter = itemTops[dragIndex] + itemHeights[dragIndex] / 2f
        val topCenter = itemTops[0] + itemHeights[0] / 2f
        val last = itemViews.size - 1
        val bottomCenter = itemTops[last] + itemHeights[last] / 2f
        return (topCenter - dragCenter) to (bottomCenter - dragCenter)
    }

    private fun updateDrag(rawY: Float) {
        if (dragIndex < 0) return
        lastRawY = rawY

        val rawDy = rawY - dragStartRawY + autoScrollOffset
        val (minDy, maxDy) = computeDyBounds()
        // 边界限制：到顶/到底后不再移动
        val dy = rawDy.coerceIn(minDy, maxDy)
        itemViews[dragIndex].translationY = dy

        val dragCenter = itemTops[dragIndex] + itemHeights[dragIndex] / 2f
        val draggedCenterY = dragCenter + dy

        val newTarget = calculateTargetIndex(draggedCenterY)
        if (newTarget != currentTargetIndex) {
            currentTargetIndex = newTarget
            updateLayoutHints(newTarget)
        }

        updateAutoScroll(rawY, dy, minDy, maxDy)
    }

    private fun calculateTargetIndex(draggedCenterY: Float): Int {
        val n = itemViews.size
        if (n <= 1) return dragIndex

        var target = dragIndex
        for (i in 0 until n) {
            if (i == dragIndex) continue
            val itemCenter = itemTops[i] + itemHeights[i] / 2f
            if (i > dragIndex && draggedCenterY > itemCenter) {
                target = i
            } else if (i < dragIndex && draggedCenterY < itemCenter) {
                return i
            }
        }
        return target
    }

    private fun updateLayoutHints(targetIndex: Int) {
        if (targetIndex == dragIndex) {
            itemViews.forEachIndexed { i, v ->
                if (i != dragIndex) {
                    v.animate().translationY(0f).setDuration(150).start()
                }
            }
            return
        }

        val dragHeight = itemHeights[dragIndex].toFloat()
        val delta = if (targetIndex > dragIndex) -dragHeight else dragHeight

        for (i in itemViews.indices) {
            if (i == dragIndex) continue
            val shouldMove = if (targetIndex > dragIndex) {
                i in (dragIndex + 1)..targetIndex
            } else {
                i in targetIndex until dragIndex
            }
            val ty = if (shouldMove) delta else 0f
            itemViews[i].animate().translationY(ty).setDuration(150).start()
        }
    }

    // 自动滚动。到边界（dy 已顶到 min/max）时不滚动。
    private fun updateAutoScroll(rawY: Float, dy: Float, minDy: Float, maxDy: Float) {
        val screenHeight = resources.displayMetrics.heightPixels
        val edgePx = (80 * resources.displayMetrics.density).toInt()

        val newDir = when {
            rawY < edgePx -> -1
            rawY > screenHeight - edgePx -> 1
            else -> 0
        }

        // 到达边界时不再滚动
        val effectiveDir = when {
            newDir == -1 && dy <= minDy -> 0
            newDir == 1 && dy >= maxDy -> 0
            else -> newDir
        }

        if (effectiveDir != autoScrollDirection) {
            autoScrollDirection = effectiveDir
            removeCallbacks(autoScrollRunnable)
            if (effectiveDir != 0) {
                postDelayed(autoScrollRunnable, 16)
            }
        }
    }

    private fun finishDrag() {
        val src = dragIndex
        val dst = currentTargetIndex
        dragIndex = -1
        currentTargetIndex = -1
        autoScrollDirection = 0
        autoScrollOffset = 0f
        removeCallbacks(autoScrollRunnable)

        if (src != dst && src >= 0 && dst >= 0) {
            onReorderCallback?.invoke(src, dst)
        } else {
            itemViews.forEachIndexed { i, v ->
                v.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start()
                if (i == src) v.elevation = 0f
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(autoScrollRunnable)
    }
}