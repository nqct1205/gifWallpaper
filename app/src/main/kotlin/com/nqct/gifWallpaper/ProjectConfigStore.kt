package com.nqct.gifWallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// 项目配置加载 / 保存 / 与磁盘同步。
// 持久化只存"用户配置"，不存解析缓存（ResourceItem.info 重新解析）。
// 加载时与磁盘 GIF 列表同步：新增补建、缺失清理引用。
// JSON 损坏返回 Corrupted，不静默覆盖磁盘。
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
                parse(JSONObject(text))
            } catch (e: Exception) {
                return LoadResult.Corrupted(e.message ?: "JSON 解析异常")
            }
        }
        syncWithDisk(context, config)
        return LoadResult.Ok(config)
    }

    // 仅解析 JSON 文本，不写盘、不同步。用于导入前的格式验证。
    fun parseOnly(text: String): ProjectConfig? {
        if (text.isBlank()) return null
        return try {
            parse(JSONObject(text))
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(json: JSONObject): ProjectConfig {
        val config = ProjectConfig()
        config.defaultStateId = json.optString("defaultStateId", "")

        val resourcesArr = json.optJSONArray("resources") ?: JSONArray()
        for (i in 0 until resourcesArr.length()) {
            val obj = resourcesArr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val fileName = obj.optString("fileName", "")
            if (id.isEmpty() || fileName.isEmpty()) continue
            config.resources[id] = ResourceItem(id, fileName, null)
        }

        val animsArr = json.optJSONArray("animations") ?: JSONArray()
        for (i in 0 until animsArr.length()) {
            val obj = animsArr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isEmpty()) continue
            val anim = AnimationItem(
                id = id,
                name = obj.optString("name", id),
                backgroundColor = obj.optString("backgroundColor", "")
            )
            val refsArr = obj.optJSONArray("resources") ?: JSONArray()
            for (j in 0 until refsArr.length()) {
                val refObj = refsArr.optJSONObject(j) ?: continue
                val rid = refObj.optString("resourceId", "")
                if (rid.isEmpty()) continue
                anim.resources.add(
                    AnimationResourceRef(
                        resourceId = rid,
                        anchor = parseAnchor(refObj.optString("anchor", "CENTER")),
                        targetAnchor = parseAnchor(refObj.optString("targetAnchor", "CENTER")),
                        scaleMode = parseScaleMode(refObj.optString("scaleMode", "PERCENT")),
                        scaleValue = refObj.optDouble("scaleValue", 100.0).toFloat(),
                        offsetX = refObj.optDouble("offsetX", 0.0).toFloat(),
                        offsetY = refObj.optDouble("offsetY", 0.0).toFloat()
                    )
                )
            }
            config.animations[id] = anim
        }

        val statesArr = json.optJSONArray("states") ?: JSONArray()
        for (i in 0 until statesArr.length()) {
            val obj = statesArr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isEmpty()) continue
            val node = StateNode(
                id = id,
                name = obj.optString("name", ""),
                animationId = obj.optString("animationId", ""),
                periodMin = obj.optInt("periodMin", 1).coerceAtLeast(1),
                periodMax = obj.optInt("periodMax", 1).coerceAtLeast(1)
            )
            if (node.periodMax < node.periodMin) node.periodMax = node.periodMin
            val rulesArr = obj.optJSONArray("rules") ?: JSONArray()
            for (j in 0 until rulesArr.length()) {
                val rObj = rulesArr.optJSONObject(j) ?: continue
                val tid = rObj.optString("targetStateId", "")
                if (tid.isEmpty()) continue
                node.rules.add(
                    TransitionRule(
                        targetStateId = tid,
                        weight = rObj.optInt("weight", 1).coerceAtLeast(0)
                    )
                )
            }
            config.states[id] = node
        }

        val evArr = json.optJSONArray("eventRules") ?: JSONArray()
        for (i in 0 until evArr.length()) {
            val obj = evArr.optJSONObject(i) ?: continue
            val type = parseEventType(obj.optString("type", "ORIENTATION"))
            val triggerParam = obj.optString("triggerParam", "")

            // 旧版本方向规则（FORWARD / REVERSE）作废：直接丢弃
            if (type == EventType.ORIENTATION && !isValidOrientationName(triggerParam)) {
                continue
            }

            val rule = EventRule(
                id = obj.optString("id", "ev_$i"),
                type = type,
                triggerParam = triggerParam,
                targetStateId = obj.optString("targetStateId", "")
            )
            val srcArr = obj.optJSONArray("sourceStateIds") ?: JSONArray()
            for (j in 0 until srcArr.length()) {
                val sid = srcArr.optString(j, "")
                if (sid.isNotEmpty()) rule.sourceStateIds.add(sid)
            }
            // 附加条件（当前仅解析方向条件）
            val condArr = obj.optJSONArray("conditions") ?: JSONArray()
            for (j in 0 until condArr.length()) {
                val cObj = condArr.optJSONObject(j) ?: continue
                val cType = parseEventType(cObj.optString("type", "ORIENTATION"))
                val cParam = cObj.optString("param", "")
                if (cType == EventType.ORIENTATION && isValidOrientationName(cParam)) {
                    rule.conditions.add(EventCondition(cType, cParam))
                }
                // 其他类型的条件暂不解析（未来扩展）
            }
            config.eventRules.add(rule)
        }

        return config
    }

    // ==================== 保存 ====================

    fun save(context: Context, config: ProjectConfig): Boolean {
        return try {
            val json = JSONObject()
            json.put("defaultStateId", config.defaultStateId)

            val resArr = JSONArray()
            for (r in config.resources.values) {
                resArr.put(JSONObject().apply {
                    put("id", r.id)
                    put("fileName", r.fileName)
                })
            }
            json.put("resources", resArr)

            val animArr = JSONArray()
            for (a in config.animations.values) {
                val obj = JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("backgroundColor", a.backgroundColor)
                }
                val refs = JSONArray()
                for (ref in a.resources) {
                    refs.put(JSONObject().apply {
                        put("resourceId", ref.resourceId)
                        put("anchor", ref.anchor.name)
                        put("targetAnchor", ref.targetAnchor.name)
                        put("scaleMode", ref.scaleMode.name)
                        put("scaleValue", ref.scaleValue.toDouble())
                        put("offsetX", ref.offsetX.toDouble())
                        put("offsetY", ref.offsetY.toDouble())
                    })
                }
                obj.put("resources", refs)
                animArr.put(obj)
            }
            json.put("animations", animArr)

            val stateArr = JSONArray()
            for (s in config.states.values) {
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("animationId", s.animationId)
                    put("periodMin", s.periodMin)
                    put("periodMax", s.periodMax)
                }
                val rules = JSONArray()
                for (r in s.rules) {
                    rules.put(JSONObject().apply {
                        put("targetStateId", r.targetStateId)
                        put("weight", r.weight)
                    })
                }
                obj.put("rules", rules)
                stateArr.put(obj)
            }
            json.put("states", stateArr)

            val evArr = JSONArray()
            for (e in config.eventRules) {
                val obj = JSONObject().apply {
                    put("id", e.id)
                    put("type", e.type.name)
                    put("triggerParam", e.triggerParam)
                    put("targetStateId", e.targetStateId)
                }
                val srcArr = JSONArray()
                for (sid in e.sourceStateIds) srcArr.put(sid)
                obj.put("sourceStateIds", srcArr)

                val condArr = JSONArray()
                for (c in e.conditions) {
                    condArr.put(JSONObject().apply {
                        put("type", c.type.name)
                        put("param", c.param)
                    })
                }
                obj.put("conditions", condArr)
                evArr.put(obj)
            }
            json.put("eventRules", evArr)

            configFile(context).writeText(json.toString(4))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ==================== 与磁盘同步 ====================

    // 用磁盘 GIF 列表刷新 resources：
    //   1. 新增 GIF 补建 ResourceItem（并解析 info）
    //   2. 缺失 GIF 移除 ResourceItem，清理引用它的 AnimationResourceRef
    //   3. 清理悬空状态 / 事件规则引用
    //   4. 校验 defaultStateId
    private fun syncWithDisk(context: Context, config: ProjectConfig) {
        val dir = animDir(context)
        val files = dir.listFiles { f -> f.extension.lowercase() == "gif" } ?: emptyArray()
        val diskIds = files.map { it.nameWithoutExtension }.toSet()

        val removedResIds = config.resources.keys.filter { it !in diskIds }.toSet()
        for (id in removedResIds) config.resources.remove(id)

        for (file in files) {
            val id = file.nameWithoutExtension
            val info = GifMetadataParser.parse(file)
            config.resources[id] = ResourceItem(id, file.name, info)
        }

        for (anim in config.animations.values) {
            anim.resources.removeAll { it.resourceId in removedResIds || config.resources[it.resourceId] == null }
        }

        val validAnimIds = config.animations.keys
        config.states.values.removeAll { it.animationId !in validAnimIds }

        val validStateIds = config.states.keys
        for (state in config.states.values) {
            state.rules.removeAll { it.targetStateId !in validStateIds }
        }

        config.eventRules.removeAll { it.targetStateId !in validStateIds }
        for (e in config.eventRules) {
            e.sourceStateIds.removeAll { it !in validStateIds }
        }

        if (config.defaultStateId !in validStateIds) {
            config.defaultStateId = config.states.keys.firstOrNull() ?: ""
        }
    }

    // ==================== 解析工具 ====================

    private fun parseAnchor(name: String): AnchorPoint =
        runCatching { AnchorPoint.valueOf(name) }.getOrDefault(AnchorPoint.CENTER)

    private fun parseScaleMode(name: String): ScaleMode =
        runCatching { ScaleMode.valueOf(name) }.getOrDefault(ScaleMode.PERCENT)

    private fun parseEventType(name: String): EventType =
        runCatching { EventType.valueOf(name) }.getOrDefault(EventType.ORIENTATION)

    // 校验方向名是否合法（用于丢弃旧 FORWARD/REVERSE 与非法值）
    private fun isValidOrientationName(name: String): Boolean {
        if (name.isEmpty()) return false
        return DeviceOrientation.values().any { it.name == name && it != DeviceOrientation.UNKNOWN }
    }
}