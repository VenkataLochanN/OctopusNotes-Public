package com.lochan.octopusnotes

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class DrawingRepository(context: Context) {

    private val dir = context.filesDir
    private fun fileFor(notebookId: Long) = File(dir, "notebook_$notebookId.json")
    private fun bakFor(notebookId: Long) = File(dir, "notebook_$notebookId.json.bak")

    data class LoadResult(
        val pages: Map<Int, DrawingCodec.DecodedPage>,
        val unsupportedTypeCounts: Map<String, Int>,
        /** True when the main file existed but couldn't be decoded (backup may have been used). */
        val mainFileCorrupt: Boolean = false
    )

    private fun decode(
        file: File,
        targetWidth: Float,
        fallbackBaseWidth: Float,
        onPage: ((Int, DrawingCodec.DecodedPage) -> Unit)?
    ): DrawingCodec.DecodeResult =
        // Streamed off the file: never materializes the whole document as a String.
        file.reader(Charsets.UTF_8).use {
            DrawingCodec.decodeDocument(it, targetWidth, fallbackBaseWidth, onPage)
        }

    /**
     * Reads + decodes. Falls back to the `.bak` copy when the main file is missing or
     * corrupt. A corrupt main file is preserved as `.corrupt` so it is never overwritten
     * by a later save and can be recovered manually.
     *
     * [targetWidth] / [fallbackBaseWidth] rescale the ink into the current page width —
     * see [DrawingCodec] for why that is orientation-dependent.
     */
    fun load(
        notebookId: Long,
        targetWidth: Float = 0f,
        fallbackBaseWidth: Float = 0f,
        onPage: ((Int, DrawingCodec.DecodedPage) -> Unit)? = null
    ): LoadResult {
        val main = fileFor(notebookId)
        val bak = bakFor(notebookId)

        if (main.exists()) {
            try {
                val r = decode(main, targetWidth, fallbackBaseWidth, onPage)
                return LoadResult(r.pages, r.unsupportedTypeCounts)
            } catch (e: Exception) {
                // Keep the damaged file around for manual recovery.
                try {
                    main.copyTo(File(dir, main.name + ".corrupt"), overwrite = true)
                } catch (_: Exception) {}
            }
            // Main existed but was unreadable — try the backup.
            if (bak.exists()) {
                try {
                    val r = decode(bak, targetWidth, fallbackBaseWidth, null)
                    return LoadResult(r.pages, r.unsupportedTypeCounts, mainFileCorrupt = true)
                } catch (_: Exception) {}
            }
            return LoadResult(emptyMap(), emptyMap(), mainFileCorrupt = true)
        }

        // No main file: a previous save may have died between the bak-rotation and the
        // final rename — the backup is then the newest good copy.
        if (bak.exists()) {
            try {
                val r = DrawingCodec.decodeDocument(bak.readText(), targetWidth, fallbackBaseWidth)
                return LoadResult(r.pages, r.unsupportedTypeCounts)
            } catch (_: Exception) {}
        }
        return LoadResult(emptyMap(), emptyMap())
    }

    /**
     * Durable write: tmp file + fsync, previous version rotated to `.bak`, then an atomic
     * rename into place. At every instant at least one intact copy exists on disk.
     */
    @Synchronized
    fun save(
        notebookId: Long,
        pageIndices: Collection<Int>,
        knownProvider: (Int) -> List<StrokeData>,
        unknownProvider: (Int) -> List<JSONObject>,
        baseWidth: Float
    ) {
        val json = DrawingCodec.encodeDocument(pageIndices, knownProvider, unknownProvider, baseWidth)
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
            // Rename failed (shouldn't happen on the same filesystem) — restore the backup.
            bak.renameTo(target)
            throw java.io.IOException("Failed to move ${tmp.name} into place")
        }
    }
}
