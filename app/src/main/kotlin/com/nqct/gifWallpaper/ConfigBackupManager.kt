package com.nqct.gifWallpaper

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// 数据备份：JSON / ZIP 导入导出、日志导出、GIF 资源导入。
//
// 【职责边界】只负责 IO 与数据完整性校验，不负责弹窗/Toast/页面跳转。
// 调用方（MainActivity）负责 UI 交互与状态刷新。
//
// 【返回值约定】导出类返回 null 表示成功，非 null 为错误信息；
// 导入类返回结果对象或 null/错误信息，由调用方决定提示与刷新。
object ConfigBackupManager {

    private const val ZIP_ENTRY_CONFIG = ProjectConfigStore.CONFIG_FILE
    private const val ZIP_ENTRY_ANIM_DIR = ProjectConfigStore.ANIM_DIR

    // ==================== 导出 ====================

    // 返回 null 表示成功，否则为错误信息。
    fun exportJson(context: Context, uri: Uri): String? {
        return try {
            val src = ProjectConfigStore.configFile(context)
            if (!src.exists()) return "配置文件不存在"
            context.contentResolver.openOutputStream(uri)?.use { output ->
                src.inputStream().use { it.copyTo(output) }
            } ?: return "无法写入目标文件"
            LogBuffer.i("JSON 导出成功")
            null
        } catch (e: Exception) {
            LogBuffer.e("JSON 导出失败", e)
            e.message ?: "未知错误"
        }
    }

    fun exportZip(context: Context, uri: Uri): String? {
        return try {
            val configFile = ProjectConfigStore.configFile(context)
            if (!configFile.exists()) return "配置文件不存在"

            context.contentResolver.openOutputStream(uri)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(ZIP_ENTRY_CONFIG))
                    configFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    val animDir = ProjectConfigStore.animDir(context)
                    val gifs = animDir.listFiles { f -> f.extension.lowercase() == "gif" } ?: emptyArray()
                    for (gif in gifs) {
                        zip.putNextEntry(ZipEntry("$ZIP_ENTRY_ANIM_DIR/${gif.name}"))
                        gif.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: return "无法写入目标文件"
            LogBuffer.i("压缩包导出成功")
            null
        } catch (e: Exception) {
            LogBuffer.e("压缩包导出失败", e)
            e.message ?: "未知错误"
        }
    }

    fun exportLog(context: Context, uri: Uri, category: String): String? {
        return try {
            val content = when (category) {
                "general" -> LogBuffer.dumpGeneral()
                "perf" -> LogBuffer.dumpPerf()
                "error" -> LogBuffer.dumpError()
                else -> LogBuffer.dumpAll()
            }
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: return "无法写入目标文件"
            null
        } catch (e: Exception) {
            LogBuffer.e("日志导出失败", e)
            e.message ?: "未知错误"
        }
    }

    // ==================== JSON 导入 ====================

    sealed class ImportPreview {
        // 读取 + 解析成功。issues 非空表示导入会丢失这些内容，调用方应弹窗确认。
        data class Ok(val text: String, val issues: List<String>) : ImportPreview()
        data class Failure(val message: String) : ImportPreview()
    }

    // 读取文件并做预校验，不写盘。调用方拿到 Ok 后再决定是否 applyJsonImport。
    fun readJsonForImport(context: Context, uri: Uri): ImportPreview {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            return ImportPreview.Failure("读取失败: ${e.message}")
        } ?: return ImportPreview.Failure("无法读取文件")

        val parsed = ProjectConfigStore.parseOnly(text)
            ?: return ImportPreview.Failure("JSON 格式错误或内容为空")

