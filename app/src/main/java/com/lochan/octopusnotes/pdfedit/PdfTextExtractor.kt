package com.lochan.octopusnotes.pdfedit

import kotlin.math.abs

class PdfTextExtractor {

    data class ExtLine(val text: String, val glyphStarts: IntArray, val rects: FloatArray)

    private val fontCache = HashMap<String, FontInfo>()

    fun extractPage(doc: PdfDoc, pageIndex: Int): List<ExtLine> {
        val page = try { doc.pageDict(pageIndex) } catch (e: Exception) { return emptyList() }
        val (pageW, pageH) = try { doc.pageSize(pageIndex) } catch (e: Exception) { return emptyList() }
        if (pageW <= 0f || pageH <= 0f) return emptyList()
        val contents = collectContents(page, doc) ?: return emptyList()
        val resources = try {
            page.map["Resources"]?.deref(doc) as? PdfObject.Dict
        } catch (e: Exception) {
            null
        } ?: PdfObject.Dict()
        fontCache.clear()
        return parseContent(contents, resources, pageW, pageH, doc)
    }

    private fun collectContents(page: PdfObject.Dict, doc: PdfDoc): ByteArray? {
        val contentsVal = page.map["Contents"] ?: return ByteArray(0)
        val contents = try { contentsVal.deref(doc) } catch (e: Exception) { return null }
        val out = java.io.ByteArrayOutputStream()
        fun addStream(s: PdfObject) {
            val st = s as? PdfObject.Stream ?: return
            val dec = try { PdfFilters.decode(st.dict, st.data, doc) } catch (e: Exception) { null }
                ?: return
            out.write(dec)
            out.write(' '.code)
        }
        when (contents) {
            is PdfObject.Stream -> addStream(contents)
            is PdfObject.ArrayRef -> for (item in contents.items) addStream(item)
            else -> {}
        }
        return out.toByteArray()
    }

    private fun parseContent(
        bytes: ByteArray,
        resources: PdfObject.Dict,
        pageW: Float,
        pageH: Float,
        doc: PdfDoc
    ): List<ExtLine> {
        val lines = ArrayList<ExtLine>()
        var lex = PdfLexer(bytes, 0)

        var curFont = ""
        var fontSize = 12f
        var leading = 0f
        var charSpacing = 0f
        var wordSpacing = 0f
        var hScale = 100f
        val tm = FloatArray(6)
        var inText = false

        val lineText = StringBuilder()
        val lineStarts = ArrayList<Int>()
        val lineRects = ArrayList<Float>()
        var lineY = Float.NaN

        fun flush() {
            if (lineText.isEmpty()) return
            lines.add(ExtLine(lineText.toString(), lineStarts.toIntArray(), lineRects.toFloatArray()))
            lineText.setLength(0); lineStarts.clear(); lineRects.clear(); lineY = Float.NaN
        }

        fun transform(x: Float, y: Float): FloatArray =
            floatArrayOf(tm[0] * x + tm[2] * y + tm[4], tm[1] * x + tm[3] * y + tm[5])

        fun translate(dx: Float, dy: Float) {
            val a = tm[0]; val c = tm[2]
            val b = tm[1]; val d = tm[3]
            tm[4] = dx * a + dy * c + tm[4]
            tm[5] = dx * b + dy * d + tm[5]
        }

        fun addGlyph(font: FontInfo, code: Int, tx: Float, ty: Float, advance: Float) {
            val text = font.unicode(code)
            if (text.isEmpty()) return
            val fs = fontSize
            val y0 = -0.85f * fs
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in arrayOf(
                transform(tx, y0), transform(tx + advance, y0),
                transform(tx, 0f), transform(tx + advance, 0f)
            )) {
                if (p[0] < minX) minX = p[0]
                if (p[0] > maxX) maxX = p[0]
                if (p[1] < minY) minY = p[1]
                if (p[1] > maxY) maxY = p[1]
            }
            if (maxX <= minX || maxY <= minY) return
            val nl = (minX / pageW).coerceIn(0f, 1f)
            val nt = ((pageH - maxY) / pageH).coerceIn(0f, 1f)
            val nr = (maxX / pageW).coerceIn(0f, 1f)
            val nb = ((pageH - minY) / pageH).coerceIn(0f, 1f)

            if (!lineY.isNaN() && abs(nt - lineY) > (fs / pageH) * 0.6f) {
                flush()
            }
            if (lineY.isNaN()) lineY = nt
            lineStarts.add(lineText.length)
            lineText.append(text)
            lineRects.add(nl); lineRects.add(nt); lineRects.add(nr); lineRects.add(nb)
        }

