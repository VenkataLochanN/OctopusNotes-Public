package com.lochan.octopusnotes

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object FontManager {

    const val DIR_NAME = "fonts"

    private val typefaceCache = HashMap<String, Typeface>()

    fun dir(context: Context): File = dirFor(context.filesDir)

    fun dirFor(filesDir: File): File = File(filesDir, DIR_NAME)

    fun isFontFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".ttf") || n.endsWith(".otf")
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && isFontFile(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun label(fileName: String): String = fileName.substringBeforeLast('.')

    fun import(context: Context, uri: Uri): File? {
        val dir = dir(context)
        if (!dir.exists()) dir.mkdirs()
        val base = displayName(context, uri) ?: "Font"
        val ext = when {
            base.lowercase().endsWith(".otf") -> ".otf"
            base.lowercase().endsWith(".ttf") -> ".ttf"
            else -> ".ttf"
        }
        val stem = sanitize(base.substringBeforeLast('.', base))
        var name = stem + ext
        var target = File(dir, name)
        var n = 1
        while (target.exists()) { n++; name = "${stem}_$n$ext"; target = File(dir, name) }
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null

            val typeface = Typeface.createFromFile(target)
            typefaceCache[name] = typeface
            target
        } catch (e: Exception) {
            typefaceCache.remove(name)
            try { target.delete() } catch (_: Exception) {}
            null
        }
    }

    fun delete(file: File) {
        typefaceCache.remove(file.name)
        try { file.delete() } catch (_: Exception) {}
    }

    fun typeface(fontsDir: File?, fileName: String?): Typeface? {
        if (fileName.isNullOrBlank() || fontsDir == null) return null
        typefaceCache[fileName]?.let { return it }
        val f = File(fontsDir, fileName)
        if (!f.isFile) return null
        return try {
            Typeface.createFromFile(f).also { typefaceCache[fileName] = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Font" }
}
