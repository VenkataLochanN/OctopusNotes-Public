package com.lochan.octopusnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

private fun View.dp(v: Float) = v * resources.displayMetrics.density

private fun Canvas.drawSelector(cx: Float, cy: Float, r: Float, ring: Paint, density: Float) {
    ring.color = Color.BLACK
    ring.strokeWidth = density * 3f
    drawCircle(cx, cy, r, ring)
    ring.color = Color.WHITE
    ring.strokeWidth = density * 2f
    drawCircle(cx, cy, r, ring)
}

class SaturationValueView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onChange: ((saturation: Float, value: Float) -> Unit)? = null

    private var hue = 0f
    private var saturation = 1f
    private var value = 1f

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val corner get() = dp(12f)

    fun setHue(h: Float) {
        if (h == hue) return
        hue = h
        buildShaders()
        invalidate()
    }

    fun setSaturationValue(s: Float, v: Float) {
        saturation = s
        value = v
        invalidate()
    }

    private fun buildShaders() {
        if (width == 0 || height == 0) return
        val pure = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        huePaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f, Color.WHITE, pure, Shader.TileMode.CLAMP
        )
        shadePaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        buildShaders()
    }

    override fun onDraw(canvas: Canvas) {
        if (huePaint.shader == null) buildShaders()
        canvas.drawRoundRect(rect, corner, corner, huePaint)
        canvas.drawRoundRect(rect, corner, corner, shadePaint)

        val cx = saturation * width
        val cy = (1f - value) * height
        canvas.drawSelector(cx, cy, dp(9f), ringPaint, resources.displayMetrics.density)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {

                parent?.requestDisallowInterceptTouchEvent(true)
                saturation = (event.x / width).coerceIn(0f, 1f)
                value = (1f - event.y / height).coerceIn(0f, 1f)
                onChange?.invoke(saturation, value)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

class HueBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onChange: ((hue: Float) -> Unit)? = null

    private var hue = 0f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val track = RectF()

    private val thumbRadius get() = dp(11f)
    private val trackHeight get() = dp(12f)

    fun setHue(h: Float) {
        if (h == hue) return
        hue = h
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val inset = thumbRadius
        track.set(inset, (h - trackHeight) / 2f, w - inset, (h + trackHeight) / 2f)
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        trackPaint.shader = LinearGradient(
            track.left, 0f, track.right, 0f, hues, null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = track.height() / 2f
        canvas.drawRoundRect(track, r, r, trackPaint)

        val cx = track.left + (hue / 360f) * track.width()
        val cy = track.centerY()
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawCircle(cx, cy, thumbRadius, thumbFill)
        canvas.drawSelector(cx, cy, thumbRadius, ringPaint, resources.displayMetrics.density)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val t = ((event.x - track.left) / track.width()).coerceIn(0f, 1f)
                hue = t * 360f
                onChange?.invoke(hue)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

class ColorPaletteGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onPick: ((Int) -> Unit)? = null

    private val cols = 12
    private val rows = 10
    private val swatches = IntArray(cols * rows)
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val bounds = RectF()
    private var selected: Int? = null

    init {
        buildPalette()
    }

    private fun buildPalette() {
        for (row in 0 until rows) {
            val t = row / (rows - 1f)

            swatches[row * cols] = Color.HSVToColor(floatArrayOf(0f, 0f, 1f - t))
            for (col in 1 until cols) {
                val hue = (col - 1) * (360f / (cols - 1))

                val s = if (t <= 0.5f) 0.15f + 1.7f * t else 1f
                val v = if (t <= 0.5f) 1f else 1f - 1.4f * (t - 0.5f)
                swatches[row * cols + col] = Color.HSVToColor(
                    floatArrayOf(hue, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
                )
            }
        }
    }

    fun setSelectedColor(color: Int) {
        val opaque = color or 0xFF000000.toInt()
        val idx = swatches.indexOfFirst { it == opaque }
        val next = if (idx >= 0) idx else null
        if (next != selected) {
            selected = next
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)

        setMeasuredDimension(w, Math.round(w.toFloat() / cols * rows))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        val r = dp(12f)
        clip.reset()
        clip.addRoundRect(bounds, r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val cw = width.toFloat() / cols
        val ch = height.toFloat() / rows
        val save = canvas.save()
        canvas.clipPath(clip)
        for (i in swatches.indices) {
            val col = i % cols
            val row = i / cols
            cellPaint.color = swatches[i]

            canvas.drawRect(col * cw, row * ch, (col + 1) * cw + 1f, (row + 1) * ch + 1f, cellPaint)
        }
        canvas.restoreToCount(save)

        selected?.let { i ->
            val cx = (i % cols + 0.5f) * cw
            val cy = (i / cols + 0.5f) * ch
            canvas.drawSelector(cx, cy, minOf(cw, ch) * 0.36f, ringPaint, resources.displayMetrics.density)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val col = (event.x / (width.toFloat() / cols)).toInt().coerceIn(0, cols - 1)
                val row = (event.y / (height.toFloat() / rows)).toInt().coerceIn(0, rows - 1)
                val color = swatches[row * cols + col]
                selected = row * cols + col
                onPick?.invoke(color)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

class EyedropperOverlayView(context: Context) : View(context) {

    var onPicked: ((Int) -> Unit)? = null
    var onCancelled: (() -> Unit)? = null

    private var snapshot: Bitmap? = null

    private var snapshotOriginX = 0
    private var snapshotOriginY = 0

    private var touchX = -1f
    private var touchY = -1f
    private var picked = Color.BLACK
    private var hasSample = false

    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val loupeClip = Path()

    private val loupeRadius get() = dp(56f)
    private val zoom = 10f

    fun setSnapshot(bmp: Bitmap, originX: Int, originY: Int) {
        snapshot = bmp
        snapshotOriginX = originX
        snapshotOriginY = originY
        invalidate()
    }

    fun release() {
        snapshot?.recycle()
        snapshot = null
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = snapshot ?: return
        if (!hasSample) {
            drawHint(canvas)
            return
        }

        val cx = touchX
        val above = touchY - loupeRadius - dp(28f)
        val cy = if (above - loupeRadius < 0) touchY + loupeRadius + dp(28f) else above

        loupeClip.reset()
        loupeClip.addCircle(cx, cy, loupeRadius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(loupeClip)

        canvas.translate(cx, cy)
        canvas.scale(zoom, zoom)
        canvas.translate(-bitmapX().toFloat() - 0.5f, -bitmapY().toFloat() - 0.5f)
        canvas.drawBitmap(bmp, 0f, 0f, loupePaint)
        canvas.restoreToCount(save)

        cellPaint.color = Color.WHITE
        cellPaint.strokeWidth = dp(1.5f)
        canvas.drawRect(cx - zoom / 2f, cy - zoom / 2f, cx + zoom / 2f, cy + zoom / 2f, cellPaint)

        ringPaint.color = picked
        ringPaint.strokeWidth = dp(8f)
        canvas.drawCircle(cx, cy, loupeRadius - dp(4f), ringPaint)
        ringPaint.color = Color.WHITE
        ringPaint.strokeWidth = dp(2f)
        canvas.drawCircle(cx, cy, loupeRadius, ringPaint)
    }

    private fun drawHint(canvas: Canvas) {
        hintPaint.textSize = dp(15f)
        val text = "Touch anywhere to pick a colour"
        val cx = width / 2f
        val cy = height * 0.12f
        val w = hintPaint.measureText(text)
        val padH = dp(16f)
        val padV = dp(10f)
        val r = RectF(cx - w / 2 - padH, cy - padV - hintPaint.textSize, cx + w / 2 + padH, cy + padV)
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, hintBg)
        canvas.drawText(text, cx, cy - dp(2f), hintPaint)
    }

    private fun bitmapX(): Int {
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        return (loc[0] + touchX).toInt() - snapshotOriginX
    }

    private fun bitmapY(): Int {
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        return (loc[1] + touchY).toInt() - snapshotOriginY
    }

    private fun sample() {
        val bmp = snapshot ?: return
        val x = bitmapX().coerceIn(0, bmp.width - 1)
        val y = bitmapY().coerceIn(0, bmp.height - 1)
        picked = bmp.getPixel(x, y) or 0xFF000000.toInt()
        hasSample = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                sample()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                touchX = event.x
                touchY = event.y
                sample()
                onPicked?.invoke(picked)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                onCancelled?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
