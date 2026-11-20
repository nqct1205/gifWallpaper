package com.nqct.gifWallpaper

import java.io.File

// 项目配置的领域操作。
//
// 【职责边界】只做数据操作 + 业务规则，不涉及 UI/Toast/弹窗/Intent。
// 是否弹窗、Toast 什么、是否跳转，由 MainActivity 决定。
//
// 【为什么放这里】这些操作与 Android 框架无关，可脱离 Activity 独立验证。
object ConfigOperations {

    // ==================== 保存前归一化 ====================

    // 单资源动画不支持启动延迟，保存前清零。
    // endTimeMs 保留：单资源也允许自定义整圈时长（自动 = 资源自身时长）。
    fun normalizeSingleResourceAnimations(config: ProjectConfig) {
        for (anim in config.animations.values) {
            if (anim.resources.size <= 1) {
                anim.resources.forEach { if (it.startDelayMs != 0) it.startDelayMs = 0 }
            }
        }
    }

    // ==================== 新建 ====================

    // 生成不冲突的新动画 id 并创建，返回新 id。
    fun createNewAnimation(config: ProjectConfig): String {
        var idx = 1
        while (config.animations.containsKey("anim_$idx")) idx++
        val id = "anim_$idx"
        config.animations[id] = AnimationItem(id = id, name = "动画 $idx")
        return id
    }

    // 生成不冲突的新状态 id 并创建；若当前无默认状态则设为默认。返回新 id。
    fun createNewState(config: ProjectConfig): String {
        var idx = 1
        while (config.states.containsKey("state_$idx")) idx++
        val id = "state_$idx"
        config.states[id] = StateNode(id = id, name = "状态 $idx")
        if (config.defaultStateId.isEmpty()) config.defaultStateId = id
        return id
    }

    // ==================== 资源 ====================

    // 返回引用该资源的动画列表；资源不存在返回 null。
    fun getResourceUsage(config: ProjectConfig, resourceId: String): List<AnimationItem>? {
        if (config.resources[resourceId] == null) return null
        return config.animations.values.filter { anim ->
            anim.resources.any { it.resourceId == resourceId }
        }
    }

    // 仅删磁盘文件。config 内存清理由调用方随后的 loadConfig（syncWithDisk）完成，
    // 保持与原 MainActivity.deleteResource 相同的行为路径。
    fun deleteResourceFile(config: ProjectConfig, resourceId: String, animDir: File) {
        val resource = config.resources[resourceId] ?: return
        File(animDir, resource.fileName).delete()
    }

    // ==================== 动画 ====================

    // 返回引用该动画的状态列表；动画不存在返回 null。
    fun getAnimationUsage(config: ProjectConfig, animationId: String): List<StateNode>? {
        if (config.animations[animationId] == null) return null
        return config.states.values.filter { it.animationId == animationId }
    }

    fun deleteAnimation(config: ProjectConfig, animationId: String) {
        config.animations.remove(animationId)
    }

    // ==================== 状态 ====================

    // 删除状态并清理所有指向它的转移规则、事件规则目标、事件规则来源；
    // 若删除的是默认状态，回退到第一个可用状态。
    fun deleteState(config: ProjectConfig, stateId: String) {
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
    }

    // ==================== 事件规则 ====================

    fun deleteEventRule(config: ProjectConfig, ruleId: String) {
        config.eventRules.removeAll { it.id == ruleId }
    }
}