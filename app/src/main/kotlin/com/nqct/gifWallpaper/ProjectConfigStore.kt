package com.nqct.gifWallpaper

import android.content.Context
import org.json.JSONObject
import java.io.File

// 项目配置加载 / 保存 / 与磁盘同步。
//
// 【拆分说明】本轮把 JSON 转换与磁盘同步拆到独立文件：
//   - ProjectConfigJson：ProjectConfig <-> JSON（parse / parseOnly / toJsonString）
//   - ConfigDiskSync   ：以磁盘为准的一致性修正（syncWithDisk）
// 本类保留文件路径、加载/保存入口，签名与行为不变，仅内部委托。
//
// 【toJsonString】保留在此作为对外入口，供 save() 与"未修改检测"共用，
// 实际实现委托 ProjectConfigJson。MainActivity 进入编辑页时记录快照，
// 返回时与当前 config 的 toJsonString 比对，未变化则不弹保存确认框。
object ProjectConfigStore {

    const val CONFIG_FILE = "project.json"
    const val ANIM_DIR = "animations"

    sealed class LoadResult {
        data class Ok(val config: ProjectConfig) : LoadResult()
        data class Corrupted(val message: String) : LoadResult()
    }

    fun animDir(context: Context): File {
        val dir = File(context.filesDir, ANIM_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun configFile(context: Context): File = File(context.filesDir, CONFIG_FILE)

    // ==================== 加载 ====================

    fun load(context: Context): LoadResult {
        val file = configFile(context)
        val config = if (!file.exists()) {
            ProjectConfig()
        } else {
            try {
                val text = file.readText()
                if (text.isBlank()) return LoadResult.Corrupted("配置文件为空")
                ProjectConfigJson.parse(JSONObject(text))
            } catch (e: Exception) {
                return LoadResult.Corrupted(e.message ?: "JSON 解析异常")
            }
        }
        ConfigDiskSync.syncWithDisk(config, animDir(context))
        return LoadResult.Ok(config)
    }

    fun parseOnly(text: String): ProjectConfig? = ProjectConfigJson.parseOnly(text)

    // ==================== 序列化 ====================

    fun toJsonString(config: ProjectConfig): String = ProjectConfigJson.toJsonString(config)

    // ==================== 保存 ====================

    fun save(context: Context, config: ProjectConfig): Boolean {
        return try {
            configFile(context).writeText(toJsonString(config))
            true
        } catch (e: Exception) {
            LogBuffer.e("配置保存失败", e)
            false
        }
    }
}