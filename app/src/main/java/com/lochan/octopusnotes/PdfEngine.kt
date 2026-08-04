package com.lochan.octopusnotes

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.SizeF
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Native PDF rendering engine backed by [PdfRenderer].
 *
 * **Design for efficiency**:
 *  - All page opens run on ONE dedicated render thread ([renderDispatcher]). PdfRenderer only
 *    allows a single open page anyway, so instead of many IO threads piling up on a contended
 *    monitor (the old design — dozens of blocked threads during a fast fling), work *queues* on
 *    the executor and each render runs uncontended.
 *  - [renderPage] is a suspend fun: a bind that gets recycled cancels its Job *before* the render
 *    starts, so fast scrolling skips off-screen pages instead of rendering them all in order.
 *  - Rendered bitmaps come from a small same-size [bitmapPool] (refilled by the adapter's cache
 *    evictions), so steady-state scrolling allocates almost nothing.
 *  - [getPageSize] is lock-free once cached; on the main thread it *never blocks* — if the render
 *    thread holds the lock it returns the typical page size and lets the layout self-correct.
 */
class PdfEngine(file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = ReentrantLock()
    @Volatile private var closed = false

    /** Immutable for the document — read once, then lock-free. */
    val pageCount: Int = renderer.pageCount

    // Page sizes are immutable; a concurrent cache lets any thread read them without the lock.
    private val sizeCache = ConcurrentHashMap<Int, SizeF>()

    // Single worker for every renderer touch. Queued coroutines that get cancelled never run.
    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PdfEngine-render")
    }
    private val renderDispatcher = renderExecutor.asCoroutineDispatcher()

    // Reusable render targets — pages in a document are almost always the same pixel size.
    private val bitmapPool = ArrayDeque<Bitmap>()

    /**
     * Set to true by the UI during a fast scroll-pill drag. The render thread checks this
     * flag between renders: when true, the size-prefetch sweep bails immediately so the
     * single render worker is dedicated to the visible-page renders the drag is issuing,
     * rather than being tied up opening the next pre-warm page.
     */
    @Volatile
    var renderPausedForDrag: Boolean = false

    /**
     * Returns page dimensions in PDF points. Lock-free once cached.
     *
     * **On the main thread this NEVER touches the native renderer.** [PdfRenderer.openPage] is
     * expensive native PDF parsing, and during a fast scroll every newly-bound page asks for its
     * size; running that on the UI thread — even when the lock happens to be free — is exactly
     * what pegged the main thread for 5s+ and caused the scroll ANR. Instead it returns the
     * document's typical page size (right for uniform-page PDFs) and lets the render thread fill
     * the real value into the cache; the adapter re-sizes the item after its first render.
     */
    fun getPageSize(pageIndex: Int): SizeF {
        sizeCache[pageIndex]?.let { return it } // fast path: no lock
        if (closed) return fallbackSize()
        if (Looper.getMainLooper().isCurrentThread) {
            return fallbackSize() // never run native PDF work on the UI thread
        }
        lock.lock()
        try {
            sizeCache[pageIndex]?.let { return it }
            if (closed) return fallbackSize()
            val page = renderer.openPage(pageIndex)
            try {
                val size = SizeF(page.width.toFloat(), page.height.toFloat())
                sizeCache[pageIndex] = size
                return size
            } finally {
                // Always release the page — a leaked page would wedge PdfRenderer's
                // one-open-page-at-a-time invariant and poison every later openPage().
                page.close()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun fallbackSize(): SizeF =
        sizeCache.values.firstOrNull() ?: SizeF(595f, 842f) // A4 points

    /**
     * Pre-loads every page's size on the render thread so later reads are cache hits.
     * Yields between pages, so queued page renders interleave instead of waiting for the sweep.
     *
     * Bails immediately when [renderPausedForDrag] is set: a fast scroll-pill drag needs the
     * single render worker for visible-page renders, so the prefetch sweep gets out of the
     * way and resumes (via a fresh launch from the activity) once the drag ends.
     */
    suspend fun prefetchSizes() {
        if (closed) return
        try {
            withContext(renderDispatcher) {
                for (i in 0 until pageCount) {
                    if (closed) return@withContext
                    if (renderPausedForDrag) return@withContext
                    getPageSize(i)
                    yield() // let any pending renderPage jump the queue
                }
            }
        } catch (_: RejectedExecutionException) {
            // Engine closed while we were queued — nothing to do.
        }
    }

    /**
     * Renders the given page at [targetWidth] px wide (height from the page's aspect ratio).
     * Suspends onto the render thread; returns null if the engine closed underneath us.
     * Cancelling the calling Job before the render starts skips the work entirely.
     */
    suspend fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? {
        if (closed) return null
        return try {
            withContext(renderDispatcher) { renderBlocking(pageIndex, targetWidth) }
        } catch (_: RejectedExecutionException) {
            null // dispatcher shut down by close()
        }
    }

    /**
     * Renders a **sub-region** of a page at full screen resolution — the sharp-zoom path.
     *
     * The region starts at ([pageLocalLeft], [pageLocalTop]) in *base* (zoom-1) pixels of an item
     * laid out [childWidth] px wide, spans [outW]x[outH] *screen* pixels at [zoom]. Instead of
     * stretching the base bitmap, PdfRenderer re-rasterises just that region via a transform
     * matrix, so text/images stay crisp at any zoom. Returns null if the engine closed.
     */
    suspend fun renderRegion(
        pageIndex: Int,
        pageLocalLeft: Float,
        pageLocalTop: Float,
        zoom: Float,
        childWidth: Int,
        outW: Int,
        outH: Int
    ): Bitmap? {
        if (closed || outW <= 0 || outH <= 0) return null
        return try {
            withContext(renderDispatcher) {
                lock.withLock {
                    if (closed) return@withLock null
                    val page = renderer.openPage(pageIndex)
                    try {
                        // Screen pixels per PDF point at this zoom level.
                        val s = zoom * childWidth / page.width.toFloat()
                        val m = android.graphics.Matrix().apply {
                            setScale(s, s)
                            postTranslate(-pageLocalLeft * zoom, -pageLocalTop * zoom)
                        }
                        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    } finally {
                        // Always release the page — a leaked page (e.g. on OOM from
                        // createBitmap) would wedge the renderer permanently.
                        page.close()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun renderBlocking(pageIndex: Int, targetWidth: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        val page = renderer.openPage(pageIndex)
        try {
            val ptW = page.width.toFloat()
            val ptH = page.height.toFloat()
            val ratio = ptH / ptW
            val w = targetWidth.coerceAtLeast(1)
            val h = (w * ratio).toInt().coerceAtLeast(1)

            val bitmap = obtainBitmap(w, h)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                sizeCache.putIfAbsent(pageIndex, SizeF(ptW, ptH)) // free size-cache warm-up
            } catch (e: Exception) {
                // Hand the pooled bitmap back so a failed render doesn't silently shrink the
                // pool (it self-heals on the next render, but every failure would otherwise
                // lose one reusable buffer).
                releaseBitmap(bitmap)
                throw e
            }
            bitmap
        } finally {
            // Always release the page. If render/createBitmap throws, a leaked page would
            // wedge PdfRenderer permanently (it only allows one open page at a time) — every
            // later openPage() would throw IllegalStateException.
            page.close()
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

    /**
     * Closes the engine **without blocking the caller**. [closed] flips immediately so queued and
     * in-flight work bails; the native renderer is freed on the render thread once it's idle, and
     * the executor then shuts down (rejecting any stragglers, which [renderPage] turns into null).
     */
    fun close() {
        if (closed) return
        closed = true
        try {
            renderExecutor.execute {
                lock.withLock {
                    try { renderer.close() } catch (_: Exception) {}
                    try { descriptor.close() } catch (_: Exception) {}
                }
                synchronized(bitmapPool) {
                    bitmapPool.forEach { it.recycle() }
                    bitmapPool.clear()
                }
            }
            renderExecutor.shutdown()
        } catch (_: RejectedExecutionException) {
            // Already shut down.
        }
    }

    companion object {
        private const val POOL_MAX = 4
    }
}
