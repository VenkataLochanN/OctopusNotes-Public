package com.lochan.octopusnotes

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.io.StringReader

object DrawingCodec {

    const val FORMAT_VERSION = 1
    private const val SAMPLE_STEP = 2f // page-space px between sampled points

    /**
     * Page space is "px at the laid-out page width" — and since a page spans the full
     * window, that width is the screen width, which CHANGES WHEN THE DEVICE ROTATES.
     * Every document therefore records the width it was written at, so a load into a
     * differently-sized viewport can scale the ink back onto the page instead of leaving
     * it bunched in the middle (portrait -> landscape) or hanging off the right edge
     * (landscape -> portrait).
     *
     * Written before "pages" so the streaming decoder knows the scale before it reads
     * the first stroke (org.json preserves insertion order).
     */
    private const val KEY_BASE_WIDTH = "baseWidth"

    /**
     * IMAGE strokes: degrees the picture is turned clockwise inside its rect. An angle, not
     * a length, so unlike every other geometry field it must NOT be touched by the page-space
     * rescale. Builds that predate it simply draw the picture unturned.
     */
    private const val KEY_IMAGE_ROTATION = "imgRot"

    /** A page after decoding: strokes we can render + raw strokes we must preserve. */
    data class DecodedPage(
        val known: MutableList<StrokeData>,
        val unknown: MutableList<JSONObject>
    )

    data class DecodeResult(
        val pages: MutableMap<Int, DecodedPage>,
        /** type -> count, for tools this build can't render. */
        val unsupportedTypeCounts: Map<String, Int>
    )

    // ---------------- ENCODE ----------------

    /** [baseWidth] is the page width (px) the strokes being written are expressed in. */
    fun encodeDocument(
        pageIndices: Collection<Int>,
        knownProvider: (Int) -> List<StrokeData>,
        unknownProvider: (Int) -> List<JSONObject>,
        baseWidth: Float
    ): String {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        if (baseWidth > 0f) root.put(KEY_BASE_WIDTH, baseWidth.toDouble())

        val pages = JSONObject()
        for (p in pageIndices) {
            val strokes = JSONArray()
            for (sd in knownProvider(p)) strokes.put(encodeStroke(sd))
            // Preserve unknown-tool strokes verbatim (round-trip safe).
            for (u in unknownProvider(p)) strokes.put(u)

            if (strokes.length() > 0) {
                pages.put(p.toString(), JSONObject().put("strokes", strokes))
            }
        }
        root.put("pages", pages)
        return root.toString()
    }

    private fun encodeStroke(stroke: StrokeData): JSONObject {
        val o = JSONObject()
        o.put("id", stroke.id)
        o.put("type", stroke.type)
        o.put("v", 1)

        // Images: destination rect + file reference, no contours.
        if (stroke.type == StrokeType.IMAGE) {
            val b = android.graphics.RectF()
            stroke.path.computeBounds(b, true)
            o.put("imageFile", stroke.imageFile ?: "")
            o.put("rect", JSONArray()
                .put(b.left.toDouble()).put(b.top.toDouble())
                .put(b.right.toDouble()).put(b.bottom.toDouble()))
            // Omitted when unturned, so files from before image rotation existed stay byte-alike.
            if (stroke.imageRotation != 0f) o.put(KEY_IMAGE_ROTATION, stroke.imageRotation.toDouble())
            return o
        }

        o.put("style", if (stroke.paint.style == Paint.Style.FILL) "FILL" else "STROKE")
        o.put("color", stroke.paint.color)
        o.put("width", stroke.paint.strokeWidth.toDouble())
        if (stroke.lineStyle != PenLineStyle.SOLID) o.put("lineStyle", stroke.lineStyle)

        o.put("cap", when (stroke.paint.strokeCap) {
            Paint.Cap.SQUARE -> "SQUARE"
            Paint.Cap.BUTT -> "BUTT"
            else -> "ROUND"
        })

        // Lossless round-trip: reuse the exact points the stroke was decoded from (or
        // previously encoded to). Only strokes whose geometry actually changed get
        // re-flattened — re-sampling on every save compounds error across cycles.
        val contourData = stroke.savedContours ?: pathToContours(stroke.path).also {
            stroke.savedContours = it
        }
        val contours = JSONArray()
        for (c in contourData) {
            val arr = JSONArray()
            for (f in c) arr.put(f.toDouble())
            contours.put(arr)
        }
        o.put("contours", contours)
        return o
    }

