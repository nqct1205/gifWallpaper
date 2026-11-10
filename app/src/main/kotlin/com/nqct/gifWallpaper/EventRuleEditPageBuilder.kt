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

// 事件规则编辑页。
//
// 【新建模式】ruleId 为 null 时创建**临时** EventRule，不加入 config.eventRules。
// 点"保存并返回"才正式加入 config；点"取消"/返回/系统返回时什么都不做（丢弃）。
//
// 【编辑模式】rule 就是 config 里那条规则的引用，字段修改会直接 mutate。
// 点"保存并返回"由 MainActivity 持久化；点"取消"/返回/系统返回时由 MainActivity loadConfig 丢弃。
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

    private val allOrientations = listOf(
        DeviceOrientation.SCREEN_UP,
        DeviceOrientation.SCREEN_DOWN,
        DeviceOrientation.PORTRAIT,
        DeviceOrientation.PORTRAIT_REVERSED,
        DeviceOrientation.LANDSCAPE_LEFT,
        DeviceOrientation.LANDSCAPE_RIGHT
    )

    fun build(): View {
        val isNew = ruleId == null

        // 新建模式：创建临时 rule，不加入 config
        // 编辑模式：从 config 里取
        val rule: EventRule = if (!isNew) {
            config.eventRules.firstOrNull { it.id == ruleId } ?: return View(activity)
        } else {
            var idx = 1
            while (config.eventRules.any { it.id == "ev_$idx" }) idx++
            EventRule(id = "ev_$idx")
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(Button(activity).apply {
            text = "< 返回"
            setOnClickListener { callbacks.onBack() }
        })
        topBar.addView(TextView(activity).apply {
            text = if (isNew) "新建事件规则" else "编辑事件规则"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        root.addView(TextView(activity).apply {
            text = "事件类型"
            textSize = 14f
            setPadding(0, 20, 0, 5)
        })

        val primaryParamContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(primaryParamContainer)

        fun rebuildPrimaryParamUi() {
            buildPrimaryParamUi(rule, primaryParamContainer)
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
            rebuildPrimaryParamUi()
        }
        root.addView(rgType)
        rebuildPrimaryParamUi()

        val conditionContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(conditionContainer)
        buildConditionUi(rule, conditionContainer)

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
            val stateIds = config.states.keys.toList()
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

        root.addView(TextView(activity).apply {
            text = "目标状态"
            textSize = 14f
            setPadding(0, 20, 0, 5)
        })

        val targetIds = config.states.keys.toList()
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
            if (config.states[rule.targetStateId] == null) {
                rule.targetStateId = targetIds.first()
            }
            root.addView(spinner)
        }

        // 底部按钮：新建模式"取消"，编辑模式"删除规则"
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        btnRow.addView(Button(activity).apply {
            text = "保存并返回"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                // 新建模式：保存时正式加入 config
                if (isNew && config.eventRules.none { it.id == rule.id }) {
                    config.eventRules.add(rule)
                }
                callbacks.onSaved()
            }
        })
        btnRow.addView(Button(activity).apply {
            text = if (isNew) "取消" else "删除规则"
            setTextColor(Color.RED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (isNew) {
                    // 新建模式：取消，直接返回
                    callbacks.onBack()
                } else {
                    callbacks.onDelete()
                }
            }
        })
        root.addView(btnRow)

        return root
    }

    // ==================== 主事件参数区 ====================

    private fun buildPrimaryParamUi(rule: EventRule, container: LinearLayout) {
        container.removeAllViews()
        when (rule.type) {
            EventType.ORIENTATION -> {
                container.addView(TextView(activity).apply {
                    text = "触发方向（手机变为该方向时触发）"
                    textSize = 14f
                    setPadding(0, 15, 0, 5)
                })
                val radios = mutableListOf<RadioButton>()
                fun refreshChecks() {
                    for (rb in radios) {
                        rb.isChecked = (rb.tag as String) == rule.triggerParam
                    }
                }
                for (pair in listOf(
                    listOf(DeviceOrientation.SCREEN_UP, DeviceOrientation.SCREEN_DOWN),
                    listOf(DeviceOrientation.PORTRAIT, DeviceOrientation.PORTRAIT_REVERSED),
                    listOf(DeviceOrientation.LANDSCAPE_LEFT, DeviceOrientation.LANDSCAPE_RIGHT)
                )) {
                    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    for (ori in pair) {
                        val rb = RadioButton(activity).apply {
                            text = ori.label
                            textSize = 13f
                            tag = ori.name
                            isChecked = rule.triggerParam == ori.name
                            layoutParams = LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                            )
                            setOnClickListener {
                                rule.triggerParam = ori.name
                                refreshChecks()
                            }
                        }
                        radios.add(rb)
                        row.addView(rb)
                    }
                    container.addView(row)
                }
                if (allOrientations.none { it.name == rule.triggerParam }) {
                    rule.triggerParam = DeviceOrientation.SCREEN_UP.name
                    refreshChecks()
                }
            }
            EventType.SHAKE, EventType.CLICK -> {
                rule.triggerParam = ""
                container.addView(TextView(activity).apply {
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

    // ==================== 附加条件区 ====================

    private fun buildConditionUi(rule: EventRule, container: LinearLayout) {
        container.removeAllViews()
        container.addView(TextView(activity).apply {
            text = "附加条件（事件触发瞬间需满足；选\"任意\"表示无条件）"
            textSize = 14f
            setPadding(0, 20, 0, 5)
        })

        val existing = rule.conditions.firstOrNull { it.type == EventType.ORIENTATION }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(activity).apply {
            text = "当前方向: "
            textSize = 13f
        })

        val options = mutableListOf<String>()
        options.add("任意")
        for (ori in allOrientations) options.add(ori.label)

        val currentIdx = if (existing != null) {
            val idx = allOrientations.indexOfFirst { it.name == existing.param }
            if (idx >= 0) idx + 1 else 0
        } else 0

        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(currentIdx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    rule.conditions.removeAll { it.type == EventType.ORIENTATION }
                    if (position > 0) {
                        val ori = allOrientations[position - 1]
                        rule.conditions.add(EventCondition(EventType.ORIENTATION, ori.name))
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(spinner)
        container.addView(row)

        container.addView(TextView(activity).apply {
            text = "说明：附加条件在事件触发瞬间判定。当前仅支持\"当前方向\"，未来可扩展其他条件。"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })
    }
}