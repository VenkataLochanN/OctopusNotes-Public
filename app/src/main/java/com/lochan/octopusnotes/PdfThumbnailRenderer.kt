package com.lochan.octopusnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renders page thumbnails (PDF background + ink overlay).
 *
 * **Design for efficiency** (mirrors [PdfEngine]):
 *  - A small **pool** of independent [PdfRenderer] instances (each with its own file descriptor)
 *    renders several thumbnails in parallel — a single renderer would serialize the whole grid
 *    behind one lock.
 *  - Renders run on a **dedicated executor with one worker per renderer slot**, not on
 *    [Dispatchers.IO]'s 64 threads. During a fast fling the old design piled dozens of blocked
 *    threads onto the per-slot locks (the exact ANR pattern PdfEngine's docs describe); now work
 *    queues on the executor and each render runs uncontended.
 *  - [renderSuspend] is cancellation-aware: a bind that gets recycled cancels its Job *before* the
 *    native render starts, so fast scrolling skips off-screen pages instead of rendering them all
 *    in order.
 *  - Rendered bitmaps come from a small same-size [bitmapPool] (refilled via [releaseBitmap] by
 *    the grid cache's evictions), so steady-state scrolling allocates almost nothing.
 *
 * [render] and [pageCount] are safe to call after [close]: they return null / 0 instead of touching
 * the closed document. That way a background render that outlives its dialog (e.g. a fast-scrolled
 * grid that was dismissed mid-render) can't crash with "Document already closed".
 *
 * [strokePageSizes] is the per-page coordinate space the strokes were authored in (PDFView's fitted
 * getPageSize), which differs from the PDF point size. Strokes must be scaled by it, not the point
 * size, or they overflow the page (only the top-left shows). Falls back to the point size when
 * unavailable.
 */
class PdfThumbnailRenderer(
    private val file: File,
    private val strokeManager: StrokeManager,
    private var strokePageSizes: List<Pair<Float, Float>> = emptyList(),
    poolSize: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
) {

    /** One independent [PdfRenderer] + FD, guarded by its own lock. */
    private class Slot(private val file: File) {
        var pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        var renderer: PdfRenderer = PdfRenderer(pfd)

        /** Reopens the file after it has been rewritten on disk (page reorder, insert, delete). */
        fun reopen() {
            close()
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
        }

        fun close() {
            try { renderer.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
        }
    }

    private val slots = List(poolSize.coerceAtLeast(1)) { Slot(file) }
    private val nextSlot = AtomicInteger()

    // Dedicated workers — one per renderer slot. Queued coroutines that get cancelled never run.
    private val renderExecutor = Executors.newFixedThreadPool(slots.size) { r ->
        Thread(r, "Thumb-render")
    }
    private val renderDispatcher = renderExecutor.asCoroutineDispatcher()

    // Reusable render targets — grid thumbnails share a width and usually an aspect ratio,
    // so steady-state scrolling reuses buffers instead of allocating a Bitmap per render.
    private val bitmapPool = ArrayDeque<Bitmap>()

    /** Set once close() runs; render()/pageCount stop touching the document after this. */
    @Volatile private var closed = false

    /**
     * Bumped by [reload]. A render that started before a reload finishes with a page index that no
     * longer maps to the content it drew (pages were reordered), so it discards its bitmap instead
     * of caching the wrong thumbnail.
     */
    @Volatile private var generation = 0

    val pageCount: Int
        get() {
            if (closed) return 0
            return synchronized(slots[0]) { slots[0].renderer.pageCount }
        }

    /**
     * Reopens the file after it has been rewritten on disk (page reorder, insert, delete).
     * Without this the old descriptors keep serving the pre-edit document.
     */
    fun reload(newStrokePageSizes: List<Pair<Float, Float>> = strokePageSizes) {
        synchronized(slots) {
            generation++
            closed = false
            for (s in slots) synchronized(s) { s.reopen() }
            strokePageSizes = newStrokePageSizes
        }
    }

    /**
     * Cancellation-aware render onto the dedicated thumbnail dispatcher. Returns null when the
     * renderer is closed, the page is out of range, or the render was cancelled while queued.
     */
    suspend fun renderSuspend(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        if (closed) return null
        return try {
            withContext(renderDispatcher) { renderBlocking(pageIndex, targetWidthPx) }
        } catch (_: RejectedExecutionException) {
            null // dispatcher shut down by close()
        }
    }

    /** Blocking render for single-shot callers (e.g. [ThumbnailGenerator]). */
    fun render(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        if (closed) return null
        return renderBlocking(pageIndex, targetWidthPx)
    }

    private fun renderBlocking(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        val gen = generation
        val slot = slots[Math.floorMod(nextSlot.getAndIncrement(), slots.size)]
        return synchronized(slot) {
            if (closed) return null
            val renderer = slot.renderer
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            val bmp = try {
                val ptW = page.width.toFloat()
                val ptH = page.height.toFloat()
                val w = targetWidthPx.coerceAtLeast(1)
                val h = (w * ptH / ptW).toInt().coerceAtLeast(1)
                val b = obtainBitmap(w, h)
                try {
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val strokeW = strokePageSizes.getOrNull(pageIndex)?.first ?: ptW
                    val strokeH = strokePageSizes.getOrNull(pageIndex)?.second ?: ptH
                    strokeManager.drawPageStrokes(pageIndex, Canvas(b), w / strokeW, h / strokeH)
                } catch (e: Exception) {
                    // Hand the pooled bitmap back so a failed render doesn't silently shrink
                    // the pool (it self-heals on the next render, but every failure would
                    // otherwise lose one reusable buffer).
                    releaseBitmap(b)
                    throw e
                }
                b
            } finally {
                try { page.close() } catch (_: Exception) {}
            }
            // A reload (page reorder) happened while we were rendering — this page index no
            // longer maps to the content just drawn. Drop it rather than cache a wrong thumb.
            if (gen != generation) { releaseBitmap(bmp); null } else bmp
        }
    }

    private fun obtainBitmap(w: Int, h: Int): Bitmap {
        synchronized(bitmapPool) {
            val it = bitmapPool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.width == w && b.height == h && !b.isRecycled) {
                    it.remove()
                    return b
                }
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /** Hand back a bitmap the UI no longer shows (cache eviction) for reuse by future renders. */
    fun releaseBitmap(bmp: Bitmap) {
        if (bmp.isRecycled) return
        synchronized(bitmapPool) {
            if (bitmapPool.size < POOL_MAX) {
                bitmapPool.addLast(bmp)
                return
            }
        }
        bmp.recycle()
    }

    fun close() {
        closed = true
        for (s in slots) synchronized(s) { s.close() }
        synchronized(bitmapPool) {
            bitmapPool.forEach { if (!it.isRecycled) it.recycle() }
            bitmapPool.clear()
        }
        renderExecutor.shutdown()
    }

    companion object {
        private const val POOL_MAX = 8
    }
}
