package com.lochan.octopusnotes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ZoomableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var zoom: Float = 1f
        private set
    var transX: Float = 0f
        private set
    var transY: Float = 0f
        private set

    fun resetZoom() {
        zoomAnimator?.cancel()
        zoom = 1f
        transX = 0f
        transY = 0f
    }

    var minZoom: Float = 0.5f
    var maxZoom: Float = 10.0f

    var onPageChanged: ((page: Int, pageCount: Int) -> Unit)? = null
    var onSingleTap: ((MotionEvent) -> Boolean)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    var onViewportWidthChanged: ((newWidth: Int, oldWidth: Int) -> Unit)? = null

    var onPanned: ((Float, Float) -> Unit)? = null

    var onZoomed: ((Float) -> Unit)? = null

    var onScrollStateChanged: ((Int) -> Unit)? = null

    @Volatile
    var dragInProgress: Boolean = false

    var hiResRenderer: (suspend (Int, Float, Float, Float, Int, Int, Int) -> Bitmap?)? = null
    var hiResScope: CoroutineScope? = null

    var drawPageInk: ((Canvas, page: Int, pageW: Float, pageH: Float) -> Unit)? = null

    private var currentVisiblePage = -1
    private var lastPanX = 0f
    private var lastPanY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean =
                onSingleTap?.invoke(e) ?: false

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (zoom > 1.5f) 1f else 2f
                animateZoomTo(target, e.x, e.y)
                return true
            }
        }
    )

    init {
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = layoutManager as? LinearLayoutManager ?: return
                val first = lm.findFirstCompletelyVisibleItemPosition()
                    .takeIf { it != NO_POSITION } ?: lm.findFirstVisibleItemPosition()
                if (first != NO_POSITION && first != currentVisiblePage) {
                    currentVisiblePage = first
                    onPageChanged?.invoke(first, adapter?.itemCount ?: 0)
                }
                scheduleHiRes()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                onScrollStateChanged?.invoke(newState)

                if (newState == SCROLL_STATE_IDLE) scheduleHiRes()
            }
        })
    }

    private var zoomAnimator: android.animation.ValueAnimator? = null

    fun animateZoomTo(target: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        val startZoom = zoom
        val endZoom = target.coerceIn(minZoom, maxZoom)
        if (kotlin.math.abs(endZoom - startZoom) < 0.001f) return

        val contentFx = (focusX - transX) / startZoom
        val contentFy = (focusY - transY) / startZoom
        zoomAnimator = android.animation.ValueAnimator.ofFloat(startZoom, endZoom).apply {
            duration = 240
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                zoom = a.animatedValue as Float
                transX = focusX - contentFx * zoom
                transY = focusY - contentFy * zoom
                clampTrans()
                invalidate()
                onZoomChanged?.invoke(zoom)
                onZoomed?.invoke(zoom)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = scheduleHiRes()
            })
            start()
        }
    }

    fun setZoomLevel(target: Float) = animateZoomTo(target, width / 2f, height / 2f)

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        val real = newZoom / zoom
        if (real == 1f) return

        transX = focusX - (focusX - transX) * real
        transY = focusY - (focusY - transY) * real
        zoom = newZoom
        clampTrans()
        invalidate()
        onZoomChanged?.invoke(zoom)
        onZoomed?.invoke(zoom)
        scheduleHiRes()
    }

    private fun clampTrans() {
        val w = width.toFloat()
        val scaledW = w * zoom
        transX = if (scaledW <= w) (w - scaledW) / 2f else transX.coerceIn(w - scaledW, 0f)

        if (zoom <= 1.001f) {
            transY = 0f
            return
        }

        val first = getChildAt(0)
        val last = getChildAt(childCount - 1)
        if (first != null && last != null) {
            val h = height.toFloat()
            val topScreen = first.top * zoom + transY
            val bottomScreen = last.bottom * zoom + transY
            val contentH = bottomScreen - topScreen
            if (contentH <= h) {
                transY += (h - (topScreen + bottomScreen)) / 2f
            } else {
                if (topScreen > 0f) transY -= topScreen
                if (last.bottom * zoom + transY < h) transY += h - (last.bottom * zoom + transY)
            }
        }
    }

    private class HiResTile(
        val page: Int,
        val bitmap: Bitmap,

        val localLeft: Float,
        val localTop: Float,

        val renderZoom: Float
    )

    private var tiles: List<HiResTile> = emptyList()
    private var hiResJob: Job? = null
    private var hiResGen = 0
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hiResRunnable = Runnable { renderHiResTiles() }

    private fun scheduleHiRes() {
        removeCallbacks(hiResRunnable)
        if (dragInProgress) {

            return
        }
        if (zoom <= HI_RES_MIN_ZOOM) {
            if (tiles.isNotEmpty()) dropTiles()
            return
        }
        postDelayed(hiResRunnable, 160)
    }

    fun scheduleHiResPublic() = scheduleHiRes()

    fun clearHiResTiles() {
        removeCallbacks(hiResRunnable)
        hiResJob?.cancel()
        hiResGen++
        dropTiles()
    }

    fun cancelHiResForDrag() {
        removeCallbacks(hiResRunnable)
        hiResJob?.cancel()
        hiResGen++
        dropTiles()
    }

    private fun dropTiles() {
        val old = tiles
        tiles = emptyList()
        invalidate()
        old.forEach { it.bitmap.recycle() }
    }

    private class TileRequest(
        val page: Int, val localLeft: Float, val localTop: Float,
        val childWidth: Int, val outW: Int, val outH: Int
    )

    private fun renderHiResTiles() {
        val renderer = hiResRenderer ?: return
        val scope = hiResScope ?: return
        if (zoom <= HI_RES_MIN_ZOOM || width == 0) return

        val z = zoom

        val vpL = (0f - transX) / z
        val vpT = (0f - transY) / z
        val vpR = (width - transX) / z
        val vpB = (height - transY) / z

        val requests = mutableListOf<TileRequest>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val pos = getChildAdapterPosition(child)
            if (pos == NO_POSITION) continue
            val visL = maxOf(child.left.toFloat(), vpL)
            val visT = maxOf(child.top.toFloat(), vpT)
            val visR = minOf(child.right.toFloat(), vpR)
            val visB = minOf(child.bottom.toFloat(), vpB)
            if (visR <= visL || visB <= visT) continue
            val outW = ((visR - visL) * z).toInt()
            val outH = ((visB - visT) * z).toInt()
            if (outW <= 0 || outH <= 0) continue
            requests.add(
                TileRequest(pos, visL - child.left, visT - child.top, child.width, outW, outH)
            )
        }
        if (requests.isEmpty()) return

        val gen = ++hiResGen
        hiResJob?.cancel()
        hiResJob = scope.launch {
            val newTiles = mutableListOf<HiResTile>()
            var completed = false
            try {
                for (r in requests) {
                    val bmp = renderer(r.page, r.localLeft, r.localTop, z, r.childWidth, r.outW, r.outH)
                        ?: continue
                    newTiles.add(HiResTile(r.page, bmp, r.localLeft, r.localTop, z))
                }
                completed = true
            } finally {

                if (!completed) newTiles.forEach { it.bitmap.recycle() }
            }
            if (gen == hiResGen) {
                val old = tiles
                tiles = newTiles
                invalidate()
                old.forEach { it.bitmap.recycle() }
            } else {
                newTiles.forEach { it.bitmap.recycle() }
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(zoom, zoom)
        super.dispatchDraw(canvas)
        canvas.restore()

        if (tiles.isEmpty() || zoom <= HI_RES_MIN_ZOOM) return
        for (t in tiles) {

            val child = findViewHolderForAdapterPosition(t.page)?.itemView ?: continue
            if (t.bitmap.isRecycled) continue
            val scale = zoom / t.renderZoom
            val dstL = (child.left + t.localLeft) * zoom + transX
            val dstT = (child.top + t.localTop) * zoom + transY
            val dst = RectF(
                dstL, dstT,
                dstL + t.bitmap.width * scale,
                dstT + t.bitmap.height * scale
            )
            canvas.drawBitmap(t.bitmap, null, dst, tilePaint)

            val ink = drawPageInk ?: continue
            canvas.save()
            canvas.translate(transX, transY)
            canvas.scale(zoom, zoom)
            canvas.translate(child.left.toFloat(), child.top.toFloat())
            canvas.clipRect(
                t.localLeft, t.localTop,
                t.localLeft + t.bitmap.width / t.renderZoom,
                t.localTop + t.bitmap.height / t.renderZoom
            )
            ink(canvas, t.page, child.width.toFloat(), child.height.toFloat())
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        clearHiResTiles()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw && h == oldh) return

        onViewportWidthChanged?.invoke(w, oldw)
        clearHiResTiles()
        invalidate()
        post {
            clampTrans()
            scheduleHiRes()
            invalidate()
        }
    }

    private fun focalPoint(e: MotionEvent): Pair<Float, Float> {
        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        return if (n == 0) Pair(e.x, e.y) else Pair(sx / n, sy / n)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastPanX = e.x; lastPanY = e.y }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {

                    val cancel = MotionEvent.obtain(e)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                }
                val (fx, fy) = focalPoint(e)
                lastPanX = fx; lastPanY = fy
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val (fx, fy) = focalPoint(e)
                lastPanX = fx; lastPanY = fy
            }
        }

        if (scaleDetector.isInProgress) {
            val (fx, fy) = focalPoint(e)
            lastPanX = fx; lastPanY = fy
            return true
        }

        if (zoom > 1.001f) {
            if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                val (fx, fy) = focalPoint(e)
                val dx = fx - lastPanX
                val dy = fy - lastPanY
                lastPanX = fx; lastPanY = fy

                val oldTX = transX
                val oldTY = transY
                transX += dx
                transY += dy
                clampTrans()
                onPanned?.invoke(transX - oldTX, transY - oldTY)

                val consumedDy = transY - oldTY
                val excessDy = dy - consumedDy
                if (kotlin.math.abs(excessDy) > 1f) {
                    scrollBy(0, (-excessDy).toInt())
                    clampTrans()
                }

                invalidate()
                scheduleHiRes()
            }
            return true
        }

        return super.onTouchEvent(e)
    }

    val currentPage: Int get() = currentVisiblePage.coerceAtLeast(0)

    fun createZoomAwareLayoutManager(): LinearLayoutManager {
        return object : LinearLayoutManager(context) {
            override fun calculateExtraLayoutSpace(state: State, extra: IntArray) {
                if (zoom >= 0.99f) { extra[0] = 0; extra[1] = 0; return }
                val bonus = ((height / zoom) - height).toInt().coerceAtLeast(0)
                extra[0] = bonus
                extra[1] = bonus
            }
        }
    }

    companion object {
        const val PAYLOAD_ZOOM = "zoom"
        private const val HI_RES_MIN_ZOOM = 1.15f
    }
}
