package com.nqct.gifWallpaper

import android.os.SystemClock

/**
 * 播放控制器：状态机的纯逻辑层。
 *
 * 【职责边界】
 *  - 只维护"当前状态、已完成圈数、目标圈数、切换决策"。
 *  - 不持有任何 Drawable / View / Context，可独立单元测试。
 *  - 由 [GifWallpaperService.GifEngine] 驱动：
 *      · 一圈播完 → [onCycleComplete]
 *      · 传感器/触摸事件 → [handleEvent]
 *      · 重置 → [reset]
 *
 * 【周期语义】当前“周期”= 进入状态时从 [StateNode.periodMin]..[StateNode.periodMax]
 * 随机抽取的目标圈数。每播完一圈 [completedCycles] 加 1；达到目标后按权重转移。
 * 后续可能改为“时长”或“状态内多动画各有独立周期”，届时需重新设计此处。
 */
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

    /** 重置到默认状态。 */
    fun reset() {
        val cfg = configProvider()
        val defaultId = cfg.defaultStateId.ifEmpty {
            cfg.states.keys.firstOrNull() ?: ""
        }
        enterState(defaultId)
    }

    /**
     * 一圈播完时调用。
     * @return 下一个应播放的状态 ID；与 [currentStateId] 相同表示原地重播。
     */
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

    /**
     * 事件驱动切换。
     *
     * 【当前策略】匹配即跳转（确定性），不经过权重随机。
     * 【后续扩展】可在 [EventRule] 增加概率字段，实现“按概率跳不同状态或干脆不跳”。
     *
     * @param type  事件类型
     * @param param 触发参数（方向事件为 FORWARD/REVERSE；其他事件传空串）
     * @return 是否发生切换
     */
    fun handleEvent(type: EventType, param: String): Boolean {
        val cfg = configProvider()
        val matched = cfg.eventRules.firstOrNull { rule ->
            rule.type == type &&
                (rule.triggerParam.isEmpty() || rule.triggerParam == param) &&
                (rule.sourceStateIds.isEmpty() || currentStateId in rule.sourceStateIds)
        } ?: return false
        if (matched.targetStateId.isEmpty() || matched.targetStateId == currentStateId) return false
        if (cfg.states[matched.targetStateId] == null) return false
        enterState(matched.targetStateId)
        return true
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