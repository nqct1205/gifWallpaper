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

// 事件规则列表页。
// 展示：事件类型（含方向参数）、附加条件、触发状态集、目标状态。
// 触发状态集为空时显示"任意状态"。
class EventRuleListPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onCreateRule()
        fun onEditRule(ruleId: String)
        fun onDeleteRule(ruleId: String)
    }

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // 顶栏
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(Button(activity).apply {
            text = "< 返回"
            setOnClickListener { callbacks.onBack() }
        })
        topBar.addView(TextView(activity).apply {
            text = "事件规则"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        // 说明
        root.addView(TextView(activity).apply {
            text = "事件规则：当主事件触发、且附加条件满足时，若当前状态在触发状态集中，则跳转到目标状态。\n" +
                "触发状态集为空表示“任意状态”；无附加条件表示无条件。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 10, 0, 10)
        })

        // 新建
        root.addView(Button(activity).apply {
            text = "新建事件规则"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener { callbacks.onCreateRule() }
        }, matchWrap(top = 10, bottom = 20))

        // 列表
        if (config.eventRules.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "暂无事件规则。"
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 50)
            })
        } else {
            for (rule in config.eventRules) {
                root.addView(buildRuleItem(rule), matchWrap(bottom = 10))
            }
        }

        return root
    }

    private fun buildRuleItem(rule: EventRule): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
            setOnClickListener { callbacks.onEditRule(rule.id) }
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(activity).apply {
            text = typeLabel(rule)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(Button(activity).apply {
            text = "删除"
            textSize = 12f
            setTextColor(Color.RED)
            setOnClickListener { callbacks.onDeleteRule(rule.id) }
        })
        card.addView(headerRow)

        // 附加条件（若有）
        val conditionText = conditionLabel(rule)
        if (conditionText.isNotEmpty()) {
            card.addView(TextView(activity).apply {
                text = "附加条件: $conditionText"
                textSize = 12f
                setTextColor(Color.parseColor("#FF6D00"))
                setPadding(0, 5, 0, 0)
            })
        }

        // 触发状态 → 目标状态
        val sourceText = if (rule.sourceStateIds.isEmpty()) {
            "任意状态"
        } else {
            rule.sourceStateIds.joinToString("、") { sid ->
                formatStateDisplay(config.states[sid])
            }
        }
        val targetText = if (rule.targetStateId.isEmpty()) {
            "未设置"
        } else {
            formatStateDisplay(config.states[rule.targetStateId])
        }
        card.addView(TextView(activity).apply {
            text = "触发状态: $sourceText  →  目标状态: $targetText"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })

        return card
    }

    // 事件标题：方向事件显示 6 方向之一，其他显示类型名。
    private fun typeLabel(rule: EventRule): String {
        return when (rule.type) {
            EventType.ORIENTATION -> {
                val ori = DeviceOrientation.values().firstOrNull { it.name == rule.triggerParam }
                val dir = ori?.label ?: "未设置"
                "${rule.type.label}（变为 $dir）"
            }
            else -> rule.type.label
        }
    }

    // 附加条件描述。当前仅 ORIENTATION 类型。
    private fun conditionLabel(rule: EventRule): String {
        if (rule.conditions.isEmpty()) return ""
        return rule.conditions.joinToString("、") { c ->
            when (c.type) {
                EventType.ORIENTATION -> {
                    val ori = DeviceOrientation.values().firstOrNull { it.name == c.param }
                    "方向 = ${ori?.label ?: c.param}"
                }
                else -> c.type.label
            }
        }
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }
}