package com.nqct.gifWallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 状态图自定义 View。
 *
 * 【职责】把状态机可视化为可拖动/缩放的关系图：
 *  - 圆形布局：状态按数量均分在圆周上。
 *  - 节点：圆角矩形，显示 状态名 / 动画名 / 周期。
 *  - 转移箭头：从起点中心到终点中心，**箭头固定在连线的正中点**，概率标签放在箭头旁。
 *    节点在箭头之后绘制，覆盖线段两端，视觉上形成"节点到节点"的连接，无需计算边界交点。
 *  - 双向边：同时存在 A→B 与 B→A 时，两条线各向垂直方向偏移一侧，避免重叠。
 *  - 自环：圆心固定在节点**左上角顶点**，半径较小、颜色较浅。圆完整绘制，
 *    右下 1/4 被节点覆盖后形成圆弧。箭头放在数学 135°（屏幕左上）位置的圆上，
 *    方向为切线方向（数学 225°，屏幕左下）。概率标签沿 135° 方向外移，避免压住箭头。
 *  - 默认状态：绿色边框 + 浅绿背景高亮。
 *  - 事件高亮：设置 [highlightedEventRule] 后隐藏所有周期箭头，只画该事件相关的橙色虚线箭头。
 *
 * 【手势】
 *  - 单指拖动：平移。
 *  - 双指：缩放（0.3x ~ 3x），围绕双指焦点。
 *  - 单击空白（无节点）：若有事件高亮则清除，否则无动作。
 *  - 双击节点：触发 [onNodeDoubleTap]。
 *
 * 【坐标系统】所有几何尺寸在世界坐标下固定（节点宽高、布局半径等）；
 * 绘制时统一通过 Canvas 平移 + 缩放。线宽 / 箭头 / 文字标签则按 1/scale 反算，
 * 保证在不同缩放级别下视觉粗细和大小保持稳定。
 *
 * 【当前状态说明】本 View 只反映"配置"，不显示运行时正在播放的状态。
 */
class StateGraphView(context: Context) : View(context) {

    // ==================== 数据 ====================
    private var states: List<StateNode> = emptyList()
    private var animations: Map<String, AnimationItem> = emptyMap()
    private var defaultStateId: String = ""

    /** 当前高亮的事件规则；null 表示显示周期箭头。 */
    var highlightedEventRule: EventRule? = null
        private set

    // ==================== 布局常量（世界坐标） ====================
    private val nodeWidth = 200f
    private val nodeHeight = 130f
    private val nodeCorner = 16f

    // ==================== 节点位置 ====================
    private val nodePositions = mutableMapOf<String, PointF>()

    // ==================== 变换 ====================
    private var translateX = 0f
    private var translateY = 0f
    private var scaleFactor = 1f
    private var hasInitialFit = false

    // ==================== 手势 ====================
    private var onNodeDoubleTap: ((String) -> Unit)? = null

