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

// --- Lifecycle Owners for Compose Dialogs ---
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

// --- Jetpack Compose Imports ---
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
    val type: String, // "BLANK", "RULE", "GRID", "CUSTOM"
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

    // Folder navigation: stack of folders from root to current. Empty = root ("All Notes").
    private val folderPath = mutableListOf<Folder>()
    private val currentFolderId: Long get() = folderPath.lastOrNull()?.id ?: 0L
    private var searchQuery: String = ""
    private var favoritesMode = false
    private var binMode = false

    private lateinit var notebooksRecyclerView: AutofitRecyclerView
    private lateinit var emptyViewTextView: TextView
    private lateinit var searchInput: EditText
    private lateinit var breadcrumbBar: android.widget.LinearLayout
    private lateinit var addNotebookFab: ExtendedFloatingActionButton
    private lateinit var selectionDock: View
    private lateinit var sortLabel: TextView

    // Multi-select
    private val selectedFolderIds = linkedSetOf<Long>()
    private val selectedNotebookIds = linkedSetOf<Long>()
    private var selectionMode = false

    // State for Image Picker
    private val customImageUriState = mutableStateOf<Uri?>(null)
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // GetContent() returns a transient URI that becomes unreadable later — copy it into
        // app storage so the custom template survives page-adds and app restarts.
        customImageUriState.value = uri?.let { PageTemplate.copyTemplateImage(this, it) } ?: uri
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                selectionMode -> exitSelectionMode()
                searchInput.text.isNotEmpty() -> searchInput.setText("")
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
                    // Generate the home thumbnail now so the list shows it instead of a skeleton.
                    ThumbnailGenerator.generate(this@MainActivity, id, dest, isImported = true)
                    id
                }
                progress.dismiss()
                loadDataFromDatabase()
                openDrawing(newId)
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Roll back the partially-imported notebook (runs even though the job is cancelled).
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

    /** Copies [input]→[output] reporting integer percent (throttled). Throws on cancel. */
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

    // =========================================================================
    // DEFAULT TEMPLATE SETTINGS MANAGEMENT
    // =========================================================================
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
        }
    }

    // ------------------------------------------------------------------------
    //  HOME THUMBNAILS
    //
    //  Thumbnails only ever appear on this screen, so they are written here and
    //  nowhere else: on creation/import (above) and, for a notebook that was just
    //  edited, when the editor hands control back to us.
    // ------------------------------------------------------------------------

    /**
     * The notebook handed to DrawingActivity; its thumbnail is stale until we come back.
     * Kept in prefs rather than a field so it also survives this activity being destroyed
     * while the editor is in front.
     */
    private var editedNotebookId: Long
        get() = getSharedPreferences("notebook_state", Context.MODE_PRIVATE)
            .getLong("pending_thumb_id", -1L)
        set(value) = getSharedPreferences("notebook_state", Context.MODE_PRIVATE)
            .edit().putLong("pending_thumb_id", value).apply()

    private fun openDrawing(notebookId: Long, title: String? = null) {
        editedNotebookId = notebookId
        startActivity(Intent(this, DrawingActivity::class.java).apply {
            putExtra("NOTEBOOK_ID", notebookId)
            if (title != null) putExtra("NOTEBOOK_TITLE", title)
        })
    }

    /** Re-renders the thumbnail of the notebook we just came back from, then refreshes the list. */
    private fun refreshEditedThumbnail() {
        val id = editedNotebookId
        if (id < 0) return
        editedNotebookId = -1L
        lifecycleScope.launch {
            // The editor's last save runs detached and may still be writing the ink file.
            DrawingActivity.lastSaveJob?.join()
            val ok = withContext(Dispatchers.IO) { writeHomeThumbnail(id) }
            // The adapter keys its bitmap cache by file modtime, so a rebind picks up the new file.
            if (ok && ::adapter.isInitialized) adapter.notifyDataSetChanged()
        }
    }

    /**
     * Renders `thumb_<id>.png` for [notebookId] from its PDF plus its saved ink. Heavy —
     * call off the main thread. Returns true when a new thumbnail was written.
     */
    private suspend fun writeHomeThumbnail(notebookId: Long): Boolean {
        val notebook = dataManager.getNotebook(notebookId) ?: return false
        val file = notebook.pdfPath?.let { java.io.File(it) }?.takeIf { it.exists() } ?: return false

        // Decode the ink into a known coordinate space; strokePageSizes() below describes the
        // same space so the renderer can scale it onto the thumbnail. Documents saved before
        // the authoring width was recorded were drawn in portrait — the display's short side.
        val inkWidth = resources.displayMetrics.let {
            minOf(it.widthPixels, it.heightPixels).toFloat()
        }
        val strokeManager = StrokeManager()
        strokeManager.imagesDir = java.io.File(filesDir, "images").apply { mkdirs() }
        strokeManager.loadDecodedData(DrawingRepository(this).load(notebookId, inkWidth, inkWidth).pages)

        // No saved page template means an imported PDF rather than a notebook we generated.
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

    /**
     * The per-page coordinate space the strokes live in: [inkWidth] wide, with the page's own
     * aspect ratio. Only pages that actually carry ink are opened — walking every page of a
     * large PDF just to learn its aspect would be far slower than the render itself. Pages
     * without ink get a harmless A4 default that is never used.
     */
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
            // Recreate adapter with the new view mode so layouts change
            adapter = NotebookAdapter(displayItems, this, lifecycleScope, mode)
            notebooksRecyclerView.adapter = adapter
        }

        if (mode == "GRID") {
            notebooksRecyclerView.setColumnOverride(cols) // 0 = auto-fit (min 2)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        loadDataFromDatabase()

        // Manage PDFs opened elsewhere.
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

            /** Undoes the half-created notebook; also runs when the job is cancelled. */
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
                    // ACTION_VIEW hands out a transient read grant — it can already be revoked
                    // (or the file simply unreadable) by the time we read. Fail the import so
                    // we roll back instead of leaving a notebook pointing at an empty/missing PDF.
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
                    // Generate the home thumbnail now so the list shows it instead of a skeleton.
                    ThumbnailGenerator.generate(this@MainActivity, id, dest, isImported = true)
                    id
                }
                progress.dismiss()
                loadDataFromDatabase()
                openDrawing(newId)
            } catch (c: kotlinx.coroutines.CancellationException) {
                // User tapped cancel (or the activity was torn down) — undo the partial import.
                rollbackImport()
                progress.dismiss()
                loadDataFromDatabase()
            } catch (t: Throwable) {
                // Revoked permission / IO error / unreadable PDF — undo the partial import
                // and tell the user, instead of leaving a broken notebook behind.
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
        openDrawing(notebook.id, notebook.title)
    }

    private fun setupViews() {
        notebooksRecyclerView = findViewById(R.id.notebooksRecyclerView)
        emptyViewTextView = findViewById(R.id.emptyViewTextView)
        searchInput = findViewById(R.id.searchInput)
        breadcrumbBar = findViewById(R.id.breadcrumbBar)
        addNotebookFab = findViewById(R.id.addNotebookFab)
        selectionDock = findViewById(R.id.selectionDock)

        searchInput.addTextChangedListener {
            searchQuery = it?.toString().orEmpty()
            loadDataFromDatabase()
        }
        addNotebookFab.setOnClickListener { showCreateDialog() }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Filter chips
        findViewById<com.google.android.material.chip.ChipGroup>(R.id.filterChips)
            .setOnCheckedStateChangeListener { _, checkedIds ->
                favoritesMode = checkedIds.contains(R.id.chipFavorites)
                binMode = checkedIds.contains(R.id.chipBin)
                if (binMode) {
                    addNotebookFab.hide()
                } else if (!selectionMode) {
                    addNotebookFab.show()
                }
                loadDataFromDatabase()
            }
        findViewById<View>(R.id.chipTags).setOnClickListener {
            android.widget.Toast.makeText(this, "Tags — coming soon", android.widget.Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageButton>(R.id.sortButton).setOnClickListener { showSortPopup(it) }
        sortLabel = findViewById(R.id.sortLabel)
        updateSortLabel()

        // Selection dock actions
        findViewById<View>(R.id.dockClose).setOnClickListener { exitSelectionMode() }
        findViewById<View>(R.id.dockMove).setOnClickListener { moveSelected() }
        findViewById<View>(R.id.dockLock).setOnClickListener {
            android.widget.Toast.makeText(this, "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.dockDelete).setOnClickListener { deleteSelected() }
    }

    private fun loadDataFromDatabase() {
        lifecycleScope.launch {
            val data = when {
                searchQuery.isNotBlank() -> dataManager.searchAll(searchQuery)
                binMode -> dataManager.getBinData()
                favoritesMode -> dataManager.getDataForScreen(-1L)
                else -> dataManager.getDataForScreen(currentFolderId)
            }
            displayItems.clear()
            displayItems.addAll(sortItems(data.folders, data.notebooks))
            adapter.notifyDataSetChanged()
            // Animate items in on start / folder navigation (but not while typing a search).
            if (searchQuery.isBlank()) notebooksRecyclerView.scheduleLayoutAnimation()
            updateEmptyViewVisibility()
            updateBreadcrumb()
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
                is DisplayItem.FolderItem -> it.folder.createdAt // folders only track creation time
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

    override fun onFolderClick(folder: Folder) {
        if (binMode) {
            android.widget.Toast.makeText(this, "Restore item to open it", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val path = buildFolderPath(folder)
            folderPath.clear()
            folderPath.addAll(path)
            // Leaving search returns to the folder view; the watcher reloads with the new path.
            if (searchInput.text.isNotEmpty()) searchInput.setText("") else loadDataFromDatabase()
        }
    }

    /** Walks parentId up to the root to build the breadcrumb path to [folder]. */
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

    // ---------------- Multi-select ----------------

    override fun onItemLongPress(item: DisplayItem) {
        if (binMode) {
            showBinItemOptions(item)
            return
        }
        if (!selectionMode) enterSelectionMode()
        setSelected(item, true)
        adapter.notifyDataSetChanged()
        updateDock()
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
        setSelected(item, !isItemSelected(item))
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
        showDock(true)
        onBackPressedCallback.isEnabled = true
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedFolderIds.clear()
        selectedNotebookIds.clear()
        showDock(false)
        addNotebookFab.show()
        adapter.notifyDataSetChanged()
        onBackPressedCallback.isEnabled = folderPath.isNotEmpty() || searchQuery.isNotBlank()
    }

    private fun updateDock() {
        findViewById<TextView>(R.id.dockCount).text =
            (selectedFolderIds.size + selectedNotebookIds.size).toString()
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

    private fun moveSelected() {
        if (selectedFolderIds.isEmpty() && selectedNotebookIds.isEmpty()) return
        val nbIds = selectedNotebookIds.toList()
        val fIds = selectedFolderIds.toList()
        lifecycleScope.launch {
            val allFolders = AppDatabase.getDatabase(this@MainActivity).notesDao().getAllFolders()
            // Can't move selected folders into themselves.
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
        content.findViewById<TextView>(R.id.bsFav).text =
            if (notebook.isFavorite) "Remove from Favorites" else "Add to Favorites"
        content.findViewById<View>(R.id.bsRename).setOnClickListener { popup.dismiss(); renameNotebook(notebook) }
        content.findViewById<View>(R.id.bsLock).setOnClickListener {
            popup.dismiss(); android.widget.Toast.makeText(this, "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
        }
        content.findViewById<View>(R.id.bsFav).setOnClickListener { popup.dismiss(); toggleNotebookFavorite(notebook) }
        content.findViewById<View>(R.id.bsMove).setOnClickListener { popup.dismiss(); moveNotebook(notebook) }
        content.findViewById<View>(R.id.bsDuplicate).setOnClickListener { popup.dismiss(); duplicateNotebook(notebook) }
        content.findViewById<View>(R.id.bsDelete).setOnClickListener { popup.dismiss(); showDeleteNotebookDialog(notebook) }
        showAnchoredPopup(popup, anchor)
    }

    /** Creates a rounded Material-3 styled popup window that dismisses on outside touch. */
    private fun buildAnchoredPopup(content: View): android.widget.PopupWindow {
        val popup = android.widget.PopupWindow(
            content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_popup_menu))
        popup.elevation = 8f * resources.displayMetrics.density
        return popup
    }

    /** Anchors the popup over the pressed item, flipping above it when there isn't room below. */
    private fun showAnchoredPopup(popup: android.widget.PopupWindow, anchor: View) {
        popup.contentView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popup.contentView.measuredWidth
        val popupHeight = popup.contentView.measuredHeight
        val xOffset = (anchor.width - popupWidth).coerceAtLeast(0) / 2

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorTop = loc[1]
        val screenHeight = resources.displayMetrics.heightPixels
        val spaceBelow = screenHeight - (anchorTop + anchor.height)

        if (popupHeight > spaceBelow && anchorTop > popupHeight) {
            // Not enough room below → place the popup above the anchor.
            popup.showAsDropDown(anchor, xOffset, -(anchor.height + popupHeight))
        } else {
            popup.showAsDropDown(anchor, xOffset, -anchor.height / 2)
        }
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
        val pickColor = { hex: String -> popup.dismiss(); setFolderColor(folder, hex) }
        content.findViewById<View>(R.id.c1).setOnClickListener { pickColor("#4285F4") }
        content.findViewById<View>(R.id.c2).setOnClickListener { pickColor("#34A853") }
        content.findViewById<View>(R.id.c3).setOnClickListener { pickColor("#FBBC05") }
        content.findViewById<View>(R.id.c4).setOnClickListener { pickColor("#EA4335") }
        content.findViewById<View>(R.id.c5).setOnClickListener { pickColor("#9C27B0") }
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
                        onImportPdf = {
                            dialog.dismiss()
                            importPdf.launch(arrayOf("application/pdf"))
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        setDialogWidth(dialog)
        dialog.show()
    }

    // =========================================================================
    // COMPOSE DIALOGS
    // =========================================================================
    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        val darkTheme = isSystemInDarkTheme()
        val colors = if (darkTheme) darkColorScheme() else lightColorScheme()
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
                // Generate the home thumbnail now so the list shows it instead of a skeleton.
                ThumbnailGenerator.generate(this@MainActivity, id, dest, isImported = false)
                id
            }
            saveNotebookTemplate(newId, settings)
            progress.dismiss()
            loadDataFromDatabase()
            openDrawing(newId)
        }
    }

    /** Persists a notebook's creation template so new pages can match it later. */
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
        // New books start with two pages.
        for (pageNum in 1..2) {
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(settings.pageSize.width, settings.pageSize.height, pageNum).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        if (settings.isCustom && settings.customImgUri != null) {
            // Software-decoded bitmap: hardware bitmaps throw on this software canvas → black page.
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

    // =========================================================================
    // FOLDERS / DELETE
    // =========================================================================
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
        // No page to sample here, so the eyedropper stays off.
        ColorPickerDialog.show(this, initial) { color ->
            val hex = String.format("#%06X", 0xFFFFFF and color)
            lifecycleScope.launch {
                dataManager.updateFolder(folder.copy(colorHex = hex))
                loadDataFromDatabase()
            }
        }
    }

    private fun updateEmptyViewVisibility() {
        emptyViewTextView.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE
        notebooksRecyclerView.visibility = if (displayItems.isEmpty()) View.GONE else View.VISIBLE
    }

    private var pendingDropFolderId: Long? = null

    /** Reused across frames — allocating in a draw pass would churn during every drag. */
    private val dropPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private fun setupDragAndDrop() {
        // Dragging is only active in selection mode: drag a selected item onto a
        // (non-selected) folder to move everything that's selected into it.
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = selectionMode
            // We don't reorder the grid — drag is only used to drop onto a folder.
            override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) pendingDropFolderId = null
            }
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (selectionMode) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END
                } else 0
            }

            /** The folder currently under the drag, highlighted and drawn each frame. */
            private var dropTargetView: View? = null

            /**
             * Resolves the hovered folder from the dragged item's centre.
             *
             * This can't be done in chooseDropTarget: ItemTouchHelper only calls that when
             * it already has candidates under the drag, so hovering off every folder never
             * called it and the previous target stayed pending — which is how items got
             * moved into a folder the user had deliberately dragged away from.
             */
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
                    // Can't drop a selected folder into itself.
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
                // Only while the finger is down. onChildDraw keeps firing during the
                // settle-back animation after release, and recomputing then would clear
                // the target before clearView could act on it.
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
                // Drawn here rather than by toggling a view in each item layout, so the
                // highlight lands correctly for both the grid and list folder layouts.
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
                    // Include the dragged item itself even if it wasn't tapped-selected.
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
            // Extra bottom padding so the FAB never covers the last row.
            notebooksRecyclerView.setPadding(0, 0, 0, systemBars.bottom + dp(96))
            (addNotebookFab.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin = systemBars.bottom + dp(16)
            addNotebookFab.requestLayout()
            (selectionDock.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin = systemBars.bottom + dp(16)
            selectionDock.requestLayout()
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

// =========================================================================
// JETPACK COMPOSE UI SCREENS
// =========================================================================

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
    onImportPdf: () -> Unit
) {
    // Spring the dialog in: fade + scale from 0.92 up to 1.0.
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
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.create_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.create_dialog_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            CreateOptionCard(
                icon = R.drawable.ic_folder,
                accent = ComposeColor(0xFF4285F4),
                title = stringResource(R.string.create_folder),
                subtitle = stringResource(R.string.create_folder_sub),
                onClick = onNewFolder
            )
            Spacer(Modifier.height(10.dp))
            CreateOptionCard(
                icon = R.drawable.ic_pen,
                accent = ComposeColor(0xFF9C27B0),
                title = stringResource(R.string.create_notebook),
                subtitle = stringResource(R.string.create_notebook_sub),
                onClick = onNewNotebook
            )
            Spacer(Modifier.height(10.dp))
            CreateOptionCard(
                icon = R.drawable.ic_pdf,
                accent = ComposeColor(0xFFEA4335),
                title = stringResource(R.string.create_pdf),
                subtitle = stringResource(R.string.create_pdf_sub),
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
private fun CreateOptionCard(
    icon: Int,
    accent: ComposeColor,
    title: String,
    subtitle: String,
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
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    // Keep these 5 in sync with the folder long-press popup swatches.
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
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
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

@Composable
fun BasicTemplateScreen(
    defaultSettings: TemplateSettings?,
    onDismiss: () -> Unit,
    onCreate: (String, TemplateSettings) -> Unit,
    onMoreOptions: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp * 0.90f).dp

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
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
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
        mutableStateOf(when (initialSettings?.type) { "GRID" -> "GRID"; "DOTS" -> "DOTS"; else -> "RULE" })
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

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
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
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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