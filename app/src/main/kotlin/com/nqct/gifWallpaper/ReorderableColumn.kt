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
// 【用法】
//   val column = ReorderableColumn(context)
//   column.setItems(
//       items = config.resources.entries.toList(),
//       scrollView = 外层 ScrollView,
//       itemBuilder = { item, index, handle ->
//           // 构建 item 视图，把 handle 放到合适位置
//           // 返回的 View 会被加入 column
//       },
//       onReorder = { from, to -> ... }
//   )
//
// 【交互】
//   - 按住 handle（☰）开始拖动，被拖项跟随手指。
//   - 拖动到其他项中心越过时，其他项平滑"让开"（translationY 动画）。
//   - 拖到屏幕上下边缘时自动滚动。
//   - 松手后立即回调 onReorder(fromIndex, toIndex)，由调用方重建页面。
//
// 【视觉】
//   - 被拖项：elevation 16、放大 1.03 倍、alpha 0.95，视觉上"浮起"。
//   - 让开项：translationY 平滑偏移。
//
// 【限制】拖动过程中列表本身不滚动（自动滚动由本类处理边缘触发）。
// 项目通常 ≤12 项，一屏可容纳，无影响。
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

    // 自动滚动
    private var autoScrollDirection = 0
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (dragIndex < 0 || autoScrollDirection == 0) return
            val sv = scrollView ?: return
            val step = (6 * resources.displayMetrics.density).toInt().coerceAtLeast(2)
            sv.scrollBy(0, autoScrollDirection * step)
            // 滚动后手指在屏幕位置不变，但内容位置变了，重新计算目标索引
            updateDrag(lastRawY)
            postDelayed(this, 16)
        }
    }

    // ==================== 公共 API ====================

    // 绑定数据并重建所有 item。
    //
    // itemBuilder 接收 (item, index, handleView)，
    // 由 itemBuilder 决定把 handleView 加到 item 的哪个位置。
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

        // 等布局完成后再绑定触摸和记录高度
        post {
            if (itemViews.isEmpty()) return@post
            itemTops = IntArray(itemViews.size) { itemViews[it].top }
            itemHeights = IntArray(itemViews.size) { itemViews[it].height }
            handles.forEachIndexed { i, h -> attachHandleTouch(h, i) }
        }
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
                    // 关键：按下 handle 时阻止外层 ScrollView 拦截触摸。
                    // 否则 ScrollView 在 MOVE 超过 touch slop 后会夺走事件，
                    // handle 只能收到 CANCEL，拖动流程被掐断。
                    // requestDisallowInterceptTouchEvent 会向上传递到所有祖先，
                    // 因此调用一次即可让整条链上的 ScrollView 都不拦截。
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
                    // 恢复拦截：后续普通触摸仍可正常滚动页面
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
        dragIndex = index
        currentTargetIndex = index
        dragStartRawY = rawY
        lastRawY = rawY

        // 视觉提升
        itemViews[index].apply {
            elevation = 16f * resources.displayMetrics.density
            scaleX = 1.03f
            scaleY = 1.03f
            alpha = 0.95f
        }
    }

    private fun updateDrag(rawY: Float) {
        if (dragIndex < 0) return
        lastRawY = rawY

        // 1. 被拖项跟随手指
        val dy = rawY - dragStartRawY
        itemViews[dragIndex].translationY = dy

        // 2. 计算被拖项中心（相对于 ReorderableColumn）
        val draggedCenterY = itemTops[dragIndex] + itemHeights[dragIndex] / 2f + dy

        // 3. 计算目标索引
        val newTarget = calculateTargetIndex(draggedCenterY)
        if (newTarget != currentTargetIndex) {
            currentTargetIndex = newTarget
            updateLayoutHints(newTarget)
        }

        // 4. 自动滚动
        updateAutoScroll(rawY)
    }

    // 目标索引计算：
    //   被拖项中心越过某 item 的原始中心时触发交换。
    //   向上拖：找到第一个"被拖项中心 < item 原始中心"的 item，返回其 index。
    //   向下拖：找到最后一个"被拖项中心 > item 原始中心"的 item，返回其 index。
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

    // 让开动画：被拖项跨过的所有项平移一个"被拖项高度"
    private fun updateLayoutHints(targetIndex: Int) {
        if (targetIndex == dragIndex) {
            // 恢复所有非拖动项
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

    // 自动滚动：拖到屏幕上下边缘 80dp 内触发
    private fun updateAutoScroll(rawY: Float) {
        val screenHeight = resources.displayMetrics.heightPixels
        val edgePx = (80 * resources.displayMetrics.density).toInt()

        val newDir = when {
            rawY < edgePx -> -1
            rawY > screenHeight - edgePx -> 1
            else -> 0
        }

        if (newDir != autoScrollDirection) {
            autoScrollDirection = newDir
            removeCallbacks(autoScrollRunnable)
            if (newDir != 0) {
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
        removeCallbacks(autoScrollRunnable)

        if (src != dst && src >= 0 && dst >= 0) {
            // 重建页面会重置所有视觉，无需手动恢复动画
            onReorderCallback?.invoke(src, dst)
        } else {
            // 未发生变化：恢复视觉
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
