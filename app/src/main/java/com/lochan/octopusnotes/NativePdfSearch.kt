package com.lochan.octopusnotes

import android.annotation.SuppressLint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.models.PageMatchBounds
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import java.io.File

class NativePdfSearch(private val file: File) {

    companion object {

        val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
    }

    @SuppressLint("NewApi")
    fun streamSearch(
        query: String,
        shouldStop: () -> Boolean = { false },
        onPage: (pageIndex: Int, totalPages: Int, matches: List<PdfTextIndex.Match>) -> Unit
    ) {
        if (query.isBlank() || !isSupported) return
        if (Build.VERSION.SDK_INT >= 35) searchV(query, shouldStop, onPage)
        else searchPreV(query, shouldStop, onPage)
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun searchV(
        query: String,
        shouldStop: () -> Boolean,
        onPage: (Int, Int, List<PdfTextIndex.Match>) -> Unit
    ) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                val n = renderer.pageCount
                for (i in 0 until n) {
                    if (shouldStop()) return
                    val page = renderer.openPage(i)
                    try {
                        val hits = page.searchText(query)
                        val matches = if (hits.isEmpty()) emptyList() else {
                            val text = pageText { page.textContents.joinToString("") { it.text } }
                            hits.mapNotNull {
                                toMatch(i, it, text, query, page.width.toFloat(), page.height.toFloat())
                            }
                        }
                        onPage(i, n, matches)
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun searchPreV(
        query: String,
        shouldStop: () -> Boolean,
        onPage: (Int, Int, List<PdfTextIndex.Match>) -> Unit
    ) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRendererPreV(pfd)
            try {
                val n = renderer.pageCount
                for (i in 0 until n) {
                    if (shouldStop()) return
                    val page = renderer.openPage(i)
                    try {
                        val hits = page.searchText(query)
                        val matches = if (hits.isEmpty()) emptyList() else {
                            val text = pageText { page.textContents.joinToString("") { it.text } }
                            hits.mapNotNull {
                                toMatch(i, it, text, query, page.width.toFloat(), page.height.toFloat())
                            }
                        }
                        onPage(i, n, matches)
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        }
    }

    private inline fun pageText(block: () -> String): String =
        try { block() } catch (e: Exception) { "" }

    private fun toMatch(
        pageIndex: Int,
        m: PageMatchBounds,
        pageText: String,
        query: String,
        pageW: Float,
        pageH: Float
    ): PdfTextIndex.Match? {
        if (pageW <= 0f || pageH <= 0f) return null
        var union: RectF? = null
        for (r in m.bounds) {
            if (union == null) union = RectF(r) else union.union(r)
        }
        val u = union ?: return null
        val norm = RectF(u.left / pageW, u.top / pageH, u.right / pageW, u.bottom / pageH)

        val start = m.textStartIndex
        val snippet = if (start in 0..(pageText.length - query.length)) {
            buildSnippet(pageText, start, start + query.length)
        } else {

            val idx = pageText.lowercase().indexOf(query.lowercase())
            if (idx >= 0) buildSnippet(pageText, idx, idx + query.length) else query
        }
        return PdfTextIndex.Match(pageIndex, norm, query, snippet)
    }

    private fun buildSnippet(text: String, start: Int, end: Int): String {
        val ws = Regex("\\s+")
        val beforeWords = text.substring(0, start).trim().split(ws).filter { it.isNotEmpty() }
        val afterWords = text.substring(end).trim().split(ws).filter { it.isNotEmpty() }
        val before = beforeWords.takeLast(3).joinToString(" ")
        val after = afterWords.take(3).joinToString(" ")
        return buildString {
            if (beforeWords.size > 3) append("… ")
            if (before.isNotEmpty()) append(before).append(" ")
            append(text.substring(start, end).replace(ws, " "))
            if (after.isNotEmpty()) append(" ").append(after)
            if (afterWords.size > 3) append(" …")
        }
    }
}
