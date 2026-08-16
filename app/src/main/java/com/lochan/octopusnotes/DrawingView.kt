package com.lochan.octopusnotes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@SuppressLint("ClickableViewAccessibility")
class DrawingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class FingerAction { SCROLL, DRAW, IGNORED }
    enum class Tool { PEN, PIXEL_ERASER, STROKE_ERASER, LASSO, HIGHLIGHTER, SHAPE, TABLE, LASER, TEXT, MEASURE, TAPE }

    enum class ResizeSide { NONE, LEFT, RIGHT, TOP, BOTTOM }

    data class SelectionTransform(
        val dx: Float,
        val dy: Float,
        val scaleX: Float,
        val scaleY: Float,
        val pivotXFrac: Float = 0f,
        val pivotYFrac: Float = 0f
    )

    var singleFingerAction: FingerAction = FingerAction.SCROLL
    private var currentTool = Tool.PEN
    private var brushColor = Color.BLACK
    private var brushSize = 10f

    var penLineStyle: String = PenLineStyle.SOLID
        private set

    var lassoShape: String = LassoShape.FREE
        private set

    var shapeType: String = ShapeType.RECT
        private set
    private var isDrawingMode = false
    private var selectionPageOffset = 0f

    private var selectionScrollDx = 0f
    private var selectionScrollDy = 0f

    private var currentPath = Path()
    private val currentPoints = mutableListOf<PointF>()
    private val stabilizer = StrokeStabilization()

    private var shapeAnchor: PointF? = null

    var tableGridRows = 3
    var tableGridCols = 3

    var tapePattern = TapePattern.SOLID
    var tapeWidth = 32f
    private val tapePreviewPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = (0x99 shl 24) or (Color.BLACK and 0xFFFFFF)
    }

    private class LaserTrailPoint(val x: Float, val y: Float, val t: Long)
    private val laserTrail = mutableListOf<LaserTrailPoint>()
    private var laserFramePosted = false
    private val laserChoreographer = android.view.Choreographer.getInstance()
    private val LASER_FADE_MS = 1200L
    private val laserFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            laserFramePosted = false
            val now = android.os.SystemClock.uptimeMillis()
            laserTrail.removeAll { now - it.t >= LASER_FADE_MS }
            if (laserTrail.isNotEmpty()) {
                laserFramePosted = true
                laserChoreographer.postFrameCallback(this)
            }
            invalidate()
        }
    }

    private fun ensureLaserFrame() {
        if (!laserFramePosted) {
            laserFramePosted = true
            laserChoreographer.postFrameCallback(laserFrameCallback)
        }
    }

    private val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val holdRunnable = Runnable { onHoldFired() }
    private var holdAnchorX = 0f
    private var holdAnchorY = 0f
    private var strokeInProgress = false
    private var shapeSnapped = false

    private var movingStroke = false

    private var moveLastX = 0f
    private var moveLastY = 0f
    private val holdSlop by lazy { 9f * resources.displayMetrics.density }
    private val HOLD_TO_SHAPE_MS = 550L

    private val minHoldSnapLength: Float get() = 64f * resources.displayMetrics.density

    private fun strokeLineTo(x: Float, y: Float) {
        if (currentPoints.isEmpty()) currentPath.moveTo(x, y) else currentPath.lineTo(x, y)
    }

    private fun currentStrokeLength(): Float {
        var len = 0f
        var prev: PointF? = null
        for (p in currentPoints) {
            prev?.let { len += hypot(p.x - it.x, p.y - it.y) }
            prev = p
        }
        return len
    }

    private fun startHoldTracking(x: Float, y: Float) {
        holdAnchorX = x; holdAnchorY = y
        holdHandler.removeCallbacks(holdRunnable)
        holdHandler.postDelayed(holdRunnable, HOLD_TO_SHAPE_MS)
    }

    private fun updateHoldTracking(x: Float, y: Float) {
        if (kotlin.math.hypot(x - holdAnchorX, y - holdAnchorY) > holdSlop) {
            startHoldTracking(x, y)
        }
    }

    private fun cancelHoldTracking() {
        holdHandler.removeCallbacks(holdRunnable)
    }

    private fun onHoldFired() {
        if (!strokeInProgress || shapeSnapped || movingStroke) return

        if (currentTool == Tool.PEN) {

            if (currentStrokeLength() < minHoldSnapLength) return
            val shape = ShapeRecognizer.recognize(currentPoints)
            if (shape != null) {

                currentPoints.clear()
                currentPoints.addAll(shape)
                currentPath.reset()
                currentPath.moveTo(shape[0].x, shape[0].y)
                for (i in 1 until shape.size) currentPath.lineTo(shape[i].x, shape[i].y)
                shapeSnapped = true

                val recognizedLine = shape.size == 2 ||
                    (shape.size == 5 && shape[1].x == shape[3].x && shape[1].y == shape[3].y)
                if (recognizedLine) {
                    val tip = currentPoints.lastOrNull()
                    if (tip != null) {
                        movingStroke = true
                        moveLastX = tip.x
                        moveLastY = tip.y
                    }
                }
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                invalidate()
                return
            }

            return
        }

        if (currentTool == Tool.HIGHLIGHTER && !highlighterStraightLine) return
        if (currentTool == Tool.SHAPE && shapeType != ShapeType.LINE && shapeType != ShapeType.ARROW) return

        val tip = currentPoints.lastOrNull() ?: return
        movingStroke = true
        moveLastX = tip.x
        moveLastY = tip.y
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private var isTransformingSelection = false

    private var selectionPageIndex = -1
    private var selectedPaths = mutableListOf<Path>()
    private var selectedPaints = mutableListOf<Paint>()
    private var selectedBitmaps = mutableListOf<Bitmap?>()

    private var selectedStrokes = mutableListOf<StrokeData>()

    private var selectedRotations = mutableListOf<Float>()
    private var selectionBounds = RectF()

    private var lastRefreshZoom = 1f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var totalDragDx = 0f
    private var totalDragDy = 0f

    private var outsideGesture = false
    private var outsideMoved = false
    private var outsideDownX = 0f
    private var outsideDownY = 0f

    private var multiTouchGesture = false

    private var suppressGesture = false

    private var totalScaleX = 1f
    private var totalScaleY = 1f

    private var resizeSide = ResizeSide.NONE

    private var resizePivotXFrac = 0f
    private var resizePivotYFrac = 0f

    private var gestureStartScaleX = 1f
    private var gestureStartScaleY = 1f
    private var resizeGestureMoved = false
    private var isResizingSelection = false

    var selectionBitmapProvider: ((StrokeData) -> Bitmap?)? = null

    var selectionStrokeRenderer: ((Canvas, StrokeData, Float) -> Unit)? = null

    var onStylusPrimaryAction: (() -> Unit)? = null

    var onStylusSecondaryAction: (() -> Unit)? = null

    var onBarrelButtonChanged: ((pressed: Boolean) -> Unit)? = null

    var stylusLongHoldEnabled: Boolean
        get() = barrelInput.longHoldEnabled
        set(value) { barrelInput.longHoldEnabled = value }

    var stylusLongPressEraseEnabled: Boolean
        get() = barrelInput.longPressEraseEnabled
        set(value) {
            barrelInput.longPressEraseEnabled = value
            barrelInput.longPressSlopPx = holdSlop
        }

    private val barrelInput = StylusBarrelInput(
        onPrimaryQuickPress = { onStylusPrimaryAction?.invoke() },
        onSecondaryPress = { onStylusSecondaryAction?.invoke() },
        onBarrelChanged = { onBarrelButtonChanged?.invoke(it) },
        onLongHoldFired = {

            currentPath.reset()
            stabilizer.reset()
            currentPoints.clear()
            shapeAnchor = null
            shapeSnapped = false
            movingStroke = false
            strokeInProgress = false
            cancelHoldTracking()
            invalidate()
        }
    )

    var lastStrokeWasScribble = false
        private set

    var scribbleReversalThreshold: Int = 3

    private fun detectScribble(points: List<PointF>): Boolean {
        if (points.size < 12) return false
        var reversalsX = 0; var reversalsY = 0
        var prevDx = 0f; var prevDy = 0f
        var length = 0f
        var minX = points[0].x; var maxX = minX
        var minY = points[0].y; var maxY = minY
        val slop = 3f * resources.displayMetrics.density
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            length += kotlin.math.hypot(dx, dy)
            minX = minOf(minX, points[i].x); maxX = maxOf(maxX, points[i].x)
            minY = minOf(minY, points[i].y); maxY = maxOf(maxY, points[i].y)
            if (kotlin.math.abs(dx) > slop) {
                if (prevDx != 0f && dx * prevDx < 0f) reversalsX++
                prevDx = dx
            }
            if (kotlin.math.abs(dy) > slop) {
                if (prevDy != 0f && dy * prevDy < 0f) reversalsY++
                prevDy = dy
            }
        }
        if (maxOf(reversalsX, reversalsY) < scribbleReversalThreshold) return false
        val diag = kotlin.math.hypot(maxX - minX, maxY - minY)
        return diag > 0f && length > 2.2f * diag
    }

    private fun releaseBarrel() = barrelInput.cancel()

    fun engageBarrel() = barrelInput.engage()

    fun onStylusKeyEvent(event: android.view.KeyEvent): Boolean =
        barrelInput.onStylusKey(event, stylusTouching)

    private var stylusTouching = false

    private var indicatorX = -1f
    private var indicatorY = -1f
    private var isIndicatorVisible = false

    private val eraserIndicatorPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private var currentPaint = createPaint()

    private val eraserLogicPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 50f
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val eraserVisualPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 50f
        color = Color.WHITE
        alpha = 100
    }

    private val lassoPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    }

    private val selectionBoxPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val movingStrokeBoxPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.parseColor("#B32196F3")
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val ghostSelectionBoxPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.parseColor("#662196F3")
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
    }

    private val RESIZE_HANDLE_RADIUS = 18f

    private val minMoveBox: Float get() = 64f * resources.displayMetrics.density

    private val minHandleSide: Float
        get() = maxOf(minMoveBox, 2f * (RESIZE_HANDLE_RADIUS + 20f * resources.displayMetrics.density + 6f))
    private val resizeHandleFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val resizeHandleStrokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 4f
    }

    private val resizeBarFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val resizeBarStrokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 3f
    }

    private val measureLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.google.android.material.color.MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnSurfaceInverse, Color.WHITE
        )
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val measureLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.google.android.material.color.MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSurfaceInverse, Color.parseColor("#E6212B36")
        )
        style = Paint.Style.FILL
    }
    private val measureLabelOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = com.google.android.material.color.MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#2196F3")
        )
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        alpha = 102
    }

    private var measureA: PageHit? = null

    private var measureB: PageHit? = null

    private var measurePlacingB = false

    private val measureLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = com.google.android.material.color.MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#2196F3")
        )
    }

    private val measurePointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val measurePointStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = com.google.android.material.color.MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#2196F3")
        )
    }

    private val tablePreviewPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1565C0")
        strokeWidth = TABLE_LINE_WIDTH
    }

    private var tableLineThickness = TABLE_LINE_WIDTH

    private var tableLineStyle = PenLineStyle.SOLID

    private val laserGlowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1565C0")
    }
    private val laserCorePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#1565C0")
    }

    private val laserTipPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#1565C0")
    }

    private var recyclerView: ZoomableRecyclerView? = null
    private var pdfAdapter: PdfPageAdapter? = null
    private var pdfEngine: PdfEngine? = null

    var onStrokeFinishedListener: ((Int, Path, Paint, FloatArray?) -> Unit)? = null
    var onLassoFinishedListener: ((Path) -> Unit)? = null

    var onTextTapListener: ((pageIndex: Int, pageX: Float, pageY: Float) -> Unit)? = null

    var onTableCellTapListener: ((pageIndex: Int, pageX: Float, pageY: Float) -> Unit)? = null

    var onTapeTapListener: ((pageIndex: Int, pageX: Float, pageY: Float) -> Unit)? = null

    var onSelectionMovedListener: ((SelectionTransform) -> Unit)? = null

    var onSelectionTransformChanged: (() -> Unit)? = null

    fun setPdfRecyclerView(rv: ZoomableRecyclerView, adapter: PdfPageAdapter, engine: PdfEngine) {
        this.recyclerView = rv
        this.pdfAdapter = adapter
        this.pdfEngine = engine
    }

    val zoom: Float get() = recyclerView?.zoom ?: 1f

    private fun createPaint(): Paint {
        return Paint().apply {
            isAntiAlias = true
            strokeWidth = brushSize
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = brushColor
            pathEffect = PenLineStyle.pathEffect(penLineStyle, brushSize)
        }
    }

    private fun mmPerScreenPx(pageIndex: Int = -1): Float {
        val rv = recyclerView ?: return 0f
        val idx = if (pageIndex >= 0) pageIndex else selectionPageIndex
        if (idx < 0) return 0f
        val size = pdfEngine?.getPageSize(idx) ?: return 0f
        val pageWidthMm = size.width * 25.4f / 72f
        val pageScreenPx = rv.width.toFloat() * rv.zoom.coerceAtLeast(0.001f)
        if (pageScreenPx <= 0f) return 0f
        return pageWidthMm / pageScreenPx
    }

    private fun pageIndexAt(x: Float, y: Float): Int {
        val rv = recyclerView ?: return -1
        rv.findChildViewUnder(x, y)?.let { return rv.getChildAdapterPosition(it) }
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(c)
            if (pos >= 0) return pos
        }
        return -1
    }

    private fun isStraightStrokeSelection(): Boolean {
        if (selectedStrokes.size != 1) return false
        val s = selectedStrokes.firstOrNull() ?: return false
        if (s.type == StrokeType.TABLE || s.type == StrokeType.TEXT || s.type == StrokeType.IMAGE || s.type == StrokeType.TAPE) return false
        val pm = PathMeasure(selectedPaths.firstOrNull() ?: return false, false)
        val len = pm.length
        if (len <= 0f) return false
        val p0 = FloatArray(2)
        pm.getPosTan(0f, p0, null)
        val tol = len * 0.03f
        for (f in floatArrayOf(1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f)) {
            val pos = FloatArray(2)
            pm.getPosTan(len * f, pos, null)
            val d = hypot(pos[0] - p0[0], pos[1] - p0[1])
            if (abs(d - len * f) > tol) return false
        }
        return true
    }

    private fun buildMeasureLabel(tb: RectF): String? {
        val perPx = mmPerScreenPx()
        if (perPx <= 0f) return null
        if (tb.width() < 0.5f && tb.height() < 0.5f) return null

        val axis = selectionLineAxis()
        if (axis != null) {
            val dx = (axis.x1 - axis.x0) * totalScaleX
            val dy = (axis.y1 - axis.y0) * totalScaleY
            return formatWHL(
                abs(dx) * perPx,
                abs(dy) * perPx,
                hypot(dx, dy) * perPx,
                lineAngleDeg(dx, dy)
            )
        }
        return formatWH(tb.width() * perPx, tb.height() * perPx)
    }

    private fun selectionLineAxis(): LineEnds? {
        if (selectedStrokes.size != 1) return null
        val s = selectedStrokes.firstOrNull() ?: return null
        if (s.type == StrokeType.TABLE || s.type == StrokeType.TEXT || s.type == StrokeType.IMAGE || s.type == StrokeType.TAPE) return null
        val path = selectedPaths.firstOrNull() ?: return null
        if (isStraightStrokeSelection()) {
            val pm = PathMeasure(path, false)
            val len = pm.length
            if (len <= 0f) return null
            val a = FloatArray(2)
            val b = FloatArray(2)
            pm.getPosTan(0f, a, null)
            pm.getPosTan(len, b, null)
            return LineEnds(a[0], a[1], b[0], b[1])
        }
        return arrowShaftBaseEnds(path)
    }

    private fun arrowShaftBaseEnds(path: Path): LineEnds? {
        val pm = PathMeasure(path, false)
        val len = pm.length
        if (len <= 0f) return null
        val p0 = FloatArray(2)
        pm.getPosTan(0f, p0, null)

        val steps = 400
        var lastOnShaft = 0f
        var firstOff = -1f
        var prevOnShaft = true
        for (i in 1..steps) {
            val t = len * i / steps
            val pos = FloatArray(2)
            pm.getPosTan(t, pos, null)
            val d = hypot(pos[0] - p0[0], pos[1] - p0[1])
            val onShaft = d >= t * 0.997f
            if (prevOnShaft && !onShaft && firstOff < 0f) firstOff = t
            if (onShaft) lastOnShaft = t
            prevOnShaft = onShaft
        }
        if (firstOff <= 0f || lastOnShaft <= 0f) return null

        var lo = lastOnShaft
        var hi = firstOff
        repeat(12) {
            val mid = (lo + hi) / 2f
            val pos = FloatArray(2)
            pm.getPosTan(mid, pos, null)
            val d = hypot(pos[0] - p0[0], pos[1] - p0[1])
            if (d >= mid * 0.997f) lo = mid else hi = mid
        }
        val tip = FloatArray(2)
        pm.getPosTan(lo, tip, null)

        val headRegion = len - lo
        if (headRegion < 3f || headRegion > 3000f) return null
        val fineStep = maxOf(0.25f, headRegion / 400f)
        val reach = maxOf(3f, fineStep * 1.5f)
        var t = lo + 12f
        var retraced = false
        var guard = 0
        while (t <= len && guard++ < 500) {
            val pos = FloatArray(2)
            pm.getPosTan(t, pos, null)
            if (hypot(pos[0] - tip[0], pos[1] - tip[1]) <= reach) {
                retraced = true
                break
            }
            t += fineStep
        }
        if (!retraced) return null
        return LineEnds(p0[0], p0[1], tip[0], tip[1])
    }

    private fun formatWH(wMm: Float, hMm: Float): String {

        val forceCm = maxOf(wMm, hMm) >= 100f
        return "W ${formatMeasure(wMm, forceCm)} · H ${formatMeasure(hMm, forceCm)}"
    }

    private fun formatWHL(wMm: Float, hMm: Float, lMm: Float, angleDeg: Float): String {
        val forceCm = maxOf(wMm, hMm, lMm) >= 100f

        val a = angleDeg.roundToInt().let { if (it == 0) 0 else it }
        return "W ${formatMeasure(wMm, forceCm)} · H ${formatMeasure(hMm, forceCm)} · L ${formatMeasure(lMm, forceCm)} · $a°"
    }

    private fun lineAngleDeg(dx: Float, dy: Float): Float {
        var deg = atan2(dy, dx) * (180f / Math.PI.toFloat())
        if (deg > 90f) deg -= 180f
        else if (deg < -90f) deg += 180f
        return deg
    }

    private fun formatMeasure(mm: Float, forceCm: Boolean = false): String {
        if (mm < 0.05f) return "0"
        if (forceCm || mm >= 100f) {
            val cm = (mm / 10f * 10f).roundToInt() / 10f
            return if (cm % 1f == 0f) "${cm.toInt()} cm" else String.format(Locale.US, "%.1f cm", cm)
        }
        return if (mm >= 10f) "${mm.roundToInt()} mm" else String.format(Locale.US, "%.1f mm", mm)
    }

    private fun drawMeasureLabel(canvas: Canvas, tb: RectF, label: String) {
        val density = resources.displayMetrics.density
        val padH = 8f * density
        val padV = 4f * density
        val textW = measureLabelTextPaint.measureText(label)
        val w = textW + padH * 2f
        val h = measureLabelTextPaint.textSize + padV * 2f

        val gap = 14f * density
        var top = tb.bottom + gap
        if (top + h > height - 4f) top = tb.top - h - gap
        top = top.coerceAtLeast(4f)
        val left = (tb.centerX() - w / 2f).coerceIn(4f, (width - w - 4f).coerceAtLeast(4f))
        val r = RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(r, h / 2f, h / 2f, measureLabelBgPaint)
        canvas.drawRoundRect(r, h / 2f, h / 2f, measureLabelOutlinePaint)
        val baseline = r.centerY() - (measureLabelTextPaint.ascent() + measureLabelTextPaint.descent()) / 2f
        canvas.drawText(label, r.centerX() - textW / 2f, baseline, measureLabelTextPaint)
    }

    fun clearMeasure() {
        measureA = null
        measureB = null
        measurePlacingB = false
        invalidate()
    }

    private fun measureScreenPos(p: PageHit): PointF? {
        val rv = recyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) ?: continue
            if (rv.getChildAdapterPosition(c) == p.pageIndex) {
                return PointF(
                    (c.left + p.pageX) * rv.zoom + rv.transX,
                    (c.top + p.pageY) * rv.zoom + rv.transY
                )
            }
        }
        return null
    }

    private fun drawMeasureOverlay(canvas: Canvas) {
        val a = measureA?.let { measureScreenPos(it) } ?: return
        val r = 6f * resources.displayMetrics.density
        canvas.drawCircle(a.x, a.y, r, measurePointFillPaint)
        canvas.drawCircle(a.x, a.y, r, measurePointStrokePaint)

        val b = measureB?.let { measureScreenPos(it) }
        if (b == null) {

            val hint = RectF(a.x, a.y, a.x, a.y)
            drawMeasureLabel(canvas, hint, "Tap end point")
            return
        }
        canvas.drawLine(a.x, a.y, b.x, b.y, measureLinePaint)
        canvas.drawCircle(b.x, b.y, r, measurePointFillPaint)
        canvas.drawCircle(b.x, b.y, r, measurePointStrokePaint)

        val perPx = mmPerScreenPx(measureA?.pageIndex ?: -1)
        if (perPx <= 0f) return
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenMm = hypot(dx, dy) * perPx
        val angle = lineAngleDeg(dx, dy).roundToInt()

        drawMeasureLabel(canvas, RectF(a.x, a.y, b.x, b.y), "${formatMeasure(lenMm)} · $angle°")
    }

    private data class LineEnds(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private fun currentLineEnds(): LineEnds? {
        if (currentPoints.size < 2) return null
        val a = currentPoints[0]
        val tip = currentPoints[1]
        val snappedArrow = currentPoints.size == 5 &&
            tip.x == currentPoints[3].x && tip.y == currentPoints[3].y
        val shapeLine = currentTool == Tool.SHAPE && shapeType == ShapeType.LINE
        val shapeArrow = currentTool == Tool.SHAPE && shapeType == ShapeType.ARROW
        val twoPoint = currentPoints.size == 2

        val freehandPen = currentTool == Tool.PEN && !shapeSnapped
        if (!shapeLine && !shapeArrow && !snappedArrow && !twoPoint && !freehandPen) return null
        val end = if (freehandPen && !shapeLine && !shapeArrow && !snappedArrow) currentPoints.last() else tip
        return LineEnds(a.x, a.y, end.x, end.y)
    }

    private fun drawCurrentLengthLabel(canvas: Canvas) {
        if (currentPath.isEmpty) return
        val b = RectF()
        currentPath.computeBounds(b, true)
        if (b.width() < 0.5f && b.height() < 0.5f) return
        val perPx = mmPerScreenPx(pageIndexAt(b.centerX(), b.centerY()))
        if (perPx <= 0f) return
        val ends = currentLineEnds()
        val label = if (ends != null) {

            val dx = (ends.x1 - ends.x0) * perPx
            val dy = (ends.y1 - ends.y0) * perPx
            formatWHL(abs(dx), abs(dy), hypot(dx, dy), lineAngleDeg(dx, dy))
        } else {
            formatWH(b.width() * perPx, b.height() * perPx)
        }
        drawMeasureLabel(canvas, b, label)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentZoom = zoom

        if ((currentTool == Tool.PIXEL_ERASER || currentTool == Tool.STROKE_ERASER) && isIndicatorVisible) {
            val radius = (brushSize * currentZoom) / 2f
            canvas.drawCircle(indicatorX, indicatorY, radius, eraserIndicatorPaint)
        }

        if (isTransformingSelection) {
            canvas.save()
            canvas.translate(totalDragDx, totalDragDy)
            canvas.scale(totalScaleX, totalScaleY, resizePivotX(), resizePivotY())

            for (i in selectedPaths.indices) {
                val selStroke = selectedStrokes.getOrNull(i)
                if (selStroke != null && selectionStrokeRenderer != null &&
                    (selStroke.type == StrokeType.TABLE || selStroke.type == StrokeType.TEXT ||
                        selStroke.type == StrokeType.TAPE)
                ) {

                    val ghostPaint = Paint(selectedPaints[i])
                    ghostPaint.strokeWidth *= currentZoom
                    val ghostStroke = when (selStroke.type) {
                        StrokeType.TEXT -> selStroke.copy(
                            path = Path(selectedPaths[i]),
                            paint = ghostPaint,
                            textData = selStroke.textData?.let { it.copy(size = it.size * currentZoom) }
                        )
                        else -> selStroke.copy(path = Path(selectedPaths[i]), paint = ghostPaint)
                    }
                    selectionStrokeRenderer?.invoke(canvas, ghostStroke, currentZoom)
                    continue
                }
                var bmp = selectedBitmaps.getOrNull(i)
                if (bmp != null) {
                    if (bmp.isRecycled) {

                        val fresh = selectedStrokes.getOrNull(i)?.let { selectionBitmapProvider?.invoke(it) }
                        if (fresh == null || fresh.isRecycled) continue
                        selectedBitmaps[i] = fresh
                        bmp = fresh
                    }
                    val b = RectF()
                    selectedPaths[i].computeBounds(b, true)
                    val rotation = selectedRotations.getOrNull(i) ?: 0f
                    if (rotation != 0f) {
                        canvas.save()
                        canvas.rotate(rotation, b.centerX(), b.centerY())
                        canvas.drawBitmap(bmp, null, b, null)
                        canvas.restore()
                    } else {
                        canvas.drawBitmap(bmp, null, b, null)
                    }
                } else {
                    val paint = Paint(selectedPaints[i])

                    if (selectedStrokes.getOrNull(i)?.type == StrokeType.IMAGE) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 2f
                    }
                    paint.strokeWidth *= currentZoom
                    canvas.drawPath(selectedPaths[i], paint)
                }
            }

            canvas.restore()

            val tb = transformedSelectionBounds()
            canvas.drawRect(tb, selectionBoxPaint)

            val hb = selectionHandleBox(tb)
            val ghostActive = hb.width() > tb.width() + 0.5f || hb.height() > tb.height() + 0.5f
            if (ghostActive) canvas.drawRect(hb, ghostSelectionBoxPaint)
            val handleBox = if (ghostActive) hb else tb

            if (!isTextSelection && (handleBox.width() >= minMoveBox || handleBox.height() >= minMoveBox)) drawResizeBars(canvas, handleBox)
            canvas.drawCircle(handleBox.right, handleBox.bottom, RESIZE_HANDLE_RADIUS, resizeHandleFillPaint)
            canvas.drawCircle(handleBox.right, handleBox.bottom, RESIZE_HANDLE_RADIUS, resizeHandleStrokePaint)

            val measureLabel = buildMeasureLabel(tb)
            if (measureLabel != null) drawMeasureLabel(canvas, if (ghostActive) hb else tb, measureLabel)
        } else if (currentTool == Tool.LASER) {
            drawLaser(canvas, currentZoom)
        } else if (!currentPath.isEmpty) {

            if (!movingStroke) {
                if (currentTool == Tool.LASSO) {
                    canvas.drawPath(currentPath, lassoPaint)
                } else if (currentTool == Tool.PIXEL_ERASER || currentTool == Tool.STROKE_ERASER) {
                    eraserVisualPaint.strokeWidth = brushSize * currentZoom
                    canvas.drawPath(currentPath, eraserVisualPaint)
                } else if (currentTool == Tool.HIGHLIGHTER) {
                    val p = Paint(highlighterPaint)
                    p.strokeWidth = highlighterSize * currentZoom
                    canvas.drawPath(currentPath, p)
                } else if (currentTool == Tool.TABLE) {
                    tablePreviewPaint.strokeWidth = tableLineThickness * currentZoom
                    TableLineStyle.drawPath(canvas, currentPath, tablePreviewPaint, tableLineStyle)
                } else if (currentTool == Tool.TAPE) {

                    val a = currentPoints.firstOrNull()
                    val b = currentPoints.lastOrNull()
                    if (a != null && b != null) {
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val len = hypot(dx, dy)
                        val w = tapeWidth * currentZoom
                        if (len > 2f && w > 1f) {
                            val r = (w * 0.18f).coerceIn(2f, 10f)
                            canvas.save()
                            canvas.rotate(
                                Math.toDegrees(atan2(dy, dx).toDouble()).toFloat(), a.x, a.y
                            )
                            canvas.drawRoundRect(a.x, a.y - w / 2f, a.x + len, a.y + w / 2f, r, r, tapePreviewPaint)
                            canvas.restore()
                        }
                    }
                } else {
                    currentPaint.strokeWidth = brushSize * currentZoom
                    canvas.drawPath(currentPath, currentPaint)
                }
            }
        }

        if (!movingStroke && strokeInProgress && currentTool == Tool.SHAPE) {
            drawCurrentLengthLabel(canvas)
        }

        if (movingStroke && !currentPath.isEmpty) {
            val tint = Paint(currentPaint).apply {

                val baseColor = if (currentTool == Tool.HIGHLIGHTER) highlighterPaint.color else currentPaint.color
                color = (baseColor and 0x00FFFFFF) or 0x66000000
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                pathEffect = null
                strokeWidth = if (currentTool == Tool.HIGHLIGHTER) {
                    highlighterSize * currentZoom
                } else {
                    brushSize * currentZoom
                }
            }
            canvas.drawPath(currentPath, tint)
            val b = RectF()
            currentPath.computeBounds(b, true)
            b.inset(-18f, -18f)
            canvas.drawRect(b, movingStrokeBoxPaint)

            drawCurrentLengthLabel(canvas)
        }

        drawPendingTablePreview(canvas, currentZoom)

        if (currentTool == Tool.MEASURE) drawMeasureOverlay(canvas)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.isStylusEvent()) {
            barrelInput.onHover(
                event.buttonState,
                event.getToolType(0),
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                    event.actionMasked == MotionEvent.ACTION_HOVER_ENTER
            )
        }
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rv = recyclerView ?: return false
        val toolType = event.getToolType(0)

        val isStylus = event.isStylusEvent()
        if (isStylus) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> stylusTouching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stylusTouching = false
            }
        }

        if (isStylus && event.actionMasked == MotionEvent.ACTION_DOWN) {
            barrelInput.onStylusDown(event.buttonState, toolType)
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) barrelInput.cancel()

        if (!isStylus) {
            if (event.pointerCount >= 2) {

                val wasDrawing = strokeInProgress || !currentPath.isEmpty

                val rvSawFirstFinger = !wasDrawing && !(isTransformingSelection && !outsideGesture)
                if (!currentPath.isEmpty) { currentPath.reset(); invalidate() }
                cancelHoldTracking(); strokeInProgress = false; shapeSnapped = false; movingStroke = false
                cancelFingerLongPress()

                if (isTransformingSelection) {
                    isResizingSelection = false
                    resizeSide = ResizeSide.NONE
                }
                if (!rvSawFirstFinger) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    rv.onTouchEvent(cancel)
                    cancel.recycle()
                }
                outsideGesture = false
                multiTouchGesture = true
                return rv.onTouchEvent(event)
            }
            if (multiTouchGesture) {

                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    multiTouchGesture = false
                }
                return rv.onTouchEvent(event)
            }
            if (!isTransformingSelection) {

                if (singleFingerAction != FingerAction.DRAW) trackFingerLongPress(event)
                when (singleFingerAction) {
                    FingerAction.SCROLL -> return rv.onTouchEvent(event)
                    FingerAction.IGNORED -> return true
                    FingerAction.DRAW -> {  }
                }
            }
        }

        if (!isDrawingMode && !isTransformingSelection) return rv.onTouchEvent(event)

        val x = event.x
        val y = event.y

        if (currentTool == Tool.PIXEL_ERASER || currentTool == Tool.STROKE_ERASER) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    indicatorX = event.x
                    indicatorY = event.y
                    isIndicatorVisible = true
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isIndicatorVisible = false
                    invalidate()
                }
            }
        }

        if (currentTool == Tool.MEASURE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {

                    if (measureA != null && measureB != null) {
                        measureA = null
                        measureB = null
                    }
                    val hit = locatePage(x, y)
                    if (hit != null) {
                        if (measureA == null) measureA = hit
                        else {
                            measureB = hit
                            measurePlacingB = true
                        }
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {

                    if (measurePlacingB) {
                        locatePage(x, y)?.let { measureB = it }
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    measurePlacingB = false

                    barrelInput.onStrokeEnd()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    measurePlacingB = false
                    releaseBarrel()
                    return true
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                suppressGesture = false

                multiTouchGesture = false
                if (isTransformingSelection) {
                    val tb = transformedSelectionBounds()

                    val textSelection = isTextSelection

                    val hb = selectionHandleBox(tb)
                    val ghostActive = hb.width() > tb.width() + 0.5f || hb.height() > tb.height() + 0.5f
                    if (ghostActive) {
                        val grabbedSide = if (textSelection) ResizeSide.NONE else hitTestResizeSide(x, y, hb)
                        val grabsCorner =
                            Math.hypot((x - hb.right).toDouble(), (y - hb.bottom).toDouble()) <= RESIZE_HANDLE_RADIUS * 2.5f
                        if (grabsCorner || grabbedSide != ResizeSide.NONE) {
                            startResizeGesture(if (grabsCorner) ResizeSide.NONE else grabbedSide, x, y)
                            return true
                        }
                        val moveHit = selectionMoveHitRect(hb)
                        if (moveHit.contains(x, y)) {
                            lastTouchX = x
                            lastTouchY = y
                            return true
                        }
                    } else {

                        val small = tb.width() < minMoveBox || tb.height() < minMoveBox
                        val insideShape = if (small) RectF(tb).apply { inset(-8f, -8f) } else null
                        val grabbedSide = if (textSelection) ResizeSide.NONE
                        else hitTestResizeSide(x, y, tb).takeIf { s ->
                            s != ResizeSide.NONE && (insideShape == null || !insideShape.contains(x, y))
                        } ?: ResizeSide.NONE
                        val grabsCorner =
                            Math.hypot((x - tb.right).toDouble(), (y - tb.bottom).toDouble()) <= RESIZE_HANDLE_RADIUS * 2.5f &&
                                (insideShape == null || !insideShape.contains(x, y))
                        if (grabsCorner || grabbedSide != ResizeSide.NONE) {
                            startResizeGesture(if (grabsCorner) ResizeSide.NONE else grabbedSide, x, y)
                            return true
                        }
                        val moveHit = selectionMoveHitRect(tb)
                        if (moveHit.contains(x, y)) {
                            lastTouchX = x
                            lastTouchY = y
                            return true
                        }
                    }

                    if (isStylus) {
                        onSelectionMovedListener?.invoke(currentSelectionTransform())
                        clearSelectionVisuals()
                        cancelHoldTracking()
                        currentPath.reset()
                        stabilizer.reset()
                        shapeAnchor = null
                        shapeSnapped = false

                        suppressGesture = true
                        releaseBarrel()
                        return true
                    } else {
                        outsideGesture = true
                        outsideMoved = false
                        outsideDownX = x
                        outsideDownY = y
                        return rv.onTouchEvent(event)
                    }
                }
                currentPath.reset()
                stabilizer.reset()
                shapeAnchor = if (currentTool == Tool.SHAPE || currentTool == Tool.TABLE) PointF(x, y) else null

                val startPt = stabilizer.processPoint(StrokePoint(x, y, event.pressure, event.eventTime))
                currentPath.moveTo(startPt.x, startPt.y)

                currentPoints.clear()
                currentPoints.add(PointF(x, y))

                if (currentTool == Tool.LASER) {
                    laserTrail.clear()
                    laserTrail.add(LaserTrailPoint(startPt.x, startPt.y, event.eventTime))
                    ensureLaserFrame()
                }

                shapeSnapped = false
                movingStroke = false
                strokeInProgress = currentTool == Tool.PEN || currentTool == Tool.HIGHLIGHTER ||
                    currentTool == Tool.SHAPE
                if (strokeInProgress) startHoldTracking(x, y)

                barrelInput.armLongPressErase(x, y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (isStylus) barrelInput.onStylusMove(event.x, event.y)
                if (suppressGesture) return true
                if (outsideGesture) {
                    if (Math.hypot((x - outsideDownX).toDouble(), (y - outsideDownY).toDouble()) > fingerSlop) {
                        outsideMoved = true
                    }
                    return rv.onTouchEvent(event)
                }
                if (isTransformingSelection) {
                    if (isResizingSelection) {
                        resizeGestureMoved = true
                        updateResizeFromFinger(x, y)
                        invalidate()
                        onSelectionTransformChanged?.invoke()
                        return true
                    }
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    totalDragDx += dx
                    totalDragDy += dy
                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                    onSelectionTransformChanged?.invoke()
                    return true
                }

                if (movingStroke) {

                    val snappedArrow = currentPoints.size == 5 &&
                        currentPoints[1].x == currentPoints[3].x &&
                        currentPoints[1].y == currentPoints[3].y
                    if (currentTool == Tool.SHAPE && shapeType == ShapeType.ARROW) {
                        buildShapePath(x, y)
                        invalidate()
                        return true
                    } else if (snappedArrow) {
                        val tail = currentPoints.firstOrNull() ?: return true
                        val pts = buildArrowPoints(PointF(tail.x, tail.y), PointF(x, y))
                        currentPoints.clear()
                        currentPoints.addAll(pts)
                        currentPath.reset()
                        currentPath.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) currentPath.lineTo(pts[i].x, pts[i].y)
                        invalidate()
                        return true
                    }
                    val first = currentPoints.firstOrNull()
                    val lineLike = currentTool == Tool.HIGHLIGHTER && highlighterStraightLine ||
                        (currentTool == Tool.SHAPE && shapeType == ShapeType.LINE) ||
                        (currentTool == Tool.PEN && !shapeSnapped) ||
                        (first != null && currentPoints.size <= 2)
                    if (first != null && lineLike) {

                        currentPath.reset()
                        currentPath.moveTo(first.x, first.y)
                        for (i in 1 until currentPoints.size - 1) {
                            val p = currentPoints[i]
                            currentPath.lineTo(p.x, p.y)
                        }
                        currentPath.lineTo(x, y)
                        if (currentPoints.size >= 2) currentPoints[currentPoints.size - 1] = PointF(x, y)
                        else currentPoints.add(PointF(x, y))
                        invalidate()
                    } else {
                        val dx = x - moveLastX
                        val dy = y - moveLastY
                        if (dx != 0f || dy != 0f) {
                            currentPath.offset(dx, dy)
                            for (p in currentPoints) {
                                p.x += dx
                                p.y += dy
                            }
                            moveLastX = x
                            moveLastY = y
                            invalidate()
                        }
                    }
                    return true
                }

                if (currentTool == Tool.SHAPE) {
                    buildShapePath(x, y)
                    invalidate()
                    return true
                }

                if (currentTool == Tool.TABLE) {
                    buildTableGridPath(x, y)
                    invalidate()
                    return true
                }

                if (currentTool == Tool.TAPE) {

                    val start = currentPoints.firstOrNull() ?: PointF(x, y)
                    currentPath.reset()
                    currentPath.moveTo(start.x, start.y)
                    currentPath.lineTo(x, y)
                    if (currentPoints.size >= 2) currentPoints[currentPoints.size - 1] = PointF(x, y)
                    else currentPoints.add(PointF(x, y))
                    invalidate()
                    return true
                }

                if (currentTool == Tool.HIGHLIGHTER && highlighterStraightLine) {
                    val start = currentPoints.firstOrNull() ?: PointF(x, y)
                    currentPath.reset()
                    currentPath.moveTo(start.x, start.y)
                    currentPath.lineTo(x, y)
                    invalidate()
                    return true
                }

                if (currentTool == Tool.LASSO && lassoShape != LassoShape.FREE) {
                    buildLassoShapePath(x, y)
                    invalidate()
                    return true
                }

                if (shapeSnapped) return true

                val historySize = event.historySize
                for (i in 0 until historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    val hp = event.getHistoricalPressure(i)
                    val ht = event.getHistoricalEventTime(i)

                    val smoothPt = stabilizer.processPoint(StrokePoint(hx, hy, hp, ht))
                    strokeLineTo(smoothPt.x, smoothPt.y)
                    currentPoints.add(PointF(hx, hy))
                    if (currentTool == Tool.LASER) {
                        laserTrail.add(LaserTrailPoint(smoothPt.x, smoothPt.y, ht))
                        ensureLaserFrame()
                    }
                }

                val smoothPt = stabilizer.processPoint(StrokePoint(x, y, event.pressure, event.eventTime))
                strokeLineTo(smoothPt.x, smoothPt.y)
                currentPoints.add(PointF(x, y))
                if (currentTool == Tool.LASER) {
                    laserTrail.add(LaserTrailPoint(smoothPt.x, smoothPt.y, event.eventTime))
                    ensureLaserFrame()
                }

                if (strokeInProgress) updateHoldTracking(x, y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> return true

            MotionEvent.ACTION_UP -> {
                if (suppressGesture) {
                    suppressGesture = false
                    cancelHoldTracking()
                    releaseBarrel()
                    return true
                }
                cancelHoldTracking()
                strokeInProgress = false
                if (outsideGesture) {
                    outsideGesture = false

                    if (!outsideMoved) {
                        rv.onTouchEvent(event)
                        onSelectionMovedListener?.invoke(currentSelectionTransform())
                        clearSelectionVisuals()

                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        rv.onTouchEvent(cancel)
                        cancel.recycle()
                        releaseBarrel()
                        return true
                    }
                    releaseBarrel()
                    return rv.onTouchEvent(event)
                }
                shapeAnchor = null
                if (isTransformingSelection) {

                    if (isResizingSelection && !resizeGestureMoved) {
                        totalScaleX = gestureStartScaleX
                        totalScaleY = gestureStartScaleY
                    }
                    isResizingSelection = false
                    resizeSide = ResizeSide.NONE
                    releaseBarrel()
                    return true
                }

                if (!shapeSnapped && !movingStroke && currentTool != Tool.SHAPE && currentTool != Tool.TABLE) {
                    val smoothPt = stabilizer.processPoint(StrokePoint(x, y, event.pressure, event.eventTime))
                    strokeLineTo(smoothPt.x, smoothPt.y)
                    currentPoints.add(PointF(x, y))
                }

                if (currentTool == Tool.LASSO) {
                    if (lassoShape == LassoShape.FREE) {
                        currentPath.close()
                    } else {
                        buildLassoShapePath(x, y)
                    }
                    onLassoFinishedListener?.invoke(Path(currentPath))
                } else if (currentTool == Tool.LASER) {

                } else if (currentTool == Tool.TEXT) {

                    val a = currentPoints.firstOrNull()
                    val b = currentPoints.lastOrNull()
                    if (a != null && b != null &&
                        kotlin.math.hypot(b.x - a.x, b.y - a.y) <= fingerSlop * 1.5f
                    ) {
                        locatePage(a.x, a.y)?.let { hit ->
                            onTextTapListener?.invoke(hit.pageIndex, hit.pageX, hit.pageY)
                        }
                    }
                } else if (currentTool == Tool.TABLE) {

                    val a = currentPoints.firstOrNull()
                    val b = currentPoints.lastOrNull()
                    if (a != null && b != null &&
                        kotlin.math.hypot(b.x - a.x, b.y - a.y) <= fingerSlop * 1.5f
                    ) {
                        locatePage(a.x, a.y)?.let { hit ->
                            onTableCellTapListener?.invoke(hit.pageIndex, hit.pageX, hit.pageY)
                        }
                    } else {
                        finalizeStroke()
                    }
                } else if (currentTool == Tool.TAPE) {

                    val a = currentPoints.firstOrNull()
                    val b = currentPoints.lastOrNull()
                    if (a != null && b != null &&
                        kotlin.math.hypot(b.x - a.x, b.y - a.y) <= fingerSlop * 1.5f
                    ) {
                        locatePage(a.x, a.y)?.let { hit ->
                            onTapeTapListener?.invoke(hit.pageIndex, hit.pageX, hit.pageY)
                        }
                    } else {
                        finalizeStroke()
                    }
                } else {

                    if (currentTool == Tool.HIGHLIGHTER && highlighterStraightLine && !movingStroke) {
                        val start = currentPoints.firstOrNull() ?: PointF(x, y)
                        currentPoints.clear()
                        currentPoints.add(start)
                        currentPoints.add(PointF(x, y))
                    }

                    movingStroke = false
                    finalizeStroke()
                }

                currentPath.reset()
                stabilizer.reset()
                shapeSnapped = false
                invalidate()

                barrelInput.onStrokeEnd(event.buttonState, toolType)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                suppressGesture = false
                isResizingSelection = false
                resizeSide = ResizeSide.NONE
                val wasOutside = outsideGesture
                outsideGesture = false
                shapeAnchor = null
                movingStroke = false
                if (currentTool == Tool.LASER) {
                    currentPath.reset()
                    invalidate()
                }
                return if (wasOutside) rv.onTouchEvent(event) else false
            }
            else -> return false
        }
    }

    fun startSelection(
        paths: List<Path>,
        paints: List<Paint>,
        bounds: RectF,
        pageStartOffset: Float,
        pageIndex: Int,
        bitmaps: List<Bitmap?> = emptyList(),
        rotations: List<Float> = emptyList(),
        strokes: List<StrokeData> = emptyList()
    ) {
        isTransformingSelection = true
        selectedPaths.clear()
        selectedPaths.addAll(paths)
        selectedPaints.clear()
        selectedPaints.addAll(paints)
        selectedBitmaps.clear()
        selectedBitmaps.addAll(bitmaps)
        selectedStrokes.clear()
        selectedStrokes.addAll(strokes)
        selectedRotations.clear()
        selectedRotations.addAll(rotations)
        selectionBounds.set(bounds)
        this.selectionPageOffset = pageStartOffset
        this.selectionPageIndex = pageIndex
        selectionScrollDx = 0f
        selectionScrollDy = 0f
        lastRefreshZoom = recyclerView?.zoom ?: 1f
        totalDragDx = 0f
        totalDragDy = 0f
        totalScaleX = 1f
        totalScaleY = 1f
        resizeSide = ResizeSide.NONE
        resizePivotXFrac = 0f
        resizePivotYFrac = 0f
        gestureStartScaleX = 1f
        gestureStartScaleY = 1f
        resizeGestureMoved = false
        isResizingSelection = false
        invalidate()
    }

    fun updateSelectionVisuals(strokes: List<StrokeData>) {
        selectedPaths.clear()
        selectedPaints.clear()
        selectedBitmaps.clear()
        selectedStrokes.clear()
        selectedRotations.clear()
        strokes.forEach {
            selectedPaints.add(Paint(it.paint))
            selectedBitmaps.add(selectionBitmapProvider?.invoke(it))
            selectedStrokes.add(it)
            selectedRotations.add(it.imageRotation)
        }
        rebuildSelectionFromStrokes()
    }

    private fun rebuildSelectionFromStrokes() {
        val rv = recyclerView ?: return
        if (selectedStrokes.isEmpty()) return
        val zoom = rv.zoom

        var child: View? = null
        if (selectionPageIndex >= 0) {
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i) ?: continue
                if (rv.getChildAdapterPosition(c) == selectionPageIndex) { child = c; break }
            }
        }
        if (child == null) {
            val contentY = (selectionBounds.centerY() - rv.transY) / zoom
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i) ?: continue
                val pos = rv.getChildAdapterPosition(c)
                if (pos < 0) continue
                if (contentY >= c.top && contentY < c.bottom) { child = c; break }
            }
        }
        if (child == null) return
        selectionPageOffset = child.top.toFloat()

        selectionScrollDx = 0f
        selectionScrollDy = 0f

        val toScreen = Matrix().apply {
            postTranslate(child.left.toFloat(), child.top.toFloat())
            postScale(zoom, zoom)
            postTranslate(rv.transX, rv.transY)
        }
        selectedPaths.clear()
        val newBounds = RectF()
        var first = true
        selectedStrokes.forEachIndexed { i, s ->
            val p = Path(s.path)
            p.transform(toScreen)
            selectedPaths.add(p)
            val b = RectF()
            p.computeBounds(b, true)

            val rotation = selectedRotations.getOrNull(i) ?: 0f
            if (rotation != 0f) Matrix().apply { setRotate(rotation, b.centerX(), b.centerY()) }.mapRect(b)
            if (first) { newBounds.set(b); first = false } else newBounds.union(b)
        }
        selectionBounds.set(newBounds)
        invalidate()
    }

    fun refreshSelectionForViewport() {
        if (!isTransformingSelection) return
        val rv = recyclerView ?: return
        val z = rv.zoom
        if (lastRefreshZoom > 0f && z != lastRefreshZoom) {

            val ratio = z / lastRefreshZoom
            totalDragDx *= ratio
            totalDragDy *= ratio
        }
        lastRefreshZoom = z
        rebuildSelectionFromStrokes()
        onSelectionTransformChanged?.invoke()
    }

    fun translateSelectionScreen(dx: Float, dy: Float) {
        if (!isTransformingSelection || (dx == 0f && dy == 0f)) return
        selectionScrollDx += dx
        selectionScrollDy += dy
    }

    val selectionScreenOffset: Pair<Float, Float> get() = selectionScrollDx to selectionScrollDy

    val isSelectionActive: Boolean get() = isTransformingSelection

    fun currentSelectionTransform(): SelectionTransform = SelectionTransform(
        totalDragDx, totalDragDy, totalScaleX, totalScaleY, resizePivotXFrac, resizePivotYFrac
    )

    fun currentSelectionScreenBounds(): RectF? {
        if (!isTransformingSelection) return null
        return selectionHandleBox(transformedSelectionBounds())
    }

    fun clearSelectionVisuals() {
        isTransformingSelection = false
        isResizingSelection = false
        resizeSide = ResizeSide.NONE
        totalScaleX = 1f
        totalScaleY = 1f
        resizePivotXFrac = 0f
        resizePivotYFrac = 0f
        gestureStartScaleX = 1f
        gestureStartScaleY = 1f
        resizeGestureMoved = false
        selectionPageIndex = -1
        selectionScrollDx = 0f
        selectionScrollDy = 0f
        selectedPaths.clear()
        selectedPaints.clear()
        selectedBitmaps.clear()
        selectedStrokes.clear()
        selectedRotations.clear()
        invalidate()
    }

    private val isTextSelection: Boolean
        get() = selectedStrokes.size == 1 && selectedStrokes.firstOrNull()?.type == StrokeType.TEXT

    private fun resizePivotX(): Float = selectionBounds.left + resizePivotXFrac * selectionBounds.width()
    private fun resizePivotY(): Float = selectionBounds.top + resizePivotYFrac * selectionBounds.height()

    private fun transformedSelectionBounds(): RectF {
        val r = RectF(selectionBounds)
        val m = Matrix()
        m.postScale(totalScaleX, totalScaleY, resizePivotX(), resizePivotY())
        m.postTranslate(totalDragDx, totalDragDy)
        m.mapRect(r)
        return r
    }

    private fun selectionHandleBox(tb: RectF): RectF {
        val half = minHandleSide / 2f
        val halfW = maxOf(tb.width() / 2f, half)
        val halfH = maxOf(tb.height() / 2f, half)
        return RectF(tb.centerX() - halfW, tb.centerY() - halfH, tb.centerX() + halfW, tb.centerY() + halfH)
    }

    private fun selectionMoveHitRect(tb: RectF): RectF {
        val pad = 40f
        val halfW = maxOf(tb.width() / 2f + pad, minMoveBox / 2f)
        val halfH = maxOf(tb.height() / 2f + pad, minMoveBox / 2f)
        return RectF(tb.centerX() - halfW, tb.centerY() - halfH, tb.centerX() + halfW, tb.centerY() + halfH)
    }

    private fun startResizeGesture(side: ResizeSide, x: Float, y: Float) {
        isResizingSelection = true
        resizeSide = side
        resizeGestureMoved = false
        gestureStartScaleX = totalScaleX
        gestureStartScaleY = totalScaleY
        val tb = transformedSelectionBounds()
        when (side) {
            ResizeSide.RIGHT -> { resizePivotXFrac = 0f; totalDragDx = tb.left - selectionBounds.left }
            ResizeSide.LEFT -> { resizePivotXFrac = 1f; totalDragDx = tb.right - selectionBounds.right }
            ResizeSide.BOTTOM -> { resizePivotYFrac = 0f; totalDragDy = tb.top - selectionBounds.top }
            ResizeSide.TOP -> { resizePivotYFrac = 1f; totalDragDy = tb.bottom - selectionBounds.bottom }
            else -> { resizePivotXFrac = 0f; resizePivotYFrac = 0f }
        }
        updateResizeFromFinger(x, y)
    }

    private fun updateResizeFromFinger(x: Float, y: Float) {
        val w = selectionBounds.width()
        val h = selectionBounds.height()
        val pivotX = selectionBounds.left + totalDragDx
        val pivotY = selectionBounds.top + totalDragDy
        when (resizeSide) {
            ResizeSide.RIGHT -> if (w > 1f) totalScaleX = ((x - pivotX) / w).coerceIn(0.05f, 20f)
            ResizeSide.LEFT -> if (w > 1f) totalScaleX = ((selectionBounds.right + totalDragDx - x) / w).coerceIn(0.05f, 20f)
            ResizeSide.BOTTOM -> if (h > 1f) totalScaleY = ((y - pivotY) / h).coerceIn(0.05f, 20f)
            ResizeSide.TOP -> if (h > 1f) totalScaleY = ((selectionBounds.bottom + totalDragDy - y) / h).coerceIn(0.05f, 20f)
            else -> {

                val diag = Math.hypot(w.toDouble(), h.toDouble())
                if (diag > 1.0) {
                    val d = Math.hypot((x - pivotX).toDouble(), (y - pivotY).toDouble())
                    val s = (d / diag).toFloat().coerceIn(0.05f, 20f)
                    totalScaleX = s
                    totalScaleY = s
                }
            }
        }
    }

    private val resizeBarThickness: Float get() = 10f * resources.displayMetrics.density

    private val resizeBarHitSlop: Float get() = 22f * resources.displayMetrics.density

    private fun drawResizeBars(canvas: Canvas, tb: RectF) {
        val d = resources.displayMetrics.density
        val halfT = resizeBarThickness / 2f
        val vHalfLen = (tb.height() * 0.8f).coerceIn(18f * d, 40f * d) / 2f
        val hHalfLen = (tb.width() * 0.8f).coerceIn(18f * d, 40f * d) / 2f
        val cx = tb.centerX()
        val cy = tb.centerY()
        drawResizeBar(canvas, tb.left - halfT, cy - vHalfLen, tb.left + halfT, cy + vHalfLen)
        drawResizeBar(canvas, tb.right - halfT, cy - vHalfLen, tb.right + halfT, cy + vHalfLen)
        drawResizeBar(canvas, cx - hHalfLen, tb.top - halfT, cx + hHalfLen, tb.top + halfT)
        drawResizeBar(canvas, cx - hHalfLen, tb.bottom - halfT, cx + hHalfLen, tb.bottom + halfT)
    }

    private fun drawResizeBar(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val rect = RectF(l, t, r, b)
        val radius = minOf(r - l, b - t) / 2f
        canvas.drawRoundRect(rect, radius, radius, resizeBarFillPaint)
        canvas.drawRoundRect(rect, radius, radius, resizeBarStrokePaint)
    }

    private fun hitTestResizeSide(x: Float, y: Float, tb: RectF): ResizeSide {
        val d = resources.displayMetrics.density
        val halfLen = 22f * d
        val slop = resizeBarHitSlop
        val cx = tb.centerX()
        val cy = tb.centerY()
        if (x >= tb.left - slop && x <= tb.left + slop && y >= cy - halfLen - slop && y <= cy + halfLen + slop) return ResizeSide.LEFT
        if (x >= tb.right - slop && x <= tb.right + slop && y >= cy - halfLen - slop && y <= cy + halfLen + slop) return ResizeSide.RIGHT
        if (y >= tb.top - slop && y <= tb.top + slop && x >= cx - halfLen - slop && x <= cx + halfLen + slop) return ResizeSide.TOP
        if (y >= tb.bottom - slop && y <= tb.bottom + slop && x >= cx - halfLen - slop && x <= cx + halfLen + slop) return ResizeSide.BOTTOM
        return ResizeSide.NONE
    }

    data class PageHit(val pageIndex: Int, val pageX: Float, val pageY: Float)

    fun locatePage(viewX: Float, viewY: Float): PageHit? {
        val rv = recyclerView ?: return null
        val lm = rv.layoutManager as? LinearLayoutManager ?: return null
        val contentX = (viewX - rv.transX) / rv.zoom
        val contentY = (viewY - rv.transY) / rv.zoom

        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(child)
            if (pos < 0) continue
            if (contentY >= child.top && contentY < child.bottom) {
                return PageHit(pos, contentX - child.left, contentY - child.top)
            }
        }

        val first = lm.findFirstVisibleItemPosition()
        if (first < 0) return null
        val child = lm.findViewByPosition(first) ?: return null
        return PageHit(first, contentX - child.left, contentY - child.top)
    }

    fun pageOrigin(pageIndex: Int): android.graphics.PointF? {
        val rv = recyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            if (rv.getChildAdapterPosition(child) == pageIndex) {
                return android.graphics.PointF(child.left.toFloat(), child.top.toFloat())
            }
        }
        return null
    }

    fun pageAtContent(contentX: Float, contentY: Float): Int? {
        val rv = recyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(child)
            if (pos < 0) continue
            if (contentY >= child.top && contentY < child.bottom) return pos
        }
        return null
    }

    var onFingerLongPress: ((viewX: Float, viewY: Float) -> Unit)? = null

    private var fingerLongPressRunnable: Runnable? = null
    private var fingerDownX = 0f
    private var fingerDownY = 0f
    private val fingerSlop by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop }

    private fun scheduleFingerLongPress(x: Float, y: Float) {
        cancelFingerLongPress()
        fingerDownX = x
        fingerDownY = y
        val r = Runnable {
            fingerLongPressRunnable = null
            onFingerLongPress?.invoke(fingerDownX, fingerDownY)
        }
        fingerLongPressRunnable = r
        postDelayed(r, android.view.ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelFingerLongPress() {
        fingerLongPressRunnable?.let { removeCallbacks(it) }
        fingerLongPressRunnable = null
    }

    private fun trackFingerLongPress(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> scheduleFingerLongPress(event.x, event.y)
            MotionEvent.ACTION_MOVE -> {

                if (Math.hypot(
                        (event.x - fingerDownX).toDouble(),
                        (event.y - fingerDownY).toDouble()
                    ) > fingerSlop
                ) cancelFingerLongPress()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelFingerLongPress()
        }
    }

    private fun finalizeStroke() {
        if (currentPoints.isEmpty()) return
        val rv = recyclerView ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return

        lastStrokeWasScribble =
            currentTool == Tool.PEN && !shapeSnapped && !movingStroke && detectScribble(currentPoints)

        val currentZoom = rv.zoom
        val tx = rv.transX
        val ty = rv.transY

        val firstContentX = (currentPoints[0].x - tx) / currentZoom
        val firstContentY = (currentPoints[0].y - ty) / currentZoom

        var pageIndex = -1
        var childLeft = 0f
        var childTop = 0f
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(child)
            if (pos < 0) continue
            if (firstContentY >= child.top && firstContentY < child.bottom) {
                pageIndex = pos
                childLeft = child.left.toFloat()
                childTop = child.top.toFloat()
                break
            }
        }
        if (pageIndex == -1) {

            val first = lm.findFirstVisibleItemPosition()
            if (first < 0) return
            val child = lm.findViewByPosition(first) ?: return
            pageIndex = first
            childLeft = child.left.toFloat()
            childTop = child.top.toFloat()
        }

        val pdfPath = Path()
        var isFirst = true
        val pagePoints = ArrayList<Float>(currentPoints.size * 2)

        val finalStabilizer = StrokeStabilization()
        finalStabilizer.setLevel(stabilizer.getLevel())

        val exactPoints = shapeSnapped || currentTool == Tool.SHAPE || currentTool == Tool.TABLE ||
            currentTool == Tool.TAPE ||
            (currentTool == Tool.HIGHLIGHTER && highlighterStraightLine)

        var px = Float.NaN
        var py = Float.NaN
        for (point in currentPoints) {
            val pageX = (point.x - tx) / currentZoom - childLeft
            val pageY = (point.y - ty) / currentZoom - childTop

            val sx: Float
            val sy: Float
            if (exactPoints) {
                sx = pageX
                sy = pageY
            } else {

                val smoothPt = finalStabilizer.processPoint(StrokePoint(pageX, pageY))
                sx = smoothPt.x
                sy = smoothPt.y
            }

            if (!isFirst && (sx - px) * (sx - px) + (sy - py) * (sy - py) <= 0.01f) continue

            if (isFirst) { pdfPath.moveTo(sx, sy); isFirst = false }
            else { pdfPath.lineTo(sx, sy) }
            pagePoints.add(sx)
            pagePoints.add(sy)
            px = sx
            py = sy
        }

        if (currentTool == Tool.TABLE) {
            val b = RectF()
            pdfPath.computeBounds(b, true)
            pdfPath.reset()
            buildTableGrid(pdfPath, b, tableGridRows, tableGridCols)
        }

        val contourData = FloatArray(pagePoints.size) { pagePoints[it] }

        val finalPaint = when (currentTool) {
            Tool.PIXEL_ERASER -> Paint(eraserLogicPaint).apply { strokeWidth = brushSize }
            Tool.STROKE_ERASER -> Paint().apply {

                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = brushSize
                color = Color.TRANSPARENT
            }
            Tool.PEN -> Paint(currentPaint).apply { strokeWidth = brushSize }
            Tool.SHAPE -> Paint(currentPaint).apply { strokeWidth = brushSize }
            Tool.TABLE -> Paint(currentPaint)
            Tool.TAPE -> Paint(currentPaint)
            Tool.HIGHLIGHTER -> Paint(highlighterPaint).apply { strokeWidth = highlighterSize }
            else -> Paint(currentPaint)
        }

        onStrokeFinishedListener?.invoke(pageIndex, pdfPath, finalPaint, contourData)
    }

    private var highlighterColor = Color.parseColor("#66FFEB00")
    private var highlighterSize = 28f
    private var highlighterStraightLine = false

    private fun createHighlighterPaint(): Paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = highlighterColor
        strokeWidth = highlighterSize
    }

    private var highlighterPaint = createHighlighterPaint()

    fun setStabilizationLevel(level: Int) { stabilizer.setLevel(level) }
    fun getStabilizationLevel(): Int = stabilizer.getLevel()

    fun setHighlighterColor(c: Int) { highlighterColor = c; highlighterPaint = createHighlighterPaint() }
    fun setHighlighterSize(s: Float) { highlighterSize = s; highlighterPaint = createHighlighterPaint() }
    fun setHighlighterStraight(straight: Boolean) { highlighterStraightLine = straight }
    fun setDrawingMode(isEnabled: Boolean) { isDrawingMode = isEnabled }
    fun getCurrentTool(): Tool = currentTool
    fun setTool(tool: Tool) {
        currentTool = tool

        if (tool != Tool.LASER) clearLaserTrails()

        if (tool != Tool.MEASURE) clearMeasure()
    }
    fun setBrushColor(newColor: Int) {
        brushColor = newColor
        currentPaint = createPaint()

        tablePreviewPaint.color = newColor
        tapePreviewPaint.color = newColor
        laserGlowPaint.color = newColor
        laserCorePaint.color = newColor
        laserTipPaint.color = newColor
        pendingTableBoxPaint.color = newColor
        pendingTableGhostPaint.color = (0x88 shl 24) or (newColor and 0xFFFFFF)
        pendingTableFillPaint.color = (0x1A shl 24) or (newColor and 0xFFFFFF)
        pendingTableHeaderPaint.color = (0x2E shl 24) or (newColor and 0xFFFFFF)
    }
    fun setPenLineStyle(style: String) { penLineStyle = style; currentPaint = createPaint() }

    fun setTableLineStyle(style: String, thickness: Float) {
        tableLineStyle = style
        tableLineThickness = thickness
        tablePreviewPaint.strokeWidth = thickness
    }
    fun setLassoShape(shape: String) { lassoShape = shape }
    fun setShapeType(type: String) { shapeType = type }
    fun setBrushSize(newSize: Float) {
        brushSize = newSize

        currentPaint = createPaint()
        eraserLogicPaint.strokeWidth = newSize
        eraserVisualPaint.strokeWidth = newSize
    }
    fun clearCanvas() { currentPath.reset(); shapeAnchor = null; clearLaserTrails(); invalidate() }

    fun clearLaserTrails() {
        laserTrail.clear()
        invalidate()
    }

    private fun buildLassoShapePath(x: Float, y: Float) {
        val start = currentPoints.firstOrNull() ?: PointF(x, y)
        currentPath.reset()
        val l = minOf(start.x, x)
        val r = maxOf(start.x, x)
        val t = minOf(start.y, y)
        val b = maxOf(start.y, y)
        if (r - l < 1f || b - t < 1f) return
        if (lassoShape == LassoShape.CIRCLE) {
            currentPath.addOval(RectF(l, t, r, b), Path.Direction.CW)
        } else {
            currentPath.addRect(l, t, r, b, Path.Direction.CW)
        }
    }

    private fun buildShapePath(x: Float, y: Float) {
        val pts = buildShapePoints(x, y) ?: return
        currentPoints.clear()
        currentPoints.addAll(pts)
        currentPath.reset()
        currentPath.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) currentPath.lineTo(pts[i].x, pts[i].y)
    }

    private fun buildShapePoints(x: Float, y: Float): List<PointF>? {
        val anchor = shapeAnchor ?: currentPoints.firstOrNull() ?: return null
        val startX = anchor.x
        val startY = anchor.y
        val l = minOf(startX, x)
        val r = maxOf(startX, x)
        val t = minOf(startY, y)
        val b = maxOf(startY, y)
        val w = r - l
        val h = b - t
        if (w < 2f && h < 2f) return null
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        return when (shapeType) {
            ShapeType.RECT -> listOf(PointF(l, t), PointF(r, t), PointF(r, b), PointF(l, b), PointF(l, t))
            ShapeType.OVAL -> {
                val rx = w / 2f
                val ry = h / 2f

                (0..48).map { i ->
                    val ang = (i / 48f) * 2f * Math.PI.toFloat()
                    PointF(cx + rx * cos(ang), cy + ry * sin(ang))
                }
            }
            ShapeType.TRIANGLE -> listOf(PointF(cx, t), PointF(r, b), PointF(l, b), PointF(cx, t))
            ShapeType.LINE -> listOf(PointF(startX, startY), PointF(x, y))
            ShapeType.ARROW -> buildArrowPoints(PointF(startX, startY), PointF(x, y))
            else -> null
        }
    }

    private fun buildArrowPoints(a: PointF, tip: PointF): List<PointF> {
        val shaftLen = hypot(tip.x - a.x, tip.y - a.y)
        if (shaftLen < 1f) return listOf(a, tip)
        val ang = atan2(tip.y - a.y, tip.x - a.x)
        val headLen = (shaftLen * 0.28f).coerceIn(6f, 28f)
        val spread = 0.42f
        val w1 = PointF(
            tip.x + headLen * cos(ang + Math.PI.toFloat() - spread),
            tip.y + headLen * sin(ang + Math.PI.toFloat() - spread)
        )
        val w2 = PointF(
            tip.x + headLen * cos(ang + Math.PI.toFloat() + spread),
            tip.y + headLen * sin(ang + Math.PI.toFloat() + spread)
        )
        return listOf(a, tip, w1, tip, w2)
    }

    private fun buildTableGridPath(x: Float, y: Float) {
        val anchor = shapeAnchor ?: currentPoints.firstOrNull() ?: return
        val l = minOf(anchor.x, x)
        val r = maxOf(anchor.x, x)
        val t = minOf(anchor.y, y)
        val b = maxOf(anchor.y, y)
        if (r - l < 2f || b - t < 2f) return
        currentPath.reset()
        buildTableGrid(currentPath, RectF(l, t, r, b), tableGridRows, tableGridCols)
        currentPoints.clear()
        currentPoints.add(PointF(l, t))
        currentPoints.add(PointF(r, b))
    }

    private fun buildTableGrid(path: Path, b: RectF, rows: Int, cols: Int) {
        val r = rows.coerceAtLeast(1)
        val c = cols.coerceAtLeast(1)
        for (i in 0..c) {
            val x = b.left + b.width() * i / c
            path.moveTo(x, b.top)
            path.lineTo(x, b.bottom)
        }
        for (j in 0..r) {
            val y = b.top + b.height() * j / r
            path.moveTo(b.left, y)
            path.lineTo(b.right, y)
        }
    }

    private var pendingTableRect: RectF? = null
    private var pendingTablePageIndex = -1
    private var pendingTableRows = 3
    private var pendingTableCols = 3
    private var pendingTableHeaderRow = true
    private var pendingTableHeaderCol = false

    private val pendingTableFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0x1A1565C0
    }
    private val pendingTableHeaderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0x2E1565C0
    }
    private val pendingTableBoxPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#1565C0")
    }
    private val pendingTableGhostPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x881565C0.toInt()
    }

    fun showPendingTable(
        pageIndex: Int,
        pageRect: RectF,
        rows: Int,
        cols: Int,
        headerRow: Boolean = true,
        headerCol: Boolean = false
    ) {
        pendingTablePageIndex = pageIndex
        pendingTableRect = RectF(pageRect)
        pendingTableRows = rows
        pendingTableCols = cols
        pendingTableHeaderRow = headerRow
        pendingTableHeaderCol = headerCol
        invalidate()
    }

    fun updatePendingTableGrid(
        rows: Int,
        cols: Int,
        headerRow: Boolean = pendingTableHeaderRow,
        headerCol: Boolean = pendingTableHeaderCol
    ) {
        pendingTableRows = rows
        pendingTableCols = cols
        pendingTableHeaderRow = headerRow
        pendingTableHeaderCol = headerCol
        invalidate()
    }

    fun clearPendingTable() {
        pendingTableRect = null
        pendingTablePageIndex = -1
        invalidate()
    }

    private fun drawPendingTablePreview(canvas: Canvas, currentZoom: Float) {
        val pr = pendingTableRect ?: return
        val rv = recyclerView ?: return
        var ox = 0f
        var oy = 0f
        var found = false
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) ?: continue
            if (rv.getChildAdapterPosition(c) == pendingTablePageIndex) {
                ox = c.left.toFloat()
                oy = c.top.toFloat()
                found = true
                break
            }
        }
        if (!found) return
        val m = Matrix()
        m.postTranslate(ox, oy)
        m.postScale(currentZoom, currentZoom)
        m.postTranslate(rv.transX, rv.transY)
        val screen = RectF(pr)
        m.mapRect(screen)
        val rows = pendingTableRows.coerceAtLeast(1)
        val cols = pendingTableCols.coerceAtLeast(1)
        canvas.drawRect(screen, pendingTableFillPaint)
        if (pendingTableHeaderRow) {
            canvas.drawRect(
                screen.left, screen.top, screen.right,
                screen.top + screen.height() / rows, pendingTableHeaderPaint
            )
        }
        if (pendingTableHeaderCol) {
            canvas.drawRect(
                screen.left, screen.top,
                screen.left + screen.width() / cols, screen.bottom, pendingTableHeaderPaint
            )
        }
        for (i in 1 until cols) {
            val x = screen.left + screen.width() * i / cols
            TableLineStyle.drawLine(canvas, pendingTableGhostPaint, x, screen.top, x, screen.bottom, tableLineStyle)
        }
        for (j in 1 until rows) {
            val y = screen.top + screen.height() * j / rows
            TableLineStyle.drawLine(canvas, pendingTableGhostPaint, screen.left, y, screen.right, y, tableLineStyle)
        }
        TableLineStyle.drawPath(
            canvas,
            Path().apply { addRect(screen, Path.Direction.CW) },
            pendingTableBoxPaint,
            tableLineStyle
        )
    }

    private fun drawLaser(canvas: Canvas, currentZoom: Float) {
        val w = (brushSize * currentZoom).coerceAtLeast(1f)
        laserGlowPaint.strokeWidth = w * 3f
        laserCorePaint.strokeWidth = w
        val now = android.os.SystemClock.uptimeMillis()
        var prev: LaserTrailPoint? = null
        for (p in laserTrail) {
            val prevP = prev
            prev = p
            if (prevP == null) continue
            val age = now - p.t
            if (age >= LASER_FADE_MS) continue
            val alpha = (255 * (1f - age.toFloat() / LASER_FADE_MS)).toInt()
            laserGlowPaint.alpha = (alpha * 0.45f).toInt()
            laserCorePaint.alpha = alpha
            canvas.drawLine(prevP.x, prevP.y, p.x, p.y, laserGlowPaint)
            canvas.drawLine(prevP.x, prevP.y, p.x, p.y, laserCorePaint)
        }

        val tip = laserTrail.lastOrNull()
        if (tip != null) {
            val tipAge = now - tip.t
            if (tipAge < LASER_FADE_MS) {
                laserTipPaint.alpha = (255 * (1f - tipAge.toFloat() / LASER_FADE_MS)).toInt()
                canvas.drawCircle(tip.x, tip.y, (w * 1.2f).coerceAtLeast(4f), laserTipPaint)
            }
        }
    }
}

const val TABLE_LINE_WIDTH = 2f

object ShapeType {
    const val RECT = "RECT"
    const val OVAL = "OVAL"
    const val TRIANGLE = "TRIANGLE"
    const val LINE = "LINE"
    const val ARROW = "ARROW"
}

object LassoShape {
    const val FREE = "FREE"
    const val RECT = "RECT"
    const val CIRCLE = "CIRCLE"
}