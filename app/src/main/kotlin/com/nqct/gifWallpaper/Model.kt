package com.nqct.gifWallpaper

/**
 * 项目全量数据模型。
 *
 * 分层关系：
 *   ResourceItem  ←  AnimationResourceRef  ←  AnimationItem  ←  StateNode
 *   （磁盘 GIF）     （动画内的资源引用）      （动画）          （状态）
 *
 * 引用规则：
 *  - 一个状态引用一个动画（1:1）。
 *  - 一个动画可被多个状态引用（1:N）。
 *  - 一个动画可包含多个资源引用（1:N）。
 *  - 一个资源可被多个动画引用（1:N）。
 */

// ==================== 资源层 ====================

/**
 * 磁盘上的 GIF 静态资源。
 *
 * 【重要】所有字段均由文件本身决定，用户**不可修改**。
 * 用户对"播放时如何定位、如何缩放"的配置属于 [AnimationResourceRef]，不属于本类。
 *
 * @param id        稳定标识（文件名去后缀），同时作为磁盘文件名主体
 * @param fileName  完整磁盘文件名（含 .gif）
 * @param info      解析得到的元信息；解析失败时为 null
 */
data class ResourceItem(
    val id: String,
    val fileName: String,
    val info: GifInfo?
)

// ==================== 9 点定位 ====================

/**
 * GIF 资源上的 9 个锚点。
 *
 * 语义：
 *  - 平移：把该锚点平移到目标位置（如屏幕底部中点）。
 *  - 缩放：以该锚点为中心缩放。
 *  - 归一化坐标 (0f,0f) 为资源左上角，(1f,1f) 为右下角。
 *
 * 这两个用途都只是数学运算，不改变资源本身。
 */
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

    /** 归一化坐标：x 向右为正，y 向下为正，范围 0..1 */
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

/**
 * 缩放模式。
 *  - [PERCENT]：[AnimationResourceRef.scaleValue] 表示相对原始尺寸的百分比（100 = 原始大小）。
 *  - [PIXEL]  ：[AnimationResourceRef.scaleValue] 表示目标**宽度**像素；高度按原始宽高比自动换算。
 *
 * 之所以只存一个数值：比例与像素可互相换算，无需冗余存储，避免二者冲突。
 */
enum class ScaleMode { PERCENT, PIXEL }

// ==================== 动画内的资源引用 ====================

/**
 * 动画项对某个资源的一次引用。
 *
 * 【重要】定位点与缩放是**动画自己的属性**，不是资源属性。
 * 同一个资源可被不同动画以不同定位 / 缩放引用。
 *
 * @param resourceId 引用的 [ResourceItem.id]
 * @param anchor     9 点锚点：决定 GIF 的哪个点参与对齐 / 缩放中心
 * @param targetAnchor 9 点目标锚点：决定 GIF 的 anchor 对齐到屏幕的哪个位置
 * @param scaleMode  缩放模式
 * @param scaleValue PERCENT 时为百分比；PIXEL 时为目标宽度像素
 * @param offsetX    在锚点对齐后的额外水平微调（像素，正向右）
 * @param offsetY    在锚点对齐后的额外垂直微调（像素，正向下）
 */
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

/**
 * 动画项：一组资源的集合。
 *
 * 【当前处理逻辑（简化版）】
 *  - 一个动画内的多个资源**同时开始播放、同时绘制**。
 *  - 之所以这样处理：用户常见用法是"复用同一个动画多遍"，各资源长度一致，
 *    统一按主资源（第一个）的帧时间轴驱动即可。
 *  - 若各资源长度不一致，当前以**最短**的为准（见 [GifWallpaperService]）。
 *
 * 【后续可能扩展】
 *  - 不同资源独立的起播偏移 / 时间轴。
 *  - 按帧索引手动控制（替代多个 [android.graphics.drawable.AnimatedImageDrawable] 实例）。
 *
 * @param name            用户可编辑的名称。展示时优先使用；为空时回退到 [id]。
 * @param backgroundColor 背景填充色。十六进制字符串（`#RRGGBB` 或 `#AARRGGBB`）。
 *                        空字符串表示不自定义，渲染时回退为黑色。
 *                        无论用户以手动输入 / 吸管 / 参考色块哪种方式设置，
 *                        最终都存储为该字符串；手动输入在失焦时规范化。
 */
