package com.nqct.gifWallpaper

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.widget.ScrollView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * 应用配置入口 Activity。
 *
 * 职责：
 *  - 生命周期（onCreate / onResume / onBackPressed）；
 *  - 数据加载与文件同步（loadData / syncWithFiles）；
 *  - 页面导航（showMainPage / showEditorPage → 委托给对应 Builder）；
 *  - ActivityResult 分发（文件选择 / JSON 导入导出 / 重映射）。
 */
class MainActivity : Activity() {

    private lateinit var animDir: File
    private var stateMachine = StateMachine()

    /** 当前编辑中的状态 ID；null 表示处于主页面 */
    private var editingStateId: String? = null

    /** 上一次加载的警告（配置损坏 / 导入失败等），用于在主页顶部红条显示 */
    private var lastLoadWarning: String? = null

    /** 上次 onResume 时的同步签名（JSON mtime + GIF 数量），用于跳过无意义的全量 reload */
    private var lastSyncSignature: String = ""

    private companion object {
        const val REQ_IMPORT_GIF = 99
        const val REQ_REPLACE_GIF = 103
        const val REQ_EXPORT_JSON = 101
        const val REQ_IMPORT_JSON = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        animDir = File(filesDir, "animations")
        if (!animDir.exists()) animDir.mkdirs()

        loadData()
        lastSyncSignature = currentSyncSignature()
        showMainPage()
    }

    override fun onResume() {
        super.onResume()
        // 只有磁盘状态真正变化时才 reload，避免 home 键切后台也全量刷新
        if (editingStateId == null) {
            val sig = currentSyncSignature()
            if (sig != lastSyncSignature) {
                loadData()
                lastSyncSignature = sig
            }
            showMainPage()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (editingStateId != null) {
            showMainPage()
        } else {
            super.onBackPressed()
        }
    }

    // ==================== 数据管理 ====================

    private fun currentSyncSignature(): String {
        val jsonFile = File(filesDir, "state_machine.json")
        val jsonMod = if (jsonFile.exists()) jsonFile.lastModified() else -1L
        val gifCount = animDir.listFiles { f -> f.extension.lowercase() == "gif" }?.size ?: 0
        return "$jsonMod:$gifCount"
    }

    /**
     * 加载 stateMachine + 与磁盘文件同步。
     * 【关键策略】JSON 损坏时保留旧 stateMachine，不覆盖磁盘（避免坏文件毁掉当前配置）。
     */
    private fun loadData() {
        when (val result = StateMachine.load(this)) {
            is LoadResult.Ok -> {
                stateMachine = result.sm
                lastLoadWarning = null
            }
            is LoadResult.Corrupted -> {
                // 保留旧 stateMachine，不覆盖磁盘
                lastLoadWarning = "⚠ 配置文件损坏，本次未应用: ${result.message}"
            }
        }
        syncWithFiles()
    }

    /**
     * 文件与状态机数据同步：
     * 1. 硬盘上被用户删掉的文件，状态机里对应的节点同步移除；
     * 2. 硬盘上新增的 GIF 文件，自动创建空节点；
     * 3. 若默认起始节点被删，自动回退到第一个文件。
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

        if (stateMachine.getState(stateMachine.initialStateId) == null) {
            stateMachine.initialStateId = stateMachine.states.firstOrNull()?.id ?: ""
        }
    }

    // ==================== 页面导航 ====================

    private fun showMainPage() {
        editingStateId = null

        val view = MainPageBuilder(this, stateMachine, lastLoadWarning, object : MainPageBuilder.Callbacks {
            override fun onEditState(stateId: String) {
                editingStateId = stateId
                showEditorPage(stateId)
            }

            override fun onImportGif() {
                selectGif()
            }

            override fun onApplyWallpaper() {
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

            override fun onExportJson() {
                exportJson()
            }

            override fun onImportJson() {
                importJson()
            }
        }).build()

        setContentView(ScrollView(this).apply { addView(view) })
    }

    private fun showEditorPage(stateId: String) {
        if (stateMachine.getState(stateId) == null) {
            showMainPage()
            return
        }
        editingStateId = stateId

        val view = EditorPageBuilder(
            this,
            animDir,
            stateMachine,
            stateId,
            object : EditorPageBuilder.Callbacks {
                override fun onBack() {
                    showMainPage()
                }

                override fun onRenameSuccess(newId: String) {
                    loadData()
                    showEditorPage(newId)
                }

                override fun onDeleteSuccess() {
                    loadData()
                    showMainPage()
                }

                override fun onRequestReplaceGif() {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        setType("image/gif")
                    }
                    startActivityForResult(intent, REQ_REPLACE_GIF)
                }

                override fun onSaved() {
                    showMainPage()
                }

                override fun onRefresh() {
                    showEditorPage(stateId)
                }
            }
        ).build()

        setContentView(ScrollView(this).apply { addView(view) })
    }

    // ==================== 文件操作 ====================

    private fun exportJson() {
        stateMachine.save(this)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "state_machine_backup.json")
        }
        startActivityForResult(intent, REQ_EXPORT_JSON)
    }

    private fun importJson() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQ_IMPORT_JSON)
    }

    private fun selectGif() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("image/gif")
        }
        startActivityForResult(intent, REQ_IMPORT_GIF)
    }

    // ==================== ActivityResult 分发 ====================

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!

        when (requestCode) {
            REQ_IMPORT_GIF -> importNewGif(uri)
            REQ_REPLACE_GIF -> replaceCurrentGif(uri)
            REQ_EXPORT_JSON -> exportJsonTo(uri)
            REQ_IMPORT_JSON -> importJsonFrom(uri)
        }
    }

    /** 处理"导入新 GIF"。 */
    private fun importNewGif(uri: android.net.Uri) {
        try {
            var displayName = "anim_${System.currentTimeMillis()}.gif"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val originalName = cursor.getString(nameIndex)
                        if (!originalName.isNullOrBlank()) {
                            displayName = if (originalName.lowercase().endsWith(".gif")) {
                                originalName
                            } else {
                                "$originalName.gif"
                            }
                        }
                    }
                }
            }

