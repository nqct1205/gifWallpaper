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
// 编辑内容：
//   - 主事件类型：方向改变 / 摇一摇 / 点击。
//   - 主事件参数：仅方向事件需要（6 方向单选）；语义为"手机变为该方向"。
//   - 附加条件：事件触发瞬间需满足的快照条件。当前仅支持"当前方向 = X"，
//     留空表示无条件。结构上预留扩展（EventCondition 列表）。
//   - 触发状态集：多选；不勾选任何状态 = 任意状态。
//   - 目标状态：单选。
//
// 新建模式：ruleId 为 null 时立即生成新规则加入 config，用户点"删除"即移除。
// 空规则不主动清理：targetStateId 为空时 PlaybackController.handleEvent 会忽略。
//
// 当前策略：主事件确定性触发（匹配即跳转）。后续可扩展概率 / 多目标。
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

    // 6 方向列表（不含 UNKNOWN）
    private val allOrientations = listOf(
        DeviceOrientation.SCREEN_UP,
        DeviceOrientation.SCREEN_DOWN,
        DeviceOrientation.PORTRAIT,
        DeviceOrientation.PORTRAIT_REVERSED,
        DeviceOrientation.LANDSCAPE_LEFT,
        DeviceOrientation.LANDSCAPE_RIGHT
    )

    fun build(): View {
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
            text = if (ruleId == null) "新建事件规则" else "编辑事件规则"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        // 主事件类型
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

        // 附加条件
        val conditionContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(conditionContainer)
        buildConditionUi(rule, conditionContainer)

        // 触发状态集
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

        // 目标状态
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
            // 目标为空或失效时纠正
            if (config.states[rule.targetStateId] == null) {
                rule.targetStateId = targetIds.first()
            }
            root.addView(spinner)
        }

        // 底部操作
        root.addView(buildBottomBar(), matchWrap(top = 30))

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

                // 6 方向 3 行 2 列。不用 RadioGroup，手动互斥管理。
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
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
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

                // 确保 triggerParam 是合法方向（默认 SCREEN_UP）
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

        // 当前只有"当前方向"一种条件。取第一条 ORIENTATION 条件。
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
                    // 移除旧的 ORIENTATION 条件，再按选择添加
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

        // 说明：结构可扩展
        container.addView(TextView(activity).apply {
            text = "说明：附加条件在事件触发瞬间判定。当前仅支持\"当前方向\"，未来可扩展其他条件。"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })
    }

    // ==================== 底部操作 ====================

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