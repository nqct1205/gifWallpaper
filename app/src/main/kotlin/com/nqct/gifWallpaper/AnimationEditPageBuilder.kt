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

/**
 * 动画编辑页。
 *
 * 【编辑内容】
 *  - 动画名称（可一键改为第一个引用资源名）。
 *  - 背景填充色（手动输入 / 吸管取色 / 参考其他动画颜色）。
 *  - 资源引用列表：每项包含资源选择、9 点锚点（GIF 侧）、9 点目标锚点（屏幕侧）、
 *    缩放模式（比例/像素）、缩放值、偏移。
 *  - 快捷对齐按钮：居中 / 底部对齐 / 铺满宽度。
 *
 * 【就地修改】本页直接修改 [ProjectConfig] 中的 [AnimationItem] 实例；
 * 用户按返回或保存时由 [MainActivity] 负责持久化。
 *
 * 【锚点语义】anchor 决定 GIF 上的哪个点参与对齐/缩放中心，
 * targetAnchor 决定该点对齐到屏幕的哪个位置。二者配合 offset 决定最终绘制矩形，
 * 详见 [GifWallpaperService.computeBounds]。
 *
 * 【背景色语义】背景色属于动画属性，渲染时铺满整个画布。
 * 无论手动输入 / 吸管 / 参考色块，最终都存为 [AnimationItem.backgroundColor] 字符串。
 * 空字符串表示不自定义，回退为黑色（见 [GifWallpaperService.resolveCurrentBackgroundColor]）。
 *
 * 【规范化】手动输入的十六进制串在失焦时被规范化（如 `ff0000` → `#FF0000`），
 * 参考色块在汇总时也规范化。保证相同颜色在存储与展示上只有一种写法，便于去重。
 */
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
                text = "编辑动画"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        // ---- 名称（带一键重命名） ----
        root.addView(buildNameRow(animation), matchWrap(top = 10, bottom = 10))

        // ---- 背景填充色 ----
        root.addView(buildBackgroundColorSection(animation), matchWrap(bottom = 10))

        // ---- 资源引用容器 ----
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

        // ---- 添加引用 ----
        root.addView(Button(activity).apply {
                text = "+ 添加资源引用"
                setOnClickListener {
                    val sortedIds = config.resources.keys.sorted()
                    if (sortedIds.isEmpty()) {
                        Toast.makeText(activity, "请先在「资源」页面导入 GIF", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    animation.resources.add(AnimationResourceRef(resourceId = sortedIds.first()))
                    refreshAll()
                }
            }, matchWrap(top = 10, bottom = 20))

        // ---- 底部操作 ----
        root.addView(buildBottomBar())

        return root
    }

    // ==================== 名称行 ====================

    /**
     * 名称输入行 + “用资源名”按钮。
     * 点击按钮后，动画名改为第一个引用资源的 id（即文件名去 .gif 后缀）。
     * 若尚未引用任何资源，Toast 提示。
     */
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

    /**
     * 背景填充色区块：
     *  - 颜色预览方块 + 十六进制输入框 + 吸管按钮。
     *  - 输入框在**失焦**时规范化：合法颜色 → formatArgbColor；非法/空保留原样。
     *  - 参考颜色：其他动画的非空背景色规范化去重后以色块展示，点击即设为当前。
     */
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

        // 失焦时规范化：合法则写回规范形式；非法/空则保留原样供用户继续修改
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

        // 参考颜色
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

    /**
     * 收集其他动画的非空背景色，规范化后去重。
     *
     * 规范化让 `#ff0000` 与 `ff0000` 等写法合并为一个色块，避免展示重复。
     */
    private fun collectReferenceColors(current: AnimationItem): List<String> {
        return config.animations.values
        .asSequence()
        .filter { it.id != current.id && it.backgroundColor.isNotBlank() }
        .mapNotNull { parseColorSafe(it.backgroundColor) }
        .map { formatArgbColor(it) }
        .distinct()
        .toList()
    }

    /**
     * 吸管取色流程：
     *  1. 弹出资源选择列表（所有已导入 GIF）。
     *  2. 选中后异步解码该 GIF 的第一帧为静态 Bitmap（软件位图）。
     *  3. 打开 [ColorPickerDialog]，用户拖动准星后点确定，回写颜色。
     *
     * 【限 API 28+】GIF 解码依赖 ImageDecoder，低于 28 直接提示。
     * 【取色对象】GIF 第一帧：动图逐帧取色意义不大，第一帧已足够代表主色。
     */
    private fun openColorPicker(animation: AnimationItem, etColor: EditText, preview: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(activity, "吸色需要 Android 9 (API 28) 及以上", Toast.LENGTH_SHORT).show()
            return
        }
        val ids = config.resources.keys.sorted()
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

    /**
     * 后台解码 GIF 第一帧为软件位图。
     *  - 限制最长边 2000px，避免超大位图。
     *  - 强制 ALLOCATOR_SOFTWARE：硬件位图不支持 getPixel，吸色会失败。
     */
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

    /** 单个资源引用卡片。 */
    private fun buildRefCard(
        animation: AnimationItem,
        index: Int,
        ref: AnimationResourceRef,
        refreshAll: () -> Unit
    ): View {
        val sortedIds = config.resources.keys.sorted()
        if (sortedIds.isNotEmpty() && config.resources[ref.resourceId] == null) {
            ref.resourceId = sortedIds.first()
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

        return card
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

        val sortedIds = config.resources.keys.sorted()
        if (sortedIds.isEmpty()) {
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
                sortedIds
            )
            val idx = sortedIds.indexOf(ref.resourceId)
            setSelection(if (idx >= 0) idx else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    val newId = sortedIds.getOrNull(position) ?: return
                    if (ref.resourceId != newId) {
                        ref.resourceId = newId
                        // 资源变化会改变原尺寸提示，需重建卡片；post 避免在 Spinner 事件中同步重建
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

    /**
     * 快捷对齐按钮：一次性设置 anchor + targetAnchor（+ 缩放）。
     *
     * 注意「铺满宽度」：使用当前设备屏幕宽度作为目标像素宽度。
     * 动态壁纸 Canvas 宽度通常等于屏幕宽度；但横竖屏切换时像素值不会自适应，
     * 用户可重新点击此按钮或手动调整。后续可增加 `ScaleMode.FILL_WIDTH` 自适应模式。
     */
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

    /** 9 点锚点选择器。3x3 网格，高亮当前选中。 */
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

    /**
     * 缩放行：模式切换（比例/像素）+ 数值输入 + 等效值实时显示。
     * 模式切换时自动换算，避免用户丢失当前大小。
     */
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

    // ==================== 工具 ====================

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }

    /** 整数值不带小数，非整数保留一位小数。 */
    private fun formatNum(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.1f", v)
}
