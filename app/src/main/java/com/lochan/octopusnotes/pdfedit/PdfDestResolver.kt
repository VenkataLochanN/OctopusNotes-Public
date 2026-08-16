package com.lochan.octopusnotes.pdfedit

internal class PdfDestResolver(private val doc: PdfDoc, private val catalog: PdfObject.Dict) {

    private var depth = 0

    fun resolve(dest: PdfObject?): Int {
        val d = dest ?: return -1

        if (depth >= MAX_DEPTH) return -1
        depth++
        try {
            val v = try { d.deref(doc) } catch (e: Exception) { return -1 }
            return when (v) {
                is PdfObject.ArrayRef -> destArrayToPage(v)
                is PdfObject.Name -> resolveNamedDest(v.name)
                is PdfObject.Str -> resolveNamedDest(decodePdfString(v.bytes))
                is PdfObject.Number -> v.intValue?.toInt()?.let { pageInRange(it) } ?: -1
                else -> -1
            }
        } finally {
            depth--
        }
    }

    private fun destArrayToPage(arr: PdfObject.ArrayRef): Int {
        val first = arr.items.firstOrNull() ?: return -1

        if (first is PdfObject.Ref) {
            return try { doc.indexOfPage(first.num) } catch (e: Exception) { -1 }
        }
        val v = try { first.deref(doc) } catch (e: Exception) { return -1 }
        return when (v) {
            is PdfObject.Ref -> try { doc.indexOfPage(v.num) } catch (e: Exception) { -1 }
            is PdfObject.Name -> resolveNamedDest(v.name)
            is PdfObject.Str -> resolveNamedDest(decodePdfString(v.bytes))

            is PdfObject.Number -> v.intValue?.toInt()?.let { pageInRange(it) } ?: -1
            else -> -1
        }
    }

    fun resolveNamedDest(name: String): Int {

        val legacy = catalog.map["Dests"]?.deref(doc) as? PdfObject.Dict
        legacy?.map?.get(name)?.let { return resolve(it) }

        val names = catalog.map["Names"]?.deref(doc) as? PdfObject.Dict ?: return -1
        val tree = names.map["Dests"]?.deref(doc) as? PdfObject.Dict ?: return -1
        return searchNameTree(tree, name)
    }

    private fun searchNameTree(node: PdfObject.Dict, name: String): Int {
        val kids = node.map["Kids"]?.deref(doc) as? PdfObject.ArrayRef
        if (kids != null) {
            for (kid in kids.items) {
                val d = try { kid.deref(doc) as? PdfObject.Dict } catch (e: Exception) { null }
                    ?: continue
                val r = searchNameTree(d, name)
                if (r >= 0) return r
            }
        }
        val arr = node.map["Names"]?.deref(doc) as? PdfObject.ArrayRef
        if (arr != null) {
            var i = 0
            while (i + 1 < arr.items.size) {
                val k = when (val kk = try { arr.items[i].deref(doc) } catch (e: Exception) { null }) {
                    is PdfObject.Str -> decodePdfString(kk.bytes)
                    is PdfObject.Name -> kk.name
                    else -> null
                }
                if (k == name) return resolve(arr.items[i + 1])
                i += 2
            }
        }
        return -1
    }

    private fun pageInRange(i: Int): Int = if (i >= 0 && i < doc.pageCount) i else -1

    companion object {
        private const val MAX_DEPTH = 32
    }
}

internal fun decodePdfString(bytes: ByteArray): String =
    if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) {
        try { String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) } catch (e: Exception) { "" }
    } else String(bytes, Charsets.ISO_8859_1)
