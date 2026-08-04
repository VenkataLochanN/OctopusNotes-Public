package com.lochan.octopusnotes

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

/**
 * RecyclerView adapter that renders PDF pages via [PdfEngine] with an
 * [InkOverlayView] layered on top for strokes and search highlights.
 *
 * Each item is a [FrameLayout] containing:
 *   1. An [ImageView] showing the rendered PDF bitmap
 *   2. An [InkOverlayView] for ink strokes and search highlights
 *
 * Bitmaps are rendered on the engine's single render thread (cancellation-aware — recycled
 * binds never render) and cached in an [LruCache] sized to ~1/8 of the available heap.
 * Evicted bitmaps are returned to the engine's pool for reuse, and the page after the
 * visible one is prefetched so scrolling forward is usually a cache hit.
 */
class PdfPageAdapter(
    private var engine: PdfEngine,
    private val scope: CoroutineScope,
    private val strokeManager: StrokeManager
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    /**
     * Swaps in a fresh engine for the SAME document after an in-place edit that only
     * *appends* pages (auto-add on last page). Existing pages are unchanged, so the
     * bitmap cache stays valid — callers just notify the inserted position. This is
     * what keeps auto-append flash-free: no adapter rebuild, no re-render of visible pages.
     */
    fun swapEngine(newEngine: PdfEngine) {
        engine = newEngine
    }

    /** Shared search highlight data — set by the activity. */
    var highlightsByPage: Map<Int, MutableList<RectF>>? = null
    var searchFillPaint: Paint? = null
    var activeFillPaint: Paint? = null
    var activeHighlightPage: Int = -1
    var activeHighlightRect: RectF? = null

    /** Current zoom level — pushed from ZoomableRecyclerView. */
    var zoom: Float = 1f

    /** Fired once, the first time any page bitmap finishes rendering. */
    var onFirstRender: (() -> Unit)? = null
    private var firstRenderDone = false

    /**
     * The page dimensions in the coordinate-space that strokes were authored
     * in (i.e. the fitted page width/height at zoom = 1 on this device).
     * Populated lazily the first time the RecyclerView measures.
     */
    private var strokePageSizes: List<Pair<Float, Float>>? = null

    /**
     * Bitmaps currently set on a visible ImageView, keyed by page position. We must NOT pool
     * these while they're displayed — the engine would write a new page onto the same Bitmap
     * object and the old ImageView would silently show the wrong content.
     *
     * Keyed (rather than a plain set) so [releaseDisplayedBitmap] can decide eviction in O(1)
     * via `bitmapCache.get(key)` — the old code called `bitmapCache.snapshot()` (which copies
     * the WHOLE cache map) on every bind/recycle, a big main-thread allocator churn during
     * fast scrolling that contributed to the ANR.
     */
    private val displayedBitmaps = java.util.IdentityHashMap<Bitmap, Int>()

    private val bitmapCache: LruCache<Int, Bitmap> = run {
        val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMem / 8
        object : LruCache<Int, Bitmap>(cacheSize) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                value.byteCount / 1024

            override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
                if (!evicted || oldValue.isRecycled) return
                // Only return to the pool if no ViewHolder is currently showing this bitmap.
                // If it IS displayed, it will be released in onViewRecycled / next bind instead.
                if (!displayedBitmaps.containsKey(oldValue)) {
                    engine.releaseBitmap(oldValue)
                }
            }
        }
    }

    inner class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        val imageView: ImageView = container.getChildAt(0) as ImageView
        val inkOverlay: InkOverlayView = container.getChildAt(1) as InkOverlayView
        val spinner: android.widget.ProgressBar = container.getChildAt(2) as android.widget.ProgressBar
        var currentJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val ctx = parent.context

        val imageView = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_XY
            // Light skeleton tint while the page hasn't rendered yet.
            setBackgroundColor(0xFFEAEAEA.toInt())
        }

        val inkOverlay = InkOverlayView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val density = ctx.resources.displayMetrics.density
        val spinnerSize = (40 * density).toInt()
        val spinner = android.widget.ProgressBar(ctx).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                gravity = android.view.Gravity.CENTER
            }
        }

        val container = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            addView(imageView)
            addView(inkOverlay)
            addView(spinner)
        }

        return PageViewHolder(container)
    }

    private fun recyclerWidth(holder: PageViewHolder): Int =
        (holder.itemView.parent as? RecyclerView)?.width?.takeIf { it > 0 }
            ?: holder.itemView.context.resources.displayMetrics.widthPixels

    /**
     * Sizes the item at the base (zoom-1) width. Zooming is handled entirely by the
     * ZoomableRecyclerView's canvas transform, so items never resize (no relayout jumps,
     * no overlap) and the ink overlay always draws at scale 1 (= page space).
     */
    private fun sizeItem(holder: PageViewHolder, position: Int) {
        val parentWidth = recyclerWidth(holder)
        val pageSize = engine.getPageSize(position)
        val aspect = pageSize.height / pageSize.width
        val itemHeight = (parentWidth * aspect).toInt().coerceAtLeast(1)
        val gapPx = (16 * holder.itemView.context.resources.displayMetrics.density).toInt()

        val lp = holder.container.layoutParams as ViewGroup.MarginLayoutParams
        val heightChanged = lp.height != itemHeight || lp.bottomMargin != gapPx
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = itemHeight
        lp.leftMargin = 0
        lp.rightMargin = 0
        lp.bottomMargin = gapPx
        // Only force a layout pass when the item's size actually changed. The old code
        // called requestLayout() on EVERY bind — during a fast scroll dozens of binds per
        // second each triggered a measure/layout pass on the main thread, a major ANR cost.
        if (heightChanged) holder.container.requestLayout()

        holder.inkOverlay.apply {
            pageIndex = position
            strokeManager = this@PdfPageAdapter.strokeManager
            strokePageWidth = parentWidth.toFloat()
            strokePageHeight = parentWidth * aspect
            zoomLevel = 1f
            highlightsByPage = this@PdfPageAdapter.highlightsByPage
            searchFillPaint = this@PdfPageAdapter.searchFillPaint
            activeFillPaint = this@PdfPageAdapter.activeFillPaint
            activeHighlightPage = this@PdfPageAdapter.activeHighlightPage
            activeHighlightRect = this@PdfPageAdapter.activeHighlightRect
            invalidate()
        }
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        sizeItem(holder, position)

        // Bitmap is always rendered at the zoom-1 width and stretched (FIT_XY) when zoomed.
        val parentWidth = recyclerWidth(holder)
        holder.currentJob?.cancel() // recycled bind: its queued render never runs

        // Release the previous bitmap this holder was showing (it may have been evicted
        // from the cache but is still tracked in displayedBitmaps).
        releaseDisplayedBitmap(holder)

        val cached = bitmapCache.get(position)
        if (cached != null && !cached.isRecycled) {
            displayedBitmaps[cached] = position
            holder.imageView.setImageBitmap(cached)
            holder.spinner.visibility = android.view.View.GONE
            prefetchNeighbor(position, parentWidth)
        } else {
            // Only null out the image when something is actually showing. During a fast
            // scroll nearly every bind hits this path; the unconditional setImageBitmap(null)
            // created a null BitmapDrawable on every call (framework warning spam) and
            // pointless drawable churn on the main thread.
            if (holder.imageView.drawable != null) holder.imageView.setImageBitmap(null)
            holder.spinner.visibility = android.view.View.VISIBLE
            holder.currentJob = scope.launch {
                // Suspends onto the engine's render thread; resumes on Main.
                val bitmap = engine.renderPage(position, parentWidth) ?: return@launch
                bitmapCache.put(position, bitmap)
                @Suppress("DEPRECATION")
                if (holder.adapterPosition == position) {
                    displayedBitmaps[bitmap] = position
                    holder.imageView.setImageBitmap(bitmap)
                    holder.spinner.visibility = android.view.View.GONE
                    // The render just warmed the size cache. If this item was laid out
                    // with the engine's fallback aspect (main thread couldn't read the
                    // real size yet — typical right after opening), the stretched bitmap
                    // and the ink would misalign until the next rebind. Fix it now.
                    fixStaleItemHeight(holder, position)
                }
                if (!firstRenderDone) {
                    firstRenderDone = true
                    onFirstRender?.invoke()
                }
                prefetchNeighbor(position, parentWidth)
            }
        }
    }

    /** Re-sizes the item if it was measured with a wrong (fallback) page aspect. */
    private fun fixStaleItemHeight(holder: PageViewHolder, position: Int) {
        val parentWidth = recyclerWidth(holder)
        val ps = engine.getPageSize(position) // cache-warm after a render: exact, lock-free
        val expected = (parentWidth * (ps.height / ps.width)).toInt().coerceAtLeast(1)
        if (holder.container.layoutParams.height != expected) sizeItem(holder, position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.currentJob?.cancel()
        holder.currentJob = null
        releaseDisplayedBitmap(holder)
        if (holder.imageView.drawable != null) holder.imageView.setImageBitmap(null)
    }

    /**
     * Removes the bitmap currently shown by [holder] from [displayedBitmaps].
     * If the bitmap is no longer in the LruCache either, it is safe to return
     * it to the engine's bitmap pool for reuse.
     *
     * O(1): uses the tracked page key to probe the cache directly, instead of the old
     * `bitmapCache.snapshot()` which copied the entire cache map on every call.
     */
    private fun releaseDisplayedBitmap(holder: PageViewHolder) {
        val bmp = (holder.imageView.drawable as? android.graphics.drawable.BitmapDrawable)
            ?.bitmap ?: return
        val key = displayedBitmaps.remove(bmp) ?: return
        // If the cache no longer holds this bitmap for its page (it was evicted while
        // displayed), we can now safely release it to the pool.
        if (!bmp.isRecycled && bitmapCache.get(key) !== bmp) {
            engine.releaseBitmap(bmp)
        }
    }

    private var prefetchJob: Job? = null

    /**
     * Warms the cache with the page after [position] while the render thread is otherwise idle,
     * so scrolling forward is usually an instant cache hit. Superseded (cancelled) by the next
     * bind or prefetch, so it never delays visible-page renders by more than one page.
     *
     * Skipped while [PdfEngine.renderPausedForDrag] is set: during a fast scroll-pill drag the
     * single render thread must serve only the visible-page renders the drag is issuing, or it
     * falls behind and pages show spinner skeletons for the whole drag.
     */
    private fun prefetchNeighbor(position: Int, width: Int) {
        if (engine.renderPausedForDrag) return
        val next = position + 1
        if (next >= itemCount || bitmapCache.get(next) != null) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val bmp = engine.renderPage(next, width) ?: return@launch
            bitmapCache.put(next, bmp)
        }
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(ZoomableRecyclerView.PAYLOAD_ZOOM)) {
            // Zoom update: resize the item and refresh the overlay (no bitmap re-render).
            sizeItem(holder, position)
            return
        }
        if (payloads.contains(PAYLOAD_INK)) {
            // Ink-only update: just invalidate the overlay
            holder.inkOverlay.apply {
                highlightsByPage = this@PdfPageAdapter.highlightsByPage
                activeHighlightPage = this@PdfPageAdapter.activeHighlightPage
                activeHighlightRect = this@PdfPageAdapter.activeHighlightRect
                invalidate()
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount() = engine.pageCount

    /** Invalidate all visible ink overlays (strokes changed, search updated). */
    fun notifyInkChanged() {
        // Notify with a lightweight payload so we don't re-render bitmaps
        notifyItemRangeChanged(0, itemCount, PAYLOAD_INK)
    }

    /**
     * Returns the stroke coordinate space sizes for all pages.
     * Matches what the old PDFView.getPageSize() returned at zoom=1.
     */
    fun getStrokePageSizes(recyclerWidth: Int): List<Pair<Float, Float>> {
        return (0 until engine.pageCount).map { i ->
            val ps = engine.getPageSize(i)
            val w = recyclerWidth.toFloat()
            val h = w * (ps.height / ps.width)
            Pair(w, h)
        }
    }

    /** Clear the bitmap cache (e.g. when the PDF file changes). */
    fun clearCache() {
        displayedBitmaps.clear()
        bitmapCache.evictAll()
    }

    companion object {
        const val PAYLOAD_INK = "ink"
    }
}