package com.nqct.gifWallpaper

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 主页面构建器。
 *
 * 【展示内容】
 *  - 顶部警告条（配置损坏等）。
 *  - 五个入口卡片：资源 / 动画 / 状态 / 事件规则 / 状态图。
 *  - 默认状态设置入口（显示“状态名 (id)”）。
 *  - 应用壁纸按钮。
 *  - 调试设置（SharedPreferences，实时同步到壁纸服务）。
 *
 * 【职责边界】本类只负责 UI 与触发回调；数据修改与持久化由 [MainActivity] 完成。
 */
class MainPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val warningMessage: String?,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onOpenResources()
        fun onOpenAnimations()
        fun onOpenStates()
        fun onOpenEventRules()
        fun onOpenStateGraph()
        fun onSetDefaultState()
        fun onApplyWallpaper()
    }

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // ---- 标题 ----
        root.addView(TextView(activity).apply {
            text = "GIF 动态壁纸"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        // ---- 警告条 ----
        if (!warningMessage.isNullOrBlank()) {
            root.addView(TextView(activity).apply {
                text = warningMessage
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setPadding(20, 15, 20, 15)
            }, matchWrapWithMargins(bottom = 20))
        }

        // ---- 统计 + 入口 ----
        root.addView(buildEntryCard("资源", config.resources.size) { callbacks.onOpenResources() },
            matchWrapWithMargins(bottom = 10))
        root.addView(buildEntryCard("动画", config.animations.size) { callbacks.onOpenAnimations() },
            matchWrapWithMargins(bottom = 10))
        root.addView(buildEntryCard("状态", config.states.size) { callbacks.onOpenStates() },
            matchWrapWithMargins(bottom = 10))
        root.addView(buildEntryCard("事件规则", config.eventRules.size) { callbacks.onOpenEventRules() },
            matchWrapWithMargins(bottom = 10))
        root.addView(buildEntryCard("状态图", config.states.size) { callbacks.onOpenStateGraph() },
            matchWrapWithMargins(bottom = 20))

        // ---- 默认状态 ----
        root.addView(buildDefaultStateRow(), matchWrapWithMargins(bottom = 30))

        // ---- 应用壁纸 ----
        root.addView(Button(activity).apply {
            text = "应用动态壁纸"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener { callbacks.onApplyWallpaper() }
        }, matchWrapWithMargins(bottom = 10))

        root.addView(TextView(activity).apply {
            text = "提示：修改规则或动画后需重新应用一次动态壁纸才会生效。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })

        // ---- 调试设置 ----
        buildMonitorSettings(root)

        return root
    }

    // ==================== 子模块构建 ====================

    /** 入口卡片：标题 + 数量 + 箭头，整行可点击。 */
    private fun buildEntryCard(title: String, count: Int, onClick: () -> Unit): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.WHITE)
            setOnClickListener { onClick() }

            addView(TextView(activity).apply {
                text = title
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(activity).apply {
                text = count.toString()
                textSize = 16f
                setTextColor(Color.parseColor("#2196F3"))
                setPadding(0, 0, 20, 0)
            })
            addView(TextView(activity).apply {
                text = ">"
                textSize = 18f
                setTextColor(Color.GRAY)
            })
        }
    }

    /** 默认状态行：显示“状态名 (id)”，点击打开选择弹窗。 */
    private fun buildDefaultStateRow(): View {
        val current = if (config.defaultStateId.isEmpty()) {
            "未设置"
        } else {
            formatStateDisplay(config.states[config.defaultStateId])
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 20, 30, 20)
            setBackgroundColor(Color.WHITE)
            setOnClickListener { callbacks.onSetDefaultState() }

            addView(TextView(activity).apply {
                text = "默认状态"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(activity).apply {
                text = current
                textSize = 14f
                setTextColor(Color.parseColor("#2196F3"))
                setPadding(0, 0, 10, 0)
            })
            addView(TextView(activity).apply {
                text = ">"
                textSize = 18f
                setTextColor(Color.GRAY)
            })
        }
    }

    /**
     * 调试设置：写入 SharedPreferences "PetPrefs"，
     * 壁纸服务已注册监听，会实时读取。
     */
    private fun buildMonitorSettings(root: LinearLayout) {
        val prefs = activity.getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)

        root.addView(TextView(activity).apply {
            text = "调试设置"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 50, 0, 10)
        })

        root.addView(CheckBox(activity).apply {
            text = "在壁纸中显示调试信息"
            isChecked = prefs.getBoolean("showDebug", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("showDebug", checked).apply()
            }
        })

        root.addView(buildNumberInputRow("渲染耗时上限: ", " ms", "limitDraw", 20))
        root.addView(buildNumberInputRow("内存占用上限: ", " MB", "limitMem", 150))
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
        row.addView(TextView(activity).apply {
            text = label; textSize = 14f
        })
        row.addView(EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(prefKey, defaultValue).toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val v = s.toString().toIntOrNull() ?: defaultValue
                    if (v > 0) prefs.edit().putInt(prefKey, v).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        })
        row.addView(TextView(activity).apply {
            text = unit; textSize = 14f
        })
        return row
    }

    // ==================== 布局参数工具 ====================

    private fun matchWrapWithMargins(
        bottom: Int = 0,
        top: Int = 0
    ) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }
}