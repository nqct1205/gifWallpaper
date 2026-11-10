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

// 资源编辑页（播放速度配置）。
//
// 【P2-10 修复】不再进页面就 clamp 旧数据：
//   - 原实现 refreshByMode 里对 customValue 执行 coerceIn(8,500)/(100,60000)，
//     用户打开一个存有非法值的旧数据页面时，值会被静默修改。
//   - 现实现：进页面显示原值；超出范围时旁边标注"⚠ 超出范围，保存时将被修正"；
//     用户主动编辑主字段时才 clamp；保存时 clamp 并 Toast 提示。
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
        val tvFrameRange = TextView(activity).apply {
            text = " ms（8~500）"
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        rowFrame.addView(tvFrameRange)
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
        val tvTotalRange = TextView(activity).apply {
            text = " ms（100~60000）"
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        rowTotal.addView(tvTotalRange)
        root.addView(rowTotal)

        var syncing = false

        // P2-10：不再 clamp，只显示。超出范围时旁注提示。
        fun refreshByMode() {
            syncing = true
            when (item.playbackMode) {
                ResourcePlaybackMode.NATIVE -> {
                    etFrame.isEnabled = false
                    etTotal.isEnabled = false
                    etFrame.setText(origAvg.toString())
                    etTotal.setText(origTotal.toString())
                    tvFrameRange.text = " ms（8~500）"
                    tvFrameRange.setTextColor(Color.GRAY)
                    tvTotalRange.text = " ms（100~60000）"
                    tvTotalRange.setTextColor(Color.GRAY)
                }
                ResourcePlaybackMode.FRAME_INTERVAL -> {
                    etFrame.isEnabled = true
                    etTotal.isEnabled = false
                    val displayValue = if (item.customValue > 0) item.customValue else origAvg
                    etFrame.setText(displayValue.toString())
                    val totalShown = displayValue * frameCount
                    etTotal.setText(totalShown.toString())
                    if (displayValue !in 8..500) {
                        tvFrameRange.text = " ⚠ 超出范围（8~500），保存时将被修正"
                        tvFrameRange.setTextColor(Color.RED)
                    } else {
                        tvFrameRange.text = " ms（8~500）"
                        tvFrameRange.setTextColor(Color.GRAY)
                    }
                    tvTotalRange.text = " ms（自动）"
                    tvTotalRange.setTextColor(Color.GRAY)
                }
                ResourcePlaybackMode.TOTAL_DURATION -> {
                    etFrame.isEnabled = false
                    etTotal.isEnabled = true
                    val displayValue = if (item.customValue > 0) item.customValue else origTotal
                    etTotal.setText(displayValue.toString())
                    val frameShown = (displayValue / frameCount.coerceAtLeast(1)).coerceAtLeast(1)
                    etFrame.setText(frameShown.toString())
                    if (displayValue !in 100..60000) {
                        tvTotalRange.text = " ⚠ 超出范围（100~60000），保存时将被修正"
                        tvTotalRange.setTextColor(Color.RED)
                    } else {
                        tvTotalRange.text = " ms（100~60000）"
                        tvTotalRange.setTextColor(Color.GRAY)
                    }
                    tvFrameRange.text = " ms（自动）"
                    tvFrameRange.setTextColor(Color.GRAY)
                }
            }
            syncing = false
        }

        // 用户编辑主字段时才 clamp
        etFrame.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (syncing || item.playbackMode != ResourcePlaybackMode.FRAME_INTERVAL) return
                val v = s?.toString()?.toIntOrNull()?.coerceIn(8, 500) ?: return
                item.customValue = v
                syncing = true
                etTotal.setText((v * frameCount).toString())
                tvFrameRange.text = " ms（8~500）"
                tvFrameRange.setTextColor(Color.GRAY)
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
                tvTotalRange.text = " ms（100~60000）"
                tvTotalRange.setTextColor(Color.GRAY)
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
                // 保存时 clamp 并提示
                var clamped = false
                when (item.playbackMode) {
                    ResourcePlaybackMode.FRAME_INTERVAL -> {
                        if (item.customValue !in 8..500) {
                            val old = item.customValue
                            item.customValue = old.coerceIn(8, 500)
                            clamped = true
                            Toast.makeText(
                                activity,
                                "帧间隔 $old ms 超出范围，已修正为 ${item.customValue}ms",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    ResourcePlaybackMode.TOTAL_DURATION -> {
                        if (item.customValue !in 100..60000) {
                            val old = item.customValue
                            item.customValue = old.coerceIn(100, 60000)
                            clamped = true
                            Toast.makeText(
                                activity,
                                "总时长 $old ms 超出范围，已修正为 ${item.customValue}ms",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    else -> {}
                }
                val speedDesc = item.speedDescription()
                LogBuffer.i("资源速度修改: $resourceId -> $speedDesc, factor=${item.speedFactor()}, clamped=$clamped")
                if (!clamped) {
                    Toast.makeText(activity, "已保存: $speedDesc", Toast.LENGTH_SHORT).show()
                }
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