package com.nqct.gifWallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

// 状态编辑页。
//
// 【P2-9 修复】periodMin / periodMax 联动：
//   - 原实现：periodMin 改动后若 periodMax < periodMin 则修正 periodMax 数据，
//     但 etMax 输入框文本未同步，导致"数据已修正、UI 显示旧值"。
//   - 现实现：用 syncing 标志避免联动死循环；periodMin 导致 periodMax 修正时，
//     同步回写 etMax 文本。
class StateEditPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val stateId: String,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onSaved()
        fun onDelete()
    }

    fun build(): View {
        val state = config.states[stateId] ?: return View(activity)

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
            text = "编辑状态: ${formatStateDisplay(state)}"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        root.addView(buildNameRow(state), matchWrap(top = 10, bottom = 10))
        root.addView(buildAnimationSelector(state), matchWrap(bottom = 10))

        root.addView(TextView(activity).apply {
            text = "周期（播放所引用动画的圈数范围）"
            textSize = 14f
            setPadding(0, 10, 0, 5)
        })
        root.addView(buildPeriodRow(state), matchWrap(bottom = 10))

        root.addView(TextView(activity).apply {
            text = "概率转移规则"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 10)
        })

        val rulesContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rulesContainer)

        val percentUpdaters = mutableListOf<() -> Unit>()
        fun updateAllPercents() { percentUpdaters.forEach { it() } }

        fun refreshRules() {
            rulesContainer.removeAllViews()
            percentUpdaters.clear()
            if (state.rules.isEmpty()) {
                rulesContainer.addView(TextView(activity).apply {
                    text = "无转移规则。当前状态播完周期后会原地重播。"
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(20, 20, 20, 20)
                })
            } else {
                state.rules.forEachIndexed { idx, rule ->
                    rulesContainer.addView(
                        buildRuleRow(state, rule, idx, percentUpdaters, ::updateAllPercents, ::refreshRules),
                        matchWrap(bottom = 8)
                    )
                }
            }
            updateAllPercents()
        }
        refreshRules()

        root.addView(Button(activity).apply {
            text = "+ 添加转移规则"
            setOnClickListener {
                if (config.states.isEmpty()) {
                    Toast.makeText(activity, "还没有状态，无法添加转移规则", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val firstTarget = config.states.keys.firstOrNull() ?: return@setOnClickListener
                state.rules.add(TransitionRule(targetStateId = firstTarget, weight = 1))
                refreshRules()
            }
        }, matchWrap(top = 10, bottom = 20))

        root.addView(buildBottomBar())

        return root
    }

    // ==================== 名称行 ====================

    private fun buildNameRow(state: StateNode): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(activity).apply {
            text = "名称: "
            textSize = 14f
        })
        val etName = EditText(activity).apply {
            setText(state.name)
            hint = state.id
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    state.name = s?.toString() ?: ""
                }
            })
        }
        row.addView(etName)
        row.addView(Button(activity).apply {
            text = "用动画名"
            textSize = 12f
            setOnClickListener {
                val anim = config.animations[state.animationId]
                if (anim == null) {
                    Toast.makeText(activity, "尚未引用动画", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val newName = anim.name.ifBlank { anim.id }
                state.name = newName
                etName.setText(newName)
                etName.setSelection(newName.length)
            }
        })
        return row
    }

    // ==================== 动画选择 ====================

    private fun buildAnimationSelector(state: StateNode): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(activity).apply {
            text = "引用动画: "
            textSize = 14f
        })

        val animIds = config.animations.keys.toList()
        if (animIds.isEmpty()) {
            row.addView(TextView(activity).apply {
                text = "（无可用动画，请先在「动画」页创建）"
                textSize = 13f
                setTextColor(Color.RED)
            })
            return row
        }

        val animDisplays = animIds.map { formatAnimDisplay(config.animations[it]) }

        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                animDisplays
            )
            val idx = animIds.indexOf(state.animationId)
            setSelection(if (idx >= 0) idx else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    animIds.getOrNull(position)?.let { state.animationId = it }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (config.animations[state.animationId] == null) {
            state.animationId = animIds.first()
        }
        row.addView(spinner)
        return row
    }

    // ==================== 周期 ====================

    // P2-9：periodMin 修正 periodMax 时，同步回写 etMax 文本。
    private fun buildPeriodRow(state: StateNode): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(activity).apply { text = "最小: "; textSize = 13f })

        // syncing 防止两个 watcher 互相触发
        var syncing = false

        val etMin = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(state.periodMin.toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val etMax = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(state.periodMax.toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        etMin.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                syncing = true
                try {
                    val v = s?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    state.periodMin = v
                    if (state.periodMax < state.periodMin) {
                        state.periodMax = state.periodMin
                        // P2-9：同步回写 etMax
                        val maxText = state.periodMax.toString()
                        if (etMax.text?.toString() != maxText) {
                            etMax.setText(maxText)
                            etMax.setSelection(maxText.length)
                        }
                    }
                } finally {
                    syncing = false
                }
            }
        })

        etMax.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                syncing = true
                try {
                    val v = s?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    state.periodMax = v.coerceAtLeast(state.periodMin)
                    // P2-9：若 max 被 coerce 修正，回写
                    val maxText = state.periodMax.toString()
                    if (etMax.text?.toString() != maxText) {
                        etMax.setText(maxText)
                        etMax.setSelection(maxText.length)
                    }
                } finally {
                    syncing = false
                }
            }
        })

        row.addView(etMin)
        row.addView(TextView(activity).apply { text = "圈   最大: "; textSize = 13f; setPadding(10, 0, 0, 0) })
        row.addView(etMax)
        row.addView(TextView(activity).apply { text = "圈"; textSize = 13f; setPadding(10, 0, 0, 0) })
        return row
    }

    // ==================== 规则行 ====================

    private fun buildRuleRow(
        state: StateNode,
        rule: TransitionRule,
        index: Int,
        percentUpdaters: MutableList<() -> Unit>,
        onAnyWeightChanged: () -> Unit,
        refreshRules: () -> Unit
    ): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(15, 15, 15, 15)
            setBackgroundColor(Color.WHITE)
        }

        val targetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        targetRow.addView(TextView(activity).apply {
            text = "#${index + 1} 目标: "
            textSize = 13f
        })

        val allStateIds = config.states.keys.toList()
        if (allStateIds.isEmpty()) {
            targetRow.addView(TextView(activity).apply {
                text = "（无状态）"
                textSize = 13f
                setTextColor(Color.RED)
            })
        } else {
            val displayList = allStateIds.map { formatStateDisplay(config.states[it]) }
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_spinner_dropdown_item,
                    displayList
                )
                val idx = allStateIds.indexOf(rule.targetStateId)
                setSelection(if (idx >= 0) idx else 0)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        allStateIds.getOrNull(position)?.let { rule.targetStateId = it }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (config.states[rule.targetStateId] == null) {
                rule.targetStateId = allStateIds.first()
            }
            targetRow.addView(spinner)
        }

        targetRow.addView(Button(activity).apply {
            text = "移除"
            textSize = 12f
            setTextColor(Color.RED)
            setOnClickListener {
                state.rules.remove(rule)
                refreshRules()
            }
        })
        card.addView(targetRow)

        val weightRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 5, 0, 0)
        }
        weightRow.addView(TextView(activity).apply {
            text = "权重: "
            textSize = 13f
        })

        val tvPercent = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#2196F3"))
            setPadding(10, 0, 0, 0)
        }

        weightRow.addView(EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(rule.weight.toString())
            layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    rule.weight = s?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    onAnyWeightChanged()
                }
            })
        })
        weightRow.addView(tvPercent)
        card.addView(weightRow)

        percentUpdaters.add {
            val total = state.rules.sumOf { it.weight.coerceAtLeast(0) }.coerceAtLeast(1)
            val pct = (rule.weight.toFloat() / total * 100f).toInt()
            tvPercent.text = " ($pct%)"
        }

        return card
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
            text = "删除状态"
            setTextColor(Color.RED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { callbacks.onDelete() }
        })
        return row
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }
}