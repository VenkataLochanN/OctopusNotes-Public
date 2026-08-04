package com.lochan.octopusnotes

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PageGridAdapter(
    private val renderer: PdfThumbnailRenderer,
    private val thumbWidthPx: Int,
    private var currentPage: Int,
    private val scope: CoroutineScope,
    /** Actual page indices to display (e.g. all pages, or only bookmarked ones). */
    private var pages: List<Int>,
    private val isBookmarked: (Int) -> Boolean,
    private val onClick: (Int) -> Unit,
    private val onMenu: (Int, View) -> Unit,
    private val onBookmarkToggle: (Int) -> Unit,
    private val isSelectionMode: () -> Boolean,
    private val isSelected: (Int) -> Boolean,
    private val onSelectToggle: (Int) -> Unit,
    /** Long press on a thumbnail; the host decides between selecting and starting a drag. */
    private val onLongPress: (Int, VH) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<PageGridAdapter.VH>() {

    /**
     * Bitmaps currently set on a visible ImageView, keyed by page position. We must NOT return
     * these to the renderer's pool while they're displayed — the next render would draw onto the
     * same Bitmap object and the ImageView would silently show the wrong thumbnail.
     */
    private val displayedBitmaps = java.util.IdentityHashMap<Bitmap, Int>()

    private val cache: LruCache<Int, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        object : LruCache<Int, Bitmap>(maxKb / 8) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024

            override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
                if (!evicted || oldValue.isRecycled) return
                // Only return to the pool if no ViewHolder is currently showing this bitmap.
                // If it IS displayed, it is released in onViewRecycled / the next bind instead.
                if (!displayedBitmaps.containsKey(oldValue)) {
                    renderer.releaseBitmap(oldValue)
                }
            }
        }
    }

    /** Pages whose thumbnail render is currently running, so a fast scroll doesn't queue a
     *  second render for the same page (they'd only pile up behind the same renderer pool). */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun setPages(newPages: List<Int>) {
        pages = newPages
        notifyDataSetChanged()
    }

    /** Page numbers change on a reorder, so cached thumbnails no longer match their keys. */
    fun refreshAfterReorder(newPages: List<Int>, newCurrentPage: Int) {
        cache.evictAll()
        currentPage = newCurrentPage
        setPages(newPages)
    }

    /**
     * Reorders in place for drag feedback only — nothing is written to disk until the
     * drag is dropped and the host commits the move.
     */
    fun moveItem(fromPos: Int, toPos: Int) {
        val list = pages.toMutableList()
        if (fromPos !in list.indices || toPos !in list.indices) return
        list.add(toPos, list.removeAt(fromPos))
        pages = list
        notifyItemMoved(fromPos, toPos)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: View = itemView.findViewById(R.id.thumbCard)
        val image: ImageView = itemView.findViewById(R.id.thumbImage)
        val label: TextView = itemView.findViewById(R.id.thumbLabel)
        val border: View = itemView.findViewById(R.id.thumbBorder)
        val bookmark: ImageView = itemView.findViewById(R.id.thumbBookmark)
        val menu: ImageButton = itemView.findViewById(R.id.thumbMenu)
        val selectOverlay: View = itemView.findViewById(R.id.thumbSelectOverlay)
        val selectCheck: ImageView = itemView.findViewById(R.id.thumbSelectCheck)
        var currentJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_page_thumb, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = pages.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val page = pages[position]
        holder.label.text = "${page + 1}"
        holder.border.visibility = if (page == currentPage) View.VISIBLE else View.GONE

        val bookmarked = isBookmarked(page)
        holder.bookmark.setImageResource(
            if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border
        )
        holder.bookmark.setColorFilter(
            if (bookmarked) 0xFF2196F3.toInt() else 0x99FFFFFF.toInt()
        )
        holder.bookmark.setOnClickListener { onBookmarkToggle(page) }
        holder.menu.setOnClickListener { onMenu(page, it) }

        val selMode = isSelectionMode()
        val selected = selMode && isSelected(page)
        holder.selectOverlay.visibility = if (selected) View.VISIBLE else View.GONE
        holder.selectCheck.visibility = if (selected) View.VISIBLE else View.GONE
        holder.menu.visibility = if (selMode) View.GONE else View.VISIBLE

        holder.card.setOnClickListener {
            if (isSelectionMode()) onSelectToggle(page) else onClick(page)
        }
        holder.card.setOnLongClickListener {
            onLongPress(page, holder)
            true
        }

        // Recycled bind: its queued render never runs (renders are cancellable), so a fast
        // fling skips off-screen pages instead of rendering them all in order.
        holder.currentJob?.cancel()
        holder.currentJob = null
        releaseDisplayedBitmap(holder)

        val cached = cache.get(page)
        if (cached != null && !cached.isRecycled) {
            displayedBitmaps[cached] = page
            holder.image.setImageBitmap(cached)
            return
        }
        if (holder.image.drawable != null) holder.image.setImageBitmap(null)
        holder.image.tag = page
        // One render per page: rebinding the same page during a fling shouldn't start another.
        if (!inFlight.add(page)) return
        holder.currentJob = scope.launch {
            var rendered = false
            try {
                // Holder recycled/rebound before this coroutine ran — nobody is looking at this
                // page, so skip the native render instead of queueing work that gets thrown away.
                if (holder.image.tag != page) return@launch
                // Suspends onto the renderer's own worker(s); cancelling this job before the
                // render starts skips the native work entirely.
                val bmp = try {
                    renderer.renderSuspend(page, thumbWidthPx)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    null
                } ?: return@launch
                rendered = true
                cache.put(page, bmp)
                if (holder.image.tag == page) {
                    displayedBitmaps[bmp] = page
                    holder.image.setImageBitmap(bmp)
                } else {
                    // The original holder was recycled and a different holder now shows this
                    // page (its own bind early-returned because this render was in flight).
                    // Rebinding it from cache prevents a blank thumbnail until re-scroll.
                    val pos = pages.indexOf(page)
                    if (pos >= 0) notifyItemChanged(pos)
                }
            } finally {
                inFlight.remove(page)
                // No bitmap was produced (render cancelled mid-flight, failed, or the holder
                // was recycled before it started). A visible holder for this page that skipped
                // its own render because this one was in flight would otherwise stay a skeleton
                // forever — rebinding it starts a fresh render now that inFlight is free.
                if (!rendered) {
                    val pos = pages.indexOf(page)
                    if (pos >= 0) notifyItemChanged(pos)
                }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.currentJob?.cancel()
        holder.currentJob = null
        releaseDisplayedBitmap(holder)
        if (holder.image.drawable != null) holder.image.setImageBitmap(null)
    }

    /**
     * Removes the bitmap currently shown by [holder] from [displayedBitmaps]. If the bitmap is no
     * longer in the LruCache either, it is returned to the renderer's pool for reuse.
     */
    private fun releaseDisplayedBitmap(holder: VH) {
        val bmp = (holder.image.drawable as? android.graphics.drawable.BitmapDrawable)
            ?.bitmap ?: return
        val key = displayedBitmaps.remove(bmp) ?: return
        if (!bmp.isRecycled && cache.get(key) !== bmp) {
            renderer.releaseBitmap(bmp)
        }
    }
}
