package com.nqct.gifWallpaper

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

/**
 * 编辑页面构建器。
 *
 * 负责生成单状态的详细编辑 UI，并就地处理**局部的、数据级别的交互**。
 * 涉及导航 / Intent 的操作通过 [Callbacks] 交给 [MainActivity] 处理。
 */
class EditorPageBuilder(
    private val activity: Activity,
    private val animDir: File,
    private val stateMachine: StateMachine,
    private val stateId: String,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onRenameSuccess(newId: String)
        fun onDeleteSuccess()
        fun onRequestReplaceGif()
        fun onSaved()
        fun onRefresh()
    }

    /** 构建完整编辑页 View。若状态已不存在，返回空视图（由 Activity 兜底）。 */
    fun build(): View {
        val state = stateMachine.getState(stateId)
            ?: return View(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // ---- 顶栏 ----
        root.addView(buildTopBar())

        // ---- 源文件信息 ----
        root.addView(buildMetaInfo())

        // ---- 改名 ----
        root.addView(buildRenameRow(state))

        // ---- 重映射 / 删除 ----
        root.addView(buildReplaceAndDeleteRow(state))

        // ---- 设为默认起始 ----
        if (stateMachine.initialStateId != state.id) {
            root.addView(Button(activity).apply {
                text = "设为默认起始动画"
                setOnClickListener {
                    stateMachine.initialStateId = state.id
                    if (stateMachine.save(activity)) {
                        Toast.makeText(activity, "已设为起始", Toast.LENGTH_SHORT).show()
                        callbacks.onRefresh()
                    } else {
                        Toast.makeText(activity, "保存失败: 无法写入配置", Toast.LENGTH_LONG).show()
                    }
                }
            }, matchWrap())
        }

        // ---- 自身播放次数范围 ----
        root.addView(TextView(activity).apply {
            text = "自身播放次数范围"
            textSize = 16f
            setPadding(0, 20, 0, 10)
        })
        val (playRow, etMin, etMax) = buildPlayCountRow(state)
        root.addView(playRow)

        // ---- 转移规则 ----
        root.addView(TextView(activity).apply {
            text = "转移规则 (权重)"
            textSize = 16f
            setPadding(0, 30, 0, 10)
        })
        val rulesContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rulesContainer, matchWrap())

        val percentTextViews = mutableListOf<TextView>()
        fun updatePercentages() {
            val totalWeight = state.rules.sumOf { it.weight.coerceAtLeast(0) }.coerceAtLeast(1)
            for (i in state.rules.indices) {
                if (i < percentTextViews.size) {
                    percentTextViews[i].text =
                        " ${(state.rules[i].weight.toFloat() / totalWeight * 100).toInt()}% "
                }
            }
        }

        fun refreshRulesUI() {
            rulesContainer.removeAllViews()
            percentTextViews.clear()
            for (i in state.rules.indices) {
                val rule = state.rules[i]
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 10, 0, 10)
                }

                val spinner = Spinner(activity).apply {
                    val ids = stateMachine.states.map { it.id }
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, ids)
                    setSelection(ids.indexOf(rule.targetId).coerceAtLeast(0))
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            rule.targetId = ids[position]
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
                row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))

                row.addView(EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(rule.weight.toString())
                    addTextChangedListener(object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) {
                            rule.weight = s.toString().toIntOrNull() ?: 0
                            updatePercentages()
                        }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })
                }, LinearLayout.LayoutParams(100, ViewGroup.LayoutParams.WRAP_CONTENT))

                val tvPercent = TextView(activity).apply {
                    textSize = 14f
                    setTextColor(Color.parseColor("#2196F3"))
                }
                percentTextViews.add(tvPercent)
                row.addView(tvPercent)

                row.addView(Button(activity).apply {
                    text = "X"
                    setTextColor(Color.RED)
                    // 【修复】用引用删除，避免索引在列表变化时错位
                    setOnClickListener { state.rules.remove(rule); refreshRulesUI() }
                })

                rulesContainer.addView(row)
            }
            updatePercentages()
        }
        refreshRulesUI()

        // ---- 添加规则 ----
        root.addView(Button(activity).apply {
            text = "+ 添加转移规则"
            setOnClickListener {
                if (stateMachine.states.isNotEmpty()) {
                    state.rules.add(TransitionRule(targetId = stateMachine.states.first().id, weight = 1))
                    refreshRulesUI()
                }
            }
        }, matchWrap())

        // ---- 保存 ----
        root.addView(Button(activity).apply {
            text = "保存规则"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                state.minSelfPlay = etMin.text.toString().toIntOrNull() ?: 1
                state.maxSelfPlay = etMax.text.toString().toIntOrNull() ?: 1
                if (state.minSelfPlay <= 0) state.minSelfPlay = 1
                if (state.maxSelfPlay < state.minSelfPlay) state.maxSelfPlay = state.minSelfPlay
                if (stateMachine.save(activity)) {
                    Toast.makeText(activity, "规则已保存！", Toast.LENGTH_SHORT).show()
                    callbacks.onSaved()
                } else {
                    Toast.makeText(activity, "保存失败: 无法写入配置", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 40, 0, 0) })

        return root
    }

    // ==================== 子模块构建 ====================

    private fun buildTopBar(): View {
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(Button(activity).apply {
            text = "< 返回"
            setOnClickListener { callbacks.onBack() }
        })
        topBar.addView(TextView(activity).apply {
            text = "配置: $stateId"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        return topBar
    }

    private fun buildMetaInfo(): View {
        val file = File(animDir, "$stateId.gif")
        val info = GifMetadataParser.parse(file)
        val text = if (info != null) {
            "源文件: ${file.name}\n尺寸: ${info.width}x${info.height} | 帧数: ${info.frameCount} | 时长: ${info.totalDelayMs / 1000f}秒"
        } else {
            "源文件: ${file.name} (解析失败)"
        }
        return TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 20, 0, 5)
        }
    }

    private fun buildRenameRow(state: StateNode): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)
        }
        val etRename = EditText(activity).apply {
            setText(stateId)
            hint = "修改名字"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(etRename)
        row.addView(Button(activity).apply {
            text = "改名"
            setOnClickListener { performRename(state, etRename.text.toString()) }
        })
        return row
    }

    /**
     * 执行改名：
     *  1. 重命名磁盘文件；
     *  2. 更新节点 id 与所有指向旧名的转移规则；
     *  3. 更新默认起始节点；
     *  4. 持久化并通知 Activity 重建编辑页。
     */
    private fun performRename(state: StateNode, rawNewName: String) {
        val newName = sanitizeFileName(rawNewName)
        if (newName.isEmpty() || newName == stateId) return
        if (stateMachine.getState(newName) != null) {
            Toast.makeText(activity, "该名字已存在！", Toast.LENGTH_SHORT).show()
            return
        }

        val oldFile = File(animDir, "$stateId.gif")
        val newFile = File(animDir, "$newName.gif")
        if (!oldFile.renameTo(newFile)) {
            Toast.makeText(activity, "文件重命名失败", Toast.LENGTH_SHORT).show()
            return
        }

        state.id = newName
        for (s in stateMachine.states) {
            for (r in s.rules) if (r.targetId == stateId) r.targetId = newName
        }
        if (stateMachine.initialStateId == stateId) stateMachine.initialStateId = newName

        if (!stateMachine.save(activity)) {
            Toast.makeText(activity, "改名成功但保存失败", Toast.LENGTH_LONG).show()
        }
        callbacks.onRenameSuccess(newName)
    }

    private fun buildReplaceAndDeleteRow(state: StateNode): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 20)
        }
        row.addView(Button(activity).apply {
            text = "重映射 (替换GIF)"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { callbacks.onRequestReplaceGif() }
        })
        row.addView(Button(activity).apply {
            text = "彻底删除"
            setTextColor(Color.RED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { confirmAndDelete(state) }
        })
        return row
    }

    /** 弹窗确认并删除：删文件 + 清规则 + 修默认起始 + 持久化。 */
    private fun confirmAndDelete(state: StateNode) {
        AlertDialog.Builder(activity)
            .setTitle("确认删除")
            .setMessage("删除后将无法恢复，且所有指向它的规则会失效。确定吗？")
            .setPositiveButton("删除") { _, _ ->
                File(animDir, "$stateId.gif").delete()
                stateMachine.states.remove(state)
                for (s in stateMachine.states) {
                    s.rules.removeAll { it.targetId == stateId }
                }
                if (stateMachine.initialStateId == stateId) {
                    stateMachine.initialStateId = stateMachine.states.firstOrNull()?.id ?: ""
                }
                if (stateMachine.save(activity)) {
                    Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "删除成功但保存失败", Toast.LENGTH_LONG).show()
                }
                callbacks.onDeleteSuccess()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildPlayCountRow(state: StateNode): Triple<View, EditText, EditText> {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val etMin = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(state.minSelfPlay.toString())
            hint = "最小"
        }
        val etMax = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(state.maxSelfPlay.toString())
            hint = "最大"
        }
        row.addView(etMin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(etMax, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return Triple(row, etMin, etMax)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}