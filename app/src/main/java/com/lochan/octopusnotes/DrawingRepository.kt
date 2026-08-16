package com.lochan.octopusnotes

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader

class DrawingRepository(context: Context) {

    private val dir = context.filesDir
    private fun fileFor(notebookId: Long) = File(dir, "notebook_$notebookId.json")
    private fun bakFor(notebookId: Long) = File(dir, "notebook_$notebookId.json.bak")

    @Volatile
    private var latestSaveGeneration = 0

    data class LoadResult(
        val pages: Map<Int, DrawingCodec.DecodedPage>,
        val unsupportedTypeCounts: Map<String, Int>,

        val mainFileCorrupt: Boolean = false,

        val baseWidth: Float = 0f
    )

    private fun decode(
        file: File,
        targetWidth: Float,
        fallbackBaseWidth: Float,
        priorityPage: Int,
        onPage: ((Int, DrawingCodec.DecodedPage) -> Unit)?
    ): DrawingCodec.DecodeResult {
        if (priorityPage < 0) {

            return file.reader(Charsets.UTF_8).use {
                DrawingCodec.decodeDocument(it, targetWidth, fallbackBaseWidth, onPage)
            }
        }

        val first = file.reader(Charsets.UTF_8).use {
            DrawingCodec.decodeDocument(it, targetWidth, fallbackBaseWidth, onPage, onlyPage = priorityPage)
        }
        val rest = file.reader(Charsets.UTF_8).use {
            DrawingCodec.decodeDocument(it, targetWidth, fallbackBaseWidth, onPage, skipPage = priorityPage)
        }
        first.pages.putAll(rest.pages)
        val merged = HashMap<String, Int>(first.unsupportedTypeCounts)
        for ((k, v) in rest.unsupportedTypeCounts) merged[k] = (merged[k] ?: 0) + v
        return DrawingCodec.DecodeResult(first.pages, merged, baseWidth = first.baseWidth)
    }

    fun load(
        notebookId: Long,
        targetWidth: Float = 0f,
        fallbackBaseWidth: Float = 0f,

        priorityPage: Int = -1,
        onPage: ((Int, DrawingCodec.DecodedPage) -> Unit)? = null
    ): LoadResult {
        val main = fileFor(notebookId)
        val bak = bakFor(notebookId)

        if (main.exists()) {
            try {
                val r = decode(main, targetWidth, fallbackBaseWidth, priorityPage, onPage)
                return LoadResult(r.pages, r.unsupportedTypeCounts, baseWidth = r.baseWidth)
            } catch (e: Exception) {

                try {
                    main.copyTo(File(dir, main.name + ".corrupt"), overwrite = true)
                } catch (_: Exception) {}
            }

            if (bak.exists()) {
                try {
                    val r = decode(bak, targetWidth, fallbackBaseWidth, -1, null)
                    return LoadResult(r.pages, r.unsupportedTypeCounts, mainFileCorrupt = true, baseWidth = r.baseWidth)
                } catch (_: Exception) {}
            }
            return LoadResult(emptyMap(), emptyMap(), mainFileCorrupt = true)
        }

        if (bak.exists()) {
            try {
                val r = DrawingCodec.decodeDocument(bak.readText(), targetWidth, fallbackBaseWidth)
                return LoadResult(r.pages, r.unsupportedTypeCounts, baseWidth = r.baseWidth)
            } catch (_: Exception) {}
        }
        return LoadResult(emptyMap(), emptyMap())
    }

    @Synchronized
    fun save(
        notebookId: Long,
        pageIndices: Collection<Int>,
        knownProvider: (Int) -> List<StrokeData>,
        unknownProvider: (Int) -> List<JSONObject>,
        baseWidth: Float,
        generation: Int = 0
    ) {
        if (generation < latestSaveGeneration) return
        latestSaveGeneration = generation

        val json = DrawingCodec.encodeDocument(pageIndices, knownProvider, unknownProvider, baseWidth)
        writeDurable(notebookId, json)
    }

    @Synchronized
    fun saveInfinite(
        notebookId: Long,
        known: List<StrokeData>,
        unknown: List<JSONObject>,
        generation: Int = 0
    ) {
        if (generation < latestSaveGeneration) return
        latestSaveGeneration = generation

        val json = DrawingCodec.encodeInfiniteDocument(known, unknown)
        writeDurable(notebookId, json)
    }

    fun loadInfinite(notebookId: Long): LoadResult {
        val main = fileFor(notebookId)
        val bak = bakFor(notebookId)

        if (main.exists()) {
            try {
                val r = main.reader(Charsets.UTF_8).use { DrawingCodec.decodeInfiniteDocument(it) }
                return LoadResult(r.pages, r.unsupportedTypeCounts, baseWidth = r.baseWidth)
            } catch (e: Exception) {

                try {
                    main.copyTo(File(dir, main.name + ".corrupt"), overwrite = true)
                } catch (_: Exception) {}
            }
            if (bak.exists()) {
                try {
                    val r = bak.reader(Charsets.UTF_8).use { DrawingCodec.decodeInfiniteDocument(it) }
                    return LoadResult(r.pages, r.unsupportedTypeCounts, mainFileCorrupt = true, baseWidth = r.baseWidth)
                } catch (_: Exception) {}
            }
            return LoadResult(emptyMap(), emptyMap(), mainFileCorrupt = true)
        }

        if (bak.exists()) {
            try {
                val r = DrawingCodec.decodeInfiniteDocument(StringReader(bak.readText()))
                return LoadResult(r.pages, r.unsupportedTypeCounts, baseWidth = r.baseWidth)
            } catch (_: Exception) {}
        }
        return LoadResult(emptyMap(), emptyMap())
    }

    private fun writeDurable(notebookId: Long, json: String) {
        val target = fileFor(notebookId)
        val bak = bakFor(notebookId)
        val tmp = File(dir, target.name + ".tmp")

        FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }

        if (target.exists()) {
            bak.delete()
            target.renameTo(bak)
        }
        if (!tmp.renameTo(target)) {

            bak.renameTo(target)
            throw java.io.IOException("Failed to move ${tmp.name} into place")
        }
    }
}