            // 【统一清洗文件名】去掉非法字符 / 前置点 / 超长
            val base = displayName.substringBeforeLast('.', displayName)
            val safeBase = sanitizeFileName(base)
            displayName = "$safeBase.gif"

            val targetFile = File(animDir, displayName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            loadData()
            lastSyncSignature = currentSyncSignature()
            showMainPage()
            Toast.makeText(this, "导入成功: ${targetFile.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 处理"重映射替换当前 GIF"。 */
    private fun replaceCurrentGif(uri: android.net.Uri) {
        try {
            val currentId = editingStateId ?: return
            val targetFile = File(animDir, "$currentId.gif")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            loadData()
            lastSyncSignature = currentSyncSignature()
            Toast.makeText(this, "GIF 文件已被替换，规则保持不变！", Toast.LENGTH_SHORT).show()
            showEditorPage(currentId)
        } catch (e: Exception) {
            Toast.makeText(this, "替换失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 处理"导出配置"。 */
    private fun exportJsonTo(uri: android.net.Uri) {
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

    /**
     * 处理"导入配置"。
     * 【关键策略】先解析验证，损坏时不覆盖当前配置，仅在主页顶部提示。
     */
    private fun importJsonFrom(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalArgumentException("无法读取文件")

            // 先写临时文件，用 StateMachine.load 验证
            val tmp = File(filesDir, "state_machine_import_tmp.json")
            tmp.writeText(text)
            val result = StateMachine.load(tmp)
            tmp.delete()

            when (result) {
                is LoadResult.Ok -> {
                    File(filesDir, "state_machine.json").writeText(text)
                    loadData()
                    lastSyncSignature = currentSyncSignature()
                    showMainPage()
                    Toast.makeText(this, "配置导入成功！", Toast.LENGTH_SHORT).show()
                }
                is LoadResult.Corrupted -> {
                    // 保留旧数据
                    lastLoadWarning = "⚠ 导入失败，文件损坏未应用: ${result.message}"
                    showMainPage()
                    Toast.makeText(this, "导入失败: 文件损坏", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}