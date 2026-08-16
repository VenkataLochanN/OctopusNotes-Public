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

class PdfEngine(file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = ReentrantLock()
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    private val sizeCache = ConcurrentHashMap<Int, SizeF>()

    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PdfEngine-render")
    }
    private val renderDispatcher = renderExecutor.asCoroutineDispatcher()

    private val bitmapPool = ArrayDeque<Bitmap>()

    @Volatile
    var renderPausedForDrag: Boolean = false

    fun getPageSize(pageIndex: Int): SizeF {
        sizeCache[pageIndex]?.let { return it }
        if (closed) return fallbackSize()
        if (Looper.getMainLooper().isCurrentThread) {
            return fallbackSize()
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

                page.close()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun fallbackSize(): SizeF =
        sizeCache.values.firstOrNull() ?: SizeF(595f, 842f)

    suspend fun prefetchSizes() {
        if (closed) return
        try {
            withContext(renderDispatcher) {
                for (i in 0 until pageCount) {
                    if (closed) return@withContext
                    if (renderPausedForDrag) return@withContext
                    getPageSize(i)
                    yield()
                }
            }
        } catch (_: RejectedExecutionException) {

        }
    }

    suspend fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? {
        if (closed) return null
        return try {
            withContext(renderDispatcher) { renderBlocking(pageIndex, targetWidth) }
        } catch (_: RejectedExecutionException) {
            null
        }
    }

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
                sizeCache.putIfAbsent(pageIndex, SizeF(ptW, ptH))
            } catch (e: Exception) {

                releaseBitmap(bitmap)
                throw e
            }
            bitmap
        } finally {

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

        }
    }

    companion object {
        private const val POOL_MAX = 4
    }
}
