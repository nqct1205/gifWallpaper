package com.nqct.gifWallpaper

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * 主页面构建器。
 *
 * 只负责生成主页面 UI 并处理页面内的局部交互；
 * 涉及"跳转编辑页 / 启动 Intent / 应用壁纸"等跨页面或系统级操作，
 * 统一通过 [Callbacks] 交给 [MainActivity] 处理。
 */
class MainPageBuilder(
    private val activity: Activity,
    private val stateMachine: StateMachine,
    /** 顶部红色警告（配置损坏 / 导入失败等）；null 或空则不显示 */
    private val warningMessage: String?,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onEditState(stateId: String)
        fun onImportGif()
        fun onApplyWallpaper()
        fun onExportJson()
        fun onImportJson()
    }

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // ---- 标题 ----
        root.addView(TextView(activity).apply {
            text = "动画状态机"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        // ---- 顶部警告条（配置损坏 / 导入失败） ----
        if (!warningMessage.isNullOrBlank()) {
            root.addView(TextView(activity).apply {
                text = warningMessage
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setPadding(20, 15, 20, 15)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) })
        }

        // ---- 导入新动画 ----
        root.addView(Button(activity).apply {
            text = "导入新动画 (GIF)"
            setOnClickListener { callbacks.onImportGif() }
        }, matchWrap())

        // ---- 提示 ----
        root.addView(TextView(activity).apply {
            text = "提示：设置完规则后，需重新应用一次动态壁纸生效。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 10, 0, 20)
        })

        // ---- 状态列表 ----
        if (stateMachine.states.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "暂无动画，请先导入。"
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 50)
            })
        } else {
            for (state in stateMachine.states) {
                root.addView(buildStateItem(state), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 15) })
            }
        }

        // ---- 应用壁纸 ----
        root.addView(Button(activity).apply {
            text = "去应用动态壁纸"
            setOnClickListener { callbacks.onApplyWallpaper() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 40, 0, 0) })

        // ---- JSON 导入 / 导出 ----
        root.addView(buildIoRow())

        // ---- 监控与调试设置 ----
        root.addView(TextView(activity).apply {
            text = "性能监控设置 (实时生效)"
            textSize = 16f
            setPadding(0, 50, 0, 10)
        })
        buildMonitorSettings(root)

        return root
    }

    // ==================== 子模块构建 ====================

    private fun buildStateItem(state: StateNode): View {
        val itemLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
            setOnClickListener { callbacks.onEditState(state.id) }
        }

        val headerRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        headerRow.addView(TextView(activity).apply {
            text = state.id
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (state.id == stateMachine.initialStateId) {
            headerRow.addView(TextView(activity).apply {
                text = "默认起始"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setPadding(10, 4, 10, 4)
            })
        }
        itemLayout.addView(headerRow)

        itemLayout.addView(TextView(activity).apply {
            text = "播放 ${state.minSelfPlay}~${state.maxSelfPlay} 圈后，有 ${state.rules.size} 条转移规则"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 10, 0, 0)
        })

        return itemLayout
    }

    private fun buildIoRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        row.addView(Button(activity).apply {
            text = "导出配置"
            setOnClickListener { callbacks.onExportJson() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(activity).apply {
            text = "导入配置"
            setOnClickListener { callbacks.onImportJson() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun buildMonitorSettings(root: LinearLayout) {
        val prefs = activity.getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)

        root.addView(CheckBox(activity).apply {
            text = "默认在壁纸中显示调试信息"
            isChecked = prefs.getBoolean("showDebug", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("showDebug", isChecked).apply()
            }
        })

        root.addView(buildNumberInputRow(
            label = "渲染耗时上限: ",
            unit = " ms",
            prefKey = "limitDraw",
            defaultValue = 20
        ))

        root.addView(buildNumberInputRow(
            label = "内存占用上限: ",
            unit = " MB",
            prefKey = "limitMem",
            defaultValue = 150
        ))
    }

    private fun buildNumberInputRow(
        label: String,
        unit: String,
        prefKey: String,
        defaultValue: Int
    ): View {
        val prefs = activity.getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 0)
        }
        row.addView(TextView(activity).apply { text = label; textSize = 14f })

        row.addView(EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(prefKey, defaultValue).toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val v = s.toString().toIntOrNull() ?: defaultValue
                    prefs.edit().putInt(prefKey, v).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        })

        row.addView(TextView(activity).apply { text = unit; textSize = 14f })
        return row
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}