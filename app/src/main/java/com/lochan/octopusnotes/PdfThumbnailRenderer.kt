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

class PdfThumbnailRenderer(
    private val file: File,
    private val strokeManager: StrokeManager,
    private var strokePageSizes: List<Pair<Float, Float>> = emptyList(),
    poolSize: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
) {

    private class Slot(private val file: File) {
        var pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        var renderer: PdfRenderer = PdfRenderer(pfd)

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

    @Volatile private var renderExecutor: java.util.concurrent.ExecutorService = newRenderExecutor()
    @Volatile private var renderDispatcher: kotlinx.coroutines.CoroutineDispatcher =
        renderExecutor.asCoroutineDispatcher()

    private fun newRenderExecutor(): java.util.concurrent.ExecutorService =
        Executors.newFixedThreadPool(slots.size) { r -> Thread(r, "Thumb-render") }

    private val bitmapPool = ArrayDeque<Bitmap>()

    @Volatile private var closed = false

    @Volatile private var generation = 0

    val pageCount: Int
        get() {
            if (closed) return 0
            return synchronized(slots[0]) { slots[0].renderer.pageCount }
        }

    fun reload(newStrokePageSizes: List<Pair<Float, Float>> = strokePageSizes) {
        synchronized(slots) {
            generation++
            closed = false

            if (renderExecutor.isShutdown) {
                renderExecutor = newRenderExecutor()
                renderDispatcher = renderExecutor.asCoroutineDispatcher()
            }
            for (s in slots) synchronized(s) { s.reopen() }
            strokePageSizes = newStrokePageSizes
        }
    }

    suspend fun renderSuspend(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        if (closed) return null
        return try {
            withContext(renderDispatcher) { renderBlocking(pageIndex, targetWidthPx) }
        } catch (_: RejectedExecutionException) {
            null
        }
    }

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
                    strokeManager.drawPageStrokes(pageIndex, Canvas(b), w / strokeW, h / strokeH, ghostSelected = false)
                } catch (e: Exception) {

                    releaseBitmap(b)
                    throw e
                }
                b
            } finally {
                try { page.close() } catch (_: Exception) {}
            }

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

        synchronized(slots) {
            for (s in slots) synchronized(s) { s.close() }
            renderExecutor.shutdown()
        }
        synchronized(bitmapPool) {
            bitmapPool.forEach { if (!it.isRecycled) it.recycle() }
            bitmapPool.clear()
        }
    }

    companion object {
        private const val POOL_MAX = 8
    }
}
