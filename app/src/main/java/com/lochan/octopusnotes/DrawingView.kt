package com.lochan.octopusnotes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager

@SuppressLint("ClickableViewAccessibility")
class DrawingView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class FingerAction { SCROLL, DRAW, IGNORED }
    enum class Tool { PEN, PIXEL_ERASER, STROKE_ERASER, LASSO, HIGHLIGHTER }

    // --- Configuration ---
    var singleFingerAction: FingerAction = FingerAction.SCROLL
    private var currentTool = Tool.PEN
    private var brushColor = Color.BLACK
    private var brushSize = 10f
    /** Current pen line style ("SOLID"/"DOTTED"/"DASHED"). Applied to new pen strokes. */
    var penLineStyle: String = PenLineStyle.SOLID
        private set
    /** Lasso selection shape: "FREE" (default), "RECT" or "CIRCLE". */
    var lassoShape: String = LassoShape.FREE
        private set
    private var isDrawingMode = false
    private var selectionPageOffset = 0f

    // --- State ---
    private var currentPath = Path()
    private val currentPoints = mutableListOf<PointF>()
    private val stabilizer = StrokeStabilization()

    // --- Draw & hold → shape snap (pen only) ---
    private val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val holdRunnable = Runnable { trySnapShape() }
    private var holdAnchorX = 0f
    private var holdAnchorY = 0f
    private var strokeInProgress = false
    private var shapeSnapped = false
    private val holdSlop by lazy { 9f * resources.displayMetrics.density }
    private val HOLD_TO_SHAPE_MS = 550L

    private fun startHoldTracking(x: Float, y: Float) {
        holdAnchorX = x; holdAnchorY = y
        holdHandler.removeCallbacks(holdRunnable)
        holdHandler.postDelayed(holdRunnable, HOLD_TO_SHAPE_MS)
    }

    /** Restart the hold timer whenever the pen moves beyond the slop. */
    private fun updateHoldTracking(x: Float, y: Float) {
        if (kotlin.math.hypot(x - holdAnchorX, y - holdAnchorY) > holdSlop) {
            startHoldTracking(x, y)
        }
    }

    private fun cancelHoldTracking() {
        holdHandler.removeCallbacks(holdRunnable)
    }

    /** Fired when the pen has been held still: replace the stroke with a recognised shape. */
    private fun trySnapShape() {
        if (!strokeInProgress || shapeSnapped || currentTool != Tool.PEN) return
        val shape = ShapeRecognizer.recognize(currentPoints) ?: return
        currentPoints.clear()
        currentPoints.addAll(shape)
        currentPath.reset()
        currentPath.moveTo(shape[0].x, shape[0].y)
        for (i in 1 until shape.size) currentPath.lineTo(shape[i].x, shape[i].y)
        shapeSnapped = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    // --- SELECTION STATE ---
    private var isTransformingSelection = false
    private var selectedPaths = mutableListOf<Path>()
    private var selectedPaints = mutableListOf<Paint>()
    private var selectedBitmaps = mutableListOf<Bitmap?>()
    /** Parallel to [selectedBitmaps]: the source strokes, so a bitmap the image cache
     *  evicted (and recycled) can be re-fetched instead of drawn recycled. */
    private var selectedStrokes = mutableListOf<StrokeData>()
    /** Per-selected-stroke picture angle, parallel to [selectedBitmaps] (0 for ink). */
    private var selectedRotations = mutableListOf<Float>()
    private var selectionBounds = RectF()
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var totalDragDx = 0f
    private var totalDragDy = 0f
    /** Uniform scale accumulated by dragging the corner resize handle (pivot = bounds top-left). */
    private var totalScale = 1f
    private var isResizingSelection = false
    /** Maps a selected StrokeData to its bitmap (images only) when rebuilding visuals. */
    var selectionBitmapProvider: ((StrokeData) -> Bitmap?)? = null

    // --- Barrel button (stylus side button) → temporary eraser ---
    private var barrelButtonDown = false
    var onBarrelButtonChanged: ((pressed: Boolean) -> Unit)? = null

    /** True when the last finalized stroke was a scribble gesture (see [detectScribble]). */
    var lastStrokeWasScribble = false
        private set

    /**
     * A scribble is a dense back-and-forth zigzag: several direction reversals along an
     * axis, with total path length far exceeding the gesture's bounding-box diagonal.
     */
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
        if (maxOf(reversalsX, reversalsY) < 4) return false
        val diag = kotlin.math.hypot(maxX - minX, maxY - minY)
        return diag > 0f && length > 2.2f * diag
    }

    /** Ends the barrel-eraser stroke: must run AFTER the stroke is finalized/dispatched. */
    private fun releaseBarrel() {
        if (barrelButtonDown) {
            barrelButtonDown = false
            onBarrelButtonChanged?.invoke(false)
        }
    }

    // --- Eraser Indicator Variables ---
    private var indicatorX = -1f
    private var indicatorY = -1f
    private var isIndicatorVisible = false

    private val eraserIndicatorPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // --- Paints ---
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

    private val RESIZE_HANDLE_RADIUS = 18f
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

    // --- References ---
    private var recyclerView: ZoomableRecyclerView? = null
    private var pdfAdapter: PdfPageAdapter? = null
    private var pdfEngine: PdfEngine? = null

    // Callbacks
    var onStrokeFinishedListener: ((Int, Path, Paint, FloatArray?) -> Unit)? = null
    var onLassoFinishedListener: ((Path) -> Unit)? = null
    /** (dragDx, dragDy, scale) — accumulated transform to commit when the selection is released. */
    var onSelectionMovedListener: ((Float, Float, Float) -> Unit)? = null

    fun setPdfRecyclerView(rv: ZoomableRecyclerView, adapter: PdfPageAdapter, engine: PdfEngine) {
        this.recyclerView = rv
        this.pdfAdapter = adapter
        this.pdfEngine = engine
    }

    /** Access the current zoom level. */
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
            canvas.scale(totalScale, totalScale, selectionBounds.left, selectionBounds.top)

            for (i in selectedPaths.indices) {
                var bmp = selectedBitmaps.getOrNull(i)
                if (bmp != null) {
                    if (bmp.isRecycled) {
                        // The image cache evicted (and recycled) this bitmap mid-selection.
                        // Re-fetch a fresh decode so the picture keeps showing; if it can't
                        // be recovered, skip it rather than draw a recycled bitmap (crash).
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
                    // An image whose bitmap is still being decoded (presentSelection fetches
                    // them off the main thread) would flash as a solid black rectangle — draw
                    // just its frame outline until the bitmap arrives.
                    if (selectedStrokes.getOrNull(i)?.type == StrokeType.IMAGE) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 2f
                    }
                    paint.strokeWidth *= currentZoom
                    canvas.drawPath(selectedPaths[i], paint)
                }
            }

            canvas.drawRect(selectionBounds, selectionBoxPaint)
            canvas.restore()

            // Resize handle (bottom-right corner), drawn unscaled on top.
            val tb = transformedSelectionBounds()
            canvas.drawCircle(tb.right, tb.bottom, RESIZE_HANDLE_RADIUS, resizeHandleFillPaint)
            canvas.drawCircle(tb.right, tb.bottom, RESIZE_HANDLE_RADIUS, resizeHandleStrokePaint)
        } else if (!currentPath.isEmpty) {
            if (currentTool == Tool.LASSO) {
                canvas.drawPath(currentPath, lassoPaint)
            } else if (currentTool == Tool.PIXEL_ERASER || currentTool == Tool.STROKE_ERASER) {
                eraserVisualPaint.strokeWidth = brushSize * currentZoom
                canvas.drawPath(currentPath, eraserVisualPaint)
            } else if (currentTool == Tool.HIGHLIGHTER) {
                val p = Paint(highlighterPaint)
                p.strokeWidth = highlighterSize * currentZoom
                canvas.drawPath(currentPath, p)
            } else {
                currentPaint.strokeWidth = brushSize * currentZoom
                canvas.drawPath(currentPath, currentPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rv = recyclerView ?: return false
        val toolType = event.getToolType(0)
        // Samsung S Pen → TOOL_TYPE_STYLUS.  Many non-Samsung styli (OnePlus, Xiaomi,
        // Lenovo, Huawei, …) report TOOL_TYPE_MOUSE but set SOURCE_STYLUS in the input
        // source.  Accept both so drawing works across all OEMs.
        val isStylusSource = (event.source and android.view.InputDevice.SOURCE_STYLUS) != 0
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS
                || toolType == MotionEvent.TOOL_TYPE_ERASER
                || (toolType == MotionEvent.TOOL_TYPE_MOUSE && isStylusSource)

        // Barrel button is sampled once per stroke, at ACTION_DOWN: the whole stroke then
        // runs as a temporary eraser and the tool is restored when the stroke ends. The
        // release often happens mid-air (no touch events), so transition tracking across
        // strokes is unreliable — per-stroke sampling is not.
        //
        // Different OEM styli report side-buttons with different MotionEvent masks:
        //   Samsung S Pen      → BUTTON_STYLUS_PRIMARY
        //   2-button styli     → BUTTON_STYLUS_PRIMARY / BUTTON_STYLUS_SECONDARY
        //   3-button / Wacom   → may also report BUTTON_SECONDARY / BUTTON_TERTIARY
        // We OR them all: any pressed side-button triggers the temporary-eraser.
        if (isStylus && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val anyBarrelButton = MotionEvent.BUTTON_STYLUS_PRIMARY or
                    MotionEvent.BUTTON_STYLUS_SECONDARY or
                    MotionEvent.BUTTON_SECONDARY or
                    MotionEvent.BUTTON_TERTIARY
            val pressed = (event.buttonState and anyBarrelButton) != 0
            if (pressed && !barrelButtonDown) {
                barrelButtonDown = true
                onBarrelButtonChanged?.invoke(true)
            } else if (!pressed && barrelButtonDown) {
                // Stale state from a stroke that never saw its UP (e.g. cancelled).
                releaseBarrel()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) releaseBarrel()

        if (!isStylus && !isTransformingSelection) {
            if (event.pointerCount >= 2) {
                val wasDrawing = strokeInProgress || !currentPath.isEmpty
                if (!currentPath.isEmpty) { currentPath.reset(); invalidate() }
                cancelHoldTracking(); strokeInProgress = false; shapeSnapped = false
                cancelFingerLongPress()
                if (wasDrawing) {
                    // A finger stroke consumed this gesture's DOWN, so the RecyclerView below
                    // never saw it — its pointer bookkeeping has no record of this gesture.
                    // Reset it with a synthetic CANCEL before forwarding the multi-touch,
                    // otherwise a later POINTER_UP logs "pointer index for id N not found".
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    rv.onTouchEvent(cancel)
                    cancel.recycle()
                }
                return rv.onTouchEvent(event)
            }
            // Only when the finger isn't drawing — in DRAW mode holding still is the
            // start of a stroke (and the shape-snap hold), not a separate gesture.
            if (singleFingerAction != FingerAction.DRAW) trackFingerLongPress(event)
            when (singleFingerAction) {
                FingerAction.SCROLL -> return rv.onTouchEvent(event)
                FingerAction.IGNORED -> return true
                FingerAction.DRAW -> { /* fall through and draw */ }
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

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isTransformingSelection) {
                    val tb = transformedSelectionBounds()
                    // Corner resize handle takes priority over dragging.
                    val handleDist = Math.hypot((x - tb.right).toDouble(), (y - tb.bottom).toDouble())
                    if (handleDist <= RESIZE_HANDLE_RADIUS * 2.5f) {
                        isResizingSelection = true
                        // Baseline the scale at the press so an off-centre grab of the
                        // handle doesn't snap the box on the first move: ACTION_MOVE
                        // computes totalScale absolutely from the finger's distance to
                        // the pivot, so without this it would jump from the stale value
                        // (1f, or the last gesture's) straight to the grab distance.
                        val pivotX = selectionBounds.left + totalDragDx
                        val pivotY = selectionBounds.top + totalDragDy
                        val diag = Math.hypot(selectionBounds.width().toDouble(), selectionBounds.height().toDouble())
                        if (diag > 1.0) {
                            val d = Math.hypot((x - pivotX).toDouble(), (y - pivotY).toDouble())
                            totalScale = (d / diag).toFloat().coerceIn(0.05f, 20f)
                        }
                        return true
                    }
                    val clickRect = RectF(tb)
                    clickRect.inset(-40f, -40f)
                    if (clickRect.contains(x, y)) {
                        lastTouchX = x
                        lastTouchY = y
                        return true
                    } else {
                        onSelectionMovedListener?.invoke(totalDragDx, totalDragDy, totalScale)
                        clearSelectionVisuals()
                    }
                }
                currentPath.reset()
                stabilizer.reset()

                val startPt = stabilizer.processPoint(StrokePoint(x, y, event.pressure, event.eventTime))
                currentPath.moveTo(startPt.x, startPt.y)

                currentPoints.clear()
                currentPoints.add(PointF(x, y))

                // Draw & hold → snap to shape (pen only).
                shapeSnapped = false
                strokeInProgress = currentTool == Tool.PEN
                if (strokeInProgress) startHoldTracking(x, y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTransformingSelection) {
                    if (isResizingSelection) {
                        // Uniform scale about the (dragged) bounds top-left: the handle
                        // follows the finger along the box diagonal.
                        val pivotX = selectionBounds.left + totalDragDx
                        val pivotY = selectionBounds.top + totalDragDy
                        val diag = Math.hypot(selectionBounds.width().toDouble(), selectionBounds.height().toDouble())
                        if (diag > 1.0) {
                            val d = Math.hypot((x - pivotX).toDouble(), (y - pivotY).toDouble())
                            totalScale = (d / diag).toFloat().coerceIn(0.05f, 20f)
                        }
                        invalidate()
                        return true
                    }
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    totalDragDx += dx
                    totalDragDy += dy
                    lastTouchX = x
                    lastTouchY = y
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

                // Once snapped to a shape, the stroke is frozen — lift to commit it.
                if (shapeSnapped) return true

                // Process historical points for ultra-low latency rendering
                val historySize = event.historySize
                for (i in 0 until historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    val hp = event.getHistoricalPressure(i)
                    val ht = event.getHistoricalEventTime(i)

                    val smoothPt = stabilizer.processPoint(StrokePoint(hx, hy, hp, ht))
                    currentPath.lineTo(smoothPt.x, smoothPt.y)
                    currentPoints.add(PointF(hx, hy))
                }

                val smoothPt = stabilizer.processPoint(StrokePoint(x, y, event.pressure, event.eventTime))
                currentPath.lineTo(smoothPt.x, smoothPt.y)
                currentPoints.add(PointF(x, y))

                if (strokeInProgress) updateHoldTracking(x, y)
                invalidate()
                return true
            }

            // A second finger landing/lifting mid-gesture must be consumed, not returned
            // false — returning false lets it fall through to the RecyclerView below, which
            // never saw that pointer's DOWN, producing "Error processing scroll; pointer
            // index for id 1 not found" in logcat.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> return true

            MotionEvent.ACTION_UP -> {
                cancelHoldTracking()
                strokeInProgress = false
                if (isTransformingSelection) {
                    isResizingSelection = false
                    releaseBarrel()
                    return true
                }

                if (!shapeSnapped) {
                    val smoothPt = stabilizer.processPoint(StrokePoint(x, y, event.pressure, event.eventTime))
                    currentPath.lineTo(smoothPt.x, smoothPt.y)
                    currentPoints.add(PointF(x, y))
                }

                if (currentTool == Tool.LASSO) {
                    if (lassoShape == LassoShape.FREE) {
                        currentPath.close()
                    } else {
                        buildLassoShapePath(x, y) // overwrite with the final rect / ellipse
                    }
                    onLassoFinishedListener?.invoke(Path(currentPath))
                } else {
                    if (currentTool == Tool.HIGHLIGHTER && highlighterStraightLine) {
                        val start = currentPoints.firstOrNull() ?: PointF(x, y)
                        currentPoints.clear()
                        currentPoints.add(start)
                        currentPoints.add(PointF(x, y))
                    }
                    finalizeStroke()
                }

                currentPath.reset()
                stabilizer.reset()
                shapeSnapped = false
                invalidate()
                releaseBarrel()
                return true
            }
            else -> return false
        }
    }

    // --- SELECTION API ---

    fun startSelection(
        paths: List<Path>,
        paints: List<Paint>,
        bounds: RectF,
        pageStartOffset: Float,
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
        totalDragDx = 0f
        totalDragDy = 0f
        totalScale = 1f
        isResizingSelection = false
        invalidate()
    }

    fun updateSelectionVisuals(strokes: List<StrokeData>) {
        selectedPaths.clear()
        selectedPaints.clear()
        selectedBitmaps.clear()
        selectedStrokes.clear()
        selectedRotations.clear()

        val rv = recyclerView ?: return
        val currentZoom = rv.zoom

        val pdfToScreenMatrix = Matrix()
        pdfToScreenMatrix.postTranslate(0f, selectionPageOffset)
        pdfToScreenMatrix.postScale(currentZoom, currentZoom)
        // For RecyclerView we use the child view offsets instead of PDFView offsets
        // This is handled by the caller (DrawingActivity) which sets the correct pageStartOffset

        strokes.forEach {
            val p = Path(it.path)
            p.transform(pdfToScreenMatrix)
            selectedPaths.add(p)
            selectedPaints.add(Paint(it.paint))
            selectedBitmaps.add(selectionBitmapProvider?.invoke(it))
            selectedStrokes.add(it)
            selectedRotations.add(it.imageRotation)
        }

        val newBounds = RectF()
        var first = true
        selectedPaths.forEachIndexed { i, p ->
            val b = RectF()
            p.computeBounds(b, true)
            // A turned picture's frame is unturned — its visible box is the frame swung
            // about its centre, which is what the selection box has to wrap.
            val rotation = selectedRotations.getOrNull(i) ?: 0f
            if (rotation != 0f) Matrix().apply { setRotate(rotation, b.centerX(), b.centerY()) }.mapRect(b)
            if (first) { newBounds.set(b); first = false } else newBounds.union(b)
        }
        selectionBounds.set(newBounds)
        invalidate()
    }

    fun clearSelectionVisuals() {
        isTransformingSelection = false
        isResizingSelection = false
        totalScale = 1f
        selectedPaths.clear()
        selectedPaints.clear()
        selectedBitmaps.clear()
        selectedStrokes.clear()
        selectedRotations.clear()
        invalidate()
    }

    /** The selection box after the accumulated drag + corner-handle scale. */
    private fun transformedSelectionBounds(): RectF {
        val r = RectF(selectionBounds)
        val m = Matrix()
        m.postScale(totalScale, totalScale, selectionBounds.left, selectionBounds.top)
        m.postTranslate(totalDragDx, totalDragDy)
        m.mapRect(r)
        return r
    }

    /** A view-space point resolved onto a page. */
    data class PageHit(val pageIndex: Int, val pageX: Float, val pageY: Float)

    /**
     * Maps a point in this view to the page under it, undoing the zoom/pan transform the
     * same way [finalizeStroke] does. Returns null when no page can be resolved.
     */
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
        // Between pages (the gap): fall back to the first visible one.
        val first = lm.findFirstVisibleItemPosition()
        if (first < 0) return null
        val child = lm.findViewByPosition(first) ?: return null
        return PageHit(first, contentX - child.left, contentY - child.top)
    }

    /**
     * A page's top-left corner in content space (the space page coordinates are offsets
     * from). Null when that page isn't currently laid out as a RecyclerView child.
     */
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

    /** The page containing a content-space point, or null if it falls in a gap / off-list. */
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

    // --- Finger long press (paste gesture) ---

    /** Long press with a finger, when the finger isn't the drawing tool. View coordinates. */
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

    /** Feeds the finger-long-press tracker. Must not consume the event — panning still needs it. */
    private fun trackFingerLongPress(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> scheduleFingerLongPress(event.x, event.y)
            MotionEvent.ACTION_MOVE -> {
                // Any real movement means this is a pan, not a press.
                if (Math.hypot(
                        (event.x - fingerDownX).toDouble(),
                        (event.y - fingerDownY).toDouble()
                    ) > fingerSlop
                ) cancelFingerLongPress()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelFingerLongPress()
        }
    }

    /**
     * Converts screen touch coordinates to PDF page coordinates and fires
     * [onStrokeFinishedListener] with the page index and page-space path.
     *
     * Page detection: finds the RecyclerView child under the first touch point
     * and uses its adapter position as the page index.
     */
    private fun finalizeStroke() {
        if (currentPoints.isEmpty()) return
        val rv = recyclerView ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return

        // Scribble gesture (pen only): dense back-and-forth zigzag. The activity turns
        // it into an erase when it lands on existing ink.
        lastStrokeWasScribble =
            currentTool == Tool.PEN && !shapeSnapped && detectScribble(currentPoints)

        val currentZoom = rv.zoom
        val tx = rv.transX
        val ty = rv.transY

        // Map the screen touch to content space (undo the canvas zoom/pan transform).
        val firstContentX = (currentPoints[0].x - tx) / currentZoom
        val firstContentY = (currentPoints[0].y - ty) / currentZoom

        // Find which child view (page) contains the content point.
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
            // Fallback: use the first visible item.
            val first = lm.findFirstVisibleItemPosition()
            if (first < 0) return
            val child = lm.findViewByPosition(first) ?: return
            pageIndex = first
            childLeft = child.left.toFloat()
            childTop = child.top.toFloat()
        }

        // Convert screen points to page-space (0,0 at top-left of page).
        val pdfPath = Path()
        var isFirst = true
        val pagePoints = ArrayList<Float>(currentPoints.size * 2)

        val finalStabilizer = StrokeStabilization()
        finalStabilizer.setLevel(stabilizer.getLevel())

        // Snapped shapes and straight highlights are already exact — smoothing would
        // round rectangle corners / bow straight lines, so map their points 1:1.
        val exactPoints = shapeSnapped || (currentTool == Tool.HIGHLIGHTER && highlighterStraightLine)

        for (point in currentPoints) {
            val pageX = (point.x - tx) / currentZoom - childLeft
            val pageY = (point.y - ty) / currentZoom - childTop

            if (exactPoints) {
                if (isFirst) { pdfPath.moveTo(pageX, pageY); isFirst = false }
                else { pdfPath.lineTo(pageX, pageY) }
                pagePoints.add(pageX)
                pagePoints.add(pageY)
            } else {
                val smoothPt = finalStabilizer.processPoint(StrokePoint(pageX, pageY))
                if (isFirst) { pdfPath.moveTo(smoothPt.x, smoothPt.y); isFirst = false }
                else { pdfPath.lineTo(smoothPt.x, smoothPt.y) }
                pagePoints.add(smoothPt.x)
                pagePoints.add(smoothPt.y)
            }
        }

        val contourData = FloatArray(pagePoints.size) { pagePoints[it] }

        val finalPaint = when (currentTool) {
            Tool.PIXEL_ERASER -> Paint(eraserLogicPaint).apply { strokeWidth = brushSize }
            Tool.STROKE_ERASER -> Paint().apply {
                // Must be a round STROKE paint so getFillPath() produces the full eraser
                // circle area (matching the on-screen indicator), not a zero-width line.
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = brushSize
                color = Color.TRANSPARENT
            }
            Tool.PEN -> Paint(currentPaint).apply { strokeWidth = brushSize }
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
    fun setTool(tool: Tool) { currentTool = tool }
    fun setBrushColor(newColor: Int) { brushColor = newColor; currentPaint = createPaint() }
    fun setPenLineStyle(style: String) { penLineStyle = style; currentPaint = createPaint() }
    fun setLassoShape(shape: String) { lassoShape = shape }
    fun setBrushSize(newSize: Float) {
        brushSize = newSize
        // Rebuild so the (width-dependent) dash/dot path effect stays in proportion.
        currentPaint = createPaint()
        eraserLogicPaint.strokeWidth = newSize
        eraserVisualPaint.strokeWidth = newSize
    }
    fun clearCanvas() { currentPath.reset(); invalidate() }

    /** Builds [currentPath] as a rectangle or ellipse from the gesture's start point to (x, y). */
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
}

/** Lasso selection shapes. String constants for a stable, serialization-friendly value. */
object LassoShape {
    const val FREE = "FREE"
    const val RECT = "RECT"
    const val CIRCLE = "CIRCLE"
}