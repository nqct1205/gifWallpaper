package com.nqct.gifWallpaper

import android.os.SystemClock

// 播放控制器：状态机的纯逻辑层。
// 只维护 当前状态 / 已完成圈数 / 目标圈数 / 切换决策，不持有 Drawable / View / Context。
//
// 周期语义：进入状态时从 [periodMin, periodMax] 随机抽取目标圈数；
// 每播完一圈 completedCycles+1，达到目标后按权重转移。
class PlaybackController(
    private val configProvider: () -> ProjectConfig
) {
    private var currentStateId: String = ""
    private var completedCycles: Int = 0
    private var targetCycles: Int = 1
    private var stateEnterTimeMs: Long = 0L

    fun currentStateId(): String = currentStateId
    fun completedCycles(): Int = completedCycles
    fun targetCycles(): Int = targetCycles
    fun stateEnterTimeMs(): Long = stateEnterTimeMs

    fun currentState(): StateNode? = configProvider().states[currentStateId]

    fun currentAnimation(): AnimationItem? {
        val state = currentState() ?: return null
        return configProvider().animations[state.animationId]
    }

    // 重置到默认状态。
    fun reset() {
        val cfg = configProvider()
        val defaultId = cfg.defaultStateId.ifEmpty {
            cfg.states.keys.firstOrNull() ?: ""
        }
        enterState(defaultId)
    }

    // 一圈播完时调用。
    // 返回下一个应播放的状态 ID；与 currentStateId 相同表示原地重播。
    fun onCycleComplete(): String {
        completedCycles++
        if (completedCycles < targetCycles) {
            return currentStateId
        }
        val state = currentState() ?: return currentStateId
        if (state.rules.isEmpty()) {
            enterState(currentStateId)
            return currentStateId
        }
        val totalWeight = state.rules.sumOf { it.weight.coerceAtLeast(0) }
        if (totalWeight <= 0) {
            enterState(currentStateId)
            return currentStateId
        }
        var r = (1..totalWeight).random()
        var target = currentStateId
        for (rule in state.rules) {
            r -= rule.weight
            if (r <= 0) { target = rule.targetStateId; break }
        }
        enterState(target)
        return target
    }

    // 事件驱动切换。
    //
    // 匹配条件（全部满足）：
    //   1. 主事件类型 + 参数匹配
    //   2. 当前状态在 sourceStateIds 中（空 = 任意）
    //   3. 所有 conditions 在当前快照下满足
    //
    // @param type               主事件类型
    // @param param              主事件参数（方向事件为目标方向名；其他为空串）
    // @param currentOrientation 事件触发瞬间的手机方向（用于条件判定）
    // @return 是否发生切换
    fun handleEvent(
        type: EventType,
        param: String,
        currentOrientation: DeviceOrientation
    ): Boolean {
        val cfg = configProvider()
        val matched = cfg.eventRules.firstOrNull { rule ->
            rule.type == type &&
                (rule.triggerParam.isEmpty() || rule.triggerParam == param) &&
                (rule.sourceStateIds.isEmpty() || currentStateId in rule.sourceStateIds) &&
                rule.conditions.all { cond ->
                    conditionMatches(cond, currentOrientation)
                }
        } ?: return false

        if (matched.targetStateId.isEmpty() || matched.targetStateId == currentStateId) return false
        if (cfg.states[matched.targetStateId] == null) return false
        enterState(matched.targetStateId)
        return true
    }

    // 条件匹配。
    // 当前仅支持 ORIENTATION：param 与当前方向 name 比较。
    // 未来新增条件类型时在此扩展。
    private fun conditionMatches(cond: EventCondition, currentOrientation: DeviceOrientation): Boolean {
        return when (cond.type) {
            EventType.ORIENTATION -> cond.param == currentOrientation.name
            // SHAKE / CLICK 作为条件类型暂不支持（属于"事件"，不是"状态快照"）
            else -> false
        }
    }

    private fun enterState(stateId: String) {
        currentStateId = stateId
        completedCycles = 0
        stateEnterTimeMs = SystemClock.elapsedRealtime()
        val state = configProvider().states[stateId]
        targetCycles = if (state != null && state.periodMax > state.periodMin) {
            (state.periodMin..state.periodMax).random()
        } else {
            (state?.periodMin ?: 1).coerceAtLeast(1)
        }
    }
}