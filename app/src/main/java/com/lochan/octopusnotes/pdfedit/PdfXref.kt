package com.lochan.octopusnotes.pdfedit

class PdfXrefEntry(
    val type: Char,
    val offset: Long = 0L,
    val gen: Int = 0
) {
    val isFree: Boolean get() = type == 'f'
    val isCompressed: Boolean get() = type == 'c'
}

class PdfXref private constructor(
    val entries: MutableMap<Int, PdfXrefEntry>,
    val trailer: PdfObject.Dict,
    val startXref: Long
) {

    val maxObjNum: Int get() = entries.keys.maxOrNull() ?: 0

    companion object {
        fun parse(data: ByteArray): PdfXref {
            val startXref = findStartXref(data) ?: throw PdfEditException.Corrupt("no startxref found")
            val merged = LinkedHashMap<Int, PdfXrefEntry>()
            var trailer: PdfObject.Dict? = null
            var offset = startXref
            var isNewest = true
            var guard = 0

            while (true) {
                if (guard++ > 100) throw PdfEditException.Corrupt("xref /Prev chain too long")

                if (offset > Int.MAX_VALUE.toLong()) {
                    throw PdfEditException.Corrupt("xref offset $offset out of range")
                }
                val section = readSection(data, offset.toInt())

                for ((num, e) in section.entries) {
                    if (isNewest || num !in merged) merged[num] = e
                }
                if (isNewest) {
                    trailer = section.trailer

                    section.trailer.map["XRefStm"]?.asNumber?.intValue?.toInt()?.let { stmOff ->
                        val stmSection = readSection(data, stmOff)
                        for ((num, e) in stmSection.entries) {
                            if (num !in merged) merged[num] = e
                        }
                    }
                }

                if (section.trailer.map["Encrypt"] != null) {
                    throw PdfEditException.Unsupported("encrypted PDFs are not supported")
                }
                val prev = section.trailer.map["Prev"]?.asNumber?.intValue
                if (prev == null) break
                offset = prev
                isNewest = false
            }

            val t = trailer ?: throw PdfEditException.Corrupt("no trailer found")
            if (0 !in merged) merged[0] = PdfXrefEntry('f', gen = 65535)
            return PdfXref(merged, t, startXref)
        }

        private class Section(val entries: LinkedHashMap<Int, PdfXrefEntry>, val trailer: PdfObject.Dict)

        private fun readSection(data: ByteArray, offset: Int): Section {
            val lex = PdfLexer(data, offset)
            val first = lex.next()
            if (first.type == PdfLexer.Token.Type.KEYWORD && first.text == "xref") {
                return readClassicSection(data, lex)
            }

            val second = lex.next()
            val third = lex.next()
            if (first.type == PdfLexer.Token.Type.NUMBER &&
                second.type == PdfLexer.Token.Type.NUMBER &&
                third.type == PdfLexer.Token.Type.KEYWORD && third.text == "obj"
            ) {
                return readStreamSection(data, lex)
            }
            throw PdfEditException.Corrupt("expected 'xref' or an xref stream at offset $offset")
        }

        private fun readClassicSection(data: ByteArray, lex: PdfLexer): Section {
            val entries = LinkedHashMap<Int, PdfXrefEntry>()

            while (true) {
                val firstTok = lex.next()
                if (firstTok.type == PdfLexer.Token.Type.KEYWORD && firstTok.text == "trailer") break
                if (firstTok.type == PdfLexer.Token.Type.EOF) break
                if (firstTok.type != PdfLexer.Token.Type.NUMBER) {
                    throw PdfEditException.Corrupt("bad xref subsection header")
                }
                val countTok = lex.next()
                if (countTok.type != PdfLexer.Token.Type.NUMBER) {
                    throw PdfEditException.Corrupt("bad xref subsection count")
                }
                val firstNum = firstTok.text.toIntOrNull() ?: throw PdfEditException.Corrupt("bad xref first")
                val count = countTok.text.toIntOrNull() ?: throw PdfEditException.Corrupt("bad xref count")
                for (i in 0 until count) {
                    val offTok = lex.next()
                    val genTok = lex.next()
                    val typeTok = lex.next()
                    if (offTok.type != PdfLexer.Token.Type.NUMBER ||
                        genTok.type != PdfLexer.Token.Type.NUMBER ||
                        typeTok.type != PdfLexer.Token.Type.KEYWORD
                    ) {
                        throw PdfEditException.Corrupt("bad xref entry at object ${firstNum + i}")
                    }
                    val num = firstNum + i
                    val t = typeTok.text.firstOrNull() ?: 'n'
                    entries[num] = if (t == 'f') PdfXrefEntry('f', gen = genTok.text.toIntOrNull() ?: 65535)
                    else PdfXrefEntry('n', offTok.text.toLongOrNull() ?: 0L, genTok.text.toIntOrNull() ?: 0)
                }
            }
            val trailerTok = lex.next()
            if (trailerTok.type != PdfLexer.Token.Type.OPEN_DICT) {
                throw PdfEditException.Corrupt("expected trailer dict")
            }
            return Section(entries, lex.parseDictBody())
        }

        private fun readStreamSection(data: ByteArray, lex: PdfLexer): Section {
            val dict = lex.parseValue() as? PdfObject.Dict
                ?: throw PdfEditException.Corrupt("xref stream: expected dict")

            val length = dict.map["Length"]?.asNumber?.intValue?.toInt() ?: -1
            val streamKw = lex.next()
            if (streamKw.type != PdfLexer.Token.Type.KEYWORD || streamKw.text != "stream") {
                throw PdfEditException.Corrupt("xref stream: expected 'stream' keyword")
            }
            var p = lex.position
            if (p < data.size && data[p] == '\r'.code.toByte()) {
                p++
                if (p < data.size && data[p] == '\n'.code.toByte()) p++
            } else if (p < data.size && data[p] == '\n'.code.toByte()) {
                p++
            }
            val raw: ByteArray
            if (length >= 0) {
                if (p + length > data.size) throw PdfEditException.Corrupt("xref stream overruns file")
                raw = data.copyOfRange(p, p + length)
            } else {
                val tail = String(data, p, data.size - p, Charsets.ISO_8859_1)
                val idx = tail.indexOf("endstream")
                if (idx < 0) throw PdfEditException.Corrupt("xref stream has no endstream")
                var end = p + idx
                if (end > p && (data[end - 1] == '\n'.code.toByte() || data[end - 1] == '\r'.code.toByte())) end--
                raw = data.copyOfRange(p, end)
            }

            val bytes = PdfFilters.decode(dict, raw)
                ?: throw PdfEditException.Corrupt("xref stream: unsupported or corrupt filter")
            return parseXrefStreamRecords(dict, bytes)
        }

        private fun parseXrefStreamRecords(dict: PdfObject.Dict, bytes: ByteArray): Section {
            val w = (dict.map["W"] as? PdfObject.ArrayRef)
                ?.items?.mapNotNullTo(ArrayList()) { it.asNumber?.intValue?.toInt() }
                ?: throw PdfEditException.Corrupt("xref stream: bad /W")
            while (w.size < 3) w.add(0)
            val size = dict.map["Size"]?.asNumber?.intValue?.toInt()
                ?: throw PdfEditException.Corrupt("xref stream: no /Size")

            val indexVal = dict.map["Index"]
            val ranges = if (indexVal is PdfObject.ArrayRef) {
                indexVal.items.chunked(2).mapNotNull {
                    val s = it.getOrNull(0)?.asNumber?.intValue?.toInt()
                    val c = it.getOrNull(1)?.asNumber?.intValue?.toInt()
                    if (s != null) s to (c ?: 0) else null
                }
            } else listOf(0 to size)

            val entries = LinkedHashMap<Int, PdfXrefEntry>()
            var pos = 0
            for ((start, count) in ranges) {
                for (i in 0 until count) {
                    val num = start + i
                    val f1 = readField(bytes, pos, w[0]); pos += w[0]
                    val f2 = readField(bytes, pos, w[1]); pos += w[1]
                    val f3 = readField(bytes, pos, w[2]); pos += w[2]
                    when (f1) {
                        0L -> entries[num] = PdfXrefEntry('f', gen = if (f3 >= 0L) f3.toInt() else 65535)
                        1L -> entries[num] = PdfXrefEntry('n', offset = f2, gen = f3.toInt())
                        2L -> entries[num] = PdfXrefEntry('c', offset = f2, gen = f3.toInt())
                    }
                }
            }
            return Section(entries, dict)
        }

        private fun readField(bytes: ByteArray, pos: Int, width: Int): Long {
            if (width <= 0) return 0L
            if (pos + width > bytes.size) return 0L

            var v = 0L
            for (i in 0 until width) v = (v shl 8) or (bytes[pos + i].toInt() and 0xFF).toLong()
            return v
        }

        private fun findStartXref(data: ByteArray): Long? {
            val tailLen = minOf(data.size, 2048)
            var s = String(data, data.size - tailLen, tailLen, Charsets.ISO_8859_1)
            var idx = s.lastIndexOf("startxref")

            if (idx < 0 && data.size > 2048) {
                s = String(data, Charsets.ISO_8859_1)
                idx = s.lastIndexOf("startxref")
            }
            if (idx < 0) return null
            var p = idx + "startxref".length
            while (p < s.length && s[p].isWhitespace()) p++
            val numStart = p
            while (p < s.length && s[p].isDigit()) p++
            if (p == numStart) return null
            return s.substring(numStart, p).toLongOrNull()
        }
    }
}
