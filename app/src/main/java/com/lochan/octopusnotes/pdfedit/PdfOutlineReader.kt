package com.lochan.octopusnotes.pdfedit

class PdfOutlineReader private constructor(private val doc: PdfDoc) {

    data class Item(
        val title: String,
        val pageIndex: Int,
        val depth: Int,
        val children: List<Item>
    )

    private val catalog = doc.catalogDict()
    private val dests = PdfDestResolver(doc, catalog)

    fun read(): List<Item> {
        val outlines = catalog.map["Outlines"]?.deref(doc) as? PdfObject.Dict ?: return emptyList()
        val first = outlines.map["First"]?.deref(doc) ?: return emptyList()
        return walkSiblings(first, 0)
    }

    private fun walkSiblings(node: PdfObject?, depth: Int): List<Item> {
        val out = mutableListOf<Item>()
        var cur = node
        var guard = 0
        while (cur != null && guard++ < 5000) {
            val item = cur as? PdfObject.Dict ?: break
            val title = decodePdfString((item.map["Title"] as? PdfObject.Str)?.bytes ?: ByteArray(0))
            val children = (item.map["First"]?.deref(doc))?.let { walkSiblings(it, depth + 1) }
                ?: emptyList()
            val page = resolveDest(item)

            if (page >= 0 || children.isNotEmpty()) {
                out.add(Item(title, page, depth, children))
            }
            cur = item.map["Next"]?.deref(doc)
        }
        return out
    }

    private fun resolveDest(item: PdfObject.Dict): Int {
        var dest: PdfObject? = item.map["Dest"]
        if (dest == null) {
            val action = item.map["A"]?.deref(doc) as? PdfObject.Dict
            if (action != null && action.map["S"]?.asName?.name == "GoTo") {
                dest = action.map["D"]
            }
        }
        return dests.resolve(dest)
    }

    companion object {

        fun read(data: ByteArray): List<Item> {
            return try {
                val doc = PdfDoc.open(data)
                PdfOutlineReader(doc).read()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
