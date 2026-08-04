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

/**
 * A [RecyclerView] that adds pinch-to-zoom and 2-D panning.
 *
 * Zoom is applied as a canvas transform in [dispatchDraw] (translate + scale) — items are
 * never resized, so there's no relayout jump or overlap. At 1× the list scrolls normally
 * (and recycles); when zoomed in, single-finger drag pans in both axes within bounds.
 *
 * **Sharp zoom**: base page bitmaps are rendered at zoom-1 width, so scaling them up blurs.
 * When the transform settles while zoomed in, the visible region of each visible page is
 * re-rendered at *screen* resolution ([hiResRenderer]) and drawn on top as a tile, with the
 * page's ink re-drawn above it ([drawPageInk]) so strokes stay visible and crisp.
 *
 * [transX]/[transY]/[zoom] are exposed so [DrawingView] can map screen touches to page space:
 *   contentPoint = (screenPoint - trans) / zoom
 */
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

    var minZoom: Float = 0.5f
    var maxZoom: Float = 10.0f // 1000%

    var onPageChanged: ((page: Int, pageCount: Int) -> Unit)? = null
    var onSingleTap: ((MotionEvent) -> Boolean)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    /**
     * Fired when the RecyclerView transitions between scroll states. Receives the new
     * [RecyclerView.ScrollState] (IDLE, DRAGGING, SETTLING). Used by the host activity to
     * flush the persisted last-page write only once scrolling stops (instead of writing on
     * every page boundary during a fast fling).
     */
    var onScrollStateChanged: ((Int) -> Unit)? = null

    /**
     * Set true by the host while the scroll-pill thumb is being dragged. While true, the
     * hi-res tile renderer holds off (`scheduleHiRes` becomes a no-op and any pending run
     * is dropped) so the PdfEngine's single render worker is dedicated to visible-page base
     * renders the drag is issuing — fast dragging through a long PDF no longer freezes.
     */
    @Volatile
    var dragInProgress: Boolean = false

    // --- Hi-res tile hooks (wired by the activity) ---

    /** Renders (page, pageLocalLeft, pageLocalTop, zoom, childWidth, outW, outH) → bitmap. */
    var hiResRenderer: (suspend (Int, Float, Float, Float, Int, Int, Int) -> Bitmap?)? = null
    var hiResScope: CoroutineScope? = null

    /** Draws a page's ink/highlights; canvas is already in that page's base coordinate space. */
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
                // When scrolling settles after a drag/fling, re-arm the hi-res tile renderer
                // (it was suppressed while dragInProgress). At other times scheduleHiRes is
                // a no-op or already debounced.
                if (newState == SCROLL_STATE_IDLE) scheduleHiRes()
            }
        })
    }

    private var zoomAnimator: android.animation.ValueAnimator? = null

    /** Smoothly animates to [target] zoom, keeping the focal screen point fixed. */
    fun animateZoomTo(target: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        val startZoom = zoom
        val endZoom = target.coerceIn(minZoom, maxZoom)
        if (kotlin.math.abs(endZoom - startZoom) < 0.001f) return
        // Content point under the focal at the start — kept fixed throughout.
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
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = scheduleHiRes()
            })
            start()
        }
    }

    /** Sets zoom (animated) pivoting around the centre of the viewport. */
    fun setZoomLevel(target: Float) = animateZoomTo(target, width / 2f, height / 2f)

    /** Scales by [factor] keeping the focal screen point fixed. */
    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        val real = newZoom / zoom
        if (real == 1f) return
        // Keep the focal point fixed: trans' = focus - (focus - trans) * real
        transX = focusX - (focusX - transX) * real
        transY = focusY - (focusY - transY) * real
        zoom = newZoom
        clampTrans()
        invalidate()
        onZoomChanged?.invoke(zoom)
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
        // Clamp vertical pan to the currently laid-out pages.
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

    // -------------------------------------------------------------------------
    //  Hi-res tiles: crisp re-render of the visible region while zoomed in
    // -------------------------------------------------------------------------

    private class HiResTile(
        val page: Int,
        val bitmap: Bitmap,
        /** Region origin within the page, in base (zoom-1) pixels. */
        val localLeft: Float,
        val localTop: Float,
        /** Zoom the tile was rasterised at — drawn scaled by zoom/renderZoom until refreshed. */
        val renderZoom: Float
    )

    private var tiles: List<HiResTile> = emptyList()
    private var hiResJob: Job? = null
    private var hiResGen = 0
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hiResRunnable = Runnable { renderHiResTiles() }

    /** Debounced: called on every transform change; renders once the gesture settles. */
    private fun scheduleHiRes() {
        removeCallbacks(hiResRunnable)
        if (dragInProgress) {
            // During a scroll-pill drag the single PdfEngine render worker must serve the
            // visible-page base renders the drag is issuing — don't compete for it. Tiles
            // get re-armed once the drag ends (onScrollStateChanged -> IDLE, or ACTION_UP).
            return
        }
        if (zoom <= HI_RES_MIN_ZOOM) {
            if (tiles.isNotEmpty()) dropTiles()
            return
        }
        postDelayed(hiResRunnable, 160)
    }

    /** Public re-arm hook used by the host activity once a scroll-pill drag ends. */
    fun scheduleHiResPublic() = scheduleHiRes()

    fun clearHiResTiles() {
        removeCallbacks(hiResRunnable)
        hiResJob?.cancel()
        hiResGen++
        dropTiles()
    }

    /** Drop any in-flight hi-res work immediately — used when a drag starts so it can't run. */
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
        // Viewport in content (base-pixel) coordinates.
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
                // Any abnormal exit leaks the bitmaps rendered so far: cancellation at the
                // renderer's suspend point (the next pan/zoom, a drag start, or a tile clear
                // cancelled this job) or a renderer failure (OOM allocating the bitmap, a
                // bad page). Recycle them here so none are leaked, then let the exception
                // keep propagating. On normal completion the swap below takes over.
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
            // Anchor to the page's *current* layout position so tiles track any relayout.
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

            // The tile covers the page's ink overlay — re-draw that region's ink on top
            // (vector paths through the scaled canvas, so ink stays crisp and current).
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

    /** Average position of the active pointers; the one lifting on ACTION_POINTER_UP is excluded. */
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

        // Anchor panning on the pointers' focal point. Re-anchoring whenever a finger
        // joins or leaves prevents the false delta (and visible jump) that happens when
        // e.x suddenly refers to a different pointer.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastPanX = e.x; lastPanY = e.y }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    // Two fingers = zoom gesture — cancel the list scroll the first
                    // finger may have started, so the page doesn't creep while pinching.
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

                transX += dx
                val oldTY = transY
                transY += dy
                clampTrans()

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

        // At 1× the list scrolls (and recycles) normally.
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
