package com.nqct.gifWallpaper

// 项目全量数据模型。
// 分层：ResourceItem <- AnimationResourceRef <- AnimationItem <- StateNode
// 引用：1 状态 -> 1 动画；1 动画 -> N 资源引用；1 资源 <- N 动画引用。

// ==================== 资源层 ====================

// 磁盘上的 GIF 静态资源。所有字段由文件决定，用户不可修改。
data class ResourceItem(
    val id: String,
    val fileName: String,
    val info: GifInfo?
)

// ==================== 9 点定位 ====================

enum class AnchorPoint(val label: String) {
    TOP_LEFT("左上"),
    TOP_CENTER("上中"),
    TOP_RIGHT("右上"),
    CENTER_LEFT("左中"),
    CENTER("中心"),
    CENTER_RIGHT("右中"),
    BOTTOM_LEFT("左下"),
    BOTTOM_CENTER("下中"),
    BOTTOM_RIGHT("右下");

    fun normalized(): Pair<Float, Float> = when (this) {
        TOP_LEFT      -> 0f   to 0f
        TOP_CENTER    -> 0.5f to 0f
        TOP_RIGHT     -> 1f   to 0f
        CENTER_LEFT   -> 0f   to 0.5f
        CENTER        -> 0.5f to 0.5f
        CENTER_RIGHT  -> 1f   to 0.5f
        BOTTOM_LEFT   -> 0f   to 1f
        BOTTOM_CENTER -> 0.5f to 1f
        BOTTOM_RIGHT  -> 1f   to 1f
    }
}

// ==================== 缩放模式 ====================

enum class ScaleMode { PERCENT, PIXEL }

// ==================== 动画内的资源引用 ====================

data class AnimationResourceRef(
    var resourceId: String = "",
    var anchor: AnchorPoint = AnchorPoint.CENTER,
    var targetAnchor: AnchorPoint = AnchorPoint.CENTER,
    var scaleMode: ScaleMode = ScaleMode.PERCENT,
    var scaleValue: Float = 100f,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f
)

// ==================== 动画层 ====================

// 动画项：一组资源引用。
// backgroundColor：十六进制字符串（#RRGGBB 或 #AARRGGBB）；空串 = 不自定义，回退黑色。
data class AnimationItem(
    var id: String = "",
    var name: String = "",
    var backgroundColor: String = "",
    var resources: MutableList<AnimationResourceRef> = mutableListOf()
)

// ==================== 状态与转移规则 ====================

data class TransitionRule(
    var targetStateId: String = "",
    var weight: Int = 1
)

// 状态节点：引用一个动画 + 周期范围 + 概率转移规则。
// id 不可改（引用键）；name 用户可编辑，仅展示用。
data class StateNode(
    var id: String = "",
    var name: String = "",
    var animationId: String = "",
    var periodMin: Int = 1,
    var periodMax: Int = 1,
    var rules: MutableList<TransitionRule> = mutableListOf()
)

// ==================== 设备方向 ====================

enum class DeviceOrientation(val label: String) {
    SCREEN_UP("屏幕朝上"),
    SCREEN_DOWN("屏幕朝下"),
    PORTRAIT("竖屏"),
    PORTRAIT_REVERSED("竖屏倒置"),
    LANDSCAPE_LEFT("横屏(顶部朝左)"),
    LANDSCAPE_RIGHT("横屏(顶部朝右)"),
    UNKNOWN("未知");
}

// ==================== 事件规则 ====================

enum class EventType(val label: String) {
    ORIENTATION("手机方向改变"),
    SHAKE("摇一摇"),
    CLICK("点击")
}

// 附加条件：事件触发瞬间需满足的快照条件。
// 当前仅支持 ORIENTATION（param = DeviceOrientation.name），结构为未来扩展预留。
data class EventCondition(
    var type: EventType = EventType.ORIENTATION,
    var param: String = ""
)

// 事件驱动规则。
// 匹配流程：主事件匹配 -> 当前状态在 sourceStateIds -> 所有 conditions 满足 -> 跳转。
data class EventRule(
    var id: String = "",
    var type: EventType = EventType.ORIENTATION,
    var triggerParam: String = "",
    var conditions: MutableList<EventCondition> = mutableListOf(),
    var sourceStateIds: MutableList<String> = mutableListOf(),
    var targetStateId: String = ""
)

// ==================== 项目总配置 ====================

class ProjectConfig {
    var defaultStateId: String = ""
    val resources = mutableMapOf<String, ResourceItem>()
    val animations = mutableMapOf<String, AnimationItem>()
    val states = mutableMapOf<String, StateNode>()
    val eventRules = mutableListOf<EventRule>()

    // 引用完整性检查，返回问题列表（空表示健康）。
    fun validate(): List<String> {
        val issues = mutableListOf<String>()

        if (defaultStateId.isNotEmpty() && states[defaultStateId] == null) {
            issues.add("默认状态不存在: $defaultStateId")
        }
        for ((id, state) in states) {
            if (state.animationId.isEmpty() || animations[state.animationId] == null) {
                issues.add("状态 $id 引用的动画不存在: ${state.animationId}")
            }
            for (rule in state.rules) {
                if (states[rule.targetStateId] == null) {
                    issues.add("状态 $id 的转移目标不存在: ${rule.targetStateId}")
                }
            }
        }
        for ((id, anim) in animations) {
            for (ref in anim.resources) {
                if (resources[ref.resourceId] == null) {
                    issues.add("动画 $id 引用的资源不存在: ${ref.resourceId}")
                }
            }
        }
        for (rule in eventRules) {
            if (states[rule.targetStateId] == null) {
                issues.add("事件规则 ${rule.id} 的目标状态不存在: ${rule.targetStateId}")
            }
            for (sid in rule.sourceStateIds) {
                if (states[sid] == null) {
                    issues.add("事件规则 ${rule.id} 的触发状态不存在: $sid")
                }
            }
        }
        return issues
    }

    // ==================== 重排 ====================
    // 用于拖动排序。索引语义：toIndex 是"最终位置"（原数组视角）。
    // 内部先移除再插入，LinkedHashMap 保持插入顺序。

    fun reorderResources(from: Int, to: Int) {
        val list = resources.entries.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        resources.clear()
        for ((k, v) in list) resources[k] = v
    }

    fun reorderAnimations(from: Int, to: Int) {
        val list = animations.entries.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        animations.clear()
        for ((k, v) in list) animations[k] = v
    }

    fun reorderStates(from: Int, to: Int) {
        val list = states.entries.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        states.clear()
        for ((k, v) in list) states[k] = v
    }
}