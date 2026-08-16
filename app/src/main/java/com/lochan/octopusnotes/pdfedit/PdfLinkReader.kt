package com.lochan.octopusnotes.pdfedit

class PdfLinkReader private constructor(private val doc: PdfDoc) {

    data class Link(
        val pageIndex: Int,
        val rect: FloatArray?,
        val uri: String?,
        val destPage: Int
    )

    private val catalog = doc.catalogDict()
    private val dests = PdfDestResolver(doc, catalog)

    fun read(): List<Link> {
        val links = mutableListOf<Link>()
        val n = doc.pageCount
        for (page in 0 until n) {
            val pageDict = try { doc.pageDict(page) } catch (e: Exception) { continue }
            val annotsVal = pageDict.map["Annots"] ?: continue
            val annots = try { annotsVal.deref(doc) as? PdfObject.ArrayRef } catch (e: Exception) { continue }
                ?: continue
            val pageW: Float
            val pageH: Float
            try {
                val (w, h) = doc.pageSize(page)
                pageW = w; pageH = h
            } catch (e: Exception) {
                continue
            }
            for (a in annots.items) {
                val annot = try { a.deref(doc) as? PdfObject.Dict } catch (e: Exception) { continue }
                    ?: continue
                if (annot.map["Subtype"]?.asName?.name != "Link") continue

                val rect = readRect(annot.map["Rect"]?.deref(doc), pageW, pageH)
                val action = annot.map["A"]?.deref(doc) as? PdfObject.Dict
                val s = action?.map?.get("S")?.asName?.name
                val uri: String?
                var destPage = -1
                when (s) {
                    "URI" -> uri = action?.map?.get("URI")?.let { decodeString(it) }
                    "GoTo" -> {
                        uri = null
                        destPage = dests.resolve(action?.map?.get("D"))
                    }
                    "Launch" -> {
                        val f = action?.map?.get("F")?.deref(doc) as? PdfObject.Dict
                        val u = f?.map?.get("F")?.let { decodeString(it) }
                        if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) uri = u
                        else uri = null
                    }

                    else -> {
                        uri = null
                        if (annot.map["Dest"] != null) destPage = dests.resolve(annot.map["Dest"])
                    }
                }
                if (uri != null) {
                    links.add(Link(page, rect, uri, -1))
                } else if (destPage >= 0) {
                    links.add(Link(page, rect, null, destPage))
                }
            }
        }
        return links
    }

    private fun readRect(v: PdfObject?, pageW: Float, pageH: Float): FloatArray? {
        val arr = v as? PdfObject.ArrayRef ?: return null
        if (arr.items.size < 4) return null
        val x1 = arr.items[0].asNumber?.doubleValue ?: return null
        val y1 = arr.items[1].asNumber?.doubleValue ?: return null
        val x2 = arr.items[2].asNumber?.doubleValue ?: return null
        val y2 = arr.items[3].asNumber?.doubleValue ?: return null
        if (pageW <= 0f || pageH <= 0f) return null
        val left = (minOf(x1, x2) / pageW).toFloat().coerceIn(0f, 1f)
        val right = (maxOf(x1, x2) / pageW).toFloat().coerceIn(0f, 1f)
        val top = (1f - maxOf(y1, y2) / pageH).toFloat().coerceIn(0f, 1f)
        val bottom = (1f - minOf(y1, y2) / pageH).toFloat().coerceIn(0f, 1f)
        return floatArrayOf(left, top, right, bottom)
    }

    private fun decodeString(obj: PdfObject): String? = when (obj) {
        is PdfObject.Str -> decodePdfString(obj.bytes)
        is PdfObject.Name -> obj.name
        else -> null
    }

    companion object {

        fun read(data: ByteArray): List<Link> {
            return try {
                val doc = PdfDoc.open(data)
                PdfLinkReader(doc).read()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
