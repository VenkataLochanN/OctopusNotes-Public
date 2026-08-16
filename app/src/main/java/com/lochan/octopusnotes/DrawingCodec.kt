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

    const val DOC_TYPE_INFINITE = "INFINITE"
    private const val SAMPLE_STEP = 2f

    private const val KEY_BASE_WIDTH = "baseWidth"

    private const val KEY_IMAGE_ROTATION = "imgRot"

    data class DecodedPage(
        val known: MutableList<StrokeData>,
        val unknown: MutableList<JSONObject>
    )

    data class DecodeResult(
        val pages: MutableMap<Int, DecodedPage>,

        val unsupportedTypeCounts: Map<String, Int>,

        val baseWidth: Float = 0f
    )

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

        if (stroke.type == StrokeType.IMAGE) {
            val b = android.graphics.RectF()
            stroke.path.computeBounds(b, true)
            o.put("imageFile", stroke.imageFile ?: "")
            o.put("rect", JSONArray()
                .put(b.left.toDouble()).put(b.top.toDouble())
                .put(b.right.toDouble()).put(b.bottom.toDouble()))

            if (stroke.imageRotation != 0f) o.put(KEY_IMAGE_ROTATION, stroke.imageRotation.toDouble())
            return o
        }

        if (stroke.type == StrokeType.TABLE) {
            val b = android.graphics.RectF()
            stroke.path.computeBounds(b, true)
            o.put("rect", JSONArray()
                .put(b.left.toDouble()).put(b.top.toDouble())
                .put(b.right.toDouble()).put(b.bottom.toDouble()))
            o.put("style", "STROKE")
            o.put("color", stroke.paint.color)
            o.put("width", stroke.paint.strokeWidth.toDouble())
            if (stroke.lineStyle != PenLineStyle.SOLID) o.put("lineStyle", stroke.lineStyle)
            val td = stroke.tableData ?: TableData(1, 1)
            val t = JSONObject()
            t.put("rows", td.rows)
            t.put("cols", td.cols)
            t.put("hdr", td.headerRow)
            if (td.headerCol) t.put("hdrC", true)
            if (td.headerColor != null) t.put("hdrCol", td.headerColor)
            if (td.headerColColor != null) t.put("hcCol", td.headerColColor)
            if (td.borderRadius > 0f) t.put("radius", td.borderRadius.toDouble())

            if (td.rowWeights != null) {
                t.put("rw", JSONArray().apply { for (v in td.rowWeights) put(v.toDouble()) })
            }
            if (td.colWeights != null) {
                t.put("cw", JSONArray().apply { for (v in td.colWeights) put(v.toDouble()) })
            }
            if (td.merges.isNotEmpty()) {
                val m = JSONObject()
                for ((k, span) in td.merges) {
                    m.put(k, JSONArray().put(span.getOrNull(0) ?: 1).put(span.getOrNull(1) ?: 1))
                }
                t.put("merges", m)
            }
            if (td.cells.isNotEmpty()) {
                t.put("cells", JSONObject().apply { for ((k, v) in td.cells) put(k, v) })
            }
            o.put("table", t)
            return o
        }

        if (stroke.type == StrokeType.TEXT) {
            val b = android.graphics.RectF()
            stroke.path.computeBounds(b, true)
            o.put("rect", JSONArray()
                .put(b.left.toDouble()).put(b.top.toDouble())
                .put(b.right.toDouble()).put(b.bottom.toDouble()))
            o.put("color", stroke.paint.color)
            o.put("width", stroke.paint.strokeWidth.toDouble())
            o.put("text", stroke.textData?.text ?: "")

            o.put("textSize", (stroke.textData?.size ?: 26f).toDouble())

            stroke.textData?.font?.let { o.put("font", it) }
            return o
        }

        if (stroke.type == StrokeType.TAPE) {
            val b = android.graphics.RectF()
            stroke.path.computeBounds(b, true)
            o.put("rect", JSONArray()
                .put(b.left.toDouble()).put(b.top.toDouble())
                .put(b.right.toDouble()).put(b.bottom.toDouble()))
            o.put("color", stroke.paint.color)

            val td = stroke.tapeData
            if (td != null && (td.pattern != TapePattern.SOLID || td.hollow)) {
                val t = JSONObject()
                if (td.pattern != TapePattern.SOLID) t.put("pattern", td.pattern)
                if (td.hollow) t.put("hollow", true)
                o.put("tape", t)
            }

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

    fun encodeInfiniteDocument(
        known: List<StrokeData>,
        unknown: List<JSONObject>
    ): String {
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("type", DOC_TYPE_INFINITE)
        val strokes = JSONArray()
        for (sd in known) strokes.put(encodeStroke(sd))

        for (u in unknown) strokes.put(u)
        root.put("strokes", strokes)
        return root.toString()
    }

    fun decodeInfiniteDocument(source: Reader): DecodeResult {
        val unsupported = HashMap<String, Int>()
        val known = mutableListOf<StrokeData>()
        val unknown = mutableListOf<JSONObject>()
        JsonReader(source.buffered()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "strokes") { r.skipValue(); continue }
                r.beginArray()
                while (r.hasNext()) {
                    val raw = readStroke(r, 1f)
                    if (raw == null) continue
                    if (raw.type !in StrokeType.SUPPORTED) {

                        unknown.add(raw.toJson())
                        unsupported[raw.type] = (unsupported[raw.type] ?: 0) + 1
                    } else {
                        val sd = try { raw.toStrokeData() } catch (e: Exception) { null }

                        if (sd != null) known.add(sd) else unknown.add(raw.toJson())
                    }
                }
                r.endArray()
            }
            r.endObject()
        }
        return DecodeResult(mutableMapOf(0 to DecodedPage(known, unknown)), unsupported)
    }

    fun decodeDocument(
        json: String,
        targetWidth: Float = 0f,
        fallbackBaseWidth: Float = 0f
    ): DecodeResult = decodeDocument(StringReader(json), targetWidth, fallbackBaseWidth)

    fun decodeDocument(
        source: Reader,
        targetWidth: Float = 0f,
        fallbackBaseWidth: Float = 0f,
        onPage: ((Int, DecodedPage) -> Unit)? = null,

        onlyPage: Int = -1,

        skipPage: Int = -1
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

                            if (onlyPage >= 0 && pageIndex != onlyPage) { r.skipValue(); continue }
                            if (skipPage >= 0 && pageIndex == skipPage) { r.skipValue(); continue }
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
        return DecodeResult(pages, unsupported, baseWidth)
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

                    unknown.add(raw.toJson())
                    unsupported[raw.type] = (unsupported[raw.type] ?: 0) + 1
                } else {
                    val sd = try { raw.toStrokeData() } catch (e: Exception) { null }

                    if (sd != null) known.add(sd) else unknown.add(raw.toJson())
                }
            }
            r.endArray()
        }
        r.endObject()
        return DecodedPage(known, unknown)
    }

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
        var table: JSONObject? = null
        var text: String? = null
        var textSize: Float? = null
        var font: String? = null
        var tape: JSONObject? = null
        var contours: ArrayList<FloatArray>? = null

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
            table?.let { o.put("table", it) }
            text?.let { o.put("text", it) }
            textSize?.let { o.put("textSize", it.toDouble()) }
            font?.let { o.put("font", it) }
            tape?.let { o.put("tape", it) }
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

            if (type == StrokeType.TABLE) {
                val rc = rect ?: throw IllegalArgumentException("table stroke without rect")
                val path = Path().apply { addRect(rc[0], rc[1], rc[2], rc[3], Path.Direction.CW) }
                val paint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                    color = this@RawStroke.color ?: Color.BLACK
                    strokeWidth = width ?: TABLE_LINE_WIDTH
                }
                val td = table?.let { t ->
                    val cells = t.optJSONObject("cells")?.let { c ->
                        HashMap<String, String>().also { m ->
                            val keys = c.keys()
                            while (keys.hasNext()) { val k = keys.next(); m[k] = c.optString(k, "") }
                        }
                    } ?: mutableMapOf()
                    TableData(
                        rows = t.optInt("rows", 1).coerceAtLeast(1),
                        cols = t.optInt("cols", 1).coerceAtLeast(1),
                        cells = cells,
                        headerRow = t.optBoolean("hdr", true),
                        headerCol = t.optBoolean("hdrC", false),
                        headerColor = if (t.has("hdrCol")) t.optInt("hdrCol") else null,
                        headerColColor = if (t.has("hcCol")) t.optInt("hcCol") else null,
                        borderRadius = t.optDouble("radius", 0.0).toFloat(),
                        rowWeights = t.optJSONArray("rw")?.let { arr ->
                            FloatArray(arr.length()) { arr.optDouble(it, 0.0).toFloat() }
                        },
                        colWeights = t.optJSONArray("cw")?.let { arr ->
                            FloatArray(arr.length()) { arr.optDouble(it, 0.0).toFloat() }
                        },
                        merges = t.optJSONObject("merges")?.let { o ->
                            HashMap<String, IntArray>().also { m ->
                                val keys = o.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val span = o.optJSONArray(k)
                                    m[k] = intArrayOf(span?.optInt(0, 1) ?: 1, span?.optInt(1, 1) ?: 1)
                                }
                            }
                        } ?: mutableMapOf()
                    )
                } ?: TableData(1, 1)
                return StrokeData(
                    strokeId, path, paint, false, type,
                    lineStyle = lineStyle ?: PenLineStyle.SOLID,
                    tableData = td
                )
            }

            if (type == StrokeType.TEXT) {
                val rc = rect ?: throw IllegalArgumentException("text stroke without rect")
                val path = Path().apply { addRect(rc[0], rc[1], rc[2], rc[3], Path.Direction.CW) }

                val paint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = 1.5f
                    color = this@RawStroke.color ?: Color.BLACK
                }
                val td = TextData(
                    text = text ?: "",
                    size = (textSize ?: 26f).coerceAtLeast(6f),
                    font = font
                )
                return StrokeData(strokeId, path, paint, false, type, textData = td)
            }

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

            if (type == StrokeType.TAPE) {
                val rc = rect ?: throw IllegalArgumentException("tape stroke without rect")
                val path = Path().apply { addRect(rc[0], rc[1], rc[2], rc[3], Path.Direction.CW) }

                val paint = Paint().apply {
                    isAntiAlias = true; style = Paint.Style.FILL
                    color = this@RawStroke.color ?: Color.BLACK
                }
                return StrokeData(
                    strokeId, path, paint, false, type,
                    imageRotation = imageRotation ?: 0f,
                    tapeData = TapeData(
                        pattern = tape?.optString("pattern", TapePattern.SOLID) ?: TapePattern.SOLID,
                        hollow = tape?.optBoolean("hollow", false) ?: false
                    )
                )
            }

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

            if (line != PenLineStyle.SOLID && !closed) {
                paint.pathEffect = PenLineStyle.pathEffect(line, strokeWidth)
            }
            return StrokeData(strokeId, contoursToPath(cs, closed), paint, false, type, line).also {
                it.savedContours = cs
            }
        }
    }

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

                KEY_IMAGE_ROTATION -> s.imageRotation = r.nextDouble().toFloat()
                "rect" -> s.rect = readFloatArray(r, scale)
                "table" -> (readValue(r) as? JSONObject ?: JSONObject()).also { t ->

                    if (t.has("radius")) t.put("radius", t.optDouble("radius", 0.0) * scale)
                    s.table = t
                }
                "text" -> s.text = r.nextString()

                "textSize" -> s.textSize = r.nextDouble().toFloat() * scale
                "font" -> s.font = r.nextString()
                "tape" -> s.tape = readValue(r) as? JSONObject ?: JSONObject()
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

        JsonToken.NUMBER -> r.nextString().let { t ->
            t.toLongOrNull() ?: t.toDoubleOrNull() ?: t
        }
        else -> { r.skipValue(); JSONObject.NULL }
    }

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

                if (dx * dx + dy * dy > 0.01f) {
                    path.lineTo(x, y)
                    px = x
                    py = y
                }
                i += 2
            }

            if (c.size == 2 && !closed) path.lineTo(c[0], c[1])
            if (closed) path.close()
        }
        return path
    }
}