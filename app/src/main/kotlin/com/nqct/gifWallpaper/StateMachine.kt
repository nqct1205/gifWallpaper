package com.nqct.gifWallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 单条状态转移规则。
 * @param targetId 目标状态 ID（无后缀文件名）
 * @param weight   随机抽取权重（>=0，0 表示不会被抽到）
 */
data class TransitionRule(
    var targetId: String = "",
    var weight: Int = 1
)

/**
 * 状态机节点（= 一个动画 + 自身播放次数范围 + 转移规则）。
 */
data class StateNode(
    var id: String = "",
    var minSelfPlay: Int = 1,
    var maxSelfPlay: Int = 1,
    var rules: MutableList<TransitionRule> = mutableListOf()
)

/**
 * 加载结果：
 *  - Ok：正常（包括"文件不存在 → 返回空状态机"）
 *  - Corrupted：任意字段致命损坏 / 整个 JSON 无法解析，调用方决定策略
 */
sealed class LoadResult {
    data class Ok(val sm: StateMachine) : LoadResult()
    data class Corrupted(val message: String) : LoadResult()
}

/**
 * 状态机序列化 / 反序列化。
 * 存储路径：filesDir/state_machine.json
 */
class StateMachine {
    var initialStateId: String = ""
    val states = mutableListOf<StateNode>()

    fun getState(id: String): StateNode? = states.find { it.id == id }

    /** 保存到默认路径，返回是否成功。 */
    fun save(context: Context): Boolean = save(File(context.filesDir, "state_machine.json"))

    /** 保存到指定文件，返回是否成功（不再抛异常）。 */
    fun save(file: File): Boolean {
        return try {
            val json = JSONObject()
            json.put("initialState", initialStateId)
            val statesArray = JSONArray()

            for (state in states) {
                val stateObj = JSONObject()
                stateObj.put("id", state.id)
                stateObj.put("minSelfPlay", state.minSelfPlay)
                stateObj.put("maxSelfPlay", state.maxSelfPlay)

                val rulesArray = JSONArray()
                for (rule in state.rules) {
                    val ruleObj = JSONObject()
                    ruleObj.put("targetId", rule.targetId)
                    ruleObj.put("weight", rule.weight)
                    rulesArray.put(ruleObj)
                }
                stateObj.put("rules", rulesArray)
                statesArray.put(stateObj)
            }
            json.put("states", statesArray)
            file.writeText(json.toString(4))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    companion object {
        fun load(context: Context): LoadResult =
            load(File(context.filesDir, "state_machine.json"))

        /**
         * 加载并解析。
         *  - 文件不存在 / 为空：视为"正常空"，返回 Ok(空 stateMachine)。
         *  - 任意解析错误，或所有节点都无效：返回 Corrupted。
         *  - 字段级容错：id 为空的节点跳过，targetId 为空的规则跳过。
         */
        fun load(file: File): LoadResult {
            if (!file.exists()) return LoadResult.Ok(StateMachine())
            return try {
                val text = file.readText()
                if (text.isBlank()) return LoadResult.Corrupted("配置文件为空")
                val json = JSONObject(text)

                val sm = StateMachine()
                sm.initialStateId = json.optString("initialState", "")
                val statesArray = json.optJSONArray("states")
                    ?: return LoadResult.Corrupted("缺少 states 字段")

                var parsed = 0
                var skipped = 0
                for (i in 0 until statesArray.length()) {
                    val stateObj = try {
                        statesArray.getJSONObject(i)
                    } catch (e: Exception) { skipped++; continue }

                    val id = stateObj.optString("id", "")
                    if (id.isEmpty()) { skipped++; continue }

                    val node = StateNode(
                        id = id,
                        minSelfPlay = stateObj.optInt("minSelfPlay", 1).coerceAtLeast(1),
                        maxSelfPlay = stateObj.optInt("maxSelfPlay", 1).coerceAtLeast(1)
                    )
                    if (node.maxSelfPlay < node.minSelfPlay) node.maxSelfPlay = node.minSelfPlay

                    val rulesArray = stateObj.optJSONArray("rules") ?: JSONArray()
                    for (j in 0 until rulesArray.length()) {
                        val ruleObj = try {
                            rulesArray.getJSONObject(j)
                        } catch (e: Exception) { continue }
                        val tid = ruleObj.optString("targetId", "")
                        if (tid.isEmpty()) continue
                        node.rules.add(
                            TransitionRule(
                                targetId = tid,
                                weight = ruleObj.optInt("weight", 1).coerceAtLeast(0)
                            )
                        )
                    }
                    sm.states.add(node)
                    parsed++
                }
                if (parsed == 0 && statesArray.length() > 0) {
                    return LoadResult.Corrupted("所有状态均无法解析（跳过 $skipped 项）")
                }
                LoadResult.Ok(sm)
            } catch (e: Exception) {
                LoadResult.Corrupted(e.message ?: "JSON 解析异常")
            }
        }
    }
}