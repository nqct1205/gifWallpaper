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

/**
 * 状态编辑页。
 *
 * 【编辑内容】
 *  - 状态名称（可一键改为引用动画名）。
 *  - 引用的动画（下拉选择，显示“动画名 (id)”）。
 *  - 周期范围 min~max（圈数）。
 *  - 转移规则列表：每条包含目标状态（显示“状态名 (id)”） + 权重 + 百分比。
 *
 * 【就地修改】直接 mutate [StateNode]；返回或保存时由 [MainActivity] 持久化。
 *
 * 【ID 不可改】状态 ID 是转移规则 / 事件规则 / 默认状态的引用键，只读展示。
 * 名称（[StateNode.name]）可自由修改，仅用于展示。
 *
 * 【周期语义】进入该状态时从 min~max 随机抽取目标圈数，见 [PlaybackController]。
 *
 * 【权重百分比】任一行权重变化时，通过 [percentUpdaters] 列表让所有行同步刷新。
 */
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
            text = "编辑状态: ${formatStateDisplay(state)}"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        // ---- 名称（带一键重命名） ----
        root.addView(buildNameRow(state), matchWrap(top = 10, bottom = 10))

        // ---- 动画选择 ----
        root.addView(buildAnimationSelector(state), matchWrap(bottom = 10))

        // ---- 周期 ----
        root.addView(TextView(activity).apply {
            text = "周期（播放所引用动画的圈数范围）"
            textSize = 14f
            setPadding(0, 10, 0, 5)
        })
        root.addView(buildPeriodRow(state), matchWrap(bottom = 10))

        // ---- 转移规则 ----
        root.addView(TextView(activity).apply {
            text = "概率转移规则"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 10)
        })

        val rulesContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rulesContainer)

        // 每行注册一个"刷新自己百分比"的回调；权重变化时遍历所有回调更新
        val percentUpdaters = mutableListOf<() -> Unit>()

        fun updateAllPercents() {
            percentUpdaters.forEach { it() }
        }

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

        // ---- 添加规则 ----
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

        // ---- 底部操作 ----
        root.addView(buildBottomBar())

        return root
    }

    // ==================== 名称行 ====================

    /**
     * 名称输入行 + “用动画名”按钮。
     *
     * 点击按钮后，状态名改为引用动画的名字（动画名字为空时用动画 id 回退）。
     * 状态只引用一个动画，所以是"第一个也是唯一一个"。
     * 若状态尚未引用任何动画，Toast 提示。
     */
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
            hint = state.id  // 空时以 ID 作为提示
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

        val animIds = config.animations.keys.sorted()
        if (animIds.isEmpty()) {
            row.addView(TextView(activity).apply {
                text = "（无可用动画，请先在「动画」页创建）"
                textSize = 13f
                setTextColor(Color.RED)
            })
            return row
        }

        // 显示格式化名，选中后用 animIds 索引取原始 ID
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
        // 防御性：若 state 原本未引用动画或引用了不存在的动画，自动纠正
        if (config.animations[state.animationId] == null) {
            state.animationId = animIds.first()
        }
        row.addView(spinner)
        return row
    }

    private fun buildPeriodRow(state: StateNode): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(activity).apply { text = "最小: "; textSize = 13f })
        row.addView(EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(state.periodMin.toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    state.periodMin = s?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    if (state.periodMax < state.periodMin) state.periodMax = state.periodMin
                }
            })
        })
        row.addView(TextView(activity).apply { text = "圈   最大: "; textSize = 13f; setPadding(10, 0, 0, 0) })
        row.addView(EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(state.periodMax.toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.toIntOrNull() ?: 1
                    state.periodMax = v.coerceAtLeast(state.periodMin)
                }
            })
        })
        row.addView(TextView(activity).apply { text = "圈"; textSize = 13f; setPadding(10, 0, 0, 0) })
        return row
    }

    /**
     * 单条转移规则行：目标状态 Spinner（显示状态名 (id)） + 权重输入 + 百分比 + 删除。
     */
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

        // 第一行：目标状态选择
        val targetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        targetRow.addView(TextView(activity).apply {
            text = "#${index + 1} 目标: "
            textSize = 13f
        })

        val allStateIds = config.states.keys.sorted()
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
            // 防御性：目标状态失效时自动纠正
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

        // 第二行：权重 + 百分比
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

        // 注册本行的百分比刷新回调
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