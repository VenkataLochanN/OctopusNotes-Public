package com.lochan.octopusnotes

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Single place that renders and writes the home-screen thumbnail (`thumb_<id>.png`) for a
 * notebook / imported PDF, honouring the user's Settings choice for which page to use.
 *
 * Used both when an item is first created (MainActivity) and whenever it's edited
 * (DrawingActivity on save), so behaviour stays consistent. Rendering is heavy — always call
 * [generate] off the main thread.
 */
object ThumbnailGenerator {
    const val WIDTH_PX = 300
    private const val PREFS = "OctopusNotesPrefs"

    fun thumbFile(context: Context, notebookId: Long): File =
        File(context.filesDir, "thumb_$notebookId.png")

    /** Page index to render, per Settings. [isImported] = an imported PDF (no saved template). */
    fun resolvePage(context: Context, pageCount: Int, isImported: Boolean, lastUsedPage: Int): Int {
        if (pageCount <= 0) return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val page = if (isImported) {
            when (prefs.getString("thumb_pdf", "FIRST")) {
                "LAST" -> pageCount - 1
                else -> 0
            }
        } else {
            when (prefs.getString("thumb_notebook", "LAST")) {
                "FIRST" -> 0
                "LAST_USED" -> lastUsedPage
                else -> pageCount - 1
            }
        }
        return page.coerceIn(0, pageCount - 1)
    }

    /**
     * Renders the chosen page (PDF background + any ink) and writes `thumb_<id>.png`.
     * Returns true on success. Safe to call repeatedly; failures are swallowed.
     */
    fun generate(
        context: Context,
        notebookId: Long,
        file: File,
        strokeManager: StrokeManager = StrokeManager(),
        strokePageSizes: List<Pair<Float, Float>> = emptyList(),
        isImported: Boolean,
        lastUsedPage: Int = 0
    ): Boolean {
        if (!file.exists()) return false
        val renderer = try {
            // Single page, single render — no need for the grid's parallel slot pool.
            PdfThumbnailRenderer(file, strokeManager, strokePageSizes, poolSize = 1)
        } catch (e: Exception) {
            return false
        }
        return try {
            val page = resolvePage(context, renderer.pageCount, isImported, lastUsedPage)
            val bmp = renderer.render(page, WIDTH_PX) ?: return false
            thumbFile(context, notebookId).outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 90, it)
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            renderer.close()
        }
    }
}
