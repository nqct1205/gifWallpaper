package com.nqct.gifWallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 状态列表页。
 *
 * 【展示】每个状态的“状态名 (id)”、引用动画的“动画名 (id)”、周期范围、转移规则数、是否默认。
 * 点击行进入编辑页；行内提供“设为默认”与“删除”。
 *
 * 【删除】由 [MainActivity.deleteState] 处理级联清理（转移规则、事件规则）。
 */
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
    }

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // ---- 顶栏 ----
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(Button(activity).apply {
            text = "< 返回"
            setOnClickListener { callbacks.onBack() }
        })
        topBar.addView(TextView(activity).apply {
            text = "状态管理"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        // ---- 新建 ----
        root.addView(Button(activity).apply {
            text = "新建状态"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener { callbacks.onCreateState() }
        }, matchWrap(top = 20, bottom = 20))

        // ---- 列表 ----
        if (config.states.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "暂无状态，请先新建。\n注意：状态需要引用动画，请先在「动画」页面创建动画。"
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 50)
            })
        } else {
            for ((id, state) in config.states.toSortedMap()) {
                root.addView(buildStateItem(id, state), matchWrap(bottom = 10))
            }
        }

        return root
    }

    private fun buildStateItem(id: String, state: StateNode): View {
        val isDefault = (id == config.defaultStateId)
        val animDisplay = config.animations[state.animationId]
            ?.let { formatAnimDisplay(it) }
            ?: "（未引用动画）"

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
            setOnClickListener { callbacks.onEditState(id) }
        }

        // 标题行：名字 (id) + 默认标记
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
        card.addView(headerRow)

        // 信息行
        card.addView(TextView(activity).apply {
            text = "动画: $animDisplay  |  周期: ${state.periodMin}~${state.periodMax} 圈  |  转移规则: ${state.rules.size}"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 5)
        })

        // 操作行
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

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }
}