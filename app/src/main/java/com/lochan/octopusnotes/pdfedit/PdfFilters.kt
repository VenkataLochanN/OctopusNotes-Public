package com.lochan.octopusnotes.pdfedit

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object PdfFilters {

    fun decode(dict: PdfObject.Dict, raw: ByteArray, doc: PdfDoc? = null): ByteArray? {
        val filterVal = dict.map["Filter"]?.let { if (doc != null) it.deref(doc) else it }
        val filters = when (filterVal) {
            is PdfObject.Name -> listOf(filterVal.name)
            is PdfObject.ArrayRef -> filterVal.items.mapNotNull { (it as? PdfObject.Name)?.name }
            null -> emptyList()
            else -> emptyList()
        }
        var out = raw
        for (f in filters) {
            out = when (f) {
                "FlateDecode", "Fl" -> inflate(out)
                "ASCIIHexDecode", "AHx" -> asciiHexDecode(out)
                "ASCII85Decode", "A85" -> ascii85Decode(out)
                "RunLengthDecode", "RL" -> runLengthDecode(out)
                else -> null
            } ?: return null
        }
        return out
    }

    fun inflate(data: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var complete = true
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {

                    if (inflater.needsInput() || inflater.needsDictionary() || inflater.getRemaining() == 0) {
                        complete = false
                        break
                    }
                } else {
                    out.write(buf, 0, n)
                }
            }
            inflater.end()
            if (complete) out.toByteArray() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun asciiHexDecode(data: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        var hi = -1
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == '>') break
            if (c.isWhitespace()) continue
            val v = Character.digit(c, 16)
            if (v < 0) return null
            if (hi < 0) hi = v
            else { out.write((hi shl 4) or v); hi = -1 }
        }
        if (hi >= 0) out.write(hi shl 4)
        return out.toByteArray()
    }

    private fun ascii85Decode(data: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        val group = IntArray(5)
        var n = 0
        var i = 0
        while (i < data.size) {
            val c = (data[i].toInt() and 0xFF).toChar()
            i++
            when {
                c == '~' -> break
                c.isWhitespace() -> {}
                c == 'z' -> {
                    out.write(0); out.write(0); out.write(0); out.write(0)
                }
                c in '!'..'u' -> {
                    group[n++] = c - '!'
                    if (n == 5) {
                        write85Group(out, group)
                        n = 0
                    }
                }
                else -> return null
            }
        }
        if (n > 0) {
            for (j in n until 5) group[j] = 84
            write85Group(out, group, n - 1)
        }
        return out.toByteArray()
    }

    private fun write85Group(out: ByteArrayOutputStream, g: IntArray, keep: Int = 4) {
        var v = 0L
        for (j in 0 until 5) v = v * 85 + g[j]
        for (k in 0 until keep) {
            out.write(((v ushr (24 - k * 8)) and 0xFF).toInt())
        }
    }

    private fun runLengthDecode(data: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            i++
            if (len == 128) break
            if (len < 128) {
                val count = len + 1
                if (i + count > data.size) return null
                for (k in 0 until count) out.write(data[i + k].toInt())
                i += count
            } else {
                if (i >= data.size) return null
                val b = data[i].toInt()
                i++
                for (k in 0 until (257 - len)) out.write(b)
            }
        }
        return out.toByteArray()
    }
}
