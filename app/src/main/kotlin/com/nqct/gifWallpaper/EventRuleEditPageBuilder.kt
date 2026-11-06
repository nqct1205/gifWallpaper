package com.nqct.gifWallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView

/**
 * 事件规则编辑页。
 *
 * 【编辑内容】
 *  - 事件类型：方向改变 / 摇一摇 / 点击。
 *  - 触发参数：仅方向事件需要（正向 / 反向）。
 *  - 触发状态集：多选；**不勾选任何状态 = 任意状态**。显示“状态名 (id)”。
 *  - 目标状态：单选。显示“状态名 (id)”。
 *
 * 【新建模式】ruleId 为 null 时立即生成新规则并加入 config，
 * 用户点“删除”即移除（等价取消）。
 *
 * 【空规则的处理路径】说明本页不主动清理空规则的边界：
 *  1. 新建模式下用户点系统返回键（非“删除规则”按钮）时，
 *     规则已加入 config.eventRules，但 [targetStateId] 可能仍为空字符串。
 *     本页**不做清理**，原因：
 *       - [PlaybackController.handleEvent] 遇到 targetStateId 为空时会直接忽略，无害；
 *       - 若用户在编辑期间临时切到其他状态页添加状态再返回，规则内容仍保留，
 *         强行清理会丢掉用户已填的触发状态集 / 类型选择。
 *  2. 用户下次进入列表页会看到这条“目标状态: 未设置”的规则，可手动删除或补完。
 *  3. 若未来需要自动清理，可在 [MainActivity] 的配置保存前做一次全局校验，
 *     把 targetStateId 为空且 sourceStateIds 也为空的“双重空”规则剔除；
 *     当前不实现，避免复杂化。
 *
 * 【当前策略】规则是确定性的：匹配即跳转。后续可扩展概率，见 [EventRule] 注释。
 */
class EventRuleEditPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val ruleId: String?,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onSaved()
        fun onDelete()
    }

    fun build(): View {
        // 新建模式：立即创建并加入 config
        val rule: EventRule = if (ruleId != null) {
            config.eventRules.firstOrNull { it.id == ruleId } ?: return View(activity)
        } else {
            var idx = 1
            while (config.eventRules.any { it.id == "ev_$idx" }) idx++
            EventRule(id = "ev_$idx").also { config.eventRules.add(it) }
        }

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
            text = if (ruleId == null) "新建事件规则" else "编辑事件规则"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        // ---- 事件类型 ----
        root.addView(TextView(activity).apply {
            text = "事件类型"
            textSize = 14f
            setPadding(0, 20, 0, 5)
        })

        val paramContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(paramContainer)

        fun rebuildParamUi() {
            paramContainer.removeAllViews()
            when (rule.type) {
                EventType.ORIENTATION -> {
                    paramContainer.addView(TextView(activity).apply {
                        text = "触发方向"
                        textSize = 14f
                        setPadding(0, 15, 0, 5)
                    })
                    val rg = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
                    val rbForward = RadioButton(activity).apply {
                        id = View.generateViewId()
                        text = "正向放置（屏幕朝上）"
                        textSize = 13f
                        isChecked = rule.triggerParam == EventRule.ORIENTATION_FORWARD ||
                            rule.triggerParam.isEmpty()
                    }
                    val rbReverse = RadioButton(activity).apply {
                        id = View.generateViewId()
                        text = "反向放置（屏幕朝下）"
                        textSize = 13f
                        isChecked = rule.triggerParam == EventRule.ORIENTATION_REVERSE
                    }
                    rg.addView(rbForward)
                    rg.addView(rbReverse)
                    rg.setOnCheckedChangeListener { _, checkedId ->
                        rule.triggerParam = if (checkedId == rbReverse.id) {
                            EventRule.ORIENTATION_REVERSE
                        } else {
                            EventRule.ORIENTATION_FORWARD
                        }
                    }
                    // 初始修正空值
                    if (rule.triggerParam.isEmpty()) {
                        rule.triggerParam = EventRule.ORIENTATION_FORWARD
                    }
                    paramContainer.addView(rg)
                }
                EventType.SHAKE, EventType.CLICK -> {
                    // 无额外参数；清空 triggerParam 避免脏数据
                    rule.triggerParam = ""
                    paramContainer.addView(TextView(activity).apply {
                        text = when (rule.type) {
                            EventType.SHAKE -> "摇一摇：检测到摇动时触发"
                            else -> "点击：在壁纸上点击时触发"
                        }
                        textSize = 13f
                        setTextColor(Color.GRAY)
                        setPadding(0, 10, 0, 5)
                    })
                }
            }
        }

        val rgType = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val rbOri = RadioButton(activity).apply {
            id = View.generateViewId()
            text = EventType.ORIENTATION.label
            isChecked = rule.type == EventType.ORIENTATION
        }
        val rbShake = RadioButton(activity).apply {
            id = View.generateViewId()
            text = EventType.SHAKE.label
            isChecked = rule.type == EventType.SHAKE
        }
        val rbClick = RadioButton(activity).apply {
            id = View.generateViewId()
            text = EventType.CLICK.label
            isChecked = rule.type == EventType.CLICK
        }
        rgType.addView(rbOri)
        rgType.addView(rbShake)
        rgType.addView(rbClick)
        rgType.setOnCheckedChangeListener { _, checkedId ->
            rule.type = when (checkedId) {
                rbShake.id -> EventType.SHAKE
                rbClick.id -> EventType.CLICK
                else -> EventType.ORIENTATION
            }
            rebuildParamUi()
        }
        root.addView(rgType)
        rebuildParamUi()

        // ---- 触发状态集 ----
        root.addView(TextView(activity).apply {
            text = "触发状态集（不勾选任何状态 = 任意状态）"
            textSize = 14f
            setPadding(0, 20, 0, 5)
        })

        if (config.states.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "（还没有任何状态）"
                textSize = 13f
                setTextColor(Color.RED)
            })
        } else {
            val stateIds = config.states.keys.sorted()
            for (sid in stateIds) {
                root.addView(CheckBox(activity).apply {
                    text = formatStateDisplay(config.states[sid])
                    textSize = 13f
                    isChecked = sid in rule.sourceStateIds
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            if (sid !in rule.sourceStateIds) rule.sourceStateIds.add(sid)
                        } else {
                            rule.sourceStateIds.remove(sid)
                        }
                    }
                })
            }
        }

        // ---- 目标状态 ----
        root.addView(TextView(activity).apply {
            text = "目标状态"
            textSize = 14f
            setPadding(0, 20, 0, 5)
        })

        val targetIds = config.states.keys.sorted()
        if (targetIds.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "（还没有任何状态）"
                textSize = 13f
                setTextColor(Color.RED)
            })
        } else {
            val displayList = targetIds.map { formatStateDisplay(config.states[it]) }
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    displayList
                )
                val idx = targetIds.indexOf(rule.targetStateId)
                setSelection(if (idx >= 0) idx else 0)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        targetIds.getOrNull(position)?.let { rule.targetStateId = it }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            // 防御性：目标为空或失效时纠正
            if (config.states[rule.targetStateId] == null) {
                rule.targetStateId = targetIds.first()
            }
            root.addView(spinner)
        }

        // ---- 底部操作 ----
        root.addView(buildBottomBar(), matchWrap(top = 30))

        return root
    }

    private fun buildBottomBar(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        row.addView(Button(activity).apply {
            text = "保存并返回"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { callbacks.onSaved() }
        })
        row.addView(Button(activity).apply {
            text = "删除规则"
            setTextColor(Color.RED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { callbacks.onDelete() }
        })
        return row
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, 0) }
}