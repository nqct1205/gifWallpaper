package com.nqct.gifWallpaper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.window.OnBackInvokedDispatcher
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 应用入口 Activity。
//
// 【职责】生命周期、页面路由、编辑快照判定、弹窗/Toast、Intent 发起。
//   具体数据操作交给 ConfigOperations，备份 IO 交给 ConfigBackupManager，
//   壁纸操作交给 WallpaperController。
//
// 【编辑页返回语义】
//   进入编辑页时记录 config 的 JSON 快照（editSnapshot）。
//   返回时比较当前 config 的 JSON 与快照：
//     - 相同（未修改）→ 直接 loadConfig + showPage(returnTo)，不弹窗。
//     - 不同（已修改）→ 弹"保存 / 不保存 / 取消"对话框。
//   覆盖 ResourceEdit / AnimationEdit / StateEdit / EventRuleEdit 四个编辑页。
class MainActivity : Activity() {

    private var config = ProjectConfig()
    private var lastWarning: String? = null
    private var currentPage: Page = Page.Main

    // 进入编辑页时的 JSON 快照。null 表示当前不在编辑页。
    private var editSnapshot: String? = null

    private sealed class Page {
        object Main : Page()
        object ResourceList : Page()
        data class ResourceEdit(val resourceId: String) : Page()
        object AnimationList : Page()
        data class AnimationEdit(val animationId: String) : Page()
        object StateList : Page()
        data class StateEdit(
            val stateId: String,
            val returnTo: Page = StateList
        ) : Page()
        object EventRuleList : Page()
        data class EventRuleEdit(
            val ruleId: String?,
            val returnTo: Page = EventRuleList
        ) : Page()
        object StateGraph : Page()
        object LogExport : Page()
    }

