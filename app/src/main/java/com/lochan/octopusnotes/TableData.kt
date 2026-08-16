package com.lochan.octopusnotes

data class TableData(
    val rows: Int,
    val cols: Int,

    val cells: MutableMap<String, String> = mutableMapOf(),

    val headerRow: Boolean = true,
    val headerCol: Boolean = false,

    val headerColor: Int? = null,

    val headerColColor: Int? = null,

    val borderRadius: Float = 0f,

    val rowWeights: FloatArray? = null,

    val colWeights: FloatArray? = null,

    val merges: MutableMap<String, IntArray> = mutableMapOf()
) {

    fun rowHeights(h: Float): FloatArray {
        val r = rows.coerceAtLeast(1)
        val w = rowWeights
        if (w != null && w.size == r) {
            val sum = w.sum()
            if (sum > 0f) return FloatArray(r) { h * (w[it] / sum) }
        }
        return FloatArray(r) { h / r }
    }

    fun colWidths(w: Float): FloatArray {
        val c = cols.coerceAtLeast(1)
        val weights = colWeights
        if (weights != null && weights.size == c) {
            val sum = weights.sum()
            if (sum > 0f) return FloatArray(c) { w * (weights[it] / sum) }
        }
        return FloatArray(c) { w / c }
    }

    fun rowBoundaries(h: Float): FloatArray {
        val heights = rowHeights(h)
        return FloatArray(heights.size + 1).also { b ->
            for (i in heights.indices) b[i + 1] = b[i] + heights[i]
        }
    }

    fun colBoundaries(w: Float): FloatArray {
        val widths = colWidths(w)
        return FloatArray(widths.size + 1).also { b ->
            for (i in widths.indices) b[i + 1] = b[i] + widths[i]
        }
    }

    fun mergeAnchor(row: Int, col: Int): Pair<Int, Int>? {
        for ((k, span) in merges) {
            if (span.size < 2 || span[0] < 1 || span[1] < 1) continue
            val parts = k.split(",")
            val ar = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val ac = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (row in ar until ar + span[0] && col in ac until ac + span[1]) return ar to ac
        }
        return null
    }

    fun isCovered(row: Int, col: Int): Boolean {
        val anchor = mergeAnchor(row, col) ?: return false
        return anchor.first != row || anchor.second != col
    }
}
