package com.lochan.octopusnotes

import android.graphics.RectF
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Extracts text and per-glyph bounding boxes from the embedded text layer of a PDF.
 * Works for digital (non-scanned) PDFs. No OCR.
 *
 * **Efficiency** (pdfbox extraction is slow on large PDFs, so we make sure it happens rarely
 * and fast):
 *  - The extracted layer is persisted to a compact binary cache (`<pdf>.idx`, keyed by the
 *    file's length+mtime). Indexing happens **once per document version** — every later search,
 *    even after an app restart, loads the cache in milliseconds instead of re-parsing.
 *  - First-time extraction runs on 2–3 worker threads (each with its own [PDDocument]) over
 *    disjoint page ranges, while results are still emitted **in page order** so streamed search
 *    results appear progressively.
 *  - Glyph data lives in primitive arrays (one `IntArray` + `FloatArray` per line) instead of
 *    per-glyph objects — several times less memory on dense documents.
 */
class PdfTextIndex(private val file: File) {

    data class Match(val pageIndex: Int, val rect: RectF, val text: String, val snippet: String = "")

    /**
     * One visual text line. Glyph i spans text [glyphStarts[i], glyphEnd(i)) with its
     * normalized [0,1] box at rects[4i..4i+3] (left, top, right, bottom).
     */
    private class Line(val text: String, val glyphStarts: IntArray, val rects: FloatArray) {
        val glyphCount: Int get() = glyphStarts.size
        fun glyphEnd(i: Int): Int =
            if (i + 1 < glyphStarts.size) glyphStarts[i + 1] else text.length
    }

    @Volatile
    private var pages: List<List<Line>>? = null

    val isReady: Boolean get() = pages != null

    private val cacheFile: File get() = File(file.parentFile, file.name + ".idx")

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Makes the text layer available: from memory, else the disk cache, else a full (parallel)
     * extraction that is then cached. Call off the UI thread. Idempotent.
     */
    fun ensureTextLayer(onPageIndexed: ((Int, Int) -> Unit)? = null) {
        if (pages != null) return
        loadCache()?.let { pages = it; return }
        val extracted = extractParallel(shouldStop = { false }) { i, n, _ ->
            onPageIndexed?.invoke(i + 1, n)
        } ?: return
        pages = extracted
        saveCache(extracted)
    }

    /** Returns normalized [0,1] rects for each line of text on a given page. */
    fun textBoxesForPage(pageIndex: Int): List<RectF> {
        val pgs = pages ?: return emptyList()
        if (pageIndex !in pgs.indices) return emptyList()
        return pgs[pageIndex].mapNotNull { line -> unionGlyphRects(line, 0, line.text.length) }
    }

    /** Finds all occurrences of the query, returning a box for each match. */
    fun search(query: String): List<Match> {
        val pgs = pages ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        val matches = ArrayList<Match>()
        for (i in pgs.indices) matches.addAll(matchesInLines(pgs[i], i, query, q))
        return matches
    }

    /**
     * Searches page-by-page, invoking [onPage] after each page so results can be shown
     * instantly instead of waiting for the whole document.
     *
     * Fast paths: in-memory layer, then the disk cache (milliseconds). Only a never-indexed
     * document pays for extraction — parallel across pages, streamed in order, then cached
     * so it never happens again for this file version. [shouldStop] is polled between pages.
     */
    fun streamSearch(
        query: String,
        shouldStop: () -> Boolean = { false },
        onPage: (pageIndex: Int, totalPages: Int, matches: List<Match>) -> Unit
    ) {
        if (query.isBlank()) return
        val q = query.lowercase()

        var pgs = pages
        if (pgs == null) pgs = loadCache()?.also { pages = it }
        if (pgs != null) {
            for (i in pgs.indices) {
                if (shouldStop()) return
                onPage(i, pgs.size, matchesInLines(pgs[i], i, query, q))
            }
            return
        }

        val extracted = extractParallel(shouldStop) { i, n, lines ->
            onPage(i, n, matchesInLines(lines, i, query, q))
        } ?: return // stopped mid-extraction: don't cache a partial layer
        pages = extracted
        saveCache(extracted)
    }

    // -------------------------------------------------------------------------
    //  Matching
    // -------------------------------------------------------------------------

    private fun matchesInLines(
        lines: List<Line>,
        pageIndex: Int,
        query: String,
        lowerQuery: String
    ): List<Match> {
        val out = ArrayList<Match>()
        for (line in lines) {
            val lower = line.text.lowercase()
            var from = 0
            while (true) {
                val idx = lower.indexOf(lowerQuery, from)
                if (idx < 0) break
                val end = idx + lowerQuery.length
                val rect = unionGlyphRects(line, idx, end)
                if (rect != null) out.add(Match(pageIndex, rect, query, buildSnippet(line.text, idx, end)))
                from = end
            }
        }
        return out
    }

    /** Builds a "…3 words before MATCH 3 words after…" preview around a match. */
    private fun buildSnippet(text: String, start: Int, end: Int): String {
        val ws = Regex("\\s+")
        val beforeWords = text.substring(0, start).trim().split(ws).filter { it.isNotEmpty() }
        val afterWords = text.substring(end).trim().split(ws).filter { it.isNotEmpty() }
        val before = beforeWords.takeLast(3).joinToString(" ")
        val after = afterWords.take(3).joinToString(" ")
        return buildString {
            if (beforeWords.size > 3) append("… ")
            if (before.isNotEmpty()) append(before).append(" ")
            append(text.substring(start, end))
            if (after.isNotEmpty()) append(" ").append(after)
            if (afterWords.size > 3) append(" …")
        }
    }

    private fun unionGlyphRects(line: Line, start: Int, end: Int): RectF? {
        var out: RectF? = null
        val r = line.rects
        for (i in 0 until line.glyphCount) {
            if (line.glyphEnd(i) <= start || line.glyphStarts[i] >= end) continue
            val b = i * 4
            if (out == null) out = RectF(r[b], r[b + 1], r[b + 2], r[b + 3])
            else {
                if (r[b] < out.left) out.left = r[b]
                if (r[b + 1] < out.top) out.top = r[b + 1]
                if (r[b + 2] > out.right) out.right = r[b + 2]
                if (r[b + 3] > out.bottom) out.bottom = r[b + 3]
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    //  Extraction (parallel, ordered emission)
    // -------------------------------------------------------------------------

    private fun memSetting(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(file.parentFile)

    /**
     * Extracts every page's lines. Splits pages across worker threads (own [PDDocument] each);
     * [onPageReady] fires **in page order** as pages complete. Returns null if [shouldStop].
     */
    private fun extractParallel(
        shouldStop: () -> Boolean,
        onPageReady: ((pageIndex: Int, total: Int, lines: List<Line>) -> Unit)?
    ): List<List<Line>>? {
        val firstDoc = try {
            PDDocument.load(file, memSetting())
        } catch (e: Exception) {
            return null
        }
        val n = firstDoc.numberOfPages
        if (n == 0) {
            firstDoc.close()
            return emptyList()
        }

        val workers = if (n < 6) 1
        else minOf(3, (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1))

        val out = arrayOfNulls<List<Line>>(n)
        val done = AtomicIntegerArray(n)
        val aborted = AtomicBoolean(false)

        // Contiguous chunks: worker 0 owns the first pages, so ordered emission starts instantly.
        val chunk = (n + workers - 1) / workers
        val exec = Executors.newFixedThreadPool(workers)
        for (w in 0 until workers) {
            val from = w * chunk
            val to = minOf(n, from + chunk)
            if (from >= to) break
            val docForWorker = if (w == 0) firstDoc else null
            exec.execute {
                var doc: PDDocument? = docForWorker
                try {
                    if (doc == null) doc = PDDocument.load(file, memSetting())
                    val stripper = LineStripper()
                    for (p in from until to) {
                        if (aborted.get()) return@execute
                        out[p] = try { stripper.extractPage(doc, p) } catch (e: Exception) { emptyList() }
                        done.set(p, 1)
                    }
                } catch (e: Exception) {
                    // Document failed for this worker — unblock the emitter with empty pages.
                    for (p in from until to) {
                        if (done.get(p) == 0) { out[p] = emptyList(); done.set(p, 1) }
                    }
                } finally {
                    try { doc?.close() } catch (_: Exception) {}
                }
            }
        }
        exec.shutdown()

        // Emit pages strictly in order as they finish.
        for (i in 0 until n) {
            while (done.get(i) == 0) {
                if (shouldStop()) {
                    aborted.set(true)
                    exec.shutdownNow()
                    return null
                }
                try { Thread.sleep(8) } catch (_: InterruptedException) { return null }
            }
            onPageReady?.invoke(i, n, out[i] ?: emptyList())
        }
        try { exec.awaitTermination(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        return out.map { it ?: emptyList() }
    }

    private class LineStripper : PDFTextStripper() {
        private val lines = ArrayList<Line>()
        private var pageW = 1f
        private var pageH = 1f

        init {
            sortByPosition = true
        }

        fun extractPage(doc: PDDocument, pageIdx: Int): List<Line> {
            lines.clear()
            val box = doc.getPage(pageIdx).cropBox
            pageW = box.width
            pageH = box.height
            startPage = pageIdx + 1
            endPage = pageIdx + 1
            getText(doc) // lines captured via writeString
            val result = ArrayList(lines)
            lines.clear()
            return result
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isEmpty() || pageW <= 0f || pageH <= 0f) return
            val sb = StringBuilder()
            val starts = IntArray(textPositions.size)
            val rects = FloatArray(textPositions.size * 4)
            var g = 0
            for (tp in textPositions) {
                val uni = tp.unicode ?: continue
                if (uni.isEmpty()) continue
                starts[g] = sb.length
                sb.append(uni)
                val left = tp.xDirAdj
                val top = tp.yDirAdj - tp.heightDir
                val b = g * 4
                rects[b] = left / pageW
                rects[b + 1] = top / pageH
                rects[b + 2] = (left + tp.widthDirAdj) / pageW
                rects[b + 3] = tp.yDirAdj / pageH
                g++
            }
            if (g == 0) return
            val lineText = sb.toString()
            if (lineText.isBlank()) return
            lines.add(Line(lineText, starts.copyOf(g), rects.copyOf(g * 4)))
        }
    }

    // -------------------------------------------------------------------------
    //  Disk cache: <pdf>.idx — binary, keyed by source length+mtime
    // -------------------------------------------------------------------------

    private fun loadCache(): List<List<Line>>? {
        val f = cacheFile
        if (!f.exists()) return null
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { ins ->
                if (ins.readInt() != CACHE_MAGIC) return null
                if (ins.readInt() != CACHE_VERSION) return null
                if (ins.readLong() != file.length()) return null
                if (ins.readLong() != file.lastModified()) return null
                val n = ins.readInt()
                if (n < 0 || n > 200_000) return null
                val pgs = ArrayList<List<Line>>(n)
                repeat(n) {
                    val lineCount = ins.readInt()
                    val lines = ArrayList<Line>(lineCount)
                    repeat(lineCount) {
                        val text = ins.readUTF()
                        val g = ins.readInt()
                        val starts = IntArray(g)
                        val rects = FloatArray(g * 4)
                        // Bulk-read the primitive arrays (much faster than element-wise).
                        val bytes = ByteArray(g * 4 + g * 16)
                        ins.readFully(bytes)
                        val bb = ByteBuffer.wrap(bytes)
                        bb.asIntBuffer().get(starts)
                        bb.position(g * 4)
                        bb.asFloatBuffer().get(rects)
                        lines.add(Line(text, starts, rects))
                    }
                    pgs.add(lines)
                }
                return pgs
            }
        } catch (e: Exception) {
            f.delete() // corrupt / stale-format cache
            return null
        }
    }

    private fun saveCache(pgs: List<List<Line>>) {
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { outs ->
                outs.writeInt(CACHE_MAGIC)
                outs.writeInt(CACHE_VERSION)
                outs.writeLong(file.length())
                outs.writeLong(file.lastModified())
                outs.writeInt(pgs.size)
                for (page in pgs) {
                    outs.writeInt(page.size)
                    for (line in page) {
                        outs.writeUTF(line.text)
                        val g = line.glyphCount
                        outs.writeInt(g)
                        val bytes = ByteArray(g * 4 + g * 16)
                        val bb = ByteBuffer.wrap(bytes)
                        bb.asIntBuffer().put(line.glyphStarts)
                        bb.position(g * 4)
                        bb.asFloatBuffer().put(line.rects)
                        outs.write(bytes)
                    }
                }
            }
            if (cacheFile.exists()) cacheFile.delete()
            tmp.renameTo(cacheFile)
        } catch (e: Exception) {
            tmp.delete() // caching is best-effort; search still works from memory
        }
    }

    companion object {
        private const val CACHE_MAGIC = 0x50494458 // "PIDX"
        private const val CACHE_VERSION = 1
    }
}
