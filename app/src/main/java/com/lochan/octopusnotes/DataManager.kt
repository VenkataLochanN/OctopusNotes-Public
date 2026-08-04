package com.lochan.octopusnotes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DataManager(
    private val notesDao: NotesDao,
    private val cacheDir: java.io.File? = null,
    private val filesDir: java.io.File? = null
) {

    data class ScreenData(
        val folders: List<Folder>,
        val notebooks: List<Notebook>
    )

    suspend fun getDataForScreen(currentFolderId: Long): ScreenData = withContext(Dispatchers.IO) {
        if (currentFolderId == -1L) {
            return@withContext ScreenData(emptyList(), notesDao.getFavoriteNotebooks())
        }
        // Subfolders + notebooks living directly in the current folder (0 = root).
        ScreenData(
            notesDao.getFoldersInParent(currentFolderId),
            notesDao.getNotebooksInFolder(currentFolderId)
        )
    }

    suspend fun getBinData(): ScreenData = withContext(Dispatchers.IO) {
        ScreenData(
            notesDao.getBinFolders(),
            notesDao.getBinNotebooks()
        )
    }

    /** Global name search across all folders and notebooks. */
    suspend fun searchAll(query: String): ScreenData = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        ScreenData(
            notesDao.getAllFolders().filter { it.name.lowercase().contains(q) },
            notesDao.getAllNotebooks().filter { it.title.lowercase().contains(q) }
        )
    }

    suspend fun getFolder(id: Long): Folder? = withContext(Dispatchers.IO) {
        notesDao.getFolderById(id)
    }

    suspend fun duplicateNotebook(notebook: Notebook, context: android.content.Context) = withContext(Dispatchers.IO) {
        var newPdfPath: String? = null
        if (notebook.pdfPath != null) {
            val originalFile = java.io.File(notebook.pdfPath)
            if (originalFile.exists()) {
                val newFile = java.io.File(context.filesDir, "pdf_copy_${System.currentTimeMillis()}.pdf")
                originalFile.copyTo(newFile, overwrite = true)
                newPdfPath = newFile.absolutePath
            }
        }
        val newId = notesDao.insertNotebook(notebook.copy(id = 0, title = notebook.title + " (Copy)", pdfPath = newPdfPath, isFavorite = false))

        // Copy the ink (stroke) file so the duplicate keeps the original's handwritten content.
        val oldInk = java.io.File(context.filesDir, "notebook_${notebook.id}.json")
        val newInk = java.io.File(context.filesDir, "notebook_${newId}.json")
        if (oldInk.exists()) oldInk.copyTo(newInk, overwrite = true)
        val oldBak = java.io.File(context.filesDir, "notebook_${notebook.id}.json.bak")
        val newBak = java.io.File(context.filesDir, "notebook_${newId}.json.bak")
        if (oldBak.exists()) oldBak.copyTo(newBak, overwrite = true)
        // Copy the thumbnail so the duplicate shows its cover preview immediately.
        val oldThumb = java.io.File(context.filesDir, "thumb_${notebook.id}.png")
        val newThumb = java.io.File(context.filesDir, "thumb_${newId}.png")
        if (oldThumb.exists()) oldThumb.copyTo(newThumb, overwrite = true)
        // Copy the page template so the duplicate renders its thumbnail as an app-created
        // notebook (last-used page) rather than an imported PDF (first page). Without this
        // the thumbnail would regenerate as isImported=true on the next writeHomeThumbnail call.
        val prefs = context.getSharedPreferences("OctopusNotesPrefs", android.content.Context.MODE_PRIVATE)
        prefs.getString("template_${notebook.id}", null)?.let { tpl ->
            prefs.edit().putString("template_${newId}", tpl).apply()
        }

        val drawings = notesDao.getDrawingsForNotebook(notebook.id)
        drawings.forEach {
            notesDao.insertOrUpdateDrawing(it.copy(id = 0, notebookId = newId))
        }
    }

    // --- Drawing Data Methods ---
    suspend fun getDrawingForPage(notebookId: Long, pageNumber: Int): Drawing? = withContext(Dispatchers.IO) {
        notesDao.getDrawingForPage(notebookId, pageNumber)
    }

    suspend fun insertOrUpdateDrawing(drawing: Drawing) = withContext(Dispatchers.IO) {
        notesDao.insertOrUpdateDrawing(drawing)
    }

    suspend fun shiftPageNumbers(notebookId: Long, startPage: Int) = withContext(Dispatchers.IO) {
        notesDao.shiftPageNumbers(notebookId, startPage)
    }

    // --- Other Methods ---
    suspend fun createFolder(name: String, colorHex: String, parentId: Long = 0) = withContext(Dispatchers.IO) {
        notesDao.insertFolder(Folder(name = name, colorHex = colorHex, parentId = parentId, createdAt = System.currentTimeMillis()))
    }

    suspend fun createNotebook(title: String, folderId: Long): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        notesDao.insertNotebook(Notebook(title = title, folderId = folderId, createdAt = now, lastModified = now))
    }

    suspend fun createNotebookWithPdf(title: String, folderId: Long, pdfPath: String?): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            notesDao.insertNotebook(Notebook(title = title, folderId = folderId, pdfPath = pdfPath, createdAt = now, lastModified = now))
        }

    suspend fun touchModified(id: Long) = withContext(Dispatchers.IO) {
        notesDao.touchModified(id, System.currentTimeMillis())
    }

    suspend fun touchOpened(id: Long) = withContext(Dispatchers.IO) {
        notesDao.touchOpened(id, System.currentTimeMillis())
    }

    suspend fun setNotebookPdfPath(id: Long, path: String) = withContext(Dispatchers.IO) {
        val nb = notesDao.getNotebookById(id) ?: return@withContext
        notesDao.updateNotebook(nb.copy(pdfPath = path))
    }

    suspend fun getNotebook(id: Long): Notebook? = withContext(Dispatchers.IO) {
        notesDao.getNotebookById(id)
    }

    data class NotebookSizeBreakdown(
        val pdfSize: Long,
        val drawingSize: Long,
        val thumbnailSize: Long,
        val searchIndexSize: Long,
        val outlineCacheSize: Long
    ) {
        val total get() = pdfSize + drawingSize + thumbnailSize + searchIndexSize + outlineCacheSize
    }

    fun notebookSizeBreakdown(notebook: Notebook): NotebookSizeBreakdown {
        val pdfSize = notebook.pdfPath?.let { java.io.File(it).takeIf { f -> f.exists() }?.length() } ?: 0L
        val drawingSize = filesDir?.let { d ->
            listOf("", ".bak", ".tmp", ".corrupt").sumOf { suffix ->
                java.io.File(d, "notebook_${notebook.id}.json$suffix")
                    .takeIf { f -> f.exists() }?.length() ?: 0L
            }
        } ?: 0L
        val thumbSize = filesDir?.let {
            java.io.File(it, "thumb_${notebook.id}.png").takeIf { f -> f.exists() }?.length()
        } ?: 0L
        val idxSize = notebook.pdfPath?.let {
            java.io.File(it + ".idx").takeIf { f -> f.exists() }?.length()
        } ?: 0L
        val outlineSize = cacheDir?.let {
            java.io.File(it, "outline_${notebook.id}.json").takeIf { f -> f.exists() }?.length()
        } ?: 0L
        return NotebookSizeBreakdown(pdfSize, drawingSize, thumbSize, idxSize, outlineSize)
    }

    suspend fun deleteNotebook(notebook: Notebook, context: android.content.Context? = null) = withContext(Dispatchers.IO) {
        notesDao.deleteNotebook(notebook)
        try {
            notebook.pdfPath?.let { path ->
                val pdf = java.io.File(path)
                java.io.File(pdf.parentFile, pdf.name + ".idx").delete()
                pdf.delete()
            }
            cacheDir?.let { java.io.File(it, "outline_${notebook.id}.json").delete() }
            filesDir?.let { d ->
                listOf("", ".bak", ".tmp", ".corrupt").forEach { suffix ->
                    java.io.File(d, "notebook_${notebook.id}.json$suffix").delete()
                }
                java.io.File(d, "thumb_${notebook.id}.png").delete()
            }
            context?.let { ctx ->
                ctx.getSharedPreferences("notebook_state", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .remove("last_page_${notebook.id}")
                    .remove("page_count_${notebook.id}")
                    .apply()
                ctx.getSharedPreferences("OctopusNotesPrefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .remove("page_bookmarks_${notebook.id}")
                    .remove("page_outline_${notebook.id}")
                    .apply()
            }
        } catch (_: Exception) {}
    }

    suspend fun deleteFolder(folder: Folder) = withContext(Dispatchers.IO) {
        // Bubble this folder's notebooks and subfolders up to its parent, then delete it.
        notesDao.moveNotebooksToParent(folder.id, folder.parentId)
        notesDao.moveSubfoldersToParent(folder.id, folder.parentId)
        notesDao.deleteFolder(folder)
    }
    
    suspend fun moveToBin(notebook: Notebook) = withContext(Dispatchers.IO) {
        notesDao.updateNotebook(notebook.copy(inBin = true, deletedAt = System.currentTimeMillis()))
    }
    
    suspend fun moveToBin(folder: Folder) = withContext(Dispatchers.IO) {
        notesDao.updateFolder(folder.copy(inBin = true, deletedAt = System.currentTimeMillis()))
    }

    suspend fun restoreFromBin(notebook: Notebook) = withContext(Dispatchers.IO) {
        notesDao.updateNotebook(notebook.copy(inBin = false, deletedAt = 0))
    }

    suspend fun restoreFromBin(folder: Folder) = withContext(Dispatchers.IO) {
        notesDao.updateFolder(folder.copy(inBin = false, deletedAt = 0))
    }
    
    suspend fun cleanupBin() = withContext(Dispatchers.IO) {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val oldNotebooks = notesDao.getOldBinNotebooks(thirtyDaysAgo)
        for (notebook in oldNotebooks) {
            deleteNotebook(notebook)
        }
        val oldFolders = notesDao.getOldBinFolders(thirtyDaysAgo)
        for (folder in oldFolders) {
            deleteFolder(folder)
        }
    }

    suspend fun updateFolder(folder: Folder) = withContext(Dispatchers.IO) {
        notesDao.updateFolder(folder)
    }

    suspend fun updateNotebook(notebook: Notebook) = withContext(Dispatchers.IO) {
        notesDao.updateNotebook(notebook)
    }
}