    // ---------------- DECODE ----------------

    fun decodeDocument(
        json: String,
        targetWidth: Float = 0f,
        fallbackBaseWidth: Float = 0f
    ): DecodeResult = decodeDocument(StringReader(json), targetWidth, fallbackBaseWidth)

    /**
     * Streams the document instead of building a JSON tree first. Coordinates go straight
     * from the reader into FloatArrays — the tree form boxed every one of them as a Double,
     * which dominated open time (and GC) on ink-heavy notebooks.
     *
     * [onPage] fires as soon as each page finishes decoding, so the UI can show ink
     * progressively rather than waiting for the whole notebook.
     *
     * Geometry is rescaled from the document's [KEY_BASE_WIDTH] to [targetWidth] (the page
     * width it is about to be drawn at) — see that constant for why. [fallbackBaseWidth] is
     * used for documents written before the field existed; pass 0 for either to skip scaling.
     */
    fun decodeDocument(
        source: Reader,
        targetWidth: Float = 0f,
        fallbackBaseWidth: Float = 0f,
        onPage: ((Int, DecodedPage) -> Unit)? = null
    ): DecodeResult {
        val pages = mutableMapOf<Int, DecodedPage>()
        val unsupported = HashMap<String, Int>()
        var baseWidth = 0f

        JsonReader(source.buffered()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    KEY_BASE_WIDTH -> baseWidth = r.nextDouble().toFloat()
                    "pages" -> {
                        val base = if (baseWidth > 0f) baseWidth else fallbackBaseWidth
                        val scale =
                            if (targetWidth > 0f && base > 0f) targetWidth / base else 1f
                        r.beginObject()
                        while (r.hasNext()) {
                            val pageIndex = r.nextName().toIntOrNull()
                            if (pageIndex == null) { r.skipValue(); continue }
                            val page = decodePage(r, unsupported, scale)
                            pages[pageIndex] = page
                            onPage?.invoke(pageIndex, page)
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return DecodeResult(pages, unsupported)
    }

    private fun decodePage(
        r: JsonReader,
        unsupported: HashMap<String, Int>,
        scale: Float
    ): DecodedPage {
        val known = mutableListOf<StrokeData>()
        val unknown = mutableListOf<JSONObject>()
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "strokes") { r.skipValue(); continue }
            r.beginArray()
            while (r.hasNext()) {
                val raw = readStroke(r, scale)
                if (raw == null) continue
                if (raw.type !in StrokeType.SUPPORTED) {
                    // Unknown tool: preserve verbatim + count for the notification.
                    unknown.add(raw.toJson())
                    unsupported[raw.type] = (unsupported[raw.type] ?: 0) + 1
                } else {
                    val sd = try { raw.toStrokeData() } catch (e: Exception) { null }
                    // Corrupt known stroke: preserve, don't notify.
                    if (sd != null) known.add(sd) else unknown.add(raw.toJson())
                }
            }
            r.endArray()
        }
        r.endObject()
        return DecodedPage(known, unknown)
    }

    /**
     * A stroke's fields as read off the wire. Held in this flat form (rather than a
     * JSONObject) so the common path allocates nothing beyond the contour arrays; the
     * verbatim JSON is rebuilt only for the rare unknown/corrupt stroke.
     */
    private class RawStroke {
        var id: String? = null
        var type: String = "unknown"
        var version: Int? = null
        var style: String? = null
        var color: Int? = null
        var width: Float? = null
        var lineStyle: String? = null
        var cap: String? = null
        var imageFile: String? = null
        var imageRotation: Float? = null
        var rect: FloatArray? = null
        var contours: ArrayList<FloatArray>? = null
        /** Fields this build doesn't know about, kept so unknown strokes round-trip intact. */
        var extra: JSONObject? = null

        fun toJson(): JSONObject {
            val o = JSONObject()
            id?.let { o.put("id", it) }
            o.put("type", type)
            version?.let { o.put("v", it) }
            style?.let { o.put("style", it) }
            color?.let { o.put("color", it) }
            width?.let { o.put("width", it.toDouble()) }
            lineStyle?.let { o.put("lineStyle", it) }
            cap?.let { o.put("cap", it) }
            imageFile?.let { o.put("imageFile", it) }
            imageRotation?.let { o.put(KEY_IMAGE_ROTATION, it.toDouble()) }
            rect?.let { rc ->
                o.put("rect", JSONArray().apply { for (f in rc) put(f.toDouble()) })
            }
            contours?.let { cs ->
                o.put("contours", JSONArray().apply {
                    for (c in cs) put(JSONArray().apply { for (f in c) put(f.toDouble()) })
                })
            }
            extra?.let { ex ->
                val keys = ex.keys()
                while (keys.hasNext()) { val k = keys.next(); o.put(k, ex.get(k)) }
            }
            return o
        }

        fun toStrokeData(): StrokeData {
            val strokeId = id ?: java.util.UUID.randomUUID().toString()

            if (type == StrokeType.IMAGE) {
                val rc = rect ?: throw IllegalArgumentException("image stroke without rect")
                val path = Path().apply { addRect(rc[0], rc[1], rc[2], rc[3], Path.Direction.CW) }
                val paint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
                return StrokeData(
                    strokeId, path, paint, false, type,
                    imageFile = imageFile ?: "",
                    imageRotation = imageRotation ?: 0f
                )
            }

            // All ink tools share the generic contour representation.
            val closed = style == "FILL"
            val strokeWidth = width ?: 10f
            val line = lineStyle ?: PenLineStyle.SOLID
            val cs = contours ?: ArrayList()

            val capValue = when (cap) {
                "SQUARE" -> Paint.Cap.SQUARE
                "BUTT" -> Paint.Cap.BUTT
                else -> Paint.Cap.ROUND
            }
            val paint = Paint().apply {
                isAntiAlias = true
                this.style = if (closed) Paint.Style.FILL else Paint.Style.STROKE
                strokeJoin = if (capValue == Paint.Cap.ROUND) Paint.Join.ROUND else Paint.Join.BEVEL
                strokeCap = capValue
                this.color = this@RawStroke.color ?: Color.BLACK
                this.strokeWidth = strokeWidth
            }
            // Restore the pen line style's dash/dot path effect (no-op for SOLID/non-pen).
            if (line != PenLineStyle.SOLID && !closed) {
                paint.pathEffect = PenLineStyle.pathEffect(line, strokeWidth)
            }
            return StrokeData(strokeId, contoursToPath(cs, closed), paint, false, type, line).also {
                it.savedContours = cs // exact round-trip on the next save
            }
        }
    }

    /**
     * [scale] converts the document's page space to the one we're rendering into. Applied
     * here — to the raw floats — so unknown-tool strokes are rescaled too and round-trip
     * consistently with everything else.
     */
    private fun readStroke(r: JsonReader, scale: Float): RawStroke? {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        val s = RawStroke()
        r.beginObject()
        while (r.hasNext()) {
            when (val name = r.nextName()) {
                "id" -> s.id = r.nextString()
                "type" -> s.type = r.nextString()
                "v" -> s.version = r.nextInt()
                "style" -> s.style = r.nextString()
                "color" -> s.color = r.nextInt()
                "width" -> s.width = r.nextDouble().toFloat() * scale
                "lineStyle" -> s.lineStyle = r.nextString()
                "cap" -> s.cap = r.nextString()
                "imageFile" -> s.imageFile = r.nextString()
                // An angle: deliberately not multiplied by [scale] like the geometry fields.
                KEY_IMAGE_ROTATION -> s.imageRotation = r.nextDouble().toFloat()
                "rect" -> s.rect = readFloatArray(r, scale)
                "contours" -> {
                    val list = ArrayList<FloatArray>()
                    r.beginArray()
                    while (r.hasNext()) list.add(readFloatArray(r, scale))
                    r.endArray()
                    s.contours = list
                }
                else -> {
                    val ex = s.extra ?: JSONObject().also { s.extra = it }
                    ex.put(name, readValue(r))
                }
            }
        }
        r.endObject()
        return s
    }

    private fun readFloatArray(r: JsonReader, scale: Float = 1f): FloatArray {
        if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return FloatArray(0) }
        // Grow-by-doubling into a primitive array: no boxing, one copy at the end.
        var buf = FloatArray(64)
        var n = 0
        r.beginArray()
        while (r.hasNext()) {
            if (n == buf.size) buf = buf.copyOf(n * 2)
            buf[n++] = r.nextDouble().toFloat() * scale
        }
        r.endArray()
        return if (n == buf.size) buf else buf.copyOf(n)
    }