    companion object {
        private const val REQ_IMPORT_GIF = 200
        private const val REQ_EXPORT_JSON = 201
        private const val REQ_EXPORT_ZIP = 202
        private const val REQ_IMPORT_JSON = 203
        private const val REQ_IMPORT_ZIP = 204
        private const val REQ_EXPORT_LOG_GENERAL = 205
        private const val REQ_EXPORT_LOG_PERF = 206
        private const val REQ_EXPORT_LOG_ERROR = 207
        private const val REQ_EXPORT_LOG_ALL = 208
    }

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        registerBackHandler()
        loadConfig()
        showPage(Page.Main)
        LogBuffer.i("MainActivity 创建")
    }

    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                handleBackNavigation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentPage == Page.Main) {
            loadConfig()
            showPage(Page.Main)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            handleBackNavigation()
        }
    }

    // ==================== 返回导航 ====================

    private fun handleBackNavigation() {
        val cur = currentPage
        when (cur) {
            is Page.Main -> exitActivity()
            is Page.ResourceEdit -> handleEditBack(Page.ResourceList)
            is Page.AnimationEdit -> handleEditBack(Page.AnimationList)
            is Page.StateEdit -> handleEditBack(cur.returnTo)
            is Page.EventRuleEdit -> handleEditBack(cur.returnTo)
            is Page.LogExport -> showPage(Page.Main)
            else -> showPage(Page.Main)
        }
    }

    private fun exitActivity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            finish()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // 编辑页返回统一处理：先比较是否修改，未修改直接返回，修改了才弹窗。
    private fun handleEditBack(returnTo: Page) {
        val snapshot = editSnapshot
        val currentJson = try {
            ProjectConfigStore.toJsonString(config)
        } catch (e: Exception) {
            null
        }

        if (snapshot != null && currentJson != null && snapshot == currentJson) {
            editSnapshot = null
            loadConfig()
            showPage(returnTo)
            return
        }

        AlertDialog.Builder(this)
            .setTitle("未保存的修改")
            .setMessage("是否保存当前修改？")
            .setPositiveButton("保存") { _, _ ->
                editSnapshot = null
                if (saveConfig()) showPage(returnTo)
            }
            .setNeutralButton("不保存") { _, _ ->
                editSnapshot = null
                loadConfig()
                showPage(returnTo)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 配置加载 / 保存 ====================

    private fun loadConfig() {
        when (val r = ProjectConfigStore.load(this)) {
            is ProjectConfigStore.LoadResult.Ok -> {
                config = r.config
                lastWarning = null
            }
            is ProjectConfigStore.LoadResult.Corrupted -> {
                lastWarning = "配置损坏，本次未应用: ${r.message}"
                LogBuffer.e("配置损坏: ${r.message}")
            }
        }
    }

    private fun saveConfig(): Boolean {
        ConfigOperations.normalizeSingleResourceAnimations(config)

        val ok = ProjectConfigStore.save(this, config)
        if (!ok) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            return false
        }

        val issues = config.validate()
        if (issues.isNotEmpty()) {
            issues.forEach { LogBuffer.w("配置校验: $it") }
        }
        return true
    }

    // ==================== 页面路由 ====================

    private fun showPage(page: Page) {
        currentPage = page

        if (page is Page.StateGraph) {
            setContentView(buildStateGraphPage())
            return
        }

        val scrollView = ScrollView(this)
        val content: android.view.View = when (page) {
            is Page.Main -> buildMainPage()
            is Page.ResourceList -> buildResourceListPage(scrollView)
            is Page.ResourceEdit -> buildResourceEditPage(page.resourceId)
            is Page.AnimationList -> buildAnimationListPage(scrollView)
            is Page.AnimationEdit -> buildAnimationEditPage(page.animationId, scrollView)
            is Page.StateList -> buildStateListPage(scrollView)
            is Page.StateEdit -> buildStateEditPage(page)
            is Page.EventRuleList -> buildEventRuleListPage()
            is Page.EventRuleEdit -> buildEventRuleEditPage(page)
            is Page.LogExport -> buildLogExportPage()
            is Page.StateGraph -> error("unreachable")
        }
        scrollView.addView(content)
        setContentView(scrollView)
    }

    // 进入编辑页前记录快照。若已有快照（理论上不会，因为每次返回都会清），先清。
    private fun captureEditSnapshot() {
        editSnapshot = try {
            ProjectConfigStore.toJsonString(config)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildMainPage(): android.view.View = MainPageBuilder(
        activity = this,
        config = config,
        warningMessage = lastWarning,
        callbacks = object : MainPageBuilder.Callbacks {
            override fun onOpenResources() { showPage(Page.ResourceList) }
            override fun onOpenAnimations() { showPage(Page.AnimationList) }
            override fun onOpenStates() { showPage(Page.StateList) }
            override fun onOpenEventRules() { showPage(Page.EventRuleList) }
            override fun onOpenStateGraph() { showPage(Page.StateGraph) }
            override fun onSetDefaultState() { showDefaultStateDialog() }
            override fun onApplyWallpaper() { applyWallpaper() }
            override fun onRestoreSystemWallpaper() { confirmRestoreSystemWallpaper() }
            override fun onExportJson() { startExportJson() }
            override fun onExportZip() { startExportZip() }
            override fun onImportJson() { startImportJson() }
            override fun onImportZip() { confirmImport("导入压缩包") { startImportZip() } }
            override fun onExportLog() { showPage(Page.LogExport) }
        }
    ).build()

    private fun buildLogExportPage(): android.view.View = LogExportPageBuilder(
        activity = this,
        callbacks = object : LogExportPageBuilder.Callbacks {
            override fun onBack() { showPage(Page.Main) }
            override fun onExportGeneral() { startExportLog(REQ_EXPORT_LOG_GENERAL, "general") }
            override fun onExportPerf() { startExportLog(REQ_EXPORT_LOG_PERF, "perf") }
            override fun onExportError() { startExportLog(REQ_EXPORT_LOG_ERROR, "error") }
            override fun onExportAll() { startExportLog(REQ_EXPORT_LOG_ALL, "all") }
            override fun onClearLogs() {
                Toast.makeText(this@MainActivity, "已清空", Toast.LENGTH_SHORT).show()
                showPage(Page.LogExport)
            }
        }
    ).build()

    private fun buildResourceListPage(scrollView: ScrollView) = ResourceListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : ResourceListPageBuilder.Callbacks {
            override fun onBack() { showPage(Page.Main) }
            override fun onImportGif() { selectGif() }
            override fun onDeleteResource(resourceId: String) { deleteResource(resourceId) }
            override fun onEditResource(resourceId: String) {
                captureEditSnapshot()
                showPage(Page.ResourceEdit(resourceId))
            }
            override fun onReorderResources(fromIndex: Int, toIndex: Int) {
                config.reorderResources(fromIndex, toIndex)
                saveConfig()
                showPage(Page.ResourceList)
                LogBuffer.i("资源排序: $fromIndex -> $toIndex")
            }
        }
    ).build(scrollView)

    private fun buildResourceEditPage(resourceId: String) = ResourceEditPageBuilder(
        activity = this,
        config = config,
        resourceId = resourceId,
        callbacks = object : ResourceEditPageBuilder.Callbacks {
            override fun onBack() { handleEditBack(Page.ResourceList) }
            override fun onSaved() {
                editSnapshot = null
                if (saveConfig()) showPage(Page.ResourceList)
            }
        }
    ).build()

    private fun buildAnimationListPage(scrollView: ScrollView) = AnimationListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : AnimationListPageBuilder.Callbacks {
            override fun onBack() { showPage(Page.Main) }
            override fun onCreateAnimation() { createNewAnimation() }
            override fun onEditAnimation(animationId: String) {
                captureEditSnapshot()
                showPage(Page.AnimationEdit(animationId))
            }
            override fun onDeleteAnimation(animationId: String) { deleteAnimation(animationId) }
            override fun onReorderAnimations(fromIndex: Int, toIndex: Int) {
                config.reorderAnimations(fromIndex, toIndex)
                saveConfig()
                showPage(Page.AnimationList)
                LogBuffer.i("动画排序: $fromIndex -> $toIndex")
            }
        }
    ).build(scrollView)

    private fun buildAnimationEditPage(animationId: String, scrollView: ScrollView) = AnimationEditPageBuilder(
        activity = this,
        config = config,
        animationId = animationId,
        scrollView = scrollView,
        callbacks = object : AnimationEditPageBuilder.Callbacks {
            override fun onBack() { handleEditBack(Page.AnimationList) }
            override fun onSaved() {
                editSnapshot = null
                if (saveConfig()) showPage(Page.AnimationList)
            }
            override fun onDelete() { deleteAnimation(animationId) }
        }
    ).build()

    private fun buildStateListPage(scrollView: ScrollView) = StateListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : StateListPageBuilder.Callbacks {
            override fun onBack() { showPage(Page.Main) }
            override fun onCreateState() { createNewState() }
            override fun onEditState(stateId: String) {
                captureEditSnapshot()
                showPage(Page.StateEdit(stateId))
            }
            override fun onDeleteState(stateId: String) { deleteState(stateId) }
            override fun onSetDefault(stateId: String) {
                config.defaultStateId = stateId
                saveConfig()
                showPage(Page.StateList)
            }
            override fun onReorderStates(fromIndex: Int, toIndex: Int) {
                config.reorderStates(fromIndex, toIndex)
                saveConfig()
                showPage(Page.StateList)
                LogBuffer.i("状态排序: $fromIndex -> $toIndex")
            }
        }
    ).build(scrollView)

    private fun buildStateEditPage(page: Page.StateEdit) = StateEditPageBuilder(
        activity = this,
        config = config,
        stateId = page.stateId,
        callbacks = object : StateEditPageBuilder.Callbacks {
            override fun onBack() { handleEditBack(page.returnTo) }
            override fun onSaved() {
                editSnapshot = null
                if (saveConfig()) showPage(page.returnTo)
            }
            override fun onDelete() { deleteState(page.stateId) }
        }
    ).build()

    private fun buildEventRuleListPage() = EventRuleListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : EventRuleListPageBuilder.Callbacks {
            override fun onBack() { showPage(Page.Main) }
            override fun onCreateRule() {
                captureEditSnapshot()
                showPage(Page.EventRuleEdit(null))
            }
            override fun onEditRule(ruleId: String) {
                captureEditSnapshot()
                showPage(Page.EventRuleEdit(ruleId))
            }
            override fun onDeleteRule(ruleId: String) { deleteEventRule(ruleId) }
        }
    ).build()

    private fun buildEventRuleEditPage(page: Page.EventRuleEdit) = EventRuleEditPageBuilder(
        activity = this,
        config = config,
        ruleId = page.ruleId,
        callbacks = object : EventRuleEditPageBuilder.Callbacks {
            override fun onBack() { handleEditBack(page.returnTo) }
            override fun onSaved() {
                editSnapshot = null
                if (saveConfig()) showPage(page.returnTo)
            }
            override fun onDelete() {
                page.ruleId?.let { deleteEventRule(it) }
            }
        }
    ).build()

    private fun buildStateGraphPage() = StateGraphPageBuilder(
        activity = this,
        config = config,
        callbacks = object : StateGraphPageBuilder.Callbacks {
            override fun onBack() { showPage(Page.Main) }
            override fun onNodeDoubleTap(stateId: String) {
                captureEditSnapshot()
                showPage(Page.StateEdit(stateId, returnTo = Page.StateGraph))
            }
            override fun onEventDoubleTap(ruleId: String) {
                captureEditSnapshot()
                showPage(Page.EventRuleEdit(ruleId, returnTo = Page.StateGraph))
            }
        }
    ).build()

    // ==================== 日志导出 ====================

    private fun startExportLog(requestCode: Int, category: String) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "gifwp_log_${category}_$ts.txt")
        }
        startActivityForResult(intent, requestCode)
    }

    // ==================== 数据备份：导出入口 ====================

    private fun startExportJson() {
        saveConfig()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "gif_wallpaper_config.json")
        }
        startActivityForResult(intent, REQ_EXPORT_JSON)
    }

    private fun startExportZip() {
        saveConfig()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "gif_wallpaper_backup.zip")
        }
        startActivityForResult(intent, REQ_EXPORT_ZIP)
    }

    // ==================== 数据备份：导入入口 ====================

    private fun confirmImport(title: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("导入将覆盖当前的全部配置（以及压缩包中的资源）。\n若导入失败，当前数据保持不变。是否继续？")
            .setPositiveButton("继续") { _, _ -> action() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startImportJson() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQ_IMPORT_JSON)
    }

    private fun startImportZip() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT_ZIP)
    }

    private fun importJsonFrom(uri: Uri) {
        when (val r = ConfigBackupManager.readJsonForImport(this, uri)) {
            is ConfigBackupManager.ImportPreview.Failure -> {
                Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
            }
            is ConfigBackupManager.ImportPreview.Ok -> {
                if (r.issues.isEmpty()) {
                    doApplyJsonImport(r.text)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("导入会丢弃以下内容")
                        .setMessage(r.issues.joinToString("\n\n") + "\n\n是否继续导入？")
                        .setPositiveButton("继续导入") { _, _ -> doApplyJsonImport(r.text) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    private fun doApplyJsonImport(text: String) {
        val err = ConfigBackupManager.applyJsonImport(this, text)
        if (err != null) {
            Toast.makeText(this, "导入失败: $err\n当前数据未改变", Toast.LENGTH_LONG).show()
            return
        }
        editSnapshot = null
        loadConfig()
        saveConfig()
        showPage(Page.Main)
        Toast.makeText(this, "JSON 导入成功", Toast.LENGTH_SHORT).show()
    }

    private fun doImportZip(uri: Uri) {
        val err = ConfigBackupManager.importZip(this, uri)
        if (err != null) {
            Toast.makeText(this, "导入失败: $err\n当前数据未改变", Toast.LENGTH_LONG).show()
            return
        }
        editSnapshot = null
        loadConfig()
        saveConfig()
        showPage(Page.Main)
        Toast.makeText(this, "压缩包导入成功", Toast.LENGTH_SHORT).show()
    }

    // ==================== ActivityResult 分发 ====================

    private fun selectGif() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("image/gif")
        }
        startActivityForResult(intent, REQ_IMPORT_GIF)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQ_IMPORT_GIF -> handleImportGif(uri)
            REQ_EXPORT_JSON -> handleExportResult(ConfigBackupManager.exportJson(this, uri))
            REQ_EXPORT_ZIP -> handleExportResult(ConfigBackupManager.exportZip(this, uri))
            REQ_IMPORT_JSON -> importJsonFrom(uri)
            REQ_IMPORT_ZIP -> doImportZip(uri)
            REQ_EXPORT_LOG_GENERAL ->
                handleExportResult(ConfigBackupManager.exportLog(this, uri, "general"), isLog = true)
            REQ_EXPORT_LOG_PERF ->
                handleExportResult(ConfigBackupManager.exportLog(this, uri, "perf"), isLog = true)
            REQ_EXPORT_LOG_ERROR ->
                handleExportResult(ConfigBackupManager.exportLog(this, uri, "error"), isLog = true)
            REQ_EXPORT_LOG_ALL ->
                handleExportResult(ConfigBackupManager.exportLog(this, uri, "all"), isLog = true)
        }
    }

    private fun handleExportResult(err: String?, isLog: Boolean = false) {
        if (err == null) {
            val msg = if (isLog) "日志已导出" else "导出成功"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "导出失败: $err", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImportGif(uri: Uri) {
        when (val r = ConfigBackupManager.importGif(this, uri)) {
            is ConfigBackupManager.ImportGifResult.Failure -> {
                Toast.makeText(this, "导入失败: ${r.message}", Toast.LENGTH_SHORT).show()
            }
            is ConfigBackupManager.ImportGifResult.Ok -> {
                loadConfig()
                saveConfig()
                showPage(Page.ResourceList)
                Toast.makeText(this, "导入成功: ${r.resourceId}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 资源删除 ====================

    private fun deleteResource(resourceId: String) {
        val usage = ConfigOperations.getResourceUsage(config, resourceId) ?: return
        val resource = config.resources[resourceId] ?: return

        val message = if (usage.isEmpty()) {
            "确定删除资源「${resource.id}」？"
        } else {
            val names = usage.joinToString("、") { formatAnimDisplay(it) }
            "以下动画正在使用此资源：$names\n删除后这些引用会被移除。确定删除？"
        }
        AlertDialog.Builder(this)
            .setTitle("删除资源")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                ConfigOperations.deleteResourceFile(config, resourceId, ProjectConfigStore.animDir(this))
                loadConfig()
                saveConfig()
                showPage(Page.ResourceList)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                LogBuffer.i("删除资源: $resourceId")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 动画 / 状态 / 事件规则 ====================

    private fun createNewAnimation() {
        val id = ConfigOperations.createNewAnimation(config)
        saveConfig()
        captureEditSnapshot()
        showPage(Page.AnimationEdit(id))
    }

    private fun deleteAnimation(animationId: String) {
        val usedBy = ConfigOperations.getAnimationUsage(config, animationId) ?: return
        if (usedBy.isNotEmpty()) {
            val names = usedBy.joinToString("、") { formatStateDisplay(it) }
            AlertDialog.Builder(this)
                .setTitle("无法删除")
                .setMessage("以下状态正在引用此动画：$names\n请先修改这些状态引用的动画。")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        val anim = config.animations[animationId] ?: return
        AlertDialog.Builder(this)
            .setTitle("删除动画")
            .setMessage("确定删除「${formatAnimDisplay(anim)}」？")
            .setPositiveButton("删除") { _, _ ->
                ConfigOperations.deleteAnimation(config, animationId)
                saveConfig()
                showPage(Page.AnimationList)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createNewState() {
        val id = ConfigOperations.createNewState(config)
        saveConfig()
        captureEditSnapshot()
        showPage(Page.StateEdit(id))
    }

    private fun deleteState(stateId: String) {
        val state = config.states[stateId]
        val display = if (state != null) formatStateDisplay(state) else stateId
        AlertDialog.Builder(this)
            .setTitle("删除状态")
            .setMessage("确定删除状态「$display」？\n指向它的转移规则与事件规则会被清理。")
            .setPositiveButton("删除") { _, _ ->
                ConfigOperations.deleteState(config, stateId)
                saveConfig()
                showPage(Page.StateList)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDefaultStateDialog() {
        if (config.states.isEmpty()) {
            Toast.makeText(this, "还没有任何状态", Toast.LENGTH_SHORT).show()
            return
        }
        val ids = config.states.keys.toTypedArray()
        val displays = ids.map { formatStateDisplay(config.states[it]) }.toTypedArray()
        val currentIdx = ids.indexOf(config.defaultStateId).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择默认状态")
            .setSingleChoiceItems(displays, currentIdx) { dialog, which ->
                config.defaultStateId = ids[which]
                saveConfig()
                dialog.dismiss()
                showPage(Page.Main)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteEventRule(ruleId: String) {
        AlertDialog.Builder(this)
            .setTitle("删除事件规则")
            .setMessage("确定删除这条规则？")
            .setPositiveButton("删除") { _, _ ->
                ConfigOperations.deleteEventRule(config, ruleId)
                saveConfig()
                showPage(Page.EventRuleList)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 壁纸操作 ====================

    private fun applyWallpaper() {
        saveConfig()
        if (!WallpaperController.applyLiveWallpaper(this)) {
            Toast.makeText(this, "系统不支持动态壁纸设置", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmRestoreSystemWallpaper() {
        AlertDialog.Builder(this)
            .setTitle("恢复系统默认壁纸")
            .setMessage(
                "将清除动态壁纸，回到手机默认壁纸。\n" +
                    "所有配置参数会保留。\n\n" +
                    "注意：部分系统（如 MIUI、EMUI）可能需要在系统设置中手动更换壁纸。"
            )
            .setPositiveButton("恢复") { _, _ ->
                val err = WallpaperController.restoreSystemWallpaper(this)
                if (err != null) {
                    Toast.makeText(
                        this,
                        "恢复失败: $err\n请在系统设置中更换壁纸",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "已恢复系统默认壁纸", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}