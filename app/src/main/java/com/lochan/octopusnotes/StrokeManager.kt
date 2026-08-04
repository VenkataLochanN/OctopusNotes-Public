package com.lochan.octopusnotes

import android.graphics.*
import org.json.JSONObject

class StrokeManager {

    // --- MEMORY STORAGE (strokes only, no bitmaps) ---
    private val pageStrokes = mutableMapOf<Int, MutableList<StrokeData>>()
    private val pageUnknownStrokes = mutableMapOf<Int, MutableList<JSONObject>>()

    var activeSelectionStrokes = mutableListOf<StrokeData>()
    var activeSelectionPageIndex = -1

    var originalSelectionStrokes = listOf<StrokeData>()
    var basePathsForTransform = listOf<Path>()
    var clipboardStrokes: List<StrokeData>? = null

    // --- IMAGE SUPPORT ---

    /** Directory holding inserted image files (set once by the activity). */
    var imagesDir: java.io.File? = null

    /** Auto-evicting bitmap cache sized to 1/8 of the runtime heap (16–64 MB).
     *  Evicted entries are NOT recycled: an active selection (or an in-flight draw) may
     *  still hold the exact same Bitmap object, and drawing a recycled bitmap crashes
     *  with "trying to use a recycled bitmap". Dropping the cache's reference is enough —
     *  on API 26+ (minSdk) pixel data lives on the Java heap, so GC reclaims it as soon
     *  as no live reference remains. Recycling on eviction also forced slow main-thread
     *  re-decodes whenever a large selected image got evicted. */
    private val imageBitmapCache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8)
            .coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024)
            .toInt()
    ) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
    }

    /** Empties the cache, recycling every bitmap.  Also called from [loadDecodedData]
     *  so switching notebooks starts fresh. */
    fun clearBitmapCache() {
        imageBitmapCache.evictAll()
    }

    /**
     * Lazily decodes (and caches) the bitmap for an IMAGE stroke, downsampled to at
     * most ~2048px so huge camera photos don't blow the heap. Returns null if the
     * file is missing or unreadable (the stroke then renders as nothing).
     */
    fun bitmapFor(stroke: StrokeData): Bitmap? {
        val file = stroke.imageFile ?: return null
        return decodeImage(file)
    }

    /**
     * Decodes (and caches) the image [fileName] if not already cached. Thread-safe — safe
     * to call from a background dispatcher, which is how insertion and selection decode
     * large photos without janking the main thread. Returns null if the file is missing
     * or unreadable.
     */
    fun preloadBitmap(fileName: String): Bitmap? = decodeImage(fileName)

    private fun decodeImage(file: String): Bitmap? {
        imageBitmapCache.get(file)?.let {
            if (!it.isRecycled) return it
            // The cache evicted (and recycled) this entry while another draw still held the
            // reference — drop the dead entry and decode a fresh copy below.
            imageBitmapCache.remove(file)
        }
        // Not in cache — decode and cache if successful.  Failed decodes are NOT
        // cached (LruCache forbids nulls), so they'll be retried on the next draw.
        val f = java.io.File(imagesDir ?: return null, file)
        if (!f.exists()) return null
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null) imageBitmapCache.put(file, bmp)
            return bmp
        } catch (e: Exception) { return null }
    }

    private fun drawImageStroke(canvas: Canvas, stroke: StrokeData) {
        val bmp = bitmapFor(stroke) ?: return
        val bounds = RectF()
        stroke.path.computeBounds(bounds, true)
        val rotation = stroke.imageRotation
        if (rotation == 0f) {
            canvas.drawBitmap(bmp, null, bounds, stroke.paint)
            return
        }
        // The frame stays axis-aligned; the picture spins inside it. See [StrokeData.imageRotation].
        canvas.save()
        canvas.rotate(rotation, bounds.centerX(), bounds.centerY())
        canvas.drawBitmap(bmp, null, bounds, stroke.paint)
        canvas.restore()
    }

    // ------------------------------------------------------------------------
    //  PROCESS A NEW STROKE (pen, eraser, highlighter)
    // ------------------------------------------------------------------------

    fun processPen(
        pageIndex: Int,
        path: Path,
        paint: Paint,
        toolType: DrawingView.Tool,
        lineStyle: String = PenLineStyle.SOLID,
        contourData: FloatArray? = null
    ): DrawingAction? {
        val type = if (toolType == DrawingView.Tool.HIGHLIGHTER) StrokeType.HIGHLIGHTER else StrokeType.PEN
        // Line style only applies to the pen; highlighter strokes stay solid.
        val style = if (type == StrokeType.PEN) lineStyle else PenLineStyle.SOLID
        val stroke = StrokeData(path = path, paint = paint, isPixelEraser = false, type = type, lineStyle = style)
        if (contourData != null) stroke.savedContours = listOf(contourData)
        return DrawingAction.AddStroke(pageIndex, stroke)
    }

    fun processErase(pageIndex: Int, path: Path, paint: Paint, toolType: DrawingView.Tool): DrawingAction? {
        return when (toolType) {
            DrawingView.Tool.PIXEL_ERASER -> {
                // Main-thread convenience: deep-copy the page's strokes first so the math
                // never touches live objects. MUST be called from the main thread (the only
                // thread allowed to touch live ink). The activity's async path uses
                // snapshotPageStrokes() + computePixelErase() to run the same math off-main.
                val strokes = snapshotPageStrokes(pageIndex) ?: return null
                computePixelErase(pageIndex, Path(path), paint.strokeWidth, strokes)
            }
            DrawingView.Tool.STROKE_ERASER -> performStrokeEraserAction(pageIndex, path, paint)
            else -> null
        }
    }

    // --- DESTRUCTIVE PIXEL ERASER (splitting) ---

    /**
     * Deep-copies every stroke on [pageIndex] — new [Path] and new [Paint] per stroke,
     * same IDs — so the expensive pixel-erase geometry can run on private copies from a
     * background thread without racing the main thread, which draws and mutates the live
     * objects. MUST be called on the main thread (the only thread allowed to touch live
     * ink). Returns null when the page has no ink.
     */
    fun snapshotPageStrokes(pageIndex: Int): List<StrokeData>? {
        // The live list is only safe to read from the main thread (where all ink mutations
        // happen). Enforce it loudly so a future caller can't silently reintroduce the
        // background-read race this snapshot exists to prevent.
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "snapshotPageStrokes must be called on the main thread"
        }
        val strokes = pageStrokes[pageIndex] ?: return null
        if (strokes.isEmpty()) return null
        return strokes.map { it.copy(path = Path(it.path), paint = Paint(it.paint)) }
    }

    /**
     * Runs the stroke-splitting pixel-erase geometry on [strokes], a deep snapshot
     * produced by [snapshotPageStrokes]. Safe on any thread — it only touches the copies,
     * never the live list or live Skia objects. [eraserPath] must also be a private copy
     * owned by the caller. Returns null when nothing was erased.
     */
    fun computePixelErase(pageIndex: Int, eraserPath: Path, eraserSize: Float, strokes: List<StrokeData>): DrawingAction.PixelErase? {
        if (strokes.isEmpty()) return null

        val eraserOutline = Path()
        val ePaint = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = eraserSize
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        safeStrokeOutline(ePaint, eraserPath, eraserOutline)
        val eraserBounds = android.graphics.RectF()
        eraserOutline.computeBounds(eraserBounds, true)
        // Rasterize the eraser's filled outline once, so "is this sample point inside
        // the eraser?" is a cheap Region.contains() lookup for every stroke on the page.
        // (Path has no point-containment query — the Iterable<PathSegment> it exposes on
        // API 35+ is a red herring — and per-point Path.op would be far too slow.)
        val eraserRegion = android.graphics.Region()
        eraserRegion.setPath(
            eraserOutline,
            android.graphics.Region(
                (eraserBounds.left - 1).toInt(), (eraserBounds.top - 1).toInt(),
                (eraserBounds.right + 1).toInt(), (eraserBounds.bottom + 1).toInt()
            )
        )

        val originalStrokes = mutableListOf<StrokeData>()
        val newStrokes = mutableListOf<StrokeData>()
        var somethingChanged = false

        for (stroke in strokes) {
            if (stroke.isPixelEraser || stroke.type == StrokeType.IMAGE) continue

            val strokeBounds = android.graphics.RectF()
            stroke.path.computeBounds(strokeBounds, true)

            if (android.graphics.RectF.intersects(strokeBounds, eraserBounds)) {
                val pieces = splitStrokeByEraser(stroke, eraserOutline, eraserRegion) ?: continue // untouched
                originalStrokes.add(stroke)
                somethingChanged = true
                newStrokes.addAll(pieces) // empty => fully erased
            }
        }
        return if (somethingChanged) DrawingAction.PixelErase(pageIndex, originalStrokes, newStrokes) else null
    }

    /**
     * Splits [stroke] wherever the pixel eraser covers it.
     *
     * Returns null when the eraser removed nothing, an empty list when the stroke is
     * fully erased, or the surviving pieces otherwise.
     *
     * Normal stroked ink is cut along its CENTRE LINE: surviving runs become real
     * strokes carrying a copy of the original paint, so a closed loop that loses an
     * arc stays a loop with a gap instead of a solid filled blob. (Filling the loop's
     * outline was the old bug: the ring's outer and inner boundaries got split apart
     * and each filled independently, which flooded the interior with pen colour.)
     *
     * Filled-region pieces written by older builds have no centre line — their path
     * IS the region — so they fall back to the historic outline subtraction.
     */
    private fun splitStrokeByEraser(stroke: StrokeData, eraserOutline: Path, eraserRegion: android.graphics.Region): List<StrokeData>? {
        // Older builds saved pixel-erased pieces as filled region outlines.
        if (stroke.paint.style != Paint.Style.STROKE) {
            val inkPathShape = Path()
            safeStrokeOutline(stroke.paint, stroke.path, inkPathShape)
            val resultPath = Path(inkPathShape)
            if (!resultPath.op(eraserOutline, Path.Op.DIFFERENCE)) return null
            if (resultPath.isEmpty) return emptyList()
            return splitPathIntoContours(resultPath).map { part ->
                val newPaint = Paint(stroke.paint).apply { style = Paint.Style.FILL; strokeWidth = 0f }
                stroke.copy(path = part, paint = newPaint, id = java.util.UUID.randomUUID().toString())
            }
        }

        val width = stroke.paint.strokeWidth.coerceAtLeast(0f)
        val pm = PathMeasure(stroke.path, false)
        val length = pm.length
        if (length <= 0f) {
            // A tap-dot has no centre line — erase it when the eraser covers its position.
            val b = android.graphics.RectF()
            stroke.path.computeBounds(b, true)
            return if (eraserRegion.contains(b.centerX().toInt(), b.centerY().toInt())) emptyList() else null
        }

        // Sample the stroke densely and mark every arc the eraser overlaps. Besides the
        // centre line, two lines just inside the ink edges are sampled too, so a small
        // eraser that grazes the edge of a thick stroke (highlighter) still erases that
        // section instead of doing nothing. The cut itself is always made on the centre
        // line, so the result is a STROKE piece — a closed loop that loses an arc stays
        // an open arc and can never fill.
        val step = maxOf(length / 2000f, 0.75f) // ~2000 samples max, ~0.75px min
        val count = (length / step).toInt() + 1
        val edgeInset = width * 0.4f // sample points just inside the ink, near each edge
        val erased = BooleanArray(count)
        var anyErased = false
        var anyKept = false
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        for (i in 0 until count) {
            val d = minOf(i * step, length)
            if (pm.getPosTan(d, pos, tan)) {
                // Tangent is unit-length, so (‑tanY, tanX) is a unit normal; the two edge
                // samples sit just inside the ink on either side of the centre line.
                val covered = eraserRegion.contains(pos[0].toInt(), pos[1].toInt()) ||
                    eraserRegion.contains((pos[0] - tan[1] * edgeInset).toInt(), (pos[1] + tan[0] * edgeInset).toInt()) ||
                    eraserRegion.contains((pos[0] + tan[1] * edgeInset).toInt(), (pos[1] - tan[0] * edgeInset).toInt())
                if (covered) { erased[i] = true; anyErased = true }
                else anyKept = true
            }
        }
        if (!anyErased) return null      // only touched the air next to the ink
        if (!anyKept) return emptyList() // fully covered — erase it all

        val pieces = mutableListOf<StrokeData>()
        var i = 0
        while (i < count) {
            if (erased[i]) { i++; continue }
            var j = i
            while (j < count && !erased[j]) j++
            val last = j - 1
            // Recess cut ends by half the stroke width, so the piece's round cap (which
            // sticks out strokeWidth/2 past the segment) reaches exactly the eraser's
            // edge — the visible gap stays eraserSize-wide even when eraser < stroke.
            // Natural stroke ends (touched by the original path start/end) are not recessed.
            var t0 = if (i == 0) 0f else minOf(i * step + width / 2f, last * step)
            var t1 = if (last == count - 1) length else maxOf(last * step - width / 2f, i * step)
            // Drop sub-pixel slivers left by sampling right at the eraser's boundary.
            if (t1 - t0 >= 1.5f) {
                val segment = Path()
                if (pm.getSegment(t0, t1, segment, true) && !segment.isEmpty) {
                    pieces.add(
                        stroke.copy(
                            path = segment,
                            paint = Paint(stroke.paint),
                            id = java.util.UUID.randomUUID().toString()
                        )
                    )
                }
            }
            i = j
        }
        return pieces
    }

    private fun splitPathIntoContours(source: Path): List<Path> {
        val list = mutableListOf<Path>()
        val pm = PathMeasure(source, false)
        do {
            val segment = Path()
            if (pm.getSegment(0f, pm.length, segment, true)) {
                segment.close()
                if (!segment.isEmpty) list.add(segment)
            }
        } while (pm.nextContour())
        return list
    }

    /**
     * Converts a stroked path to its filled outline for hit-testing.
     * Falls back to the original path if [Paint.getFillPath] fails (e.g. on degenerate
     * geometry with many near-zero-length segments), so the stroke remains interactive
     * even when the precise outline can't be computed.
     */
    private fun safeStrokeOutline(paint: Paint, path: Path, outPath: Path) {
        if (paint.style == Paint.Style.STROKE) {
            if (!paint.getFillPath(path, outPath)) {
                outPath.set(path)
            }
        } else {
            outPath.set(path)
        }
    }

    // --- STROKE ERASER ---
    private fun performStrokeEraserAction(pageIndex: Int, eraserPath: Path, eraserPaint: Paint): DrawingAction.DeleteStrokes? {
        val strokes = pageStrokes[pageIndex] ?: return null
        val eraserOutline = Path()
        safeStrokeOutline(eraserPaint, eraserPath, eraserOutline)
        val eBounds = android.graphics.RectF().apply { eraserOutline.computeBounds(this, true) }

        val erased = mutableListOf<StrokeData>()
        val iterator = strokes.iterator()
        while (iterator.hasNext()) {
            val stroke = iterator.next()
            // Images are only editable via the select tool — erasers pass through them.
            if (stroke.isPixelEraser || stroke.type == StrokeType.IMAGE) continue

            val strokeOutline = Path()
            safeStrokeOutline(stroke.paint, stroke.path, strokeOutline)

            val sBounds = android.graphics.RectF().apply { strokeOutline.computeBounds(this, true) }

            if (android.graphics.RectF.intersects(eBounds, sBounds)) {
                val intersection = Path()
                if (intersection.op(eraserOutline, strokeOutline, Path.Op.INTERSECT) && !intersection.isEmpty) {
                    erased.add(stroke)
                    iterator.remove()
                }
            }
        }
        return if (erased.isNotEmpty()) DrawingAction.DeleteStrokes(pageIndex, erased) else null
    }
    // --- LASSO SELECTION ---
    fun selectStrokesInPath(pageIndex: Int, lassoPagePath: Path): Pair<List<StrokeData>, RectF>? {
        val strokes = pageStrokes[pageIndex] ?: return null
        val selectedItems = mutableListOf<StrokeData>()
        val lassoBounds = android.graphics.RectF().apply { lassoPagePath.computeBounds(this, true) }

        for (stroke in strokes) {
            if (stroke.isPixelEraser) continue
            val strokeOutline = Path()
            safeStrokeOutline(stroke.paint, stroke.path, strokeOutline)

            val sBounds = android.graphics.RectF().apply { strokeOutline.computeBounds(this, true) }
            if (!android.graphics.RectF.intersects(lassoBounds, sBounds)) continue

            val intersection = Path()
            if (intersection.op(lassoPagePath, strokeOutline, Path.Op.INTERSECT) && !intersection.isEmpty) {
                selectedItems.add(stroke)
            }
        }

        if (selectedItems.isEmpty()) return null
        return beginSelection(pageIndex, selectedItems)
    }

    /** Puts specific strokes (already on [pageIndex]) into live selection — e.g. a just-inserted image. */
    fun selectStrokes(pageIndex: Int, strokes: List<StrokeData>): Pair<List<StrokeData>, RectF>? {
        if (strokes.isEmpty()) return null
        return beginSelection(pageIndex, strokes)
    }

    private fun beginSelection(pageIndex: Int, selectedItems: List<StrokeData>): Pair<List<StrokeData>, RectF> {
        // Deep copy the selected items so we can modify them live
        activeSelectionStrokes = selectedItems.map { it.copy(path = Path(it.path), paint = Paint(it.paint)) }.toMutableList()
        originalSelectionStrokes = selectedItems.toList()
        basePathsForTransform = activeSelectionStrokes.map { Path(it.path) }
        activeSelectionPageIndex = pageIndex

        val unionBounds = getSelectionBounds()
        return Pair(activeSelectionStrokes, unionBounds)
    }

    fun getSelectionBounds(): RectF {
        val bounds = RectF()
        var isFirst = true
        activeSelectionStrokes.forEach {
            val b = visualBounds(it)
            if (isFirst) { bounds.set(b); isFirst = false } else bounds.union(b)
        }
        return bounds
    }

    /**
     * The axis-aligned box a stroke actually covers on the page. For every stroke but a
     * turned picture that is just its path bounds; a turned picture's frame stays
     * axis-aligned and *unturned* (see [StrokeData.imageRotation]), so its on-page extent is
     * the frame swung about its own centre. Used for the selection box, which would
     * otherwise sit at right angles to the picture the user can see.
     */
    fun visualBounds(stroke: StrokeData): RectF {
        val b = RectF()
        stroke.path.computeBounds(b, true)
        if (stroke.type != StrokeType.IMAGE || stroke.imageRotation == 0f) return b
        Matrix().apply { setRotate(stroke.imageRotation, b.centerX(), b.centerY()) }.mapRect(b)
        return b
    }

    // --- LIVE TRANSFORMS ---
    fun applyAbsoluteRotation(angleDegrees: Float) {
        if (basePathsForTransform.isEmpty()) return
        val baseBounds = RectF()
        var first = true
        basePathsForTransform.forEach {
            val b = RectF()
            it.computeBounds(b, true)
            if (first) { baseBounds.set(b); first = false } else { baseBounds.union(b) }
        }

        val matrix = Matrix()
        matrix.postRotate(angleDegrees, baseBounds.centerX(), baseBounds.centerY())

        for (i in activeSelectionStrokes.indices) {
            val stroke = activeSelectionStrokes[i]
            if (stroke.type == StrokeType.IMAGE) {
                // Pictures turn about their own centre, keeping an axis-aligned frame — the
                // frame is rebuilt from the untransformed base each time, so dragging the
                // rotation handle back and forth doesn't accumulate error.
                stroke.path.set(basePathsForTransform[i])
                turnImageFrame(stroke, matrix, 1f, 0f)
                stroke.imageRotation = normalizeDegrees(
                    (originalSelectionStrokes.getOrNull(i)?.imageRotation ?: 0f) + angleDegrees
                )
                continue
            }
            val newPath = Path(basePathsForTransform[i])
            newPath.transform(matrix)
            stroke.path.set(newPath)
            stroke.savedContours = null // in-place edit: re-flatten on save
        }
    }

    fun flipSelection(horizontal: Boolean, vertical: Boolean) {
        val bounds = getSelectionBounds()
        val matrix = Matrix()
        matrix.postScale(if (horizontal) -1f else 1f, if (vertical) -1f else 1f, bounds.centerX(), bounds.centerY())

        activeSelectionStrokes.forEach {
            it.path.transform(matrix)
            it.savedContours = null // in-place edit: re-flatten on save
        }
        basePathsForTransform.forEach { it.transform(matrix) }
    }

    fun deleteSelection(): DrawingAction.DeleteStrokes? {
        if (activeSelectionPageIndex == -1 || originalSelectionStrokes.isEmpty()) return null
        val action = DrawingAction.DeleteStrokes(activeSelectionPageIndex, originalSelectionStrokes)
        clearSelectionState()
        return action
    }

    private fun clearSelectionState() {
        activeSelectionStrokes.clear()
        originalSelectionStrokes = emptyList()
        basePathsForTransform = emptyList()
        activeSelectionPageIndex = -1
    }

    fun addStrokeToPage(pageIndex: Int, stroke: StrokeData) {
        pageStrokes.getOrPut(pageIndex) { mutableListOf() }.add(stroke)
    }

    fun removeStrokeFromPage(pageIndex: Int, strokeId: String) {
        pageStrokes[pageIndex]?.removeAll { it.id == strokeId }
    }

    fun translateStrokes(pageIndex: Int, strokeIds: List<String>, dx: Float, dy: Float) {
        val list = pageStrokes[pageIndex] ?: return
        val matrix = android.graphics.Matrix().apply { setTranslate(dx, dy) }
        for (i in list.indices) {
            val stroke = list[i]
            if (stroke.id in strokeIds) {
                val newPath = android.graphics.Path(stroke.path)
                newPath.transform(matrix)
                list[i] = stroke.copy(path = newPath)
            }
        }
    }

    /** Outcome of a commit: the undoable action, plus where the strokes actually landed. */
    data class CommitResult(
        val action: DrawingAction,
        val pageIndex: Int,
        val newStrokes: List<StrokeData>
    )

    /** The selection's bounds as they would be after committing with these values. */
    fun selectionBoundsAfter(pdfDx: Float, pdfDy: Float, scale: Float): RectF? {
        if (activeSelectionStrokes.isEmpty()) return null
        val bounds = getSelectionBounds()
        commitMatrix(pdfDx, pdfDy, scale, 0f, 0f, bounds).mapRect(bounds)
        return bounds
    }

    private fun commitMatrix(
        pdfDx: Float,
        pdfDy: Float,
        scale: Float,
        rebaseDx: Float,
        rebaseDy: Float,
        pivot: RectF
    ) = Matrix().apply {
        // Scale about the selection's page-space top-left (matches the on-screen resize
        // pivot in DrawingView), then apply the final drag translation.
        if (scale != 1f) postScale(scale, scale, pivot.left, pivot.top)
        postTranslate(pdfDx + rebaseDx, pdfDy + rebaseDy)
    }

    /**
     * Bakes the live transform into real strokes.
     *
     * [targetPageIndex] lets a selection dragged onto a different page land there instead
     * of on the page it came from. [rebaseDx]/[rebaseDy] convert the coordinates from the
     * source page's space into the target's — without them the strokes keep coordinates
     * measured from the old page's origin and end up outside the new page, invisible.
     */
    fun commitSelection(
        pdfDx: Float,
        pdfDy: Float,
        scale: Float = 1f,
        targetPageIndex: Int = activeSelectionPageIndex,
        rebaseDx: Float = 0f,
        rebaseDy: Float = 0f
    ): CommitResult? {
        if (activeSelectionPageIndex == -1 || activeSelectionStrokes.isEmpty()) return null

        val sourcePage = activeSelectionPageIndex
        val matrix = commitMatrix(pdfDx, pdfDy, scale, rebaseDx, rebaseDy, getSelectionBounds())
        val finalStrokes = activeSelectionStrokes.map { stroke ->
            val newPath = Path(stroke.path)
            newPath.transform(matrix)
            val newPaint = Paint(stroke.paint)
            if (scale != 1f && newPaint.style == Paint.Style.STROKE) newPaint.strokeWidth *= scale
            stroke.copy(path = newPath, paint = newPaint)
        }

        val action = if (targetPageIndex == sourcePage) {
            DrawingAction.ReplaceStrokes(sourcePage, originalSelectionStrokes, finalStrokes)
        } else {
            // Crossing pages isn't a replace: the strokes leave one page and join another.
            DrawingAction.BatchAction(
                listOf(DrawingAction.DeleteStrokes(sourcePage, originalSelectionStrokes)) +
                        finalStrokes.map { DrawingAction.AddStroke(targetPageIndex, it) }
            )
        }
        clearSelectionState()
        return CommitResult(action, targetPageIndex, finalStrokes)
    }

    // ------------------------------------------------------------------------
    //  DRAWING – direct drawing on PDFView's canvas (no transforms)
    //  The canvas is already in page coordinates, so we just draw.
    // ------------------------------------------------------------------------

    fun drawToLayer(canvas: Canvas, pageWidth: Float, pageHeight: Float, displayedPage: Int, zoom: Float) {
        val strokes = pageStrokes[displayedPage] ?: return
        canvas.save()
        canvas.scale(zoom, zoom)
        val selectedIds = if (displayedPage == activeSelectionPageIndex) activeSelectionStrokes.map { it.id } else emptyList()
        // Bottom-to-top: images (under all ink so strokes draw over pictures), then
        // highlighter (like a real highlighter under ink), then pen/other ink on top.
        for (stroke in strokes) {
            if (stroke.type != StrokeType.IMAGE || stroke.id in selectedIds) continue
            drawImageStroke(canvas, stroke)
        }
        for (stroke in strokes) {
            if (stroke.type != StrokeType.HIGHLIGHTER || stroke.id in selectedIds) continue
            canvas.drawPath(stroke.path, stroke.paint)
        }
        for (stroke in strokes) {
            if (stroke.type == StrokeType.HIGHLIGHTER || stroke.type == StrokeType.IMAGE || stroke.id in selectedIds) continue
            canvas.drawPath(stroke.path, stroke.paint)
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------------
    //  THUMBNAIL RENDERER SUPPORT
    // ------------------------------------------------------------------------

    fun drawPageStrokes(pageIndex: Int, canvas: Canvas, scaleX: Float, scaleY: Float) {
        val strokes = pageStrokes[pageIndex] ?: return
        canvas.save()
        canvas.scale(scaleX, scaleY)
        // Images first (underneath all ink), then highlighter, then pen ink on top.
        for (s in strokes) if (s.type == StrokeType.IMAGE) drawImageStroke(canvas, s)
        for (s in strokes) if (s.type == StrokeType.HIGHLIGHTER) canvas.drawPath(s.path, s.paint)
        for (s in strokes) if (s.type != StrokeType.HIGHLIGHTER && s.type != StrokeType.IMAGE) canvas.drawPath(s.path, s.paint)
        canvas.restore()
    }

    // ------------------------------------------------------------------------
    //  SAVE / LOAD ACCESS
    // ------------------------------------------------------------------------

    fun knownStrokesForPage(page: Int): List<StrokeData> = pageStrokes[page] ?: emptyList()
    fun unknownStrokesForPage(page: Int): List<JSONObject> = pageUnknownStrokes[page] ?: emptyList()
    fun allPagesWithData(): Set<Int> = pageStrokes.keys + pageUnknownStrokes.keys

    fun loadDecodedData(pages: Map<Int, DrawingCodec.DecodedPage>) {
        pageStrokes.clear()
        pageUnknownStrokes.clear()
        clearBitmapCache()
        for ((p, data) in pages) applyLoadedPage(p, data)
    }

    /**
     * Rescales every stroke in memory by [factor], for when the page width changes under a
     * live document (window resize, fold/unfold — rotation normally recreates the activity
     * and comes back through the codec instead). Without this the ink keeps its old
     * pixel coordinates and drifts off the page. See [DrawingCodec] for the coordinate space.
     */
    fun rescaleAll(factor: Float) {
        if (factor <= 0f || factor == 1f) return
        val m = Matrix().apply { setScale(factor, factor) }
        clearSelectionState()
        for (strokes in pageStrokes.values) {
            for (s in strokes) {
                s.path.transform(m)
                s.paint.strokeWidth *= factor
                if (s.lineStyle != PenLineStyle.SOLID && s.paint.style != Paint.Style.FILL) {
                    // Dash metrics are derived from the stroke width — rebuild at the new one.
                    s.paint.pathEffect = PenLineStyle.pathEffect(s.lineStyle, s.paint.strokeWidth)
                }
                s.savedContours = null // geometry changed: re-flatten on the next save
            }
        }
        // Unknown-tool strokes are held as raw JSON; scale their geometry in place so they
        // stay aligned with everything else when a newer build renders them.
        for (strokes in pageUnknownStrokes.values) for (o in strokes) rescaleRawStroke(o, factor)
    }

    private fun rescaleRawStroke(o: JSONObject, factor: Float) {
        if (o.has("width")) o.put("width", o.optDouble("width", 0.0) * factor)
        o.optJSONArray("rect")?.let { scaleJsonFloats(it, factor) }
        o.optJSONArray("contours")?.let { cs ->
            for (i in 0 until cs.length()) cs.optJSONArray(i)?.let { scaleJsonFloats(it, factor) }
        }
    }

    private fun scaleJsonFloats(a: org.json.JSONArray, factor: Float) {
        for (i in 0 until a.length()) a.put(i, a.optDouble(i, 0.0) * factor)
    }

    /**
     * Turns one page's ink with the page itself, for a PDF page rotation of [deltaDeg]
     * (any multiple of 90, positive = clockwise, matching the PDF /Rotate convention).
     *
     * [pageWidth] x [pageHeight] is the page's stroke-space size *before* the rotation. A
     * quarter turn swaps the page's aspect, but the page is always fitted to the same view
     * width, so the turned ink is also scaled by width/height to land back inside the page —
     * otherwise it would hang off the bottom (portrait -> landscape) or sit in a narrow band
     * down the middle (landscape -> portrait). Stroke widths follow the same scale.
     */
    fun rotatePage(pageIndex: Int, deltaDeg: Int, pageWidth: Float, pageHeight: Float) {
        val quarterTurns = (((deltaDeg / 90) % 4) + 4) % 4
        if (quarterTurns == 0 || pageWidth <= 0f || pageHeight <= 0f) return
        val known = pageStrokes[pageIndex]
        val unknown = pageUnknownStrokes[pageIndex]
        if (known.isNullOrEmpty() && unknown.isNullOrEmpty()) return
        // A live selection holds pre-rotation copies and base paths — they can't be salvaged.
        if (activeSelectionPageIndex == pageIndex) clearSelectionState()

        // Rotate about the origin, translate the page back into positive coordinates, then
        // fit the swapped-aspect page to the unchanged view width.
        val m = Matrix()
        val scale: Float
        when (quarterTurns) {
            1 -> { m.postRotate(90f); m.postTranslate(pageHeight, 0f); scale = pageWidth / pageHeight }
            2 -> { m.postRotate(180f); m.postTranslate(pageWidth, pageHeight); scale = 1f }
            else -> { m.postRotate(-90f); m.postTranslate(0f, pageWidth); scale = pageWidth / pageHeight }
        }
        m.postScale(scale, scale)

        known?.forEach { s ->
            if (s.type == StrokeType.IMAGE) {
                // A picture's frame must stay axis-aligned, so instead of turning the rect
                // itself we move its centre and spin the picture inside it.
                turnImageFrame(s, m, scale, quarterTurns * 90f)
                return@forEach
            }
            s.path.transform(m)
            s.paint.strokeWidth *= scale
            if (s.lineStyle != PenLineStyle.SOLID && s.paint.style != Paint.Style.FILL) {
                // Dash metrics are derived from the stroke width — rebuild at the new one.
                s.paint.pathEffect = PenLineStyle.pathEffect(s.lineStyle, s.paint.strokeWidth)
            }
            s.savedContours = null // geometry changed: re-flatten on the next save
        }
        // Unknown-tool strokes are held as raw JSON; turn their geometry in place so they
        // stay aligned with everything else when a newer build renders them.
        unknown?.forEach { transformRawStroke(it, m, scale) }
    }

    /**
     * Moves an IMAGE stroke's frame through [m] and adds [deltaRotation] to the picture's own
     * angle. The frame keeps its own width and height (scaled by [sizeScale]) rather than
     * being turned, so it stays the axis-aligned rect that everything else assumes.
     */
    private fun turnImageFrame(s: StrokeData, m: Matrix, sizeScale: Float, deltaRotation: Float) {
        val frame = RectF()
        s.path.computeBounds(frame, true)
        val centre = floatArrayOf(frame.centerX(), frame.centerY())
        m.mapPoints(centre)
        val halfW = frame.width() * sizeScale / 2f
        val halfH = frame.height() * sizeScale / 2f
        s.path.reset()
        s.path.addRect(
            centre[0] - halfW, centre[1] - halfH,
            centre[0] + halfW, centre[1] + halfH,
            Path.Direction.CW
        )
        s.imageRotation = normalizeDegrees(s.imageRotation + deltaRotation)
    }

    private fun normalizeDegrees(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    private fun transformRawStroke(o: JSONObject, m: Matrix, widthScale: Float) {
        if (o.has("width")) o.put("width", o.optDouble("width", 0.0) * widthScale)
        o.optJSONArray("rect")?.let { mapJsonRect(it, m) }
        o.optJSONArray("contours")?.let { cs ->
            for (i in 0 until cs.length()) cs.optJSONArray(i)?.let { mapJsonPoints(it, m) }
        }
    }

    /** Maps a flat `[x0,y0,x1,y1,…]` array through [m] in place. */
    private fun mapJsonPoints(a: org.json.JSONArray, m: Matrix) {
        val n = a.length() - (a.length() % 2)
        if (n == 0) return
        val pts = FloatArray(n) { a.optDouble(it, 0.0).toFloat() }
        m.mapPoints(pts)
        for (i in 0 until n) a.put(i, pts[i].toDouble())
    }

    /** Maps a `[l,t,r,b]` array through [m] in place, keeping it axis-aligned and sorted. */
    private fun mapJsonRect(a: org.json.JSONArray, m: Matrix) {
        if (a.length() < 4) return
        val r = RectF(
            a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(),
            a.optDouble(2, 0.0).toFloat(), a.optDouble(3, 0.0).toFloat()
        )
        m.mapRect(r)
        a.put(0, r.left.toDouble()); a.put(1, r.top.toDouble())
        a.put(2, r.right.toDouble()); a.put(3, r.bottom.toDouble())
    }

    /** Installs a single decoded page — used while a load streams pages in one at a time. */
    fun applyLoadedPage(p: Int, data: DrawingCodec.DecodedPage) {
        if (data.known.isNotEmpty()) pageStrokes[p] = data.known.toMutableList()
        if (data.unknown.isNotEmpty()) pageUnknownStrokes[p] = data.unknown.toMutableList()
    }

    // ------------------------------------------------------------------------
    //  PAGE MANIPULATION (insert, delete, duplicate, shift)
    // ------------------------------------------------------------------------

    private fun addStrokeToMemory(pageIndex: Int, stroke: StrokeData) {
        pageStrokes.getOrPut(pageIndex) { mutableListOf() }.add(stroke)
    }

    fun removePage(removeIndex: Int, currentTotalPages: Int) {
        pageStrokes.remove(removeIndex)
        pageUnknownStrokes.remove(removeIndex)
        for (i in (removeIndex + 1) until currentTotalPages) {
            val to = i - 1
            pageStrokes.remove(i)?.let { pageStrokes[to] = it }
            pageUnknownStrokes.remove(i)?.let { pageUnknownStrokes[to] = it }
        }
    }

    /** Renumbers every page's ink for a drag of [from] to [to]. */
    fun movePage(from: Int, to: Int) {
        if (from == to) return
        val strokes = pageStrokes.toMap()
        val unknown = pageUnknownStrokes.toMap()
        pageStrokes.clear()
        pageUnknownStrokes.clear()
        for ((p, v) in strokes) pageStrokes[pageIndexAfterMove(p, from, to)] = v
        for ((p, v) in unknown) pageUnknownStrokes[pageIndexAfterMove(p, from, to)] = v
    }

    fun duplicatePage(sourceIndex: Int, currentTotalPages: Int) {
        shiftPages(sourceIndex + 1, currentTotalPages)
        pageStrokes[sourceIndex]?.let { src ->
            pageStrokes[sourceIndex + 1] = src.map {
                it.copy(path = Path(it.path), paint = Paint(it.paint))
            }.toMutableList()
        }
        pageUnknownStrokes[sourceIndex]?.let { src ->
            pageUnknownStrokes[sourceIndex + 1] = src.map { JSONObject(it.toString()) }.toMutableList()
        }
    }

    fun shiftPages(insertIndex: Int, currentTotalPages: Int) {
        for (i in (currentTotalPages - 1) downTo insertIndex) {
            val newIndex = i + 1
            pageStrokes.remove(i)?.let { pageStrokes[newIndex] = it }
            pageUnknownStrokes.remove(i)?.let { pageUnknownStrokes[newIndex] = it }
        }
    }
}