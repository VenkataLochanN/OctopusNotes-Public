package com.lochan.octopusnotes

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

        detectTriangle(pts, len)?.let { return it }

        return if (ellipseResidualPx(pts) <= rectResidualPx(pts)) detectEllipse(pts)
        else detectRectangle(pts)
    }

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

    private fun detectLine(pts: List<PointF>, len: Float): List<PointF>? {
        val a = pts.first()
        val b = pts.last()
        val chord = dist(a, b)
        if (chord < 0.85f * len) return null

        val tol = maxOf(0.03f * len, 4f)
        for (p in pts) if (pointToSegment(p, a, b) > tol) return null
        return listOf(PointF(a.x, a.y), PointF(b.x, b.y))
    }

    private fun detectArrow(pts: List<PointF>, len: Float): List<PointF>? {
        val verts = simplify(pts, maxOf(0.035f * len, 5f))
        if (verts.size < 3 || verts.size > 5) return null

        val a = verts[0]
        val tip = verts[1]
        val shaftLen = dist(a, tip)
        if (shaftLen < 0.5f * len) return null

        for (i in 2 until verts.size) {
            if (dist(verts[i - 1], verts[i]) > 0.4f * shaftLen) return null
            if (dist(verts[i], tip) > 0.5f * shaftLen) return null
        }

        val shaftAng = atan2(tip.y - a.y, tip.x - a.x)
        val headAng = atan2(verts[2].y - tip.y, verts[2].x - tip.x)
        val turn = abs(normalizeAngle(headAng - (shaftAng + Math.PI.toFloat())))
        if (turn > 1.2f) return null

        val headLen = (dist(tip, verts[2])).coerceIn(0.12f * shaftLen, 0.35f * shaftLen)
        val spread = 0.46f
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

    private fun detectTriangle(pts: List<PointF>, len: Float): List<PointF>? {
        val n = pts.size

        val window = (n / 20).coerceAtLeast(3)
        val turn = FloatArray(n)
        val sharp = BooleanArray(n)
        for (i in 0 until n) {
            val p0 = pts[(i - window + n) % n]
            val p1 = pts[i]
            val p2 = pts[(i + window) % n]
            val a1 = atan2(p1.y - p0.y, p1.x - p0.x)
            val a2 = atan2(p2.y - p1.y, p2.x - p1.x)
            turn[i] = abs(normalizeAngle(a2 - a1))
            sharp[i] = turn[i] >= 0.9f
        }

        var start = -1
        for (i in 0 until n) if (!sharp[i]) { start = i; break }
        if (start == -1) return null

        val cornerIdx = ArrayList<Int>(4)
        var runStart = -1
        for (step in 1..n) {
            val k = (start + step) % n
            if (sharp[k]) {
                if (runStart == -1) runStart = k
            } else if (runStart != -1) {

                var peak = runStart
                var j = runStart
                while (j != k) {
                    if (turn[j] > turn[peak]) peak = j
                    j = (j + 1) % n
                }
                cornerIdx.add(peak)
                if (cornerIdx.size > 3) return null
                runStart = -1
            }
        }
        if (cornerIdx.size != 3) return null

        cornerIdx.sort()
        val a = pts[cornerIdx[0]]
        val b = pts[cornerIdx[1]]
        val c = pts[cornerIdx[2]]
        val ab = dist(a, b); val bc = dist(b, c); val ca = dist(c, a)
        val per = ab + bc + ca
        if (per < 1f) return null

        if (minOf(ab, bc, ca) < 0.15f * per || maxOf(ab, bc, ca) > 0.6f * per) return null

        val drawnPer = len + dist(pts.last(), pts.first())
        if (drawnPer < 0.72f * per || drawnPer > 1.4f * per) return null

        return listOf(PointF(a.x, a.y), PointF(b.x, b.y), PointF(c.x, c.y), PointF(a.x, a.y))
    }

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

        val per = pathLength(pts) + dist(pts.last(), pts.first())
        val boxPer = 2 * (w + h)
        if (per < 0.72f * boxPer || per > 1.35f * boxPer) return null

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

        var residual = 0f
        for (p in pts) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            residual += abs(hypot(nx, ny) - 1f)
        }
        if (residual / pts.size > 0.28f) return null

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
