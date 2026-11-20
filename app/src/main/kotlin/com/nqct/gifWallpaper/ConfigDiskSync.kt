package com.nqct.gifWallpaper

import java.io.File

// 配置与磁盘资源的同步规则。
//
// 【职责边界】只做"以磁盘为准"的数据一致性修正，不做 IO 读写（目录由调用方传入）。
// 从 ProjectConfigStore.syncWithDisk 拆出，便于独立验证清理规则。
//
// 【当前规则（行为与拆分前一致）】
//   1. 磁盘上不存在对应 .gif 的资源 → 从 config.resources 移除。
//   2. 磁盘上存在但未登记的资源 → 补登记，并解析 GifInfo。
//   3. 已登记资源 → 刷新 fileName 与 info。
//   4. 清理动画里指向已移除/不存在资源的引用。
//   5. 清理 animationId 不存在动画的状态。
//   6. 清理转移规则/事件规则里指向不存在状态的目标与来源。
//   7. 修正无效的 defaultStateId。
object ConfigDiskSync {

    fun syncWithDisk(config: ProjectConfig, dir: File) {
        val files = dir.listFiles { f -> f.extension.lowercase() == "gif" } ?: emptyArray()
        val diskIds = files.map { it.nameWithoutExtension }.toSet()

        val removedResIds = config.resources.keys.filter { it !in diskIds }.toSet()
        for (id in removedResIds) config.resources.remove(id)

        for (file in files) {
            val id = file.nameWithoutExtension
            val info = GifMetadataParser.parse(file)
            val existing = config.resources[id]
            if (existing != null) {
                config.resources[id] = existing.copy(fileName = file.name, info = info)
            } else {
                config.resources[id] = ResourceItem(id, file.name, info)
            }
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
}