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

// 状态图自定义 View。
//
// 【布局】节点按状态数均分在圆周上。半径 R = max(240, 240 / sin(π/n))，
//   相邻弦长恒为 480，保证 n 从 2 到 15 之间相邻节点间距足够。
//
// 【颜色】莫兰迪低饱和多色方案。预设 12 色，按状态顺序取；
//   状态数 > 12 时用黄金角动态生成（饱和度 18%~30%，明度 62%~73%）。
//
// 【概率标签】无背景衬托。位置在箭头的尾巴（沿线段方向往起点一侧偏移），
//   文字颜色为源色加深（×0.55），确保压在线段上仍清晰可读。
//
// 【手势】单指拖动、双指缩放、单击空白清高亮、双击节点跳编辑。
class StateGraphView(context: Context) : View(context) {

    // ==================== 数据 ====================
    private var states: List<StateNode> = emptyList()
    private var animations: Map<String, AnimationItem> = emptyMap()
    private var defaultStateId: String = ""

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

    // ==================== 调色板（莫兰迪 12 色） ====================
    // 低饱和（18%~30%）、中高明度（62%~73%）的多色。
    // 状态数 ≤ 8 时用前 8 个（色相分散）；9~12 用后 4 个深色版本。
    // 状态数 > 12 时不循环，走 generateMorandiColor 动态生成。
    private val stateColorPalette = intArrayOf(
        Color.parseColor("#A8B8C8"), // 0  灰蓝
        Color.parseColor("#C8B8A8"), // 1  灰驼
        Color.parseColor("#B8C8A8"), // 2  灰绿
        Color.parseColor("#C8A8B8"), // 3  灰粉
        Color.parseColor("#A8C8C8"), // 4  灰青
        Color.parseColor("#C8C0A8"), // 5  灰卡其
        Color.parseColor("#B0A8C8"), // 6  灰紫
        Color.parseColor("#C8B0A0"), // 7  灰陶
        Color.parseColor("#9EB0C0"), // 8  深灰蓝
        Color.parseColor("#C0A090"), // 9  深灰驼
        Color.parseColor("#90A898"), // 10 深灰绿
        Color.parseColor("#B090A8")  // 11 深灰粉
    )

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
    // 概率标签文字画笔：无背景，颜色在绘制时按源色加深动态设置
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#333333")
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

