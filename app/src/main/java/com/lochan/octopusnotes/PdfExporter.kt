package com.lochan.octopusnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Renders notebook pages (PDF background + ink overlay) and exports them as a PDF,
 * a set of PNG images, or a ZIP of PNGs. All methods are blocking — call off the UI thread.
 * PdfRenderer is not thread-safe, so a single exporter instance must not be used concurrently.
 */
class PdfExporter(
    private val context: Context,
    private val pdfFile: File?,
    private val strokeManager: StrokeManager,
    // Per-page coordinate space the strokes were authored in (PDFView's fitted getPageSize).
    // Strokes must be scaled by this, not the PDF point size, or they overflow the page.
    private val strokePageSizes: List<Pair<Float, Float>> = emptyList()
) {
    // Background raster scale. Ink is drawn as vectors in the PDF path, so this only
    // governs the PDF background; PNG/ZIP export rasterizes everything and needs more.
    private val renderScale = 2f
    private val imageRenderScale = 4f
    // Guard against OOM on very large pages: cap the longest raster edge.
    private val maxRasterEdge = 4500
    private val exportDir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private class Rendered(val bmp: Bitmap, val ptW: Int, val ptH: Int)

    /** Largest scale <= [desired] that keeps both raster edges within [maxRasterEdge]. */
    private fun clampScale(desired: Float, ptW: Float, ptH: Float): Float {
        val longest = maxOf(ptW, ptH).coerceAtLeast(1f)
        return minOf(desired, maxRasterEdge / longest).coerceAtLeast(1f)
    }

    private fun openRenderer(): PdfRenderer? {
        val f = pdfFile ?: return null
        if (!f.exists()) return null
        return try {
            PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Renders one page (0-based) to a bitmap together with its point size.
     * [withStrokes] bakes the ink into the raster — leave it off when the caller can
     * draw the ink as vectors instead (PDF export), which keeps fine strokes sharp.
     */
    private fun renderPage(
        renderer: PdfRenderer?,
        pageIndex: Int,
        scale: Float,
        withStrokes: Boolean
    ): Rendered {
        val ptW: Float
        val ptH: Float
        val bmp: Bitmap
        if (renderer != null && pageIndex in 0 until renderer.pageCount) {
            val page = renderer.openPage(pageIndex)
            try {
                ptW = page.width.toFloat()
                ptH = page.height.toFloat()
                val s = clampScale(scale, ptW, ptH)
                val w = (ptW * s).toInt().coerceAtLeast(1)
                val h = (ptH * s).toInt().coerceAtLeast(1)
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            } finally {
                // Always release the page. If createBitmap/render throws (e.g. OOM on a
                // 4500px raster), a leaked page would make renderer.close() below throw
                // IllegalStateException ("Current page not closed") and mask the real error.
                try { page.close() } catch (_: Exception) {}
            }
        } else {
            ptW = 595f
            ptH = 842f
            val s = clampScale(scale, ptW, ptH)
            val w = (ptW * s).toInt().coerceAtLeast(1)
            val h = (ptH * s).toInt().coerceAtLeast(1)
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
        }
        if (withStrokes) {
            drawStrokes(pageIndex, Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), ptW, ptH)
        }
        return Rendered(bmp, ptW.toInt().coerceAtLeast(1), ptH.toInt().coerceAtLeast(1))
    }

    /** Draws the page's ink onto [canvas], mapping the authoring space onto [targetW] x [targetH]. */
    private fun drawStrokes(
        pageIndex: Int,
        canvas: Canvas,
        targetW: Float,
        targetH: Float,
        ptW: Float,
        ptH: Float
    ) {
        val strokeW = strokePageSizes.getOrNull(pageIndex)?.first ?: ptW
        val strokeH = strokePageSizes.getOrNull(pageIndex)?.second ?: ptH
        strokeManager.drawPageStrokes(pageIndex, canvas, targetW / strokeW, targetH / strokeH)
    }

    private fun clearExportDir() {
        exportDir.listFiles()?.forEach { it.delete() }
    }

    /** [pages] are 0-based indices. Returns the written PDF file. */
    fun exportPdf(
        pages: List<Int>,
        baseName: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false }
    ): File {
        clearExportDir()
        val out = File(exportDir, "$baseName.pdf")
        val doc = PdfDocument()
        val renderer = openRenderer()
        try {
            pages.forEachIndexed { i, pageIndex ->
                if (shouldCancel()) throw kotlinx.coroutines.CancellationException("export cancelled")
                val r = renderPage(renderer, pageIndex, renderScale, withStrokes = false)
                val info = PdfDocument.PageInfo.Builder(r.ptW, r.ptH, i + 1).create()
                val pdfPage = doc.startPage(info)
                pdfPage.canvas.drawBitmap(r.bmp, null, Rect(0, 0, r.ptW, r.ptH), bitmapPaint)
                // Ink goes on as vector paths, so it stays sharp at any viewer zoom
                // instead of inheriting the background raster's resolution.
                drawStrokes(
                    pageIndex, pdfPage.canvas,
                    r.ptW.toFloat(), r.ptH.toFloat(),
                    r.ptW.toFloat(), r.ptH.toFloat()
                )
                doc.finishPage(pdfPage)
                r.bmp.recycle()
                onProgress(i + 1, pages.size)
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
            renderer?.close()
        }
        return out
    }

    /** Returns one PNG file per page. */
    fun exportImages(
        pages: List<Int>,
        baseName: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false }
    ): List<File> {
        clearExportDir()
        val renderer = openRenderer()
        val files = mutableListOf<File>()
        try {
            pages.forEachIndexed { i, pageIndex ->
                if (shouldCancel()) throw kotlinx.coroutines.CancellationException("export cancelled")
                val r = renderPage(renderer, pageIndex, imageRenderScale, withStrokes = true)
                val f = File(exportDir, "${baseName}_p${pageIndex + 1}.png")
                FileOutputStream(f).use { r.bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                r.bmp.recycle()
                files.add(f)
                onProgress(i + 1, pages.size)
            }
        } finally {
            renderer?.close()
        }
        return files
    }

    /** Writes all pages as PNGs into a single ZIP and returns it. */
    fun exportZip(
        pages: List<Int>,
        baseName: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false }
    ): File {
        val images = exportImages(pages, baseName, onProgress, shouldCancel)
        val zip = File(exportDir, "$baseName.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            images.forEach { img ->
                if (shouldCancel()) throw kotlinx.coroutines.CancellationException("export cancelled")
                zos.putNextEntry(ZipEntry(img.name))
                img.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        images.forEach { it.delete() }
        return zip
    }
}
