package com.lochan.octopusnotes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

import androidx.compose.ui.platform.ComposeView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

data class PageSize(val name: String, val width: Int, val height: Int)

data class TemplateSettings(
    val type: String,
    val bgColor: ComposeColor,
    val density: Float,
    val brightness: Float,
    val thickness: Float,
    val lineColor: ComposeColor,
    val pageSize: PageSize,
    val isCustom: Boolean,
    val customImgUri: Uri?
)

class MainActivity : AppCompatActivity(), NotebookAdapter.OnItemInteractionListener {
    private lateinit var dataManager: DataManager
    private lateinit var adapter: NotebookAdapter
    private val displayItems = mutableListOf<DisplayItem>()

    private val folderPath = mutableListOf<Folder>()
    private val currentFolderId: Long get() = folderPath.lastOrNull()?.id ?: 0L
    private var searchQuery: String = ""
    private var favoritesMode = false
    private var binMode = false

    private var tagFilter: String? = null

    private var suppressTagFilterClear = false

    private lateinit var notebooksRecyclerView: AutofitRecyclerView
    private lateinit var emptyViewTextView: TextView
    private lateinit var searchInput: EditText
    private lateinit var breadcrumbBar: android.widget.LinearLayout
    private lateinit var addNotebookFab: ExtendedFloatingActionButton
    private lateinit var selectionDock: View
    private lateinit var binClearDock: View
    private lateinit var sortLabel: TextView

    private val selectedFolderIds = linkedSetOf<Long>()
    private val selectedNotebookIds = linkedSetOf<Long>()
    private var selectionMode = false

    private val customImageUriState = mutableStateOf<Uri?>(null)
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->

