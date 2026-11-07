package com.nqct.gifWallpaper

// 项目全量数据模型。
// 分层：ResourceItem <- AnimationResourceRef <- AnimationItem <- StateNode
// 引用：1 状态 -> 1 动画；1 动画 -> N 资源引用；1 资源 <- N 动画引用。

// ==================== 资源层 ====================

// 磁盘上的 GIF 静态资源。所有字段由文件决定，用户不可修改。
// 播放时的定位/缩放属于 AnimationResourceRef，不属于本类。
data class ResourceItem(
    val id: String,
    val fileName: String,
    val info: GifInfo?
)

// ==================== 9 点定位 ====================

// GIF 资源上的 9 个锚点，作为平移目标点与缩放中心。
// 归一化坐标：(0,0) 左上，(1,1) 右下。
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

// PERCENT：scaleValue 是相对原始尺寸的百分比（100 = 原始大小）。
// PIXEL：scaleValue 是目标宽度像素，高度按原始宽高比换算。
enum class ScaleMode { PERCENT, PIXEL }

// ==================== 动画内的资源引用 ====================

// 定位/缩放是动画的属性，不是资源的属性。
// 同一资源可被不同动画以不同定位/缩放引用。
data class AnimationResourceRef(
    var resourceId: String = "",
    // GIF 上的锚点：决定 GIF 的哪个点参与对齐 / 缩放中心
    var anchor: AnchorPoint = AnchorPoint.CENTER,
    // 屏幕目标锚点：决定 GIF 的 anchor 对齐到屏幕的哪个位置
    var targetAnchor: AnchorPoint = AnchorPoint.CENTER,
    var scaleMode: ScaleMode = ScaleMode.PERCENT,
    var scaleValue: Float = 100f,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f
)

// ==================== 动画层 ====================

// 动画项：一组资源引用。
// 当前简化：同一动画内多个资源同时开始、同时绘制，以第一个资源（主实例）的
// onAnimationEnd 作为一圈结束信号；各资源长度不一致时按最短者计。
// 后续可改为手动帧索引控制（共享解码数据，各自控制进度）。
//
// name：用户可编辑，展示时优先使用；为空时回退到 id。
// backgroundColor：十六进制字符串（#RRGGBB 或 #AARRGGBB）；空串 = 不自定义，回退黑色。
data class AnimationItem(
    var id: String = "",
    var name: String = "",
    var backgroundColor: String = "",
    var resources: MutableList<AnimationResourceRef> = mutableListOf()
)

// ==================== 状态与转移规则 ====================

// 按权重随机转移的规则。weight = 0 表示不会被抽到。
data class TransitionRule(
    var targetStateId: String = "",
    var weight: Int = 1
)

// 状态节点：引用一个动画 + 周期范围 + 概率转移规则。
// id 不可改（引用键）；name 用户可编辑，仅展示用。
// 周期 = 进入状态时从 [periodMin, periodMax] 随机抽取的目标圈数。
data class StateNode(
    var id: String = "",
    var name: String = "",
    var animationId: String = "",
    var periodMin: Int = 1,
    var periodMax: Int = 1,
    var rules: MutableList<TransitionRule> = mutableListOf()
)

// ==================== 设备方向 ====================

// 手机在空间中的 6 个朝向，外加未知/临界状态。
// 基于重力在设备坐标系的归一化分量判定：
//   平放：|gz| > 0.7
//   竖持：|gz| <= 0.7 且 |gy| > 0.7
//   横持：|gz| <= 0.7 且 |gy| <= 0.7 且 |gx| > 0.7
//   否则 UNKNOWN（临界/斜握）
// 物理含义：
//   SCREEN_UP      屏幕朝上（正面朝上）
//   SCREEN_DOWN    屏幕朝下（背面朝上）
//   PORTRAIT       竖屏（顶部朝上）
//   PORTRAIT_REVERSED 竖屏倒置（顶部朝下）
//   LANDSCAPE_LEFT 横屏（顶部朝左）
//   LANDSCAPE_RIGHT 横屏（顶部朝右）
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

// 支持的外部事件类型。
enum class EventType(val label: String) {
    ORIENTATION("手机方向改变"),
    SHAKE("摇一摇"),
    CLICK("点击")
}

// 附加条件：事件触发瞬间需满足的快照条件。
// 当前仅支持 ORIENTATION 类型（param = DeviceOrientation.name）；
// 结构上为未来扩展预留（如电量、时间、当前状态等）。
data class EventCondition(
    var type: EventType = EventType.ORIENTATION,
    var param: String = ""
)

// 事件驱动规则。
//
// 匹配流程：
//   1. 主事件（type + triggerParam）匹配
//   2. 当前状态在 sourceStateIds 中（空 = 任意状态）
//   3. 所有 conditions 在当前快照下满足
//   全部通过则跳转到 targetStateId。
//
// triggerParam：
//   - ORIENTATION: DeviceOrientation.name（如 "PORTRAIT"），语义为"变为该方向"
//   - SHAKE / CLICK: 忽略
//
// conditions：附加条件列表，每项独立判定（AND 关系）。
// 当前仅支持方向条件：param = DeviceOrientation.name，比较"事件触发瞬间的方向"。
//
// 后续可扩展：为规则增加概率 / 多目标，实现"按概率跳不同状态或干脆不跳"。
data class EventRule(
    var id: String = "",
    var type: EventType = EventType.ORIENTATION,
    var triggerParam: String = "",
    var conditions: MutableList<EventCondition> = mutableListOf(),
    var sourceStateIds: MutableList<String> = mutableListOf(),
    var targetStateId: String = ""
)

// ==================== 项目总配置 ====================

// 项目根配置，所有子表按 id 索引。
// 持久化到 filesDir/project.json；ResourceItem.info 是解析缓存，不写入 JSON。
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
}