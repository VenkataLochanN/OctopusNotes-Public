package com.lochan.octopusnotes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class ImageCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bmp: Bitmap? = null
    private var cropInitialized = false

    private val imgRect = RectF()
    private val cropRect = RectF()
    private val fitMatrix = Matrix()

    private val density = resources.displayMetrics.density
    private val minCropSize = 48 * density

    private val scrimPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.WHITE
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        color = Color.argb(130, 255, 255, 255)
        isAntiAlias = true
    }
    private val handleFill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        isAntiAlias = true
    }
    private val handleRing = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.parseColor("#1565C0")
        isAntiAlias = true
    }

    private enum class Hit { NONE, MOVE, TL, TR, BL, BR }
    private var hit = Hit.NONE
    private var lastX = 0f
    private var lastY = 0f

    fun setBitmap(b: Bitmap) {
        bmp = b
        cropInitialized = false
        if (width > 0 && height > 0) {
            resetCrop()
        } else {
            post { if (bmp === b && width > 0) resetCrop() }
        }
        invalidate()
    }

    fun clearBitmap() {
        bmp = null
        cropInitialized = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val b = bmp ?: return
        if (w <= 0 || h <= 0) return
        if (!cropInitialized) {
            resetCrop()
            return
        }

        val oldImg = RectF(imgRect)
        val oldCrop = RectF(cropRect)
        fitMatrix.reset()
        val scale = minOf(width / b.width.toFloat(), height / b.height.toFloat())
        val dx = (width - b.width * scale) / 2f
        val dy = (height - b.height * scale) / 2f
        fitMatrix.postScale(scale, scale)
        fitMatrix.postTranslate(dx, dy)
        imgRect.set(dx, dy, dx + b.width * scale, dy + b.height * scale)
        if (oldImg.width() <= 0f || oldImg.height() <= 0f) {
            resetCrop()
            return
        }
        fun fx(v: Float) = imgRect.left + (v - oldImg.left) / oldImg.width() * imgRect.width()
        fun fy(v: Float) = imgRect.top + (v - oldImg.top) / oldImg.height() * imgRect.height()
        cropRect.set(fx(oldCrop.left), fy(oldCrop.top), fx(oldCrop.right), fy(oldCrop.bottom))
        invalidate()
    }

    private fun resetCrop() {
        val b = bmp ?: return
        cropInitialized = true
        fitMatrix.reset()
        val scale = minOf(width / b.width.toFloat(), height / b.height.toFloat())
        val dx = (width - b.width * scale) / 2f
        val dy = (height - b.height * scale) / 2f
        fitMatrix.postScale(scale, scale)
        fitMatrix.postTranslate(dx, dy)
        imgRect.set(dx, dy, dx + b.width * scale, dy + b.height * scale)

        val insetX = imgRect.width() * 0.05f
        val insetY = imgRect.height() * 0.05f
        cropRect.set(
            imgRect.left + insetX, imgRect.top + insetY,
            imgRect.right - insetX, imgRect.bottom - insetY
        )
    }

    fun cropRectInBitmap(): Rect {
        val b = bmp ?: return Rect(0, 0, 0, 0)
        if (imgRect.width() <= 0f || imgRect.height() <= 0f) return Rect(0, 0, b.width, b.height)
        fun x(v: Float) = ((v - imgRect.left) / imgRect.width() * b.width)
        fun y(v: Float) = ((v - imgRect.top) / imgRect.height() * b.height)
        val l = x(cropRect.left).toInt().coerceIn(0, b.width - 1)
        val t = y(cropRect.top).toInt().coerceIn(0, b.height - 1)
        val r = x(cropRect.right).toInt().coerceIn(l + 1, b.width)
        val bt = y(cropRect.bottom).toInt().coerceIn(t + 1, b.height)
        return Rect(l, t, r, bt)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp ?: return
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(b, fitMatrix, null)

        val scrim = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(cropRect, Path.Direction.CW)
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(scrim, scrimPaint)

        val w = cropRect.width()
        val h = cropRect.height()
        for (i in 1..2) {
            val x = cropRect.left + w * i / 3f
            canvas.drawLine(x, cropRect.top, x, cropRect.bottom, gridPaint)
            val y = cropRect.top + h * i / 3f
            canvas.drawLine(cropRect.left, y, cropRect.right, y, gridPaint)
        }

        canvas.drawRect(cropRect, borderPaint)
        val handleR = 9 * density
        fun handle(cx: Float, cy: Float) {
            canvas.drawCircle(cx, cy, handleR, handleFill)
            canvas.drawCircle(cx, cy, handleR, handleRing)
        }
        handle(cropRect.left, cropRect.top)
        handle(cropRect.right, cropRect.top)
        handle(cropRect.left, cropRect.bottom)
        handle(cropRect.right, cropRect.bottom)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bmp == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hit = hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
                if (hit != Hit.NONE) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hit != Hit.NONE) {
                    applyDrag(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hit = Hit.NONE
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Hit {
        val r = 26 * density
        fun near(cx: Float, cy: Float) = abs(x - cx) <= r && abs(y - cy) <= r
        if (near(cropRect.left, cropRect.top)) return Hit.TL
        if (near(cropRect.right, cropRect.top)) return Hit.TR
        if (near(cropRect.left, cropRect.bottom)) return Hit.BL
        if (near(cropRect.right, cropRect.bottom)) return Hit.BR
        if (cropRect.contains(x, y)) return Hit.MOVE
        return Hit.NONE
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (hit) {
            Hit.MOVE -> {
                val nx = (cropRect.left + dx).coerceIn(imgRect.left, imgRect.right - cropRect.width())
                val ny = (cropRect.top + dy).coerceIn(imgRect.top, imgRect.bottom - cropRect.height())
                cropRect.offset(nx - cropRect.left, ny - cropRect.top)
            }
            Hit.TL -> {
                cropRect.left = (cropRect.left + dx).coerceIn(imgRect.left, cropRect.right - minCropSize)
                cropRect.top = (cropRect.top + dy).coerceIn(imgRect.top, cropRect.bottom - minCropSize)
            }
            Hit.TR -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, imgRect.right)
                cropRect.top = (cropRect.top + dy).coerceIn(imgRect.top, cropRect.bottom - minCropSize)
            }
            Hit.BL -> {
                cropRect.left = (cropRect.left + dx).coerceIn(imgRect.left, cropRect.right - minCropSize)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, imgRect.bottom)
            }
            Hit.BR -> {
                cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, imgRect.right)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, imgRect.bottom)
            }
            else -> return
        }
        invalidate()
    }
}