        fun showText(strBytes: ByteArray) {
            if (!inText) return
            val font = fontCache[curFont] ?: return
            var tx = tm[4]
            val ty = tm[5]
            val fs = fontSize
            var i = 0
            while (i < strBytes.size) {
                val code: Int
                if (font.codeSize == 2) {
                    if (i + 1 >= strBytes.size) break
                    code = ((strBytes[i].toInt() and 0xFF) shl 8) or (strBytes[i + 1].toInt() and 0xFF)
                    i += 2
                } else {
                    code = strBytes[i].toInt() and 0xFF
                    i++
                }
                val advance = font.width(code) / 1000f * fs * (hScale / 100f) +
                    charSpacing + (if (code == 0x20) wordSpacing else 0f)
                addGlyph(font, code, tx, ty, advance)
                tx += advance
            }
            tm[4] = tx
            tm[5] = ty
        }

        val operands = ArrayList<PdfObject>()

        fun beginText() {
            inText = true
            tm[0] = 1f; tm[1] = 0f; tm[2] = 0f; tm[3] = 1f; tm[4] = 0f; tm[5] = 0f
            flush()
        }

        fun num(i: Int): Float {
            val v = operands.getOrNull(i) as? PdfObject.Number ?: return 0f
            return v.doubleValue.toFloat()
        }

        fun applyOp(op: String) {
            when (op) {
                "BT" -> beginText()
                "ET" -> { inText = false; flush() }
                "Tf" -> {
                    val name = (operands.getOrNull(0) as? PdfObject.Name)?.name ?: return
                    curFont = name
                    fontSize = num(1).takeIf { it > 0f } ?: 12f
                    if (fontCache[curFont] == null) {
                        resolveFont(resources, name, doc)?.let { fontCache[curFont] = it }
                    }
                }
                "Td" -> { translate(num(0), num(1)); flush() }
                "TD" -> { translate(num(0), num(1)); leading = -num(1); flush() }
                "T*" -> { translate(0f, -leading); flush() }
                "Tm" -> {
                    if (operands.size >= 6) {
                        for (k in 0 until 6) tm[k] = num(k)
                    }
                    flush()
                }
                "TL" -> leading = num(0)
                "Tc" -> charSpacing = num(0)
                "Tw" -> wordSpacing = num(0)
                "Tz" -> { val v = num(0); if (v > 0f) hScale = v }
                "Tj" -> (operands.getOrNull(0) as? PdfObject.Str)?.let { showText(it.bytes) }
                "TJ" -> {
                    val arr = operands.getOrNull(0) as? PdfObject.ArrayRef ?: return
                    for (item in arr.items) {
                        when (item) {
                            is PdfObject.Str -> showText(item.bytes)
                            is PdfObject.Number -> {

                                tm[4] = tm[4] - item.doubleValue.toFloat() * fontSize / 1000f
                            }
                            else -> {}
                        }
                    }
                }
                "'" -> { translate(0f, -leading); flush(); (operands.getOrNull(0) as? PdfObject.Str)?.let { showText(it.bytes) } }
                "\"" -> {
                    wordSpacing = num(0); charSpacing = num(1)
                    translate(0f, -leading); flush()
                    (operands.getOrNull(2) as? PdfObject.Str)?.let { showText(it.bytes) }
                }
                "BI" -> {

                    val end = findInlineImageEnd(bytes, lex.position)
                    if (end != null) lex = PdfLexer(bytes, end)
                }

                else -> {}
            }
        }

