package com.nqct.gifWallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// 状态列表页。骨架由 ListPageScaffold 提供，本类只负责数据源与 item 渲染。
// 排序结果影响列表顺序与状态图布局顺序，不影响引用关系与默认状态。
class StateListPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onCreateState()
        fun onEditState(stateId: String)
        fun onDeleteState(stateId: String)
        fun onSetDefault(stateId: String)
        fun onReorderStates(fromIndex: Int, toIndex: Int)
    }

    fun build(scrollView: ScrollView): View {
        return ListPageScaffold.build(
            activity = activity,
            scrollView = scrollView,
            title = "状态管理",
            actionText = "新建状态",
            actionColor = Color.parseColor("#4CAF50"),
            emptyText = "暂无状态，请先新建。\n注意：状态需要引用动画，请先在「动画」页面创建动画。",
            onBack = { callbacks.onBack() },
            onAction = { callbacks.onCreateState() },
            itemCount = config.states.size,
            bindItems = { column ->
                val entries = config.states.entries.toList()
                column.setItems(
                    items = entries,
                    scrollView = scrollView,
                    itemBuilder = { entry, _, handle ->
                        buildStateItem(entry.key, entry.value, handle)
                    },
                    onReorder = { from, to -> callbacks.onReorderStates(from, to) }
                )
            }
        )
    }

    private fun buildStateItem(id: String, state: StateNode, handle: View): View {
        val isDefault = (id == config.defaultStateId)
        val animDisplay = config.animations[state.animationId]
            ?.let { formatAnimDisplay(it) }
            ?: "（未引用动画）"

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
            setOnClickListener { callbacks.onEditState(id) }
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(activity).apply {
            text = formatStateDisplay(state)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        })
        if (isDefault) {
            headerRow.addView(TextView(activity).apply {
                text = "  默认"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setPadding(10, 4, 10, 4)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 10 }
            })
        }
        headerRow.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        headerRow.addView(handle)
        card.addView(headerRow)

        card.addView(TextView(activity).apply {
            text = "动画: $animDisplay  |  周期: ${state.periodMin}~${state.periodMax} 圈  |  转移规则: ${state.rules.size}"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 5)
        })

        val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        if (!isDefault) {
            actionRow.addView(Button(activity).apply {
                text = "设为默认"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { callbacks.onSetDefault(id) }
            })
        }
        actionRow.addView(Button(activity).apply {
            text = "删除"
            textSize = 12f
            setTextColor(Color.RED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { callbacks.onDeleteState(id) }
        })
        card.addView(actionRow)

        return card
    }
}