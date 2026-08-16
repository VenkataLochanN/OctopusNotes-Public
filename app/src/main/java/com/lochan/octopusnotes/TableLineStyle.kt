package com.lochan.octopusnotes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.hypot
import kotlin.math.min

object TableLineStyle {

    private const val DOT_SPACING = 2.2f
    private const val DASH_ON = 2.6f
    private const val DASH_OFF = 2.2f

    fun drawLine(canvas: Canvas, paint: Paint, x0: Float, y0: Float, x1: Float, y1: Float, style: String) {
        val p = Paint(paint).apply { pathEffect = null }
        if (style == PenLineStyle.SOLID) {
            canvas.drawLine(x0, y0, x1, y1, p)
            return
        }
        val w = p.strokeWidth.coerceAtLeast(1f)
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 0.01f) return
        val ux = dx / len
        val uy = dy / len
        when (style) {
            PenLineStyle.DOTTED -> {

                val dotPaint = Paint(p).apply { this.style = Paint.Style.FILL }
                val gap = w * DOT_SPACING
                var d = 0f
                while (d <= len) {
                    canvas.drawCircle(x0 + ux * d, y0 + uy * d, w / 2f, dotPaint)
                    d += gap
                }
            }
            PenLineStyle.DASHED -> {
                val on = w * DASH_ON
                val off = w * DASH_OFF
                var d = 0f
                while (d < len) {
                    val e = min(d + on, len)
                    canvas.drawLine(x0 + ux * d, y0 + uy * d, x0 + ux * e, y0 + uy * e, p)
                    d += on + off
                }
            }
        }
    }

    fun drawPath(canvas: Canvas, path: Path, paint: Paint, style: String) {
        if (style == PenLineStyle.SOLID) {
            canvas.drawPath(path, paint)
            return
        }
        val p = Paint(paint).apply { pathEffect = null }
        val w = p.strokeWidth.coerceAtLeast(1f)

        val pm = PathMeasure(path, false)
        val pos = FloatArray(2)
        while (true) {
            val len = pm.length
            if (len > 0f) {
                when (style) {
                    PenLineStyle.DOTTED -> {
                        val dotPaint = Paint(p).apply { this.style = Paint.Style.FILL }
                        val gap = w * DOT_SPACING
                        var d = 0f
                        while (d <= len) {
                            pm.getPosTan(d, pos, null)
                            canvas.drawCircle(pos[0], pos[1], w / 2f, dotPaint)
                            d += gap
                        }
                    }
                    PenLineStyle.DASHED -> {
                        val on = w * DASH_ON
                        val off = w * DASH_OFF
                        var d = 0f
                        while (d < len) {
                            val e = min(d + on, len)
                            pm.getPosTan(d, pos, null)
                            val sx = pos[0]
                            val sy = pos[1]
                            pm.getPosTan(e, pos, null)
                            canvas.drawLine(sx, sy, pos[0], pos[1], p)
                            d += on + off
                        }
                    }
                }
            }
            if (!pm.nextContour()) break
        }
    }
}
