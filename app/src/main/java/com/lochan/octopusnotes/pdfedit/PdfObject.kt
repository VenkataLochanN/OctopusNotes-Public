package com.lochan.octopusnotes.pdfedit

import java.io.ByteArrayOutputStream

sealed class PdfObject {
    data object Null : PdfObject()

    data class Bool(val value: Boolean) : PdfObject()

    data class Number(val raw: String) : PdfObject() {
        val intValue: Long? get() = raw.toLongOrNull()
        val doubleValue: Double get() = raw.toDoubleOrNull() ?: 0.0
    }

    data class Name(val name: String) : PdfObject()

    data class Str(val bytes: ByteArray) : PdfObject()

    data class ArrayRef(val items: MutableList<PdfObject> = mutableListOf()) : PdfObject()

    data class Dict(val map: LinkedHashMap<String, PdfObject> = LinkedHashMap()) : PdfObject()

    data class Ref(val num: Int, val gen: Int) : PdfObject()

    data class Stream(val dict: Dict, val data: ByteArray) : PdfObject()

    val asDict: Dict? get() = this as? Dict
    val asArray: ArrayRef? get() = this as? ArrayRef
    val asName: Name? get() = this as? Name
    val asNumber: Number? get() = this as? Number
    val asRef: Ref? get() = this as? Ref

    fun deref(doc: PdfDoc): PdfObject? = if (this is Ref) doc.loadRef(this) else this

    fun writeTo(out: ByteArrayOutputStream) {
        when (this) {
            is Null -> {
                out.write('n'.code); out.write('u'.code); out.write('l'.code); out.write('l'.code)
            }
            is Bool -> {
                if (value) {
                    out.write('t'.code); out.write('r'.code); out.write('u'.code); out.write('e'.code)
                } else {
                    out.write('f'.code); out.write('a'.code); out.write('l'.code); out.write('s'.code); out.write('e'.code)
                }
            }
            is Number -> out.write(raw.toByteArray(Charsets.US_ASCII))
            is Name -> writeName(out, name)
            is Str -> writeString(out, bytes)
            is ArrayRef -> {
                out.write('['.code); out.write(' '.code)
                for (i in items.indices) {
                    if (i > 0) out.write(' '.code)
                    items[i].writeTo(out)
                }
                out.write(' '.code); out.write(']'.code)
            }
            is Dict -> {
                out.write('<'.code); out.write('<'.code)
                for ((k, v) in map) {
                    out.write(' '.code)
                    writeName(out, k)
                    out.write(' '.code)
                    v.writeTo(out)
                }
                out.write(' '.code); out.write('>'.code); out.write('>'.code)
            }
            is Ref -> {
                out.write(num.toString().toByteArray(Charsets.US_ASCII))
                out.write(' '.code)
                out.write(gen.toString().toByteArray(Charsets.US_ASCII))
                out.write(' '.code); out.write('R'.code)
            }
            is Stream -> {
                dict.writeTo(out)
                out.write('\n'.code)
                out.write('s'.code); out.write('t'.code); out.write('r'.code); out.write('e'.code); out.write('a'.code); out.write('m'.code)
                out.write('\n'.code)
                out.write(data)
                out.write('\n'.code)
                out.write('e'.code); out.write('n'.code); out.write('d'.code); out.write('s'.code); out.write('t'.code); out.write('r'.code); out.write('e'.code); out.write('a'.code); out.write('m'.code)
            }
        }
    }

    fun toByteArray(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray()

    companion object {

        private val NAME_DELIMS: BooleanArray = BooleanArray(256).also { arr ->
            for (c in "()<>[]{}/%#".map { it.code }) arr[c] = true
        }

        private fun isRegularNameChar(c: Int): Boolean =
            c in 0x21..0x7E && !NAME_DELIMS[c]

        fun writeName(out: ByteArrayOutputStream, name: String) {
            out.write('/'.code)
            for (b in name.toByteArray(Charsets.ISO_8859_1)) {
                val c = b.toInt() and 0xFF
                if (isRegularNameChar(c)) {
                    out.write(c)
                } else {
                    out.write('#'.code)
                    out.write(HEX[(c ushr 4) and 0xF].code)
                    out.write(HEX[c and 0xF].code)
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"

        fun writeString(out: ByteArrayOutputStream, bytes: ByteArray) {
            var allPrintable = true
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c !in 0x20..0x7E || c == '\\'.code || c == '('.code || c == ')'.code) {
                    allPrintable = false
                    break
                }
            }
            if (allPrintable) {
                out.write('('.code)
                for (b in bytes) {
                    val c = b.toInt() and 0xFF
                    when (c) {
                        '\\'.code -> { out.write('\\'.code); out.write('\\'.code) }
                        '('.code -> { out.write('\\'.code); out.write('('.code) }
                        ')'.code -> { out.write('\\'.code); out.write(')'.code) }
                        else -> out.write(c)
                    }
                }
                out.write(')'.code)
            } else {
                out.write('<'.code)
                for (b in bytes) {
                    val c = b.toInt() and 0xFF
                    out.write(HEX[(c ushr 4) and 0xF].code)
                    out.write(HEX[c and 0xF].code)
                }
                out.write('>'.code)
            }
        }
    }
}
