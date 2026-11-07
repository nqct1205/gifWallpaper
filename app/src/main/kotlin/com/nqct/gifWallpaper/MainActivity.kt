package com.nqct.gifWallpaper

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Window
import android.window.OnBackInvokedDispatcher
import android.widget.ScrollView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// 应用入口 Activity。
class MainActivity : Activity() {

    private var config = ProjectConfig()
    private var lastWarning: String? = null
    private var currentPage: Page = Page.Main

    private sealed class Page {
        object Main : Page()
        object ResourceList : Page()
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

        private const val ZIP_ENTRY_CONFIG = ProjectConfigStore.CONFIG_FILE
        private const val ZIP_ENTRY_ANIM_DIR = ProjectConfigStore.ANIM_DIR
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

    private fun handleBackNavigation() {
        val cur = currentPage
        val back: Page? = when (cur) {
            is Page.Main -> null
            is Page.StateEdit -> cur.returnTo
            is Page.EventRuleEdit -> cur.returnTo
            is Page.LogExport -> Page.Main
            else -> Page.Main
        }
        if (back == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                finish()
            } else {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        } else {
            if (cur is Page.StateEdit || cur is Page.EventRuleEdit) {
                saveConfig()
            }
            showPage(back)
        }
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
        val ok = ProjectConfigStore.save(this, config)
        if (!ok) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            LogBuffer.e("配置保存失败")
        }
        return ok
    }

    // ==================== 页面路由 ====================

    private fun showPage(page: Page) {
        currentPage = page

        // 状态图：全屏，不包 ScrollView
        if (page is Page.StateGraph) {
            setContentView(buildStateGraphPage())
            return
        }

        val scrollView = ScrollView(this)
        val content: android.view.View = when (page) {
            is Page.Main -> buildMainPage()
            is Page.ResourceList -> buildResourceListPage(scrollView)
            is Page.AnimationList -> buildAnimationListPage(scrollView)
            is Page.AnimationEdit -> buildAnimationEditPage(page.animationId)
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
            override fun onImportJson() { confirmImport("导入 JSON") { startImportJson() } }
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
            override fun onReorderResources(fromIndex: Int, toIndex: Int) {
                config.reorderResources(fromIndex, toIndex)
                saveConfig()
                showPage(Page.ResourceList)
                LogBuffer.i("资源排序: $fromIndex -> $toIndex")
            }
        }
    ).build(scrollView)