    /** Generic read for fields this build doesn't recognize, preserved as org.json values. */
    private fun readValue(r: JsonReader): Any = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().also { o ->
            r.beginObject()
            while (r.hasNext()) o.put(r.nextName(), readValue(r))
            r.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().also { a ->
            r.beginArray()
            while (r.hasNext()) a.put(readValue(r))
            r.endArray()
        }
        JsonToken.STRING -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> { r.nextNull(); JSONObject.NULL }
        // Read as text and keep integers integral, so unknown strokes round-trip byte-alike.
        JsonToken.NUMBER -> r.nextString().let { t ->
            t.toLongOrNull() ?: t.toDoubleOrNull() ?: t
        }
        else -> { r.skipValue(); JSONObject.NULL }
    }

    // ---------------- GEOMETRY ----------------

    private fun pathToContours(path: Path): List<FloatArray> {
        val contours = mutableListOf<FloatArray>()
        val pm = PathMeasure(path, false)
        val pos = FloatArray(2)
        do {
            val len = pm.length
            val pts = ArrayList<Float>()
            var d = 0f
            while (d < len) {
                if (pm.getPosTan(d, pos, null)) { pts.add(pos[0]); pts.add(pos[1]) }
                d += SAMPLE_STEP
            }
            if (pm.getPosTan(len, pos, null)) { pts.add(pos[0]); pts.add(pos[1]) }
            if (pts.isNotEmpty()) contours.add(pts.toFloatArray())
        } while (pm.nextContour())
        return contours
    }

    private fun contoursToPath(contours: List<FloatArray>, closed: Boolean): Path {
        val path = Path()
        for (c in contours) {
            if (c.size < 2) continue
            var px = c[0]
            var py = c[1]
            path.moveTo(px, py)
            var i = 2
            while (i + 1 < c.size) {
                val x = c[i]
                val y = c[i + 1]
                val dx = x - px
                val dy = y - py
                // Skip points that are extremely close to the previous drawn point.
                // Consecutive near-zero-length segments can cause Paint.getFillPath()
                // to silently fail, making the stroke permanently invisible to erasers
                // and the lasso tool.
                if (dx * dx + dy * dy > 0.01f) {
                    path.lineTo(x, y)
                    px = x
                    py = y
                }
                i += 2
            }
            // Force a dot for single-point taps (zero-length segment + round cap renders a dot).
            if (c.size == 2 && !closed) path.lineTo(c[0], c[1])
            if (closed) path.close()
        }
        return path
    }
}