package com.nqct.gifWallpaper

// 项目全量数据模型。

// ==================== 资源层 ====================

enum class ResourcePlaybackMode { NATIVE, FRAME_INTERVAL, TOTAL_DURATION }

data class ResourceItem(
    val id: String,
    val fileName: String,
    val info: GifInfo?,
    var playbackMode: ResourcePlaybackMode = ResourcePlaybackMode.NATIVE,
    var customValue: Int = 0
) {
    private fun frames(): Int = info?.frameCount?.coerceAtLeast(1) ?: 0

    private fun origAvgMs(): Long {
        val inf = info ?: return 0L
        val n = inf.frameCount.coerceAtLeast(1)
        return (inf.totalDelayMs / n).toLong().coerceAtLeast(1L)
    }

    fun targetFrameIntervalMs(): Long {
        val inf = info ?: return 33L
        val n = inf.frameCount.coerceAtLeast(1)
        val origAvg = origAvgMs()
        return when (playbackMode) {
            ResourcePlaybackMode.NATIVE -> origAvg
            ResourcePlaybackMode.FRAME_INTERVAL ->
                customValue.takeIf { it > 0 }?.toLong() ?: origAvg
            ResourcePlaybackMode.TOTAL_DURATION ->
                if (customValue > 0) (customValue.toLong() / n).coerceAtLeast(1L)
                else origAvg
        }
    }

    fun targetTotalDurationMs(): Long {
        val inf = info ?: return 0L
        val n = inf.frameCount.coerceAtLeast(1)
        return when (playbackMode) {
            ResourcePlaybackMode.NATIVE -> inf.totalDelayMs.toLong().coerceAtLeast(1L)
            ResourcePlaybackMode.FRAME_INTERVAL ->
                if (customValue > 0) (customValue.toLong() * n).coerceAtLeast(1L)
                else inf.totalDelayMs.toLong().coerceAtLeast(1L)
            ResourcePlaybackMode.TOTAL_DURATION ->
                customValue.takeIf { it > 0 }?.toLong() ?: inf.totalDelayMs.toLong().coerceAtLeast(1L)
        }
    }

    fun speedFactor(): Float {
        val inf = info ?: return 1f
        val origAvg = origAvgMs().toFloat()
        if (origAvg <= 0f) return 1f
        val target = targetFrameIntervalMs().toFloat()
        if (target <= 0f) return 1f
        return (origAvg / target).coerceIn(0.1f, 10f)
    }

    fun speedDescription(): String {
        val inf = info ?: return "原生速度"
        val n = inf.frameCount.coerceAtLeast(1)
        return when (playbackMode) {
            ResourcePlaybackMode.NATIVE -> "原生速度（${inf.totalDelayMs}ms）"
            ResourcePlaybackMode.FRAME_INTERVAL ->
                if (customValue <= 0) "原生速度"
                else "帧间隔 ${customValue}ms（总 ${customValue * n}ms）"
            ResourcePlaybackMode.TOTAL_DURATION ->
                if (customValue <= 0) "原生速度"
                else "总时长 ${customValue}ms（每帧约 ${customValue / n}ms）"
        }
    }
}

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
    var offsetY: Float = 0f,
    var startDelayMs: Int = 0
)

// ==================== 动画层 ====================

// 动画项。
//
// 【endTimeMs】多资源时的整圈时长（毫秒）。语义：
//   0              -> 自动 = max(每个资源.startDelayMs + 自身时长)
//   >0 且资源数 >1 -> 用户自定义整圈时长
//   单资源时忽略（无论填什么值都按资源自身时长）
//
// 用户自定义整圈时长时，若某个资源在 endTimeMs 之后仍在播，会被强制截断；
// 若所有资源都早于 endTimeMs 播完，中间的空窗期持续显示背景色。
data class AnimationItem(
    var id: String = "",
    var name: String = "",
    var backgroundColor: String = "",
    var resources: MutableList<AnimationResourceRef> = mutableListOf(),
    var endTimeMs: Int = 0
)

// ==================== 状态与转移规则 ====================

data class TransitionRule(
    var targetStateId: String = "",
    var weight: Int = 1
)

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

data class EventCondition(
    var type: EventType = EventType.ORIENTATION,
    var param: String = ""
)

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