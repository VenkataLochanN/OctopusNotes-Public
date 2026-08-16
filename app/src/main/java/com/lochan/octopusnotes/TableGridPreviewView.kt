package com.lochan.octopusnotes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class TableGridPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var rows = 3
    private var cols = 3
    private var headerRow = true
    private var headerCol = false
    private var lineStyle = PenLineStyle.SOLID
    private var rowWeights: FloatArray? = null
    private var colWeights: FloatArray? = null

    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#14000000")
    }
    private val headerPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#261563C0")
    }
    private val linePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.parseColor("#8A000000")
        strokeWidth = dp(1f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun setGrid(
        r: Int,
        c: Int,
        headerRow: Boolean = true,
        headerCol: Boolean = false,
        style: String = PenLineStyle.SOLID,
        rowWeights: FloatArray? = null,
        colWeights: FloatArray? = null
    ) {
        rows = r.coerceAtLeast(1)
        cols = c.coerceAtLeast(1)
        this.headerRow = headerRow
        this.headerCol = headerCol
        lineStyle = style
        this.rowWeights = rowWeights
        this.colWeights = colWeights
        invalidate()
    }

    private fun boundaries(count: Int, total: Float, weights: FloatArray?): FloatArray {
        val out = FloatArray(count + 1)
        if (weights != null && weights.size == count && weights.sum() > 0f) {
            var acc = 0f
            for (i in 0 until count) {
                acc += total * weights[i] / weights.sum()
                out[i + 1] = acc
            }
        } else {
            for (i in 0 until count) out[i + 1] = total * (i + 1) / count
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return
        val colX = boundaries(cols, w, colWeights)
        val rowY = boundaries(rows, h, rowWeights)
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        if (headerRow) canvas.drawRect(0f, 0f, w, rowY[1], headerPaint)
        if (headerCol) canvas.drawRect(0f, 0f, colX[1], h, headerPaint)
        for (i in 0..cols) {
            val x = colX[i]
            TableLineStyle.drawLine(canvas, linePaint, x, 0f, x, h, lineStyle)
        }
        for (j in 0..rows) {
            val y = rowY[j]
            TableLineStyle.drawLine(canvas, linePaint, 0f, y, w, y, lineStyle)
        }
    }
}
