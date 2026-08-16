package com.lochan.octopusnotes

import android.graphics.Paint
import android.graphics.Path
import java.util.UUID

data class StrokeData(
    val id: String = UUID.randomUUID().toString(),
    val path: Path,
    val paint: Paint,
    val isPixelEraser: Boolean = false,
    val type: String = StrokeType.PEN,

    val lineStyle: String = PenLineStyle.SOLID,

    val imageFile: String? = null,

    var imageRotation: Float = 0f,

    var tableData: TableData? = null,

    var textData: TextData? = null,

    val tapeData: TapeData? = null
) {

    var savedContours: List<FloatArray>? = null
}

object PenLineStyle {
    const val SOLID = "SOLID"
    const val DOTTED = "DOTTED"
    const val DASHED = "DASHED"

    fun pathEffect(style: String, width: Float): android.graphics.PathEffect? {
        val w = width.coerceAtLeast(1f)
        return when (style) {

            DOTTED -> android.graphics.DashPathEffect(floatArrayOf(0.01f, w * 2.2f), 0f)
            DASHED -> android.graphics.DashPathEffect(floatArrayOf(w * 2.6f, w * 2.2f), 0f)
            else -> null
        }
    }
}