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

/**
 * 应用入口 Activity。
 *
 * 【职责】
 *  - 持有唯一 [ProjectConfig] 实例。
 *  - 页面路由（[Page] sealed class + [showPage]）。
 *  - 保存配置（[saveConfig]）。
 *  - 系统级操作（导入 GIF、应用壁纸）。
 *
 * 【返回来源】部分编辑页（状态 / 事件规则）可能从不同入口进入，
 * 通过 [Page.StateEdit.returnTo] / [Page.EventRuleEdit.returnTo] 记录来源页，
 * 页内“< 返回”与系统返回键均回到该来源。
 */
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

        /**
         * 状态编辑页。
         * @param returnTo 返回目标。默认回到状态列表；从状态图双击进入时为 [StateGraph]。
         */
        data class StateEdit(
            val stateId: String,
            val returnTo: Page = StateList
        ) : Page()

        object EventRuleList : Page()

        /**
         * 事件规则编辑页。
         * @param ruleId   null 表示新建。
         * @param returnTo 返回目标。默认回到事件规则列表；从状态图双击进入时为 [StateGraph]。
         */
        data class EventRuleEdit(
            val ruleId: String?,
            val returnTo: Page = EventRuleList
        ) : Page()

        object StateGraph : Page()
    }

    companion object {
        private const val REQ_IMPORT_GIF = 200
    }

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        registerBackHandler()
        loadConfig()
        showPage(Page.Main)
    }

    /**
     * 分版本注册返回键处理。
     *
     * - API 33+：通过 [OnBackInvokedDispatcher] 注册回调，替代已废弃的 onBackPressed。
     * - API 26–32：走 [onBackPressed]（仍需保留）。
     */
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

    /**
     * API 26–32 的返回键入口。
     * API 33+ 已由 [registerBackHandler] 注册的 OnBackInvokedCallback 接管。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            handleBackNavigation()
        }
    }

    /**
     * 统一的返回导航处理。
     *
     * - 主页面：退出 Activity（API 33+ 用 finish；低版本调 super.onBackPressed）。
     * - 状态 / 事件规则编辑页：回到 [Page.StateEdit.returnTo] / [Page.EventRuleEdit.returnTo]，
     *   离开前先 [saveConfig]，与页内“< 返回”按钮一致。
     * - 其他页面：回主页面。
     */
    private fun handleBackNavigation() {
        val cur = currentPage
        val back: Page? = when (cur) {
            is Page.Main -> null
            is Page.StateEdit -> cur.returnTo
            is Page.EventRuleEdit -> cur.returnTo
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
            }
        }
    }

    private fun saveConfig(): Boolean {
        val ok = ProjectConfigStore.save(this, config)
        if (!ok) Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        return ok
    }

    // ==================== 页面路由 ====================

    private fun showPage(page: Page) {
        currentPage = page
        val content = when (page) {
            is Page.Main -> buildMainPage()
            is Page.ResourceList -> buildResourceListPage()
            is Page.AnimationList -> buildAnimationListPage()
            is Page.AnimationEdit -> buildAnimationEditPage(page.animationId)
            is Page.StateList -> buildStateListPage()
            is Page.StateEdit -> buildStateEditPage(page)
            is Page.EventRuleList -> buildEventRuleListPage()
            is Page.EventRuleEdit -> buildEventRuleEditPage(page)
            is Page.StateGraph -> buildStateGraphPage()
        }
        // 状态图页面自带拖动/缩放，必须全屏且不能包 ScrollView（否则手势冲突）
        if (page is Page.StateGraph) {
            setContentView(content)
        } else {
            setContentView(ScrollView(this).apply { addView(content) })
        }
    }

    private fun buildMainPage() = MainPageBuilder(
        activity = this,
        config = config,
        warningMessage = lastWarning,
        callbacks = object : MainPageBuilder.Callbacks {
            override fun onOpenResources() = showPage(Page.ResourceList)
            override fun onOpenAnimations() = showPage(Page.AnimationList)
            override fun onOpenStates() = showPage(Page.StateList)
            override fun onOpenEventRules() = showPage(Page.EventRuleList)
            override fun onOpenStateGraph() = showPage(Page.StateGraph)
            override fun onSetDefaultState() = showDefaultStateDialog()
            override fun onApplyWallpaper() = applyWallpaper()
        }
    ).build()

    private fun buildResourceListPage() = ResourceListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : ResourceListPageBuilder.Callbacks {
            override fun onBack() = showPage(Page.Main)
            override fun onImportGif() = selectGif()
            override fun onDeleteResource(resourceId: String) = deleteResource(resourceId)
        }
    ).build()

    private fun buildAnimationListPage() = AnimationListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : AnimationListPageBuilder.Callbacks {
            override fun onBack() = showPage(Page.Main)
            override fun onCreateAnimation() = createNewAnimation()
            override fun onEditAnimation(animationId: String) = showPage(Page.AnimationEdit(animationId))
            override fun onDeleteAnimation(animationId: String) = deleteAnimation(animationId)
        }
    ).build()

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
            override fun onDelete() = deleteAnimation(animationId)
        }
    ).build()

    private fun buildStateListPage() = StateListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : StateListPageBuilder.Callbacks {
            override fun onBack() = showPage(Page.Main)
            override fun onCreateState() = createNewState()
            override fun onEditState(stateId: String) = showPage(Page.StateEdit(stateId))
            override fun onDeleteState(stateId: String) = deleteState(stateId)
            override fun onSetDefault(stateId: String) {
                config.defaultStateId = stateId
                saveConfig()
                showPage(Page.StateList)
            }
        }
    ).build()

    /**
     * 状态编辑页。
     * 返回目标由 [Page.StateEdit.returnTo] 决定：状态列表 或 状态图。
     */
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
            // 删除状态后统一回到状态列表：状态图可能因删除后变空而不适合作为返回目标
            override fun onDelete() = deleteState(page.stateId)
        }
    ).build()

    private fun buildEventRuleListPage() = EventRuleListPageBuilder(
        activity = this,
        config = config,
        callbacks = object : EventRuleListPageBuilder.Callbacks {
            override fun onBack() = showPage(Page.Main)
            override fun onCreateRule() = showPage(Page.EventRuleEdit(null))
            override fun onEditRule(ruleId: String) = showPage(Page.EventRuleEdit(ruleId))
            override fun onDeleteRule(ruleId: String) = deleteEventRule(ruleId)
        }
    ).build()

    /**
     * 事件规则编辑页。
     * 返回目标由 [Page.EventRuleEdit.returnTo] 决定：事件规则列表 或 状态图。
     */
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

    /**
     * 状态图页面。
     * 双击节点 / 事件框进入对应编辑页时，[returnTo] 设为 [Page.StateGraph]，
     * 编辑完成后返回状态图而非列表页。
     */
    private fun buildStateGraphPage() = StateGraphPageBuilder(
        activity = this,
        config = config,
        callbacks = object : StateGraphPageBuilder.Callbacks {
            override fun onBack() = showPage(Page.Main)
            override fun onNodeDoubleTap(stateId: String) {
                showPage(Page.StateEdit(stateId, returnTo = Page.StateGraph))
            }
            override fun onEventDoubleTap(ruleId: String) {
                showPage(Page.EventRuleEdit(ruleId, returnTo = Page.StateGraph))
            }
        }
    ).build()

    // ==================== 资源操作 ====================

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
        if (requestCode == REQ_IMPORT_GIF) importGif(uri)
    }

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
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 动画操作 ====================

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

    // ==================== 状态操作 ====================

    private fun createNewState() {
        var idx = 1
        while (config.states.containsKey("state_$idx")) idx++
        val id = "state_$idx"
        config.states[id] = StateNode(id = id, name = "状态 $idx")
        if (config.defaultStateId.isEmpty()) config.defaultStateId = id
        saveConfig()
        showPage(Page.StateEdit(id))
    }

    /**
     * 删除状态后统一回到状态列表。
     * 不回到 [Page.StateGraph]，因为删除后状态图可能为空，体验不佳。
     */
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
        val ids = config.states.keys.sorted().toTypedArray()
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

    // ==================== 事件规则操作 ====================

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

    // ==================== 应用壁纸 ====================

    private fun applyWallpaper() {
        saveConfig()
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    // apply 块内 this 是 Intent，必须显式指定 Activity 实例
                    ComponentName(this@MainActivity, GifWallpaperService::class.java)
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "系统不支持动态壁纸设置", Toast.LENGTH_LONG).show()
        }
    }
}