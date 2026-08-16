package com.lochan.octopusnotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File

object SyncFolderManager {

    private const val PREFS_NAME = "OctopusNotesPrefs"
    private const val KEY_URI = "sync_folder_uri"
    private const val KEY_ENABLED = "sync_folder_enabled"

    private const val KEY_MISSING_NOTIFIED = "sync_folder_missing_notified"

    private const val README_NAME = "README.txt"
    private const val DIR_NOTEBOOKS = "notebooks"
    private const val DIR_PDFS = "pdfs"
    private const val DIR_IMAGES = "images"
    private const val DIR_TEMPLATES = "templates"
    private const val DIR_DATABASE = "database"
    private const val DIR_SETTINGS = "settings"
    private const val DIR_FOLDERS = "folders"

    private const val FOLDERS_FILE = "folders.json"
    private const val PREFS_SETTINGS = "OctopusNotesPrefs.xml"
    private const val PREFS_STATE = "notebook_state.xml"

    private const val META_SUFFIX = ".meta.json"

    data class NotebookMeta(
        val title: String,
        val documentType: String,
        val createdAt: Long,
        val lastModified: Long,
        val isImported: Boolean,
        val folderId: Long = 0,
        val isFavorite: Boolean = false,
        val inBin: Boolean = false,
        val deletedAt: Long = 0,
        val tagColorHex: String? = null
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun uriString(context: Context): String? = prefs(context).getString(KEY_URI, null)

    fun isEnabled(context: Context): Boolean {
        val p = prefs(context)
        return if (p.contains(KEY_ENABLED)) p.getBoolean(KEY_ENABLED, false) else uriString(context) != null
    }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) clearMissingNotified(context)
    }

    fun isActive(context: Context): Boolean = isEnabled(context) && hasFolderGrant(context)

    fun hasFolderGrant(context: Context): Boolean {
        val s = uriString(context) ?: return false
        return try {
            val uri = Uri.parse(s)
            context.contentResolver.persistedUriPermissions.any { it.uri == uri }
        } catch (e: Exception) {
            false
        }
    }

    fun isReachable(context: Context): Boolean {
        if (!hasFolderGrant(context)) return false
        return try {
            val root = rootDir(context) ?: return false
            root.exists()
        } catch (e: Exception) {
            false
        }
    }

    fun isMissing(context: Context): Boolean = isActive(context) && !isReachable(context)

    fun wasMissingNotified(context: Context): Boolean = prefs(context).getBoolean(KEY_MISSING_NOTIFIED, false)

    fun markMissingNotified(context: Context) {
        prefs(context).edit().putBoolean(KEY_MISSING_NOTIFIED, true).apply()
    }

    fun clearMissingNotified(context: Context) {
        prefs(context).edit().remove(KEY_MISSING_NOTIFIED).apply()
    }

    fun folderDisplayName(context: Context): String? {
        val s = uriString(context) ?: return null
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(s))?.name
        } catch (e: Exception) {
            null
        }
    }

    fun setFolder(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_URI, uri.toString()).apply()
        clearMissingNotified(context)
    }

    fun clearFolder(context: Context) {
        prefs(context).edit().remove(KEY_URI).apply()
        clearMissingNotified(context)
    }

    fun mirrorNotebook(context: Context, notebookId: Long, notebook: Notebook? = null) {
        if (!isActive(context)) return
        val dir = context.filesDir
        mirrorFile(context, File(dir, "notebook_$notebookId.json"), DIR_NOTEBOOKS)
        mirrorFile(context, File(dir, "pdf_$notebookId.pdf"), DIR_PDFS)
        mirrorImages(context)
        mirrorTemplates(context)
        if (notebook != null && notebook.id == notebookId) writeNotebookMeta(context, notebook)
    }

    fun mirrorAll(
        context: Context,
        notebooks: List<Notebook>? = null,
        onProgress: ((done: Long, total: Long) -> Unit)? = null
    ) {
        if (!isActive(context)) return
        val root = rootDir(context) ?: return

        val notebookDir = subdir(context, root, DIR_NOTEBOOKS) ?: return
        val pdfDir = subdir(context, root, DIR_PDFS) ?: return
        val imagesDir = subdir(context, root, DIR_IMAGES) ?: return
        val templatesDir = subdir(context, root, DIR_TEMPLATES) ?: return
        val dbDir = subdir(context, root, DIR_DATABASE) ?: return
        val settingsDir = subdir(context, root, DIR_SETTINGS) ?: return

        val notebookLocals = notebookFiles(context)
        val pdfLocals = pdfFiles(context)
        val imageLocals = imageFiles(context)
        val templateLocals = templateFiles(context)
        val dbLocals = databaseFiles(context)
        val settingsLocals = settingsFiles(context)

        data class Batch(val dir: DocumentFile, val locals: List<File>, val toCopy: List<File>)

        val batches = listOf(
            Batch(notebookDir, notebookLocals, filesNeedingCopy(context, notebookDir, notebookLocals)),
            Batch(pdfDir, pdfLocals, filesNeedingCopy(context, pdfDir, pdfLocals)),
            Batch(imagesDir, imageLocals, filesNeedingCopy(context, imagesDir, imageLocals)),
            Batch(templatesDir, templateLocals, filesNeedingCopy(context, templatesDir, templateLocals)),
            Batch(dbDir, dbLocals, filesNeedingCopy(context, dbDir, dbLocals)),
            Batch(settingsDir, settingsLocals, filesNeedingCopy(context, settingsDir, settingsLocals))
        )

        val total = 2L + batches.sumOf { it.toCopy.size } + (notebooks?.size ?: 0)
        var done = 0L
        fun report() = onProgress?.invoke(done, total)

        writeReadme(context)
        done++
        report()
        mirrorFolders(context)
        done++
        report()
        for (batch in batches) {
            for (f in batch.toCopy) {
                copyFileTo(context, f, batch.dir)
                done++
                report()
            }
            prune(context, batch.dir) { name ->
                batch.locals.any { it.name == name } ||
                    (name.endsWith(META_SUFFIX) && batch.locals.any { it.name == name.removeSuffix(META_SUFFIX) + ".json" })
            }
        }
        notebooks?.forEach {
            writeNotebookMeta(context, it)
            done++
            report()
        }
    }

    private fun notebookFiles(context: Context): List<File> =
        context.filesDir.listFiles { f ->
            f.name.startsWith("notebook_") && f.name.endsWith(".json") && !f.name.endsWith(META_SUFFIX)
        }?.toList() ?: emptyList()

    private fun pdfFiles(context: Context): List<File> =
        context.filesDir.listFiles { f ->
            f.name.startsWith("pdf_") && f.name.endsWith(".pdf")
        }?.toList() ?: emptyList()

    private fun imageFiles(context: Context): List<File> {
        val localImages = File(context.filesDir, "images")
        return if (localImages.exists()) (localImages.listFiles() ?: emptyArray()).filter { it.isFile } else emptyList()
    }

    private fun templateFiles(context: Context): List<File> {
        val localTemplates = File(context.filesDir, "templates")
        return if (localTemplates.exists()) (localTemplates.listFiles() ?: emptyArray()).filter { it.isFile } else emptyList()
    }

    private fun databaseFiles(context: Context): List<File> {
        val dbDir = File(context.applicationInfo.dataDir, "databases")
        val main = File(dbDir, BuildConfig.DB_NAME)
        if (!main.exists()) return emptyList()

        try {
            AppDatabase.getDatabase(context).openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)").close()
        } catch (e: Exception) {
        }
        return listOf(main) +
            listOf(File(dbDir, BuildConfig.DB_NAME + "-wal"), File(dbDir, BuildConfig.DB_NAME + "-shm"))
                .filter { it.exists() }
    }

    private fun settingsFiles(context: Context): List<File> {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        return listOf(PREFS_SETTINGS, PREFS_STATE)
            .map { File(prefsDir, it) }
            .filter { it.exists() }
    }

    private fun mirrorFolders(context: Context) {
        if (!isActive(context)) return
        val root = rootDir(context) ?: return
        val dir = subdir(context, root, DIR_FOLDERS) ?: return
        val folders = try {
            AppDatabase.getDatabase(context).notesDao().getAllFoldersIncludingBin()
        } catch (e: Exception) {
            emptyList()
        }
        val json = org.json.JSONArray()
        for (f in folders) {
            json.put(
                JSONObject()
                    .put("id", f.id)
                    .put("name", f.name)
                    .put("colorHex", f.colorHex)
                    .put("parentId", f.parentId)
                    .put("createdAt", f.createdAt)
                    .put("inBin", f.inBin)
                    .put("deletedAt", f.deletedAt)
                    .put("tagColorHex", f.tagColorHex)
            )
        }
        try {
            val file = dir.findFile(FOLDERS_FILE) ?: dir.createFile("application/json", FOLDERS_FILE) ?: return
            context.contentResolver.openOutputStream(file.uri, "w")?.use {
                it.write(json.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
        }
    }

    private fun filesNeedingCopy(context: Context, dir: DocumentFile, locals: List<File>): List<File> =
        locals.filter { !mirrorHoldsCopy(context, dir, it) }

    fun removeNotebook(context: Context, notebookId: Long) {
        if (!isActive(context)) return
        val root = rootDir(context) ?: return
        deleteFile(context, root, DIR_NOTEBOOKS, "notebook_$notebookId.json")
        deleteFile(context, root, DIR_NOTEBOOKS, "notebook_$notebookId$META_SUFFIX")
        deleteFile(context, root, DIR_PDFS, "pdf_$notebookId.pdf")
    }

    private fun mirrorImages(context: Context) {
        if (!isActive(context)) return
        val root = rootDir(context) ?: return
        syncDir(context, subdir(context, root, DIR_IMAGES) ?: return, imageFiles(context))
    }

    private fun mirrorTemplates(context: Context) {
        if (!isActive(context)) return
        val root = rootDir(context) ?: return
        syncDir(context, subdir(context, root, DIR_TEMPLATES) ?: return, templateFiles(context))
    }

    private fun syncDir(context: Context, dir: DocumentFile, locals: List<File>) {
        for (f in locals) copyFileTo(context, f, dir)
        prune(context, dir) { name ->
            locals.any { it.name == name } ||
                (name.endsWith(META_SUFFIX) && locals.any { it.name == name.removeSuffix(META_SUFFIX) + ".json" })
        }
    }

    private fun mirrorFile(context: Context, local: File, sub: String) {
        if (!local.exists()) return
        val root = rootDir(context) ?: return
        val dir = subdir(context, root, sub) ?: return
        copyFileTo(context, local, dir)
    }

    private fun copyFileTo(context: Context, local: File, dir: DocumentFile) {
        try {

            if (mirrorHoldsCopy(context, dir, local)) return
            val out = dir.findFile(local.name) ?: dir.createFile("application/octet-stream", local.name) ?: return
            local.inputStream().use { inp ->
                context.contentResolver.openOutputStream(out.uri, "w")?.use { o ->
                    inp.copyTo(o, 64 * 1024)
                }
            }
        } catch (e: Exception) {

        }
    }

    private fun mirrorHoldsCopy(context: Context, dir: DocumentFile, local: File): Boolean = try {
        val dest = dir.findFile(local.name)
        dest != null && dest.length() == local.length() &&
            (dest.lastModified() == 0L || dest.lastModified() == local.lastModified())
    } catch (e: Exception) {
        false
    }

    private fun prune(context: Context, dir: DocumentFile, stillExists: (String) -> Boolean) {
        try {
            for (child in dir.listFiles()) {
                if (child.isFile && child.name?.let { !stillExists(it) } != false) child.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun deleteFile(context: Context, root: DocumentFile, sub: String, name: String) {
        try {
            root.findFile(sub)?.findFile(name)?.delete()
        } catch (e: Exception) {
        }
    }

    private fun rootDir(context: Context): DocumentFile? {
        val s = uriString(context) ?: return null
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(s))
        } catch (e: Exception) {
            null
        }
    }

    fun listFiles(context: Context, sub: String): List<String> {
        if (!hasFolderGrant(context)) return emptyList()
        val root = rootDir(context) ?: return emptyList()
        return try {
            root.findFile(sub)?.listFiles()?.filter { it.isFile }?.mapNotNull { it.name } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun readBytes(context: Context, sub: String, name: String): ByteArray? {
        if (!hasFolderGrant(context)) return null
        val root = rootDir(context) ?: return null
        return try {
            val src = root.findFile(sub)?.findFile(name) ?: return null
            val inp = context.contentResolver.openInputStream(src.uri) ?: return null
            inp.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    fun copyTo(context: Context, sub: String, name: String, dest: File): Boolean {
        if (!hasFolderGrant(context)) return false
        val root = rootDir(context) ?: return false
        return try {
            val src = root.findFile(sub)?.findFile(name) ?: return false
            val inp = context.contentResolver.openInputStream(src.uri) ?: return false
            dest.parentFile?.mkdirs()
            inp.use { i -> dest.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun writeNotebookMeta(context: Context, notebook: Notebook) {
        if (!isActive(context)) return
        val root = rootDir(context) ?: return
        val dir = subdir(context, root, DIR_NOTEBOOKS) ?: return
        val name = "notebook_${notebook.id}$META_SUFFIX"

        val isImported = prefs(context).getString("template_${notebook.id}", null) == null
        val json = JSONObject()
            .put("title", notebook.title)
            .put("documentType", notebook.documentType)
            .put("createdAt", notebook.createdAt)
            .put("lastModified", notebook.lastModified)
            .put("isImported", isImported)
            .put("folderId", notebook.folderId)
            .put("isFavorite", notebook.isFavorite)
            .put("inBin", notebook.inBin)
            .put("deletedAt", notebook.deletedAt)
            .put("tagColorHex", notebook.tagColorHex)
        try {
            val file = dir.findFile(name) ?: dir.createFile("application/json", name) ?: return
            context.contentResolver.openOutputStream(file.uri, "w")?.use {
                it.write(json.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
        }
    }

    fun readNotebookMeta(context: Context, notebookId: Long): NotebookMeta? {
        val bytes = readBytes(context, DIR_NOTEBOOKS, "notebook_$notebookId$META_SUFFIX") ?: return null
        return try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            NotebookMeta(
                title = o.optString("title").ifBlank { "Restored notebook" },
                documentType = o.optString("documentType", DocumentType.PAGED),
                createdAt = o.optLong("createdAt", 0L),
                lastModified = o.optLong("lastModified", 0L),
                isImported = o.optBoolean("isImported", true),
                folderId = o.optLong("folderId", 0L),
                isFavorite = o.optBoolean("isFavorite", false),
                inBin = o.optBoolean("inBin", false),
                deletedAt = o.optLong("deletedAt", 0L),
                tagColorHex = o.optString("tagColorHex", "").ifBlank { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun subdir(context: Context, root: DocumentFile, name: String): DocumentFile? {
        return try {
            root.findFile(name) ?: root.createDirectory(name)
        } catch (e: Exception) {
            null
        }
    }

    fun writeReadme(context: Context) {
        val root = rootDir(context) ?: return
        try {
            val file = root.findFile(README_NAME) ?: root.createFile("text/plain", README_NAME) ?: return
            context.contentResolver.openOutputStream(file.uri, "w")?.use {
                it.write(readmeText().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
        }
    }

    private fun readmeText(): String = """
        OctopusNotes sync folder
        =========================

        This folder contains a mirror of your OctopusNotes data, updated automatically
        whenever you edit a note. It exists so you can back it up, or sync it to another
        device with your own tools (cloud drive, Syncthing, git, ...).

        Contents:
          notebooks/   one JSON file per notebook (its ink/strokes), plus a small
                       <id>.meta.json sidecar with the title, dates, folder and bin state
                       the app needs to bring the note back on restore
          pdfs/        imported PDFs and generated page templates
          images/      photos and pictures inserted into notes
          templates/   custom page-background images you picked for notebooks
          folders/     your folder tree (names, colors, nesting, bin state) as folders.json
          database/    a snapshot of the app's database (notebooks, folders, metadata)
          settings/    the app's preference files (view mode, tool settings, ...)
          README.txt   this file

        The database and settings snapshots complete the backup, so this folder holds a
        full copy of your OctopusNotes data — you can back it up or sync it anywhere.

        To bring notes back into the app (e.g. after reinstalling, or from another
        device), use Settings -> Restore from folder: it recreates your folders, notes
        (with their bin state) and every saved setting. For a complete all-data restore,
        Settings -> Data -> Export (Backup) / Import (Backup) still ships everything in
        one portable file.

        IMPORTANT: keep this folder — don't delete or rename it while syncing is on.
        The app writes new copies here after every edit. If the folder disappears,
        syncing simply stops until you choose it again in Settings.
    """".trimIndent()

    fun grantFlags(): Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}