        val issues = previewImportChanges(context, parsed)
        return ImportPreview.Ok(text, issues)
    }

    // 写入配置文件并回滚失败。返回 null 表示成功。
    fun applyJsonImport(context: Context, text: String): String? {
        val configFile = ProjectConfigStore.configFile(context)
        val oldText: String? = try {
            if (configFile.exists()) configFile.readText() else null
        } catch (e: Exception) { null }

        return try {
            try {
                configFile.writeText(text)
            } catch (e: Exception) {
                try {
                    if (oldText != null) configFile.writeText(oldText) else configFile.delete()
                } catch (_: Exception) {}
                throw e
            }
            LogBuffer.i("JSON 导入成功")
            null
        } catch (e: Exception) {
            LogBuffer.e("JSON 导入失败", e)
            e.message ?: "未知错误"
        }
    }

    // 预校验：列出"导入后会丢失"的内容。逻辑与原 MainActivity.previewImportChanges 一致。
    private fun previewImportChanges(context: Context, parsed: ProjectConfig): List<String> {
        val issues = mutableListOf<String>()
        val diskResIds = try {
            ProjectConfigStore.animDir(context).listFiles()
                ?.filter { it.extension.lowercase() == "gif" }
                ?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

        val missingRes = parsed.resources.keys.filter { it !in diskResIds }
        if (missingRes.isNotEmpty()) {
            val preview = missingRes.take(3).joinToString("、")
            val suffix = if (missingRes.size > 3) " 等" else ""
            issues.add("• ${missingRes.size} 个资源文件在磁盘上不存在，将被移除: $preview$suffix")
        }

        var animRefCount = 0
        for (anim in parsed.animations.values) {
            animRefCount += anim.resources.count { it.resourceId !in diskResIds }
        }
        if (animRefCount > 0) {
            issues.add("• $animRefCount 处动画资源引用将失效并被清理")
        }

        val badStates = parsed.states.values.filter { it.animationId !in parsed.animations.keys }
        if (badStates.isNotEmpty()) {
            val preview = badStates.take(3).joinToString("、") { it.id }
            val suffix = if (badStates.size > 3) " 等" else ""
            issues.add("• ${badStates.size} 个状态引用了不存在的动画，将被移除: $preview$suffix")
        }

        var badRules = 0
        for (s in parsed.states.values) {
            badRules += s.rules.count { it.targetStateId !in parsed.states.keys }
        }
        if (badRules > 0) {
            issues.add("• $badRules 条转移规则的目标状态不存在，将被移除")
        }

        var badEventRules = 0
        var badEventSources = 0
        for (r in parsed.eventRules) {
            if (r.targetStateId !in parsed.states.keys) badEventRules++
            badEventSources += r.sourceStateIds.count { it !in parsed.states.keys }
        }
        if (badEventRules > 0) {
            issues.add("• $badEventRules 条事件规则的目标状态不存在，将被移除")
        }
        if (badEventSources > 0) {
            issues.add("• $badEventSources 个事件规则的触发状态引用将失效")
        }

        if (parsed.defaultStateId.isNotEmpty() && parsed.defaultStateId !in parsed.states.keys) {
            issues.add("• 默认状态 ${parsed.defaultStateId} 不存在，将被自动修正")
        }

        return issues
    }

    // ==================== ZIP 导入 ====================

    // 解压 → 校验 → 备份现有 → 覆盖 → 失败回滚。返回 null 表示成功。
    // 全流程在调用线程执行，调用方应在主线程外或接受短暂阻塞。
    fun importZip(context: Context, uri: Uri): String? {
        val tmpRoot = File(context.filesDir, "import_tmp")
        val backupRoot = File(context.filesDir, "import_backup")
        val configFile = ProjectConfigStore.configFile(context)
        val animDir = ProjectConfigStore.animDir(context)

        return try {
            tmpRoot.deleteRecursively()
            backupRoot.deleteRecursively()
            if (!tmpRoot.mkdirs()) return "无法创建临时目录"

            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    val canonicalRoot = tmpRoot.canonicalPath + File.separator
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val target = File(tmpRoot, name)
                        if (name.contains("..") ||
                            !target.canonicalPath.startsWith(canonicalRoot)) {
                            return "压缩包包含非法路径: $name"
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
            } ?: return "无法读取压缩包"

            val tmpConfig = File(tmpRoot, ZIP_ENTRY_CONFIG)
            if (!tmpConfig.exists()) return "压缩包内缺少 project.json"
            val tmpText = tmpConfig.readText()
            val parsed = ProjectConfigStore.parseOnly(tmpText)
                ?: return "project.json 格式错误"

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
                return "压缩包缺少资源: ${missing.distinct().joinToString("、")}"
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
                    // 回滚失败交给上层提示
                }
                throw e
            }

            LogBuffer.i("压缩包导入成功")
            null
        } catch (e: Exception) {
            LogBuffer.e("压缩包导入失败", e)
            e.message ?: "未知错误"
        } finally {
            tmpRoot.deleteRecursively()
            backupRoot.deleteRecursively()
        }
    }

    // ==================== GIF 资源导入 ====================

    sealed class ImportGifResult {
        data class Ok(val resourceId: String) : ImportGifResult()
        data class Failure(val message: String) : ImportGifResult()
    }

    fun importGif(context: Context, uri: Uri): ImportGifResult {
        return try {
            var displayName = "anim_${System.currentTimeMillis()}.gif"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
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

            val dir = ProjectConfigStore.animDir(context)
            var target = File(dir, "$safeBase.gif")
            var counter = 1
            while (target.exists()) {
                target = File(dir, "${safeBase}_$counter.gif")
                counter++
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return ImportGifResult.Failure("无法读取所选文件")

            LogBuffer.i("GIF 导入: ${target.nameWithoutExtension}")
            ImportGifResult.Ok(target.nameWithoutExtension)
        } catch (e: Exception) {
            LogBuffer.e("GIF 导入失败", e)
            ImportGifResult.Failure(e.message ?: "未知错误")
        }
    }
}