        customImageUriState.value = uri?.let { PageTemplate.copyTemplateImage(this, it) } ?: uri
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                selectionMode -> exitSelectionMode()
                searchInput.text.isNotEmpty() -> searchInput.setText("")
                tagFilter != null -> { clearTagFilter(); loadDataFromDatabase() }
                folderPath.isNotEmpty() -> {
                    folderPath.removeAt(folderPath.lastIndex)
                    loadDataFromDatabase()
                }
            }
        }
    }

    private val importPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val displayName = queryDisplayName(uri) ?: "Imported PDF"
        val title = displayName.removeSuffix(".pdf").ifBlank { "Imported PDF" }
        val total = queryFileSize(uri)

        var job: kotlinx.coroutines.Job? = null
        val progress = ProgressDialogController(this, getString(R.string.importing_pdf)) { job?.cancel() }
        progress.show()

        job = lifecycleScope.launch {
            var createdId: Long? = null
            try {
                val newId = withContext(Dispatchers.IO) {
                    val id = dataManager.createNotebookWithPdf(title, currentFolderId, null)
                    createdId = id
                    val dest = java.io.File(filesDir, "pdf_$id.pdf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output ->
                            copyWithProgress(input, output, total, { progress.isCancelled }) { pct ->
                                runOnUiThread { if (!progress.isCancelled) progress.setProgress(pct, 100) }
                            }
                        }
                    }
                    dataManager.setNotebookPdfPath(id, dest.absolutePath)

                    ThumbnailGenerator.generate(this@MainActivity, id, dest, isImported = true)
                    id
                }
                progress.dismiss()
                loadDataFromDatabase()
                openDrawing(newId)
            } catch (c: kotlinx.coroutines.CancellationException) {

                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    createdId?.let { id ->
                        java.io.File(filesDir, "pdf_$id.pdf").delete()
                        dataManager.getNotebook(id)?.let { dataManager.deleteNotebook(it, this@MainActivity) }
                    }
                }
                progress.dismiss()
                loadDataFromDatabase()
            } catch (t: Throwable) {
                progress.dismiss()
                android.widget.Toast.makeText(this@MainActivity, "Import failed", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun queryFileSize(uri: android.net.Uri): Long {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        } catch (_: Exception) {}
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) { -1L }
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        shouldCancel: () -> Boolean,
        onPercent: (Int) -> Unit
    ) {
        val buf = ByteArray(64 * 1024)
        var copied = 0L
        var lastPct = -1
        while (true) {
            if (shouldCancel()) throw kotlinx.coroutines.CancellationException("import cancelled")
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            copied += n
            if (total > 0) {
                val pct = ((copied * 100) / total).toInt().coerceIn(0, 100)
                if (pct != lastPct) { lastPct = pct; onPercent(pct) }
            }
        }
    }

    private fun createProgressDialog(message: String): androidx.appcompat.app.AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_indexing, null)
        view.findViewById<TextView>(R.id.indexingText).text = message
        view.findViewById<android.widget.ProgressBar>(R.id.indexingBar).isIndeterminate = true
        return MaterialAlertDialogBuilder(this).setView(view).setCancelable(false).create()
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment?.substringAfterLast('/')
        }
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveDefaultTemplate(settings: TemplateSettings) {
        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("type", settings.type)
            put("bgColor", settings.bgColor.toArgb())
            put("density", settings.density)
            put("brightness", settings.brightness)
            put("thickness", settings.thickness)
            put("lineColor", settings.lineColor.toArgb())
            put("pageSizeName", settings.pageSize.name)
            put("pageSizeWidth", settings.pageSize.width)
            put("pageSizeHeight", settings.pageSize.height)
            put("isCustom", settings.isCustom)
            put("customUri", settings.customImgUri?.toString())
        }
        prefs.edit().putString("DEFAULT_TEMPLATE", json.toString()).apply()
    }

    private fun loadDefaultTemplate(): TemplateSettings? {
        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("DEFAULT_TEMPLATE", null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            TemplateSettings(
                type = json.getString("type"),
                bgColor = ComposeColor(json.getInt("bgColor")),
                density = json.getDouble("density").toFloat(),
                brightness = json.getDouble("brightness").toFloat(),
                thickness = json.getDouble("thickness").toFloat(),
                lineColor = ComposeColor(json.getInt("lineColor")),
                pageSize = PageSize(
                    json.getString("pageSizeName"),
                    json.getInt("pageSizeWidth"),
                    json.getInt("pageSizeHeight")
                ),
                isCustom = json.getBoolean("isCustom"),
                customImgUri = json.optString("customUri", null)?.let { Uri.parse(it) }
            )
        } catch (e: Exception) {
            null
        }
    }

    private var currentViewMode = "GRID"

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            applyViewModePreference()
            loadDataFromDatabase()
            refreshEditedThumbnail()

            lifecycleScope.launch {
                DrawingActivity.lastSaveJob?.join()
                InfiniteCanvasActivity.lastSaveJob?.join()
                withContext(Dispatchers.IO) {
                    SyncFolderManager.mirrorAll(this@MainActivity, dataManager.getAllNotebooksIncludingBin())
                }
                refreshSyncBanner()
            }
        }
    }

    private fun refreshSyncBanner() {
        val banner = findViewById<View>(R.id.syncFolderBanner) ?: return
        if (!SyncFolderManager.isMissing(this)) {
            banner.visibility = View.GONE
            SyncFolderManager.clearMissingNotified(this)
        } else if (!SyncFolderManager.wasMissingNotified(this)) {
            banner.visibility = View.VISIBLE
            SyncFolderManager.markMissingNotified(this)
        }

    }

    private var editedNotebookId: Long
        get() = getSharedPreferences("notebook_state", Context.MODE_PRIVATE)
            .getLong("pending_thumb_id", -1L)
        set(value) = getSharedPreferences("notebook_state", Context.MODE_PRIVATE)
            .edit().putLong("pending_thumb_id", value).apply()

    private fun openDrawing(notebookId: Long, title: String? = null) {
        editedNotebookId = notebookId

        if (TabSession.isEnabled(this)) TabSession.addOrFocus(this, notebookId)
        startActivity(Intent(this, DrawingActivity::class.java).apply {
            putExtra("NOTEBOOK_ID", notebookId)
            if (title != null) putExtra("NOTEBOOK_TITLE", title)
        })
    }

    private fun openInfiniteCanvas(notebookId: Long, title: String? = null) {
        editedNotebookId = notebookId
        if (TabSession.isEnabled(this)) TabSession.addOrFocus(this, notebookId)
        startActivity(Intent(this, InfiniteCanvasActivity::class.java).apply {
            putExtra("NOTEBOOK_ID", notebookId)
            if (title != null) putExtra("NOTEBOOK_TITLE", title)
        })
    }

    private fun refreshEditedThumbnail() {
        val id = editedNotebookId
        if (id < 0) return
        editedNotebookId = -1L
        lifecycleScope.launch {

            DrawingActivity.lastSaveJob?.join()
            InfiniteCanvasActivity.lastSaveJob?.join()
            val isInfinite = dataManager.getNotebook(id)?.documentType == DocumentType.INFINITE
            val ok = withContext(Dispatchers.IO) {
                if (isInfinite) writeInfiniteThumbnail(id) else writeHomeThumbnail(id)
            }

            if (ok && ::adapter.isInitialized) adapter.notifyDataSetChanged()
        }
    }

    private suspend fun writeHomeThumbnail(notebookId: Long): Boolean {
        val notebook = dataManager.getNotebook(notebookId) ?: return false
        val file = notebook.pdfPath?.let { java.io.File(it) }?.takeIf { it.exists() } ?: return false

        val inkWidth = resources.displayMetrics.let {
            minOf(it.widthPixels, it.heightPixels).toFloat()
        }
        val strokeManager = StrokeManager()
        strokeManager.imagesDir = java.io.File(filesDir, "images").apply { mkdirs() }
        strokeManager.loadDecodedData(DrawingRepository(this).load(notebookId, inkWidth, inkWidth).pages)

        val isImported = PageTemplate.fromJson(
            getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
                .getString("template_$notebookId", null)
        ) == null
        val lastUsedPage = getSharedPreferences("notebook_state", Context.MODE_PRIVATE)
            .getInt("last_page_$notebookId", 0)

        return ThumbnailGenerator.generate(
            this,
            notebookId,
            file,
            strokeManager,
            strokePageSizes(file, strokeManager.allPagesWithData(), inkWidth),
            isImported = isImported,
            lastUsedPage = lastUsedPage
        )
    }

    private suspend fun writeInfiniteThumbnail(notebookId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val notebook = dataManager.getNotebook(notebookId) ?: return@withContext false
            if (notebook.documentType != DocumentType.INFINITE) return@withContext false
            val result = DrawingRepository(this@MainActivity).loadInfinite(notebookId)
            val sm = StrokeManager()
            sm.imagesDir = java.io.File(filesDir, "images").apply { mkdirs() }
            sm.loadDecodedData(result.pages)
            val strokes = sm.knownStrokesForPage(0)
            if (strokes.isEmpty()) return@withContext false

            val bounds = android.graphics.RectF()
            var first = true
            val b = android.graphics.RectF()
            for (s in strokes) {
                s.path.computeBounds(b, true)
                if (first) { bounds.set(b); first = false } else bounds.union(b)
            }
            bounds.inset(-40f, -40f)
            if (bounds.width() < 1f || bounds.height() < 1f) return@withContext false

            val w = ThumbnailGenerator.WIDTH_PX
            val scale = minOf(w / bounds.width(), w / bounds.height())
            val h = (bounds.height() * scale).toInt().coerceIn(1, 2400)
            val bmp = try {
                android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                return@withContext false
            }
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.save()
            canvas.scale(scale, scale)
            canvas.translate(-bounds.left, -bounds.top)

            sm.drawPageStrokes(0, canvas, 1f, 1f, ghostSelected = false)
            canvas.restore()
            val ok = try {
                ThumbnailGenerator.thumbFile(this@MainActivity, notebookId).outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
                }
            } catch (e: Exception) {
                false
            }
            bmp.recycle()
            ok
        }

    private fun strokePageSizes(
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

    private fun applyViewModePreference() {
        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("home_view_mode", "GRID") ?: "GRID"
        val cols = prefs.getInt("home_columns", 0)

        if (mode != currentViewMode) {
            currentViewMode = mode
            notebooksRecyclerView.setListMode(mode == "LIST")

            adapter = NotebookAdapter(displayItems, this, lifecycleScope, mode)
            notebooksRecyclerView.adapter = adapter
        }

        if (mode == "GRID") {
            notebooksRecyclerView.setColumnOverride(cols)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_completed", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val notesDao = AppDatabase.getDatabase(this).notesDao()
        dataManager = DataManager(notesDao, cacheDir, filesDir)
        lifecycleScope.launch { dataManager.cleanupBin() }
        setupViews()
        setupEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        currentViewMode = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
            .getString("home_view_mode", "GRID") ?: "GRID"
        notebooksRecyclerView.setListMode(currentViewMode == "LIST")
        adapter = NotebookAdapter(displayItems, this, lifecycleScope, currentViewMode)
        notebooksRecyclerView.adapter = adapter
        setupDragAndDrop()
        applyViewModePreference()

        tagFilter = savedInstanceState?.getString("tag_filter")
        loadDataFromDatabase()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                SyncFolderManager.mirrorAll(this@MainActivity, dataManager.getAllNotebooksIncludingBin())
            }
        }

        handleIncomingPdf(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingPdf(it) }
    }

    private fun handleIncomingPdf(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW && intent.data != null && intent.type == "application/pdf") {
            importPdfFromUri(intent.data!!)
        }
    }

    private fun importPdfFromUri(uri: Uri) {
        val displayName = queryDisplayName(uri) ?: "Imported PDF"
        val title = displayName.removeSuffix(".pdf").ifBlank { "Imported PDF" }
        val total = queryFileSize(uri)

        var job: kotlinx.coroutines.Job? = null
        val progress = ProgressDialogController(this, getString(R.string.importing_pdf)) { job?.cancel() }
        progress.show()

        job = lifecycleScope.launch {
            var createdId: Long? = null

            suspend fun rollbackImport() {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    createdId?.let { id ->
                        java.io.File(filesDir, "pdf_$id.pdf").delete()
                        dataManager.getNotebook(id)?.let { dataManager.deleteNotebook(it, this@MainActivity) }
                    }
                }
            }

            try {
                val newId = withContext(Dispatchers.IO) {
                    val id = dataManager.createNotebookWithPdf(title, currentFolderId, null)
                    createdId = id
                    val dest = java.io.File(filesDir, "pdf_$id.pdf")

                    val input = contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Cannot read $uri")
                    input.use { inputStream ->
                        dest.outputStream().use { output ->
                            copyWithProgress(inputStream, output, total, { progress.isCancelled }) { pct ->
                                runOnUiThread { if (!progress.isCancelled) progress.setProgress(pct, 100) }
                            }
                        }
                    }
                    dataManager.setNotebookPdfPath(id, dest.absolutePath)

                    ThumbnailGenerator.generate(this@MainActivity, id, dest, isImported = true)
                    id
                }
                progress.dismiss()
                loadDataFromDatabase()
                openDrawing(newId)
            } catch (c: kotlinx.coroutines.CancellationException) {

                rollbackImport()
                progress.dismiss()
                loadDataFromDatabase()
            } catch (t: Throwable) {

                rollbackImport()
                progress.dismiss()
                android.widget.Toast.makeText(this@MainActivity, "Import failed", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onNotebookClick(notebook: Notebook) {
        if (binMode) {
            android.widget.Toast.makeText(this, "Restore item to open it", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        if (notebook.documentType == DocumentType.INFINITE) {
            openInfiniteCanvas(notebook.id, notebook.title)
        } else {
            openDrawing(notebook.id, notebook.title)
        }
    }

    private fun setupViews() {
        notebooksRecyclerView = findViewById(R.id.notebooksRecyclerView)
        emptyViewTextView = findViewById(R.id.emptyViewTextView)
        searchInput = findViewById(R.id.searchInput)
        breadcrumbBar = findViewById(R.id.breadcrumbBar)
        addNotebookFab = findViewById(R.id.addNotebookFab)
        selectionDock = findViewById(R.id.selectionDock)
        binClearDock = findViewById(R.id.binClearDock)
        findViewById<View>(R.id.binClearAction).setOnClickListener { confirmClearBin() }

        searchInput.addTextChangedListener {
            searchQuery = it?.toString().orEmpty()
            loadDataFromDatabase()
        }
        addNotebookFab.setOnClickListener { showCreateDialog() }

        addNotebookFab.setOnLongClickListener {
            createInstantNote()
            true
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val syncBanner = findViewById<View>(R.id.syncFolderBanner)
        syncBanner.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.syncBannerFix).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.syncBannerClose).setOnClickListener {
            syncBanner.visibility = View.GONE
        }

        findViewById<com.google.android.material.chip.ChipGroup>(R.id.filterChips)
            .setOnCheckedStateChangeListener { _, checkedIds ->
                favoritesMode = checkedIds.contains(R.id.chipFavorites)
                val wasInBin = binMode
                binMode = checkedIds.contains(R.id.chipBin)
                if (binMode) {
                    addNotebookFab.hide()
                } else {

                    if (wasInBin && selectionMode) exitSelectionMode()
                    else if (!selectionMode) addNotebookFab.show()
                }

                if (!suppressTagFilterClear) {
                    tagFilter = null
                    collapseTagRow()
                }
                loadDataFromDatabase()
            }

        findViewById<View>(R.id.chipAll).setOnClickListener {
            if (tagFilter != null) {
                tagFilter = null
                collapseTagRow()
                loadDataFromDatabase()
            }
        }

        findViewById<View>(R.id.chipTags).setOnClickListener { toggleTagRow() }

        findViewById<ImageButton>(R.id.sortButton).setOnClickListener { showSortPopup(it) }
        sortLabel = findViewById(R.id.sortLabel)
        updateSortLabel()

        findViewById<View>(R.id.dockClose).setOnClickListener { exitSelectionMode() }
        findViewById<View>(R.id.dockMove).setOnClickListener { moveSelected() }
        findViewById<View>(R.id.dockLock).setOnClickListener {
            android.widget.Toast.makeText(this, "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.dockDelete).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.dockRestore).setOnClickListener { restoreSelected() }
        findViewById<View>(R.id.dockDeletePerm).setOnClickListener { deleteSelectedPermanently() }
    }

    private fun loadDataFromDatabase() {
        syncTagChip()
        syncTagChips()
        lifecycleScope.launch {
            val data = when {
                searchQuery.isNotBlank() -> dataManager.searchAll(searchQuery)
                tagFilter != null -> dataManager.getNotebooksByTag(tagFilter!!)
                binMode -> dataManager.getBinData()
                favoritesMode -> dataManager.getDataForScreen(-1L)
                else -> dataManager.getDataForScreen(currentFolderId)
            }
            displayItems.clear()
            displayItems.addAll(sortItems(data.folders, data.notebooks))
            adapter.notifyDataSetChanged()

            if (searchQuery.isBlank()) notebooksRecyclerView.scheduleLayoutAnimation()
            updateEmptyViewVisibility()
            updateBreadcrumb()
            updateBinClearDock()
            updateSortLabel()
            onBackPressedCallback.isEnabled = selectionMode || folderPath.isNotEmpty() || searchQuery.isNotBlank()
        }
    }

    private fun updateSortLabel() {
        if (!::sortLabel.isInitialized) return
        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
        val field = prefs.getString("sort_field", "MODIFIED") ?: "MODIFIED"
        val asc = prefs.getBoolean("sort_asc", false)
        val label = when (field) {
            "MODIFIED" -> "Modified"
            "OPENED" -> "Opened"
            "CREATED" -> "Created"
            "NAME" -> "Name"
            else -> "Modified"
        }
        val arrow = if (asc) "↑" else "↓"
        sortLabel.text = "$label $arrow"
    }

    private fun sortItems(folders: List<Folder>, notebooks: List<Notebook>): List<DisplayItem> {
        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
        val field = prefs.getString("sort_field", "MODIFIED") ?: "MODIFIED"
        val asc = prefs.getBoolean("sort_asc", false)
        val keepTop = prefs.getBoolean("keep_folders_top", true)

        val folderItems = folders.map { DisplayItem.FolderItem(it) }
        val notebookItems = notebooks.map { DisplayItem.NotebookItem(it) }

        val nameOf: (DisplayItem) -> String = {
            when (it) {
                is DisplayItem.FolderItem -> it.folder.name
                is DisplayItem.NotebookItem -> it.notebook.title
            }.lowercase()
        }
        val timeOf: (DisplayItem) -> Long = {
            when (it) {
                is DisplayItem.FolderItem -> it.folder.createdAt
                is DisplayItem.NotebookItem -> when (field) {
                    "CREATED" -> it.notebook.createdAt
                    "OPENED" -> it.notebook.lastOpened
                    else -> it.notebook.lastModified
                }
            }
        }
        val base: Comparator<DisplayItem> = if (field == "NAME") compareBy(nameOf) else compareBy(timeOf)
        val cmp = if (asc) base else base.reversed()

        return if (keepTop) {
            folderItems.sortedWith(cmp) + notebookItems.sortedWith(cmp)
        } else {
            (folderItems + notebookItems).sortedWith(cmp)
        }
    }

    private fun showSortPopup(anchor: View) {
        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
        val content = layoutInflater.inflate(R.layout.popup_sort, null)
        val popup = android.widget.PopupWindow(
            content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_popup_menu))
        popup.elevation = 8f * resources.displayMetrics.density

        val primary = com.google.android.material.color.MaterialColors.getColor(content, com.google.android.material.R.attr.colorPrimary, 0)
        val normal = com.google.android.material.color.MaterialColors.getColor(content, com.google.android.material.R.attr.colorOnSurface, 0)

        val rows = mapOf(
            "MODIFIED" to content.findViewById<TextView>(R.id.sortModified),
            "OPENED" to content.findViewById<TextView>(R.id.sortOpened),
            "CREATED" to content.findViewById<TextView>(R.id.sortCreated),
            "NAME" to content.findViewById<TextView>(R.id.sortName)
        )
        val ascBtn = content.findViewById<ImageButton>(R.id.sortAsc)
        val descBtn = content.findViewById<ImageButton>(R.id.sortDesc)

        fun refresh() {
            val field = prefs.getString("sort_field", "MODIFIED")
            rows.forEach { (k, tv) -> tv.setTextColor(if (k == field) primary else normal) }
            val asc = prefs.getBoolean("sort_asc", false)
            ascBtn.setBackgroundResource(if (asc) R.drawable.bg_select_circle else 0)
            descBtn.setBackgroundResource(if (!asc) R.drawable.bg_select_circle else 0)
            ascBtn.imageTintList = android.content.res.ColorStateList.valueOf(if (asc) android.graphics.Color.WHITE else normal)
            descBtn.imageTintList = android.content.res.ColorStateList.valueOf(if (!asc) android.graphics.Color.WHITE else normal)
        }
        refresh()

        rows.forEach { (k, tv) ->
            tv.setOnClickListener {
                prefs.edit().putString("sort_field", k).apply(); refresh(); loadDataFromDatabase()
            }
        }
        ascBtn.setOnClickListener { prefs.edit().putBoolean("sort_asc", true).apply(); refresh(); loadDataFromDatabase() }
        descBtn.setOnClickListener { prefs.edit().putBoolean("sort_asc", false).apply(); refresh(); loadDataFromDatabase() }

        val keep = content.findViewById<android.widget.CheckBox>(R.id.sortKeepFolders)
        keep.isChecked = prefs.getBoolean("keep_folders_top", true)
        keep.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("keep_folders_top", checked).apply(); loadDataFromDatabase()
        }

        popup.showAsDropDown(anchor, 0, 8, android.view.Gravity.END)
    }

    private fun updateBreadcrumb() {
        breadcrumbBar.removeAllViews()
        if (searchQuery.isNotBlank()) {
            breadcrumbBar.addView(makeCrumb("Search results", isLast = true, onClick = null))
            return
        }
        if (binMode) {
            breadcrumbBar.addView(makeCrumb("Bin", isLast = true, onClick = null))
            return
        }
        if (tagFilter != null) {
            breadcrumbBar.addView(makeCrumb(
                "Tag · ${NotebookTags.nameOf(this, tagFilter) ?: "Tagged"}", isLast = true, onClick = null
            ))
            return
        }
        if (favoritesMode) {
            breadcrumbBar.addView(makeCrumb("Favorites", isLast = true, onClick = null))
            return
        }
        breadcrumbBar.addView(makeCrumb("All Notes", isLast = folderPath.isEmpty()) {
            folderPath.clear(); loadDataFromDatabase()
        })
        folderPath.forEachIndexed { index, folder ->
            breadcrumbBar.addView(makeSeparator())
            breadcrumbBar.addView(makeCrumb(folder.name, isLast = index == folderPath.lastIndex) {
                while (folderPath.size > index + 1) folderPath.removeAt(folderPath.lastIndex)
                loadDataFromDatabase()
            })
        }
    }

    private fun makeCrumb(label: String, isLast: Boolean, onClick: (() -> Unit)?): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val pad = (6 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val attr = if (isLast) android.R.attr.textColorPrimary
            else com.google.android.material.R.attr.colorPrimary
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, attr))
            if (isLast) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (!isLast && onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
    }

    private fun makeSeparator(): TextView {
        return TextView(this).apply {
            text = "›"
            textSize = 16f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
    }

    private fun confirmClearBin() {
        val count = displayItems.size
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear bin?")
            .setMessage(
                if (count == 1) "Permanently delete 1 item from the bin? This can't be undone."
                else "Permanently delete all $count items from the bin? This can't be undone."
            )
            .setPositiveButton("Clear all") { _, _ ->
                lifecycleScope.launch {
                    dataManager.clearBin()
                    android.widget.Toast.makeText(this@MainActivity, "Bin cleared", android.widget.Toast.LENGTH_SHORT).show()
                    loadDataFromDatabase()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateBinClearDock() {
        if (!::binClearDock.isInitialized) return
        findViewById<TextView>(R.id.binClearCount).text = displayItems.size.toString()
        val show = binMode && displayItems.isNotEmpty()
        if (show && binClearDock.visibility != View.VISIBLE) {
            binClearDock.visibility = View.VISIBLE
            binClearDock.post {
                binClearDock.translationY = binClearDock.height.toFloat()
                binClearDock.animate().translationY(0f).setDuration(220).start()
            }
        } else if (!show && binClearDock.visibility == View.VISIBLE) {
            binClearDock.animate().translationY(binClearDock.height.toFloat()).setDuration(180)
                .withEndAction {
                    binClearDock.visibility = View.GONE
                    binClearDock.translationY = 0f
                }.start()
        }
    }

    override fun onFolderClick(folder: Folder) {
        if (binMode) {
            android.widget.Toast.makeText(this, "Restore item to open it", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val path = buildFolderPath(folder)
            folderPath.clear()
            folderPath.addAll(path)

            if (searchInput.text.isNotEmpty()) searchInput.setText("")
            if (tagFilter != null) clearTagFilter()
            loadDataFromDatabase()
        }
    }

    private suspend fun buildFolderPath(folder: Folder): List<Folder> {
        val chain = ArrayDeque<Folder>()
        var current: Folder? = folder
        val guard = HashSet<Long>()
        while (current != null && current.id != 0L && guard.add(current.id)) {
            chain.addFirst(current)
            current = if (current.parentId == 0L) null else dataManager.getFolder(current.parentId)
        }
        return chain.toList()
    }

    override fun onItemLongPress(item: DisplayItem) {
        if (!selectionMode) enterSelectionMode()
        setSelected(item, true)
        adapter.animateTickIn(item)
        adapter.notifyDataSetChanged()
        updateDock()
    }

    override fun onSelectionLongPress(item: DisplayItem): Boolean {
        if (binMode) {
            toggleSelection(item)
            return true
        }
        return false
    }

    private fun showBinItemOptions(item: DisplayItem) {
        val title = when (item) {
            is DisplayItem.FolderItem -> item.folder.name
            is DisplayItem.NotebookItem -> item.notebook.title
        }
        val options = arrayOf("Restore", "Delete permanently")
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch {
                        when (item) {
                            is DisplayItem.FolderItem -> dataManager.restoreFromBin(item.folder)
                            is DisplayItem.NotebookItem -> dataManager.restoreFromBin(item.notebook)
                        }
                        android.widget.Toast.makeText(this@MainActivity, "Item restored", android.widget.Toast.LENGTH_SHORT).show()
                        loadDataFromDatabase()
                    }
                    1 -> when (item) {
                        is DisplayItem.FolderItem -> confirmPermanentDeleteFolder(item.folder)
                        is DisplayItem.NotebookItem -> confirmPermanentDeleteNotebook(item.notebook)
                    }
                }
            }
            .show()
    }

    private fun confirmPermanentDeleteFolder(folder: Folder) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete permanently?")
            .setMessage("'${folder.name}' and its contents will be permanently deleted.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    dataManager.deleteFolder(folder)
                    android.widget.Toast.makeText(this@MainActivity, "Deleted permanently", android.widget.Toast.LENGTH_SHORT).show()
                    loadDataFromDatabase()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmPermanentDeleteNotebook(notebook: Notebook) {
        lifecycleScope.launch {
            val sizes = withContext(Dispatchers.IO) { dataManager.notebookSizeBreakdown(notebook) }
            val rows = mutableListOf<Pair<String, Long>>()
            if (sizes.pdfSize > 0) rows.add("PDF file" to sizes.pdfSize)
            if (sizes.drawingSize > 0) rows.add("Notes & strokes" to sizes.drawingSize)
            if (sizes.thumbnailSize > 0) rows.add("Thumbnail" to sizes.thumbnailSize)
            if (sizes.searchIndexSize > 0) rows.add("Search index" to sizes.searchIndexSize)
            if (sizes.outlineCacheSize > 0) rows.add("Outline cache" to sizes.outlineCacheSize)

            val density = resources.displayMetrics.density
            val pad = (20 * density).toInt()
            val padSmall = (8 * density).toInt()

            val container = android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, padSmall, pad, 0)
            }

            val msg = android.widget.TextView(this@MainActivity).apply {
                text = "'${notebook.title}' and all related data will be permanently deleted."
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface, 0))
                textSize = 14f
            }
            container.addView(msg)

            if (rows.isNotEmpty()) {
                val table = android.widget.TableLayout(this@MainActivity).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (14 * density).toInt() }
                    setColumnStretchable(0, true)
                }
                val variant = com.google.android.material.color.MaterialColors.getColor(
                    container, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
                val onSurface = com.google.android.material.color.MaterialColors.getColor(
                    container, com.google.android.material.R.attr.colorOnSurface, 0)

                for ((label, size) in rows) {
                    val row = android.widget.TableRow(this@MainActivity)
                    row.addView(android.widget.TextView(this@MainActivity).apply {
                        text = label; setTextColor(variant); textSize = 13f
                        setPadding(0, (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
                    })
                    row.addView(android.widget.TextView(this@MainActivity).apply {
                        text = formatFileSize(size); setTextColor(variant); textSize = 13f
                        gravity = android.view.Gravity.END
                        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                    })
                    table.addView(row)
                }

                val divider = android.view.View(this@MainActivity).apply {
                    layoutParams = android.widget.TableLayout.LayoutParams(
                        android.widget.TableLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt()
                    ).apply { topMargin = (2 * density).toInt(); bottomMargin = (2 * density).toInt() }
                    setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                        container, com.google.android.material.R.attr.colorOutlineVariant, 0))
                }
                table.addView(divider)

                val totalRow = android.widget.TableRow(this@MainActivity)
                totalRow.addView(android.widget.TextView(this@MainActivity).apply {
                    text = "Total"; setTextColor(onSurface); textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
                })
                totalRow.addView(android.widget.TextView(this@MainActivity).apply {
                    text = formatFileSize(sizes.total); setTextColor(onSurface); textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.END
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                })
                table.addView(totalRow)
                container.addView(table)
            }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Delete permanently?")
                .setView(container)
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        dataManager.deleteNotebook(notebook, this@MainActivity)
                        android.widget.Toast.makeText(this@MainActivity, "Deleted permanently", android.widget.Toast.LENGTH_SHORT).show()
                        loadDataFromDatabase()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    override fun isSelectionMode(): Boolean = selectionMode

    override fun isItemSelected(item: DisplayItem): Boolean = when (item) {
        is DisplayItem.FolderItem -> item.folder.id in selectedFolderIds
        is DisplayItem.NotebookItem -> item.notebook.id in selectedNotebookIds
    }

    override fun toggleSelection(item: DisplayItem) {
        val selecting = !isItemSelected(item)
        setSelected(item, selecting)

        if (selecting) adapter.animateTickIn(item)
        if (selectedFolderIds.isEmpty() && selectedNotebookIds.isEmpty()) {
            exitSelectionMode()
        } else {
            adapter.notifyDataSetChanged()
            updateDock()
        }
    }

    private fun setSelected(item: DisplayItem, selected: Boolean) {
        when (item) {
            is DisplayItem.FolderItem ->
                if (selected) selectedFolderIds.add(item.folder.id) else selectedFolderIds.remove(item.folder.id)
            is DisplayItem.NotebookItem ->
                if (selected) selectedNotebookIds.add(item.notebook.id) else selectedNotebookIds.remove(item.notebook.id)
        }
    }

    private fun enterSelectionMode() {
        selectionMode = true
        addNotebookFab.hide()

        if (binMode) binClearDock.visibility = View.GONE
        showDock(true)
        onBackPressedCallback.isEnabled = true
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedFolderIds.clear()
        selectedNotebookIds.clear()
        showDock(false)

        if (!binMode) addNotebookFab.show()
        updateBinClearDock()
        adapter.notifyDataSetChanged()
        onBackPressedCallback.isEnabled = folderPath.isNotEmpty() || searchQuery.isNotBlank()
    }

    private fun updateDock() {
        findViewById<TextView>(R.id.dockCount).text =
            (selectedFolderIds.size + selectedNotebookIds.size).toString()

        val bin = binMode
        findViewById<View>(R.id.dockMove).visibility = if (bin) View.GONE else View.VISIBLE
        findViewById<View>(R.id.dockLock).visibility = if (bin) View.GONE else View.VISIBLE
        findViewById<View>(R.id.dockDelete).visibility = if (bin) View.GONE else View.VISIBLE
        findViewById<View>(R.id.dockRestore).visibility = if (bin) View.VISIBLE else View.GONE
        findViewById<View>(R.id.dockDeletePerm).visibility = if (bin) View.VISIBLE else View.GONE
    }

    private fun showDock(show: Boolean) {
        if (show) {
            updateDock()
            selectionDock.visibility = View.VISIBLE
            selectionDock.post {
                selectionDock.translationY = selectionDock.height.toFloat()
                selectionDock.animate().translationY(0f).setDuration(220).start()
            }
        } else {
            selectionDock.animate().translationY(selectionDock.height.toFloat()).setDuration(180)
                .withEndAction {
                    selectionDock.visibility = View.GONE
                    selectionDock.translationY = 0f
                }.start()
        }
    }

    private fun deleteSelected() {
        val count = selectedFolderIds.size + selectedNotebookIds.size
        if (count == 0) return
        val nbIds = selectedNotebookIds.toList()
        val fIds = selectedFolderIds.toList()
        MaterialAlertDialogBuilder(this)
            .setTitle("Move $count item${if (count > 1) "s" else ""} to Bin?")
            .setMessage("Items in Bin will be permanently deleted after 30 days.")
            .setPositiveButton("Move to Bin") { _, _ ->
                lifecycleScope.launch {
                    nbIds.forEach { id ->
                        dataManager.getNotebook(id)?.let {
                            dataManager.moveToBin(it)
                        }
                    }
                    fIds.forEach { id -> dataManager.getFolder(id)?.let { dataManager.moveToBin(it) } }
                    exitSelectionMode()
                    loadDataFromDatabase()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreSelected() {
        val count = selectedFolderIds.size + selectedNotebookIds.size
        if (count == 0) return
        val nbIds = selectedNotebookIds.toList()
        val fIds = selectedFolderIds.toList()
        lifecycleScope.launch {
            nbIds.forEach { id -> dataManager.getNotebook(id)?.let { dataManager.restoreFromBin(it) } }
            fIds.forEach { id -> dataManager.getFolder(id)?.let { dataManager.restoreFromBin(it) } }
            android.widget.Toast.makeText(
                this@MainActivity,
                if (count == 1) "Item restored" else "$count items restored",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            exitSelectionMode()
            loadDataFromDatabase()
        }
    }

    private fun deleteSelectedPermanently() {
        val count = selectedFolderIds.size + selectedNotebookIds.size
        if (count == 0) return
        val nbIds = selectedNotebookIds.toList()
        val fIds = selectedFolderIds.toList()
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete $count item${if (count > 1) "s" else ""} permanently?")
            .setMessage(
                if (count == 1) "This item and its contents will be gone forever. This can't be undone."
                else "These $count items and their contents will be gone forever. This can't be undone."
            )
            .setPositiveButton("Delete permanently") { _, _ ->
                lifecycleScope.launch {
                    nbIds.forEach { id -> dataManager.getNotebook(id)?.let { dataManager.deleteNotebook(it, this@MainActivity) } }
                    fIds.forEach { id -> dataManager.getFolder(id)?.let { dataManager.deleteFolder(it) } }
                    android.widget.Toast.makeText(this@MainActivity, "Deleted permanently", android.widget.Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                    loadDataFromDatabase()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveSelected() {
        if (selectedFolderIds.isEmpty() && selectedNotebookIds.isEmpty()) return
        val nbIds = selectedNotebookIds.toList()
        val fIds = selectedFolderIds.toList()
        lifecycleScope.launch {
            val allFolders = AppDatabase.getDatabase(this@MainActivity).notesDao().getAllFolders()

            val destFolders = allFolders.filter { it.id !in selectedFolderIds }
            val options = (listOf("All Notes (root)") + destFolders.map { it.name }).toTypedArray()
            val destIds = listOf(0L) + destFolders.map { it.id }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Move to")
                .setItems(options) { _, which ->
                    val target = destIds[which]
                    lifecycleScope.launch {
                        nbIds.forEach { id -> dataManager.getNotebook(id)?.let { dataManager.updateNotebook(it.copy(folderId = target)) } }
                        fIds.forEach { id -> dataManager.getFolder(id)?.let { dataManager.updateFolder(it.copy(parentId = target)) } }
                        exitSelectionMode()
                        loadDataFromDatabase()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onNotebookMenu(notebook: Notebook, anchor: View) {
        if (binMode) {
            showBinItemOptions(DisplayItem.NotebookItem(notebook))
            return
        }
        val content = layoutInflater.inflate(R.layout.popup_notebook_menu, null)
        val popup = buildAnchoredPopup(content)
        content.findViewById<TextView>(R.id.popupTitle).text = notebook.title

        val popupMeta = content.findViewById<TextView>(R.id.popupMeta)
        popupMeta.text = buildString {
            if (notebook.isFavorite) append("Favorite")
            notebook.tagColorHex?.let { tag ->
                if (isNotEmpty()) append("  •  ")
                append(NotebookTags.nameOf(this@MainActivity, tag) ?: "Tagged")
            }
        }
        popupMeta.visibility = if (popupMeta.text.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        val headerTagColor = notebook.tagColorHex?.let { hex ->
            try { android.graphics.Color.parseColor(hex) } catch (e: IllegalArgumentException) { null }
        }
        content.findViewById<ImageView>(R.id.popupHeaderIcon).imageTintList =
            android.content.res.ColorStateList.valueOf(
                headerTagColor ?: com.google.android.material.color.MaterialColors.getColor(
                    content, com.google.android.material.R.attr.colorPrimary, 0
                )
            )
        content.findViewById<TextView>(R.id.bsFav).text =
            if (notebook.isFavorite) "Remove from Favorites" else "Add to Favorites"
        content.findViewById<View>(R.id.bsRename).setOnClickListener { popup.dismiss(); renameNotebook(notebook) }
        content.findViewById<View>(R.id.bsLock).setOnClickListener {
            popup.dismiss(); android.widget.Toast.makeText(this, "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
        }
        content.findViewById<View>(R.id.bsFav).setOnClickListener { popup.dismiss(); toggleNotebookFavorite(notebook) }
        content.findViewById<TextView>(R.id.bsTag).text =
            if (notebook.tagColorHex != null) "Change Tag" else "Add Tag"
        content.findViewById<View>(R.id.bsTag).setOnClickListener { popup.dismiss(); showTagPicker(anchor, notebook) }
        content.findViewById<View>(R.id.bsMove).setOnClickListener { popup.dismiss(); moveNotebook(notebook) }
        content.findViewById<View>(R.id.bsDuplicate).setOnClickListener { popup.dismiss(); duplicateNotebook(notebook) }
        content.findViewById<View>(R.id.bsDelete).setOnClickListener { popup.dismiss(); showDeleteNotebookDialog(notebook) }
        showAnchoredPopup(popup, anchor)
    }

    private fun buildAnchoredPopup(content: View): android.widget.PopupWindow {

        val maxWidth = (300 * resources.displayMetrics.density).toInt()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val width = minOf(content.measuredWidth, maxWidth)
        val popup = android.widget.PopupWindow(
            content,
            width,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_popup_menu))
        popup.elevation = 8f * resources.displayMetrics.density
        return popup
    }

    private fun showAnchoredPopup(popup: android.widget.PopupWindow, anchor: View) {
        val popupWidth = popup.width

        popup.contentView.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = popup.contentView.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)

        val display = android.graphics.Rect()
        anchor.getWindowVisibleDisplayFrame(display)
        val margin = (8 * resources.displayMetrics.density).toInt()
        val left = (loc[0] + (anchor.width - popupWidth) / 2)
            .coerceIn(display.left + margin, display.right - popupWidth - margin)
        val spaceBelow = display.bottom - (loc[1] + anchor.height) - margin
        val showAbove = popupHeight > spaceBelow && loc[1] - display.top > popupHeight + margin
        val top = if (showAbove) {
            loc[1] - popupHeight - margin
        } else {
            (loc[1] + anchor.height + margin).coerceAtMost(display.bottom - popupHeight - margin)
        }

        popup.showAtLocation(anchor, android.view.Gravity.TOP or android.view.Gravity.START, left, top)
    }

    private fun clearTagFilter() {
        tagFilter = null
    }

    private fun syncTagChip() {
        val chip = findViewById<com.google.android.material.chip.Chip>(R.id.chipTags)
        chip.chipIconTint = android.content.res.ColorStateList.valueOf(
            tagFilter?.let { android.graphics.Color.parseColor(it) }
                ?: com.google.android.material.color.MaterialColors.getColor(
                    chip, com.google.android.material.R.attr.colorControlNormal, 0
                )
        )
    }

    private fun toggleTagRow() {
        if (findViewById<View>(R.id.tagsScroll).visibility == View.VISIBLE) collapseTagRow()
        else expandTagRow()
    }

    private fun expandTagRow() {
        val scroll = findViewById<View>(R.id.tagsScroll)
        syncTagChips()
        if (scroll.visibility == View.VISIBLE) return
        scroll.alpha = 0f
        scroll.translationY = -dp(8).toFloat()
        scroll.visibility = View.VISIBLE
        scroll.animate()
            .alpha(1f).translationY(0f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        val group = findViewById<com.google.android.material.chip.ChipGroup>(R.id.tagChips)
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i)
            chip.alpha = 0f
            chip.scaleX = 0.7f
            chip.scaleY = 0.7f
            chip.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220L)
                .setStartDelay(40L + i * 30L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
                .start()
        }
    }

    private fun collapseTagRow() {
        val scroll = findViewById<View>(R.id.tagsScroll)
        if (scroll.visibility != View.VISIBLE) return
        scroll.animate()
            .alpha(0f).translationY(-dp(8).toFloat())
            .setDuration(150L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                scroll.visibility = View.GONE
                scroll.alpha = 1f
                scroll.translationY = 0f
            }
            .start()
    }

    private fun syncTagChips() {
        val group = findViewById<com.google.android.material.chip.ChipGroup>(R.id.tagChips)
        if (group.childCount == 0) {
            NotebookTags.COLORS.forEach { tag ->
                val chip = layoutInflater.inflate(R.layout.item_tag_chip, group, false)
                        as com.google.android.material.chip.Chip

                val tagColor = android.graphics.Color.parseColor(tag.hex)
                val onTagColor = if (isLightColor(tagColor))
                    android.graphics.Color.parseColor("#1F1F1F")
                else android.graphics.Color.WHITE
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(tagColor)
                chip.setTextColor(onTagColor)
                chip.checkedIconTint = android.content.res.ColorStateList.valueOf(onTagColor)
                chip.setOnClickListener {
                    pulseTagChip()
                    applyTagColor(tag.hex)
                }
                chip.setOnLongClickListener {
                    showRenameTagDialog(chip, tag, reopenPicker = false)
                    true
                }
                group.addView(chip)
            }
        }
        NotebookTags.COLORS.forEachIndexed { i, tag ->
            val chip = group.getChildAt(i) as? com.google.android.material.chip.Chip
                ?: return@forEachIndexed
            chip.text = NotebookTags.nameOf(this, tag.hex) ?: tag.name
            chip.isChecked = tagFilter?.equals(tag.hex, ignoreCase = true) == true
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val r = android.graphics.Color.red(color) / 255.0
        val g = android.graphics.Color.green(color) / 255.0
        val b = android.graphics.Color.blue(color) / 255.0
        return 0.299 * r + 0.587 * g + 0.114 * b >= 0.6
    }

    private fun pulseTagChip() {
        val chip = findViewById<View>(R.id.chipTags)
        chip.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                chip.animate().scaleX(1f).scaleY(1f).setDuration(200L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2f))
                    .start()
            }
            .start()
    }

    private fun showTagPicker(anchor: View, forNotebook: Notebook? = null, forFolder: Folder? = null) {
        val content = layoutInflater.inflate(R.layout.popup_tag_picker, null)
        val popup = buildAnchoredPopup(content)
        val activeColor = forNotebook?.tagColorHex ?: forFolder?.tagColorHex

        content.findViewById<TextView>(R.id.popupTitle).text = "Tag color"

        val removeRow = content.findViewById<TextView>(R.id.bsRemoveTag)
        removeRow.visibility = if (activeColor != null) View.VISIBLE else View.GONE
        removeRow.setOnClickListener {
            popup.dismiss()
            lifecycleScope.launch {
                when {
                    forNotebook != null -> dataManager.updateNotebook(forNotebook.copy(tagColorHex = null))
                    forFolder != null -> dataManager.updateFolder(forFolder.copy(tagColorHex = null))
                }
                loadDataFromDatabase()
            }
        }

        val rowIds = listOf(
            R.id.tagRow0, R.id.tagRow1, R.id.tagRow2, R.id.tagRow3,
            R.id.tagRow4, R.id.tagRow5, R.id.tagRow6
        )
        NotebookTags.COLORS.forEachIndexed { i, tag ->
            val row = content.findViewById<TextView>(rowIds[i])

            row.text = buildString {
                if (activeColor?.equals(tag.hex, ignoreCase = true) == true) append("✓  ")
                append(NotebookTags.nameOf(this@MainActivity, tag.hex) ?: tag.name)
            }
            (row.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(android.graphics.Color.parseColor(tag.hex))
            row.setOnClickListener {
                popup.dismiss()
                applyTagColor(tag.hex, forNotebook, forFolder)
            }
            row.setOnLongClickListener {
                popup.dismiss()
                showRenameTagDialog(anchor, tag, forNotebook, forFolder)
                true
            }
        }
        showAnchoredPopup(popup, anchor)
    }

    private fun showRenameTagDialog(
        anchor: View,
        tag: NotebookTags.TagColor,
        forNotebook: Notebook? = null,
        forFolder: Folder? = null,
        reopenPicker: Boolean = true
    ) {
        val input = EditText(this).apply {
            setText(NotebookTags.nameOf(this@MainActivity, tag.hex) ?: tag.name)
            selectAll()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename tag")
            .setMessage("The new name shows wherever this tag color is used.")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                NotebookTags.rename(this, tag.hex, input.text.toString().trim())

                loadDataFromDatabase()
                if (reopenPicker) showTagPicker(anchor, forNotebook, forFolder)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTagColor(hex: String, notebook: Notebook? = null, folder: Folder? = null) {
        if (notebook == null && folder == null) {

            if (tagFilter?.equals(hex, ignoreCase = true) == true) {
                clearTagFilter()
            } else {

                suppressTagFilterClear = true
                findViewById<com.google.android.material.chip.ChipGroup>(R.id.filterChips).check(R.id.chipAll)
                suppressTagFilterClear = false
                tagFilter = hex
            }
            loadDataFromDatabase()
        } else {
            lifecycleScope.launch {
                if (notebook != null) {
                    dataManager.updateNotebook(notebook.copy(tagColorHex = hex))
                } else {
                    folder?.let { dataManager.updateFolder(it.copy(tagColorHex = hex)) }
                }
                loadDataFromDatabase()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tag_filter", tagFilter)
    }

    private fun renameNotebook(notebook: Notebook) {
        val input = EditText(this).apply { setText(notebook.title) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Notebook").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                lifecycleScope.launch {
                    dataManager.updateNotebook(notebook.copy(title = input.text.toString().trim()))
                    loadDataFromDatabase()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun toggleNotebookFavorite(notebook: Notebook) {
        lifecycleScope.launch {
            dataManager.updateNotebook(notebook.copy(isFavorite = !notebook.isFavorite))
            loadDataFromDatabase()
        }
    }

    private fun moveNotebook(notebook: Notebook) {
        lifecycleScope.launch {
            val folders = AppDatabase.getDatabase(this@MainActivity).notesDao().getAllFolders()
            val options = listOf("All Notes") + folders.map { it.name }
            val folderIds = listOf(0L) + folders.map { it.id }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Move to Folder")
                .setItems(options.toTypedArray()) { _, which ->
                    lifecycleScope.launch {
                        dataManager.updateNotebook(notebook.copy(folderId = folderIds[which]))
                        loadDataFromDatabase()
                    }
                }.show()
        }
    }

    private fun duplicateNotebook(notebook: Notebook) {
        lifecycleScope.launch {
            dataManager.duplicateNotebook(notebook, this@MainActivity)
            loadDataFromDatabase()
        }
    }

    override fun onFolderMenu(folder: Folder, anchor: View) {
        if (binMode) {
            showBinItemOptions(DisplayItem.FolderItem(folder))
            return
        }
        val content = layoutInflater.inflate(R.layout.popup_folder_menu, null)
        val popup = buildAnchoredPopup(content)
        content.findViewById<TextView>(R.id.popupTitle).text = folder.name

        content.findViewById<TextView>(R.id.popupMeta).visibility = android.view.View.GONE
        val pickColor = { hex: String -> popup.dismiss(); setFolderColor(folder, hex) }
        val swatchColors = mapOf(
            R.id.c1 to "#4285F4", R.id.c2 to "#34A853", R.id.c3 to "#FBBC05",
            R.id.c4 to "#EA4335", R.id.c5 to "#9C27B0"
        )
        swatchColors.forEach { (id, hex) ->
            val swatch = content.findViewById<View>(id)

            if (hex.equals(folder.colorHex, ignoreCase = true)) {
                swatch.setBackgroundResource(R.drawable.bg_color_slot_selected)
            }

            (swatch.background as? android.graphics.drawable.LayerDrawable)
                ?.findDrawableByLayerId(R.id.color_shape)
                ?.let { it as? android.graphics.drawable.GradientDrawable }
                ?.setColor(android.graphics.Color.parseColor(hex))
            swatch.setOnClickListener { pickColor(hex) }
        }

        val folderTagColor = folder.tagColorHex?.let { hex ->
            try { android.graphics.Color.parseColor(hex) } catch (e: IllegalArgumentException) { null }
        }
        content.findViewById<ImageView>(R.id.popupHeaderIcon).imageTintList =
            android.content.res.ColorStateList.valueOf(
                folderTagColor ?: com.google.android.material.color.MaterialColors.getColor(
                    content, com.google.android.material.R.attr.colorPrimary, 0
                )
            )
        content.findViewById<TextView>(R.id.bsTag).text =
            if (folder.tagColorHex != null) "Change Tag" else "Add Tag"
        content.findViewById<View>(R.id.bsTag).setOnClickListener { popup.dismiss(); showTagPicker(anchor, forFolder = folder) }
        content.findViewById<View>(R.id.bsCustomColor).setOnClickListener { popup.dismiss(); showColorPickerDialog(folder) }
        content.findViewById<View>(R.id.bsRename).setOnClickListener { popup.dismiss(); renameFolder(folder) }
        content.findViewById<View>(R.id.bsMove).setOnClickListener {
            popup.dismiss(); android.widget.Toast.makeText(this, "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
        }
        content.findViewById<View>(R.id.bsDelete).setOnClickListener { popup.dismiss(); showDeleteFolderDialog(folder) }
        showAnchoredPopup(popup, anchor)
    }

    private fun setFolderColor(folder: Folder, hex: String) {
        lifecycleScope.launch {
            dataManager.updateFolder(folder.copy(colorHex = hex))
            loadDataFromDatabase()
        }
    }

    private fun renameFolder(folder: Folder) {
        val input = EditText(this).apply { setText(folder.name) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename Folder").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                lifecycleScope.launch {
                    dataManager.updateFolder(folder.copy(name = input.text.toString().trim()))
                    loadDataFromDatabase()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showCreateDialog() {
        val dialog = android.app.Dialog(this, R.style.Theme_OctopusNotes)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeViewModelStoreOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)
            setContent {
                AppTheme {
                    CreateNewDialog(
                        onDismiss = { dialog.dismiss() },
                        onNewFolder = {
                            dialog.dismiss()
                            showCreateFolderDialog()
                        },
                        onNewNotebook = {
                            dialog.dismiss()
                            showCreateNotebookDialog()
                        },
                        onNewWhiteboard = {
                            dialog.dismiss()
                            showCreateWhiteboardDialog()
                        },
                        onImportPdf = {
                            dialog.dismiss()
                            importPdf.launch(arrayOf("application/pdf"))
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        setCreateDialogWidth(dialog)
        dialog.show()
    }

    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        val darkTheme = isSystemInDarkTheme()

        val colors = if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
        else dynamicLightColorScheme(LocalContext.current)
        MaterialTheme(colorScheme = colors, content = content)
    }

    private fun showCreateFolderDialog() {
        val dialog = android.app.Dialog(this, R.style.Theme_OctopusNotes)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeViewModelStoreOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)
            setContent {
                AppTheme {
                    CreateFolderDialog(
                        onDismiss = { dialog.dismiss() },
                        onCreate = { name, colorHex ->
                            dialog.dismiss()
                            lifecycleScope.launch {
                                dataManager.createFolder(name, colorHex, currentFolderId)
                                loadDataFromDatabase()
                            }
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        setDialogWidth(dialog)
        dialog.show()
    }

    private fun showCreateNotebookDialog() {
        val dialog = android.app.Dialog(this, R.style.Theme_OctopusNotes)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val defaultSettings = loadDefaultTemplate()

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeViewModelStoreOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)
            setContent {
                AppTheme {
                    BasicTemplateScreen(
                        defaultSettings = defaultSettings,
                        onDismiss = { dialog.dismiss() },
                        onCreate = { title, settings ->
                            dialog.dismiss()
                            createNotebookFlow(title, settings)
                        },
                        onMoreOptions = { typedTitle ->
                            dialog.dismiss()
                            showAdvancedTemplateDialog(defaultSettings, typedTitle)
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        setDialogWidth(dialog)
        dialog.show()
    }

    private fun showCreateWhiteboardDialog() {
        val dialog = android.app.Dialog(this, R.style.Theme_OctopusNotes)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeViewModelStoreOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)
            setContent {
                AppTheme {
                    CreateWhiteboardDialog(
                        onDismiss = { dialog.dismiss() },
                        onCreate = { title ->
                            dialog.dismiss()
                            createWhiteboardFlow(title)
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        setDialogWidth(dialog)
        dialog.show()
    }

    private fun createWhiteboardFlow(title: String) {
        lifecycleScope.launch {
            val newId = withContext(Dispatchers.IO) {
                dataManager.createWhiteboard(title.trim(), currentFolderId)
            }
            loadDataFromDatabase()
            openInfiniteCanvas(newId)
        }
    }

    private fun showAdvancedTemplateDialog(initialSettings: TemplateSettings?, initialTitle: String = "") {
        val dialog = android.app.Dialog(this, R.style.Theme_OctopusNotes)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        if (initialSettings?.customImgUri != null) {
            customImageUriState.value = initialSettings.customImgUri
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeViewModelStoreOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)
            setContent {
                AppTheme {
                    AdvancedTemplateScreen(
                        initialSettings = initialSettings,
                        initialTitle = initialTitle,
                        customUriState = customImageUriState,
                        onPickImage = { pickImageLauncher.launch("image/*") },
                        onDismiss = { dialog.dismiss() },
                        onCreate = { title, settings, setAsDefault ->
                            dialog.dismiss()
                            if (setAsDefault) {
                                saveDefaultTemplate(settings)
                            }
                            createNotebookFlow(title, settings)
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        setDialogWidth(dialog)
        dialog.show()
    }

    private fun setDialogWidth(dialog: android.app.Dialog) {
        val layoutParams = android.view.WindowManager.LayoutParams()
        layoutParams.copyFrom(dialog.window?.attributes)
        layoutParams.width = (resources.displayMetrics.widthPixels * 0.95).toInt()
        layoutParams.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
        dialog.window?.attributes = layoutParams
    }

    private fun setCreateDialogWidth(dialog: android.app.Dialog) {
        val layoutParams = android.view.WindowManager.LayoutParams()
        layoutParams.copyFrom(dialog.window?.attributes)
        layoutParams.width = android.view.WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
        dialog.window?.attributes = layoutParams
    }

    private fun createInstantNote() {
        val settings = loadDefaultTemplate() ?: TemplateSettings(
            type = "BLANK",
            bgColor = ComposeColor(0xFFFFFFFF),
            density = 4f,
            brightness = 5f,
            thickness = 5f,
            lineColor = ComposeColor.LightGray,
            pageSize = PageSize("A4", 595, 842),
            isCustom = false,
            customImgUri = null
        )
        createNotebookFlow(defaultNotebookTitle(), settings)
    }

    private fun createNotebookFlow(title: String, settings: TemplateSettings) {
        lifecycleScope.launch {
            val progress = createProgressDialog("Generating template...")
            progress.show()
            val newId = withContext(Dispatchers.IO) {
                val id = dataManager.createNotebookWithPdf(title, currentFolderId, null)
                val bytes = createCustomPdfBytes(settings, this@MainActivity)
                val dest = java.io.File(filesDir, "pdf_$id.pdf")
                dest.writeBytes(bytes)
                dataManager.setNotebookPdfPath(id, dest.absolutePath)

                ThumbnailGenerator.generate(this@MainActivity, id, dest, isImported = false)
                id
            }
            saveNotebookTemplate(newId, settings)
            progress.dismiss()
            loadDataFromDatabase()
            openDrawing(newId)
        }
    }

    private fun saveNotebookTemplate(id: Long, settings: TemplateSettings) {
        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("type", settings.type)
            put("bgColor", settings.bgColor.toArgb())
            put("density", settings.density.toDouble())
            put("brightness", settings.brightness.toDouble())
            put("thickness", settings.thickness.toDouble())
            put("lineColor", settings.lineColor.toArgb())
            put("pageSizeWidth", settings.pageSize.width)
            put("pageSizeHeight", settings.pageSize.height)
            put("isCustom", settings.isCustom)
            put("customUri", settings.customImgUri?.toString())
        }
        prefs.edit().putString("template_$id", json.toString()).apply()
    }

    private fun createCustomPdfBytes(settings: TemplateSettings, context: Context): ByteArray {
        val outputStream = java.io.ByteArrayOutputStream()
        val document = android.graphics.pdf.PdfDocument()

        for (pageNum in 1..2) {
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(settings.pageSize.width, settings.pageSize.height, pageNum).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        if (settings.isCustom && settings.customImgUri != null) {

            val bitmap = PageTemplate.decodeSoftwareBitmap(context, settings.customImgUri)
            canvas.drawColor(android.graphics.Color.WHITE)
            if (bitmap != null) {
                val destRect = android.graphics.Rect(0, 0, pageInfo.pageWidth, pageInfo.pageHeight)
                canvas.drawBitmap(bitmap, null, destRect, null)
            }
        } else {
            canvas.drawColor(settings.bgColor.toArgb())
            if (settings.type != "BLANK") {
                val spacing = 80f - (settings.density * 5f)
                val paint = android.graphics.Paint().apply {
                    color = settings.lineColor.toArgb()
                    strokeWidth = settings.thickness * 0.5f
                    style = android.graphics.Paint.Style.STROKE
                    alpha = ((settings.brightness / 10f) * 255).toInt().coerceIn(0, 255)
                }
                if (settings.type == "DOTS") {
                    val dotPaint = android.graphics.Paint(paint).apply { style = android.graphics.Paint.Style.FILL }
                    val r = (settings.thickness * 0.7f).coerceAtLeast(1f)
                    var y = spacing
                    while (y < pageInfo.pageHeight) {
                        var x = spacing
                        while (x < pageInfo.pageWidth) {
                            canvas.drawCircle(x, y, r, dotPaint)
                            x += spacing
                        }
                        y += spacing
                    }
                } else {
                    if (settings.type == "RULE" || settings.type == "GRID") {
                        var y = spacing
                        while (y < pageInfo.pageHeight) {
                            canvas.drawLine(0f, y, pageInfo.pageWidth.toFloat(), y, paint)
                            y += spacing
                        }
                    }
                    if (settings.type == "GRID") {
                        var x = spacing
                        while (x < pageInfo.pageWidth) {
                            canvas.drawLine(x, 0f, x, pageInfo.pageHeight.toFloat(), paint)
                            x += spacing
                        }
                    }
                }
            }
        }
        document.finishPage(page)
        }
        document.writeTo(outputStream)
        document.close()
        return outputStream.toByteArray()
    }

    private fun showDeleteNotebookDialog(notebook: Notebook) {
        MaterialAlertDialogBuilder(this).setTitle("Move to Bin")
            .setMessage("Are you sure you want to move '${notebook.title}' to Bin?")
            .setPositiveButton("Move") { _, _ ->
                lifecycleScope.launch {
                    dataManager.moveToBin(notebook)
                    loadDataFromDatabase()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showDeleteFolderDialog(folder: Folder) {
        MaterialAlertDialogBuilder(this).setTitle("Move to Bin")
            .setMessage("Are you sure you want to move '${folder.name}' to Bin?")
            .setPositiveButton("Move") { _, _ ->
                lifecycleScope.launch {
                    dataManager.moveToBin(folder)
                    loadDataFromDatabase()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showColorPickerDialog(folder: Folder) {
        val initial = try { android.graphics.Color.parseColor(folder.colorHex) } catch (e: Exception) { android.graphics.Color.parseColor("#FFC107") }

        ColorPickerDialog.show(this, initial) { color ->
            val hex = String.format("#%06X", 0xFFFFFF and color)
            lifecycleScope.launch {
                dataManager.updateFolder(folder.copy(colorHex = hex))
                loadDataFromDatabase()
            }
        }
    }

    private fun updateEmptyViewVisibility() {
        emptyViewTextView.text = if (tagFilter != null) {
            "No notebooks tagged this color yet.\nPick a tag from a notebook's ⋯ menu to see it here."
        } else {
            "Nothing here yet.\nTap 'Create' to add a note or folder!"
        }
        emptyViewTextView.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE
        notebooksRecyclerView.visibility = if (displayItems.isEmpty()) View.GONE else View.VISIBLE
    }

    private var pendingDropFolderId: Long? = null

    private val dropPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private fun setupDragAndDrop() {

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = selectionMode && !binMode

            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) pendingDropFolderId = null
            }
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {

                return if (selectionMode && !binMode) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END
                } else 0
            }

            private var dropTargetView: View? = null

            private fun folderUnder(
                rv: RecyclerView,
                dragged: RecyclerView.ViewHolder,
                x: Float,
                y: Float
            ): Pair<View, Long>? {
                for (i in 0 until rv.childCount) {
                    val child = rv.getChildAt(i)
                    if (child === dragged.itemView) continue
                    if (x < child.left || x > child.right || y < child.top || y > child.bottom) continue
                    val holder = rv.getChildViewHolder(child)
                    if (holder !is NotebookAdapter.FolderViewHolder) continue
                    val item = displayItems.getOrNull(holder.adapterPosition) as? DisplayItem.FolderItem
                        ?: continue

                    if (item.folder.id in selectedFolderIds) continue
                    return child to item.folder.id
                }
                return null
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)

                if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !isCurrentlyActive) return
                val hit = folderUnder(
                    rv, vh,
                    vh.itemView.left + dX + vh.itemView.width / 2f,
                    vh.itemView.top + dY + vh.itemView.height / 2f
                )
                dropTargetView = hit?.first
                pendingDropFolderId = hit?.second
            }

            override fun onChildDrawOver(
                c: android.graphics.Canvas,
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder?,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                super.onChildDrawOver(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                val target = dropTargetView ?: return

                val inset = dp(6).toFloat()
                val radius = dp(14).toFloat()
                val rect = android.graphics.RectF(
                    target.left + inset, target.top + inset,
                    target.right - inset, target.bottom - inset
                )
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    rv, com.google.android.material.R.attr.colorPrimary
                )
                dropPaint.style = android.graphics.Paint.Style.FILL
                dropPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 48)
                c.drawRoundRect(rect, radius, radius, dropPaint)
                dropPaint.style = android.graphics.Paint.Style.STROKE
                dropPaint.strokeWidth = dp(3).toFloat()
                dropPaint.color = primary
                c.drawRoundRect(rect, radius, radius, dropPaint)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val target = pendingDropFolderId
                pendingDropFolderId = null
                dropTargetView = null
                if (target != null && selectionMode) {

                    displayItems.getOrNull(viewHolder.adapterPosition)?.let { setSelected(it, true) }
                    moveSelectedTo(target)
                }
            }
        }
        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(notebooksRecyclerView)
    }

    private fun moveSelectedTo(targetFolderId: Long) {
        val nbIds = selectedNotebookIds.toList()
        val fIds = selectedFolderIds.toList().filter { it != targetFolderId }
        if (nbIds.isEmpty() && fIds.isEmpty()) { exitSelectionMode(); return }
        lifecycleScope.launch {
            nbIds.forEach { id -> dataManager.getNotebook(id)?.let { dataManager.updateNotebook(it.copy(folderId = targetFolderId)) } }
            fIds.forEach { id -> dataManager.getFolder(id)?.let { dataManager.updateFolder(it.copy(parentId = targetFolderId)) } }
            exitSelectionMode()
            loadDataFromDatabase()
        }
    }

    private fun setupEdgeToEdge() {
        val rootLayout: View = findViewById(R.id.rootLayout)
        val topBar: View = findViewById(R.id.topBar)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(topBar.paddingLeft, systemBars.top + dp(8), topBar.paddingRight, topBar.paddingBottom)

            notebooksRecyclerView.setPadding(0, 0, 0, systemBars.bottom + dp(96))
            (addNotebookFab.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin = systemBars.bottom + dp(16)
            addNotebookFab.requestLayout()
            (selectionDock.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin = systemBars.bottom + dp(16)
            selectionDock.requestLayout()
            (binClearDock.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin = systemBars.bottom + dp(16)
            binClearDock.requestLayout()
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

@Composable
fun TemplatePreview(settings: TemplateSettings, modifier: Modifier = Modifier) {
    val pageRatio = settings.pageSize.width.toFloat() / settings.pageSize.height.toFloat()
    Box(
        modifier = modifier
            .aspectRatio(pageRatio)
            .background(settings.bgColor)
            .border(1.dp, ComposeColor.LightGray)
    ) {
        if (settings.isCustom && settings.customImgUri != null) {
            val context = LocalContext.current
            val bitmap = remember(settings.customImgUri) {
                PageTemplate.decodeSoftwareBitmap(context, settings.customImgUri)
            }
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Failed to load image")
                }
            }
        } else if (settings.type != "BLANK") {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val activeWidth = settings.pageSize.width.toFloat()
                val activeHeight = settings.pageSize.height.toFloat()
                val scaleY = size.height / activeHeight
                val scale = minOf(size.width / activeWidth, scaleY)
                val spacing = (80f - (settings.density * 5f)) * scaleY
                val strokeW = (settings.thickness * 0.5f) * scale
                val alphaValue = ((settings.brightness / 10f) * 255).toInt().coerceIn(0, 255)
                val lc = settings.lineColor.copy(alpha = alphaValue / 255f)

                if (settings.type == "DOTS") {
                    val r = (settings.thickness * 0.7f * scale).coerceAtLeast(1f)
                    var y = spacing
                    while (y < size.height) {
                        var x = spacing
                        while (x < size.width) {
                            drawCircle(color = lc, radius = r, center = Offset(x, y))
                            x += spacing
                        }
                        y += spacing
                    }
                } else {
                    var y = spacing
                    while (y < size.height) {
                        drawLine(color = lc, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = strokeW)
                        y += spacing
                    }
                    if (settings.type == "GRID") {
                        var x = spacing
                        while (x < size.width) {
                            drawLine(color = lc, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = strokeW)
                            x += spacing
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateNewDialog(
    onDismiss: () -> Unit,
    onNewFolder: () -> Unit,
    onNewNotebook: () -> Unit,
    onNewWhiteboard: () -> Unit,
    onImportPdf: () -> Unit
) {

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val dialogScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.92f,
        animationSpec = tween(durationMillis = 220)
    )
    val dialogAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 180)
    )

    Surface(
        shape = RoundedCornerShape(28.dp),

        color = MaterialTheme.colorScheme.surfaceContainerHigh,

        modifier = Modifier.graphicsLayer {
            scaleX = dialogScale
            scaleY = dialogScale
            alpha = dialogAlpha
        }
    ) {
        val configuration = LocalConfiguration.current
        val maxDialogHeight = (configuration.screenHeightDp * 0.90f).dp
        Box(modifier = Modifier.heightIn(max = maxDialogHeight)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.create_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(20.dp))

            CreateOptionCard(
                icon = R.drawable.ic_folder,
                accent = ComposeColor(0xFF4285F4),
                title = stringResource(R.string.create_folder),
                onClick = onNewFolder
            )
            Spacer(Modifier.height(10.dp))
            CreateOptionCard(
                icon = R.drawable.ic_pen,
                accent = ComposeColor(0xFF9C27B0),
                title = stringResource(R.string.create_notebook),
                onClick = onNewNotebook
            )
            Spacer(Modifier.height(10.dp))
            CreateOptionCard(
                icon = R.drawable.ic_grid,
                accent = ComposeColor(0xFF00ACC1),
                title = stringResource(R.string.create_whiteboard_beta),
                onClick = onNewWhiteboard
            )
            Spacer(Modifier.height(10.dp))
            CreateOptionCard(
                icon = R.drawable.ic_pdf,
                accent = ComposeColor(0xFFEA4335),
                title = stringResource(R.string.create_pdf),
                onClick = onImportPdf
            )

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
        }
    }
}

@Composable
fun CreateWhiteboardDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    val defaultTitle = remember { defaultNotebookTitle() }
    var title by remember { mutableStateOf(defaultTitle) }
    var isError by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp * 0.90f).dp

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(modifier = Modifier.heightIn(max = maxDialogHeight)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(stringResource(R.string.create_whiteboard), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; isError = false },
                    label = { Text(stringResource(R.string.whiteboard_title)) },
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isError) {
                    Text(
                        text = stringResource(R.string.title_cannot_be_empty),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.create_whiteboard_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (title.isBlank()) isError = true else onCreate(title)
                    }) { Text(stringResource(R.string.create)) }
                }
            }
        }
    }
}

@Composable
private fun CreateOptionCard(
    icon: Int,
    accent: ComposeColor,
    title: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 120)
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .padding(14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(accent.copy(alpha = 0.16f))
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    val folderColors = listOf("#4285F4", "#34A853", "#FBBC05", "#EA4335", "#9C27B0")
    var selectedColor by remember { mutableStateOf(folderColors.first()) }
    var customHex by remember { mutableStateOf("") }
    var showHexInput by remember { mutableStateOf(false) }
    var hexError by remember { mutableStateOf(false) }
    fun parseHexColor(hex: String): String? {
        return try {
            val cleanHex = hex.removePrefix("#")
            if (cleanHex.length == 6) {
                android.graphics.Color.parseColor("#$cleanHex")
                "#$cleanHex"
            } else null
        } catch (e: Exception) {
            null
        }
    }
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp * 0.90f).dp
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(modifier = Modifier.heightIn(max = maxDialogHeight)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text("New Folder", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it; isError = false },
                    label = { Text("Folder Name") },
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isError) {
                    Text(
                        text = "Name cannot be empty",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text("Folder Color", style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    folderColors.forEach { colorHex ->
                        val color = try {
                            ComposeColor(android.graphics.Color.parseColor(colorHex))
                        } catch (e: Exception) {
                            ComposeColor.Gray
                        }
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(color, CircleShape)
                                .border(
                                    width = if (selectedColor == colorHex) 3.dp else 1.dp,
                                    color = if (selectedColor == colorHex) MaterialTheme.colorScheme.primary else ComposeColor.Gray,
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedColor = colorHex
                                    showHexInput = false
                                }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { showHexInput = !showHexInput },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showHexInput) "Hide Custom Color" else "Custom Hex Color")
                }
                if (showHexInput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customHex,
                            onValueChange = {
                                customHex = it
                                hexError = false
                            },
                            label = { Text("#HEX Color") },
                            isError = hexError,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            val hex = parseHexColor(customHex)
                            if (hex != null) {
                                selectedColor = hex
                                showHexInput = false
                                customHex = ""
                            } else {
                                hexError = true
                            }
                        }) {
                            Text("Apply")
                        }
                    }
                    if (hexError) {
                        Text(
                            text = "Invalid hex color",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (folderName.isBlank()) {
                            isError = true
                        } else {
                            onCreate(folderName.trim(), selectedColor)
                        }
                    }) { Text("Create") }
                }
            }
        }
    }
}

fun defaultNotebookTitle(): String {
    val c = java.util.Calendar.getInstance()
    return String.format(
        java.util.Locale.US, "Untitled %02d%02d%02d %02d%02d",
        c.get(java.util.Calendar.DAY_OF_MONTH),
        c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.YEAR) % 100,
        c.get(java.util.Calendar.HOUR_OF_DAY),
        c.get(java.util.Calendar.MINUTE)
    )
}

@Composable
fun BasicTemplateScreen(
    defaultSettings: TemplateSettings?,
    onDismiss: () -> Unit,
    onCreate: (String, TemplateSettings) -> Unit,
    onMoreOptions: (String) -> Unit
) {

    val defaultTitle = remember { defaultNotebookTitle() }
    var title by remember { mutableStateOf(defaultTitle) }
    var isError by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp * 0.90f).dp

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(modifier = Modifier.heightIn(max = maxDialogHeight)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text("New Notebook", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; isError = false },
                    label = { Text("Notebook Title") },
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isError) {
                    Text(
                        text = "Title cannot be empty",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text("Default Template", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                if (defaultSettings != null) {
                    TemplatePreview(
                        settings = defaultSettings,
                        modifier = Modifier.fillMaxWidth(0.4f).align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(16.dp))
                } else {
                    Text("No default template set.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                }

                TextButton(
                    onClick = { onMoreOptions(title) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose / Edit Template")
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (title.isBlank()) {
                            isError = true
                        } else {
                            val settings = defaultSettings ?: TemplateSettings(
                                type = "BLANK",
                                bgColor = ComposeColor(0xFFFFFFFF),
                                density = 4f,
                                brightness = 5f,
                                thickness = 5f,
                                lineColor = ComposeColor.LightGray,
                                pageSize = PageSize("A4", 595, 842),
                                isCustom = false,
                                customImgUri = null
                            )
                            onCreate(title, settings)
                        }
                    }) { Text("Create") }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Tip: long-press the + button on the home screen to create an instant note with the default template.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdvancedTemplateScreen(
    initialSettings: TemplateSettings?,
    initialTitle: String = "",
    customUriState: State<Uri?>,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String, TemplateSettings, Boolean) -> Unit,
    changeMode: Boolean = false,
    onApply: ((TemplateSettings, Boolean) -> Unit)? = null,
    applyLabel: String = "Apply",
    showApplyAll: Boolean = true
) {
    var title by remember { mutableStateOf(initialTitle) }
    var isError by remember { mutableStateOf(false) }
    var isCustomMode by remember { mutableStateOf(initialSettings?.isCustom ?: false) }
    var lineStyle by remember {
        mutableStateOf(when (initialSettings?.type) { "BLANK" -> "BLANK"; "GRID" -> "GRID"; "DOTS" -> "DOTS"; else -> "RULE" })
    }
    var setAsDefault by remember { mutableStateOf(false) }

    val standardSizes = listOf(
        PageSize("A4", 595, 842),
        PageSize("A3", 842, 1191),
        PageSize("A5", 420, 595),
        PageSize("Letter", 612, 792),
        PageSize("Custom", 595, 842)
    )
    var expandedSizeDropdown by remember { mutableStateOf(false) }
    var selectedPageSize by remember { mutableStateOf(initialSettings?.pageSize ?: standardSizes[0]) }
    var customWidthText by remember { mutableStateOf(initialSettings?.pageSize?.width?.toString() ?: "595") }
    var customHeightText by remember { mutableStateOf(initialSettings?.pageSize?.height?.toString() ?: "842") }

    var selectedPageColor by remember { mutableStateOf(initialSettings?.bgColor ?: ComposeColor(0xFFF2F2F2)) }
    var density by remember { mutableStateOf(initialSettings?.density ?: 4f) }
    var brightness by remember { mutableStateOf(initialSettings?.brightness ?: 5f) }
    var thickness by remember { mutableStateOf(initialSettings?.thickness ?: 5f) }
    var selectedLineColor by remember { mutableStateOf(initialSettings?.lineColor ?: ComposeColor.LightGray) }

    var customPageHex by remember { mutableStateOf("") }
    var customLineHex by remember { mutableStateOf("") }
    var showPageHexInput by remember { mutableStateOf(false) }
    var showLineHexInput by remember { mutableStateOf(false) }
    var pageHexError by remember { mutableStateOf(false) }
    var lineHexError by remember { mutableStateOf(false) }

    val pageColors = listOf(ComposeColor(0xFFF2F2F2), ComposeColor(0xFFEFEFE0), ComposeColor(0xFFFFFFFF), ComposeColor(0xFFD1DAE6), ComposeColor(0xFF333333), ComposeColor(0xFF000000))
    val lineColors = listOf(ComposeColor.LightGray, ComposeColor.DarkGray, ComposeColor(0xFF2196F3), ComposeColor(0xFF4CAF50))

    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp * 0.90f).dp
    val screenWidthDp = configuration.screenWidthDp
    val isSmallScreen = screenWidthDp < 360

    val activeWidth = if (selectedPageSize.name == "Custom") (customWidthText.toIntOrNull() ?: 595) else selectedPageSize.width
    val activeHeight = if (selectedPageSize.name == "Custom") (customHeightText.toIntOrNull() ?: 842) else selectedPageSize.height
    val pageRatio = activeWidth.toFloat() / activeHeight.toFloat()

    fun parseHexColor(hex: String): ComposeColor? {
        return try {
            val cleanHex = hex.removePrefix("#")
            if (cleanHex.length == 6) {
                ComposeColor(android.graphics.Color.parseColor("#$cleanHex"))
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(modifier = Modifier.heightIn(max = maxDialogHeight)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(if (changeMode) "Change Template" else "Advanced Template", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                if (!changeMode) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it; isError = false },
                        label = { Text("Notebook Title") }, isError = isError, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isError) Text("Title cannot be empty", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                }

                ExposedDropdownMenuBox(
                    expanded = expandedSizeDropdown,
                    onExpandedChange = { expandedSizeDropdown = !expandedSizeDropdown }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = if (selectedPageSize.name == "Custom") "Custom" else "${selectedPageSize.name} (${selectedPageSize.width} x ${selectedPageSize.height})",
                        onValueChange = { },
                        label = { Text("Page Size") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSizeDropdown) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expandedSizeDropdown, onDismissRequest = { expandedSizeDropdown = false }) {
                        standardSizes.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = {
                                    if (selectionOption.name == "Custom") Text("Custom")
                                    else Text("${selectionOption.name} (${selectionOption.width} x ${selectionOption.height})")
                                },
                                onClick = { selectedPageSize = selectionOption; expandedSizeDropdown = false }
                            )
                        }
                    }
                }

                if (selectedPageSize.name == "Custom") {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = customWidthText,
                            onValueChange = { customWidthText = it },
                            label = { Text("Width (px)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customHeightText,
                            onValueChange = { customHeightText = it },
                            label = { Text("Height (px)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    FilterChip(selected = !isCustomMode, onClick = { isCustomMode = false }, label = { Text("Built-in Lines") })
                    Spacer(Modifier.width(16.dp))
                    FilterChip(selected = isCustomMode, onClick = { isCustomMode = true }, label = { Text("Custom Image") })
                }

                Spacer(Modifier.height(16.dp))

                if (!isCustomMode) {

                    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = lineStyle == "BLANK", onClick = { lineStyle = "BLANK" }, label = { Text("None") })
                        FilterChip(selected = lineStyle == "RULE", onClick = { lineStyle = "RULE" }, label = { Text("Ruled") })
                        FilterChip(selected = lineStyle == "GRID", onClick = { lineStyle = "GRID" }, label = { Text("Grid") })
                        FilterChip(selected = lineStyle == "DOTS", onClick = { lineStyle = "DOTS" }, label = { Text("Dots") })
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (!isCustomMode) {
                    if (isSmallScreen) {
                        Text("Page Color", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            pageColors.forEach { color ->
                                Box(modifier = Modifier.size(32.dp).background(color = color, shape = CircleShape).border(width = if (selectedPageColor == color) 3.dp else 1.dp, color = if (selectedPageColor == color) MaterialTheme.colorScheme.primary else ComposeColor.Gray, shape = CircleShape).clickable { selectedPageColor = color; showPageHexInput = false })
                            }
                            Box(modifier = Modifier.size(32.dp).background(ComposeColor.Transparent, CircleShape).border(1.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable { showPageHexInput = !showPageHexInput }, contentAlignment = Alignment.Center) { Text("+", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (showPageHexInput) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = customPageHex, onValueChange = { customPageHex = it; pageHexError = false }, label = { Text("#HEX") }, isError = pageHexError, singleLine = true, modifier = Modifier.weight(1f))
                                Button(onClick = { val color = parseHexColor(customPageHex); if (color != null) { selectedPageColor = color; showPageHexInput = false; customPageHex = "" } else { pageHexError = true } }) { Text("Apply") }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Line Color", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            lineColors.forEach { color ->
                                Box(modifier = Modifier.size(32.dp).background(color = color, shape = CircleShape).border(width = if (selectedLineColor == color) 3.dp else 1.dp, color = if (selectedLineColor == color) MaterialTheme.colorScheme.primary else ComposeColor.Gray, shape = CircleShape).clickable { selectedLineColor = color; showLineHexInput = false })
                            }
                            Box(modifier = Modifier.size(32.dp).background(ComposeColor.Transparent, CircleShape).border(1.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable { showLineHexInput = !showLineHexInput }, contentAlignment = Alignment.Center) { Text("+", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (showLineHexInput) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = customLineHex, onValueChange = { customLineHex = it; lineHexError = false }, label = { Text("#HEX") }, isError = lineHexError, singleLine = true, modifier = Modifier.weight(1f))
                                Button(onClick = { val color = parseHexColor(customLineHex); if (color != null) { selectedLineColor = color; showLineHexInput = false; customLineHex = "" } else { lineHexError = true } }) { Text("Apply") }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Density", style = MaterialTheme.typography.titleSmall)
                        AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context -> com.google.android.material.slider.Slider(context).apply { valueFrom = 1f; valueTo = 10f; stepSize = 1f; value = density; labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_FLOATING; addOnChangeListener { _, v, _ -> density = v } } })
                        Text("Brightness", style = MaterialTheme.typography.titleSmall)
                        AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context -> com.google.android.material.slider.Slider(context).apply { valueFrom = 1f; valueTo = 10f; stepSize = 1f; value = brightness; labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_FLOATING; addOnChangeListener { _, v, _ -> brightness = v } } })
                        Text("Thickness", style = MaterialTheme.typography.titleSmall)
                        AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context -> com.google.android.material.slider.Slider(context).apply { valueFrom = 1f; valueTo = 10f; stepSize = 1f; value = thickness; labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_FLOATING; addOnChangeListener { _, v, _ -> thickness = v } } })
                        Spacer(Modifier.height(16.dp))
                        Text("Preview", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        TemplatePreview(
                            settings = TemplateSettings(lineStyle, selectedPageColor, density, brightness, thickness, selectedLineColor, selectedPageSize, false, null),
                            modifier = Modifier.fillMaxWidth(0.5f)
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(modifier = Modifier.weight(0.3f)) {
                                Text("Preview", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                TemplatePreview(
                                    settings = TemplateSettings(lineStyle, selectedPageColor, density, brightness, thickness, selectedLineColor, selectedPageSize, false, null),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Column(modifier = Modifier.weight(0.7f)) {
                                Text("Page Color", style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    pageColors.forEach { color ->
                                        Box(modifier = Modifier.size(28.dp).background(color = color, shape = CircleShape).border(width = if (selectedPageColor == color) 3.dp else 1.dp, color = if (selectedPageColor == color) MaterialTheme.colorScheme.primary else ComposeColor.Gray, shape = CircleShape).clickable { selectedPageColor = color; showPageHexInput = false })
                                    }
                                    Box(modifier = Modifier.size(28.dp).background(ComposeColor.Transparent, CircleShape).border(1.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable { showPageHexInput = !showPageHexInput }, contentAlignment = Alignment.Center) { Text("+", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (showPageHexInput) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = customPageHex, onValueChange = { customPageHex = it; pageHexError = false }, label = { Text("#HEX") }, isError = pageHexError, singleLine = true, modifier = Modifier.weight(1f))
                                        Button(onClick = { val color = parseHexColor(customPageHex); if (color != null) { selectedPageColor = color; showPageHexInput = false; customPageHex = "" } else { pageHexError = true } }) { Text("Apply") }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Line Color", style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    lineColors.forEach { color ->
                                        Box(modifier = Modifier.size(28.dp).background(color = color, shape = CircleShape).border(width = if (selectedLineColor == color) 3.dp else 1.dp, color = if (selectedLineColor == color) MaterialTheme.colorScheme.primary else ComposeColor.Gray, shape = CircleShape).clickable { selectedLineColor = color; showLineHexInput = false })
                                    }
                                    Box(modifier = Modifier.size(28.dp).background(ComposeColor.Transparent, CircleShape).border(1.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable { showLineHexInput = !showLineHexInput }, contentAlignment = Alignment.Center) { Text("+", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (showLineHexInput) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(value = customLineHex, onValueChange = { customLineHex = it; lineHexError = false }, label = { Text("#HEX") }, isError = lineHexError, singleLine = true, modifier = Modifier.weight(1f))
                                        Button(onClick = { val color = parseHexColor(customLineHex); if (color != null) { selectedLineColor = color; showLineHexInput = false; customLineHex = "" } else { lineHexError = true } }) { Text("Apply") }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Density", style = MaterialTheme.typography.titleSmall)
                                AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context -> com.google.android.material.slider.Slider(context).apply { valueFrom = 1f; valueTo = 10f; stepSize = 1f; value = density; labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_FLOATING; addOnChangeListener { _, v, _ -> density = v } } })
                                Text("Brightness", style = MaterialTheme.typography.titleSmall)
                                AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context -> com.google.android.material.slider.Slider(context).apply { valueFrom = 1f; valueTo = 10f; stepSize = 1f; value = brightness; labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_FLOATING; addOnChangeListener { _, v, _ -> brightness = v } } })
                                Text("Thickness", style = MaterialTheme.typography.titleSmall)
                                AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context -> com.google.android.material.slider.Slider(context).apply { valueFrom = 1f; valueTo = 10f; stepSize = 1f; value = thickness; labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_FLOATING; addOnChangeListener { _, v, _ -> thickness = v } } })
                            }
                        }
                    }
                } else {
                    if (isSmallScreen) {
                        Button(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                            Text(if (customUriState.value != null) "Change Selected Image" else "Choose Image from Gallery")
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Preview", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        TemplatePreview(
                            settings = TemplateSettings("CUSTOM", ComposeColor.LightGray, 0f, 0f, 0f, ComposeColor.LightGray, selectedPageSize, true, customUriState.value),
                            modifier = Modifier.fillMaxWidth(0.5f)
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(modifier = Modifier.weight(0.3f)) {
                                Text("Preview", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                TemplatePreview(
                                    settings = TemplateSettings("CUSTOM", ComposeColor.LightGray, 0f, 0f, 0f, ComposeColor.LightGray, selectedPageSize, true, customUriState.value),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Column(modifier = Modifier.weight(0.7f)) {
                                Button(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (customUriState.value != null) "Change Selected Image" else "Choose Image from Gallery")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                fun buildSettings(): TemplateSettings {
                    val finalPageSize = if (selectedPageSize.name == "Custom") {
                        PageSize("Custom", activeWidth, activeHeight)
                    } else {
                        selectedPageSize
                    }
                    val type = if (isCustomMode) "CUSTOM" else lineStyle
                    return TemplateSettings(
                        type = type,
                        bgColor = selectedPageColor,
                        density = density,
                        brightness = brightness,
                        thickness = thickness,
                        lineColor = selectedLineColor,
                        pageSize = finalPageSize,
                        isCustom = isCustomMode,
                        customImgUri = if (isCustomMode) customUriState.value else null
                    )
                }

                if (changeMode) {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        if (showApplyAll) {
                            OutlinedButton(onClick = { onApply?.invoke(buildSettings(), false) }) { Text(applyLabel) }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { onApply?.invoke(buildSettings(), true) }) { Text("Apply to whole PDF") }
                        } else {
                            Button(onClick = { onApply?.invoke(buildSettings(), false) }) { Text(applyLabel) }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(checked = setAsDefault, onCheckedChange = { setAsDefault = it })
                        Text("Set as default template", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            if (title.isBlank()) isError = true
                            else onCreate(title, buildSettings(), setAsDefault)
                        }) { Text("Create") }
                    }
                }
            }
        }
    }
}