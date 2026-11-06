package com.nqct.gifWallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 状态图页面。
 *
 * 【布局】
 *  - 顶部栏：返回 + 标题 + 重置视图。
 *  - 中部：[StateGraphView]（占满剩余空间，支持拖动缩放双击）。
 *  - 底部：事件规则框列表（水平滚动）。
 *
 * 【交互】
 *  - 单击事件框：通知 [StateGraphView] 高亮该事件；再单击同一框取消高亮。
 *  - 单击图区空白：清除事件高亮。
 *  - 双击节点：回调 [Callbacks.onNodeDoubleTap]。
 *  - 双击事件框：回调 [Callbacks.onEventDoubleTap]。
 *
 * 【双击事件框实现】用 [GestureDetector] 接管触摸，避免单击和双击冲突。
 */
class StateGraphPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onNodeDoubleTap(stateId: String)
        fun onEventDoubleTap(ruleId: String)
    }

    /** 事件框列表：用于高亮切换时刷新所有框的背景。 */
    private val eventBoxes = mutableListOf<Pair<EventRule, View>>()

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val graphView = StateGraphView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // ---- 顶栏 ----
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 20, 20, 10)
            setBackgroundColor(Color.WHITE)
        }
        topBar.addView(Button(activity).apply {
            text = "< 返回"
            setOnClickListener { callbacks.onBack() }
        })
        topBar.addView(TextView(activity).apply {
            text = "状态图"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = 20 }
        })
        topBar.addView(Button(activity).apply {
            text = "重置视图"
            setOnClickListener { graphView.fitToScreen() }
        })
        root.addView(topBar)

        // ---- 中部状态图 ----
        root.addView(graphView)

        // ---- 底部事件规则区 ----
        root.addView(buildEventSection(graphView))

        // ---- 数据 & 回调 ----
        graphView.setData(
            config.states.values.toList(),
            config.animations,
            config.defaultStateId
        )
        graphView.setOnNodeDoubleTapListener { stateId ->
            callbacks.onNodeDoubleTap(stateId)
        }

        return root
    }

    // ==================== 事件区域 ====================

    private fun buildEventSection(graphView: StateGraphView): View {
        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(20, 10, 20, 15)
        }

        section.addView(TextView(activity).apply {
            text = "事件触发（单击查看相关箭头，双击编辑）"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 8)
        })

        if (config.eventRules.isEmpty()) {
            section.addView(TextView(activity).apply {
                text = "暂无事件规则"
                textSize = 12f
                setTextColor(Color.LTGRAY)
            })
            return section
        }

        eventBoxes.clear()

        val scroll = HorizontalScrollView(activity)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        for (rule in config.eventRules) {
            val box = buildEventBox(rule, graphView)
            eventBoxes.add(rule to box)
            row.addView(box, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 10 })
        }

        scroll.addView(row)
        section.addView(scroll)

        refreshEventBoxVisuals(graphView)
        return section
    }

    private fun buildEventBox(rule: EventRule, graphView: StateGraphView): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
            setBackgroundColor(Color.WHITE)
        }

        // 事件标题
        box.addView(TextView(activity).apply {
            text = eventTitle(rule)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })

        // 目标状态
        val targetText = if (rule.targetStateId.isEmpty()) {
            "未设置"
        } else {
            formatStateDisplay(config.states[rule.targetStateId])
        }
        box.addView(TextView(activity).apply {
            text = "→ $targetText"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 3, 0, 0)
        })

        // 单击 / 双击用 GestureDetector 区分
        val gd = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 同一框再点一次：取消高亮
                if (graphView.highlightedEventRule?.id == rule.id) {
                    graphView.setHighlightedEventRule(null)
                } else {
                    graphView.setHighlightedEventRule(rule)
                }
                refreshEventBoxVisuals(graphView)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                callbacks.onEventDoubleTap(rule.id)
                return true
            }
        })
        box.setOnTouchListener { _, event -> gd.onTouchEvent(event) }

        return box
    }

    /** 根据当前高亮规则刷新所有事件框的视觉（高亮橙色背景/边框）。 */
    private fun refreshEventBoxVisuals(graphView: StateGraphView) {
        val highlighted = graphView.highlightedEventRule?.id
        for ((rule, box) in eventBoxes) {
            if (rule.id == highlighted) {
                box.setBackgroundColor(Color.parseColor("#FFE0B2")) // 浅橙
            } else {
                box.setBackgroundColor(Color.WHITE)
            }
        }
    }

    private fun eventTitle(rule: EventRule): String {
        return when (rule.type) {
            EventType.ORIENTATION -> {
                val dir = when (rule.triggerParam) {
                    EventRule.ORIENTATION_FORWARD -> "正向"
                    EventRule.ORIENTATION_REVERSE -> "反向"
                    else -> ""
                }
                "方向改变（$dir）"
            }
            EventType.SHAKE -> "摇一摇"
            EventType.CLICK -> "点击"
        }
    }
}