        while (true) {
            val t = lex.next()
            if (t.type == PdfLexer.Token.Type.EOF) break
            when (t.type) {
                PdfLexer.Token.Type.KEYWORD -> {
                    applyOp(t.text)
                    operands.clear()
                }
                PdfLexer.Token.Type.NUMBER -> operands.add(PdfObject.Number(t.text))
                PdfLexer.Token.Type.NAME -> operands.add(PdfObject.Name(t.text))
                PdfLexer.Token.Type.STRING -> operands.add(PdfObject.Str(t.bytes ?: ByteArray(0)))
                PdfLexer.Token.Type.OPEN_ARRAY -> operands.add(lex.parseArrayBody())
                else -> {}
            }
        }
        flush()
        return lines
    }

    private fun findInlineImageEnd(data: ByteArray, from: Int): Int? {
        var i = from
        while (i < data.size - 1) {
            if ((data[i].toInt() and 0xFF) == 'I'.code && (data[i + 1].toInt() and 0xFF) == 'D'.code) {
                val prev = if (i == 0) -1 else data[i - 1].toInt() and 0xFF
                val prevWs = prev == -1 || prev == 0 || prev == 9 || prev == 10 || prev == 12 || prev == 13 || prev == 32
                if (prevWs) {
                    var p = i + 2

                    if (p < data.size && data[p] == '\r'.code.toByte()) p++
                    if (p < data.size && data[p] == '\n'.code.toByte()) p++
                    while (p < data.size - 1) {
                        if ((data[p].toInt() and 0xFF) == 'E'.code && (data[p + 1].toInt() and 0xFF) == 'I'.code) {
                            val prevEI = if (p == 0) -1 else data[p - 1].toInt() and 0xFF
                            val nextEI = if (p + 2 >= data.size) -1 else data[p + 2].toInt() and 0xFF
                            val prevOk = prevEI == -1 || prevEI == 0 || prevEI == 9 || prevEI == 10 ||
                                prevEI == 12 || prevEI == 13 || prevEI == 32
                            val nextOk = nextEI == -1 || nextEI == 0 || nextEI == 9 || nextEI == 10 ||
                                nextEI == 12 || nextEI == 13 || nextEI == 32 || nextEI == '%'.code
                            if (prevOk && nextOk) return p + 2
                        }
                        p++
                    }
                    return data.size
                }
            }
            i++
        }
        return data.size
    }

    private class FontInfo(
        val unicodeMap: Map<Int, String>?,
        val hasToUnicode: Boolean,
        val encodingTable: String?,
        val diffs: Map<Int, String>?,
        val widthFn: (Int) -> Float,
        val codeSize: Int
    ) {
        fun unicode(code: Int): String {
            unicodeMap?.get(code)?.let { return it }
            if (hasToUnicode) return ""
            val n = diffs?.get(code)
            if (n != null) return PdfTextExtractor.glyphNameToUnicode(n, code)
            val t = encodingTable
            if (t != null && code < t.length) {
                val c = t[code]
                if (c != '\u0000') return c.toString()
            }
            return if (code in 32..255) code.toChar().toString() else ""
        }

        fun width(code: Int): Float = try { widthFn(code) } catch (e: Exception) { 500f }
    }

    private fun resolveFont(resources: PdfObject.Dict, name: String, doc: PdfDoc): FontInfo? {
        val fonts = resources.map["Font"]?.deref(doc) as? PdfObject.Dict ?: return null
        val fontVal = fonts.map[name] ?: return null
        val font = try { fontVal.deref(doc) as? PdfObject.Dict } catch (e: Exception) { return null }
            ?: return null
        val subtype = font.map["Subtype"]?.asName?.name ?: font.map["Type"]?.asName?.name
        return when (subtype) {
            "Type0" -> resolveType0(font, doc)
            "Type3" -> null
            else -> resolveSimple(font, doc)
        }
    }

    private fun resolveSimple(font: PdfObject.Dict, doc: PdfDoc): FontInfo {
        val toUnicode = readToUnicode(font, doc)
        var base = "Standard"
        val diffs = HashMap<Int, String>()
        val encVal = try { font.map["Encoding"]?.deref(doc) } catch (e: Exception) { null }
        if (encVal is PdfObject.Name) {
            base = encVal.name
        } else if (encVal is PdfObject.Dict) {
            (encVal.map["BaseEncoding"] as? PdfObject.Name)?.let { base = it.name }
            val diffArr = encVal.map["Differences"] as? PdfObject.ArrayRef
            if (diffArr != null) {
                var code = -1
                for (item in diffArr.items) {
                    val v = try { item.deref(doc) } catch (e: Exception) { null }
                    when (v) {
                        is PdfObject.Number -> code = v.intValue?.toInt() ?: 0
                        is PdfObject.Name -> { if (code >= 0) { diffs[code] = v.name; code++ } }
                        else -> {}
                    }
                }
            }
        }
        val firstChar = font.map["FirstChar"]?.asNumber?.intValue?.toInt() ?: 0
        val widths = try { font.map["Widths"]?.deref(doc) } catch (e: Exception) { null }
            as? PdfObject.ArrayRef
        val widthList = widths?.items?.mapNotNull { it.asNumber?.doubleValue?.toFloat() }
        var missing = 500f
        (try { font.map["FontDescriptor"]?.deref(doc) } catch (e: Exception) { null })?.let { fd ->
            if (fd is PdfObject.Dict) {
                missing = fd.map["MissingWidth"]?.asNumber?.doubleValue?.toFloat() ?: missing
            }
        }
        val widthFn: (Int) -> Float = { code ->
            if (widthList != null && code >= firstChar) {
                val idx = code - firstChar
                if (idx < widthList.size) widthList[idx] else missing
            } else missing
        }
        val table = when (base) {
            "WinAnsiEncoding" -> WIN_ANSI
            "MacRomanEncoding" -> MAC_ROMAN
            "MacExpertEncoding" -> MAC_ROMAN
            "StandardEncoding" -> STANDARD
            else -> null
        }
        return FontInfo(toUnicode, toUnicode != null, table, diffs.ifEmpty { null }, widthFn, codeSize = 1)
    }

    private fun resolveType0(font: PdfObject.Dict, doc: PdfDoc): FontInfo {
        var toUnicode = readToUnicode(font, doc)
        val desc = (try { font.map["DescendantFonts"]?.deref(doc) } catch (e: Exception) { null }
            as? PdfObject.ArrayRef)?.items?.firstOrNull()
        val d = try { desc?.deref(doc) as? PdfObject.Dict } catch (e: Exception) { null }
        val widths = HashMap<Int, Float>()
        var dw = 1000f
        if (d != null) {
            if (toUnicode == null) toUnicode = readToUnicode(d, doc)
            dw = d.map["DW"]?.asNumber?.doubleValue?.toFloat() ?: 1000f
            val wArr = (try { d.map["W"]?.deref(doc) } catch (e: Exception) { null }) as? PdfObject.ArrayRef
            if (wArr != null) {
                val items = wArr.items
                var i = 0
                while (i < items.size) {
                    val firstV = try { items[i].deref(doc) } catch (e: Exception) { null }
                    i++
                    val c1 = (firstV as? PdfObject.Number)?.intValue?.toInt() ?: continue
                    val secondV = if (i < items.size) try { items[i].deref(doc) } catch (e: Exception) { null } else null
                    if (secondV is PdfObject.ArrayRef) {

                        var c = c1
                        for (item in secondV.items) {
                            val w = (try { item.deref(doc) } catch (e: Exception) { null })
                                ?.asNumber?.doubleValue?.toFloat()
                            if (w != null) widths[c] = w
                            c++
                        }
                    } else if (secondV is PdfObject.Number) {
                        val c2 = secondV.intValue?.toInt() ?: continue
                        i++
                        val thirdV = if (i < items.size) try { items[i].deref(doc) } catch (e: Exception) { null } else null
                        if (thirdV is PdfObject.Number) {

                            val w = thirdV.doubleValue?.toFloat() ?: continue
                            for (c in c1..c2) widths[c] = w
                        }
                    }
                }
            }
        }
        val widthFn: (Int) -> Float = { widths[it] ?: dw }
        return FontInfo(toUnicode, toUnicode != null, null, null, widthFn, codeSize = 2)
    }

    private fun readToUnicode(font: PdfObject.Dict, doc: PdfDoc): Map<Int, String>? {
        val tu = try { font.map["ToUnicode"]?.deref(doc) } catch (e: Exception) { null }
            as? PdfObject.Stream ?: return null
        val bytes = try { PdfFilters.decode(tu.dict, tu.data, doc) } catch (e: Exception) { null }
            ?: return null
        return parseToUnicode(bytes)
    }

    private fun parseToUnicode(cmap: ByteArray): Map<Int, String> {
        val s = String(cmap, Charsets.ISO_8859_1)
        val tokens = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '%' -> { while (i < s.length && s[i] != '\n') i++ }
                c == '<' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < s.length && s[i] != '>') { sb.append(s[i]); i++ }
                    i++
                    tokens.add(sb.toString())
                }
                c == '[' -> { tokens.add("["); i++ }
                c == ']' -> { tokens.add("]"); i++ }
                c == '/' -> { while (i < s.length && !s[i].isWhitespace() && s[i] != '<') i++ }
                else -> {
                    val start = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '<' && s[i] != '[' && s[i] != ']') i++
                    tokens.add(s.substring(start, i))
                }
            }
        }

        fun hexToInt(h: String): Int? {
            var v = 0
            for (ch in h) {
                val d = Character.digit(ch, 16)
                if (d < 0) return null
                v = (v shl 4) or d
            }
            return v
        }

        fun hexToUtf16(h: String): String? {
            if (h.length % 2 != 0) return null
            val bytes = ByteArray(h.length / 2)
            for (k in bytes.indices) {
                val d1 = Character.digit(h[k * 2], 16)
                val d2 = Character.digit(h[k * 2 + 1], 16)
                if (d1 < 0 || d2 < 0) return null
                bytes[k] = ((d1 shl 4) or d2).toByte()
            }
            return try { String(bytes, Charsets.UTF_16BE) } catch (e: Exception) { null }
        }

        val map = HashMap<Int, String>()
        var t = 0
        while (t < tokens.size) {
            when (tokens[t]) {
                "beginbfchar" -> {
                    t++
                    while (t + 1 < tokens.size && tokens[t] != "endbfchar") {
                        val src = hexToInt(tokens[t]) ?: break
                        val dst = hexToUtf16(tokens[t + 1]) ?: break
                        if (dst.isNotEmpty()) map[src] = dst
                        t += 2
                    }
                    t++
                }
                "beginbfrange" -> {
                    t++
                    while (t + 2 < tokens.size && tokens[t] != "endbfrange") {
                        val lo = hexToInt(tokens[t]) ?: break
                        val hi = hexToInt(tokens[t + 1]) ?: break
                        val third = tokens[t + 2]
                        t += 3
                        if (third == "[") {

                            val arr = ArrayList<String>()
                            while (t < tokens.size && tokens[t] != "]") {
                                hexToUtf16(tokens[t])?.let { arr.add(it) }
                                t++
                            }
                            t++
                            for (c in lo..hi) {
                                val idx = c - lo
                                if (idx in arr.indices && arr[idx].isNotEmpty()) map[c] = arr[idx]
                            }
                        } else {
                            val startDst = hexToInt(third) ?: break
                            for (c in lo..hi) {
                                val cp = startDst + (c - lo)
                                if (cp in 0..0x10FFFF) map[c] = String(Character.toChars(cp))
                            }
                        }
                    }
                    t++
                }
                else -> t++
            }
        }
        return map
    }

    companion object {
        private fun glyphNameToUnicode(name: String, code: Int): String {
            GLYPH_NAMES[name]?.let { return it.toString() }
            if (name.length == 1) return name
            if (name.startsWith("uni") && name.length >= 7) {
                name.substring(3).toIntOrNull(16)?.let {
                    return try { String(Character.toChars(it)) } catch (e: Exception) { "" }
                }
            }
            return if (code in 32..255) code.toChar().toString() else ""
        }

        private val WIN_ANSI: String =
            "\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u0009\u000A\u000B\u000C\u000D\u000E\u000F" +
            "\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001F" +
            " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\u0000" +
            "\u20AC\u0000\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u0000\u017D\u0000" +
            "\u0000\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u0000\u017E\u0178" +
            "\u00A0\u00A1\u00A2\u00A3\u00A4\u00A5\u00A6\u00A7\u00A8\u00A9\u00AA\u00AB\u00AC\u00AD\u00AE\u00AF" +
            "\u00B0\u00B1\u00B2\u00B3\u00B4\u00B5\u00B6\u00B7\u00B8\u00B9\u00BA\u00BB\u00BC\u00BD\u00BE\u00BF" +
            "\u00C0\u00C1\u00C2\u00C3\u00C4\u00C5\u00C6\u00C7\u00C8\u00C9\u00CA\u00CB\u00CC\u00CD\u00CE\u00CF" +
            "\u00D0\u00D1\u00D2\u00D3\u00D4\u00D5\u00D6\u00D7\u00D8\u00D9\u00DA\u00DB\u00DC\u00DD\u00DE\u00DF" +
            "\u00E0\u00E1\u00E2\u00E3\u00E4\u00E5\u00E6\u00E7\u00E8\u00E9\u00EA\u00EB\u00EC\u00ED\u00EE\u00EF" +
            "\u00F0\u00F1\u00F2\u00F3\u00F4\u00F5\u00F6\u00F7\u00F8\u00F9\u00FA\u00FB\u00FC\u00FD\u00FE\u00FF"

        private val STANDARD: String =
            "\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u0009\u000A\u000B\u000C\u000D\u000E\u000F" +
            "\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001F" +
            " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\u0000" +
            "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000" +
            "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000" +
            "\u0000\u00A1\u00A2\u00A3\u0000\u00A5\u00A6\u00A7\u00A8\u00A9\u00AA\u00AB\u00AC\u00AD\u00AE\u00AF" +
            "\u00B0\u00B1\u00B2\u00B3\u00B4\u00B5\u00B6\u00B7\u00B8\u00B9\u00BA\u00BB\u00BC\u00BD\u00BE\u00BF" +
            "\u00C0\u00C1\u00C2\u00C3\u00C4\u00C5\u00C6\u00C7\u00C8\u00C9\u00CA\u00CB\u00CC\u00CD\u00CE\u00CF" +
            "\u00D0\u00D1\u00D2\u00D3\u00D4\u00D5\u00D6\u00D7\u00D8\u00D9\u00DA\u00DB\u00DC\u00DD\u00DE\u00DF" +
            "\u00E0\u00E1\u00E2\u00E3\u00E4\u00E5\u00E6\u00E7\u00E8\u00E9\u00EA\u00EB\u00EC\u00ED\u00EE\u00EF" +
            "\u00F0\u00F1\u00F2\u00F3\u00F4\u00F5\u00F6\u00F7\u00F8\u00F9\u00FA\u00FB\u00FC\u00FD\u00FE\u00FF"

        private val MAC_ROMAN: String =
            "\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u0009\u000A\u000B\u000C\u000D\u000E\u000F" +
            "\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001F" +
            " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\u0000" +
            "\u00C4\u00C5\u00C7\u00C9\u00D1\u00D6\u00DC\u00E1\u00E0\u00E2\u00E4\u00E3\u00E5\u00E7\u00E9\u00E8" +
            "\u00EA\u00EB\u00ED\u00EC\u00EE\u00EF\u00F1\u00F3\u00F2\u00F4\u00F6\u00F5\u00FA\u00F9\u00FB\u00FC" +
            "\u2020\u00B0\u00A2\u00A3\u00A7\u2022\u00B6\u00DF\u00AE\u00A9\u2122\u00B4\u00A8\u2260\u00C6\u00D8" +
            "\u221E\u00B1\u2264\u2265\u00A5\u00B5\u2202\u2211\u220F\u03C0\u222B\u00AA\u00BA\u03A9\u00E6\u00F8" +
            "\u00BF\u00A1\u00AC\u221A\u0192\u2248\u2206\u00AB\u00BB\u2026\u00A0\u00C0\u00C3\u00D5\u0152\u0153" +
            "\u2013\u2014\u201C\u201D\u2018\u2019\u00F7\u25CA\u00FF\u0178\u2044\u20AC\u2039\u203A\uFB01\uFB02" +
            "\u2021\u00B7\u201A\u201E\u2030\u00C2\u00CA\u00C1\u00CB\u00C8\u00CD\u00CE\u00CF\u00CC\u00D3\u00D4" +
            "\uF8FF\u00D2\u00DA\u00DB\u00D9\u0131\u02C6\u02DC\u00AF\u02D8\u02D9\u02DA\u00B8\u02DD\u02DB\u02C7"

        private val GLYPH_NAMES: Map<String, Char> by lazy {
            buildMap {
                for (pair in GLYPH_NAME_PAIRS.split(" ")) {
                    val idx = pair.indexOf(':')
                    if (idx > 0) put(pair.substring(0, idx), pair[idx + 1])
                }
            }
        }

        private val GLYPH_NAME_PAIRS: String =
            "space:\u0020 exclam:! quotedbl:\" numbersign:# dollar:$ percent:% ampersand:& quotesingle:' " +
            "parenleft:( parenright:) asterisk:* plus:+ comma:, hyphen:- period:. slash:/ zero:0 one:1 two:2 three:3 " +
            "four:4 five:5 six:6 seven:7 eight:8 nine:9 colon:; semicolon:; less:< equal:= greater:> question:? at:@ " +
            "bracketleft:[ backslash:\\ bracketright:] asciicircum:^ underscore:_ grave:` braceleft:{ bar:| braceright:} " +
            "asciitilde:~ exclamdown:\u00A1 cent:\u00A2 sterling:\u00A3 currency:\u00A4 yen:\u00A5 brokenbar:\u00A6 " +
            "section:\u00A7 dieresis:\u00A8 copyright:\u00A9 ordfeminine:\u00AA guillemotleft:\u00AB logicalnot:\u00AC " +
            "registered:\u00AE macron:\u00AF degree:\u00B0 plusminus:\u00B1 twosuperior:\u00B2 threesuperior:\u00B3 " +
            "acute:\u00B4 mu:\u00B5 paragraph:\u00B6 periodcentered:\u00B7 cedilla:\u00B8 onesuperior:\u00B9 " +
            "ordmasculine:\u00BA guillemotright:\u00BB onequarter:\u00BC onehalf:\u00BD threequarters:\u00BE " +
            "questiondown:\u00BF Agrave:\u00C0 Aacute:\u00C1 Acircumflex:\u00C2 Atilde:\u00C3 Adieresis:\u00C4 " +
            "Aring:\u00C5 AE:\u00C6 Ccedilla:\u00C7 Egrave:\u00C8 Eacute:\u00C9 Ecircumflex:\u00CA Edieresis:\u00CB " +
            "Igrave:\u00CC Iacute:\u00CD Icircumflex:\u00CE Idieresis:\u00CF Eth:\u00D0 Ntilde:\u00D1 Ograve:\u00D2 " +
            "Oacute:\u00D3 Ocircumflex:\u00D4 Otilde:\u00D5 Odieresis:\u00D6 multiply:\u00D7 Oslash:\u00D8 " +
            "Ugrave:\u00D9 Uacute:\u00DA Ucircumflex:\u00DB Udieresis:\u00DC Yacute:\u00DD Thorn:\u00DE " +
            "germandbls:\u00DF agrave:\u00E0 aacute:\u00E1 acircumflex:\u00E2 atilde:\u00E3 adieresis:\u00E4 " +
            "aring:\u00E5 ae:\u00E6 ccedilla:\u00E7 egrave:\u00E8 eacute:\u00E9 ecircumflex:\u00EA edieresis:\u00EB " +
            "igrave:\u00EC iacute:\u00ED icircumflex:\u00EE idieresis:\u00EF eth:\u00F0 ntilde:\u00F1 ograve:\u00F2 " +
            "oacute:\u00F3 ocircumflex:\u00F4 otilde:\u00F5 odieresis:\u00F6 divide:\u00F7 oslash:\u00F8 " +
            "ugrave:\u00F9 uacute:\u00FA ucircumflex:\u00FB udieresis:\u00FC yacute:\u00FD thorn:\u00FE " +
            "ydieresis:\u00FF endash:\u2013 emdash:\u2014 quoteleft:\u2018 quoteright:\u2019 quotedblleft:\u201C " +
            "quotedblright:\u201D bullet:\u2022 ellipsis:\u2026 perthousand:\u2030 guilsinglleft:\u2039 " +
            "guilsinglright:\u203A OE:\u0152 oe:\u0153 Ydieresis:\u0178 florin:\u0192 trademark:\u2122 " +
            "Euro:\u20AC arrowleft:\u2190 arrowup:\u2191 arrowright:\u2192 arrowdown:\u2193 " +
            "emptyset:\u2205 infinity:\u221E lessequal:\u2264 greaterequal:\u2265 lozenge:\u25CA"
    }
}
