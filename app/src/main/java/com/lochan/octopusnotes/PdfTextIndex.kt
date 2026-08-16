package com.lochan.octopusnotes

import android.graphics.RectF
import com.lochan.octopusnotes.pdfedit.PdfDoc
import com.lochan.octopusnotes.pdfedit.PdfTextExtractor
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

class PdfTextIndex(private val file: File) {

    data class Match(val pageIndex: Int, val rect: RectF, val text: String, val snippet: String = "")

    private class Line(val text: String, val glyphStarts: IntArray, val rects: FloatArray) {
        val glyphCount: Int get() = glyphStarts.size
        fun glyphEnd(i: Int): Int =
            if (i + 1 < glyphStarts.size) glyphStarts[i + 1] else text.length
    }

    @Volatile
    private var pages: List<List<Line>>? = null

    val isReady: Boolean get() = pages != null

    private val cacheFile: File get() = File(file.parentFile, file.name + ".idx")

    fun ensureTextLayer(onPageIndexed: ((Int, Int) -> Unit)? = null) {
        if (pages != null) return
        loadCache()?.let { pages = it; return }
        val extracted = extractParallel(shouldStop = { false }) { i, n, _ ->
            onPageIndexed?.invoke(i + 1, n)
        } ?: return
        pages = extracted
        saveCache(extracted)
    }

    fun textBoxesForPage(pageIndex: Int): List<RectF> {
        val pgs = pages ?: return emptyList()
        if (pageIndex !in pgs.indices) return emptyList()
        return pgs[pageIndex].mapNotNull { line -> unionGlyphRects(line, 0, line.text.length) }
    }

    fun search(query: String): List<Match> {
        val pgs = pages ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        val matches = ArrayList<Match>()
        for (i in pgs.indices) matches.addAll(matchesInLines(pgs[i], i, query, q))
        return matches
    }

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
        } ?: return
        pages = extracted
        saveCache(extracted)
    }

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

    private fun extractParallel(
        shouldStop: () -> Boolean,
        onPageReady: ((pageIndex: Int, total: Int, lines: List<Line>) -> Unit)?
    ): List<List<Line>>? {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return null
        }
        val n = try {
            PdfDoc.open(bytes).pageCount
        } catch (e: Exception) {
            return null
        }
        if (n == 0) return emptyList()

        val workers = if (n < 6) 1
        else minOf(3, (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1))

        val out = arrayOfNulls<List<Line>>(n)
        val done = AtomicIntegerArray(n)
        val aborted = AtomicBoolean(false)

        val chunk = (n + workers - 1) / workers
        val exec = Executors.newFixedThreadPool(workers)
        for (w in 0 until workers) {
            val from = w * chunk
            val to = minOf(n, from + chunk)
            if (from >= to) break
            exec.execute {
                try {
                    val doc = PdfDoc.open(bytes)
                    val extractor = PdfTextExtractor()
                    for (p in from until to) {
                        if (aborted.get()) return@execute
                        out[p] = try {
                            extractor.extractPage(doc, p).map { Line(it.text, it.glyphStarts, it.rects) }
                        } catch (e: Exception) {
                            emptyList()
                        }
                        done.set(p, 1)
                    }
                } catch (e: Exception) {

                    for (p in from until to) {
                        if (done.get(p) == 0) { out[p] = emptyList(); done.set(p, 1) }
                    }
                }
            }
        }
        exec.shutdown()

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
            f.delete()
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
            tmp.delete()
        }
    }

    companion object {
        private const val CACHE_MAGIC = 0x50494458
        private const val CACHE_VERSION = 1
    }
}
