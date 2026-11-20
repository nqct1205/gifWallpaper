package com.nqct.gifWallpaper

import org.json.JSONArray
import org.json.JSONObject

// 项目配置的 JSON 解析与序列化。
//
// 【职责边界】只做 ProjectConfig <-> JSON 的纯转换，不碰文件系统、不碰 Context。
// 从 ProjectConfigStore 拆出，便于单独验证"同一份配置两次 toJsonString 结果一致"。
//
// 【一致性约束】toJsonString 输出格式固定（4 空格缩进、字段顺序固定），
// 被 MainActivity 用于"未修改检测"的字符串比对，任何字段顺序/格式变化都视为破坏性变更。
object ProjectConfigJson {

    // ==================== 解析 ====================

    fun parse(json: JSONObject): ProjectConfig {
        val config = ProjectConfig()
        config.defaultStateId = json.optString("defaultStateId", "")

        val resourcesArr = json.optJSONArray("resources") ?: JSONArray()
        for (i in 0 until resourcesArr.length()) {
            val obj = resourcesArr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val fileName = obj.optString("fileName", "")
            if (id.isEmpty() || fileName.isEmpty()) continue
            config.resources[id] = ResourceItem(
                id = id,
                fileName = fileName,
                info = null,
                playbackMode = parsePlaybackMode(obj.optString("playbackMode", "NATIVE")),
                customValue = obj.optInt("customValue", 0)
            )
        }

        val animsArr = json.optJSONArray("animations") ?: JSONArray()
        for (i in 0 until animsArr.length()) {
            val obj = animsArr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isEmpty()) continue
            val anim = AnimationItem(
                id = id,
                name = obj.optString("name", id),
                backgroundColor = obj.optString("backgroundColor", ""),
                endTimeMs = obj.optInt("endTimeMs", 0).coerceAtLeast(0)
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
                        offsetY = refObj.optDouble("offsetY", 0.0).toFloat(),
                        startDelayMs = refObj.optInt("startDelayMs", 0).coerceAtLeast(0)
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

            if (type == EventType.ORIENTATION && !isValidOrientationName(triggerParam)) continue

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
            val condArr = obj.optJSONArray("conditions") ?: JSONArray()
            for (j in 0 until condArr.length()) {
                val cObj = condArr.optJSONObject(j) ?: continue
                val cType = parseEventType(cObj.optString("type", "ORIENTATION"))
                val cParam = cObj.optString("param", "")
                if (cType == EventType.ORIENTATION && isValidOrientationName(cParam)) {
                    rule.conditions.add(EventCondition(cType, cParam))
                }
            }
            config.eventRules.add(rule)
        }

        return config
    }

    fun parseOnly(text: String): ProjectConfig? {
        if (text.isBlank()) return null
        return try {
            parse(JSONObject(text))
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 序列化 ====================

    // 格式固定（4 空格缩进、字段顺序固定），同一份配置两次调用结果完全一致，可做字符串比对。
    fun toJsonString(config: ProjectConfig): String {
        val json = JSONObject()
        json.put("defaultStateId", config.defaultStateId)

        val resArr = JSONArray()
        for (r in config.resources.values) {
            resArr.put(JSONObject().apply {
                put("id", r.id)
                put("fileName", r.fileName)
                put("playbackMode", r.playbackMode.name)
                put("customValue", r.customValue)
            })
        }
        json.put("resources", resArr)

        val animArr = JSONArray()
        for (a in config.animations.values) {
            val obj = JSONObject().apply {
                put("id", a.id)
                put("name", a.name)
                put("backgroundColor", a.backgroundColor)
                put("endTimeMs", a.endTimeMs)
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
                    put("startDelayMs", ref.startDelayMs)
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

        return json.toString(4)
    }

    // ==================== 解析工具 ====================

    private fun parseAnchor(name: String): AnchorPoint =
        runCatching { AnchorPoint.valueOf(name) }.getOrDefault(AnchorPoint.CENTER)

    private fun parseScaleMode(name: String): ScaleMode =
        runCatching { ScaleMode.valueOf(name) }.getOrDefault(ScaleMode.PERCENT)

    private fun parseEventType(name: String): EventType =
        runCatching { EventType.valueOf(name) }.getOrDefault(EventType.ORIENTATION)

    private fun parsePlaybackMode(name: String): ResourcePlaybackMode =
        runCatching { ResourcePlaybackMode.valueOf(name) }.getOrDefault(ResourcePlaybackMode.NATIVE)

    private fun isValidOrientationName(name: String): Boolean {
        if (name.isEmpty()) return false
        return DeviceOrientation.values().any { it.name == name && it != DeviceOrientation.UNKNOWN }
    }
}