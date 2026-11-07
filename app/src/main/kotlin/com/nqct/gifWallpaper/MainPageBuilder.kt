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
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

// 主页面构建器。
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
        fun onRestoreSystemWallpaper()
        fun onExportJson()
        fun onExportZip()
        fun onImportJson()
        fun onImportZip()
        // 新增：导出调试日志
        fun onExportLog()
    }

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        root.addView(TextView(activity).apply {
            text = "GIF 动态壁纸"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        if (!warningMessage.isNullOrBlank()) {
            root.addView(TextView(activity).apply {
                text = warningMessage
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setPadding(20, 15, 20, 15)
            }, matchWrapWithMargins(bottom = 20))
        }

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

        root.addView(buildDefaultStateRow(), matchWrapWithMargins(bottom = 20))

        buildBackupSection(root)
        buildSensorAndInteractionSection(root)
        buildWallpaperActions(root)
        buildMonitorSettings(root)

        return root
    }

    // ==================== 数据备份 ====================

    private fun buildBackupSection(root: LinearLayout) {
        root.addView(TextView(activity).apply {
            text = "数据备份"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 5)
        })
        root.addView(TextView(activity).apply {
            text = "压缩包包含配置文件与全部 GIF 资源；JSON 只含配置，导入后缺失的资源引用会被清理。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 10)
        })

        root.addView(buildButtonPair(
            leftText = "导出 JSON",
            onLeft = { callbacks.onExportJson() },
            rightText = "导出压缩包",
            onRight = { callbacks.onExportZip() }
        ), matchWrapWithMargins(bottom = 10))

        root.addView(buildButtonPair(
            leftText = "导入 JSON",
            onLeft = { callbacks.onImportJson() },
            rightText = "导入压缩包",
            onRight = { callbacks.onImportZip() }
        ), matchWrapWithMargins(bottom = 10))
    }

    // ==================== 传感器与交互 ====================

    private fun buildSensorAndInteractionSection(root: LinearLayout) {
        val prefs = activity.getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)

        root.addView(TextView(activity).apply {
            text = "传感器与交互"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 30, 0, 5)
        })
        root.addView(TextView(activity).apply {
            text = "关闭后对应事件不会触发；已配置的规则保留不变。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 10)
        })

        root.addView(CheckBox(activity).apply {
            text = "启用方向改变事件"
            isChecked = prefs.getBoolean("enableOrientation", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("enableOrientation", checked).apply()
            }
        })
        root.addView(CheckBox(activity).apply {
            text = "启用摇一摇事件"
            isChecked = prefs.getBoolean("enableShake", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("enableShake", checked).apply()
            }
        })
        root.addView(CheckBox(activity).apply {
            text = "启用点击事件"
            isChecked = prefs.getBoolean("enableClick", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("enableClick", checked).apply()
            }
        })

        val currentSensitivity = prefs.getFloat("shakeSensitivity", 2.4f)
        val sensitivityLabel = TextView(activity).apply {
            text = sensitivityLabelText(currentSensitivity)
            textSize = 14f
            setPadding(0, 15, 0, 5)
        }
        root.addView(sensitivityLabel)

        val sensitivitySeek = SeekBar(activity).apply {
            max = 25
            progress = ((currentSensitivity * 10f) - 15f).toInt().coerceIn(0, 25)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val g = (progress + 15) / 10f
                    sensitivityLabel.text = sensitivityLabelText(g)
                    if (fromUser) prefs.edit().putFloat("shakeSensitivity", g).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(sensitivitySeek)
        root.addView(TextView(activity).apply {
            text = "说明：静止时约 1.0g，轻摇约 1.5~2.0g，正常摇约 2.5~3.5g。数值越低越灵敏。"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })

        val currentCooldown = prefs.getLong("shakeCooldownMs", 1200L)
        val cooldownLabel = TextView(activity).apply {
            text = cooldownLabelText(currentCooldown)
            textSize = 14f
            setPadding(0, 15, 0, 5)
        }
        root.addView(cooldownLabel)

        val cooldownSeek = SeekBar(activity).apply {
            max = 27
            progress = ((currentCooldown - 300L) / 100L).toInt().coerceIn(0, 27)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ms = 300L + progress * 100L
                    cooldownLabel.text = cooldownLabelText(ms)
                    if (fromUser) prefs.edit().putLong("shakeCooldownMs", ms).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(cooldownSeek)
        root.addView(TextView(activity).apply {
            text = "说明：冷却时间越长，两次摇一摇触发之间的最小间隔越大，可避免误触抖动。"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })
    }

    private fun sensitivityLabelText(g: Float): String =
        "摇一摇灵敏度: ${String.format(Locale.US, "%.1f", g)}g（越低越灵敏）"

    private fun cooldownLabelText(ms: Long): String =
        "摇一摇冷却: ${ms}ms（越长越不易连续触发）"

    // ==================== 壁纸操作 ====================

    private fun buildWallpaperActions(root: LinearLayout) {
        root.addView(Button(activity).apply {
            text = "应用动态壁纸"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener { callbacks.onApplyWallpaper() }
        }, matchWrapWithMargins(top = 30, bottom = 10))

        root.addView(TextView(activity).apply {
            text = "提示：修改规则或动画后需重新应用一次动态壁纸才会生效。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 10)
        })

        root.addView(Button(activity).apply {
            text = "恢复系统默认壁纸"
            setTextColor(Color.RED)
            setOnClickListener { callbacks.onRestoreSystemWallpaper() }
        }, matchWrapWithMargins(bottom = 5))

        root.addView(TextView(activity).apply {
            text = "清除动态壁纸，回到手机默认壁纸。所有配置参数保留。部分系统可能需在设置中手动更换。"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 10)
        })
    }

    // ==================== 调试设置 ====================

    private fun buildMonitorSettings(root: LinearLayout) {
        val prefs = activity.getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)

        root.addView(TextView(activity).apply {
            text = "调试设置"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 30, 0, 10)
        })

        root.addView(CheckBox(activity).apply {
            text = "在壁纸中显示调试信息"
            isChecked = prefs.getBoolean("showDebug", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("showDebug", checked).apply()
            }
        })
        root.addView(TextView(activity).apply {
            text = "开启调试后，即使未配置传感器规则也会注册传感器，用于实时观察姿态与 g 值。"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 5)
        })

        root.addView(buildNumberInputRow("渲染耗时上限: ", " ms", "limitDraw", 20))
        root.addView(buildNumberInputRow("内存占用上限: ", " MB", "limitMem", 150))

        // 日志导出
        root.addView(TextView(activity).apply {
            text = "调试日志"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 5)
        })
        root.addView(Button(activity).apply {
            text = "导出日志（${LogBuffer.size()} 条）"
            setOnClickListener { callbacks.onExportLog() }
        }, matchWrapWithMargins(bottom = 5))
        root.addView(TextView(activity).apply {
            text = "导出后可分享或保存。包含状态切换、传感器、异常等调试信息，便于排查问题。"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 10)
        })
    }

    // ==================== 子模块 ====================

    private fun buildButtonPair(
        leftText: String, onLeft: () -> Unit,
        rightText: String, onRight: () -> Unit
    ): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(activity).apply {
            text = leftText
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onLeft() }
        })
        row.addView(Button(activity).apply {
            text = rightText
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = 10 }
            setOnClickListener { onRight() }
        })
        return row
    }

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

    private fun buildNumberInputRow(
        label: String, unit: String, prefKey: String, defaultValue: Int
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
                    if (v > 0) prefs.edit().putInt(prefKey, v).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        })
        row.addView(TextView(activity).apply { text = unit; textSize = 14f })
        return row
    }

    private fun matchWrapWithMargins(bottom: Int = 0, top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }
}