data class AnimationItem(
    var id: String = "",
    var name: String = "",
    var backgroundColor: String = "",
    var resources: MutableList<AnimationResourceRef> = mutableListOf()
)

// ==================== 状态与转移规则 ====================

/**
 * 按权重随机转移的规则。
 * @param targetStateId 目标状态 ID
 * @param weight        权重（>=0；0 表示不会被抽到）
 */
data class TransitionRule(
    var targetStateId: String = "",
    var weight: Int = 1
)

/**
 * 状态节点：引用一个动画 + 周期范围 + 概率转移规则。
 *
 * @param id          稳定标识。**不可修改**：转移规则、事件规则、默认状态都以它为引用键。
 * @param name        用户可编辑的名称。展示时优先使用；为空或与 [id] 相同时回退到 [id]。
 * @param animationId 引用的 [AnimationItem.id]
 * @param periodMin   进入状态时随机抽取的周期下限（圈数）
 * @param periodMax   周期上限（圈数）
 *
 * 【周期语义】当前“周期”= 还要播放所引用动画多少**圈**。
 * 每播完一圈减 1；减到 0 触发概率转移。进入状态时从 [periodMin, periodMax] 随机取值。
 *
 * 【后续可能扩展】周期改为时长；或状态内不同动画各有独立周期。
 * 当前因状态引用多个动画时各动画长度一致，统一按圈数计。
 */
data class StateNode(
    var id: String = "",
    var name: String = "",
    var animationId: String = "",
    var periodMin: Int = 1,
    var periodMax: Int = 1,
    var rules: MutableList<TransitionRule> = mutableListOf()
)

// ==================== 事件规则 ====================

/** 支持的外部事件类型。 */
enum class EventType(val label: String) {
    ORIENTATION("手机方向改变"),
    SHAKE("摇一摇"),
    CLICK("点击")
}

/**
 * 事件驱动规则。
 *
 * 【当前处理逻辑】**确定性**：只要事件匹配，且当前状态在 [sourceStateIds] 中，
 * 就跳转到 [targetStateId]，不经过权重随机。
 *
 * 【后续可能扩展】为事件规则也增加概率 / 多目标，实现“按概率跳不同状态或干脆不跳”。
 * 当前先保持确定性，逻辑简单且易于用户理解。
 *
 * @param type          事件类型
 * @param triggerParam  触发条件参数：
 *                      - ORIENTATION: [ORIENTATION_FORWARD] / [ORIENTATION_REVERSE]
 *                      - SHAKE / CLICK: 忽略
 * @param sourceStateIds 触发状态集合；**空集合表示“任意状态”**
 * @param targetStateId  目标状态 ID
 */
data class EventRule(
    var id: String = "",
    var type: EventType = EventType.ORIENTATION,
    var triggerParam: String = "",
    var sourceStateIds: MutableList<String> = mutableListOf(),
    var targetStateId: String = ""
) {
    companion object {
        /** 正向放置（屏幕朝上） */
        const val ORIENTATION_FORWARD = "FORWARD"
        /** 反向放置（屏幕朝下） */
        const val ORIENTATION_REVERSE = "REVERSE"
    }
}

// ==================== 项目总配置 ====================

/**
 * 项目根配置。所有子表按 id 索引，便于 O(1) 查找与引用完整性校验。
 *
 * 持久化到 filesDir/project.json。
 * [ResourceItem.info] 是解析缓存，**不写入 JSON**，加载时重新解析磁盘文件。
 */
class ProjectConfig {
    var defaultStateId: String = ""
    val resources = mutableMapOf<String, ResourceItem>()
    val animations = mutableMapOf<String, AnimationItem>()
    val states = mutableMapOf<String, StateNode>()
    val eventRules = mutableListOf<EventRule>()

    /** 引用完整性检查，返回发现的问题列表（空表示健康）。 */
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