    private fun buildAnimationListPage(scrollView: ScrollView) = AnimationListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : AnimationListPageBuilder.Callbacks {
            override fun onBack() { showPage(Page.Main) }
            override fun onCreateAnimation() { createNewAnimation() }
            override fun onEditAnimation(animationId: String) { showPage(Page.AnimationEdit(animationId)) }
            override fun onDeleteAnimation(animationId: String) { deleteAnimation(animationId) }
            override fun onReorderAnimations(fromIndex: Int, toIndex: Int) {
                config.reorderAnimations(fromIndex, toIndex)
                saveConfig()
                showPage(Page.AnimationList)
                LogBuffer.i("动画排序: $fromIndex -> $toIndex")
            }
        }
    ).build(scrollView)

    private fun buildStateListPage(scrollView: ScrollView) = StateListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : StateListPageBuilder.Callbacks {
            override fun onBack() { showPage(Page.Main) }
            override fun onCreateState() { createNewState() }
            override fun onEditState(stateId: String) { showPage(Page.StateEdit(stateId)) }
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

    private fun buildAnimationEditPage(animationId: String) = AnimationEditPageBuilder(
        activity = this,
        config = config,
        animationId = animationId,
        callbacks = object : AnimationEditPageBuilder.Callbacks {
            override fun onBack() {
                saveConfig()
                showPage(Page.AnimationList)
            }
            override fun onSaved() {
                if (saveConfig()) showPage(Page.AnimationList)
            }
            override fun onDelete() { deleteAnimation(animationId) }
        }
    ).build()

    private fun buildStateEditPage(page: Page.StateEdit) = StateEditPageBuilder(
        activity = this,
        config = config,
        stateId = page.stateId,
        callbacks = object : StateEditPageBuilder.Callbacks {
            override fun onBack() {
                saveConfig()
                showPage(page.returnTo)
            }
            override fun onSaved() {
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
            override fun onCreateRule() { showPage(Page.EventRuleEdit(null)) }
            override fun onEditRule(ruleId: String) { showPage(Page.EventRuleEdit(ruleId)) }
            override fun onDeleteRule(ruleId: String) { deleteEventRule(ruleId) }
        }
    ).build()

    private fun buildEventRuleEditPage(page: Page.EventRuleEdit) = EventRuleEditPageBuilder(
        activity = this,
        config = config,
        ruleId = page.ruleId,
        callbacks = object : EventRuleEditPageBuilder.Callbacks {
            override fun onBack() {
                saveConfig()
                showPage(page.returnTo)
            }
            override fun onSaved() {
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
                showPage(Page.StateEdit(stateId, returnTo = Page.StateGraph))
            }
            override fun onEventDoubleTap(ruleId: String) {
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

    private fun exportLogTo(uri: Uri, category: String) {
        try {
            val content = when (category) {
                "general" -> LogBuffer.dumpGeneral()
                "perf" -> LogBuffer.dumpPerf()
                "error" -> LogBuffer.dumpError()
                else -> LogBuffer.dumpAll()
            }
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法写入目标文件")
            Toast.makeText(this, "日志已导出", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            LogBuffer.e("日志导出失败", e)
        }
    }

    // ==================== 数据备份：导出 ====================

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

    private fun exportJsonTo(uri: Uri) {
        try {
            val src = ProjectConfigStore.configFile(this)
            if (!src.exists()) throw IllegalStateException("配置文件不存在")
            contentResolver.openOutputStream(uri)?.use { output ->
                src.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法写入目标文件")
            Toast.makeText(this, "JSON 导出成功", Toast.LENGTH_SHORT).show()
            LogBuffer.i("JSON 导出成功")
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            LogBuffer.e("JSON 导出失败", e)
        }
    }

    private fun exportZipTo(uri: Uri) {
        try {
            val configFile = ProjectConfigStore.configFile(this)
            if (!configFile.exists()) throw IllegalStateException("配置文件不存在")

            contentResolver.openOutputStream(uri)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(ZIP_ENTRY_CONFIG))
                    configFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    val animDir = ProjectConfigStore.animDir(this)
                    val gifs = animDir.listFiles { f -> f.extension.lowercase() == "gif" } ?: emptyArray()
                    for (gif in gifs) {
                        zip.putNextEntry(ZipEntry("$ZIP_ENTRY_ANIM_DIR/${gif.name}"))
                        gif.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalStateException("无法写入目标文件")
            Toast.makeText(this, "压缩包导出成功", Toast.LENGTH_SHORT).show()
            LogBuffer.i("压缩包导出成功")
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            LogBuffer.e("压缩包导出失败", e)
        }
    }

    // ==================== 数据备份：导入 ====================

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
        val configFile = ProjectConfigStore.configFile(this)
        val oldText: String? = try {
            if (configFile.exists()) configFile.readText() else null
        } catch (e: Exception) { null }

        try {
            val text = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("无法读取文件")

            ProjectConfigStore.parseOnly(text)
                ?: throw IllegalArgumentException("JSON 格式错误或内容为空")

            try {
                configFile.writeText(text)
            } catch (e: Exception) {
                try {
                    if (oldText != null) configFile.writeText(oldText) else configFile.delete()
                } catch (_: Exception) {}
                throw e
            }

            loadConfig()
            saveConfig()
            showPage(Page.Main)
            Toast.makeText(this, "JSON 导入成功", Toast.LENGTH_SHORT).show()
            LogBuffer.i("JSON 导入成功")
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}\n当前数据未改变", Toast.LENGTH_LONG).show()
            LogBuffer.e("JSON 导入失败", e)
        }
    }

    private fun importZipFrom(uri: Uri) {
        val tmpRoot = File(filesDir, "import_tmp")
        val backupRoot = File(filesDir, "import_backup")
        val configFile = ProjectConfigStore.configFile(this)
        val animDir = ProjectConfigStore.animDir(this)

        try {
            tmpRoot.deleteRecursively()
            backupRoot.deleteRecursively()
            if (!tmpRoot.mkdirs()) throw IllegalStateException("无法创建临时目录")

            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    val canonicalRoot = tmpRoot.canonicalPath + File.separator
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val target = File(tmpRoot, name)
                        if (name.contains("..") ||
                            !target.canonicalPath.startsWith(canonicalRoot)) {
                            throw IllegalArgumentException("压缩包包含非法路径: $name")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: throw IllegalStateException("无法读取压缩包")

            val tmpConfig = File(tmpRoot, ZIP_ENTRY_CONFIG)
            if (!tmpConfig.exists()) throw IllegalArgumentException("压缩包内缺少 project.json")
            val tmpText = tmpConfig.readText()
            val parsed = ProjectConfigStore.parseOnly(tmpText)
                ?: throw IllegalArgumentException("project.json 格式错误")

            val tmpAnimDir = File(tmpRoot, ZIP_ENTRY_ANIM_DIR)
            val missing = mutableListOf<String>()
            for (anim in parsed.animations.values) {
                for (ref in anim.resources) {
                    val f = File(tmpAnimDir, "${ref.resourceId}.gif")
                    if (!f.exists() || f.length() == 0L) {
                        missing.add("${ref.resourceId}.gif")
                    }
                }
            }
            if (missing.isNotEmpty()) {
                throw IllegalArgumentException("压缩包缺少资源: ${missing.distinct().joinToString("、")}")
            }

            backupRoot.mkdirs()
            if (configFile.exists()) {
                configFile.copyTo(File(backupRoot, ZIP_ENTRY_CONFIG), overwrite = true)
            }
            val backupAnim = File(backupRoot, ZIP_ENTRY_ANIM_DIR)
            backupAnim.mkdirs()
            animDir.listFiles()?.forEach { old ->
                old.copyTo(File(backupAnim, old.name), overwrite = true)
            }

            try {
                configFile.writeText(tmpText)
                animDir.listFiles()?.forEach { it.delete() }
                tmpAnimDir.listFiles { f -> f.extension.lowercase() == "gif" }?.forEach { gif ->
                    gif.copyTo(File(animDir, gif.name), overwrite = true)
                }
            } catch (e: Exception) {
                try {
                    val backupConfig = File(backupRoot, ZIP_ENTRY_CONFIG)
                    if (backupConfig.exists()) {
                        backupConfig.copyTo(configFile, overwrite = true)
                    } else {
                        configFile.delete()
                    }
                    animDir.listFiles()?.forEach { it.delete() }
                    backupAnim.listFiles()?.forEach { old ->
                        old.copyTo(File(animDir, old.name), overwrite = true)
                    }
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "导入过程中断，回滚可能不完整，请检查配置",
                        Toast.LENGTH_LONG
                    ).show()
                }
                throw e
            }

            loadConfig()
            saveConfig()
            showPage(Page.Main)
            Toast.makeText(this, "压缩包导入成功", Toast.LENGTH_SHORT).show()
            LogBuffer.i("压缩包导入成功")
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}\n当前数据未改变", Toast.LENGTH_LONG).show()
            LogBuffer.e("压缩包导入失败", e)
        } finally {
            tmpRoot.deleteRecursively()
            backupRoot.deleteRecursively()
        }
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
            REQ_IMPORT_GIF -> importGif(uri)
            REQ_EXPORT_JSON -> exportJsonTo(uri)
            REQ_EXPORT_ZIP -> exportZipTo(uri)
            REQ_IMPORT_JSON -> importJsonFrom(uri)
            REQ_IMPORT_ZIP -> importZipFrom(uri)
            REQ_EXPORT_LOG_GENERAL -> exportLogTo(uri, "general")
            REQ_EXPORT_LOG_PERF -> exportLogTo(uri, "perf")
            REQ_EXPORT_LOG_ERROR -> exportLogTo(uri, "error")
            REQ_EXPORT_LOG_ALL -> exportLogTo(uri, "all")
        }
    }

    // ==================== 资源操作 ====================

    private fun importGif(uri: Uri) {
        try {
            var displayName = "anim_${System.currentTimeMillis()}.gif"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) {
                            displayName = if (name.lowercase().endsWith(".gif")) name else "$name.gif"
                        }
                    }
                }
            }
            val base = displayName.substringBeforeLast('.', displayName)
            val safeBase = sanitizeFileName(base)

            val dir = ProjectConfigStore.animDir(this)
            var target = File(dir, "$safeBase.gif")
            var counter = 1
            while (target.exists()) {
                target = File(dir, "${safeBase}_$counter.gif")
                counter++
            }

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取所选文件")

            loadConfig()
            saveConfig()
            showPage(Page.ResourceList)
            Toast.makeText(this, "导入成功: ${target.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
            LogBuffer.i("GIF 导入: ${target.nameWithoutExtension}")
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            LogBuffer.e("GIF 导入失败", e)
        }
    }

    private fun deleteResource(resourceId: String) {
        val resource = config.resources[resourceId] ?: return
        val usingAnims = config.animations.values.filter { anim ->
            anim.resources.any { it.resourceId == resourceId }
        }
        val message = if (usingAnims.isEmpty()) {
            "确定删除资源「${resource.id}」？"
        } else {
            val names = usingAnims.joinToString("、") { formatAnimDisplay(it) }
            "以下动画正在使用此资源：$names\n删除后这些引用会被移除。确定删除？"
        }
        AlertDialog.Builder(this)
            .setTitle("删除资源")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                File(ProjectConfigStore.animDir(this), resource.fileName).delete()
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
        var idx = 1
        while (config.animations.containsKey("anim_$idx")) idx++
        val id = "anim_$idx"
        config.animations[id] = AnimationItem(id = id, name = "动画 $idx")
        saveConfig()
        showPage(Page.AnimationEdit(id))
    }

    private fun deleteAnimation(animationId: String) {
        val usedBy = config.states.values.filter { it.animationId == animationId }
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
                config.animations.remove(animationId)
                saveConfig()
                showPage(Page.AnimationList)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createNewState() {
        var idx = 1
        while (config.states.containsKey("state_$idx")) idx++
        val id = "state_$idx"
        config.states[id] = StateNode(id = id, name = "状态 $idx")
        if (config.defaultStateId.isEmpty()) config.defaultStateId = id
        saveConfig()
        showPage(Page.StateEdit(id))
    }

    private fun deleteState(stateId: String) {
        val state = config.states[stateId]
        val display = if (state != null) formatStateDisplay(state) else stateId
        AlertDialog.Builder(this)
            .setTitle("删除状态")
            .setMessage("确定删除状态「$display」？\n指向它的转移规则与事件规则会被清理。")
            .setPositiveButton("删除") { _, _ ->
                config.states.remove(stateId)
                for (s in config.states.values) {
                    s.rules.removeAll { it.targetStateId == stateId }
                }
                config.eventRules.removeAll { it.targetStateId == stateId }
                for (r in config.eventRules) {
                    r.sourceStateIds.removeAll { it == stateId }
                }
                if (config.defaultStateId == stateId) {
                    config.defaultStateId = config.states.keys.firstOrNull() ?: ""
                }
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
        // 顺序跟随用户排序
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
                config.eventRules.removeAll { it.id == ruleId }
                saveConfig()
                showPage(Page.EventRuleList)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 壁纸操作 ====================

    private fun applyWallpaper() {
        saveConfig()
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, GifWallpaperService::class.java)
                )
            }
            startActivity(intent)
            LogBuffer.i("应用动态壁纸")
        } catch (e: Exception) {
            Toast.makeText(this, "系统不支持动态壁纸设置", Toast.LENGTH_LONG).show()
            LogBuffer.e("应用动态壁纸失败", e)
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
                try {
                    val wm = WallpaperManager.getInstance(this)
                    wm.clear()
                    Toast.makeText(this, "已恢复系统默认壁纸", Toast.LENGTH_SHORT).show()
                    LogBuffer.i("恢复系统默认壁纸")
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "恢复失败: ${e.message}\n请在系统设置中更换壁纸",
                        Toast.LENGTH_LONG
                    ).show()
                    LogBuffer.e("恢复系统默认壁纸失败", e)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}