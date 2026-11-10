package com.nqct.gifWallpaper

import android.os.SystemClock

// 播放控制器：状态机的纯逻辑层。
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

    fun reset() {
        val cfg = configProvider()
        val defaultId = cfg.defaultStateId.ifEmpty {
            cfg.states.keys.firstOrNull() ?: ""
        }
        enterState(defaultId)
    }

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
    // 【修复 P3-14】遍历所有匹配规则，取第一条"目标有效且不等于当前状态"的规则。
    // 原实现用 firstOrNull 取首条匹配规则，若其目标无效或等于当前状态则直接 return false，
    // 导致后续有效规则被忽略。
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
                rule.conditions.all { conditionMatches(it, currentOrientation) } &&
                // 目标必须有效且不等于当前状态，否则跳过此条，尝试下一条
                rule.targetStateId.isNotEmpty() &&
                rule.targetStateId != currentStateId &&
                cfg.states[rule.targetStateId] != null
        } ?: return false

        enterState(matched.targetStateId)
        return true
    }

    private fun conditionMatches(cond: EventCondition, currentOrientation: DeviceOrientation): Boolean {
        return when (cond.type) {
            EventType.ORIENTATION -> cond.param == currentOrientation.name
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