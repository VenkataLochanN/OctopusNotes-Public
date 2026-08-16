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

    suspend fun getNotebooksByTag(colorHex: String): ScreenData = withContext(Dispatchers.IO) {
        ScreenData(notesDao.getFoldersByTag(colorHex), notesDao.getNotebooksByTag(colorHex))
    }

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
        val newNotebook = notebook.copy(id = 0, title = notebook.title + " (Copy)", pdfPath = newPdfPath, isFavorite = false)
        val newId = notesDao.insertNotebook(newNotebook)

        val oldInk = java.io.File(context.filesDir, "notebook_${notebook.id}.json")
        val newInk = java.io.File(context.filesDir, "notebook_${newId}.json")
        if (oldInk.exists()) oldInk.copyTo(newInk, overwrite = true)
        val oldBak = java.io.File(context.filesDir, "notebook_${notebook.id}.json.bak")
        val newBak = java.io.File(context.filesDir, "notebook_${newId}.json.bak")
        if (oldBak.exists()) oldBak.copyTo(newBak, overwrite = true)

        val oldThumb = java.io.File(context.filesDir, "thumb_${notebook.id}.png")
        val newThumb = java.io.File(context.filesDir, "thumb_${newId}.png")
        if (oldThumb.exists()) oldThumb.copyTo(newThumb, overwrite = true)

        val prefs = context.getSharedPreferences("OctopusNotesPrefs", android.content.Context.MODE_PRIVATE)
        prefs.getString("template_${notebook.id}", null)?.let { tpl ->
            prefs.edit().putString("template_${newId}", tpl).apply()
        }

        val drawings = notesDao.getDrawingsForNotebook(notebook.id)
        drawings.forEach {
            notesDao.insertOrUpdateDrawing(it.copy(id = 0, notebookId = newId))
        }

        SyncFolderManager.mirrorNotebook(context, newId, newNotebook.copy(id = newId))
    }

    suspend fun getAllNotebooks(): List<Notebook> = withContext(Dispatchers.IO) {
        notesDao.getAllNotebooks()
    }

    suspend fun getAllNotebooksIncludingBin(): List<Notebook> = withContext(Dispatchers.IO) {
        notesDao.getAllNotebooksIncludingBin()
    }

    data class RestoreSummary(val notebooks: Int, val pdfs: Int, val images: Int, val templates: Int, val folders: Int)

    suspend fun restoreFromSyncFolder(
        context: android.content.Context,
        onProgress: ((done: Long, total: Long) -> Unit)? = null
    ): RestoreSummary =
        withContext(Dispatchers.IO) {
            val filesDir = context.filesDir
            var notebooks = 0
            var pdfs = 0
            var images = 0
            var templates = 0
            var folders = 0

            restorePrefsFile(context, "settings", "OctopusNotesPrefs.xml")
            restorePrefsFile(context, "settings", "notebook_state.xml")

            folders = restoreFolders(context)

            val imageNames = SyncFolderManager.listFiles(context, "images")
            val templateNames = SyncFolderManager.listFiles(context, "templates")

            val pdfIds = SyncFolderManager.listFiles(context, "pdfs")
                .mapNotNull { it.removePrefix("pdf_").removeSuffix(".pdf").toLongOrNull() }
                .toHashSet()

            val toRestore = SyncFolderManager.listFiles(context, "notebooks")
                .filter { it.endsWith(".json") && !it.endsWith(".meta.json") }
                .mapNotNull { name ->
                    val id = name.removePrefix("notebook_").removeSuffix(".json").toLongOrNull()
                        ?: return@mapNotNull null
                    if (notesDao.getNotebookById(id) != null) null else name to id
                }

            val total = (imageNames.size + templateNames.size + toRestore.size).toLong()
            var done = 0L
            fun report() = onProgress?.invoke(done, total)

            for (name in imageNames) {
                val dest = java.io.File(filesDir, "images/$name")
                if (SyncFolderManager.copyTo(context, "images", name, dest)) images++
                done++
                report()
            }

            for (name in templateNames) {
                val dest = java.io.File(filesDir, "templates/$name")
                if (SyncFolderManager.copyTo(context, "templates", name, dest)) templates++
                done++
                report()
            }

            for ((name, id) in toRestore) {
                val bytes = SyncFolderManager.readBytes(context, "notebooks", name)
                done++
                report()
                if (bytes == null) continue

                val isInfinite = try {
                    org.json.JSONObject(String(bytes, Charsets.UTF_8)).optString("type") == "INFINITE"
                } catch (e: Exception) {
                    false
                }
                val inkFile = java.io.File(filesDir, "notebook_$id.json")
                inkFile.writeBytes(bytes)

                var pdfPath: String? = null
                if (id in pdfIds) {
                    val pdfFile = java.io.File(filesDir, "pdf_$id.pdf")
                    if (SyncFolderManager.copyTo(context, "pdfs", "pdf_$id.pdf", pdfFile)) {
                        pdfPath = pdfFile.absolutePath
                        pdfs++
                    }
                }

                val meta = SyncFolderManager.readNotebookMeta(context, id)
                val now = System.currentTimeMillis()
                notesDao.insertNotebook(
                    Notebook(
                        id = id,
                        title = meta?.title ?: "Restored notebook",
                        folderId = meta?.folderId ?: 0,
                        pdfPath = pdfPath,
                        isFavorite = meta?.isFavorite ?: false,
                        createdAt = meta?.createdAt?.takeIf { it > 0L } ?: now,
                        lastModified = meta?.lastModified?.takeIf { it > 0L } ?: now,

                        inBin = meta?.inBin ?: false,
                        deletedAt = meta?.deletedAt ?: 0L,
                        tagColorHex = meta?.tagColorHex,
                        documentType = if (isInfinite) DocumentType.INFINITE else DocumentType.PAGED
                    )
                )

                restoreThumbnail(context, id, isInfinite, pdfPath, meta?.isImported ?: true)
                notebooks++
            }
            RestoreSummary(notebooks, pdfs, images, templates, folders)
        }

    private suspend fun restoreFolders(context: android.content.Context): Int {
        val bytes = SyncFolderManager.readBytes(context, "folders", "folders.json") ?: return 0
        return try {
            val arr = org.json.JSONArray(String(bytes, Charsets.UTF_8))
            var count = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optLong("id", 0L)
                if (id <= 0L || notesDao.getFolderById(id) != null) continue
                notesDao.insertFolder(
                    Folder(
                        id = id,
                        name = o.optString("name", "Folder"),
                        colorHex = o.optString("colorHex", "#FFC107"),
                        parentId = o.optLong("parentId", 0L),
                        createdAt = o.optLong("createdAt", 0L),

                        inBin = o.optBoolean("inBin", false),
                        deletedAt = o.optLong("deletedAt", 0L)
                    )
                )
                count++
            }
            count
        } catch (e: Exception) {
            0
        }
    }

    private fun restorePrefsFile(context: android.content.Context, sub: String, name: String) {
        val bytes = SyncFolderManager.readBytes(context, sub, name) ?: return
        try {
            val prefs = context.getSharedPreferences(
                name.removeSuffix(".xml"), android.content.Context.MODE_PRIVATE
            )
            val editor = prefs.edit()
            val parser = android.util.Xml.newPullParser()
            parser.setInput(java.io.StringReader(String(bytes, Charsets.UTF_8)))
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    val key = parser.getAttributeValue(null, "name")
                    if (key != null) {
                        when (parser.name) {
                            "string" -> {
                                val text = if (parser.next() == org.xmlpull.v1.XmlPullParser.TEXT) parser.text else ""
                                editor.putString(key, text)
                            }
                            "int" -> editor.putInt(key, parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0)
                            "boolean" -> editor.putBoolean(key, parser.getAttributeValue(null, "value")?.toBoolean() ?: false)
                            "float" -> editor.putFloat(key, parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f)
                            "long" -> editor.putLong(key, parser.getAttributeValue(null, "value")?.toLongOrNull() ?: 0L)
                        }
                    }
                }
                event = parser.next()
            }
            editor.apply()
        } catch (e: Exception) {
        }
    }

    private fun restoreThumbnail(
        context: android.content.Context,
        notebookId: Long,
        isInfinite: Boolean,
        pdfPath: String?,
        isImported: Boolean
    ) {
        try {
            val filesDir = context.filesDir
            if (isInfinite) {
                val result = DrawingRepository(context).loadInfinite(notebookId)
                val sm = StrokeManager()
                sm.imagesDir = java.io.File(filesDir, "images").apply { mkdirs() }
                sm.loadDecodedData(result.pages)
                val strokes = sm.knownStrokesForPage(0)
                if (strokes.isEmpty()) return

                val bounds = android.graphics.RectF()
                var first = true
                val b = android.graphics.RectF()
                for (s in strokes) {
                    s.path.computeBounds(b, true)
                    if (first) { bounds.set(b); first = false } else bounds.union(b)
                }
                bounds.inset(-40f, -40f)
                if (bounds.width() < 1f || bounds.height() < 1f) return

                val w = ThumbnailGenerator.WIDTH_PX
                val scale = minOf(w / bounds.width(), w / bounds.height())
                val h = (bounds.height() * scale).toInt().coerceIn(1, 2400)
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.save()
                canvas.scale(scale, scale)
                canvas.translate(-bounds.left, -bounds.top)
                sm.drawPageStrokes(0, canvas, 1f, 1f, ghostSelected = false)
                canvas.restore()
                ThumbnailGenerator.thumbFile(context, notebookId).outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
                }
                bmp.recycle()
            } else {
                val pdf = pdfPath?.let { java.io.File(it) }?.takeIf { it.exists() } ?: return
                val dm = context.resources.displayMetrics
                val inkWidth = minOf(dm.widthPixels, dm.heightPixels).toFloat()
                val sm = StrokeManager()
                sm.imagesDir = java.io.File(filesDir, "images").apply { mkdirs() }
                sm.loadDecodedData(DrawingRepository(context).load(notebookId, inkWidth, inkWidth).pages)
                ThumbnailGenerator.generate(
                    context,
                    notebookId,
                    pdf,
                    sm,
                    restorePageSizes(pdf, sm.allPagesWithData(), inkWidth),
                    isImported = isImported,
                    lastUsedPage = 0
                )
            }
        } catch (e: Exception) {

        }
    }

    private fun restorePageSizes(
        file: java.io.File,
        pagesWithInk: Set<Int>,
        inkWidth: Float
    ): List<Pair<Float, Float>> {
        val default = Pair(inkWidth, inkWidth * 1.414f)
        var pfd: android.os.ParcelFileDescriptor? = null
        var renderer: android.graphics.pdf.PdfRenderer? = null
        return try {
            pfd = android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            val r = android.graphics.pdf.PdfRenderer(pfd)
            renderer = r
            (0 until r.pageCount).map { i ->
                if (i in pagesWithInk) {
                    val page = r.openPage(i)
                    val size = Pair(inkWidth, inkWidth * page.height / page.width)
                    page.close()
                    size
                } else default
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    suspend fun getDrawingForPage(notebookId: Long, pageNumber: Int): Drawing? = withContext(Dispatchers.IO) {
        notesDao.getDrawingForPage(notebookId, pageNumber)
    }

    suspend fun insertOrUpdateDrawing(drawing: Drawing) = withContext(Dispatchers.IO) {
        notesDao.insertOrUpdateDrawing(drawing)
    }

    suspend fun shiftPageNumbers(notebookId: Long, startPage: Int) = withContext(Dispatchers.IO) {
        notesDao.shiftPageNumbers(notebookId, startPage)
    }

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

    suspend fun createWhiteboard(title: String, folderId: Long): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        notesDao.insertNotebook(
            Notebook(title = title, folderId = folderId, createdAt = now, lastModified = now, documentType = DocumentType.INFINITE)
        )
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

        context?.let { SyncFolderManager.removeNotebook(it, notebook.id) }
    }

    private suspend fun descendantFolderIds(rootId: Long): List<Long> {
        val out = mutableListOf<Long>()

        val visited = HashSet<Long>()
        suspend fun walk(parentId: Long, depth: Int) {
            if (depth > 64 || !visited.add(parentId)) return
            for (f in notesDao.getFoldersByParent(parentId)) {
                out.add(f.id)
                walk(f.id, depth + 1)
            }
        }
        walk(rootId, 0)
        return out
    }

    private suspend fun notebooksInSubtree(folderId: Long, includeBinned: Boolean): List<Notebook> {
        val ids = descendantFolderIds(folderId) + folderId
        return ids.flatMap { id ->
            if (includeBinned) notesDao.getAllNotebooksInFolder(id)
            else notesDao.getNotebooksInFolder(id)
        }
    }

    suspend fun deleteFolder(folder: Folder) = withContext(Dispatchers.IO) {

        for (nb in notebooksInSubtree(folder.id, includeBinned = true)) {
            deleteNotebook(nb)
        }
        for (fid in descendantFolderIds(folder.id) + folder.id) {
            notesDao.getFolderById(fid)?.let { notesDao.deleteFolder(it) }
        }
    }

    suspend fun moveToBin(notebook: Notebook) = withContext(Dispatchers.IO) {
        notesDao.updateNotebook(notebook.copy(inBin = true, deletedAt = System.currentTimeMillis()))
    }

    suspend fun moveToBin(folder: Folder) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        notesDao.updateFolder(folder.copy(inBin = true, deletedAt = now))
        for (fid in descendantFolderIds(folder.id)) {
            notesDao.getFolderById(fid)?.let { notesDao.updateFolder(it.copy(inBin = true, deletedAt = now)) }
        }
        notebooksInSubtree(folder.id, includeBinned = false).forEach {
            notesDao.updateNotebook(it.copy(inBin = true, deletedAt = now))
        }
    }

    suspend fun restoreFromBin(notebook: Notebook) = withContext(Dispatchers.IO) {

        unbinBinnedAncestors(notebook.folderId)
        notesDao.updateNotebook(notebook.copy(inBin = false, deletedAt = 0))
    }

    private suspend fun restoreSubtree(folder: Folder) {
        notesDao.updateFolder(folder.copy(inBin = false, deletedAt = 0))
        for (fid in descendantFolderIds(folder.id)) {
            notesDao.getFolderById(fid)?.let { notesDao.updateFolder(it.copy(inBin = false, deletedAt = 0)) }
        }
        notebooksInSubtree(folder.id, includeBinned = true).forEach {
            notesDao.updateNotebook(it.copy(inBin = false, deletedAt = 0))
        }
    }

    suspend fun restoreFromBin(folder: Folder) = withContext(Dispatchers.IO) {

        unbinBinnedAncestors(folder.parentId)
        restoreSubtree(folder)
    }

    private suspend fun unbinBinnedAncestors(startFolderId: Long) {
        var cursor = startFolderId
        var guard = 0
        while (cursor != 0L && guard++ < 64) {
            val parent = notesDao.getFolderById(cursor) ?: break
            if (!parent.inBin) break
            notesDao.updateFolder(parent.copy(inBin = false, deletedAt = 0))
            cursor = parent.parentId
        }
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

    suspend fun clearBin() = withContext(Dispatchers.IO) {
        for (folder in notesDao.getBinFolders()) {
            deleteFolder(folder)
        }
        for (notebook in notesDao.getBinNotebooks()) {
            deleteNotebook(notebook)
        }
    }

    suspend fun updateFolder(folder: Folder) = withContext(Dispatchers.IO) {
        notesDao.updateFolder(folder)
    }

    suspend fun updateNotebook(notebook: Notebook) = withContext(Dispatchers.IO) {
        notesDao.updateNotebook(notebook)
    }
}