    // ==================== 画笔 ====================
    private val nodeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }
    private val animPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.GRAY
    }
    private val periodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#2196F3")
    }
    private val arrowLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#616161")
    }
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#616161")
    }
    private val eventLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF6D00")
    }
    private val eventFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF6D00")
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#212121")
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EEEEEE")
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.GRAY
        textSize = 40f
    }

    // 视觉尺寸（每次 onDraw 按 1/scale 更新）
    private var arrowStrokeW = 3f
    private var arrowHeadLen = 16f
    private var arrowHeadWidth = 12f
    private var labelTextSize = 20f
    private var labelPadH = 8f
    private var labelPadV = 4f
    private var arrowDash = floatArrayOf(12f, 8f)

    // ==================== 手势识别 ====================
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scaleFactor
            val new = (scaleFactor * detector.scaleFactor).coerceIn(0.3f, 3f)
            if (abs(new - old) < 1e-4f) return true
            val fx = detector.focusX
            val fy = detector.focusY
            translateX = fx - (fx - translateX) * (new / old)
            translateY = fy - (fy - translateY) * (new / old)
            scaleFactor = new
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
        ): Boolean {
            if (scaleDetector.isInProgress) return false
            translateX -= distanceX
            translateY -= distanceY
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (highlightedEventRule != null && findNodeAt(e.x, e.y) == null) {
                highlightedEventRule = null
                invalidate()
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val node = findNodeAt(e.x, e.y) ?: return false
            onNodeDoubleTap?.invoke(node.id)
            return true
        }
    })

    // ==================== 公共 API ====================

    fun setData(
        states: List<StateNode>,
        animations: Map<String, AnimationItem>,
        defaultStateId: String
    ) {
        this.states = states
        this.animations = animations
        this.defaultStateId = defaultStateId
        layoutNodes()
        post { if (!hasInitialFit) { hasInitialFit = true; fitToScreen() } }
        invalidate()
    }

    fun setOnNodeDoubleTapListener(listener: ((String) -> Unit)?) {
        onNodeDoubleTap = listener
    }

    fun setHighlightedEventRule(rule: EventRule?) {
        if (highlightedEventRule?.id == rule?.id) return
        highlightedEventRule = rule
        invalidate()
    }

    fun fitToScreen() {
        if (width <= 0 || height <= 0 || states.isEmpty()) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (pos in nodePositions.values) {
            minX = minOf(minX, pos.x - nodeWidth / 2)
            minY = minOf(minY, pos.y - nodeHeight / 2)
            maxX = maxOf(maxX, pos.x + nodeWidth / 2)
            maxY = maxOf(maxY, pos.y + nodeHeight / 2)
        }
        val worldW = (maxX - minX).coerceAtLeast(1f)
        val worldH = (maxY - minY).coerceAtLeast(1f)
        val pad = 60f
        val s = minOf(
            (width - pad * 2) / worldW,
            (height - pad * 2) / worldH
        ).coerceIn(0.3f, 1.6f)
        scaleFactor = s
        val wcx = (minX + maxX) / 2f
        val wcy = (minY + maxY) / 2f
        translateX = width / 2f - wcx * s
        translateY = height / 2f - wcy * s
        invalidate()
    }

    // ==================== 布局 ====================

    private fun layoutNodes() {
        nodePositions.clear()
        if (states.isEmpty()) return
        if (states.size == 1) {
            nodePositions[states[0].id] = PointF(0f, 0f)
            return
        }
        val n = states.size
        val minR = nodeWidth * 1.3f / (2f * sin(PI / n).toFloat())
        val radius = maxOf(minR, 300f)
        for (i in 0 until n) {
            val angle = -PI / 2 + 2 * PI * i / n
            nodePositions[states[i].id] = PointF(
                (radius * cos(angle)).toFloat(),
                (radius * sin(angle)).toFloat()
            )
        }
    }

    // ==================== 触摸 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (states.isEmpty()) {
            canvas.drawText("暂无状态", width / 2f, height / 2f, hintPaint)
            return
        }

        updateVisualSizes()

        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleFactor, scaleFactor)

        val ev = highlightedEventRule
        if (ev != null) {
            drawEventArrows(canvas, ev)
        } else {
            drawTransitionArrows(canvas)
        }

        for (state in states) {
            drawNode(canvas, state)
        }

        canvas.restore()
    }

    private fun updateVisualSizes() {
        val inv = 1f / scaleFactor
        arrowStrokeW = 3f * inv
        arrowHeadLen = 16f * inv
        arrowHeadWidth = 12f * inv
        labelTextSize = 20f * inv
        labelPadH = 8f * inv
        labelPadV = 4f * inv
        arrowDash = floatArrayOf(12f * inv, 8f * inv)

        arrowLinePaint.strokeWidth = arrowStrokeW
        eventLinePaint.strokeWidth = arrowStrokeW
        labelTextPaint.textSize = labelTextSize
    }

    // ==================== 节点绘制 ====================

    private fun drawNode(canvas: Canvas, state: StateNode) {
        val pos = nodePositions[state.id] ?: return
        val isDefault = state.id == defaultStateId

        val rect = RectF(
            pos.x - nodeWidth / 2,
            pos.y - nodeHeight / 2,
            pos.x + nodeWidth / 2,
            pos.y + nodeHeight / 2
        )

        nodeBgPaint.color = if (isDefault) Color.parseColor("#E8F5E9") else Color.WHITE
        canvas.drawRoundRect(rect, nodeCorner, nodeCorner, nodeBgPaint)

        nodeBorderPaint.color = if (isDefault) Color.parseColor("#4CAF50") else Color.parseColor("#BDBDBD")
        nodeBorderPaint.strokeWidth = if (isDefault) 6f else 3f
        canvas.drawRoundRect(rect, nodeCorner, nodeCorner, nodeBorderPaint)

        val cx = pos.x
        val maxTextW = nodeWidth - 20f

        namePaint.textSize = 26f
        val stateText = state.name.ifBlank { state.id }
        canvas.drawText(ellipsize(stateText, namePaint, maxTextW), cx, pos.y - 30f, namePaint)

        animPaint.textSize = 20f
        val anim = animations[state.animationId]
        val animText = if (anim != null) formatAnimDisplay(anim) else "（无动画）"
        canvas.drawText(ellipsize(animText, animPaint, maxTextW), cx, pos.y + 5f, animPaint)

        periodPaint.textSize = 20f
        canvas.drawText(
            "${state.periodMin}~${state.periodMax} 圈",
            cx, pos.y + 40f, periodPaint
        )
    }

    // ==================== 周期转移箭头 ====================

    private fun drawTransitionArrows(canvas: Canvas) {
        arrowLinePaint.pathEffect = null
        arrowFillPaint.color = Color.parseColor("#616161")

        for (state in states) {
            val rules = state.rules
            if (rules.isEmpty()) continue
            val totalWeight = rules.sumOf { it.weight.coerceAtLeast(0) }
            if (totalWeight <= 0) continue
            for (rule in rules) {
                val toId = rule.targetStateId
                val fromPos = nodePositions[state.id] ?: continue
                val toPos = nodePositions[toId] ?: continue

                if (state.id == toId) {
                    drawSelfLoop(
                        canvas, fromPos,
                        weight = rule.weight,
                        totalWeight = totalWeight,
                        linePaint = arrowLinePaint,
                        fillPaint = arrowFillPaint
                    )
                } else {
                    val hasReverse = states.firstOrNull { it.id == toId }
                        ?.rules?.any { it.targetStateId == state.id } == true
                    drawStraightArrow(
                        canvas, fromPos, toPos,
                        probability = rule.weight.toFloat() / totalWeight,
                        hasReverse = hasReverse,
                        linePaint = arrowLinePaint,
                        fillPaint = arrowFillPaint
                    )
                }
            }
        }
    }

    // ==================== 事件箭头 ====================

    private fun drawEventArrows(canvas: Canvas, rule: EventRule) {
        val targetId = rule.targetStateId
        val targetPos = nodePositions[targetId] ?: return

        eventLinePaint.pathEffect = DashPathEffect(arrowDash, 0f)
        eventFillPaint.color = Color.parseColor("#FF6D00")

        val sources = if (rule.sourceStateIds.isEmpty()) {
            states.map { it.id }.filter { it != targetId }
        } else {
            rule.sourceStateIds.filter { it != targetId && nodePositions.containsKey(it) }
        }

        for (srcId in sources) {
            val fromPos = nodePositions[srcId] ?: continue
            drawEventStraightArrow(canvas, fromPos, targetPos)
        }

        val includeSelf = rule.sourceStateIds.isEmpty() || targetId in rule.sourceStateIds
        if (includeSelf) {
            drawSelfLoop(
                canvas, targetPos,
                weight = 1, totalWeight = 1,
                linePaint = eventLinePaint,
                fillPaint = eventFillPaint
            )
        }
    }

    private fun drawEventStraightArrow(canvas: Canvas, from: PointF, to: PointF) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len

        canvas.drawLine(from.x, from.y, to.x, to.y, eventLinePaint)
        drawArrowHead(canvas, (from.x + to.x) / 2, (from.y + to.y) / 2, ux, uy, eventFillPaint)
    }

    // ==================== 直线箭头（带概率） ====================

    /**
     * 从 [from] 中心到 [to] 中心绘制箭头：
     *  - 线段本身被节点覆盖两端，只中间部分可见。
     *  - 箭头固定在**中点**，方向 = 起点→终点。
     *  - 概率标签在中点垂直方向偏移，避免压住箭头。
     *  - 双向边时偏移 12px，两条线分居两侧，标签同理。
     */
    private fun drawStraightArrow(
        canvas: Canvas,
        from: PointF,
        to: PointF,
        probability: Float,
        hasReverse: Boolean,
        linePaint: Paint,
        fillPaint: Paint
    ) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return

        val ux = dx / len
        val uy = dy / len
        val px = -uy
        val py = ux

        val lineOffset = if (hasReverse) 12f else 0f
        val labelOffset = 32f

        val sx = from.x + px * lineOffset
        val sy = from.y + py * lineOffset
        val ex = to.x + px * lineOffset
        val ey = to.y + py * lineOffset

        canvas.drawLine(sx, sy, ex, ey, linePaint)

        val mx = (sx + ex) / 2f
        val my = (sy + ey) / 2f
        drawArrowHead(canvas, mx, my, ux, uy, fillPaint)

        val lx = mx + px * labelOffset
        val ly = my + py * labelOffset
        drawProbabilityLabel(canvas, formatProbability(probability), lx, ly)
    }

    // ==================== 自环 ====================

    /**
     * 自环。
     *
     * 【几何】
     *  - 圆心固定在节点**左上角顶点** (pos.x - nodeWidth/2, pos.y - nodeHeight/2)。
     *    完整圆绘制后，右下 1/4 被节点圆角矩形覆盖，自然形成圆弧。
     *    放在角上便于计算，不随文字宽度变化。
     *  - 半径 28f，比主线箭头小，视觉更轻。
     *  - 颜色调浅：周期自环 #9E9E9E，事件自环 #FFB74D，弱化次要元素。
     *  - 箭头位置：数学 135°（屏幕左上方）的圆上；
     *    方向：数学 225°（屏幕左下方），即该点的切线方向，与半径垂直。
     *  - 概率标签沿 135° 方向外移（r + 30），避免压住箭头。
     *
     * 【绘制顺序】自环在节点之前绘制，节点覆盖其右下部分。
     */
    private fun drawSelfLoop(
        canvas: Canvas,
        nodePos: PointF,
        weight: Int,
        totalWeight: Int,
        linePaint: Paint,
        fillPaint: Paint
    ) {
        val r = 28f
        val cx = nodePos.x - nodeWidth / 2f
        val cy = nodePos.y - nodeHeight / 2f

        val oldLineColor = linePaint.color
        val oldFillColor = fillPaint.color
        val lightColor = if (linePaint === eventLinePaint) {
            Color.parseColor("#FFB74D")
        } else {
            Color.parseColor("#9E9E9E")
        }
        linePaint.color = lightColor
        fillPaint.color = lightColor

        canvas.drawCircle(cx, cy, r, linePaint)

        // 箭头：数学 135° 位置（屏幕左上）
        val arrowAngle = Math.toRadians(135.0)
        val ax = cx + (r * cos(arrowAngle)).toFloat()
        val ay = cy - (r * sin(arrowAngle)).toFloat()
        // 切向：数学 225°（屏幕左下）
        val tangentAngle = Math.toRadians(225.0)
        val ux = cos(tangentAngle).toFloat()
        val uy = -sin(tangentAngle).toFloat()
        drawArrowHead(canvas, ax, ay, ux, uy, fillPaint)

        // 概率标签：沿 135° 外移，避免压住箭头
        val prob = weight.toFloat() / totalWeight.coerceAtLeast(1)
        val labelOffset = r + 30f
        val lx = cx + (labelOffset * cos(arrowAngle)).toFloat()
        val ly = cy - (labelOffset * sin(arrowAngle)).toFloat()
        drawProbabilityLabel(canvas, formatProbability(prob), lx, ly)

        linePaint.color = oldLineColor
        fillPaint.color = oldFillColor
    }

    // ==================== 绘制辅助 ====================

    /** 在 [cx,cy] 位置沿 [ux,uy] 方向绘制一个三角形箭头（中点为中心）。 */
    private fun drawArrowHead(
        canvas: Canvas,
        cx: Float, cy: Float,
        ux: Float, uy: Float,
        fillPaint: Paint
    ) {
        val half = arrowHeadLen / 2f
        val tipX = cx + ux * half
        val tipY = cy + uy * half
        val backX = cx - ux * half
        val backY = cy - uy * half
        val px = -uy
        val py = ux
        val w = arrowHeadWidth / 2f

        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(backX + px * w, backY + py * w)
        path.lineTo(backX - px * w, backY - py * w)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawProbabilityLabel(canvas: Canvas, text: String, cx: Float, cy: Float) {
        val w = labelTextPaint.measureText(text)
        val h = labelTextSize
        val rect = RectF(
            cx - w / 2f - labelPadH,
            cy - h / 2f - labelPadV,
            cx + w / 2f + labelPadH,
            cy + h / 2f + labelPadV
        )
        canvas.drawRoundRect(rect, 6f, 6f, labelBgPaint)

        val fm = labelTextPaint.fontMetrics
        val baselineY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baselineY, labelTextPaint)
    }

    // ==================== 概率格式化 ====================

    /**
     * 概率格式化：最多保留两位小数，去掉尾随 0，整数不带小数点。
     * 0.1234 → "0.12"，0.5 → "0.5"，1.0 → "1"，0.1 → "0.1"。
     */
    private fun formatProbability(p: Float): String {
        val s = String.format(Locale.US, "%.2f", p)
        if (!s.contains('.')) return s
        val trimmed = s.trimEnd('0')
        return if (trimmed.endsWith(".")) trimmed.dropLast(1) else trimmed
    }

    // ==================== 文字省略 ====================

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ew = paint.measureText(ellipsis)
        if (ew > maxWidth) return ellipsis
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(text, 0, mid) + ew <= maxWidth) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + ellipsis
    }

    // ==================== 坐标转换与命中检测 ====================

    private fun screenToWorld(sx: Float, sy: Float): PointF =
        PointF((sx - translateX) / scaleFactor, (sy - translateY) / scaleFactor)

    private fun findNodeAt(sx: Float, sy: Float): StateNode? {
        val wp = screenToWorld(sx, sy)
        return states.firstOrNull { state ->
            val pos = nodePositions[state.id] ?: return@firstOrNull false
            abs(wp.x - pos.x) <= nodeWidth / 2 && abs(wp.y - pos.y) <= nodeHeight / 2
        }
    }
}