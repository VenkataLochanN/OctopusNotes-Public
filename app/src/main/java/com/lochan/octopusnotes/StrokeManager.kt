package com.lochan.octopusnotes

import android.graphics.*
import org.json.JSONObject

class StrokeManager {

    private val pageStrokes = mutableMapOf<Int, MutableList<StrokeData>>()
    private val pageUnknownStrokes = mutableMapOf<Int, MutableList<JSONObject>>()

    var activeSelectionStrokes = mutableListOf<StrokeData>()
    var activeSelectionPageIndex = -1

    var originalSelectionStrokes = listOf<StrokeData>()
    var basePathsForTransform = listOf<Path>()
    var clipboardStrokes: List<StrokeData>? = null

    var selectionMutated = false

    var imagesDir: java.io.File? = null

    val fontsDir: java.io.File?
        get() = imagesDir?.parentFile?.let { java.io.File(it, FontManager.DIR_NAME) }

    private val imageBitmapCache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8)
            .coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024)
            .toInt()
    ) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
    }

    fun clearBitmapCache() {
        imageBitmapCache.evictAll()
        inkLayerCache.evictAll()
    }

    fun shutdown() {
        layerRenderExecutor.shutdown()
    }

    private val inkLayerMinStrokes = 30

    private data class InkLayerKey(val page: Int, val w: Int, val h: Int)

    private class InkLayer(val bitmap: Bitmap, val revision: Long, val renderedIds: Set<String>)

    private val pageRevisions = HashMap<Int, Long>()

    private val inkLayerCache = object : android.util.LruCache<InkLayerKey, InkLayer>(inkLayerBudget()) {
        override fun sizeOf(key: InkLayerKey, value: InkLayer): Int = value.bitmap.byteCount
    }

    private fun inkLayerBudget(): Int = (Runtime.getRuntime().maxMemory() / 8)
        .coerceIn(16L * 1024 * 1024, 96L * 1024 * 1024)
        .toInt()

    private val layerAllocFailures = HashSet<InkLayerKey>()

    private val layerRenderExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ink-layer-render").apply { isDaemon = true }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val pendingLayerRenders = HashMap<InkLayerKey, Long>()

    var onLayerRendered: ((page: Int) -> Unit)? = null

    private fun bumpPageRevision(page: Int) {
        if (page < 0) return
        pageRevisions[page] = (pageRevisions[page] ?: 0L) + 1L

        layerAllocFailures.removeIf { it.page == page }
    }

    private fun invalidateAllLayers() {
        pageRevisions.clear()
        inkLayerCache.evictAll()
    }

    private val inkBlitPaint = Paint().apply { isFilterBitmap = true }

    fun bitmapFor(stroke: StrokeData): Bitmap? {
        val file = stroke.imageFile ?: return null
        return decodeImage(file)
    }

    fun preloadBitmap(fileName: String): Bitmap? = decodeImage(fileName)

    private fun decodeImage(file: String): Bitmap? {
        imageBitmapCache.get(file)?.let {
            if (!it.isRecycled) return it

            imageBitmapCache.remove(file)
        }

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

    fun drawTableStroke(canvas: Canvas, stroke: StrokeData, alpha: Int = 255, zoom: Float = 1f) {
        val td = stroke.tableData ?: return
        val bounds = RectF()
        stroke.path.computeBounds(bounds, true)
        val rows = td.rows.coerceAtLeast(1)
        val cols = td.cols.coerceAtLeast(1)
        if (bounds.width() < 1f || bounds.height() < 1f) return

        val linePaint = if (alpha >= 255) stroke.paint else Paint(stroke.paint).apply { this.alpha = alpha }

        val rowY = td.rowBoundaries(bounds.height())
        val colX = td.colBoundaries(bounds.width())

        val merged = ArrayList<IntArray>()
        for ((k, span) in td.merges) {
            if (span.size < 2 || span[0] < 1 || span[1] < 1) continue
            val parts = k.split(",")
            val ar = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val ac = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (ar !in 0 until rows || ac !in 0 until cols) continue
            merged.add(intArrayOf(ac, ar, (ac + span[1]).coerceAtMost(cols), (ar + span[0]).coerceAtMost(rows)))
        }

        val radius = td.borderRadius.coerceIn(0f, minOf(bounds.width(), bounds.height()) / 2f)
        var clipActive = false
        if (radius > 0f) {
            val clipPath = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)
            clipActive = true
        }

        if (td.headerRow) {

            var headerBottom = rowY[1]
            for (m in merged) if (m[1] == 0) headerBottom = maxOf(headerBottom, rowY[m[3]])
            val c = td.headerColor ?: stroke.paint.color
            val headerPaint = Paint().apply {
                style = Paint.Style.FILL

                color = Color.argb(0x26 * Color.alpha(c) / 255, Color.red(c), Color.green(c), Color.blue(c))
            }
            if (alpha < 255) headerPaint.alpha = headerPaint.alpha * alpha / 255
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + headerBottom, headerPaint)
        }

        if (td.headerCol) {
            var headerRight = colX[1]
            for (m in merged) if (m[0] == 0) headerRight = maxOf(headerRight, colX[m[2]])
            val c = td.headerColColor ?: stroke.paint.color
            val colPaint = Paint().apply {
                style = Paint.Style.FILL
                color = Color.argb(0x26 * Color.alpha(c) / 255, Color.red(c), Color.green(c), Color.blue(c))
            }
            if (alpha < 255) colPaint.alpha = colPaint.alpha * alpha / 255
            canvas.drawRect(bounds.left, bounds.top, bounds.left + headerRight, bounds.bottom, colPaint)
        }

        val style = stroke.lineStyle
        for (i in 1 until cols) {
            val x = bounds.left + colX[i]
            var y0 = bounds.top
            for (m in merged.sortedBy { it[1] }) {
                if (m[0] >= i || i >= m[2]) continue
                val ys = bounds.top + rowY[m[1]]
                val ye = bounds.top + rowY[m[3]]
                if (y0 < ys) TableLineStyle.drawLine(canvas, linePaint, x, y0, x, ys, style)
                y0 = maxOf(y0, ye)
            }
            if (y0 < bounds.bottom) TableLineStyle.drawLine(canvas, linePaint, x, y0, x, bounds.bottom, style)
        }
        for (j in 1 until rows) {
            val y = bounds.top + rowY[j]
            var x0 = bounds.left
            for (m in merged.sortedBy { it[0] }) {
                if (m[1] >= j || j >= m[3]) continue
                val xs = bounds.left + colX[m[0]]
                val xe = bounds.left + colX[m[2]]
                if (x0 < xs) TableLineStyle.drawLine(canvas, linePaint, x0, y, xs, y, style)
                x0 = maxOf(x0, xe)
            }
            if (x0 < bounds.right) TableLineStyle.drawLine(canvas, linePaint, x0, y, bounds.right, y, style)
        }

        if (td.cells.isNotEmpty()) {
            val textPaint = android.text.TextPaint().apply {
                isAntiAlias = true
                color = stroke.paint.color
                textAlign = Paint.Align.CENTER
            }
            for ((key, value) in td.cells) {
                if (value.isBlank()) continue
                val parts = key.split(",")
                val r = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val c = parts.getOrNull(1)?.toIntOrNull() ?: continue
                if (r !in 0 until rows || c !in 0 until cols) continue

                if (td.isCovered(r, c)) continue

                val span = td.merges[key]
                val c1 = if (span != null && span.size >= 2 && span[1] > 1) (c + span[1]).coerceAtMost(cols) else c + 1
                val r1 = if (span != null && span.size >= 2 && span[0] > 1) (r + span[0]).coerceAtMost(rows) else r + 1
                val cell = RectF(
                    bounds.left + colX[c], bounds.top + rowY[r],
                    bounds.left + colX[c1], bounds.top + rowY[r1]
                )
                val lines = value.split("\n")

                textPaint.isFakeBoldText = (td.headerRow && r == 0) || (td.headerCol && c == 0)
                var size = (cell.height() * 0.52f).coerceIn(6f * zoom, 28f * zoom)
                textPaint.textSize = size

                while (size > 6f * zoom) {
                    val longest = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
                    if (longest <= cell.width() * 0.92f &&
                        textPaint.fontSpacing * lines.size <= cell.height() * 0.94f
                    ) break
                    size -= 1f
                    textPaint.textSize = size
                }
                if (alpha < 255) textPaint.alpha = alpha
                val layout = android.text.StaticLayout.Builder
                    .obtain(value, 0, value.length, textPaint, cell.width().toInt().coerceAtLeast(1))
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                    .build()
                canvas.save()
                canvas.clipRect(cell)
                canvas.translate(cell.left, cell.centerY() - layout.height / 2f)
                layout.draw(canvas)
                canvas.restore()
            }
        }
        if (clipActive) canvas.restore()

        TableLineStyle.drawPath(
            canvas,
            Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) },
            linePaint,
            style
        )
    }

    fun drawTextStroke(canvas: Canvas, stroke: StrokeData, alpha: Int = 255) {
        val td = stroke.textData ?: return
        if (td.text.isBlank()) return
        val bounds = RectF()
        stroke.path.computeBounds(bounds, true)
        if (bounds.width() < 1f || bounds.height() < 1f) return

        val paint = android.text.TextPaint().apply {
            isAntiAlias = true
            color = stroke.paint.color
            textSize = td.size.coerceAtLeast(4f)
        }
        FontManager.typeface(fontsDir, td.font)?.let { paint.typeface = it }
        if (alpha < 255) paint.alpha = alpha

        val layout = android.text.StaticLayout.Builder
            .obtain(td.text, 0, td.text.length, paint, bounds.width().toInt().coerceAtLeast(1))
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .build()

        canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(bounds.left, bounds.top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawImageStroke(canvas: Canvas, stroke: StrokeData, alpha: Int = 255) {
        val bmp = bitmapFor(stroke) ?: return
        val bounds = RectF()
        stroke.path.computeBounds(bounds, true)
        val rotation = stroke.imageRotation
        val paint = if (alpha >= 255) stroke.paint else Paint(stroke.paint).apply { this.alpha = alpha }
        if (rotation == 0f) {
            canvas.drawBitmap(bmp, null, bounds, paint)
            return
        }

        canvas.save()
        canvas.rotate(rotation, bounds.centerX(), bounds.centerY())
        canvas.drawBitmap(bmp, null, bounds, paint)
        canvas.restore()
    }

    fun drawTapeStroke(canvas: Canvas, stroke: StrokeData, alpha: Int = 255) {
        val bounds = RectF()
        stroke.path.computeBounds(bounds, true)
        val w = bounds.width()
        val h = bounds.height()
        if (w < 1f || h < 1f) return
        val base = stroke.paint.color

        val bodyAlpha = (alpha * ((base ushr 24) and 0xFF) / 255f).toInt().coerceIn(0, 255)
        val radius = (minOf(w, h) * 0.18f).coerceIn(2f, 10f)

        canvas.save()
        canvas.rotate(stroke.imageRotation, bounds.centerX(), bounds.centerY())

        canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })

        val td = stroke.tapeData
        val pattern = td?.pattern ?: TapePattern.SOLID
        if (td?.hollow == true) {

            canvas.drawRoundRect(bounds, radius, radius, Paint().apply {
                isAntiAlias = true; style = Paint.Style.STROKE
                strokeWidth = (minOf(w, h) * 0.09f).coerceIn(2f, 7f)
                color = tapeColor(base, bodyAlpha)
            })
            canvas.restore()
            return
        }

        canvas.drawRoundRect(bounds, radius, radius, Paint().apply {
            isAntiAlias = true; style = Paint.Style.FILL
            color = tapeColor(base, bodyAlpha)
        })

        if (pattern != TapePattern.SOLID) {

            fun overlayPaint(fill: Boolean, fraction: Float, width: Float = 1f) = Paint().apply {
                isAntiAlias = true
                style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
                strokeWidth = width
                color = tapeColor(Color.WHITE, (bodyAlpha * fraction).toInt().coerceIn(0, 255))
            }
            when (pattern) {
                TapePattern.STRIPES -> {
                    val paint = overlayPaint(fill = false, fraction = 0.40f, width = (h / 7f).coerceAtLeast(1.5f))
                    val spacing = (h / 2.5f).coerceAtLeast(5f)
                    var off = -h
                    while (off < w + h) {
                        canvas.drawLine(bounds.left + off, bounds.top, bounds.left + off + h, bounds.bottom, paint)
                        off += spacing
                    }
                }
                TapePattern.DOTS -> {
                    val paint = overlayPaint(fill = true, fraction = 0.45f)
                    val radius = (h / 7f).coerceAtLeast(1.5f)
                    val step = (h / 2f).coerceAtLeast(radius * 2f + 2f)
                    var row = 0
                    var y = bounds.top + step / 2f
                    while (y <= bounds.bottom) {
                        val stagger = if (row % 2 == 0) 0f else step / 2f
                        var x = bounds.left + step / 2f + stagger
                        while (x <= bounds.right) {
                            canvas.drawCircle(x, y, radius, paint)
                            x += step
                        }
                        y += step
                        row++
                    }
                }
                TapePattern.GRID -> {
                    val paint = overlayPaint(fill = false, fraction = 0.40f, width = (h / 9f).coerceAtLeast(1f))
                    val step = (h / 2f).coerceAtLeast(5f)
                    var x = bounds.left + step
                    while (x < bounds.right) {
                        canvas.drawLine(x, bounds.top, x, bounds.bottom, paint)
                        x += step
                    }
                    var y = bounds.top + step
                    while (y < bounds.bottom) {
                        canvas.drawLine(bounds.left, y, bounds.right, y, paint)
                        y += step
                    }
                }
                TapePattern.CHECKS -> {
                    val paint = overlayPaint(fill = true, fraction = 0.32f)
                    val cell = (h / 2f).coerceAtLeast(5f)
                    var row = 0
                    var y = bounds.top
                    while (y < bounds.bottom) {
                        var col = 0
                        var x = bounds.left
                        while (x < bounds.right) {
                            if ((row + col) % 2 == 0) {
                                canvas.drawRect(x, y, minOf(x + cell, bounds.right), minOf(y + cell, bounds.bottom), paint)
                            }
                            x += cell
                            col++
                        }
                        y += cell
                        row++
                    }
                }
            }
        }

        canvas.drawRoundRect(bounds, radius, radius, Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE
            strokeWidth = (h * 0.045f).coerceIn(1f, 4f)
            color = tapeColor(tapeDarken(base), bodyAlpha)
        })
        canvas.restore()
    }

    fun tapeAt(pageIndex: Int, x: Float, y: Float): StrokeData? {
        val strokes = pageStrokes[pageIndex] ?: return null
        for (i in strokes.indices.reversed()) {
            val s = strokes[i]
            if (s.type != StrokeType.TAPE) continue
            val b = RectF()
            s.path.computeBounds(b, true)
            if (b.isEmpty) continue
            var px = x
            var py = y
            if (s.imageRotation != 0f) {
                val pts = floatArrayOf(x, y)
                Matrix().apply { setRotate(-s.imageRotation, b.centerX(), b.centerY()) }.mapPoints(pts)
                px = pts[0]; py = pts[1]
            }
            if (b.contains(px, py)) return s
        }
        return null
    }

    private fun tapeColor(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)

    private fun tapeDarken(color: Int): Int {
        val r = ((color shr 16) and 0xFF) * 3 / 4
        val g = ((color shr 8) and 0xFF) * 3 / 4
        val b = (color and 0xFF) * 3 / 4
        return (r shl 16) or (g shl 8) or b
    }

    fun processPen(
        pageIndex: Int,
        path: Path,
        paint: Paint,
        toolType: DrawingView.Tool,
        lineStyle: String = PenLineStyle.SOLID,
        contourData: FloatArray? = null
    ): DrawingAction? {
        val type = if (toolType == DrawingView.Tool.HIGHLIGHTER) StrokeType.HIGHLIGHTER else StrokeType.PEN

        val style = if (type == StrokeType.PEN) lineStyle else PenLineStyle.SOLID
        val stroke = StrokeData(path = path, paint = paint, isPixelEraser = false, type = type, lineStyle = style)
        if (contourData != null) stroke.savedContours = listOf(contourData)
        return DrawingAction.AddStroke(pageIndex, stroke)
    }

    fun processErase(pageIndex: Int, path: Path, paint: Paint, toolType: DrawingView.Tool): DrawingAction? {
        return when (toolType) {
            DrawingView.Tool.PIXEL_ERASER -> {

                val strokes = snapshotPageStrokes(pageIndex) ?: return null
                computePixelErase(pageIndex, Path(path), paint.strokeWidth, strokes)
            }
            DrawingView.Tool.STROKE_ERASER -> performStrokeEraserAction(pageIndex, path, paint)
            else -> null
        }
    }

    fun snapshotPageStrokes(pageIndex: Int): List<StrokeData>? {

        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "snapshotPageStrokes must be called on the main thread"
        }
        val strokes = pageStrokes[pageIndex] ?: return null
        if (strokes.isEmpty()) return null
        return strokes.map { it.copy(path = Path(it.path), paint = Paint(it.paint)) }
    }

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
            if (stroke.isPixelEraser || stroke.type == StrokeType.IMAGE || stroke.type == StrokeType.TABLE || stroke.type == StrokeType.TEXT || stroke.type == StrokeType.TAPE) continue

            val strokeBounds = android.graphics.RectF()
            stroke.path.computeBounds(strokeBounds, true)

            if (android.graphics.RectF.intersects(strokeBounds, eraserBounds)) {
                val pieces = splitStrokeByEraser(stroke, eraserOutline, eraserRegion) ?: continue
                originalStrokes.add(stroke)
                somethingChanged = true
                newStrokes.addAll(pieces)
            }
        }
        return if (somethingChanged) DrawingAction.PixelErase(pageIndex, originalStrokes, newStrokes) else null
    }

    private fun splitStrokeByEraser(stroke: StrokeData, eraserOutline: Path, eraserRegion: android.graphics.Region): List<StrokeData>? {

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

            val b = android.graphics.RectF()
            stroke.path.computeBounds(b, true)
            return if (eraserRegion.contains(b.centerX().toInt(), b.centerY().toInt())) emptyList() else null
        }

        val step = maxOf(length / 2000f, 0.75f)
        val count = (length / step).toInt() + 1
        val edgeInset = width * 0.4f
        val erased = BooleanArray(count)
        var anyErased = false
        var anyKept = false
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        for (i in 0 until count) {
            val d = minOf(i * step, length)
            if (pm.getPosTan(d, pos, tan)) {

                val covered = eraserRegion.contains(pos[0].toInt(), pos[1].toInt()) ||
                    eraserRegion.contains((pos[0] - tan[1] * edgeInset).toInt(), (pos[1] + tan[0] * edgeInset).toInt()) ||
                    eraserRegion.contains((pos[0] + tan[1] * edgeInset).toInt(), (pos[1] - tan[0] * edgeInset).toInt())
                if (covered) { erased[i] = true; anyErased = true }
                else anyKept = true
            }
        }
        if (!anyErased) return null
        if (!anyKept) return emptyList()

        val pieces = mutableListOf<StrokeData>()
        var i = 0
        while (i < count) {
            if (erased[i]) { i++; continue }
            var j = i
            while (j < count && !erased[j]) j++
            val last = j - 1

            var t0 = if (i == 0) 0f else minOf(i * step + width / 2f, last * step)
            var t1 = if (last == count - 1) length else maxOf(last * step - width / 2f, i * step)

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

    private fun safeStrokeOutline(paint: Paint, path: Path, outPath: Path) {
        if (paint.style == Paint.Style.STROKE) {
            if (!paint.getFillPath(path, outPath)) {
                outPath.set(path)
            }
        } else {
            outPath.set(path)
        }
    }

    private fun performStrokeEraserAction(pageIndex: Int, eraserPath: Path, eraserPaint: Paint): DrawingAction.DeleteStrokes? {
        val strokes = pageStrokes[pageIndex] ?: return null
        val eraserOutline = Path()
        safeStrokeOutline(eraserPaint, eraserPath, eraserOutline)
        val eBounds = android.graphics.RectF().apply { eraserOutline.computeBounds(this, true) }

        val eraserRegion = android.graphics.Region()
        eraserRegion.setPath(
            eraserOutline,
            android.graphics.Region(
                (eBounds.left - 1).toInt(), (eBounds.top - 1).toInt(),
                (eBounds.right + 1).toInt(), (eBounds.bottom + 1).toInt()
            )
        )

        val erased = mutableListOf<StrokeData>()
        val iterator = strokes.iterator()
        while (iterator.hasNext()) {
            val stroke = iterator.next()

            if (stroke.isPixelEraser || stroke.type == StrokeType.IMAGE || stroke.type == StrokeType.TABLE || stroke.type == StrokeType.TEXT || stroke.type == StrokeType.TAPE) continue

            if (regionCoversStroke(stroke, eraserRegion, eBounds)) {
                erased.add(stroke)
                iterator.remove()
            }
        }
        return if (erased.isNotEmpty()) DrawingAction.DeleteStrokes(pageIndex, erased) else null
    }

    private fun regionCoversStroke(
        stroke: StrokeData,
        region: android.graphics.Region,
        regionBounds: android.graphics.RectF
    ): Boolean {
        val strokeOutline = Path()
        safeStrokeOutline(stroke.paint, stroke.path, strokeOutline)

        val sBounds = android.graphics.RectF().apply { strokeOutline.computeBounds(this, true) }
        if (!android.graphics.RectF.intersects(regionBounds, sBounds)) return false

        val strokeRegion = android.graphics.Region()
        strokeRegion.setPath(
            strokeOutline,
            android.graphics.Region(
                (sBounds.left - 1).toInt(), (sBounds.top - 1).toInt(),
                (sBounds.right + 1).toInt(), (sBounds.bottom + 1).toInt()
            )
        )
        val overlap = android.graphics.Region()
        val regionHit = !strokeRegion.isEmpty &&
            overlap.op(region, strokeRegion, android.graphics.Region.Op.INTERSECT) && !overlap.isEmpty
        return regionHit || (strokeRegion.isEmpty && strokeCoveredByRegion(stroke, region))
    }

    private fun strokeCoveredByRegion(stroke: StrokeData, region: android.graphics.Region): Boolean {
        val width = stroke.paint.strokeWidth.coerceAtLeast(0f)
        val pm = PathMeasure(stroke.path, false)
        val length = pm.length
        if (length <= 0f) {

            val b = android.graphics.RectF()
            stroke.path.computeBounds(b, true)
            return region.contains(b.centerX().toInt(), b.centerY().toInt())
        }
        val step = maxOf(length / 2000f, 0.75f)
        val count = (length / step).toInt() + 1
        val edgeInset = width * 0.4f
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        for (i in 0 until count) {
            if (pm.getPosTan(minOf(i * step, length), pos, tan)) {
                if (region.contains(pos[0].toInt(), pos[1].toInt()) ||
                    region.contains((pos[0] - tan[1] * edgeInset).toInt(), (pos[1] + tan[0] * edgeInset).toInt()) ||
                    region.contains((pos[0] + tan[1] * edgeInset).toInt(), (pos[1] - tan[0] * edgeInset).toInt())
                ) return true
            }
        }
        return false
    }

    fun computeSelectionInPath(
        lassoPagePath: Path,
        strokes: List<StrokeData>
    ): Pair<List<StrokeData>, RectF>? {
        val selectedItems = mutableListOf<StrokeData>()
        val lassoBounds = android.graphics.RectF().apply { lassoPagePath.computeBounds(this, true) }

        val lassoRegion = android.graphics.Region()
        lassoRegion.setPath(
            lassoPagePath,
            android.graphics.Region(
                (lassoBounds.left - 1).toInt(), (lassoBounds.top - 1).toInt(),
                (lassoBounds.right + 1).toInt(), (lassoBounds.bottom + 1).toInt()
            )
        )

        for (stroke in strokes) {
            if (stroke.isPixelEraser) continue
            if (regionCoversStroke(stroke, lassoRegion, lassoBounds)) {
                selectedItems.add(stroke)
            }
        }
        if (selectedItems.isEmpty()) return null
        return Pair(selectedItems, getSelectionBounds(selectedItems))
    }

    fun selectStrokesInPath(pageIndex: Int, lassoPagePath: Path): Pair<List<StrokeData>, RectF>? {
        val strokes = pageStrokes[pageIndex] ?: return null
        val result = computeSelectionInPath(lassoPagePath, strokes) ?: return null
        return beginSelection(pageIndex, result.first)
    }

    fun selectStrokes(pageIndex: Int, strokes: List<StrokeData>): Pair<List<StrokeData>, RectF>? {
        if (strokes.isEmpty()) return null
        return beginSelection(pageIndex, strokes)
    }

    fun beginSelection(pageIndex: Int, selectedItems: List<StrokeData>): Pair<List<StrokeData>, RectF> {

        activeSelectionStrokes = selectedItems.map { it.copy(path = Path(it.path), paint = Paint(it.paint)) }.toMutableList()
        originalSelectionStrokes = selectedItems.toList()
        basePathsForTransform = activeSelectionStrokes.map { Path(it.path) }
        activeSelectionPageIndex = pageIndex
        selectionMutated = false

        bumpPageRevision(pageIndex)

        val unionBounds = getSelectionBounds()
        return Pair(activeSelectionStrokes, unionBounds)
    }

    fun getSelectionBounds(): RectF = getSelectionBounds(activeSelectionStrokes)

    private fun getSelectionBounds(items: List<StrokeData>): RectF {
        val bounds = RectF()
        var isFirst = true
        items.forEach {
            val b = visualBounds(it)
            if (isFirst) { bounds.set(b); isFirst = false } else bounds.union(b)
        }
        return bounds
    }

    fun visualBounds(stroke: StrokeData): RectF {
        val b = RectF()
        stroke.path.computeBounds(b, true)
        if ((stroke.type != StrokeType.IMAGE && stroke.type != StrokeType.TAPE) || stroke.imageRotation == 0f) return b
        Matrix().apply { setRotate(stroke.imageRotation, b.centerX(), b.centerY()) }.mapRect(b)
        return b
    }

    fun applyAbsoluteRotation(angleDegrees: Float) {
        if (basePathsForTransform.isEmpty()) return
        selectionMutated = true
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
            if (stroke.type == StrokeType.IMAGE || stroke.type == StrokeType.TAPE) {

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
            stroke.savedContours = null
        }
    }

    fun flipSelection(horizontal: Boolean, vertical: Boolean) {
        selectionMutated = true
        val bounds = getSelectionBounds()
        val matrix = Matrix()
        matrix.postScale(if (horizontal) -1f else 1f, if (vertical) -1f else 1f, bounds.centerX(), bounds.centerY())

        activeSelectionStrokes.forEach {
            it.path.transform(matrix)
            it.savedContours = null
        }
        basePathsForTransform.forEach { it.transform(matrix) }
    }

    fun deleteSelection(): DrawingAction.DeleteStrokes? {
        if (activeSelectionPageIndex == -1 || originalSelectionStrokes.isEmpty()) return null
        val action = DrawingAction.DeleteStrokes(activeSelectionPageIndex, originalSelectionStrokes)
        clearSelectionState()
        return action
    }

    fun cancelSelection() {
        clearSelectionState()
    }

    private fun clearSelectionState() {
        val page = activeSelectionPageIndex
        activeSelectionStrokes.clear()
        originalSelectionStrokes = emptyList()
        basePathsForTransform = emptyList()
        activeSelectionPageIndex = -1
        selectionMutated = false

        if (page >= 0) bumpPageRevision(page)
    }

    fun addStrokeToPage(pageIndex: Int, stroke: StrokeData) {
        pageStrokes.getOrPut(pageIndex) { mutableListOf() }.add(stroke)
        bumpPageRevision(pageIndex)
    }

    fun removeStrokeFromPage(pageIndex: Int, strokeId: String) {
        removeStrokesFromPage(pageIndex, setOf(strokeId))
    }

    fun removeStrokesFromPage(pageIndex: Int, strokeIds: Set<String>) {
        val list = pageStrokes[pageIndex] ?: return
        if (strokeIds.isEmpty()) return
        list.removeAll { it.id in strokeIds }
        bumpPageRevision(pageIndex)
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
        bumpPageRevision(pageIndex)
    }

    data class CommitResult(
        val action: DrawingAction,
        val pageIndex: Int,
        val newStrokes: List<StrokeData>
    )

    fun selectionBoundsAfter(
        pdfDx: Float,
        pdfDy: Float,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        pivotXFrac: Float = 0f,
        pivotYFrac: Float = 0f
    ): RectF? {
        if (activeSelectionStrokes.isEmpty()) return null
        val bounds = getSelectionBounds()
        commitMatrix(pdfDx, pdfDy, scaleX, scaleY, pivotXFrac, pivotYFrac, 0f, 0f, bounds).mapRect(bounds)
        return bounds
    }

    private fun commitMatrix(
        pdfDx: Float,
        pdfDy: Float,
        scaleX: Float,
        scaleY: Float,
        pivotXFrac: Float,
        pivotYFrac: Float,
        rebaseDx: Float,
        rebaseDy: Float,
        pivot: RectF
    ) = Matrix().apply {
        if (scaleX != 1f || scaleY != 1f) {
            postScale(
                scaleX, scaleY,
                pivot.left + pivotXFrac * pivot.width(),
                pivot.top + pivotYFrac * pivot.height()
            )
        }
        postTranslate(pdfDx + rebaseDx, pdfDy + rebaseDy)
    }

    fun commitSelection(
        pdfDx: Float,
        pdfDy: Float,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        pivotXFrac: Float = 0f,
        pivotYFrac: Float = 0f,
        targetPageIndex: Int = activeSelectionPageIndex,
        rebaseDx: Float = 0f,
        rebaseDy: Float = 0f
    ): CommitResult? {
        if (activeSelectionPageIndex == -1 || activeSelectionStrokes.isEmpty()) return null

        val sourcePage = activeSelectionPageIndex
        val matrix = commitMatrix(
            pdfDx, pdfDy, scaleX, scaleY, pivotXFrac, pivotYFrac,
            rebaseDx, rebaseDy, getSelectionBounds()
        )

        val widthScale = if (scaleX == scaleY) scaleX else kotlin.math.sqrt(scaleX * scaleY)
        val finalStrokes = activeSelectionStrokes.map { stroke ->
            val newPath = Path(stroke.path)
            newPath.transform(matrix)
            val newPaint = Paint(stroke.paint)
            if (widthScale != 1f && newPaint.style == Paint.Style.STROKE) newPaint.strokeWidth *= widthScale

            val newText = if (widthScale != 1f) stroke.textData?.let { it.copy(size = it.size * widthScale) } else stroke.textData
            stroke.copy(path = newPath, paint = newPaint, textData = newText)
        }

        val action = if (targetPageIndex == sourcePage) {
            DrawingAction.ReplaceStrokes(sourcePage, originalSelectionStrokes, finalStrokes)
        } else {

            DrawingAction.BatchAction(
                listOf(DrawingAction.DeleteStrokes(sourcePage, originalSelectionStrokes)) +
                        finalStrokes.map { DrawingAction.AddStroke(targetPageIndex, it) }
            )
        }
        clearSelectionState()
        return CommitResult(action, targetPageIndex, finalStrokes)
    }

    fun drawToLayer(canvas: Canvas, pageWidth: Float, pageHeight: Float, displayedPage: Int, zoom: Float) {
        val strokes = pageStrokes[displayedPage] ?: return
        canvas.save()
        canvas.scale(zoom, zoom)
        val selectedIds = if (displayedPage == activeSelectionPageIndex) activeSelectionStrokes.map { it.id } else emptyList()

        for (stroke in strokes) {
            if (stroke.type != StrokeType.IMAGE || stroke.id in selectedIds) continue
            drawImageStroke(canvas, stroke)
        }
        for (stroke in strokes) {
            if (stroke.type != StrokeType.TABLE || stroke.id in selectedIds) continue
            drawTableStroke(canvas, stroke)
        }
        for (stroke in strokes) {
            if (stroke.type != StrokeType.TEXT || stroke.id in selectedIds) continue
            drawTextStroke(canvas, stroke)
        }
        for (stroke in strokes) {
            if (stroke.type != StrokeType.HIGHLIGHTER || stroke.id in selectedIds) continue
            canvas.drawPath(stroke.path, stroke.paint)
        }
        for (stroke in strokes) {
            if (stroke.type == StrokeType.HIGHLIGHTER || stroke.type == StrokeType.IMAGE || stroke.type == StrokeType.TABLE || stroke.type == StrokeType.TEXT || stroke.id in selectedIds) continue
            canvas.drawPath(stroke.path, stroke.paint)
        }
        for (stroke in strokes) {
            if (stroke.type != StrokeType.TAPE || stroke.id in selectedIds) continue
            drawTapeStroke(canvas, stroke)
        }
        canvas.restore()
    }

    fun drawPageStrokes(
        pageIndex: Int,
        canvas: Canvas,
        scaleX: Float,
        scaleY: Float,
        ghostSelected: Boolean = true
    ) {
        val strokes = pageStrokes[pageIndex] ?: return

        val ghostIds = if (ghostSelected && pageIndex == activeSelectionPageIndex && activeSelectionStrokes.isNotEmpty()) {
            val ids = HashSet<String>()
            activeSelectionStrokes.forEach { ids.add(it.id) }
            ids
        } else emptySet()
        drawStrokes(strokes, canvas, scaleX, scaleY, ghostIds)
    }

    private fun drawStrokes(
        strokes: List<StrokeData>,
        canvas: Canvas,
        scaleX: Float,
        scaleY: Float,
        ghostIds: Set<String>
    ) {
        canvas.save()
        canvas.scale(scaleX, scaleY)

        for (s in strokes) if (s.type == StrokeType.IMAGE) {
            drawImageStroke(canvas, s, if (s.id in ghostIds) GHOST_ALPHA else 255)
        }
        for (s in strokes) if (s.type == StrokeType.TABLE) {
            drawTableStroke(canvas, s, if (s.id in ghostIds) GHOST_ALPHA else 255)
        }
        for (s in strokes) if (s.type == StrokeType.TEXT) {
            drawTextStroke(canvas, s, if (s.id in ghostIds) GHOST_ALPHA else 255)
        }
        for (s in strokes) if (s.type == StrokeType.HIGHLIGHTER) canvas.drawPath(s.path, ghostPaint(s, ghostIds))
        for (s in strokes) if (s.type != StrokeType.HIGHLIGHTER && s.type != StrokeType.IMAGE && s.type != StrokeType.TABLE && s.type != StrokeType.TEXT && s.type != StrokeType.TAPE) canvas.drawPath(s.path, ghostPaint(s, ghostIds))
        for (s in strokes) if (s.type == StrokeType.TAPE) {
            drawTapeStroke(canvas, s, if (s.id in ghostIds) GHOST_ALPHA else 255)
        }
        canvas.restore()
    }

    fun drawInkLayer(
        canvas: Canvas,
        pageIndex: Int,
        viewW: Float,
        viewH: Float,
        scaleX: Float,
        scaleY: Float,
        ghostSelected: Boolean = true
    ) {
        val strokes = pageStrokes[pageIndex]
        if (strokes.isNullOrEmpty()) return
        val w = viewW.toInt()
        val h = viewH.toInt()
        if (w <= 0 || h <= 0) return

        if (strokes.size < inkLayerMinStrokes) {
            drawPageStrokes(pageIndex, canvas, scaleX, scaleY, ghostSelected)
            return
        }

        val key = InkLayerKey(pageIndex, w, h)

        if (w.toLong() * h * 4L > inkLayerBudget()) {
            drawPageStrokes(pageIndex, canvas, scaleX, scaleY, ghostSelected)
            return
        }

        if (key in layerAllocFailures) {
            drawPageStrokes(pageIndex, canvas, scaleX, scaleY, ghostSelected)
            return
        }

        val rev = pageRevisions[pageIndex] ?: 0L
        val cached = inkLayerCache.get(key)
        if (cached != null && !cached.bitmap.isRecycled && cached.revision == rev) {
            canvas.drawBitmap(cached.bitmap, 0f, 0f, inkBlitPaint)
            return
        }

        if (cached != null && !cached.bitmap.isRecycled) {
            requestAsyncLayerRender(pageIndex, w, h, rev, scaleX, scaleY)
            canvas.drawBitmap(cached.bitmap, 0f, 0f, inkBlitPaint)

            val ghostIds = if (pageIndex == activeSelectionPageIndex && activeSelectionStrokes.isNotEmpty()) {
                HashSet<String>().apply { activeSelectionStrokes.forEach { add(it.id) } }
            } else emptySet()
            val missing = strokes.filter { it.id !in cached.renderedIds }
            if (missing.isNotEmpty()) drawStrokes(missing, canvas, scaleX, scaleY, ghostIds)
            return
        }

        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            layerAllocFailures.add(key)
            drawPageStrokes(pageIndex, canvas, scaleX, scaleY, ghostSelected)
            return
        }
        drawPageStrokes(pageIndex, Canvas(bmp), scaleX, scaleY, ghostSelected)
        inkLayerCache.put(key, InkLayer(bmp, rev, strokes.mapTo(HashSet()) { it.id }))
        canvas.drawBitmap(bmp, 0f, 0f, inkBlitPaint)
    }

    private fun requestAsyncLayerRender(page: Int, w: Int, h: Int, rev: Long, scaleX: Float, scaleY: Float) {
        val key = InkLayerKey(page, w, h)

        if (pendingLayerRenders[key] == rev) return
        if (layerRenderExecutor.isShutdown) return
        val strokes = snapshotPageStrokes(page) ?: return
        val ghostIds = if (page == activeSelectionPageIndex && activeSelectionStrokes.isNotEmpty()) {
            HashSet<String>().apply { activeSelectionStrokes.forEach { add(it.id) } }
        } else emptySet()
        pendingLayerRenders[key] = rev
        layerRenderExecutor.execute {
            val bmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bmp != null) drawStrokes(strokes, Canvas(bmp), scaleX, scaleY, ghostIds)
            mainHandler.post {
                val stillCurrent = pendingLayerRenders[key] == rev
                if (stillCurrent) pendingLayerRenders.remove(key)
                if (bmp == null) {

                    if (stillCurrent) layerAllocFailures.add(key)
                    onLayerRendered?.invoke(page)
                    return@post
                }

                if (stillCurrent && pageRevisions[page] == rev) {
                    inkLayerCache.put(key, InkLayer(bmp, rev, strokes.mapTo(HashSet()) { it.id }))
                } else {
                    bmp.recycle()
                }
                onLayerRendered?.invoke(page)
            }
        }
    }

    private fun ghostPaint(s: StrokeData, ghostIds: Set<String>): Paint {
        if (s.id !in ghostIds) return s.paint
        val g = Paint(s.paint)
        g.alpha = (g.alpha * GHOST_ALPHA / 255).coerceIn(0, 255)
        return g
    }

    companion object {

        private const val GHOST_ALPHA = 72
    }

    fun knownStrokesForPage(page: Int): List<StrokeData> = pageStrokes[page] ?: emptyList()
    fun unknownStrokesForPage(page: Int): List<JSONObject> = pageUnknownStrokes[page] ?: emptyList()
    fun allPagesWithData(): Set<Int> = pageStrokes.keys + pageUnknownStrokes.keys

    fun loadDecodedData(pages: Map<Int, DrawingCodec.DecodedPage>) {
        pageStrokes.clear()
        pageUnknownStrokes.clear()
        clearBitmapCache()
        invalidateAllLayers()
        for ((p, data) in pages) applyLoadedPage(p, data)
    }

    fun rescaleAll(factor: Float, extraStrokes: Collection<StrokeData> = emptyList()) {
        if (factor <= 0f || factor == 1f) return
        val m = Matrix().apply { setScale(factor, factor) }
        invalidateAllLayers()
        clearSelectionState()
        val transformed =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap<StrokeData, Boolean>())
        fun rescaleStroke(s: StrokeData) {
            if (!transformed.add(s)) return
            s.path.transform(m)
            if (s.paint.style == Paint.Style.STROKE && s.type != StrokeType.IMAGE) {
                s.paint.strokeWidth *= factor

                if (s.lineStyle != PenLineStyle.SOLID) {
                    s.paint.pathEffect = PenLineStyle.pathEffect(s.lineStyle, s.paint.strokeWidth)
                }
            }

            s.textData?.let { s.textData = it.copy(size = it.size * factor) }
            s.tableData?.let { td ->
                if (td.borderRadius > 0f) {
                    s.tableData = td.copy(borderRadius = td.borderRadius * factor)
                }
            }
            s.savedContours = null
        }
        for (strokes in pageStrokes.values) for (s in strokes) rescaleStroke(s)
        for (s in extraStrokes) rescaleStroke(s)

        for (strokes in pageUnknownStrokes.values) for (o in strokes) rescaleRawStroke(o, factor)
    }

    private fun rescaleRawStroke(o: JSONObject, factor: Float) {

        if (o.has("width")) o.put("width", o.optDouble("width", 0.0) * factor)
        if (o.has("textSize")) o.put("textSize", o.optDouble("textSize", 0.0) * factor)
        o.optJSONArray("rect")?.let { scaleJsonFloats(it, factor) }
        o.optJSONArray("contours")?.let { cs ->
            for (i in 0 until cs.length()) cs.optJSONArray(i)?.let { scaleJsonFloats(it, factor) }
        }
    }

    private fun scaleJsonFloats(a: org.json.JSONArray, factor: Float) {
        for (i in 0 until a.length()) a.put(i, a.optDouble(i, 0.0) * factor)
    }

    fun rotatePage(pageIndex: Int, deltaDeg: Int, pageWidth: Float, pageHeight: Float) {
        val quarterTurns = (((deltaDeg / 90) % 4) + 4) % 4
        if (quarterTurns == 0 || pageWidth <= 0f || pageHeight <= 0f) return
        val known = pageStrokes[pageIndex]
        val unknown = pageUnknownStrokes[pageIndex]
        if (known.isNullOrEmpty() && unknown.isNullOrEmpty()) return

        if (activeSelectionPageIndex == pageIndex) clearSelectionState()

        val m = Matrix()
        val scale: Float
        when (quarterTurns) {
            1 -> { m.postRotate(90f); m.postTranslate(pageHeight, 0f); scale = pageWidth / pageHeight }
            2 -> { m.postRotate(180f); m.postTranslate(pageWidth, pageHeight); scale = 1f }
            else -> { m.postRotate(-90f); m.postTranslate(0f, pageWidth); scale = pageWidth / pageHeight }
        }
        m.postScale(scale, scale)

        known?.forEach { s ->
            if (s.type == StrokeType.IMAGE || s.type == StrokeType.TAPE) {

                turnImageFrame(s, m, scale, quarterTurns * 90f)
                return@forEach
            }
            s.path.transform(m)
            s.paint.strokeWidth *= scale

            s.textData?.let { s.textData = it.copy(size = it.size * scale) }
            if (s.lineStyle != PenLineStyle.SOLID && s.paint.style != Paint.Style.FILL) {

                s.paint.pathEffect = PenLineStyle.pathEffect(s.lineStyle, s.paint.strokeWidth)
            }
            s.savedContours = null
        }

        unknown?.forEach { transformRawStroke(it, m, scale) }
        bumpPageRevision(pageIndex)
    }

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

    private fun mapJsonPoints(a: org.json.JSONArray, m: Matrix) {
        val n = a.length() - (a.length() % 2)
        if (n == 0) return
        val pts = FloatArray(n) { a.optDouble(it, 0.0).toFloat() }
        m.mapPoints(pts)
        for (i in 0 until n) a.put(i, pts[i].toDouble())
    }

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

    fun applyLoadedPage(p: Int, data: DrawingCodec.DecodedPage) {
        if (data.known.isNotEmpty()) pageStrokes[p] = data.known.toMutableList()
        if (data.unknown.isNotEmpty()) pageUnknownStrokes[p] = data.unknown.toMutableList()
        bumpPageRevision(p)
    }

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

        invalidateAllLayers()
    }

    fun movePage(from: Int, to: Int) {
        if (from == to) return
        val strokes = pageStrokes.toMap()
        val unknown = pageUnknownStrokes.toMap()
        pageStrokes.clear()
        pageUnknownStrokes.clear()
        for ((p, v) in strokes) pageStrokes[pageIndexAfterMove(p, from, to)] = v
        for ((p, v) in unknown) pageUnknownStrokes[pageIndexAfterMove(p, from, to)] = v

        invalidateAllLayers()
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
        invalidateAllLayers()
    }

    fun shiftPages(insertIndex: Int, currentTotalPages: Int) {
        for (i in (currentTotalPages - 1) downTo insertIndex) {
            val newIndex = i + 1
            pageStrokes.remove(i)?.let { pageStrokes[newIndex] = it }
            pageUnknownStrokes.remove(i)?.let { pageUnknownStrokes[newIndex] = it }
        }

        invalidateAllLayers()
    }
}