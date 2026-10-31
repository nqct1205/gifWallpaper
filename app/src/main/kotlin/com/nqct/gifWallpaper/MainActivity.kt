package com.nqct.gifWallpaper

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var animDir: File
    private var stateMachine = StateMachine()

    private var editingStateId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 隐藏系统的标题栏
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        animDir = File(filesDir, "animations")
        if (!animDir.exists()) animDir.mkdirs()

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val scrollView = ScrollView(this).apply { addView(rootLayout) }
        setContentView(scrollView)

        loadData()
    }

    override fun onResume() {
        super.onResume()
        // 从壁纸设置页返回时刷新界面
        if (editingStateId == null) {
            loadData()
            showMainPage()
        }
    }

    private fun loadData() {
        stateMachine = StateMachine.load(this)
        syncWithFiles()
    }

    /**
     * 【重要逻辑】文件与状态机数据同步
     * 1. 如果硬盘上的文件被用户删了，状态机里对应的节点也要删掉。
     * 2. 如果硬盘新增了文件，状态机里自动创建空节点。
     */
    private fun syncWithFiles() {
        val files = animDir.listFiles { file -> file.extension.lowercase() == "gif" } ?: emptyArray()
        val fileIds = files.map { it.nameWithoutExtension }.toSet()
        
        stateMachine.states.removeAll { it.id !in fileIds }
        for (id in fileIds) {
            if (stateMachine.getState(id) == null) {
                stateMachine.states.add(StateNode(id = id, minSelfPlay = 1, maxSelfPlay = 1))
            }
        }

        // 兜底：如果默认起始节点被删了，自动把第一个文件设为默认
        if (stateMachine.getState(stateMachine.initialStateId) == null) {
            stateMachine.initialStateId = stateMachine.states.firstOrNull()?.id ?: ""
        }
    }

    // ================= 主页面 =================
    private fun showMainPage() {
        editingStateId = null
        rootLayout.removeAllViews()

        val title = TextView(this).apply {
            text = "动画状态机"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        rootLayout.addView(title)

        val btnImport = Button(this).apply {
            text = "导入新动画 (GIF)"
            setOnClickListener { selectGif() }
        }
        rootLayout.addView(btnImport, matchWrap())

        val tvTip = TextView(this).apply {
            text = "提示：设置完规则后，需重新应用一次动态壁纸生效。"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 10, 0, 20)
        }
        rootLayout.addView(tvTip)

        if (stateMachine.states.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "暂无动画，请先导入。"
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 50)
            }
            rootLayout.addView(emptyView)
        } else {
            for (state in stateMachine.states) {
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 20, 20, 20)
                    setBackgroundColor(Color.WHITE)
                    setOnClickListener {
                        editingStateId = state.id
                        showEditorPage(state.id)
                    }
                }
                
                val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val nameTv = TextView(this).apply {
                    text = state.id
                    textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                headerRow.addView(nameTv)

                if (state.id == stateMachine.initialStateId) {
                    val initialTv = TextView(this).apply {
                        text = "默认起始"
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#4CAF50"))
                        setPadding(10, 4, 10, 4)
                    }
                    headerRow.addView(initialTv)
                }
                itemLayout.addView(headerRow)

                val infoTv = TextView(this).apply {
                    text = "播放 ${state.minSelfPlay}~${state.maxSelfPlay} 圈后，有 ${state.rules.size} 条转移规则"
                    textSize = 14f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 10, 0, 0)
                }
                itemLayout.addView(infoTv)

                val marginLayout = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 15)
                }
                rootLayout.addView(itemLayout, marginLayout)
            }
        }

        val btnSetWallpaper = Button(this).apply {
            text = "去应用动态壁纸"
            setOnClickListener {
                stateMachine.save(this@MainActivity)
                try {
                    val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                        putExtra(
                            android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            ComponentName(this@MainActivity, GifWallpaperService::class.java)
                        )
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "系统不支持动态壁纸设置", Toast.LENGTH_LONG).show()
                }
            }
        }
        val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 40, 0, 0)
        }
        rootLayout.addView(btnSetWallpaper, btnParams)

        // JSON 导入/导出
        val ioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 20, 0, 0) }
        val btnExport = Button(this).apply {
            text = "导出配置"
            setOnClickListener { exportJson() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnImportJson = Button(this).apply {
            text = "导入配置"
            setOnClickListener { importJson() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ioRow.addView(btnExport)
        ioRow.addView(btnImportJson)
        rootLayout.addView(ioRow)

        // ================= 监控与调试设置（实时热更新） =================
        val settingsTitle = TextView(this).apply {
            text = "性能监控设置 (实时生效)"
            textSize = 16f
            setPadding(0, 50, 0, 10)
        }
        rootLayout.addView(settingsTitle)

        val prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
        
        val cbShowDebug = CheckBox(this).apply {
            text = "默认在壁纸中显示调试信息"
            isChecked = prefs.getBoolean("showDebug", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("showDebug", isChecked).apply()
            }
        }
        rootLayout.addView(cbShowDebug)

        val drawRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 0) }
        val tvDrawLabel = TextView(this).apply { text = "渲染耗时上限: "; textSize = 14f }
        val etLimitDraw = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("limitDraw", 20).toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val v = s.toString().toIntOrNull() ?: 20
                    prefs.edit().putInt("limitDraw", v).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        val tvDrawUnit = TextView(this).apply { text = " ms"; textSize = 14f }
        drawRow.addView(tvDrawLabel)
        drawRow.addView(etLimitDraw)
        drawRow.addView(tvDrawUnit)
        rootLayout.addView(drawRow)

        val memRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 0) }
        val tvMemLabel = TextView(this).apply { text = "内存占用上限: "; textSize = 14f }
        val etLimitMem = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("limitMem", 150).toString())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val v = s.toString().toIntOrNull() ?: 150
                    prefs.edit().putInt("limitMem", v).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        val tvMemUnit = TextView(this).apply { text = " MB"; textSize = 14f }
        memRow.addView(tvMemLabel)
        memRow.addView(etLimitMem)
        memRow.addView(tvMemUnit)
        rootLayout.addView(memRow)
    }

    // ================= 编辑页面 =================
    private fun showEditorPage(stateId: String) {
        val state = stateMachine.getState(stateId) ?: return
        rootLayout.removeAllViews()

        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val btnBack = Button(this).apply {
            text = "< 返回"
            setOnClickListener { showMainPage() }
        }
        topBar.addView(btnBack)
        val title = TextView(this).apply {
            text = "配置: $stateId"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        }
        topBar.addView(title)
        rootLayout.addView(topBar)

        // 显示源文件详细信息
        val file = File(animDir, "$stateId.gif")
        val info = GifMetadataParser.parse(file)
        val metaText = if (info != null) {
            "源文件: ${file.name}\n尺寸: ${info.width}x${info.height} | 帧数: ${info.frameCount} | 时长: ${info.totalDelayMs / 1000f}秒"
        } else {
            "源文件: ${file.name} (解析失败)"
        }
        val tvMeta = TextView(this).apply {
            text = metaText
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 20, 0, 5)
        }
        rootLayout.addView(tvMeta)

        // 重命名、重映射、删除
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
        val etRename = EditText(this).apply {
            setText(stateId)
            hint = "修改名字"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnRename = Button(this).apply {
            text = "改名"
            setOnClickListener {
                val newName = etRename.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (newName.isEmpty() || newName == stateId) return@setOnClickListener
                if (stateMachine.getState(newName) != null) {
                    Toast.makeText(this@MainActivity, "该名字已存在！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val oldFile = File(animDir, "$stateId.gif")
                val newFile = File(animDir, "$newName.gif")
                if (oldFile.renameTo(newFile)) {
                    state.id = newName
                    // 【重要联动】修改所有指向旧名字的规则
                    for (s in stateMachine.states) {
                        for (r in s.rules) if (r.targetId == stateId) r.targetId = newName
                    }
                    if (stateMachine.initialStateId == stateId) stateMachine.initialStateId = newName
                    stateMachine.save(this@MainActivity)
                    loadData()
                    showEditorPage(newName)
                } else {
                    Toast.makeText(this@MainActivity, "文件重命名失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
        actionRow.addView(etRename)
        actionRow.addView(btnRename)
        rootLayout.addView(actionRow)

        val btnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 20) }
        val btnReplace = Button(this).apply {
            text = "重映射 (替换GIF)"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                // 直接覆盖原文件，不改状态机节点名
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setType("image/gif")
                }
                startActivityForResult(intent, 103)
            }
        }
        val btnDelete = Button(this).apply {
            text = "彻底删除"
            setTextColor(Color.RED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("确认删除")
                    .setMessage("删除后将无法恢复，且所有指向它的规则会失效。确定吗？")
                    .setPositiveButton("删除") { _, _ ->
                        file.delete()
                        stateMachine.states.remove(state)
                        // 清理其他节点中指向它的规则
                        for (s in stateMachine.states) {
                            s.rules.removeAll { it.targetId == stateId }
                        }
                        if (stateMachine.initialStateId == stateId) {
                            stateMachine.initialStateId = stateMachine.states.firstOrNull()?.id ?: ""
                        }
                        stateMachine.save(this@MainActivity)
                        loadData()
                        showMainPage()
                        Toast.makeText(this@MainActivity, "已删除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        btnRow2.addView(btnReplace)
        btnRow2.addView(btnDelete)
        rootLayout.addView(btnRow2)

        if (stateMachine.initialStateId != state.id) {
            val btnSetInitial = Button(this).apply {
                text = "设为默认起始动画"
                setOnClickListener {
                    stateMachine.initialStateId = state.id
                    stateMachine.save(this@MainActivity)
                    Toast.makeText(this@MainActivity, "已设为起始", Toast.LENGTH_SHORT).show()
                    showEditorPage(state.id)
                }
            }
            rootLayout.addView(btnSetInitial, matchWrap())
        }

        val playTitle = TextView(this).apply { text = "自身播放次数范围"; textSize = 16f; setPadding(0, 20, 0, 10) }
        rootLayout.addView(playTitle)
        
        val playRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etMin = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(state.minSelfPlay.toString()); hint = "最小" }
        val etMax = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(state.maxSelfPlay.toString()); hint = "最大" }
        playRow.addView(etMin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        playRow.addView(etMax, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rootLayout.addView(playRow)

        val ruleTitle = TextView(this).apply { text = "转移规则 (权重)"; textSize = 16f; setPadding(0, 30, 0, 10) }
        rootLayout.addView(ruleTitle)

        val rulesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootLayout.addView(rulesContainer, matchWrap())
        val percentTextViews = mutableListOf<TextView>()

        // 【坑点】权重修改不能刷新整个UI，否则输入框焦点会丢失，变成只能输入一位数。只能局部刷新百分比。
        fun updatePercentages() {
            val totalWeight = state.rules.sumOf { it.weight.coerceAtLeast(0) }.coerceAtLeast(1)
            for (i in state.rules.indices) {
                if (i < percentTextViews.size) {
                    percentTextViews[i].text = " ${(state.rules[i].weight.toFloat() / totalWeight * 100).toInt()}% "
                }
            }
        }

        fun refreshRulesUI() {
            rulesContainer.removeAllViews()
            percentTextViews.clear()
            for (i in state.rules.indices) {
                val rule = state.rules[i]
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
                
                val spinner = Spinner(this).apply {
                    val ids = stateMachine.states.map { it.id }
                    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ids)
                    setSelection(ids.indexOf(rule.targetId).coerceAtLeast(0))
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { rule.targetId = ids[position] }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
                row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))

                val etWeight = EditText(this).apply {
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
                }
                row.addView(etWeight, LinearLayout.LayoutParams(100, ViewGroup.LayoutParams.WRAP_CONTENT))

                val tvPercent = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#2196F3")) }
                percentTextViews.add(tvPercent)
                row.addView(tvPercent)

                val btnDel = Button(this).apply {
                    text = "X"
                    setTextColor(Color.RED)
                    setOnClickListener { state.rules.removeAt(i); refreshRulesUI() }
                }
                row.addView(btnDel)
                rulesContainer.addView(row)
            }
            updatePercentages()
        }
        refreshRulesUI()

        val btnAddRule = Button(this).apply {
            text = "+ 添加转移规则"
            setOnClickListener {
                if (stateMachine.states.isNotEmpty()) {
                    state.rules.add(TransitionRule(targetId = stateMachine.states.first().id, weight = 1))
                    refreshRulesUI()
                }
            }
        }
        rootLayout.addView(btnAddRule, matchWrap())

        val btnSave = Button(this).apply {
            text = "保存规则"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                state.minSelfPlay = etMin.text.toString().toIntOrNull() ?: 1
                state.maxSelfPlay = etMax.text.toString().toIntOrNull() ?: 1
                if (state.minSelfPlay <= 0) state.minSelfPlay = 1
                if (state.maxSelfPlay < state.minSelfPlay) state.maxSelfPlay = state.minSelfPlay
                stateMachine.save(this@MainActivity)
                Toast.makeText(this@MainActivity, "规则已保存！", Toast.LENGTH_SHORT).show()
                showMainPage()
            }
        }
        val btnSaveParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 40, 0, 0) }
        rootLayout.addView(btnSave, btnSaveParams)
    }

    private fun exportJson() {
        stateMachine.save(this)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "state_machine_backup.json")
        }
        startActivityForResult(intent, 101)
    }

    private fun importJson() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, 102)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun selectGif() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("image/gif")
        }
        startActivityForResult(intent, 99)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!

        when (requestCode) {
            99 -> {
                try {
                    // 【坑点】通过 ContentResolver 获取系统真实的文件名，而不是乱编一个名字
                    var displayName = "anim_${System.currentTimeMillis()}.gif"
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                val originalName = cursor.getString(nameIndex)
                                if (!originalName.isNullOrBlank()) {
                                    displayName = if (originalName.lowercase().endsWith(".gif")) originalName else "$originalName.gif"
                                }
                            }
                        }
                    }
                    displayName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val targetFile = File(animDir, displayName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                    }
                    loadData()
                    showMainPage()
                    Toast.makeText(this, "导入成功: ${targetFile.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            103 -> {
                try {
                    val currentId = editingStateId ?: return
                    val targetFile = File(animDir, "$currentId.gif")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                    }
                    loadData()
                    Toast.makeText(this, "GIF 文件已被替换，规则保持不变！", Toast.LENGTH_SHORT).show()
                    showEditorPage(currentId)
                } catch (e: Exception) {
                    Toast.makeText(this, "替换失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            101 -> {
                try {
                    val jsonFile = File(filesDir, "state_machine.json")
                    contentResolver.openOutputStream(uri)?.use { output ->
                        jsonFile.inputStream().use { input -> input.copyTo(output) }
                    }
                    Toast.makeText(this, "配置导出成功！", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            102 -> {
                try {
                    val jsonFile = File(filesDir, "state_machine.json")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(jsonFile).use { output -> input.copyTo(output) }
                    }
                    loadData()
                    showMainPage()
                    Toast.makeText(this, "配置导入成功！", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}