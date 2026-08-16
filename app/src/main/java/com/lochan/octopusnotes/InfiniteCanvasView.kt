package com.lochan.octopusnotes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

@SuppressLint("ClickableViewAccessibility")
class InfiniteCanvasView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class FingerAction { SCROLL, DRAW, IGNORED }
    enum class Tool { PEN, PIXEL_ERASER, STROKE_ERASER, HIGHLIGHTER, SHAPE, LASSO, LASER, TABLE, TEXT, TAPE }

    var strokeManager: StrokeManager? = null

    var singleFingerAction: FingerAction = FingerAction.SCROLL

    var penLineStyle: String = PenLineStyle.SOLID
        set(value) {
            field = value
            if (currentTool == Tool.PEN || currentTool == Tool.SHAPE) {
                currentPaint.pathEffect = PenLineStyle.pathEffect(value, currentPaint.strokeWidth)
            }

            if (currentTool == Tool.TABLE) {
                tableLineStyle = value
            }
        }

    var shapeType: String = ShapeType.RECT

    private var currentTool = Tool.PEN
    private var brushColor = Color.BLACK
    private var brushSize = 12f
    private var highlighterColor = Color.parseColor("#66FFEB00")
    private var highlighterSize = 28f

    private var highlighterStraight = false
    private var isDrawingMode = true

    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()

    var zoom: Float = 1f
        private set

    var onZoomChanged: ((Float) -> Unit)? = null

    private val minZoom = 0.15f
    private val maxZoom = 8f

    private var currentPath = Path()
    private val currentPoints = mutableListOf<PointF>()
    private val stabilizer = StrokeStabilization()
    private var currentPaint = createPaint()
    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 50f
    }
    private var shapeAnchor: PointF? = null
    private var strokeInProgress = false

    private var panning = false

    private var panMoved = false
    private var multiTouch = false
    private var gestureStartZoom = 1f
    private var gestureStartSpan = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var indicatorVisible = false
    private var indicatorX = 0f
    private var indicatorY = 0f

    private val eraserIndicatorPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.argb(46, 0, 0, 0)
    }

    var onStrokeFinishedListener: ((Int, Path, Paint, FloatArray?) -> Unit)? = null

    var onTextTapListener: ((pageIndex: Int, x: Float, y: Float) -> Unit)? = null
    var onTableCellTapListener: ((pageIndex: Int, x: Float, y: Float) -> Unit)? = null

    var onTapeTapListener: ((pageIndex: Int, x: Float, y: Float) -> Unit)? = null

    private val fingerSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    fun setTool(tool: Tool) {
        currentTool = tool

        if (tool != Tool.LASER) clearLaserTrails()
        when (tool) {
            Tool.PEN, Tool.SHAPE -> {
                currentPaint = createPaint()
                currentPaint.color = brushColor
                currentPaint.strokeWidth = brushSize
                currentPaint.pathEffect = PenLineStyle.pathEffect(penLineStyle, brushSize)
            }
            Tool.HIGHLIGHTER -> {
                currentPaint = createPaint()
                currentPaint.color = highlighterColor
                currentPaint.strokeWidth = highlighterSize
            }
            Tool.PIXEL_ERASER, Tool.STROKE_ERASER -> {
                currentPaint = Paint(eraserPaint)
            }
            Tool.TABLE -> {

                currentPaint = createPaint()
                currentPaint.color = brushColor
                currentPaint.strokeWidth = brushSize
            }
            Tool.TAPE -> {

                currentPaint = createPaint()
                currentPaint.color = brushColor
                currentPaint.strokeWidth = brushSize
            }
            Tool.LASSO, Tool.LASER, Tool.TEXT -> {

                currentPaint = Paint()
            }
        }
        invalidate()
    }

    fun setDrawingMode(drawing: Boolean) {
        isDrawingMode = drawing
    }

    fun setBrushColor(color: Int) {
        brushColor = color
        if (currentTool == Tool.PEN || currentTool == Tool.SHAPE || currentTool == Tool.TABLE ||
            currentTool == Tool.TAPE
        ) {
            currentPaint.color = color
        }
        tablePreviewPaint.color = color

        tapePreviewPaint.color = color
        laserGlowPaint.color = color
        laserCorePaint.color = color
        laserTipPaint.color = color
        invalidate()
    }

    fun setBrushSize(size: Float) {
        brushSize = size
        if (currentTool == Tool.PEN || currentTool == Tool.SHAPE || currentTool == Tool.TABLE) {
            currentPaint.strokeWidth = size
        }

        if (currentTool == Tool.TABLE) {
            tableLineThickness = size
            tablePreviewPaint.strokeWidth = size
        }
        invalidate()
    }

    fun setEraserSize(size: Float) {
        eraserPaint.strokeWidth = size
        if (currentTool == Tool.PIXEL_ERASER || currentTool == Tool.STROKE_ERASER) {
            currentPaint.strokeWidth = size
        }
    }

    fun setHighlighterColor(color: Int) {
        highlighterColor = color
        if (currentTool == Tool.HIGHLIGHTER) currentPaint.color = color
        invalidate()
    }

    fun setHighlighterSize(size: Float) {
        highlighterSize = size
        if (currentTool == Tool.HIGHLIGHTER) currentPaint.strokeWidth = size
        invalidate()
    }

    fun setHighlighterStraight(straight: Boolean) {
        highlighterStraight = straight
    }

    fun setStabilizationLevel(level: Int) {
        stabilizer.setLevel(level)
    }

    private var tableLineStyle = PenLineStyle.SOLID
    private var tableLineThickness = 2f
    private var tableGridRows = 3
    private var tableGridCols = 3

    var tapePattern = TapePattern.SOLID
    var tapeWidth = 32f
    private val tapePreviewPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = (0x99 shl 24) or (Color.BLACK and 0xFFFFFF)
    }

    private val tablePreviewPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f
        color = Color.BLACK
    }

    private var pendingTableRect: RectF? = null
    private var pendingTableRows = 3
    private var pendingTableCols = 3
    private var pendingTableHeaderRow = true
    private var pendingTableHeaderCol = false

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
    private val pendingTableFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0x1A1565C0.toInt()
    }
    private val pendingTableHeaderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = 0x2E1565C0.toInt()
    }

    fun setTableLineStyle(style: String, thickness: Float) {
        tableLineStyle = style
        tableLineThickness = thickness
        tablePreviewPaint.strokeWidth = thickness
    }

    fun setTableGrid(rows: Int, cols: Int) {
        tableGridRows = rows.coerceIn(1, 10)
        tableGridCols = cols.coerceIn(1, 8)
    }

    fun showPendingTable(rect: RectF, rows: Int, cols: Int, headerRow: Boolean = true, headerCol: Boolean = false) {
        pendingTableRect = RectF(rect)
        pendingTableRows = rows
        pendingTableCols = cols
        pendingTableHeaderRow = headerRow
        pendingTableHeaderCol = headerCol
        invalidate()
    }

    fun updatePendingTableGrid(rows: Int, cols: Int, headerRow: Boolean = pendingTableHeaderRow, headerCol: Boolean = pendingTableHeaderCol) {
        pendingTableRows = rows
        pendingTableCols = cols
        pendingTableHeaderRow = headerRow
        pendingTableHeaderCol = headerCol
        invalidate()
    }

    fun clearPendingTable() {
        pendingTableRect = null
        invalidate()
    }

    val currentToolForHost: Tool get() = currentTool

    fun screenToWorld(x: Float, y: Float): PointF {
        viewMatrix.invert(inverseMatrix)
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    fun worldToScreenRect(rect: RectF): RectF {
        val r = RectF(rect)
        viewMatrix.mapRect(r)
        return r
    }

    fun visibleWorldRect(): RectF = visibleWorldBounds()

    private fun visibleWorldBounds(): RectF {
        viewMatrix.invert(inverseMatrix)
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inverseMatrix.mapRect(r)
        return r
    }

    fun fitToContent() {
        val bounds = RectF()
        val strokes = strokeManager?.knownStrokesForPage(0)
        if (strokes.isNullOrEmpty()) {

            bounds.set(-500f, -380f, 500f, 380f)
        } else {
            var first = true
            val b = RectF()
            for (s in strokes) {
                s.path.computeBounds(b, true)
                if (first) { bounds.set(b); first = false } else bounds.union(b)
            }
            bounds.inset(-80f, -80f)
        }
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || bounds.width() < 1f || bounds.height() < 1f) return
        val scale = minOf(w / bounds.width(), h / bounds.height()).coerceIn(minZoom, maxZoom)
        viewMatrix.reset()
        viewMatrix.setScale(scale, scale)
        viewMatrix.postTranslate(w / 2f - bounds.centerX() * scale, h / 2f - bounds.centerY() * scale)
        zoom = scale
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(viewMatrix)
        drawGrid(canvas)
        drawStrokes(canvas)
        drawPendingTable(canvas)

        if (!currentPath.isEmpty && !isEraserTool && currentTool != Tool.LASER && currentTool != Tool.TEXT) {
            if (currentTool == Tool.LASSO) {
                canvas.drawPath(currentPath, lassoFillPaint)
                canvas.drawPath(currentPath, lassoStrokePaint)
            } else if (currentTool == Tool.TABLE) {
                tablePreviewPaint.strokeWidth = tableLineThickness
                TableLineStyle.drawPath(canvas, currentPath, tablePreviewPaint, tableLineStyle)
            } else if (currentTool == Tool.TAPE) {

                val a = currentPoints.firstOrNull()
                val b = currentPoints.lastOrNull()
                if (a != null && b != null) {
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val len = hypot(dx, dy)
                    if (len > 2f && tapeWidth > 1f) {
                        val r = (tapeWidth * 0.18f).coerceIn(2f, 10f)
                        canvas.save()
                        canvas.rotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat(), a.x, a.y)
                        canvas.drawRoundRect(a.x, a.y - tapeWidth / 2f, a.x + len, a.y + tapeWidth / 2f, r, r, tapePreviewPaint)
                        canvas.restore()
                    }
                }
            } else {
                canvas.drawPath(currentPath, currentPaint)
            }
        }
        drawSelection(canvas)
        drawLaser(canvas)
        canvas.restore()

        if (isEraserTool && indicatorVisible) {
            canvas.drawCircle(indicatorX, indicatorY, eraserIndicatorRadius(), eraserIndicatorPaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        var spacing = 32f
        var guard = 0
        while (spacing * zoom < 24f && guard++ < 16) spacing *= 4f
        guard = 0
        while (spacing * zoom > 96f && guard++ < 16) spacing /= 4f
        val b = visibleWorldBounds()

        val radius = (1.5f / zoom).coerceIn(0.7f, 3f)
        val startX = floor(b.left / spacing) * spacing
        val startY = floor(b.top / spacing) * spacing
        var x = startX
        while (x <= b.right) {
            var y = startY
            while (y <= b.bottom) {
                canvas.drawCircle(x, y, radius, gridPaint)
                y += spacing
            }
            x += spacing
        }
    }

    private fun drawStrokes(canvas: Canvas) {
        val sm = strokeManager ?: return
        val strokes = sm.knownStrokesForPage(0)
        if (strokes.isEmpty()) return
        val visible = visibleWorldBounds()
        val b = RectF()
        for (s in strokes) {
            s.path.computeBounds(b, true)
            if (!RectF.intersects(b, visible)) continue
            val draw = if (selectionActive && s.id in selectionOriginalIds) {
                val ghost = Paint(s.paint)
                ghost.alpha = (ghost.alpha * GHOST_ALPHA / 255).coerceIn(0, 255)
                s.copy(paint = ghost)
            } else s
            when (draw.type) {
                StrokeType.TABLE -> sm.drawTableStroke(canvas, draw)
                StrokeType.TEXT -> sm.drawTextStroke(canvas, draw)
                StrokeType.IMAGE -> drawImageStroke(canvas, draw, sm)
                StrokeType.TAPE -> sm.drawTapeStroke(canvas, draw)
                else -> canvas.drawPath(draw.path, draw.paint)
            }
        }
    }

    private fun drawPendingTable(canvas: Canvas) {
        val r = pendingTableRect ?: return
        val rows = pendingTableRows.coerceAtLeast(1)
        val cols = pendingTableCols.coerceAtLeast(1)
        canvas.drawRect(r, pendingTableFillPaint)
        if (pendingTableHeaderRow) {
            canvas.drawRect(r.left, r.top, r.right, r.top + r.height() / rows, pendingTableHeaderPaint)
        }
        if (pendingTableHeaderCol) {
            canvas.drawRect(r.left, r.top, r.left + r.width() / cols, r.bottom, pendingTableHeaderPaint)
        }
        for (i in 1 until cols) {
            val x = r.left + r.width() * i / cols
            TableLineStyle.drawLine(canvas, pendingTableGhostPaint, x, r.top, x, r.bottom, tableLineStyle)
        }
        for (j in 1 until rows) {
            val y = r.top + r.height() * j / rows
            TableLineStyle.drawLine(canvas, pendingTableGhostPaint, r.left, y, r.right, y, tableLineStyle)
        }
        TableLineStyle.drawPath(
            canvas,
            Path().apply { addRect(r, Path.Direction.CW) },
            pendingTableBoxPaint,
            tableLineStyle
        )
    }

    private fun drawImageStroke(canvas: Canvas, stroke: StrokeData, sm: StrokeManager) {
        val bmp = sm.bitmapFor(stroke) ?: return
        val bounds = RectF()
        stroke.path.computeBounds(bounds, true)
        val rotation = stroke.imageRotation
        if (rotation == 0f) {
            canvas.drawBitmap(bmp, null, bounds, stroke.paint)
            return
        }
        canvas.save()
        canvas.rotate(rotation, bounds.centerX(), bounds.centerY())
        canvas.drawBitmap(bmp, null, bounds, stroke.paint)
        canvas.restore()
    }

    private fun eraserIndicatorRadius(): Float = (eraserPaint.strokeWidth * zoom) / 2f

    private fun createPaint(): Paint = Paint().apply {
        isAntiAlias = true
        strokeWidth = brushSize
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = brushColor
        pathEffect = PenLineStyle.pathEffect(penLineStyle, brushSize)
    }

    private val isEraserTool: Boolean
        get() = currentTool == Tool.PIXEL_ERASER || currentTool == Tool.STROKE_ERASER

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

    fun clearLaserTrails() {
        laserTrail.clear()
        invalidate()
    }

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

    private fun drawLaser(canvas: Canvas) {
        if (laserTrail.isEmpty()) return
        val w = brushSize.coerceAtLeast(1f)
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

    var lassoShape: String = LassoShape.FREE
        private set

    var onLassoFinishedListener: ((Path) -> Unit)? = null

    var onSelectionCommit: ((dx: Float, dy: Float) -> Unit)? = null

    var onSelectionDismiss: (() -> Unit)? = null

    var onSelectionBoundsChanged: ((RectF) -> Unit)? = null

    private var selectionActive = false

    private val selectionStrokes = mutableListOf<StrokeData>()
    private val selectionOriginalIds = HashSet<String>()
    private var selectionBounds = RectF()
    private var selectionDragging = false
    private var selectionDragLastX = 0f
    private var selectionDragLastY = 0f
    private var selectionDragStartX = 0f
    private var selectionDragStartY = 0f
    private var suppressGesture = false

    private val selectionBoxPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val lassoFillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#222196F3")
    }
    private val lassoStrokePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF2196F3")
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private fun touchSlop(): Float = 12f * resources.displayMetrics.density

    fun setLassoShape(shape: String) { lassoShape = shape }

    fun setSelection(strokes: List<StrokeData>, bounds: RectF) {
        selectionStrokes.clear()
        selectionStrokes.addAll(strokes.map { it.copy(path = Path(it.path), paint = Paint(it.paint)) })
        selectionOriginalIds.clear()
        strokes.forEach { selectionOriginalIds.add(it.id) }
        selectionBounds.set(bounds)
        selectionActive = true
        selectionDragging = false
        suppressGesture = false
        onSelectionBoundsChanged?.invoke(selectionBounds)
        invalidate()
    }

    fun clearSelection() {
        selectionActive = false
        selectionStrokes.clear()
        selectionOriginalIds.clear()
        selectionDragging = false
        onSelectionBoundsChanged?.invoke(selectionBounds)
        invalidate()
    }

    fun selectionScreenRect(): RectF? {
        if (!selectionActive) return null
        val pts = floatArrayOf(
            selectionBounds.left, selectionBounds.top,
            selectionBounds.right, selectionBounds.bottom
        )
        viewMatrix.mapPoints(pts)
        return RectF(pts[0], pts[1], pts[2], pts[3])
    }

    private fun drawSelection(canvas: Canvas) {
        if (!selectionActive || selectionStrokes.isEmpty()) return
        val sm = strokeManager ?: return
        for (s in selectionStrokes) {
            when (s.type) {
                StrokeType.TABLE -> sm.drawTableStroke(canvas, s)
                StrokeType.TEXT -> sm.drawTextStroke(canvas, s)
                StrokeType.IMAGE -> drawImageStroke(canvas, s, sm)
                StrokeType.TAPE -> sm.drawTapeStroke(canvas, s)
                else -> canvas.drawPath(s.path, s.paint)
            }
        }
        canvas.drawRect(selectionBounds, selectionBoxPaint)
    }

    private fun buildLassoShapePath(x: Float, y: Float) {
        val start = currentPoints.firstOrNull() ?: return
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
            barrelInput.longPressSlopPx = 9f * resources.displayMetrics.density
        }

    private val barrelInput = StylusBarrelInput(
        onPrimaryQuickPress = { onStylusPrimaryAction?.invoke() },
        onSecondaryPress = { onStylusSecondaryAction?.invoke() },
        onBarrelChanged = { onBarrelButtonChanged?.invoke(it) },
        onLongHoldFired = {

            currentPath.reset()
            currentPoints.clear()
            shapeAnchor = null
            indicatorVisible = false
            invalidate()
        }
    )

    fun engageBarrel() = barrelInput.engage()

    fun onStylusKeyEvent(event: android.view.KeyEvent): Boolean =
        barrelInput.onStylusKey(event, stylusTouching)

    private var stylusTouching = false

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
        val toolType = event.getToolType(0)
        val isStylus = event.isStylusEvent()
        if (isStylus) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> stylusTouching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stylusTouching = false
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount == 1) {
                    multiTouch = false
                    panning = false
                    if (isStylus) {

                        barrelInput.onStylusDown(event.buttonState, toolType)
                        if (isDrawingMode) {
                            beginLassoOrStroke(event)

                            if (strokeInProgress) barrelInput.armLongPressErase(event.x, event.y)
                        } else {
                            beginPan(event)
                        }
                    } else {
                        when (singleFingerAction) {
                            FingerAction.SCROLL -> beginPan(event)
                            FingerAction.IGNORED -> return true
                            FingerAction.DRAW -> if (isDrawingMode) beginLassoOrStroke(event) else beginPan(event)
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {

                if (event.pointerCount >= 2) {
                    multiTouch = true
                    cancelInProgressStroke()

                    barrelInput.cancelLongPressErase()
                    gestureStartSpan = span(event)
                    gestureStartZoom = zoom
                    lastPanX = midpointX(event)
                    lastPanY = midpointY(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {

                if (isStylus) barrelInput.onStylusMove(event.x, event.y)
                if (multiTouch && event.pointerCount >= 2) {
                    val s = span(event)
                    if (s > 0f && gestureStartSpan > 0f) {
                        val mx = midpointX(event)
                        val my = midpointY(event)

                        viewMatrix.postTranslate(mx - lastPanX, my - lastPanY)
                        val newZoom = (gestureStartZoom * s / gestureStartSpan).coerceIn(minZoom, maxZoom)
                        applyZoom(newZoom, mx, my)
                        lastPanX = mx
                        lastPanY = my
                        invalidate()
                    }
                    return true
                }
                if (selectionDragging) {

                    val w = screenToWorld(event.x, event.y)
                    translateSelection(w.x - selectionDragLastX, w.y - selectionDragLastY)
                    selectionDragLastX = w.x
                    selectionDragLastY = w.y
                    return true
                }
                if (strokeInProgress) {
                    val w = screenToWorld(event.x, event.y)
                    when {
                        currentTool == Tool.PEN || currentTool == Tool.HIGHLIGHTER -> {
                            val p = stabilizer.processPoint(
                                StrokePoint(w.x, w.y, event.pressure, event.eventTime)
                            )
                            if (currentTool == Tool.HIGHLIGHTER && highlighterStraight) {

                                val start = currentPoints.firstOrNull()
                                if (start != null) {
                                    currentPath.reset()
                                    currentPath.moveTo(start.x, start.y)
                                    currentPath.lineTo(p.x, p.y)
                                }
                            } else {
                                currentPath.lineTo(p.x, p.y)
                            }
                            currentPoints.add(PointF(w.x, w.y))
                        }
                        currentTool == Tool.SHAPE -> buildShapePath(w.x, w.y)
                        currentTool == Tool.TABLE -> buildTableRectPath(w.x, w.y)
                        currentTool == Tool.TAPE -> {

                            val start = currentPoints.firstOrNull() ?: PointF(w.x, w.y)
                            currentPath.reset()
                            currentPath.moveTo(start.x, start.y)
                            currentPath.lineTo(w.x, w.y)
                            if (currentPoints.size >= 2) currentPoints[currentPoints.size - 1] = PointF(w.x, w.y)
                            else currentPoints.add(PointF(w.x, w.y))
                        }
                        currentTool == Tool.TEXT -> currentPoints.add(PointF(w.x, w.y))
                        currentTool == Tool.LASSO -> {
                            if (lassoShape == LassoShape.FREE) {
                                currentPath.lineTo(w.x, w.y)
                                currentPoints.add(PointF(w.x, w.y))
                            } else {
                                buildLassoShapePath(w.x, w.y)
                            }
                        }
                        currentTool == Tool.LASER -> {
                            laserTrail.add(LaserTrailPoint(w.x, w.y, event.eventTime))
                            ensureLaserFrame()
                        }
                        else -> {

                            if (currentPoints.isEmpty()) currentPath.moveTo(w.x, w.y)
                            else currentPath.lineTo(w.x, w.y)
                            indicatorX = event.x
                            indicatorY = event.y
                        }
                    }
                    invalidate()
                    return true
                }
                if (panning) {
                    val dx = event.x - lastPanX
                    val dy = event.y - lastPanY
                    if (!panMoved && hypot(dx, dy) > 8f * resources.displayMetrics.density) {
                        panMoved = true
                    }
                    viewMatrix.postTranslate(dx, dy)
                    lastPanX = event.x
                    lastPanY = event.y
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount >= 2) {

                    multiTouch = false
                    panning = true
                    panMoved = true
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastPanX = event.getX(remaining)
                    lastPanY = event.getY(remaining)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (multiTouch) multiTouch = false
                if (selectionDragging) {
                    finishSelectionDrag(event)
                } else if (strokeInProgress) {
                    finishStroke(event)
                } else if (panning) {
                    panning = false
                    if (!panMoved) maybeDoubleTapZoom(event)
                }

                barrelInput.onStrokeEnd(event.buttonState, toolType)
            }

            MotionEvent.ACTION_CANCEL -> {
                multiTouch = false
                cancelInProgressStroke()
                panning = false
                panMoved = false
                indicatorVisible = false
                laserTrail.clear()
                barrelInput.cancel()
            }
        }
        return true
    }

    private fun beginPan(event: MotionEvent) {
        strokeInProgress = false
        panning = true
        panMoved = false
        lastPanX = event.x
        lastPanY = event.y
        currentPath.reset()
        indicatorVisible = false
    }

    private fun beginStroke(event: MotionEvent) {
        panning = false
        strokeInProgress = true
        currentPath.reset()
        currentPoints.clear()
        stabilizer.reset()
        val w = screenToWorld(event.x, event.y)
        shapeAnchor = if (currentTool == Tool.SHAPE || currentTool == Tool.TABLE) PointF(w.x, w.y) else null
        val start = stabilizer.processPoint(StrokePoint(w.x, w.y, event.pressure, event.eventTime))
        currentPath.moveTo(start.x, start.y)
        currentPoints.add(PointF(w.x, w.y))
        if (isEraserTool) {
            indicatorVisible = true
            indicatorX = event.x
            indicatorY = event.y
        }
        if (currentTool == Tool.LASER) {
            laserTrail.add(LaserTrailPoint(w.x, w.y, event.eventTime))
            ensureLaserFrame()
        }
        invalidate()
    }

    private fun beginLassoOrStroke(event: MotionEvent) {
        if (currentTool == Tool.LASSO && selectionActive) {
            val rect = selectionScreenRect()
            if (rect != null && rect.contains(event.x, event.y)) {
                beginSelectionDrag(event)
            } else {
                onSelectionDismiss?.invoke()
            }
        } else {
            beginStroke(event)
        }
    }

    private fun beginSelectionDrag(event: MotionEvent) {
        selectionDragging = true
        suppressGesture = true
        val w = screenToWorld(event.x, event.y)
        selectionDragStartX = w.x
        selectionDragStartY = w.y
        selectionDragLastX = w.x
        selectionDragLastY = w.y
    }

    private fun translateSelection(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val m = Matrix().apply { setTranslate(dx, dy) }
        for (s in selectionStrokes) {
            val np = Path(s.path)
            np.transform(m)
            s.path.set(np)
            s.savedContours = null
        }
        selectionBounds.offset(dx, dy)
        onSelectionBoundsChanged?.invoke(selectionBounds)
        invalidate()
    }

    private fun finishSelectionDrag(event: MotionEvent) {
        selectionDragging = false
        suppressGesture = false
        val w = screenToWorld(event.x, event.y)
        onSelectionCommit?.invoke(w.x - selectionDragStartX, w.y - selectionDragStartY)
    }

    private fun cancelInProgressStroke() {
        if (!strokeInProgress) return
        strokeInProgress = false
        currentPath.reset()
        currentPoints.clear()
        shapeAnchor = null
        indicatorVisible = false
        laserTrail.clear()
        invalidate()
    }

    private fun finishStroke(event: MotionEvent) {
        if (!strokeInProgress) return
        strokeInProgress = false
        indicatorVisible = false

        if (currentTool == Tool.PEN || currentTool == Tool.HIGHLIGHTER) {
            val w = screenToWorld(event.x, event.y)
            val p = stabilizer.processPoint(StrokePoint(w.x, w.y, event.pressure, event.eventTime))
            if (currentTool == Tool.HIGHLIGHTER && highlighterStraight) {
                val start = currentPoints.firstOrNull()
                if (start != null) {
                    currentPath.reset()
                    currentPath.moveTo(start.x, start.y)
                    currentPath.lineTo(p.x, p.y)
                }
            } else {
                currentPath.lineTo(p.x, p.y)
            }
            currentPoints.add(PointF(w.x, w.y))
        }
        when (currentTool) {
            Tool.LASSO -> {
                if (!currentPath.isEmpty) onLassoFinishedListener?.invoke(Path(currentPath))
            }
            Tool.LASER -> {

                laserTrail.clear()
            }
            Tool.TEXT -> {

                val a = currentPoints.firstOrNull()
                val b = currentPoints.lastOrNull()
                if (a != null && b != null && hypot(b.x - a.x, b.y - a.y) <= fingerSlop * 1.5f) {
                    onTextTapListener?.invoke(0, a.x, a.y)
                }
            }
            Tool.TABLE -> {

                val a = currentPoints.firstOrNull()
                val b = currentPoints.lastOrNull()
                if (a != null && b != null && hypot(b.x - a.x, b.y - a.y) <= fingerSlop * 1.5f) {
                    onTableCellTapListener?.invoke(0, a.x, a.y)
                } else if (!currentPath.isEmpty) {
                    onStrokeFinishedListener?.invoke(0, Path(currentPath), Paint(currentPaint), null)
                }
            }
            Tool.TAPE -> {

                val a = currentPoints.firstOrNull()
                val b = currentPoints.lastOrNull()
                if (a != null && b != null && hypot(b.x - a.x, b.y - a.y) <= fingerSlop * 1.5f) {
                    onTapeTapListener?.invoke(0, a.x, a.y)
                } else if (!currentPath.isEmpty) {
                    onStrokeFinishedListener?.invoke(0, Path(currentPath), Paint(currentPaint), null)
                }
            }
            else -> {
                if (!currentPath.isEmpty) {
                    onStrokeFinishedListener?.invoke(0, Path(currentPath), Paint(currentPaint), null)
                }
            }
        }
        currentPath.reset()
        currentPoints.clear()
        shapeAnchor = null
        invalidate()
    }

    private fun maybeDoubleTapZoom(event: MotionEvent) {
        if (singleFingerAction != FingerAction.SCROLL) return
        val now = SystemClock.uptimeMillis()
        val slop = 48f * resources.displayMetrics.density
        if (now - lastTapTime < 320L && hypot(event.x - lastTapX, event.y - lastTapY) < slop) {
            lastTapTime = 0L
            if (zoom >= maxZoom * 0.95f) {
                fitToContent()
            } else {
                applyZoom(zoom * 1.6f, event.x, event.y)
            }
        } else {
            lastTapTime = now
            lastTapX = event.x
            lastTapY = event.y
        }
    }

    private fun applyZoom(newZoom: Float, focusX: Float, focusY: Float) {
        val clamped = newZoom.coerceIn(minZoom, maxZoom)
        if (clamped == zoom) return
        viewMatrix.postScale(clamped / zoom, clamped / zoom, focusX, focusY)
        zoom = clamped
        onZoomChanged?.invoke(zoom)
        invalidate()
    }

    private fun span(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val i0 = event.getPointerId(0)
        val i1 = event.getPointerId(1)
        val p0x = event.getX(event.findPointerIndex(i0))
        val p0y = event.getY(event.findPointerIndex(i0))
        val p1x = event.getX(event.findPointerIndex(i1))
        val p1y = event.getY(event.findPointerIndex(i1))
        return hypot(p1x - p0x, p1y - p0y)
    }

    private fun midpointX(event: MotionEvent): Float {
        val i0 = event.getPointerId(0)
        val i1 = event.getPointerId(1)
        return (event.getX(event.findPointerIndex(i0)) + event.getX(event.findPointerIndex(i1))) / 2f
    }

    private fun midpointY(event: MotionEvent): Float {
        val i0 = event.getPointerId(0)
        val i1 = event.getPointerId(1)
        return (event.getY(event.findPointerIndex(i0)) + event.getY(event.findPointerIndex(i1))) / 2f
    }

    private fun buildTableRectPath(x: Float, y: Float) {
        val anchor = shapeAnchor ?: return
        val l = minOf(anchor.x, x)
        val r = maxOf(anchor.x, x)
        val t = minOf(anchor.y, y)
        val b = maxOf(anchor.y, y)
        if (r - l < 2f || b - t < 2f) return
        val rows = tableGridRows.coerceAtLeast(1)
        val cols = tableGridCols.coerceAtLeast(1)
        currentPath.reset()
        for (i in 0..cols) {
            val gx = l + (r - l) * i / cols
            currentPath.moveTo(gx, t)
            currentPath.lineTo(gx, b)
        }
        for (j in 0..rows) {
            val gy = t + (b - t) * j / rows
            currentPath.moveTo(l, gy)
            currentPath.lineTo(r, gy)
        }
        currentPoints.clear()
        currentPoints.add(PointF(l, t))
        currentPoints.add(PointF(r, b))
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
        val anchor = shapeAnchor ?: return null
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
        val ang = Math.atan2((tip.y - a.y).toDouble(), (tip.x - a.x).toDouble()).toFloat()
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

    companion object {

        private const val GHOST_ALPHA = 72
    }
}