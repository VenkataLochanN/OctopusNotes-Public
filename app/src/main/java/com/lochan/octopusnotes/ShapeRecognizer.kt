package com.lochan.octopusnotes

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Recognises a freehand stroke as a clean geometric shape — used by "draw & hold":
 * the user draws, keeps the pen still, and the in-progress stroke snaps to a
 * perfect **line, arrow, rectangle/square, or circle/ellipse**.
 *
 * Input/output are in the same coordinate space. The result is a polyline
 * (dense enough to render with `lineTo`) that [DrawingView] substitutes for the
 * raw points, so the normal stroke pipeline (undo, erase, serialize) is untouched.
 */
object ShapeRecognizer {

    fun recognize(raw: List<PointF>): List<PointF>? {
        if (raw.size < 8) return null
        val pts = resample(raw, 96)
        val len = pathLength(pts)
        if (len < 48f) return null

        val closed = dist(pts.first(), pts.last()) < 0.25f * len

        if (!closed) {
            detectArrow(pts, len)?.let { return it }
            detectLine(pts, len)?.let { return it }
            return null
        }

        // Rectangle vs circle: score how well the points hug the bounding-box outline
        // vs the fitted ellipse (mean pixel error), and take ONLY the better fit. Corner
        // counting is too fragile here — wobbly circles produce phantom corners — and
        // falling back to the other shape would turn a sloppy circle into a square.
        return if (ellipseResidualPx(pts) <= rectResidualPx(pts)) detectEllipse(pts)
        else detectRectangle(pts)
    }

    /** Mean pixel distance from the points to their bounding-box outline. */
    private fun rectResidualPx(pts: List<PointF>): Float {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        var sum = 0f
        for (p in pts) {
            sum += minOf(p.x - minX, maxX - p.x, p.y - minY, maxY - p.y)
        }
        return sum / pts.size
    }

