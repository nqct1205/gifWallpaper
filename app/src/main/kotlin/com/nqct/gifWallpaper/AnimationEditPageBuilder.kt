package com.nqct.gifWallpaper

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.os.Build
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

// 动画编辑页。
//
// 【结束方式】
//   多资源时提供两种结束方式（RadioGroup 二选一）：
//     - 自动：单资源=资源自身播完；多资源=最后一个资源播完。endTimeMs = 0。
//     - 自定义时长：持续 N 毫秒后结束，所有资源立即停止绘制。endTimeMs = N。
//   单资源时隐藏整块（自动即资源自身时长）。
//
// 【返回语义】"< 返回"按钮直接调 callbacks.onBack()。
// 是否保存由 MainActivity 判断（未修改不弹窗）。
class AnimationEditPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val animationId: String,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onSaved()
        fun onDelete()
    }

    private enum class SelectorType { ANCHOR, TARGET }

    fun build(): View {
        val animation = config.animations[animationId] ?: return View(activity)

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
            text = "编辑动画"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        root.addView(buildNameRow(animation), matchWrap(top = 10, bottom = 10))
        root.addView(buildBackgroundColorSection(animation), matchWrap(bottom = 10))

        // 多资源：结束方式 + 提示
        if (animation.resources.size > 1) {
            root.addView(buildEndTimeSection(animation), matchWrap(bottom = 10))
            root.addView(TextView(activity).apply {
                text = "当前动画引用了 ${animation.resources.size} 个资源。" +
                    "每个资源可设置独立的启动延迟。"
                textSize = 12f
                setTextColor(Color.parseColor("#2196F3"))
                setPadding(0, 0, 0, 10)
            })
        }

        val refContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(refContainer)

        fun refreshAll() {
            refContainer.removeAllViews()
            if (animation.resources.isEmpty()) {
                refContainer.addView(TextView(activity).apply {
                    text = "尚未添加资源引用"
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(20, 20, 20, 20)
                })
            } else {
                animation.resources.forEachIndexed { idx, ref ->
                    refContainer.addView(
                        buildRefCard(animation, idx, ref, ::refreshAll),
                        matchWrap(bottom = 10)
                    )
                }
            }
        }
        refreshAll()

        root.addView(Button(activity).apply {
            text = "+ 添加资源引用"
            setOnClickListener {
                val ids = config.resources.keys.toList()
                if (ids.isEmpty()) {
                    Toast.makeText(activity, "请先在「资源」页面导入 GIF", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                animation.resources.add(AnimationResourceRef(resourceId = ids.first()))
                refreshAll()
            }
        }, matchWrap(top = 10, bottom = 20))

        root.addView(buildBottomBar())

        return root
    }

    // ==================== 结束方式 ====================

    // 计算"自动"时长的预估值（供 UI 显示）。
    private fun computeAutoTotalMs(animation: AnimationItem): Long {
        var maxEnd = 0L
        for (ref in animation.resources) {
            val resource = config.resources[ref.resourceId] ?: continue
            val dur = resource.targetTotalDurationMs()
            val end = ref.startDelayMs.toLong() + dur
            if (end > maxEnd) maxEnd = end
        }
        return maxEnd.coerceAtLeast(1L)
    }

    private fun buildEndTimeSection(animation: AnimationItem): View {
        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }
        section.addView(TextView(activity).apply {
            text = "结束方式"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        })

        val autoTotalMs = computeAutoTotalMs(animation)
        val autoDesc = if (animation.resources.size == 1) {
            "自动（资源播放结束时，约 ${autoTotalMs}ms）"
        } else {
            "自动（最后一个资源播放结束时，约 ${autoTotalMs}ms）"
        }

        val rbAuto = RadioButton(activity).apply {
            id = View.generateViewId()
            text = autoDesc
            textSize = 14f
        }
        val rbCustom = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "自定义时长"
            textSize = 14f
        }
        // 先设 isChecked 再 addView，避免触发多次回调
        if (animation.endTimeMs <= 0) rbAuto.isChecked = true else rbCustom.isChecked = true

        val rg = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        rg.addView(rbAuto)
        rg.addView(rbCustom)
        section.addView(rg)

        // 自定义时长输入行
        val customRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 0)
            visibility = if (animation.endTimeMs > 0) View.VISIBLE else View.GONE
        }
        customRow.addView(TextView(activity).apply {
            text = "持续: "
            textSize = 13f
        })
        val etCustom = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            val displayVal = if (animation.endTimeMs > 0) animation.endTimeMs
                else autoTotalMs.coerceAtLeast(1000).toInt()
            setText(displayVal.toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    animation.endTimeMs = v
                }
            })
        }
        customRow.addView(etCustom)
        customRow.addView(TextView(activity).apply {
            text = " ms"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        section.addView(customRow)

        section.addView(TextView(activity).apply {
            text = "说明：\n" +
                "· 自动：按资源自身的播放时长决定整圈结束时间。\n" +
                "· 自定义：到达指定时长后所有资源立即停止绘制，切换到下一状态。\n" +
                "  早于该时长播完的资源会提前消失；晚于该时长仍在播的会被截断。"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 8, 0, 0)
        })

        rg.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == rbAuto.id) {
                animation.endTimeMs = 0
                customRow.visibility = View.GONE
            } else {
                if (animation.endTimeMs <= 0) {
                    // 切到自定义时给一个默认值（基于自动时长）
                    animation.endTimeMs = autoTotalMs.coerceAtLeast(1000).toInt()
                    etCustom.setText(animation.endTimeMs.toString())
                    etCustom.setSelection(etCustom.text?.length ?: 0)
                }
                customRow.visibility = View.VISIBLE
            }
        }

        return section
    }

    // ==================== 名称行 ====================

    private fun buildNameRow(animation: AnimationItem): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(activity).apply {
            text = "名称: "
            textSize = 14f
        })
        val etName = EditText(activity).apply {
            setText(animation.name)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    animation.name = s?.toString() ?: ""
                }
            })
        }
        row.addView(etName)
        row.addView(Button(activity).apply {
            text = "用资源名"
            textSize = 12f
            setOnClickListener {
                val firstRef = animation.resources.firstOrNull()
                val resource = firstRef?.let { config.resources[it.resourceId] }
                if (resource == null) {
                    Toast.makeText(activity, "尚未引用任何资源", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val newName = resource.id
                animation.name = newName
                etName.setText(newName)
                etName.setSelection(newName.length)
            }
        })
        return row
    }

    // ==================== 背景颜色 ====================

    private fun buildBackgroundColorSection(animation: AnimationItem): View {
        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }

        section.addView(TextView(activity).apply {
            text = "背景填充色"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        })

        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 0)
        }

        val preview = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(60, 60)
            setBackgroundColor(parseColorSafe(animation.backgroundColor) ?: Color.BLACK)
        }
        inputRow.addView(preview)

        val etColor = EditText(activity).apply {
            hint = "#RRGGBB 或 #AARRGGBB，留空=黑色"
            setText(animation.backgroundColor)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = 10 }
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    val raw = s?.toString()?.trim() ?: ""
                    animation.backgroundColor = raw
                    preview.setBackgroundColor(parseColorSafe(raw) ?: Color.BLACK)
                }
            })
        }
        etColor.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val raw = etColor.text?.toString()?.trim() ?: ""
            val parsed = parseColorSafe(raw) ?: return@setOnFocusChangeListener
            val normalized = formatArgbColor(parsed)
            if (normalized != raw) {
                etColor.setText(normalized)
                etColor.setSelection(normalized.length)
            }
        }
        inputRow.addView(etColor)

        inputRow.addView(Button(activity).apply {
            text = "吸管"
            textSize = 12f
            setOnClickListener { openColorPicker(animation, etColor, preview) }
        })
        section.addView(inputRow)

        val referenceColors = collectReferenceColors(animation)
        if (referenceColors.isNotEmpty()) {
            section.addView(TextView(activity).apply {
                text = "参考颜色（其他动画的背景色）"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, 12, 0, 6)
            })
            val colorRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (hex in referenceColors) {
                val colorInt = parseColorSafe(hex) ?: continue
                colorRow.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(48, 48).apply { rightMargin = 8 }
                    setBackgroundColor(colorInt)
                    setOnClickListener {
                        animation.backgroundColor = hex
                        etColor.setText(hex)
                        etColor.setSelection(hex.length)
                        preview.setBackgroundColor(colorInt)
                    }
                })
            }
            section.addView(colorRow)
        }

        return section
    }

    private fun collectReferenceColors(current: AnimationItem): List<String> {
        return config.animations.values
            .asSequence()
            .filter { it.id != current.id && it.backgroundColor.isNotBlank() }
            .mapNotNull { parseColorSafe(it.backgroundColor) }
            .map { formatArgbColor(it) }
            .distinct()
            .toList()
    }

    private fun openColorPicker(animation: AnimationItem, etColor: EditText, preview: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(activity, "吸色需要 Android 9 (API 28) 及以上", Toast.LENGTH_SHORT).show()
            return
        }
        val ids = config.resources.keys.toList()
        if (ids.isEmpty()) {
            Toast.makeText(activity, "还没有任何资源可吸色", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("选择用于吸色的 GIF")
            .setItems(ids.toTypedArray()) { _, which ->
                val resId = ids[which]
                val resource = config.resources[resId] ?: return@setItems
                val file = File(ProjectConfigStore.animDir(activity), resource.fileName)
                loadFirstFrameBitmapAsync(file) { bitmap ->
                    if (bitmap == null) {
                        Toast.makeText(activity, "图片解码失败", Toast.LENGTH_SHORT).show()
                        return@loadFirstFrameBitmapAsync
                    }
                    ColorPickerDialog.show(activity, bitmap) { color ->
                        val hex = formatArgbColor(color)
                        animation.backgroundColor = hex
                        etColor.setText(hex)
                        etColor.setSelection(hex.length)
                        preview.setBackgroundColor(color)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadFirstFrameBitmapAsync(file: File, onDone: (Bitmap?) -> Unit) {
        Thread {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && file.exists()) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        val maxDim = 2000
                        val w = info.size.width
                        val h = info.size.height
                        val longSide = maxOf(w, h)
                        if (longSide > maxDim) {
                            val s = maxDim.toFloat() / longSide
                            decoder.setTargetSize(
                                (w * s).toInt().coerceAtLeast(1),
                                (h * s).toInt().coerceAtLeast(1)
                            )
                        }
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else null
            } catch (e: Exception) {
                null
            }
            activity.runOnUiThread { onDone(bmp) }
        }.start()
    }

    // ==================== 资源引用卡片 ====================

    private fun buildRefCard(
        animation: AnimationItem,
        index: Int,
        ref: AnimationResourceRef,
        refreshAll: () -> Unit
    ): View {
        val ids = config.resources.keys.toList()
        if (ids.isNotEmpty() && config.resources[ref.resourceId] == null) {
            ref.resourceId = ids.first()
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(activity).apply {
            text = "资源引用 #${index + 1}"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(activity).apply {
            text = "移除"
            setTextColor(Color.RED)
            setOnClickListener {
                animation.resources.remove(ref)
                refreshAll()
            }
        })
        card.addView(header)

        card.addView(buildResourceSelector(ref, refreshAll), matchWrap(top = 5))

        val resource = config.resources[ref.resourceId]
        val info = resource?.info
        card.addView(TextView(activity).apply {
            text = if (info != null) {
                "原尺寸: ${info.width}x${info.height}  |  ${info.frameCount} 帧  |  ${info.totalDelayMs / 1000f} 秒"
            } else {
                "（资源未解析）"
            }
            textSize = 12f
            setTextColor(if (info != null) Color.GRAY else Color.RED)
            setPadding(0, 5, 0, 5)
        })

        card.addView(buildQuickAlignRow(ref, refreshAll), matchWrap(top = 5, bottom = 5))

        card.addView(TextView(activity).apply {
            text = "GIF 锚点（缩放/对齐中心）"
            textSize = 13f
            setPadding(0, 10, 0, 5)
        })
        card.addView(buildAnchorSelector(ref, SelectorType.ANCHOR))

        card.addView(TextView(activity).apply {
            text = "屏幕目标锚点（对齐位置）"
            textSize = 13f
            setPadding(0, 10, 0, 5)
        })
        card.addView(buildAnchorSelector(ref, SelectorType.TARGET))

        card.addView(TextView(activity).apply {
            text = "缩放"
            textSize = 13f
            setPadding(0, 10, 0, 5)
        })
        card.addView(buildScaleRow(ref, info))

        card.addView(TextView(activity).apply {
            text = "额外偏移（像素，正向右 / 下）"
            textSize = 13f
            setPadding(0, 10, 0, 5)
        })
        card.addView(buildOffsetRow(ref))

        // 多资源时显示启动延迟
        if (animation.resources.size > 1) {
            card.addView(TextView(activity).apply {
                text = "播放时间"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 15, 0, 5)
            })
            card.addView(buildStartDelayRow(ref))
            card.addView(TextView(activity).apply {
                text = "说明：从动画开始后延迟 ${ref.startDelayMs}ms 再播放本资源。"
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 3, 0, 0)
            })
        }

        return card
    }

    private fun buildStartDelayRow(ref: AnimationResourceRef): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(activity).apply {
            text = "启动延迟: "
            textSize = 13f
        })
        row.addView(EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ref.startDelayMs.toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    ref.startDelayMs = s?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                }
            })
        })
        row.addView(TextView(activity).apply {
            text = " ms"
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        return row
    }

    private fun buildResourceSelector(ref: AnimationResourceRef, refreshAll: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(activity).apply {
            text = "资源: "
            textSize = 13f
        })

        val ids = config.resources.keys.toList()
        if (ids.isEmpty()) {
            row.addView(TextView(activity).apply {
                text = "无可用资源"
                textSize = 13f
                setTextColor(Color.RED)
            })
            return row
        }

        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                ids
            )
            val idx = ids.indexOf(ref.resourceId)
            setSelection(if (idx >= 0) idx else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    val newId = ids.getOrNull(position) ?: return
                    if (ref.resourceId != newId) {
                        ref.resourceId = newId
                        view?.post { refreshAll() }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(spinner)
        return row
    }

    private fun buildQuickAlignRow(ref: AnimationResourceRef, refreshAll: () -> Unit): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        row.addView(Button(activity).apply {
            text = "居中"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                ref.anchor = AnchorPoint.CENTER
                ref.targetAnchor = AnchorPoint.CENTER
                refreshAll()
            }
        })
        row.addView(Button(activity).apply {
            text = "底部对齐"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                ref.anchor = AnchorPoint.BOTTOM_CENTER
                ref.targetAnchor = AnchorPoint.BOTTOM_CENTER
                refreshAll()
            }
        })
        row.addView(Button(activity).apply {
            text = "铺满宽度"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val screenW = activity.resources.displayMetrics.widthPixels
                ref.scaleMode = ScaleMode.PIXEL
                ref.scaleValue = screenW.toFloat()
                ref.anchor = AnchorPoint.BOTTOM_CENTER
                ref.targetAnchor = AnchorPoint.BOTTOM_CENTER
                refreshAll()
            }
        })
        return row
    }

    private fun buildAnchorSelector(ref: AnimationResourceRef, type: SelectorType): View {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val buttons = mutableMapOf<AnchorPoint, Button>()

        fun current(): AnchorPoint = when (type) {
            SelectorType.ANCHOR -> ref.anchor
            SelectorType.TARGET -> ref.targetAnchor
        }
        fun setCurrent(p: AnchorPoint) {
            when (type) {
                SelectorType.ANCHOR -> ref.anchor = p
                SelectorType.TARGET -> ref.targetAnchor = p
            }
        }
        fun refresh() {
            val cur = current()
            for ((p, b) in buttons) {
                val selected = (p == cur)
                b.setBackgroundColor(
                    if (selected) Color.parseColor("#2196F3") else Color.parseColor("#E0E0E0")
                )
                b.setTextColor(if (selected) Color.WHITE else Color.BLACK)
            }
        }

        val rows = listOf(
            listOf(AnchorPoint.TOP_LEFT, AnchorPoint.TOP_CENTER, AnchorPoint.TOP_RIGHT),
            listOf(AnchorPoint.CENTER_LEFT, AnchorPoint.CENTER, AnchorPoint.CENTER_RIGHT),
            listOf(AnchorPoint.BOTTOM_LEFT, AnchorPoint.BOTTOM_CENTER, AnchorPoint.BOTTOM_RIGHT)
        )
        for (row in rows) {
            val rowView = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (p in row) {
                val b = Button(activity).apply {
                    text = p.label
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        setCurrent(p)
                        refresh()
                    }
                }
                buttons[p] = b
                rowView.addView(b)
            }
            container.addView(rowView)
        }
        refresh()
        return container
    }

    private fun buildScaleRow(ref: AnimationResourceRef, info: GifInfo?): View {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val origW = (info?.width ?: 1).coerceAtLeast(1)

        val rbPercent = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "比例 (%)"
            textSize = 13f
        }
        val rbPixel = RadioButton(activity).apply {
            id = View.generateViewId()
            text = "像素宽度 (px)"
            textSize = 13f
        }
        val group = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbPercent)
            addView(rbPixel)
        }
        if (ref.scaleMode == ScaleMode.PERCENT) rbPercent.isChecked = true else rbPixel.isChecked = true
        container.addView(group)

        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val etValue = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatNum(ref.scaleValue))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvEquiv = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#2196F3"))
            setPadding(10, 0, 0, 0)
        }
        inputRow.addView(etValue)
        inputRow.addView(tvEquiv)
        container.addView(inputRow)

        fun updateEquiv() {
            tvEquiv.text = when (ref.scaleMode) {
                ScaleMode.PERCENT -> {
                    val px = origW * (ref.scaleValue / 100f)
                    "≈ 宽度 ${px.toInt()} px"
                }
                ScaleMode.PIXEL -> {
                    val pct = if (origW > 0) ref.scaleValue / origW * 100f else 0f
                    "≈ ${formatNum(pct)}%"
                }
            }
        }
        updateEquiv()

        etValue.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                ref.scaleValue = s?.toString()?.toFloatOrNull() ?: ref.scaleValue
                updateEquiv()
            }
        })

        group.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == rbPercent.id) ScaleMode.PERCENT else ScaleMode.PIXEL
            if (newMode == ref.scaleMode) return@setOnCheckedChangeListener
            val oldValue = ref.scaleValue
            val newValue = when {
                ref.scaleMode == ScaleMode.PERCENT && newMode == ScaleMode.PIXEL ->
                    origW * (oldValue / 100f)
                ref.scaleMode == ScaleMode.PIXEL && newMode == ScaleMode.PERCENT ->
                    oldValue / origW * 100f
                else -> oldValue
            }
            ref.scaleMode = newMode
            ref.scaleValue = newValue
            etValue.setText(formatNum(newValue))
            etValue.setSelection(etValue.text?.length ?: 0)
            updateEquiv()
        }

        return container
    }

    private fun buildOffsetRow(ref: AnimationResourceRef): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(TextView(activity).apply { text = "X:"; textSize = 13f })
        row.addView(EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatNum(ref.offsetX))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    ref.offsetX = s?.toString()?.toFloatOrNull() ?: 0f
                }
            })
        })
        row.addView(TextView(activity).apply {
            text = "px"
            textSize = 13f
            setPadding(5, 0, 20, 0)
        })

        row.addView(TextView(activity).apply { text = "Y:"; textSize = 13f })
        row.addView(EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatNum(ref.offsetY))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    ref.offsetY = s?.toString()?.toFloatOrNull() ?: 0f
                }
            })
        })
        row.addView(TextView(activity).apply { text = "px"; textSize = 13f; setPadding(5, 0, 0, 0) })

        return row
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
            text = "删除动画"
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

    private fun formatNum(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.1f", v)
}