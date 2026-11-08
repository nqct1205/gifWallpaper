package com.nqct.gifWallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

// 资源编辑页（当前仅支持播放速度配置）。
//
// 【存储】只存 customValue 一个字段：
//   FRAME_INTERVAL 模式时 = 帧间隔（ms），TOTAL_DURATION 模式时 = 总时长（ms）。
// 另一个字段只在 UI 上显示，由 customValue 和 frameCount 算出来，不存储。
//
// 【联动】切换模式时保留用户已填过的值（customValue），另一个字段自动重算。
// 在当前模式下编辑"主字段"时，另一个"副字段"实时重算。
class ResourceEditPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val resourceId: String,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onSaved()
    }

    fun build(): View {
        val item = config.resources[resourceId] ?: return View(activity)
        val info = item.info
        val frameCount = info?.frameCount ?: 0
        val origTotal = info?.totalDelayMs ?: 0
        val origAvg = if (frameCount > 0) origTotal / frameCount else 33

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
            text = "编辑资源: $resourceId"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        root.addView(TextView(activity).apply {
            text = "文件: ${item.fileName}\n" +
                "帧数: $frameCount\n" +
                "原始时长: ${origTotal}ms（每帧约 ${origAvg}ms）"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 15, 0, 15)
        })

        if (frameCount <= 0) {
            root.addView(TextView(activity).apply {
                text = "（该资源解析失败，无法修改播放速度）"
                textSize = 13f
                setTextColor(Color.RED)
            })
            return root
        }

        root.addView(TextView(activity).apply {
            text = "播放速度"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 10, 0, 5)
        })

        val rgMode = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val rbNative = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "原生速度"
            textSize = 14f
        }
        val rbFrame = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "自定义帧间隔"
            textSize = 14f
        }
        val rbTotal = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "自定义总时长"
            textSize = 14f
        }
        rgMode.addView(rbNative)
        rgMode.addView(rbFrame)
        rgMode.addView(rbTotal)
        when (item.playbackMode) {
            ResourcePlaybackMode.NATIVE -> rbNative.isChecked = true
            ResourcePlaybackMode.FRAME_INTERVAL -> rbFrame.isChecked = true
            ResourcePlaybackMode.TOTAL_DURATION -> rbTotal.isChecked = true
        }
        root.addView(rgMode)

        // 帧间隔行
        val rowFrame = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 15, 0, 0)
        }
        rowFrame.addView(TextView(activity).apply {
            text = "帧间隔: "
            textSize = 14f
        })
        val etFrame = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowFrame.addView(etFrame)
        rowFrame.addView(TextView(activity).apply {
            text = " ms（8~500）"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        root.addView(rowFrame)

        // 总时长行
        val rowTotal = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 0)
        }
        rowTotal.addView(TextView(activity).apply {
            text = "总时长: "
            textSize = 14f
        })
        val etTotal = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rowTotal.addView(etTotal)
        rowTotal.addView(TextView(activity).apply {
            text = " ms（100~60000）"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        root.addView(rowTotal)

        var syncing = false

        // 根据当前模式刷新两个输入框的显示值与可编辑状态。
        // 主字段：当前模式对应的那个（FRAME_INTERVAL 时帧间隔，TOTAL_DURATION 时总时长）。
        // 副字段：由主字段 + 帧数推算，只读。
        fun refreshByMode() {
            syncing = true
            when (item.playbackMode) {
                ResourcePlaybackMode.NATIVE -> {
                    etFrame.isEnabled = false
                    etTotal.isEnabled = false
                    etFrame.setText(origAvg.toString())
                    etTotal.setText(origTotal.toString())
                }
                ResourcePlaybackMode.FRAME_INTERVAL -> {
                    etFrame.isEnabled = true
                    etTotal.isEnabled = false
                    if (item.customValue <= 0) item.customValue = origAvg.coerceIn(8, 500)
                    item.customValue = item.customValue.coerceIn(8, 500)
                    etFrame.setText(item.customValue.toString())
                    etTotal.setText((item.customValue * frameCount).toString())
                }
                ResourcePlaybackMode.TOTAL_DURATION -> {
                    etFrame.isEnabled = false
                    etTotal.isEnabled = true
                    if (item.customValue <= 0) item.customValue = origTotal.coerceIn(100, 60000)
                    item.customValue = item.customValue.coerceIn(100, 60000)
                    etTotal.setText(item.customValue.toString())
                    etFrame.setText((item.customValue / frameCount.coerceAtLeast(1)).coerceAtLeast(1).toString())
                }
            }
            syncing = false
        }

        etFrame.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (syncing || item.playbackMode != ResourcePlaybackMode.FRAME_INTERVAL) return
                val v = s?.toString()?.toIntOrNull()?.coerceIn(8, 500) ?: return
                item.customValue = v
                syncing = true
                etTotal.setText((v * frameCount).toString())
                syncing = false
            }
        })

        etTotal.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (syncing || item.playbackMode != ResourcePlaybackMode.TOTAL_DURATION) return
                val v = s?.toString()?.toIntOrNull()?.coerceIn(100, 60000) ?: return
                item.customValue = v
                syncing = true
                etFrame.setText((v / frameCount.coerceAtLeast(1)).coerceAtLeast(1).toString())
                syncing = false
            }
        })

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (syncing) return@setOnCheckedChangeListener
            item.playbackMode = when (checkedId) {
                rbFrame.id -> ResourcePlaybackMode.FRAME_INTERVAL
                rbTotal.id -> ResourcePlaybackMode.TOTAL_DURATION
                else -> ResourcePlaybackMode.NATIVE
            }
            refreshByMode()
        }

        refreshByMode()

        root.addView(TextView(activity).apply {
            text = "说明：\n" +
                "· 帧间隔是每帧的播放时长，越小越快。\n" +
                "· 总时长是整个动画播放一轮的时长，按帧数均分。\n" +
                "· 只存储一个值（当前模式的字段），另一个由帧数推算。\n" +
                "· 切换模式时保留已填值。\n" +
                "· 修改后需重新应用壁纸或等待状态周期切回该资源时才生效。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 15, 0, 0)
        })

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
                val speedDesc = item.speedDescription()
                LogBuffer.i("资源速度修改: $resourceId -> $speedDesc, factor=${item.speedFactor()}")
                Toast.makeText(activity, "已保存: $speedDesc", Toast.LENGTH_SHORT).show()
                callbacks.onSaved()
            }
        })
        btnRow.addView(Button(activity).apply {
            text = "取消"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = 10 }
            setOnClickListener { callbacks.onBack() }
        })
        root.addView(btnRow)

        return root
    }
}