    /** Mean pixel distance from the points to the ellipse fitted on their bounding box. */
    private fun ellipseResidualPx(pts: List<PointF>): Float {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        val rx = ((maxX - minX) / 2f).coerceAtLeast(1f)
        val ry = ((maxY - minY) / 2f).coerceAtLeast(1f)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        var sum = 0f
        for (p in pts) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            sum += abs(hypot(nx, ny) - 1f) * ((rx + ry) / 2f)
        }
        return sum / pts.size
    }

    // ---------------------------------------------------------------- line

    private fun detectLine(pts: List<PointF>, len: Float): List<PointF>? {
        val a = pts.first()
        val b = pts.last()
        val chord = dist(a, b)
        if (chord < 0.85f * len) return null // too curved
        val tol = maxOf(0.045f * len, 6f)
        for (p in pts) if (pointToSegment(p, a, b) > tol) return null
        return listOf(PointF(a.x, a.y), PointF(b.x, b.y))
    }

    // ---------------------------------------------------------------- arrow

    /**
     * Shaft + head flick(s) drawn in one stroke: a long first segment, then 1–2 short
     * segments that double back near the tip. Output retraces the tip so the arrow is
     * a single polyline: A → tip → wing1 → tip → wing2.
     */
    private fun detectArrow(pts: List<PointF>, len: Float): List<PointF>? {
        val verts = simplify(pts, maxOf(0.035f * len, 5f))
        if (verts.size < 3 || verts.size > 5) return null

        val a = verts[0]
        val tip = verts[1]
        val shaftLen = dist(a, tip)
        if (shaftLen < 0.5f * len) return null

        // Everything after the tip must be short strokes staying near the tip.
        for (i in 2 until verts.size) {
            if (dist(verts[i - 1], verts[i]) > 0.4f * shaftLen) return null
            if (dist(verts[i], tip) > 0.5f * shaftLen) return null
        }
        // The first head segment must turn sharply back relative to the shaft.
        val shaftAng = atan2(tip.y - a.y, tip.x - a.x)
        val headAng = atan2(verts[2].y - tip.y, verts[2].x - tip.x)
        val turn = abs(normalizeAngle(headAng - (shaftAng + Math.PI.toFloat())))
        if (turn > 1.2f) return null // head should be within ~70° of the reversed shaft

        // Canonical symmetric head.
        val headLen = (dist(tip, verts[2])).coerceIn(0.12f * shaftLen, 0.35f * shaftLen)
        val spread = 0.46f // ~26° each side
        val w1 = PointF(
            tip.x + headLen * cos(shaftAng + Math.PI.toFloat() - spread),
            tip.y + headLen * sin(shaftAng + Math.PI.toFloat() - spread)
        )
        val w2 = PointF(
            tip.x + headLen * cos(shaftAng + Math.PI.toFloat() + spread),
            tip.y + headLen * sin(shaftAng + Math.PI.toFloat() + spread)
        )
        return listOf(PointF(a.x, a.y), PointF(tip.x, tip.y), w1, PointF(tip.x, tip.y), w2)
    }

    // ---------------------------------------------------------------- rectangle

    private fun detectRectangle(pts: List<PointF>): List<PointF>? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        var w = maxX - minX
        var h = maxY - minY
        if (w < 24f || h < 24f) return null

        // The drawn perimeter should roughly match the box perimeter.
        val per = pathLength(pts) + dist(pts.last(), pts.first())
        val boxPer = 2 * (w + h)
        if (per < 0.72f * boxPer || per > 1.35f * boxPer) return null

        // Near-square → snap to a perfect square around the same centre.
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        if (abs(w - h) / maxOf(w, h) < 0.16f) {
            val side = (w + h) / 2f
            w = side; h = side
        }
        val l = cx - w / 2f; val t = cy - h / 2f
        val r = cx + w / 2f; val b = cy + h / 2f
        return listOf(PointF(l, t), PointF(r, t), PointF(r, b), PointF(l, b), PointF(l, t))
    }

    // ---------------------------------------------------------------- ellipse / circle

    private fun detectEllipse(pts: List<PointF>): List<PointF>? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        var rx = (maxX - minX) / 2f
        var ry = (maxY - minY) / 2f
        if (rx < 12f || ry < 12f) return null
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f

        // Points should sit near the ellipse: ((x-cx)/rx)² + ((y-cy)/ry)² ≈ 1.
        var residual = 0f
        for (p in pts) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            residual += abs(hypot(nx, ny) - 1f)
        }
        if (residual / pts.size > 0.28f) return null

        // Near-round → perfect circle.
        if (abs(rx - ry) / maxOf(rx, ry) < 0.2f) {
            val r = (rx + ry) / 2f
            rx = r; ry = r
        }
        val out = ArrayList<PointF>(65)
        for (i in 0..64) {
            val ang = (i / 64f) * 2f * Math.PI.toFloat()
            out.add(PointF(cx + rx * cos(ang), cy + ry * sin(ang)))
        }
        return out
    }

    // ---------------------------------------------------------------- geometry helpers

    /** Douglas–Peucker polyline simplification. */
    private fun simplify(pts: List<PointF>, epsilon: Float): List<PointF> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0f; var idx = -1
            for (i in s + 1 until e) {
                val d = pointToSegment(pts[i], pts[s], pts[e])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > epsilon && idx > 0) {
                keep[idx] = true
                stack.addLast(s to idx)
                stack.addLast(idx to e)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /** Resamples the polyline to [n] evenly spaced points. */
    private fun resample(pts: List<PointF>, n: Int): List<PointF> {
        val total = pathLength(pts)
        if (total <= 0f) return pts
        val step = total / (n - 1)
        val out = ArrayList<PointF>(n)
        out.add(PointF(pts[0].x, pts[0].y))
        var acc = 0f
        var i = 1
        var prev = pts[0]
        while (i < pts.size && out.size < n) {
            val d = dist(prev, pts[i])
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val nx = prev.x + t * (pts[i].x - prev.x)
                val ny = prev.y + t * (pts[i].y - prev.y)
                val np = PointF(nx, ny)
                out.add(np)
                prev = np
                acc = 0f
            } else {
                acc += d
                prev = pts[i]
                i++
            }
        }
        while (out.size < n) out.add(PointF(pts.last().x, pts.last().y))
        return out
    }

    private fun pathLength(pts: List<PointF>): Float {
        var l = 0f
        for (i in 1 until pts.size) l += dist(pts[i - 1], pts[i])
        return l
    }

    private fun dist(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)

    private fun pointToSegment(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun normalizeAngle(a: Float): Float {
        var x = a
        while (x > Math.PI) x -= (2 * Math.PI).toFloat()
        while (x < -Math.PI) x += (2 * Math.PI).toFloat()
        return x
    }
}
