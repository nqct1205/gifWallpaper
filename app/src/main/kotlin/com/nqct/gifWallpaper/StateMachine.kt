package com.nqct.gifWallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// 数据模型：转移规则（目标状态 + 权重）
data class TransitionRule(
    var targetId: String = "",
    var weight: Int = 1
)

// 数据模型：状态节点（动画本身 + 播放次数 + 规则）
data class StateNode(
    var id: String = "",           // 对应 animations/ 目录下的文件名 (无后缀)
    var minSelfPlay: Int = 1,      // 自身最小播放次数
    var maxSelfPlay: Int = 1,      // 自身最大播放次数
    var rules: MutableList<TransitionRule> = mutableListOf()
)

class StateMachine {
    var initialStateId: String = ""
    val states = mutableListOf<StateNode>()

    fun getState(id: String): StateNode? = states.find { it.id == id }

    /**
     * 保存到 JSON 文件。
     * 【注意】此方法在主线程中执行，因为文件通常极小。
     * 若未来扩展让文件变大，需移至后台线程。
     */
    fun save(context: Context) {
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
        
        val file = File(context.filesDir, "state_machine.json")
        file.writeText(json.toString(4)) // 格式化缩进，方便用户导出后阅读
    }

    companion object {
        /**
         * 从文件加载。
         * 【坑点】JSON 格式可能被用户手动修改损坏，或者引用的文件被删。
         * 所以这里使用了大量 optXxx 和 try-catch 做防崩溃兜底。
         */
        fun load(context: Context): StateMachine {
            val sm = StateMachine()
            val file = File(context.filesDir, "state_machine.json")
            if (!file.exists()) return sm

            try {
                val json = JSONObject(file.readText())
                sm.initialStateId = json.optString("initialState", "")
                val statesArray = json.optJSONArray("states") ?: return sm
                
                for (i in 0 until statesArray.length()) {
                    val stateObj = statesArray.getJSONObject(i)
                    val node = StateNode(
                        id = stateObj.getString("id"),
                        minSelfPlay = stateObj.optInt("minSelfPlay", 1),
                        maxSelfPlay = stateObj.optInt("maxSelfPlay", 1)
                    )
                    val rulesArray = stateObj.optJSONArray("rules") ?: JSONArray()
                    for (j in 0 until rulesArray.length()) {
                        val ruleObj = rulesArray.getJSONObject(j)
                        node.rules.add(
                            TransitionRule(
                                targetId = ruleObj.getString("targetId"),
                                weight = ruleObj.optInt("weight", 1)
                            )
                        )
                    }
                    sm.states.add(node)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 【注意】这里如果解析失败，会返回空状态机（表现为黑屏报错），这是刻意设计的，为了把问题暴露出来
            }
            return sm
        }
    }
}