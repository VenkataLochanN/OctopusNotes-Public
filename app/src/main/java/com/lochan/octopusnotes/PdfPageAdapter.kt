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

class PdfPageAdapter(
    private var engine: PdfEngine,
    private val scope: CoroutineScope,
    private val strokeManager: StrokeManager
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    fun swapEngine(newEngine: PdfEngine) {
        engine = newEngine
    }

    var highlightsByPage: Map<Int, MutableList<RectF>>? = null
    var searchFillPaint: Paint? = null
    var activeFillPaint: Paint? = null
    var activeHighlightPage: Int = -1
    var activeHighlightRect: RectF? = null

    var zoom: Float = 1f

    var onFirstRender: (() -> Unit)? = null
    private var firstRenderDone = false

    private var strokePageSizes: List<Pair<Float, Float>>? = null

    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecyclerView === recyclerView) attachedRecyclerView = null
    }

    private val displayedBitmaps = java.util.IdentityHashMap<Bitmap, Int>()

    private val bitmapCache: LruCache<Int, Bitmap> = run {
        val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMem / 8
        object : LruCache<Int, Bitmap>(cacheSize) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                value.byteCount / 1024

            override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
                if (!evicted || oldValue.isRecycled) return

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

    private fun sizeItem(holder: PageViewHolder, position: Int, parentWidthOverride: Int = -1) {
        val parentWidth = if (parentWidthOverride > 0) parentWidthOverride else recyclerWidth(holder)
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

        val parentWidth = recyclerWidth(holder)
        holder.currentJob?.cancel()

        releaseDisplayedBitmap(holder)

        val cached = bitmapCache.get(position)
        if (cached != null && !cached.isRecycled) {
            displayedBitmaps[cached] = position
            holder.imageView.setImageBitmap(cached)
            holder.spinner.visibility = android.view.View.GONE
            prefetchNeighbor(position, parentWidth)
        } else {

            if (holder.imageView.drawable != null) holder.imageView.setImageBitmap(null)
            holder.spinner.visibility = android.view.View.VISIBLE
            holder.currentJob = scope.launch {

                val bitmap = engine.renderPage(position, parentWidth) ?: return@launch
                bitmapCache.put(position, bitmap)
                @Suppress("DEPRECATION")
                if (holder.adapterPosition == position) {
                    displayedBitmaps[bitmap] = position
                    holder.imageView.setImageBitmap(bitmap)
                    holder.spinner.visibility = android.view.View.GONE

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

    private fun fixStaleItemHeight(holder: PageViewHolder, position: Int) {
        val parentWidth = recyclerWidth(holder)
        val ps = engine.getPageSize(position)
        val expected = (parentWidth * (ps.height / ps.width)).toInt().coerceAtLeast(1)
        if (holder.container.layoutParams.height != expected) sizeItem(holder, position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.currentJob?.cancel()
        holder.currentJob = null
        releaseDisplayedBitmap(holder)
        if (holder.imageView.drawable != null) holder.imageView.setImageBitmap(null)
    }

    private fun releaseDisplayedBitmap(holder: PageViewHolder) {
        val bmp = (holder.imageView.drawable as? android.graphics.drawable.BitmapDrawable)
            ?.bitmap ?: return
        val key = displayedBitmaps.remove(bmp) ?: return

        if (!bmp.isRecycled && bitmapCache.get(key) !== bmp) {
            engine.releaseBitmap(bmp)
        }
    }

    private var prefetchJob: Job? = null

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

            sizeItem(holder, position)
            return
        }
        if (payloads.contains(PAYLOAD_INK)) {

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

    fun notifyInkChanged() {

        notifyItemRangeChanged(0, itemCount, PAYLOAD_INK)
    }

    fun getStrokePageSizes(recyclerWidth: Int): List<Pair<Float, Float>> {
        return (0 until engine.pageCount).map { i ->
            val ps = engine.getPageSize(i)
            val w = recyclerWidth.toFloat()
            val h = w * (ps.height / ps.width)
            Pair(w, h)
        }
    }

    fun clearCache() {
        displayedBitmaps.clear()
        bitmapCache.evictAll()
    }

    fun refreshForWidthChange(newWidth: Int) {

        attachedRecyclerView?.let { rv ->
            for (i in 0 until rv.childCount) {
                val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? PageViewHolder ?: continue
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) sizeItem(holder, pos, newWidth)
            }
        }

        bitmapCache.evictAll()
        notifyDataSetChanged()
    }

    companion object {
        const val PAYLOAD_INK = "ink"
    }
}