    // 圆周布局，半径随状态数增长：R = max(240, 240 / sin(π/n))
    // 相邻弦长恒为 480，n 从 2 到 15 之间都足够容纳箭头与标签。
    private fun layoutNodes() {
        nodePositions.clear()
        if (states.isEmpty()) return
        if (states.size == 1) {
            nodePositions[states[0].id] = PointF(0f, 0f)
            return
        }
        val n = states.size
        val sinHalf = sin(PI / n).toFloat().coerceAtLeast(1e-3f)
        val radius = maxOf(240f, 240f / sinHalf)
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

        for ((stateIdx, state) in states.withIndex()) {
            val rules = state.rules
            if (rules.isEmpty()) continue
            val totalWeight = rules.sumOf { it.weight.coerceAtLeast(0) }
            if (totalWeight <= 0) continue

            val sourceColor = colorForState(stateIdx)

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
                        fillPaint = arrowFillPaint,
                        sourceColor = sourceColor
                    )
                } else {
                    val hasReverse = states.firstOrNull { it.id == toId }
                        ?.rules?.any { it.targetStateId == state.id } == true
                    drawStraightArrow(
                        canvas, fromPos, toPos,
                        probability = rule.weight.toFloat() / totalWeight,
                        hasReverse = hasReverse,
                        linePaint = arrowLinePaint,
                        fillPaint = arrowFillPaint,
                        sourceColor = sourceColor
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
                fillPaint = eventFillPaint,
                sourceColor = Color.parseColor("#FF6D00")
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

    // ==================== 直线箭头（带概率标签） ====================

    // 箭头在中点；标签在箭头**尾巴**（沿线段方向往起点一侧偏移），无背景。
    private fun drawStraightArrow(
        canvas: Canvas,
        from: PointF,
        to: PointF,
        probability: Float,
        hasReverse: Boolean,
        linePaint: Paint,
        fillPaint: Paint,
        sourceColor: Int
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

        val sx = from.x + px * lineOffset
        val sy = from.y + py * lineOffset
        val ex = to.x + px * lineOffset
        val ey = to.y + py * lineOffset

        val oldLineColor = linePaint.color
        val oldFillColor = fillPaint.color
        linePaint.color = sourceColor
        fillPaint.color = sourceColor

        canvas.drawLine(sx, sy, ex, ey, linePaint)

        val mx = (sx + ex) / 2f
        val my = (sy + ey) / 2f
        drawArrowHead(canvas, mx, my, ux, uy, fillPaint)

        // 标签放在箭头尾巴：从箭头中心沿反方向偏移 (箭头半长 + 余量)
        val backOffset = arrowHeadLen / 2f + 14f
        val lx = mx - ux * backOffset
        val ly = my - uy * backOffset
        drawProbabilityLabel(canvas, formatProbability(probability), lx, ly, sourceColor)

        linePaint.color = oldLineColor
        fillPaint.color = oldFillColor
    }

    // ==================== 自环 ====================

    private fun drawSelfLoop(
        canvas: Canvas,
        nodePos: PointF,
        weight: Int,
        totalWeight: Int,
        linePaint: Paint,
        fillPaint: Paint,
        sourceColor: Int
    ) {
        val r = 28f
        val cx = nodePos.x - nodeWidth / 2f
        val cy = nodePos.y - nodeHeight / 2f

        // 源色与白色 50% 混合，得到浅化色，视觉更轻
        val rr = (sourceColor ushr 16) and 0xFF
        val gg = (sourceColor ushr 8) and 0xFF
        val bb = sourceColor and 0xFF
        val lightColor = Color.rgb((rr + 255) / 2, (gg + 255) / 2, (bb + 255) / 2)

        val oldLineColor = linePaint.color
        val oldFillColor = fillPaint.color
        linePaint.color = lightColor
        fillPaint.color = lightColor

        canvas.drawCircle(cx, cy, r, linePaint)

        val arrowAngle = Math.toRadians(135.0)
        val ax = cx + (r * cos(arrowAngle)).toFloat()
        val ay = cy - (r * sin(arrowAngle)).toFloat()
        val tangentAngle = Math.toRadians(225.0)
        val ux = cos(tangentAngle).toFloat()
        val uy = -sin(tangentAngle).toFloat()
        drawArrowHead(canvas, ax, ay, ux, uy, fillPaint)

        // 标签放在箭头尾巴：从箭头中心沿反方向偏移 (箭头半长 + 余量)
        val prob = weight.toFloat() / totalWeight.coerceAtLeast(1)
        val backOffset = arrowHeadLen / 2f + 14f
        val lx = ax - ux * backOffset
        val ly = ay - uy * backOffset
        drawProbabilityLabel(canvas, formatProbability(prob), lx, ly, sourceColor)

        linePaint.color = oldLineColor
        fillPaint.color = oldFillColor
    }

    // ==================== 绘制辅助 ====================

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

    // 概率标签：无背景，直接画深色文字。
    // 颜色 = 源色 × 0.55（加深），确保压在线段上仍可读。
    private fun drawProbabilityLabel(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        sourceColor: Int
    ) {
        val r = (sourceColor ushr 16) and 0xFF
        val g = (sourceColor ushr 8) and 0xFF
        val b = sourceColor and 0xFF
        val darkColor = Color.rgb(
            (r * 0.55f).toInt().coerceIn(0, 255),
            (g * 0.55f).toInt().coerceIn(0, 255),
            (b * 0.55f).toInt().coerceIn(0, 255)
        )

        val oldColor = labelTextPaint.color
        labelTextPaint.color = darkColor

        val fm = labelTextPaint.fontMetrics
        val baselineY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baselineY, labelTextPaint)

        labelTextPaint.color = oldColor
    }

    // ==================== 颜色生成 ====================

    private fun colorForState(stateIndex: Int): Int =
        if (stateIndex < stateColorPalette.size) {
            stateColorPalette[stateIndex]
        } else {
            generateMorandiColor(stateIndex)
        }

    private fun generateMorandiColor(index: Int): Int {
        val goldenAngle = 137.507764f
        val hue = (index * goldenAngle) % 360f
        val saturation = (18f + (index * 7) % 13) / 100f
        val lightness = (62f + (index * 5) % 12) / 100f
        return hslToColor(hue, saturation, lightness)
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val r: Float
        val g: Float
        val b: Float
        when {
            h < 60f  -> { r = c; g = x; b = 0f }
            h < 120f -> { r = x; g = c; b = 0f }
            h < 180f -> { r = 0f; g = c; b = x }
            h < 240f -> { r = 0f; g = x; b = c }
            h < 300f -> { r = x; g = 0f; b = c }
            else     -> { r = c; g = 0f; b = x }
        }
        return Color.rgb(
            ((r + m) * 255f).toInt().coerceIn(0, 255),
            ((g + m) * 255f).toInt().coerceIn(0, 255),
            ((b + m) * 255f).toInt().coerceIn(0, 255)
        )
    }

    // ==================== 概率格式化 ====================

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