package com.lochan.octopusnotes

import android.annotation.SuppressLint
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class DrawingActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView
    private lateinit var pdfRecyclerView: ZoomableRecyclerView
    private var pdfAdapter: PdfPageAdapter? = null
    private lateinit var dataManager: DataManager
    private lateinit var pageNumberTextView: TextView
    private lateinit var historyManager: HistoryManager

    private lateinit var strokeManager: StrokeManager
    private lateinit var toolSettingsManager: ToolSettingsManager
    private var pdfEngine: PdfEngine? = null
    private lateinit var drawingRepository: DrawingRepository

    private var notebookId: Long = -1L
    private var currentPage = 0
    private var totalPages = 1
    private var activeToolButton: ImageButton? = null

    // --- Horizontal pen dock state ---
    // Size presets are customizable slots (like the color swatches): each slot keeps its
    // own persisted size, and the active slot is saved + always highlighted.
    private val dockThicknessDefaults = floatArrayOf(5f, 7f, 10f)
    private val dockThickness = FloatArray(3)
    private var dockActiveThicknessIndex = 2
    private val dockDefaultColors = intArrayOf(
        Color.BLACK,
        Color.parseColor("#1565C0"), // blue
        Color.parseColor("#C62828"), // red
        Color.parseColor("#2E7D32"), // green
        Color.parseColor("#F9A825")  // amber
    )
    private val dockColors = IntArray(5)
    private var dockActiveColorIndex = 0

    private var savedDataLoaded = false
    /** True once saved strokes have been decoded into the StrokeManager. Saving before this
     *  point would overwrite the notebook file with an empty document — never allowed. */
    private var strokesLoaded = false
    /** True when strokes changed since the last successful save. */
    private var strokesDirty = false
    /**
     * The page width (px) the in-memory strokes are currently expressed in. Set when they
     * are loaded and kept in step with the laid-out width, so ink survives a rotation or
     * window resize. See [DrawingCodec] for the coordinate space.
     */
    private var inkBaseWidth = 0f
    private var pageRestorePending = true
    private var pdfFilePath: String? = null

    // --- Barrel button (stylus side button) → temporary eraser ---
    /** True while the current stroke was started with the stylus barrel button held. */
    private var barrelStrokeInProgress = false

    // --- Search state ---
    private var pdfTextIndex: PdfTextIndex? = null
    private var searchMatches: List<PdfTextIndex.Match> = emptyList()
    private var searchPos = -1
    private var selectionPopup: android.widget.PopupWindow? = null
    private var selectionTotalDx = 0f
    private var selectionTotalDy = 0f

    // --- Scroll pill state ---
    private lateinit var scrollPillTrack: View
    private lateinit var scrollPillThumb: View
    private var scrollPillDragging = false
    /**
     * Latest finger fraction along the track, captured in ACTION_MOVE and consumed once per
     * Choreographer frame. A fast drag fires many ACTION_MOVEs per frame; coalescing them
     * into one `scrollBy` per frame keeps the main thread responsive instead of issuing a
     * big scroll + dozens of binds for *every* move event (which overshot the 5s input
     * dispatch window and caused the ANR).
     */
    private var dragPendingFraction: Float = 0f
    /** True once the finger actually moved along the track — guards against a bare tap on
     *  the pill (DOWN→UP with no MOVE) jumping the document. */
    private var dragMoved = false
    private var dragFramePosted = false

    /** Max distance the list may travel in one drag frame, in viewport heights. Capping it
     *  keeps a fast pull through a 4000-page PDF at a handful of binds per frame instead of
     *  one giant scrollBy that binds every page it traverses. */
    private val maxDragViewportsPerFrame = 3

    private val dragFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            dragFramePosted = false
            if (!scrollPillDragging) return
            if (applyDragFraction(dragPendingFraction)) {
                // Still catching up to the finger's latest position — keep stepping every
                // frame even if no new MOVE arrived (finger paused mid-drag).
                dragFramePosted = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }
    private val scrollPillFadeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scrollPillFadeRunnable = Runnable {
        if (!scrollPillDragging) scrollPillThumb.animate().alpha(0f).setDuration(300)
            .withEndAction {
                // Hide once faded so it no longer receives touches near the right edge.
                if (!scrollPillDragging && scrollPillThumb.alpha == 0f) {
                    scrollPillThumb.visibility = View.INVISIBLE
                }
            }.start()
    }
    private val highlightsByPage = HashMap<Int, MutableList<RectF>>()
    private var activeHighlight: PdfTextIndex.Match? = null
    private var searchJob: kotlinx.coroutines.Job? = null
    @Volatile private var searchGen = 0
    private val statePrefs by lazy { getSharedPreferences("notebook_state", MODE_PRIVATE) }


    // Image picker for the advanced (change) template dialog.
    private val templateImageUriState = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)
    private val templatePickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            // Copy into app storage — the picker URI is transient and won't decode later.
            templateImageUriState.value = uri?.let { PageTemplate.copyTemplateImage(this, it) } ?: uri
        }

    // --- Image tool ---
    private var selectionTotalScale = 1f
    private val insertImagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { insertImageFromUri(it) }
        }
    private val imagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshImageToolStrip()
        }

    private val searchFillPaint = Paint().apply {
        color = Color.parseColor("#66FFEB3B") // translucent yellow
        style = Paint.Style.FILL
    }
    private val activeFillPaint = Paint().apply {
        color = Color.parseColor("#99FF9800") // stronger orange
        style = Paint.Style.FILL
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_drawing)

        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)

        val notesDao = AppDatabase.getDatabase(this).notesDao()
        dataManager = DataManager(notesDao, cacheDir, filesDir)
        drawingRepository = DrawingRepository(this)
        notebookId = intent.getLongExtra("NOTEBOOK_ID", -1L)
        if (notebookId >= 0) lifecycleScope.launch { dataManager.touchOpened(notebookId) }

        strokeManager = StrokeManager()
        strokeManager.imagesDir = java.io.File(filesDir, "images").apply { mkdirs() }

        currentPage = statePrefs.getInt("last_page_$notebookId", 0)
        totalPages = statePrefs.getInt("page_count_$notebookId", 1)

        historyManager = HistoryManager()
        historyManager.onMutation = { strokesDirty = true }

        setupViews()
        setupEdgeToEdge()
        loadPdf()
    }

    /** Redraws strokes + search highlights on all visible page overlays. */
    private fun invalidateInk() {
        pdfAdapter?.notifyInkChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Drop any coalesced drag frame so it never fires against a torn-down RecyclerView.
        Choreographer.getInstance().removeFrameCallback(dragFrameCallback)
        dragFramePosted = false
        strokeManager.clearBitmapCache()
        pdfEngine?.close()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        pdfRecyclerView = findViewById(R.id.pdfRecyclerView)
        drawingView = findViewById(R.id.drawingView)
        pageNumberTextView = findViewById(R.id.pageNumberTextView)

        toolSettingsManager = ToolSettingsManager(this, drawingView)

        pdfRecyclerView.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if ((r - l) != (oldR - oldL)) onInkWidthChanged()
        }

        pdfRecyclerView.layoutManager = pdfRecyclerView.createZoomAwareLayoutManager()
        pdfRecyclerView.onPageChanged = { page, count -> onPageChanged(page, count) }
        pdfRecyclerView.onZoomChanged = { z -> showZoomIndicator(z) }
        findViewById<TextView>(R.id.zoomIndicator).apply {
            visibility = View.VISIBLE
            text = "100%"
            setOnClickListener { showZoomOptions() }
        }

        // --- TOOL BUTTONS ---
        val backButton: ImageButton = findViewById(R.id.backButton)
        val penButton: ImageButton = findViewById(R.id.penButton)
        val eraserButton: ImageButton = findViewById(R.id.eraserButton)
        val selectionButton: ImageButton = findViewById(R.id.selectionButton)
        val addPageButton: ImageButton = findViewById(R.id.addPageButton)

        val highlighterButton: ImageButton = findViewById(R.id.highlighterButton)
        highlighterButton.setOnClickListener {
            if (activeToolButton != highlighterButton) {
                setActiveTool(highlighterButton)
                toolSettingsManager.applyHighlighterSettings()
            } else {
                toggleToolOptions()
            }
        }

        setupPenDock()
        setupEraserDock()
        setupHighlighterDock()
        setupLassoDock()
        configureDockPlacement()

        val undoButton: ImageButton = findViewById(R.id.undoButton)
        val redoButton: ImageButton = findViewById(R.id.redoButton)

        undoButton.setOnClickListener {
            if (historyManager.undo(strokeManager)) {
                drawingView.clearSelectionVisuals()
                selectionPopup?.dismiss()
                invalidateInk()
                updateUndoRedoButtons()
            }
        }
        redoButton.setOnClickListener {
            if (historyManager.redo(strokeManager)) {
                invalidateInk()
                updateUndoRedoButtons()
            }
        }
        updateUndoRedoButtons()

        backButton.setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.pagesButton).setOnClickListener { showPageGrid() }

        penButton.setOnClickListener {
            if (activeToolButton != penButton) {
                setActiveTool(penButton)
                toolSettingsManager.applyPenSettings()
            } else {
                toggleToolOptions()
            }
        }

        eraserButton.setOnClickListener {
            if (activeToolButton != eraserButton) {
                setActiveTool(eraserButton)
                toolSettingsManager.applyEraserSettings()
            } else {
                toggleToolOptions()
            }
        }

        selectionButton.setOnClickListener {
            if (activeToolButton != selectionButton) {
                setActiveTool(selectionButton)
                drawingView.setTool(DrawingView.Tool.LASSO)
                drawingView.setDrawingMode(true)
            } else {
                toggleToolOptions()
            }
        }

        val imageButton: ImageButton = findViewById(R.id.imageButton)
        imageButton.setOnClickListener {
            if (activeToolButton != imageButton) {
                setActiveTool(imageButton)
                // The image tool doesn't draw — touches scroll; a chosen image enters
                // selection mode which handles its own touches.
                drawingView.setDrawingMode(false)
            } else {
                toggleToolOptions()
            }
        }
        findViewById<View>(R.id.imageGrantButton).setOnClickListener {
            imagePermissionLauncher.launch(imagesPermission())
        }
        findViewById<ImageButton>(R.id.imagePickButton).setOnClickListener {
            insertImagePickerLauncher.launch("image/*")
        }
        drawingView.selectionBitmapProvider = { strokeManager.bitmapFor(it) }

        addPageButton.setOnClickListener { showAddPagePopup(addPageButton) }
        pageNumberTextView.setOnClickListener { showJumpToPageDialog() }

        setupScrollPill()

        // --- DRAWING VIEW CALLBACKS ---
        drawingView.onStrokeFinishedListener = { pageIndex, path, paint, contourData ->
            // Barrel-button strokes route to the eraser regardless of the selected tool,
            // using the dock's current eraser type (pixel or stroke).
            val effectiveToolId = if (barrelStrokeInProgress) R.id.eraserButton else activeToolButton?.id

            if (effectiveToolId == R.id.eraserButton && toolSettingsManager.currentEraserType == DrawingView.Tool.PIXEL_ERASER) {
                // Deep-copy the page's strokes on the main thread — the only thread allowed
                // to touch live ink — then run the expensive path geometry on those private
                // copies on a background thread. The old code read the live Path/Paint
                // objects from Dispatchers.Default while the main thread drew / erased them,
                // which could throw ConcurrentModificationException or race Skia internals.
                val snapshot = strokeManager.snapshotPageStrokes(pageIndex)
                // Capture the eraser geometry on the main thread too, so the coroutine
                // never even reads the stroke-callback objects from the background.
                val eraserPath = Path(path)
                val eraserSize = paint.strokeWidth
                lifecycleScope.launch {
                    val action = withContext(Dispatchers.Default) {
                        snapshot?.let { strokeManager.computePixelErase(pageIndex, eraserPath, eraserSize, it) }
                    }
                    if (action != null) {
                        historyManager.execute(action, strokeManager)
                        invalidateInk()
                        updateUndoRedoButtons()
                    }
                }
            } else {
                val action = when (effectiveToolId) {
                    R.id.eraserButton -> strokeManager.processErase(pageIndex, path, paint, DrawingView.Tool.STROKE_ERASER)
                    R.id.highlighterButton -> handleHighlighterStrokeAction(pageIndex, path, paint, contourData)
                    else -> handlePenStrokeAction(pageIndex, path, paint, contourData)
                }

                if (action != null) {
                    historyManager.execute(action, strokeManager)
                    invalidateInk()
                    updateUndoRedoButtons()
                    maybeAutoAppendPage(pageIndex)
                }
            }
        }

        drawingView.onLassoFinishedListener = { lassoScreenPath ->
            handleLassoSelection(lassoScreenPath)
        }
        drawingView.onSelectionMovedListener = { dx, dy, scale ->
            handleSelectionCommit(dx, dy, scale)
        }

        drawingView.onFingerLongPress = { x, y -> showPastePopup(x, y) }

        drawingView.onBarrelButtonChanged = { pressed ->
            if (pressed) {
                barrelStrokeInProgress = true
                // Temporary eraser honours the dock's current eraser type + size.
                drawingView.setTool(toolSettingsManager.currentEraserType)
                drawingView.setBrushSize(toolSettingsManager.lastEraserSize)
                drawingView.setDrawingMode(true)
            } else {
                barrelStrokeInProgress = false
                // Restore whatever tool is actually selected in the dock — never a stale
                // snapshot, which could clobber a tool the user switched to meanwhile.
                when (activeToolButton?.id) {
                    R.id.eraserButton -> toolSettingsManager.applyEraserSettings()
                    R.id.highlighterButton -> toolSettingsManager.applyHighlighterSettings()
                    R.id.selectionButton -> {
                        drawingView.setTool(DrawingView.Tool.LASSO)
                        drawingView.setDrawingMode(true)
                    }
                    else -> toolSettingsManager.applyPenSettings()
                }
            }
        }

        // --- SEARCH ---
        val searchButton: ImageButton = findViewById(R.id.searchButton)
        val searchBar: View = findViewById(R.id.searchBar)
        val searchInput: android.widget.EditText = findViewById(R.id.searchInput)
        val searchCount: TextView = findViewById(R.id.searchCount)

        searchButton.setOnClickListener {
            val show = searchBar.visibility != View.VISIBLE
            if (show) animateSearchBar(searchBar, true) { searchInput.requestFocus() }
            else animateSearchBar(searchBar, false)
        }
        findViewById<ImageButton>(R.id.searchClose).setOnClickListener {
            // Stop any in-flight streaming search (bumping the generation makes
            // shouldStop() return true and guards stray UI callbacks).
            searchJob?.cancel()
            searchGen++
            animateSearchBar(searchBar, false)
            searchMatches = emptyList(); searchPos = -1; setActiveHighlight(null)
            highlightsByPage.clear(); invalidateInk()
            searchCount.text = ""
            setSearchNavEnabled(false)
            findViewById<View>(R.id.searchProgressPill).visibility = View.GONE
        }
        findViewById<ImageButton>(R.id.searchNext).setOnClickListener { stepSearch(+1, searchCount) }
        findViewById<ImageButton>(R.id.searchPrev).setOnClickListener { stepSearch(-1, searchCount) }
        findViewById<ImageButton>(R.id.searchDropdown).setOnClickListener {
            showSearchResultsDropdown(searchBar, searchCount)
        }
        setSearchNavEnabled(false)
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                runSearch(searchInput.text.toString(), searchCount); true
            } else false
        }

        // --- OVERFLOW (3 dots) ---
        val overflowButton: ImageButton = findViewById(R.id.overflowButton)
        overflowButton.setOnClickListener { showOverflowMenu(overflowButton) }
    }

    private fun updateUndoRedoButtons() {
        val undoBtn: ImageButton = findViewById(R.id.undoButton)
        val redoBtn: ImageButton = findViewById(R.id.redoButton)
        undoBtn.isEnabled = historyManager.canUndo()
        redoBtn.isEnabled = historyManager.canRedo()
        undoBtn.alpha = if (historyManager.canUndo()) 1f else 0.3f
        redoBtn.alpha = if (historyManager.canRedo()) 1f else 0.3f
    }

    // --- Highlighter text snapping (uses PdfTextIndex) ---

    /**
     * Pen stroke — unless it was a scribble gesture over existing ink, in which case the
     * strokes underneath are erased instead (scribble-to-erase, pen tool only).
     */
    private fun handlePenStrokeAction(pageIndex: Int, path: Path, paint: Paint, contourData: FloatArray? = null): DrawingAction? {
        if (drawingView.lastStrokeWasScribble) {
            val erasePaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = paint.strokeWidth.coerceAtLeast(12f)
            }
            val erase = strokeManager.processErase(pageIndex, path, erasePaint, DrawingView.Tool.STROKE_ERASER)
            if (erase != null) return erase
            // Nothing under the scribble — it's just ink; fall through and draw it.
        }
        return strokeManager.processPen(pageIndex, path, paint, DrawingView.Tool.PEN, drawingView.penLineStyle, contourData)
    }

    private fun handleHighlighterStrokeAction(pageIndex: Int, pagePath: Path, paint: Paint, contourData: FloatArray? = null): DrawingAction? {
        if (!toolSettingsManager.highlighterTextMode) {
            return strokeManager.processPen(pageIndex, pagePath, paint, DrawingView.Tool.HIGHLIGHTER, contourData = contourData)
        }

        val path = pdfFilePath ?: return strokeManager.processPen(pageIndex, pagePath, paint, DrawingView.Tool.HIGHLIGHTER)

        val index = pdfTextIndex ?: PdfTextIndex(File(path)).also { pdfTextIndex = it }

        if (!index.isReady) {
            lifecycleScope.launch(Dispatchers.IO) { index.ensureTextLayer() }
            return strokeManager.processPen(pageIndex, pagePath, paint, DrawingView.Tool.HIGHLIGHTER)
        }

        val dragBounds = RectF().also { pagePath.computeBounds(it, true) }
        // Strokes are authored in the laid-out child's coordinate space — use the actual
        // child dimensions so snapped rects land exactly where the text is. (The old
        // strokePageSizes() lookup returned a default A4 aspect for pages without ink,
        // misplacing highlights vertically on non-A4 PDFs.)
        val child = pdfRecyclerView.findViewHolderForAdapterPosition(pageIndex)?.itemView
        val pw: Float
        val ph: Float
        if (child != null && child.width > 0 && child.height > 0) {
            pw = child.width.toFloat()
            ph = child.height.toFloat()
        } else {
            pw = pdfRecyclerView.width.toFloat()
            val s = pdfEngine?.getPageSize(pageIndex)
            ph = if (s != null) pw * (s.height / s.width) else pw * 1.414f
        }
        val boxes = index.textBoxesForPage(pageIndex)
        val snapped = computeTextHighlights(dragBounds, boxes, pw, ph, true)

        if (snapped.isEmpty()) {
            return strokeManager.processPen(pageIndex, pagePath, paint, DrawingView.Tool.HIGHLIGHTER)
        } else {
            val actions = mutableListOf<DrawingAction>()
            for (rectPath in snapped) {
                val fill = Paint(paint).apply { style = Paint.Style.FILL }
                // Uses named arguments so the default UUID is automatically applied
                val stroke = StrokeData(path = rectPath, paint = fill, isPixelEraser = false, type = StrokeType.HIGHLIGHTER)
                actions.add(DrawingAction.AddStroke(pageIndex, stroke))
            }
            return DrawingAction.BatchAction(actions)
        }
    }
    private fun computeTextHighlights(drag: RectF, normBoxes: List<RectF>, pw: Float, ph: Float, round: Boolean): List<Path> {
        if (pw <= 0f || ph <= 0f || normBoxes.isEmpty()) return emptyList()
        val dl = drag.left / pw; val dt = drag.top / ph; val dr = drag.right / pw; val db = drag.bottom / ph
        val out = mutableListOf<Path>()
        for (b in normBoxes) {
            val vOverlap = minOf(db, b.bottom) - maxOf(dt, b.top)
            if (vOverlap <= 0f) continue
            val hl = maxOf(dl, b.left); val hr = minOf(dr, b.right)
            if (hr <= hl) continue
            val rect = RectF(hl * pw, b.top * ph, hr * pw, b.bottom * ph)
            val padY = rect.height() * 0.12f
            rect.top -= padY; rect.bottom += padY
            val p = Path()
            if (round) {
                val r = rect.height() * 0.18f
                p.addRoundRect(rect, r, r, Path.Direction.CW)
            } else {
                p.addRect(rect, Path.Direction.CW)
            }
            out.add(p)
        }
        return out
    }

    // --- Search methods ---

    private fun runSearch(query: String, countView: TextView) {
        val path = pdfFilePath ?: run {
            Toast.makeText(this, "Open a PDF to search", Toast.LENGTH_SHORT).show()
            return
        }
        if (query.isBlank()) return
        // Native (pdfium) search when the platform supports it; pdfbox index as fallback.
        val useNative = NativePdfSearch.isSupported
        val index = if (useNative) null
        else pdfTextIndex ?: PdfTextIndex(File(path)).also { pdfTextIndex = it }

        // Start a fresh search, superseding any in-flight one.
        searchJob?.cancel()
        val gen = ++searchGen
        searchMatches = emptyList(); searchPos = -1; setActiveHighlight(null)
        highlightsByPage.clear(); invalidateInk()
        setSearchNavEnabled(false)
        countView.text = "…"

        val progressPill: TextView = findViewById(R.id.searchProgressPill)
        progressPill.text = "Searching pages…"
        progressPill.visibility = View.VISIBLE

        val accumulated = mutableListOf<PdfTextIndex.Match>()
        searchJob = lifecycleScope.launch {
            var failed = false
            withContext(Dispatchers.IO) {
                try {
                    // Emits matches page-by-page so results appear instantly, not after the whole PDF.
                    val emit = { pageIndex: Int, total: Int, matches: List<PdfTextIndex.Match> ->
                        runOnUiThread {
                            if (gen != searchGen) return@runOnUiThread
                            progressPill.text = "Searching pages…  ${pageIndex + 1} / $total"
                            if (matches.isNotEmpty()) {
                                accumulated.addAll(matches)
                                for (m in matches) {
                                    highlightsByPage.getOrPut(m.pageIndex) { mutableListOf() }.add(m.rect)
                                }
                                searchMatches = accumulated.toList()
                                setSearchNavEnabled(true)
                                if (searchPos == -1) {
                                    searchPos = 0
                                    focusMatch(0, countView) // jump to first result immediately
                                } else {
                                    countView.text = "${searchPos + 1}/${searchMatches.size}"
                                }
                                invalidateInk()
                            }
                        }
                    }
                    if (useNative) {
                        NativePdfSearch(File(path)).streamSearch(query, shouldStop = { gen != searchGen }, onPage = emit)
                    } else {
                        index!!.streamSearch(query, shouldStop = { gen != searchGen }, onPage = emit)
                    }
                } catch (t: Throwable) {
                    t.printStackTrace(); failed = true
                }
            }
            if (gen != searchGen) return@launch
            progressPill.visibility = View.GONE
            if (failed) {
                countView.text = ""
                setSearchNavEnabled(false)
                Toast.makeText(
                    this@DrawingActivity,
                    "This PDF is too large to search on this device.",
                    Toast.LENGTH_LONG
                ).show()
            } else if (accumulated.isEmpty()) {
                countView.text = "0/0"
                Toast.makeText(
                    this@DrawingActivity,
                    "No text matches. This PDF may be scanned – OCR is not yet supported.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Slides + fades the search bar in/out (it's anchored at the top, below the dock). */
    private fun animateSearchBar(searchBar: View, show: Boolean, onShown: (() -> Unit)? = null) {
        searchBar.animate().cancel()
        val slide = (40 * resources.displayMetrics.density)
        if (show) {
            if (searchBar.visibility != View.VISIBLE) {
                searchBar.visibility = View.VISIBLE
                searchBar.alpha = 0f
                searchBar.translationY = -slide
            }
            searchBar.animate()
                .alpha(1f).translationY(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { onShown?.invoke() }
                .start()
        } else {
            if (searchBar.visibility != View.VISIBLE) return
            searchBar.animate()
                .alpha(0f).translationY(-slide)
                .setDuration(160)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    searchBar.visibility = View.GONE
                    searchBar.translationY = 0f
                    searchBar.alpha = 1f
                }
                .start()
        }
    }

    private fun setSearchNavEnabled(enabled: Boolean) {
        val next: ImageButton = findViewById(R.id.searchNext)
        val prev: ImageButton = findViewById(R.id.searchPrev)
        next.isEnabled = enabled
        prev.isEnabled = enabled
        next.alpha = if (enabled) 1f else 0.3f
        prev.alpha = if (enabled) 1f else 0.3f
    }

    private fun setActiveHighlight(m: PdfTextIndex.Match?) {
        activeHighlight = m
        pdfAdapter?.activeHighlightPage = m?.pageIndex ?: -1
        pdfAdapter?.activeHighlightRect = m?.rect
    }

    private fun jumpToPage(page: Int) {
        (pdfRecyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(page.coerceIn(0, (totalPages - 1).coerceAtLeast(0)), 0)
    }

    private fun focusMatch(pos: Int, countView: TextView) {
        val m = searchMatches[pos]
        setActiveHighlight(m)
        jumpToPage(m.pageIndex)
        invalidateInk()
        countView.text = "${pos + 1}/${searchMatches.size}"
    }

    private fun stepSearch(dir: Int, countView: TextView) {
        if (searchMatches.isEmpty()) return
        searchPos = (searchPos + dir + searchMatches.size) % searchMatches.size
        focusMatch(searchPos, countView)
    }

    /** Bolds and yellow-highlights every occurrence of [query] within [text]. */
    private fun highlightQuery(text: String, query: String): CharSequence {
        if (query.isBlank()) return text
        val span = android.text.SpannableString(text)
        val lower = text.lowercase()
        val q = query.lowercase()
        var from = 0
        while (true) {
            val idx = lower.indexOf(q, from)
            if (idx < 0) break
            val end = idx + q.length
            span.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), idx, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(android.text.style.BackgroundColorSpan(android.graphics.Color.parseColor("#66FFEB3B")), idx, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.BLACK), idx, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            from = end
        }
        return span
    }

    /** Rounded dropdown listing each match with a context snippet; tapping jumps to it. */
    private fun showSearchResultsDropdown(anchor: View, countView: TextView) {
        if (searchMatches.isEmpty()) {
            Toast.makeText(this, "No results yet", Toast.LENGTH_SHORT).show()
            return
        }
        val content = layoutInflater.inflate(R.layout.popup_search_results, null)
        val container = content.findViewById<android.widget.LinearLayout>(R.id.searchResultsContainer)
        val popup = android.widget.PopupWindow(
            content, anchor.width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        popup.setBackgroundDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_popup_menu))
        popup.elevation = 8f * resources.displayMetrics.density

        searchMatches.forEachIndexed { index, m ->
            val row = layoutInflater.inflate(R.layout.item_search_result, container, false)
            row.findViewById<TextView>(R.id.resultPage).text = "PAGE ${m.pageIndex + 1}"
            row.findViewById<TextView>(R.id.resultSnippet).text =
                highlightQuery(m.snippet.ifBlank { m.text }, m.text)
            row.setOnClickListener {
                searchPos = index
                focusMatch(index, countView)
                popup.dismiss()
            }
            container.addView(row)
        }

        // Cap height so long lists scroll instead of covering the screen.
        content.measure(
            View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxPx = (320 * resources.displayMetrics.density).toInt()
        if (content.measuredHeight > maxPx) popup.height = maxPx

        popup.showAsDropDown(anchor, 0, (4 * resources.displayMetrics.density).toInt())
    }

    // --- PDF LOADING (native PdfRenderer engine) ---

    private fun loadPdf() {
        findViewById<View>(R.id.pdfLoadingOverlay).visibility = View.VISIBLE
        lifecycleScope.launch {
            if (pdfFilePath == null && notebookId >= 0) {
                pdfFilePath = withContext(Dispatchers.IO) { dataManager.getNotebook(notebookId)?.pdfPath }
            }
            val path = pdfFilePath
            val file = if (path != null && File(path).exists()) {
                findViewById<View>(R.id.searchButton).visibility = View.VISIBLE
                File(path)
            } else {
                findViewById<View>(R.id.searchButton).visibility = View.GONE
                val bytes = withContext(Dispatchers.Default) { createBlankPdfBytes(totalPages) }
                File(cacheDir, "blank_$notebookId.pdf").apply { writeBytes(bytes) }
            }
            setupPdfEngine(file)
        }
    }

    /**
     * (Re)builds the PDF engine + adapter for [file].
     *
     * [restoreScroll] = (firstVisiblePosition, topOffsetPx). When provided, the view is restored
     * to that exact scroll position instead of jumping to [currentPage] — used for in-place edits
     * (e.g. auto-appending a page) so the user isn't yanked elsewhere.
     */
    private fun setupPdfEngine(file: File, restoreScroll: Pair<Int, Int>? = null) {
        pageRestorePending = true
        pdfEngine?.close()
        val engine = try {
            PdfEngine(file)
        } catch (e: Exception) {
            findViewById<View>(R.id.pdfLoadingOverlay).visibility = View.GONE
            Toast.makeText(this, "Couldn't open this PDF", Toast.LENGTH_LONG).show()
            return
        }
        pdfEngine = engine
        totalPages = engine.pageCount
        statePrefs.edit().putInt("page_count_$notebookId", totalPages).apply()
        // Warm the size cache on the engine's render thread so scrolling never blocks on getPageSize().
        lifecycleScope.launch { engine.prefetchSizes() }

        val adapter = PdfPageAdapter(engine, lifecycleScope, strokeManager).apply {
            highlightsByPage = this@DrawingActivity.highlightsByPage
            searchFillPaint = this@DrawingActivity.searchFillPaint
            activeFillPaint = this@DrawingActivity.activeFillPaint
            zoom = pdfRecyclerView.zoom // keep zoom consistent across reloads
            onFirstRender = {
                findViewById<View>(R.id.pdfLoadingOverlay).visibility = View.GONE
            }
        }
        pdfAdapter = adapter
        pdfRecyclerView.adapter = adapter
        drawingView.setPdfRecyclerView(pdfRecyclerView, adapter, engine)

        // Sharp zoom: re-render the visible region at screen resolution once the zoom settles.
        pdfRecyclerView.clearHiResTiles()
        pdfRecyclerView.hiResScope = lifecycleScope
        pdfRecyclerView.hiResRenderer = { page, lx, ly, z, childW, outW, outH ->
            engine.renderRegion(page, lx, ly, z, childW, outW, outH)
        }
        pdfRecyclerView.drawPageInk = { canvas, page, pageW, pageH ->
            strokeManager.drawPageStrokes(page, canvas, 1f, 1f)
            // Search highlights (normalised page coords) so tiles don't hide them.
            highlightsByPage[page]?.forEach { r ->
                val active = pdfAdapter?.activeHighlightPage == page && pdfAdapter?.activeHighlightRect == r
                canvas.drawRect(
                    r.left * pageW, r.top * pageH, r.right * pageW, r.bottom * pageH,
                    if (active) activeFillPaint else searchFillPaint
                )
            }
        }

        currentPage = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        pdfRecyclerView.post {
            if (restoreScroll != null) {
                (pdfRecyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(
                        restoreScroll.first.coerceIn(0, (totalPages - 1).coerceAtLeast(0)),
                        restoreScroll.second
                    )
            } else {
                jumpToPage(currentPage)
            }
            updateScrollPillPosition()
            pdfRecyclerView.post { pageRestorePending = false }
        }
        updatePageNumberView()

        if (!savedDataLoaded) {
            savedDataLoaded = true
            loadSavedDrawing()
        }
        if (pdfFilePath != null) startBackgroundOutlineLoad()
    }

    private fun createBlankPdfBytes(pageCount: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val linePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f; style = Paint.Style.STROKE }
        for (i in 1..pageCount) {
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            var y = 100f
            while (y < 800f) { canvas.drawLine(50f, y, 545f, y, linePaint); y += 30f }
            document.finishPage(page)
        }
        document.writeTo(outputStream)
        document.close()
        return outputStream.toByteArray()
    }

    // --- Add page / delete (search index must be cleared on modifications) ---

    private fun showAddPagePopup(anchor: View) {
        val view = layoutInflater.inflate(R.layout.popup_add_page, null)
        val widthPx = (260 * resources.displayMetrics.density).toInt()
        val popup = android.widget.PopupWindow(
            view,
            widthPx,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)
        view.findViewById<View>(R.id.optionBefore).setOnClickListener { addPageAt(currentPage); popup.dismiss() }
        view.findViewById<View>(R.id.optionAfter).setOnClickListener { addPageAt(currentPage + 1); popup.dismiss() }
        view.findViewById<View>(R.id.optionEnd).setOnClickListener { addPageAt(totalPages); popup.dismiss() }
        popup.showAsDropDown(anchor, 0, 8)
    }

    // --- Per-notebook page template ---

    private fun loadNotebookTemplate(): PageTemplate? =
        PageTemplate.fromJson(
            getSharedPreferences("OctopusNotesPrefs", MODE_PRIVATE).getString("template_$notebookId", null)
        )

    private fun saveNotebookTemplate(tpl: PageTemplate) {
        getSharedPreferences("OctopusNotesPrefs", MODE_PRIVATE)
            .edit().putString("template_$notebookId", tpl.toJson()).apply()
    }

    private fun currentPageSizePoints(): Pair<Int, Int> = try {
        val s = pdfEngine!!.getPageSize(currentPage)
        Pair(s.width.toInt().coerceAtLeast(1), s.height.toInt().coerceAtLeast(1))
    } catch (e: Exception) {
        Pair(595, 842)
    }

    /** Compact preset chooser (Blank / Ruled / Grid / Dotted) sized to the current page. */
    private fun showTemplateChooser(onPick: (PageTemplate) -> Unit) {
        val labels = arrayOf("Blank", "Ruled", "Grid", "Dotted")
        val codes = arrayOf("BLANK", "RULE", "GRID", "DOTS")
        val (w, h) = currentPageSizePoints()
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose template")
            .setItems(labels) { _, which -> onPick(PageTemplate.preset(codes[which], w, h)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPageAt(insertIndex: Int) {
        val path = pdfFilePath
        if (path == null) {
            // Legacy blank notebook with no backing PDF.
            historyManager.clear()
            strokeManager.shiftPages(insertIndex, totalPages)
            strokesDirty = true
            pageMeta.onPageInserted(insertIndex)
            totalPages++
            statePrefs.edit().putInt("page_count_$notebookId", totalPages).apply()
            currentPage = insertIndex
            loadPdf()
            Toast.makeText(this, "Page added", Toast.LENGTH_SHORT).show()
            return
        }
        val tpl = loadNotebookTemplate()
        if (tpl != null) {
            insertTemplatePage(path, insertIndex, tpl)
        } else {
            // No stored template – show the full template picker (and remember the choice).
            showAddPageTemplateDialog(path, insertIndex)
        }
    }

    /** Full (advanced) template picker for adding a page to a PDF with no stored template. */
    private fun showAddPageTemplateDialog(path: String, insertIndex: Int) {
        templateImageUriState.value = null
        val dialog = android.app.Dialog(this, R.style.Theme_OctopusNotes)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DrawingActivity)
            setViewTreeViewModelStoreOwner(this@DrawingActivity)
            setViewTreeSavedStateRegistryOwner(this@DrawingActivity)
            setContent {
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                androidx.compose.material3.MaterialTheme(
                    colorScheme = if (dark) androidx.compose.material3.darkColorScheme()
                    else androidx.compose.material3.lightColorScheme()
                ) {
                    AdvancedTemplateScreen(
                        initialSettings = null,
                        customUriState = templateImageUriState,
                        onPickImage = { templatePickImageLauncher.launch("image/*") },
                        onDismiss = { dialog.dismiss() },
                        onCreate = { _, _, _ -> },
                        changeMode = true,
                        applyLabel = "Add page",
                        showApplyAll = false,
                        onApply = { settings, _ ->
                            dialog.dismiss()
                            val tpl = settings.toPageTemplate()
                            saveNotebookTemplate(tpl)
                            insertTemplatePage(path, insertIndex, tpl)
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.window?.let { w ->
            val lp = android.view.WindowManager.LayoutParams()
            lp.copyFrom(w.attributes)
            lp.width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            w.attributes = lp
        }
        dialog.show()
    }

    private fun insertTemplatePage(path: String, insertIndex: Int, tpl: PageTemplate) {
        historyManager.clear()
        val busy = showBusyDialog("Adding page…")
        busy.show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { insertTemplatePageIntoPdf(File(path), insertIndex, tpl) }
            } catch (e: Exception) {
                e.printStackTrace()
                busy.dismiss()
                Toast.makeText(this@DrawingActivity, "Couldn't save the PDF — please try again", Toast.LENGTH_LONG).show()
                return@launch
            }
            busy.dismiss()

            strokeManager.shiftPages(insertIndex, totalPages)
            strokesDirty = true
            pageMeta.onPageInserted(insertIndex)
            totalPages++
            currentPage = insertIndex
            saveDrawing()
            invalidateTextIndex()
            loadPdf()
            Toast.makeText(this@DrawingActivity, "Page added", Toast.LENGTH_SHORT).show()
        }
    }

    private var isAutoAppending = false

    /**
     * "Add pages continuously": when the user draws on the last page (and the setting is on),
     * silently append a fresh page to the end using the notebook template. Appending at the end
     * shifts no existing pages, so undo history and stroke page-indices stay valid, and we keep
     * the user on the page they're writing on (no jump).
     */
    private fun maybeAutoAppendPage(pageIndex: Int) {
        val prefs = getSharedPreferences("OctopusNotesPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("continuous_pages", false)) return
        if (pageIndex != totalPages - 1 || isAutoAppending) return
        val path = pdfFilePath ?: return

        isAutoAppending = true
        val tpl = loadNotebookTemplate() ?: run {
            val (w, h) = currentPageSizePoints()
            PageTemplate.preset("BLANK", w, h).also { saveNotebookTemplate(it) }
        }
        lifecycleScope.launch {
            // Write the page and open a fresh engine, all off the main thread. The old engine
            // keeps serving renders from its open FD in the meantime, so drawing never pauses.
            val newEngine = withContext(Dispatchers.IO) {
                try {
                    insertTemplatePageIntoPdf(File(path), totalPages, tpl)
                    PdfEngine(File(path))
                } catch (e: Exception) {
                    null
                }
            }
            if (newEngine == null) {
                isAutoAppending = false
                return@launch
            }

            // Seamless swap: same adapter, same bitmap cache, same scroll — the only UI
            // change is one new item appended below. No reload, no white flash.
            val oldEngine = pdfEngine
            pdfEngine = newEngine
            lifecycleScope.launch { newEngine.prefetchSizes() }
            pdfAdapter?.let { adapter ->
                adapter.swapEngine(newEngine)
                adapter.notifyItemInserted(totalPages)
                drawingView.setPdfRecyclerView(pdfRecyclerView, adapter, newEngine)
            }
            pdfRecyclerView.hiResRenderer = { page, lx, ly, z, cw, ow, oh ->
                newEngine.renderRegion(page, lx, ly, z, cw, ow, oh)
            }
            oldEngine?.close()

            totalPages++
            statePrefs.edit().putInt("page_count_$notebookId", totalPages).apply()
            updatePageNumberView()
            saveDrawing()
            invalidateTextIndex()
            isAutoAppending = false
        }
    }

    /** Loads a PDF spooling parse buffers to disk, so large files don't OOM. */
    private fun loadPdfLowMemory(file: File): PDDocument {
        val mem = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().setTempDir(cacheDir)
        return PDDocument.load(file, mem)
    }

    /**
     * Atomically replaces [file] with the freshly written [tmp] (same directory, same
     * filesystem). The current [file] is first rotated to a `.bak`; the tmp is renamed
     * into place; if that rename fails the backup is restored so the notebook's PDF is
     * never left missing, and the tmp is cleaned up. On success the previous version
     * stays as `.bak` (a crash-safety copy, matching [DrawingRepository.save]'s policy)
     * and the next edit overwrites it. Throws [java.io.IOException] when the swap can't
     * be completed, so callers can abort their in-memory mutations instead of letting
     * the PDF desync from the stroke/page state.
     */
    private fun swapPdfIntoPlace(tmp: File, file: File) {
        val bak = File(file.parentFile, file.name + ".bak")
        if (file.exists()) {
            bak.delete()
            if (!file.renameTo(bak)) {
                tmp.delete()
                throw java.io.IOException("Failed to rotate ${file.name} to backup")
            }
        }
        if (!tmp.renameTo(file)) {
            // The new file didn't land — put the original back so the notebook keeps a PDF.
            if (!bak.renameTo(file)) {
                // Double failure: the original couldn't be restored either. Surface both
                // so it's clear the PDF is missing, not silently "fine".
                tmp.delete()
                throw java.io.IOException(
                    "Failed to move ${tmp.name} into place (and could not restore ${bak.name})"
                )
            }
            tmp.delete()
            throw java.io.IOException("Failed to move ${tmp.name} into place")
        }
        // Success: the stale tmp is gone (renamed away); `.bak` holds the previous version.
    }

    private fun insertTemplatePageIntoPdf(file: File, insertIndex: Int, tpl: PageTemplate) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        loadPdfLowMemory(file).use { doc ->
            val count = doc.numberOfPages
            val refIndex = if (insertIndex < count) insertIndex else count - 1
            val box = if (refIndex in 0 until count) doc.getPage(refIndex).mediaBox else PDRectangle.A4
            val newPage = PDPage(PDRectangle(box.width, box.height))
            drawTemplateBackground(doc, newPage, box.width, box.height, tpl, prepend = false)

            if (insertIndex >= count) doc.pages.add(newPage)
            else doc.pages.insertBefore(newPage, doc.getPage(insertIndex))

            doc.save(tmp)
        }
        swapPdfIntoPlace(tmp, file)
    }

    /**
     * Draws [tpl] as a full-page image. When [prepend] is true the background is written
     * *under* any existing page content (so original PDF content stays visible on top).
     */
    private fun drawTemplateBackground(
        doc: PDDocument,
        page: PDPage,
        wPt: Float,
        hPt: Float,
        tpl: PageTemplate,
        prepend: Boolean
    ) {
        val scale = 2
        val bmp = tpl.renderBitmap(this, (wPt * scale).toInt(), (hPt * scale).toInt())
        try {
            val img = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, bmp)
            val mode = if (prepend) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
            PDPageContentStream(doc, page, mode, true, true).use { cs ->
                cs.drawImage(img, 0f, 0f, wPt, hPt)
            }
        } finally {
            bmp.recycle()
        }
    }

    // --- Change template of current / all pages (from overflow menu) ---

    /** Opens the full (advanced) template editor in change mode, with Apply / Apply-to-whole-PDF. */
    private fun showChangeTemplateDialog() {
        if (pdfFilePath == null) {
            Toast.makeText(this, "No PDF page to change", Toast.LENGTH_SHORT).show()
            return
        }
        val initial = loadNotebookTemplate()?.toTemplateSettings()
        templateImageUriState.value = initial?.customImgUri

        val dialog = android.app.Dialog(this, R.style.Theme_OctopusNotes)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DrawingActivity)
            setViewTreeViewModelStoreOwner(this@DrawingActivity)
            setViewTreeSavedStateRegistryOwner(this@DrawingActivity)
            setContent {
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                androidx.compose.material3.MaterialTheme(
                    colorScheme = if (dark) androidx.compose.material3.darkColorScheme()
                    else androidx.compose.material3.lightColorScheme()
                ) {
                    AdvancedTemplateScreen(
                        initialSettings = initial,
                        customUriState = templateImageUriState,
                        onPickImage = { templatePickImageLauncher.launch("image/*") },
                        onDismiss = { dialog.dismiss() },
                        onCreate = { _, _, _ -> },
                        changeMode = true,
                        onApply = { settings, allPages ->
                            dialog.dismiss()
                            confirmAndApplyTemplate(settings.toPageTemplate(), allPages)
                        }
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.window?.let { w ->
            val lp = android.view.WindowManager.LayoutParams()
            lp.copyFrom(w.attributes)
            lp.width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            w.attributes = lp
        }
        dialog.show()
    }

    private fun PageTemplate.toTemplateSettings(): TemplateSettings = TemplateSettings(
        type = type,
        bgColor = androidx.compose.ui.graphics.Color(bgColor),
        density = density,
        brightness = brightness,
        thickness = thickness,
        lineColor = androidx.compose.ui.graphics.Color(lineColor),
        pageSize = PageSize("Custom", pageW, pageH),
        isCustom = isCustom,
        customImgUri = customUri?.let { android.net.Uri.parse(it) }
    )

    private fun TemplateSettings.toPageTemplate(): PageTemplate = PageTemplate(
        type = type,
        bgColor = bgColor.toArgb(),
        density = density,
        brightness = brightness,
        thickness = thickness,
        lineColor = lineColor.toArgb(),
        pageW = pageSize.width,
        pageH = pageSize.height,
        isCustom = isCustom,
        customUri = customImgUri?.toString()
    )

    private fun confirmAndApplyTemplate(tpl: PageTemplate, allPages: Boolean) {
        val path = pdfFilePath ?: return
        lifecycleScope.launch {
            val hasContent = withContext(Dispatchers.IO) {
                try {
                    loadPdfLowMemory(File(path)).use { doc ->
                        val range = if (allPages) 0 until doc.numberOfPages else currentPage..currentPage
                        range.any { it in 0 until doc.numberOfPages && pageHasOriginalContent(doc.getPage(it)) }
                    }
                } catch (e: Exception) { false }
            }
            val scope = if (allPages) "all $totalPages pages" else "this page"
            val message = buildString {
                append("Apply this template to $scope?")
                if (hasContent) append("\n\nSome of these pages contain original PDF content — the template will be drawn behind it.")
            }
            MaterialAlertDialogBuilder(this@DrawingActivity)
                .setTitle("Apply template")
                .setMessage(message)
                .setPositiveButton("Apply") { _, _ -> applyTemplate(tpl, allPages) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun applyTemplate(tpl: PageTemplate, allPages: Boolean) {
        val path = pdfFilePath ?: return
        val busy = showBusyDialog(if (allPages) "Applying to all pages…" else "Applying template…")
        busy.show()
        lifecycleScope.launch {
            try {
                // Close the renderer before pdfbox touches the file so the two don't compete.
                pdfEngine?.close()
                pdfEngine = null
                withContext(Dispatchers.IO) { applyTemplateToPdf(File(path), tpl, allPages, currentPage) }
                if (allPages) saveNotebookTemplate(tpl)
                busy.dismiss()
                invalidateTextIndex()
                loadPdf()
                Toast.makeText(this@DrawingActivity, "Template applied", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                busy.dismiss()
                Toast.makeText(
                    this@DrawingActivity,
                    "Failed to apply template — the file may be closed by another operation.\nPlease try again.",
                    Toast.LENGTH_LONG
                ).show()
                // Reopen the engine so the user can keep working.
                loadPdf()
            }
        }
    }

    private fun applyTemplateToPdf(file: File, tpl: PageTemplate, allPages: Boolean, currentIndex: Int) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        loadPdfLowMemory(file).use { doc ->
            val n = doc.numberOfPages
            val targets = if (allPages) (0 until n) else (currentIndex..currentIndex)
            for (i in targets) {
                if (i !in 0 until n) continue
                val page = doc.getPage(i)
                val box = page.mediaBox
                // PREPEND only for imported PDFs with original content (the template goes behind it).
                // For app-created notebooks the old template is a full-page opaque image, so
                // APPEND replaces it instead of hiding the new one underneath.
                val prepend = pageHasOriginalContent(page)
                drawTemplateBackground(doc, page, box.width, box.height, tpl, prepend = prepend)
            }
            doc.save(tmp)
        }
        swapPdfIntoPlace(tmp, file)
    }

    /** Heuristic: a page has original (non-template) content if it references fonts.
     *  Image XObjects are deliberately NOT checked — drawTemplateBackground writes
     *  template images into the page, and treating those as "original content" would
     *  cause a second template change to PREPEND behind the first one (invisible). */
    private fun pageHasOriginalContent(page: PDPage): Boolean {
        val res = page.resources ?: return false
        return res.fontNames?.iterator()?.hasNext() == true
    }

    private fun showBusyDialog(message: String): AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_indexing, null)
        view.findViewById<TextView>(R.id.indexingText).text = message
        view.findViewById<android.widget.ProgressBar>(R.id.indexingBar).isIndeterminate = true
        return MaterialAlertDialogBuilder(this).setView(view).setCancelable(false).create()
    }

    private fun showJumpToPageDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("${currentPage + 1}"); selectAll()
        }
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(50, 20, 50, 20); input.layoutParams = params; container.addView(input)
        MaterialAlertDialogBuilder(this).setTitle("Jump to Page").setView(container).setPositiveButton("Go") { _, _ ->
            val target = input.text.toString().toIntOrNull()
            if (target != null && target in 1..totalPages) jumpToPage(target - 1)
        }.show()
    }

    private fun setActiveTool(selectedButton: ImageButton) {
        activeToolButton?.isSelected = false
        selectedButton.isSelected = true
        activeToolButton = selectedButton
        toolOptionsHidden = false
        showActiveToolOptions()
        val key = when (selectedButton.id) {
            R.id.penButton -> "PEN"
            R.id.eraserButton -> "ERASER"
            R.id.highlighterButton -> "HIGHLIGHTER"
            R.id.selectionButton -> "LASSO"
            R.id.imageButton -> "IMAGE"
            else -> return
        }
        penPrefs().edit().putString("LAST_ACTIVE_TOOL", key).apply()
    }

    private var smallDock = false
    private var toolOptionsHidden = false

    /** Shows the option strip matching the active tool + the scroll that hosts it. */
    private fun showActiveToolOptions() {
        val hasOptions = !dockCollapsed && !toolOptionsHidden
        val id = if (hasOptions) (activeToolButton?.id ?: -1) else -1
        findViewById<View>(R.id.penPropsStrip).visibility =
            if (id == R.id.penButton) View.VISIBLE else View.GONE
        findViewById<View>(R.id.eraserPropsStrip).visibility =
            if (id == R.id.eraserButton) View.VISIBLE else View.GONE
        findViewById<View>(R.id.highlighterPropsStrip).visibility =
            if (id == R.id.highlighterButton) View.VISIBLE else View.GONE
        findViewById<View>(R.id.lassoPropsStrip).visibility =
            if (id == R.id.selectionButton) View.VISIBLE else View.GONE
        findViewById<View>(R.id.imagePropsStrip).visibility =
            if (id == R.id.imageButton) View.VISIBLE else View.GONE
        if (id == R.id.imageButton) refreshImageToolStrip()

        // The options scroll container is only present when a strip should show.
        val optionsVisible = id != -1
        val scroll = if (smallDock) R.id.optionsScrollV else R.id.optionsScrollH
        findViewById<View>(scroll).visibility = if (optionsVisible) View.VISIBLE else View.GONE
        if (smallDock && optionsVisible) capDockScrolls()
    }

    /**
     * On small (phone) screens the dock lives on the left edge (vertically centred): a vertical
     * tools column on the **left**, the tool-options column to its **right**. Both columns
     * scroll vertically. Larger screens keep the XML top-centre layout (tools row on top,
     * options row below, both scrolling horizontally).
     */
    private fun configureDockPlacement() {
        if (resources.configuration.screenWidthDp >= 600) return
        smallDock = true

        val toolDock = findViewById<android.widget.LinearLayout>(R.id.toolDock)
        val toolsGroup = findViewById<android.widget.LinearLayout>(R.id.toolsGroup)
        val toolDockItems = findViewById<android.widget.LinearLayout>(R.id.toolDockItems)
        val optionsRow = findViewById<android.widget.LinearLayout>(R.id.optionsRow)
        val toolsScrollH = findViewById<android.widget.HorizontalScrollView>(R.id.toolsScrollH)
        val toolsScrollV = findViewById<android.widget.ScrollView>(R.id.toolsScrollV)
        val optionsScrollH = findViewById<android.widget.HorizontalScrollView>(R.id.optionsScrollH)
        val optionsScrollV = findViewById<android.widget.ScrollView>(R.id.optionsScrollV)

        // Dock becomes a horizontal pair of columns; tools left, options right.
        toolDock.orientation = android.widget.LinearLayout.HORIZONTAL

        // Tools: stack vertically inside the vertical scroll.
        toolsScrollH.removeView(toolDockItems)
        toolsScrollV.addView(toolDockItems)
        toolDockItems.orientation = android.widget.LinearLayout.VERTICAL
        toolsScrollH.visibility = View.GONE
        toolsScrollV.visibility = View.VISIBLE

        // Options: stack vertically inside the vertical scroll.
        optionsScrollH.removeView(optionsRow)
        optionsScrollV.addView(optionsRow)
        optionsRow.orientation = android.widget.LinearLayout.VERTICAL
        optionsRow.gravity = android.view.Gravity.CENTER_HORIZONTAL
        optionsScrollH.visibility = View.GONE
        listOf(R.id.penPropsStrip, R.id.eraserPropsStrip, R.id.highlighterPropsStrip, R.id.lassoPropsStrip, R.id.imagePropsStrip)
            .forEach { makeStripVertical(findViewById(it)) }

        // Order children: toolsGroup (left), optionsScrollV (right).
        toolDock.removeView(optionsScrollV)
        toolDock.addView(optionsScrollV) // now last → rightmost

        // Collapse toggle.
        val collapseBtn = findViewById<ImageButton>(R.id.dockCollapseButton)
        collapseBtn.visibility = View.VISIBLE
        collapseBtn.setOnClickListener { setDockCollapsed(!dockCollapsed) }

        // Anchor the dock to the left edge, vertically centred.
        val lp = toolDock.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val parent = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        lp.startToStart = parent
        lp.startToEnd = unset
        lp.endToStart = unset
        lp.endToEnd = unset
        lp.topToTop = parent
        lp.bottomToBottom = parent
        toolDock.layoutParams = lp

        // Search bar returns to the top-right (the dock no longer occupies the top).
        val searchBar = findViewById<View>(R.id.searchBar)
        val slp = searchBar.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        slp.topToBottom = R.id.topBarEnd
        searchBar.layoutParams = slp

        // Re-apply option visibility now that smallDock is on (picks optionsScrollV).
        showActiveToolOptions()

        // Phones start with the dock collapsed to keep the canvas clear.
        setDockCollapsed(true)
    }

    /** Re-orients an option strip (LinearLayout) to vertical, fixing its dividers + spacing. */
    private fun makeStripVertical(strip: android.widget.LinearLayout) {
        strip.orientation = android.widget.LinearLayout.VERTICAL
        strip.gravity = android.view.Gravity.CENTER_HORIZONTAL
        val density = resources.displayMetrics.density
        val thin = (2 * density).toInt() + 1
        val gap = (3 * density).toInt()
        for (i in 0 until strip.childCount) {
            val child = strip.getChildAt(i)
            val lp = child.layoutParams as android.widget.LinearLayout.LayoutParams
            if (child.id == View.NO_ID && lp.width in 1..thin) {
                // Vertical (1dp-wide) divider becomes a horizontal one.
                lp.width = (28 * density).toInt()
                lp.height = (1 * density).toInt().coerceAtLeast(1)
                lp.setMargins(0, (5 * density).toInt(), 0, (5 * density).toInt())
            } else {
                lp.setMargins(0, gap, 0, gap)
            }
            child.layoutParams = lp
        }
    }

    /** Caps the small-dock scroll columns to the viewport so tall content scrolls instead of clipping. */
    private fun capDockScrolls() {
        if (!smallDock) return
        val root = findViewById<View>(R.id.drawingRootLayout)
        root.post {
            val cap = (root.height * 0.82f).toInt()
            if (cap <= 0) return@post
            listOf(R.id.optionsScrollV, R.id.toolsScrollV).forEach { id ->
                val sv = findViewById<android.view.ViewGroup>(id)
                val child = sv.getChildAt(0) ?: return@forEach
                child.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val lp = sv.layoutParams
                lp.height = if (child.measuredHeight > cap) cap
                else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                sv.layoutParams = lp
            }
        }
    }

    private var dockCollapsed = false

    private fun setDockCollapsed(collapsed: Boolean) {
        dockCollapsed = collapsed
        findViewById<View>(R.id.toolsScrollV).visibility = if (collapsed) View.GONE else View.VISIBLE
        showActiveToolOptions() // hides options when collapsed, restores the active one otherwise

        // Collapse also hides undo / redo / add-page.
        val v = if (collapsed) View.GONE else View.VISIBLE
        findViewById<View>(R.id.undoButton).visibility = v
        findViewById<View>(R.id.redoButton).visibility = v
        findViewById<View>(R.id.addPageButton).visibility = v

        findViewById<ImageButton>(R.id.dockCollapseButton).setImageResource(
            if (collapsed) R.drawable.arrow_forward_ios_24px else R.drawable.arrow_back_ios_24px
        )
    }

    /** Re-tapping the active tool hides/shows its options. */
    private fun toggleToolOptions() {
        toolOptionsHidden = !toolOptionsHidden
        showActiveToolOptions()
    }

    // -------------------------------------------------------------------------
    //  Horizontal pen dock: line style + 5 colours + 3 thickness presets
    // -------------------------------------------------------------------------

    private fun penPrefs() = getSharedPreferences("OctopusNotesPrefs", MODE_PRIVATE)

    private fun setupPenDock() {
        val prefs = penPrefs()
        for (i in 0 until 5) dockColors[i] = prefs.getInt("DOCK_COLOR_$i", dockDefaultColors[i])
        dockActiveColorIndex = prefs.getInt("DOCK_COLOR_ACTIVE", 0).coerceIn(0, 4)

        val swatches = listOf(
            findViewById<View>(R.id.colorSwatch0),
            findViewById<View>(R.id.colorSwatch1),
            findViewById<View>(R.id.colorSwatch2),
            findViewById<View>(R.id.colorSwatch3),
            findViewById<View>(R.id.colorSwatch4)
        )
        fun refreshSwatches() {
            swatches.forEachIndexed { i, v -> updateDockSwatch(v, dockColors[i], i == dockActiveColorIndex) }
        }
        swatches.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (dockActiveColorIndex == i) {
                    // Re-tap the active swatch → custom colour picker.
                    openDockColorPicker(dockColors[i]) { picked ->
                        dockColors[i] = picked
                        prefs.edit().putInt("DOCK_COLOR_$i", picked).apply()
                        applyDockColor(picked)
                        refreshSwatches()
                    }
                } else {
                    dockActiveColorIndex = i
                    prefs.edit().putInt("DOCK_COLOR_ACTIVE", i).apply()
                    applyDockColor(dockColors[i])
                    refreshSwatches()
                }
            }
        }
        refreshSwatches()

        // Thickness slots — tap to select, re-tap the active one to customize its size.
        for (i in 0 until 3) dockThickness[i] = prefs.getFloat("DOCK_THICK_$i", dockThicknessDefaults[i])
        dockActiveThicknessIndex = prefs.getInt("DOCK_THICK_ACTIVE", -1)
        if (dockActiveThicknessIndex !in 0..2) {
            // Adopt the previously saved pen size into its nearest slot.
            val s = toolSettingsManager.lastPenSize
            val idx = (0..2).minByOrNull { kotlin.math.abs(dockThickness[it] - s) } ?: 2
            if (kotlin.math.abs(dockThickness[idx] - s) > 0.5f) {
                dockThickness[idx] = s
                prefs.edit().putFloat("DOCK_THICK_$idx", s).apply()
            }
            dockActiveThicknessIndex = idx
            prefs.edit().putInt("DOCK_THICK_ACTIVE", idx).apply()
        }
        toolSettingsManager.lastPenSize = dockThickness[dockActiveThicknessIndex]

        val thicknessBtns = listOf(
            findViewById<ImageButton>(R.id.thinButton),
            findViewById<ImageButton>(R.id.mediumButton),
            findViewById<ImageButton>(R.id.thickButton)
        )
        thicknessBtns.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (dockActiveThicknessIndex == i) {
                    showSizeSliderPopup(b, dockThickness[i], 1f, 40f) { v ->
                        dockThickness[i] = v
                        prefs.edit().putFloat("DOCK_THICK_$i", v).apply()
                        applyDockThickness(v)
                    }
                } else {
                    dockActiveThicknessIndex = i
                    prefs.edit().putInt("DOCK_THICK_ACTIVE", i).apply()
                    applyDockThickness(dockThickness[i])
                    refreshThicknessHighlight()
                }
            }
        }
        refreshThicknessHighlight()

        val lineBtn = findViewById<ImageButton>(R.id.lineStyleButton)
        drawingView.setPenLineStyle(prefs.getString("DOCK_LINE_STYLE", PenLineStyle.SOLID) ?: PenLineStyle.SOLID)
        updateLineStyleIcon(lineBtn)
        lineBtn.setOnClickListener { showLineStylePopup(lineBtn) }

        // Restore the last-used tool (default to pen).
        toolSettingsManager.lastPenColorInt = dockColors[dockActiveColorIndex]
        findViewById<ImageButton>(R.id.penButton).setColorFilter(dockColors[dockActiveColorIndex])
        val lastTool = prefs.getString("LAST_ACTIVE_TOOL", "PEN") ?: "PEN"
        when (lastTool) {
            "ERASER" -> {
                setActiveTool(findViewById(R.id.eraserButton))
                toolSettingsManager.applyEraserSettings()
            }
            "HIGHLIGHTER" -> {
                setActiveTool(findViewById(R.id.highlighterButton))
                toolSettingsManager.applyHighlighterSettings()
            }
            "LASSO" -> {
                setActiveTool(findViewById(R.id.selectionButton))
                drawingView.setTool(DrawingView.Tool.LASSO)
                drawingView.setDrawingMode(true)
            }
            "IMAGE" -> {
                setActiveTool(findViewById(R.id.imageButton))
                drawingView.setDrawingMode(false)
            }
            else -> {
                setActiveTool(findViewById(R.id.penButton))
                toolSettingsManager.applyPenSettings()
            }
        }
    }

    private fun applyDockColor(color: Int) {
        drawingView.setBrushColor(color)
        toolSettingsManager.lastPenColorInt = color
        findViewById<ImageButton>(R.id.penButton).setColorFilter(color)
    }

    private fun applyDockThickness(size: Float) {
        drawingView.setBrushSize(size)
        toolSettingsManager.lastPenSize = size
        penPrefs().edit().putFloat("PEN_SIZE", size).apply()
    }

    private fun refreshThicknessHighlight() {
        findViewById<ImageButton>(R.id.thinButton).isSelected = dockActiveThicknessIndex == 0
        findViewById<ImageButton>(R.id.mediumButton).isSelected = dockActiveThicknessIndex == 1
        findViewById<ImageButton>(R.id.thickButton).isSelected = dockActiveThicknessIndex == 2
    }

    private fun updateDockSwatch(view: View, color: Int, selected: Boolean) {
        val bg = view.background as android.graphics.drawable.LayerDrawable
        (bg.findDrawableByLayerId(R.id.color_shape) as android.graphics.drawable.GradientDrawable).setColor(color)
        val stroke = bg.getDrawable(1) as android.graphics.drawable.GradientDrawable
        if (selected) stroke.setStroke(8, Color.parseColor("#2196F3"))
        else stroke.setStroke(2, Color.parseColor("#BDBDBD"))
    }

    private fun openDockColorPicker(initial: Int, onPicked: (Int) -> Unit) {
        ColorPickerDialog.show(this, initial, allowEyedropper = true) { onPicked(it) }
    }

    private fun updateLineStyleIcon(btn: ImageButton) {
        btn.setImageResource(
            when (drawingView.penLineStyle) {
                PenLineStyle.DOTTED -> R.drawable.ic_line_dotted
                PenLineStyle.DASHED -> R.drawable.ic_line_dashed
                else -> R.drawable.ic_line_solid
            }
        )
    }

    private fun showLineStylePopup(anchor: ImageButton) {
        val view = layoutInflater.inflate(R.layout.popup_line_style, null)
        val widthPx = (280 * resources.displayMetrics.density).toInt()
        val popup = android.widget.PopupWindow(
            view, widthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)
        fun choose(style: String) {
            drawingView.setPenLineStyle(style)
            penPrefs().edit().putString("DOCK_LINE_STYLE", style).apply()
            updateLineStyleIcon(anchor)
            popup.dismiss()
        }
        view.findViewById<View>(R.id.styleSolid).setOnClickListener { choose(PenLineStyle.SOLID) }
        view.findViewById<View>(R.id.styleDotted).setOnClickListener { choose(PenLineStyle.DOTTED) }
        view.findViewById<View>(R.id.styleDashed).setOnClickListener { choose(PenLineStyle.DASHED) }

        // Stroke stabilization lives here too (1–10).
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.stabilizationSlider)
        val valueText = view.findViewById<TextView>(R.id.stabilizationValue)
        val level = toolSettingsManager.lastStabilizationLevel.coerceIn(1, 10)
        slider.value = level.toFloat()
        valueText.text = "Level $level"
        slider.addOnChangeListener { _, v, _ ->
            val lvl = v.toInt()
            toolSettingsManager.lastStabilizationLevel = lvl
            drawingView.setStabilizationLevel(lvl)
            penPrefs().edit().putInt("STABILIZATION_LEVEL", lvl).apply()
            valueText.text = "Level $lvl"
        }

        // Dock is at the top, so the popup drops down below the button.
        popup.showAsDropDown(anchor, 0, 8)
    }

    /** Reusable size-slider popup (pen thickness, eraser size, highlighter size). */
    private fun showSizeSliderPopup(
        anchor: View,
        initial: Float,
        from: Float,
        to: Float,
        onValue: (Float) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.popup_thickness, null)
        val widthPx = (300 * resources.displayMetrics.density).toInt()
        val popup = android.widget.PopupWindow(
            view, widthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)

        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.thicknessSlider)
        val valueText = view.findViewById<TextView>(R.id.thicknessValue)
        slider.valueFrom = from
        slider.valueTo = to
        val current = initial.coerceIn(from, to)
        slider.value = current
        valueText.text = current.toInt().toString()

        slider.addOnChangeListener { _, v, _ ->
            onValue(v)
            valueText.text = v.toInt().toString()
        }
        view.findViewById<ImageButton>(R.id.thicknessMinus).setOnClickListener {
            slider.value = (slider.value - 1f).coerceAtLeast(slider.valueFrom)
        }
        view.findViewById<ImageButton>(R.id.thicknessPlus).setOnClickListener {
            slider.value = (slider.value + 1f).coerceAtMost(slider.valueTo)
        }
        popup.showAsDropDown(anchor, 0, 8)
    }

    // -------------------------------------------------------------------------
    //  Eraser dock row: pixel/stroke + 3 size presets
    // -------------------------------------------------------------------------

    private val eraserSizeDefaults = floatArrayOf(20f, 50f, 90f)
    private val eraserSizes = FloatArray(3)
    private var eraserActiveSizeIndex = 1

    private fun setupEraserDock() {
        val prefs = penPrefs()
        for (i in 0 until 3) eraserSizes[i] = prefs.getFloat("DOCK_ERASER_SIZE_$i", eraserSizeDefaults[i])
        eraserActiveSizeIndex = prefs.getInt("DOCK_ERASER_SIZE_ACTIVE", -1)
        if (eraserActiveSizeIndex !in 0..2) {
            val s = toolSettingsManager.lastEraserSize
            val idx = (0..2).minByOrNull { kotlin.math.abs(eraserSizes[it] - s) } ?: 1
            if (kotlin.math.abs(eraserSizes[idx] - s) > 0.5f) {
                eraserSizes[idx] = s
                prefs.edit().putFloat("DOCK_ERASER_SIZE_$idx", s).apply()
            }
            eraserActiveSizeIndex = idx
            prefs.edit().putInt("DOCK_ERASER_SIZE_ACTIVE", idx).apply()
        }
        toolSettingsManager.lastEraserSize = eraserSizes[eraserActiveSizeIndex]

        val pixelBtn = findViewById<TextView>(R.id.eraserPixelButton)
        val strokeBtn = findViewById<TextView>(R.id.eraserStrokeButton)
        fun refreshType() {
            val pixel = toolSettingsManager.currentEraserType == DrawingView.Tool.PIXEL_ERASER
            pixelBtn.isSelected = pixel
            strokeBtn.isSelected = !pixel
        }
        fun selectType(pixel: Boolean) {
            val type = if (pixel) DrawingView.Tool.PIXEL_ERASER else DrawingView.Tool.STROKE_ERASER
            toolSettingsManager.currentEraserType = type
            drawingView.setTool(type)
            penPrefs().edit().putString("ERASER_TYPE", type.name).apply()
            refreshType()
        }
        pixelBtn.setOnClickListener { selectType(true) }
        strokeBtn.setOnClickListener { selectType(false) }
        refreshType()

        val sizeBtns = listOf(
            findViewById<ImageButton>(R.id.eraserSize0),
            findViewById<ImageButton>(R.id.eraserSize1),
            findViewById<ImageButton>(R.id.eraserSize2)
        )
        sizeBtns.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (eraserActiveSizeIndex == i) {
                    showSizeSliderPopup(b, eraserSizes[i], 5f, 120f) { v ->
                        eraserSizes[i] = v
                        prefs.edit().putFloat("DOCK_ERASER_SIZE_$i", v).apply()
                        applyEraserSize(v)
                    }
                } else {
                    eraserActiveSizeIndex = i
                    prefs.edit().putInt("DOCK_ERASER_SIZE_ACTIVE", i).apply()
                    applyEraserSize(eraserSizes[i])
                    refreshEraserSizeHighlight()
                }
            }
        }
        refreshEraserSizeHighlight()
    }

    private fun applyEraserSize(size: Float) {
        toolSettingsManager.lastEraserSize = size
        drawingView.setBrushSize(size)
        penPrefs().edit().putFloat("ERASER_SIZE", size).apply()
    }

    private fun refreshEraserSizeHighlight() {
        findViewById<ImageButton>(R.id.eraserSize0).isSelected = eraserActiveSizeIndex == 0
        findViewById<ImageButton>(R.id.eraserSize1).isSelected = eraserActiveSizeIndex == 1
        findViewById<ImageButton>(R.id.eraserSize2).isSelected = eraserActiveSizeIndex == 2
    }

    // -------------------------------------------------------------------------
    //  Highlighter dock row: shape/mode + 5 colours + 3 sizes
    // -------------------------------------------------------------------------

    private val hlSizeDefaults = floatArrayOf(20f, 30f, 45f)
    private val hlSizes = FloatArray(3)
    private var hlActiveSizeIndex = 1
    private val hlDefaultColors = intArrayOf(
        Color.parseColor("#FFEB00"), // yellow
        Color.parseColor("#00E676"), // green
        Color.parseColor("#FF4081"), // pink
        Color.parseColor("#40C4FF"), // blue
        Color.parseColor("#FF9100")  // orange
    )
    private val hlColors = IntArray(5)
    private var hlActiveColorIndex = 0

    private fun setupHighlighterDock() {
        val prefs = penPrefs()
        for (i in 0 until 5) hlColors[i] = prefs.getInt("DOCK_HL_COLOR_$i", hlDefaultColors[i])
        hlActiveColorIndex = prefs.getInt("DOCK_HL_COLOR_ACTIVE", 0).coerceIn(0, 4)

        val swatches = listOf(
            findViewById<View>(R.id.hlColor0),
            findViewById<View>(R.id.hlColor1),
            findViewById<View>(R.id.hlColor2),
            findViewById<View>(R.id.hlColor3),
            findViewById<View>(R.id.hlColor4)
        )
        fun refreshSwatches() {
            swatches.forEachIndexed { i, v -> updateDockSwatch(v, hlColors[i], i == hlActiveColorIndex) }
        }
        swatches.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (hlActiveColorIndex == i) {
                    openDockColorPicker(hlColors[i]) { picked ->
                        hlColors[i] = picked
                        prefs.edit().putInt("DOCK_HL_COLOR_$i", picked).apply()
                        toolSettingsManager.setHighlighterColorPref(picked)
                        refreshSwatches()
                    }
                } else {
                    hlActiveColorIndex = i
                    prefs.edit().putInt("DOCK_HL_COLOR_ACTIVE", i).apply()
                    toolSettingsManager.setHighlighterColorPref(hlColors[i])
                    refreshSwatches()
                }
            }
        }
        refreshSwatches()
        // Sync the highlighter's active colour to the dock selection.
        toolSettingsManager.setHighlighterColorPref(hlColors[hlActiveColorIndex])

        for (i in 0 until 3) hlSizes[i] = prefs.getFloat("DOCK_HL_SIZE_$i", hlSizeDefaults[i])
        hlActiveSizeIndex = prefs.getInt("DOCK_HL_SIZE_ACTIVE", -1)
        if (hlActiveSizeIndex !in 0..2) {
            val s = toolSettingsManager.getHighlighterSize()
            val idx = (0..2).minByOrNull { kotlin.math.abs(hlSizes[it] - s) } ?: 1
            if (kotlin.math.abs(hlSizes[idx] - s) > 0.5f) {
                hlSizes[idx] = s
                prefs.edit().putFloat("DOCK_HL_SIZE_$idx", s).apply()
            }
            hlActiveSizeIndex = idx
            prefs.edit().putInt("DOCK_HL_SIZE_ACTIVE", idx).apply()
        }
        toolSettingsManager.setHighlighterSizePref(hlSizes[hlActiveSizeIndex])

        val sizeBtns = listOf(
            findViewById<ImageButton>(R.id.hlSize0),
            findViewById<ImageButton>(R.id.hlSize1),
            findViewById<ImageButton>(R.id.hlSize2)
        )
        sizeBtns.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (hlActiveSizeIndex == i) {
                    showSizeSliderPopup(b, hlSizes[i], 8f, 60f) { v ->
                        hlSizes[i] = v
                        prefs.edit().putFloat("DOCK_HL_SIZE_$i", v).apply()
                        toolSettingsManager.setHighlighterSizePref(v)
                    }
                } else {
                    hlActiveSizeIndex = i
                    prefs.edit().putInt("DOCK_HL_SIZE_ACTIVE", i).apply()
                    toolSettingsManager.setHighlighterSizePref(hlSizes[i])
                    refreshHlSizeHighlight()
                }
            }
        }
        refreshHlSizeHighlight()

        val shapeBtn = findViewById<ImageButton>(R.id.hlShapeButton)
        updateHlShapeIcon(shapeBtn)
        shapeBtn.setOnClickListener { showHighlighterShapePopup(shapeBtn) }
    }

    private fun refreshHlSizeHighlight() {
        findViewById<ImageButton>(R.id.hlSize0).isSelected = hlActiveSizeIndex == 0
        findViewById<ImageButton>(R.id.hlSize1).isSelected = hlActiveSizeIndex == 1
        findViewById<ImageButton>(R.id.hlSize2).isSelected = hlActiveSizeIndex == 2
    }

    private fun updateHlShapeIcon(btn: ImageButton) {
        btn.setImageResource(
            if (toolSettingsManager.isHighlighterStraight()) R.drawable.ic_line_straight
            else R.drawable.ic_line_wavy
        )
    }

    private fun showHighlighterShapePopup(anchor: ImageButton) {
        val view = layoutInflater.inflate(R.layout.popup_highlighter_line, null)
        val popup = android.widget.PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)

        val free = view.findViewById<View>(R.id.hlShapeFree)
        val straight = view.findViewById<View>(R.id.hlShapeStraight)
        val normal = view.findViewById<TextView>(R.id.hlModeNormal)
        val text = view.findViewById<TextView>(R.id.hlModeText)

        fun refreshShape() {
            val s = toolSettingsManager.isHighlighterStraight()
            straight.isSelected = s; free.isSelected = !s
        }
        fun refreshMode() {
            val t = toolSettingsManager.highlighterTextMode
            text.isSelected = t; normal.isSelected = !t
        }
        refreshShape(); refreshMode()

        free.setOnClickListener {
            toolSettingsManager.setHighlighterStraightPref(false)
            refreshShape(); updateHlShapeIcon(anchor)
        }
        straight.setOnClickListener {
            toolSettingsManager.setHighlighterStraightPref(true)
            refreshShape(); updateHlShapeIcon(anchor)
        }
        normal.setOnClickListener { toolSettingsManager.setHighlighterTextModePref(false); refreshMode() }
        text.setOnClickListener { toolSettingsManager.setHighlighterTextModePref(true); refreshMode() }

        popup.showAsDropDown(anchor, 0, 8)
    }

    // -------------------------------------------------------------------------
    //  Lasso dock row: free / rectangular / circular selection
    // -------------------------------------------------------------------------

    private fun setupLassoDock() {
        val saved = penPrefs().getString("DOCK_LASSO_SHAPE", LassoShape.FREE) ?: LassoShape.FREE
        drawingView.setLassoShape(saved)

        val free = findViewById<ImageButton>(R.id.lassoFreeButton)
        val rect = findViewById<ImageButton>(R.id.lassoRectButton)
        val circle = findViewById<ImageButton>(R.id.lassoCircleButton)
        fun refresh() {
            val s = drawingView.lassoShape
            free.isSelected = s == LassoShape.FREE
            rect.isSelected = s == LassoShape.RECT
            circle.isSelected = s == LassoShape.CIRCLE
        }
        fun select(shape: String) {
            drawingView.setLassoShape(shape)
            penPrefs().edit().putString("DOCK_LASSO_SHAPE", shape).apply()
            refresh()
        }
        free.setOnClickListener { select(LassoShape.FREE) }
        rect.setOnClickListener { select(LassoShape.RECT) }
        circle.setOnClickListener { select(LassoShape.CIRCLE) }
        refresh()
    }

    // --- OVERFLOW MENU ---

    private fun showOverflowMenu(anchor: View) {
        val view = layoutInflater.inflate(R.layout.popup_overflow, null)

        val widthPx = (260 * resources.displayMetrics.density).toInt()

        val popup = android.widget.PopupWindow(
            view,
            widthPx,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)
        view.findViewById<View>(R.id.overflowStylus).setOnClickListener {
            popup.dismiss()
            toolSettingsManager.showGestureOptionsPopup(anchor)
        }
        view.findViewById<View>(R.id.overflowIndex).setOnClickListener {
            popup.dismiss()
            Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.overflowTemplate).setOnClickListener {
            popup.dismiss()
            showChangeTemplateDialog()
        }
        view.findViewById<View>(R.id.overflowExport).setOnClickListener {
            popup.dismiss()
            showExportDialog()
        }
        popup.showAsDropDown(anchor, 0, 8)
    }

    private fun invalidateTextIndex() {
        pdfTextIndex = null
        searchMatches = emptyList(); searchPos = -1; activeHighlight = null
        highlightsByPage.clear()
        outlineCacheValue = null
        outlineCacheFile().delete()
    }

    // --- Export (PDF / images / image zip) ---

    private fun showExportDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_export, null)
        val fromInput = view.findViewById<android.widget.EditText>(R.id.exportFrom)
        val toInput = view.findViewById<android.widget.EditText>(R.id.exportTo)
        fromInput.setText("1")
        toInput.setText("$totalPages")
        view.findViewById<TextView>(R.id.exportRangeHint).text = "Pages (1 – $totalPages)"
        val formatGroup = view.findViewById<android.widget.RadioGroup>(R.id.exportFormatGroup)

        MaterialAlertDialogBuilder(this)
            .setTitle("Export")
            .setView(view)
            .setPositiveButton("Export") { _, _ ->
                val from = (fromInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, totalPages)
                val to = (toInput.text.toString().toIntOrNull() ?: totalPages).coerceIn(from, totalPages)
                val pages = (from - 1..to - 1).toList()
                val format = if (formatGroup.checkedRadioButtonId == R.id.fmtImages) "img" else "pdf"
                runExport(pages, format)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runExport(pages: List<Int>, format: String) {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Invalid page range", Toast.LENGTH_SHORT).show()
            return
        }
        val exporter = PdfExporter(this, pdfFilePath?.let { File(it) }, strokeManager, strokePageSizes())
        val base = exportBaseName()

        var job: kotlinx.coroutines.Job? = null
        val progress = ProgressDialogController(this, "Exporting…") { job?.cancel() }
        progress.show()
        val onProgress: (Int, Int) -> Unit = { done, total ->
            runOnUiThread { if (!progress.isCancelled) progress.setProgress(done, total) }
        }
        val cancel = { progress.isCancelled }

        job = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when {
                        // Single page → one PNG; multiple pages → a ZIP of PNGs.
                        format == "img" && pages.size == 1 ->
                            Pair(exporter.exportImages(pages, base, onProgress, cancel), "image/png")
                        format == "img" ->
                            Pair(listOf(exporter.exportZip(pages, base, onProgress, cancel)), "application/zip")
                        else ->
                            Pair(listOf(exporter.exportPdf(pages, base, onProgress, cancel)), "application/pdf")
                    }
                }
                progress.dismiss()
                shareExport(result.first, result.second)
            } catch (c: kotlinx.coroutines.CancellationException) {
                progress.dismiss() // user cancelled — no error
            } catch (t: Throwable) {
                t.printStackTrace()
                progress.dismiss()
                Toast.makeText(this@DrawingActivity, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportBaseName(): String {
        val raw = intent.getStringExtra("NOTEBOOK_TITLE") ?: "OctopusNotes"
        return raw.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "OctopusNotes" }
    }

    private fun shareExport(files: List<File>, mime: String) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map {
            androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
        })
        val intent = if (uris.size == 1) {
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(android.content.Intent.createChooser(intent, "Export / Share"))
    }

    // --- LASSO / SELECTION ---

    private fun handleLassoSelection(lassoScreenPath: Path) {
        val rv = pdfRecyclerView
        val zoom = rv.zoom
        val tx = rv.transX
        val ty = rv.transY
        val bounds = RectF()
        lassoScreenPath.computeBounds(bounds, true)
        val contentCenterY = (bounds.centerY() - ty) / zoom

        // Find the page child under the lasso centre (content space).
        var pageIndex = -1
        var childLeft = 0f
        var childTop = 0f
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(child)
            if (pos < 0) continue
            if (contentCenterY >= child.top && contentCenterY < child.bottom) {
                pageIndex = pos
                childLeft = child.left.toFloat()
                childTop = child.top.toFloat()
                break
            }
        }
        if (pageIndex == -1) return

        // screen → page-space: page = (screen - trans) / zoom - childOrigin
        val toPage = Matrix().apply {
            postTranslate(-tx, -ty)
            postScale(1 / zoom, 1 / zoom)
            postTranslate(-childLeft, -childTop)
        }
        val lassoPagePath = Path()
        lassoScreenPath.transform(toPage, lassoPagePath)

        val result = strokeManager.selectStrokesInPath(pageIndex, lassoPagePath) ?: return
        invalidateInk()
        presentSelection(childLeft, childTop, result.second)
    }

    /**
     * Puts the StrokeManager's active selection on screen: builds screen-space visuals
     * (paths + image bitmaps), starts the DrawingView transform overlay and shows the
     * floating selection popup. Shared by lasso select and image insertion.
     */
    private fun presentSelection(childLeft: Float, childTop: Float, pdfUnionBounds: RectF) {
        val rv = pdfRecyclerView
        val zoom = rv.zoom

        // page → screen: screen = (page + childOrigin) * zoom + trans
        val toScreen = Matrix().apply {
            postTranslate(childLeft, childTop)
            postScale(zoom, zoom)
            postTranslate(rv.transX, rv.transY)
        }
        val selectedStrokes = strokeManager.activeSelectionStrokes
        val screenPaths = selectedStrokes.map { Path(it.path).apply { transform(toScreen) } }
        val paints = selectedStrokes.map { it.paint }
        val rotations = selectedStrokes.map { it.imageRotation }
        val screenBounds = RectF()
        toScreen.mapRect(screenBounds, pdfUnionBounds)

        // Selection visuals appear instantly (paths only — image slots draw a frame outline
        // until their bitmap arrives). The bitmaps are decoded on the IO dispatcher: decoding
        // a large photo takes 100-300ms, and doing it here was janking the frame that opens
        // the selection (adding several images stuttered, and so did lassoing them).
        drawingView.startSelection(screenPaths, paints, screenBounds, childTop, emptyList(), rotations, selectedStrokes)
        showSelectionPopup(screenBounds)

        val selectionRef = strokeManager.activeSelectionStrokes
        // Only images need decoding — skip the dispatch entirely for ink-only selections.
        if (selectionRef.any { it.type == StrokeType.IMAGE }) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { selectionRef.forEach { strokeManager.bitmapFor(it) } }
                // Skip if the selection was cleared or replaced while decoding.
                if (isDestroyed || strokeManager.activeSelectionStrokes !== selectionRef || selectionRef.isEmpty()) return@launch
                // Cache hits now — swaps the outline placeholders for the real pictures.
                drawingView.updateSelectionVisuals(selectionRef)
            }
        }
    }

    /**
     * Works out which page a committed selection has been dragged onto, and how far its
     * coordinates must shift to be expressed in that page's space. Returns the source
     * page and zero offsets when the selection hasn't left its page.
     */
    private fun resolveSelectionTarget(
        pdfDx: Float,
        pdfDy: Float,
        scale: Float
    ): Triple<Int, Float, Float> {
        val sourcePage = strokeManager.activeSelectionPageIndex
        val unchanged = Triple(sourcePage, 0f, 0f)
        val bounds = strokeManager.selectionBoundsAfter(pdfDx, pdfDy, scale) ?: return unchanged
        val sourceOrigin = drawingView.pageOrigin(sourcePage) ?: return unchanged

        // The selection's centre decides which page it belongs to.
        val contentX = sourceOrigin.x + bounds.centerX()
        val contentY = sourceOrigin.y + bounds.centerY()
        val targetPage = drawingView.pageAtContent(contentX, contentY) ?: return unchanged
        if (targetPage == sourcePage) return unchanged
        val targetOrigin = drawingView.pageOrigin(targetPage) ?: return unchanged

        return Triple(targetPage, sourceOrigin.x - targetOrigin.x, sourceOrigin.y - targetOrigin.y)
    }

    private fun handleSelectionCommit(screenDx: Float, screenDy: Float, scale: Float = 1f) {
        val pdfDx = screenDx / pdfRecyclerView.zoom
        val pdfDy = screenDy / pdfRecyclerView.zoom
        val (targetPage, rebaseDx, rebaseDy) = resolveSelectionTarget(pdfDx, pdfDy, scale)
        val result = strokeManager.commitSelection(pdfDx, pdfDy, scale, targetPage, rebaseDx, rebaseDy)
        if (result != null) {
            historyManager.execute(result.action, strokeManager)
            strokesDirty = true
            invalidateInk()
            updateUndoRedoButtons()
        }
        selectionTotalDx = 0f
        selectionTotalDy = 0f
        selectionTotalScale = 1f
        selectionPopup?.dismiss()
    }

    private fun showSelectionPopup(screenBounds: RectF) {
        selectionPopup?.dismiss()
        val view = layoutInflater.inflate(R.layout.popup_selection, null)
        view.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)

        selectionPopup = android.widget.PopupWindow(view, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false)
        selectionPopup?.elevation = 20f
        selectionPopup?.setBackgroundDrawable(null)

        val colorBtn = view.findViewById<android.view.View>(R.id.selColorBtn)
        val currentShape = colorBtn.background as? android.graphics.drawable.GradientDrawable
        currentShape?.setColor(strokeManager.activeSelectionStrokes.firstOrNull()?.paint?.color ?: android.graphics.Color.BLACK)

        colorBtn.setOnClickListener {
            val initialColor = strokeManager.activeSelectionStrokes.firstOrNull()?.paint?.color ?: android.graphics.Color.BLACK
            ColorPickerDialog.show(this, initialColor, allowEyedropper = true) { color ->
                strokeManager.activeSelectionStrokes.forEach { it.paint.color = color }
                strokesDirty = true
                drawingView.updateSelectionVisuals(strokeManager.activeSelectionStrokes)
                currentShape?.setColor(color)
            }
        }

        view.findViewById<android.view.View>(R.id.selAngleBtn).setOnClickListener {
            showAnglePopup(it)
        }

        view.findViewById<android.view.View>(R.id.selDelBtn).setOnClickListener {
            val action = strokeManager.deleteSelection()
            if (action != null) historyManager.execute(action, strokeManager)
            drawingView.clearSelectionVisuals()
            invalidateInk()
            selectionPopup?.dismiss()
        }

        view.findViewById<android.view.View>(R.id.selMoreBtn).setOnClickListener {
            showMoreSelectionPopup(it)
        }

        view.findViewById<android.view.View>(R.id.selDupBtn).setOnClickListener {
            val pdfDx = selectionTotalDx / pdfRecyclerView.zoom
            val pdfDy = selectionTotalDy / pdfRecyclerView.zoom
            val (targetPage, rebaseDx, rebaseDy) = resolveSelectionTarget(pdfDx, pdfDy, selectionTotalScale)
            val commit = strokeManager.commitSelection(
                pdfDx, pdfDy, selectionTotalScale, targetPage, rebaseDx, rebaseDy
            )
            if (commit != null) {
                historyManager.execute(commit.action, strokeManager)
                // Offset duplicates slightly
                val dupStrokes = commit.newStrokes.map { s ->
                    val p = Path(s.path)
                    p.transform(Matrix().apply { postTranslate(30f, 30f) })
                    s.copy(path = p, id = java.util.UUID.randomUUID().toString())
                }
                // Onto the page the selection actually landed on, which isn't necessarily
                // the page being displayed.
                historyManager.execute(
                    DrawingAction.BatchAction(dupStrokes.map { DrawingAction.AddStroke(commit.pageIndex, it) }),
                    strokeManager
                )
            }
            drawingView.clearSelectionVisuals()
            invalidateInk()
            selectionPopup?.dismiss()
            updateUndoRedoButtons()
        }

        // Track the drag locally so the popup can use it for duplication bounds.
        drawingView.onSelectionMovedListener = { dx, dy, scale ->
            selectionTotalDx = dx
            selectionTotalDy = dy
            selectionTotalScale = scale
            handleSelectionCommit(dx, dy, scale)
        }

        val x = screenBounds.centerX().toInt() - (view.measuredWidth / 2)
        val y = screenBounds.top.toInt() - view.measuredHeight - 40
        selectionPopup?.showAtLocation(drawingView, android.view.Gravity.NO_GRAVITY, x, y.coerceAtLeast(100))
    }

    private fun showAnglePopup(anchor: android.view.View) {
        val view = layoutInflater.inflate(R.layout.popup_angle, null)
        val popup = android.widget.PopupWindow(view, (250 * resources.displayMetrics.density).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)

        val slider = view.findViewById<android.widget.SeekBar>(R.id.angleSlider)
        val text = view.findViewById<TextView>(R.id.angleValueText)
        slider.max = 360
        slider.progress = 180

        slider.setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val angle = progress - 180f
                text.text = "${angle.toInt()}°"
                strokeManager.applyAbsoluteRotation(angle)
                strokesDirty = true
                drawingView.updateSelectionVisuals(strokeManager.activeSelectionStrokes)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        popup.showAsDropDown(anchor, 0, -200)
    }

    private fun showMoreSelectionPopup(anchor: android.view.View) {
        val view = layoutInflater.inflate(R.layout.popup_selection_more, null)
        val popup = android.widget.PopupWindow(view, (200 * resources.displayMetrics.density).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)

        view.findViewById<android.view.View>(R.id.btnFlipH).setOnClickListener {
            strokeManager.flipSelection(horizontal = true, vertical = false)
            strokesDirty = true
            drawingView.updateSelectionVisuals(strokeManager.activeSelectionStrokes)
        }
        view.findViewById<android.view.View>(R.id.btnFlipV).setOnClickListener {
            strokeManager.flipSelection(horizontal = false, vertical = true)
            strokesDirty = true
            drawingView.updateSelectionVisuals(strokeManager.activeSelectionStrokes)
        }

        view.findViewById<android.view.View>(R.id.btnCopy).setOnClickListener {
            strokeManager.clipboardStrokes = strokeManager.activeSelectionStrokes.map { it.copy(id = java.util.UUID.randomUUID().toString()) }
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            popup.dismiss()
        }

        view.findViewById<android.view.View>(R.id.btnCut).setOnClickListener {
            strokeManager.clipboardStrokes = strokeManager.activeSelectionStrokes.map { it.copy(id = java.util.UUID.randomUUID().toString()) }
            val action = strokeManager.deleteSelection()
            if (action != null) historyManager.execute(action, strokeManager)
            drawingView.clearSelectionVisuals()
            invalidateInk()
            popup.dismiss()
            selectionPopup?.dismiss()
        }

        val btnPaste = view.findViewById<android.view.View>(R.id.btnPaste)
        btnPaste.alpha = if (strokeManager.clipboardStrokes.isNullOrEmpty()) 0.4f else 1.0f
        btnPaste.setOnClickListener {
            val clip = strokeManager.clipboardStrokes ?: return@setOnClickListener
            val actions = clip.map { DrawingAction.AddStroke(currentPage, it.copy(id = java.util.UUID.randomUUID().toString())) }
            historyManager.execute(DrawingAction.BatchAction(actions), strokeManager)
            drawingView.clearSelectionVisuals()
            invalidateInk()
            popup.dismiss()
            selectionPopup?.dismiss()
        }

        popup.showAsDropDown(anchor, 0, 8)
    }

    private var pastePopup: android.widget.PopupWindow? = null

    /**
     * Finger long press on the page: offers to paste the copied strokes right there.
     * Silent when the clipboard is empty — an empty menu is worse than no menu.
     */
    private fun showPastePopup(viewX: Float, viewY: Float) {
        if (strokeManager.clipboardStrokes.isNullOrEmpty()) return
        pastePopup?.dismiss()

        val view = layoutInflater.inflate(R.layout.popup_paste, null)
        val popup = android.widget.PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(null)
        popup.elevation = 20f
        pastePopup = popup
        popup.setOnDismissListener { pastePopup = null }

        view.findViewById<View>(R.id.btnPasteHere).setOnClickListener {
            pasteClipboardAt(viewX, viewY)
            popup.dismiss()
        }

        // Sit the pill above the finger, and keep it on screen near the edges.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2).also { drawingView.getLocationOnScreen(it) }
        val w = view.measuredWidth
        val h = view.measuredHeight
        val margin = (12 * resources.displayMetrics.density).toInt()
        val x = (loc[0] + viewX.toInt() - w / 2)
            .coerceIn(margin, (resources.displayMetrics.widthPixels - w - margin).coerceAtLeast(margin))
        val y = (loc[1] + viewY.toInt() - h - margin).coerceAtLeast(margin)
        popup.showAtLocation(drawingView, android.view.Gravity.NO_GRAVITY, x, y)
    }

    /** Pastes the clipboard centred on the page point under [viewX]/[viewY]. */
    private fun pasteClipboardAt(viewX: Float, viewY: Float) {
        val clip = strokeManager.clipboardStrokes?.takeIf { it.isNotEmpty() } ?: return
        val hit = drawingView.locatePage(viewX, viewY) ?: return

        val bounds = android.graphics.RectF()
        val each = android.graphics.RectF()
        clip.forEachIndexed { i, s ->
            s.path.computeBounds(each, true)
            if (i == 0) bounds.set(each) else bounds.union(each)
        }

        val move = android.graphics.Matrix().apply {
            setTranslate(hit.pageX - bounds.centerX(), hit.pageY - bounds.centerY())
        }
        val actions = clip.map { s ->
            // copy() clears savedContours, so the moved geometry is what gets saved.
            val moved = android.graphics.Path(s.path).apply { transform(move) }
            DrawingAction.AddStroke(
                hit.pageIndex,
                s.copy(
                    path = moved,
                    paint = android.graphics.Paint(s.paint),
                    id = java.util.UUID.randomUUID().toString()
                )
            )
        }
        historyManager.execute(DrawingAction.BatchAction(actions), strokeManager)
        strokesDirty = true
        invalidateInk()
    }

    // --- IMAGE TOOL ---

    private fun imagesPermission(): String =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun imagePermissionGranted(): Boolean =
        checkSelfPermission(imagesPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Syncs the image tool's option strip with the current permission state. */
    private fun refreshImageToolStrip() {
        val granted = imagePermissionGranted()
        findViewById<View>(R.id.imagePermissionGroup).visibility = if (granted) View.GONE else View.VISIBLE
        findViewById<View>(R.id.imagePickButton).visibility = if (granted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.imageStripDivider).visibility = if (granted) View.VISIBLE else View.GONE
        val recentsRow = findViewById<android.widget.LinearLayout>(R.id.imageRecentsRow)
        recentsRow.visibility = if (granted) View.VISIBLE else View.GONE
        // On the narrow phone dock the strip is a vertical column — stack previews too.
        if (smallDock) {
            recentsRow.orientation = android.widget.LinearLayout.VERTICAL
            findViewById<android.widget.LinearLayout>(R.id.imagePermissionGroup).orientation =
                android.widget.LinearLayout.VERTICAL
        }
        if (granted) loadRecentImagePreviews(recentsRow)
    }

    /** Fills the strip with small previews of the most recently added device images. */
    private fun loadRecentImagePreviews(row: android.widget.LinearLayout) {
        lifecycleScope.launch {
            val thumbs = withContext(Dispatchers.IO) {
                val list = mutableListOf<Pair<android.net.Uri, android.graphics.Bitmap>>()
                try {
                    contentResolver.query(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(android.provider.MediaStore.Images.Media._ID),
                        null, null,
                        "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                    )?.use { c ->
                        while (c.moveToNext() && list.size < 8) {
                            val uri = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                            )
                            val bmp = loadPreviewThumb(uri) ?: continue
                            list.add(uri to bmp)
                        }
                    }
                } catch (_: Exception) {}
                list
            }
            row.removeAllViews()
            val density = resources.displayMetrics.density
            val size = (40 * density).toInt()
            val margin = (3 * density).toInt()
            val corner = 6 * density
            for ((uri, bmp) in thumbs) {
                val iv = android.widget.ImageView(this@DrawingActivity).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                        marginStart = margin; marginEnd = margin
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, v.width, v.height, corner)
                        }
                    }
                    setImageBitmap(bmp)
                    setOnClickListener { insertImageFromUri(uri) }
                }
                row.addView(iv)
            }
        }
    }

    private fun loadPreviewThumb(uri: android.net.Uri): android.graphics.Bitmap? = try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            contentResolver.loadThumbnail(uri, android.util.Size(128, 128), null)
        } else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / sample > 256 || o.outHeight / sample > 256) sample *= 2
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) { null }

    /**
     * Copies the picked image into app storage (picker URIs are transient), then places
     * it on the currently visible page and opens it in selection mode for move/resize.
     */
    private fun insertImageFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                try {
                    val dir = strokeManager.imagesDir ?: return@withContext null
                    val name = "img_${java.util.UUID.randomUUID()}"
                    val f = java.io.File(dir, name)
                    contentResolver.openInputStream(uri)?.use { inp ->
                        f.outputStream().use { inp.copyTo(it) }
                    } ?: return@withContext null
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                    if (o.outWidth <= 0 || o.outHeight <= 0) { f.delete(); null }
                    else {
                        // Decode once here (off the main thread) so the auto-select that follows
                        // and any later selection draw the cached bitmap instead of janking.
                        strokeManager.preloadBitmap(name)
                        Triple(name, o.outWidth, o.outHeight)
                    }
                } catch (e: Exception) { null }
            }
            if (prepared == null) {
                Toast.makeText(this@DrawingActivity, "Couldn't load that image", Toast.LENGTH_SHORT).show()
                return@launch
            }
            placeImageOnPage(prepared.first, prepared.second, prepared.third)
        }
    }

    private fun placeImageOnPage(imageFile: String, imgW: Int, imgH: Int) {
        val rv = pdfRecyclerView
        // Viewport centre in content space → find the page under it.
        val contentCenterY = (rv.height / 2f - rv.transY) / rv.zoom
        var pageIndex = -1
        var child: View? = null
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(c)
            if (pos < 0) continue
            if (contentCenterY >= c.top && contentCenterY < c.bottom) { pageIndex = pos; child = c; break }
        }
        if (child == null) {
            // Fallback: first laid-out page.
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i) ?: continue
                val pos = rv.getChildAdapterPosition(c)
                if (pos >= 0) { pageIndex = pos; child = c; break }
            }
        }
        val pageChild = child ?: return

        val pageW = pageChild.width.toFloat()
        val pageH = pageChild.height.toFloat()

        // Initial size: 50% of the page width, capped at 60% of the page height.
        var w = pageW * 0.5f
        var h = w * imgH / imgW
        if (h > pageH * 0.6f) { h = pageH * 0.6f; w = h * imgW / imgH }

        val cx = pageW / 2f
        val cy = (contentCenterY - pageChild.top).coerceIn(h / 2f, (pageH - h / 2f).coerceAtLeast(h / 2f))
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        val path = Path().apply { addRect(rect, Path.Direction.CW) }
        val paint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        val stroke = StrokeData(path = path, paint = paint, type = StrokeType.IMAGE, imageFile = imageFile)

        historyManager.execute(DrawingAction.AddStroke(pageIndex, stroke), strokeManager)
        invalidateInk()
        updateUndoRedoButtons()

        // Open the fresh image in selection mode so it can be dragged/resized right away.
        val result = strokeManager.selectStrokes(pageIndex, listOf(stroke)) ?: return
        presentSelection(pageChild.left.toFloat(), pageChild.top.toFloat(), result.second)
    }

    // --- PAGE GRID / SAVE / LOAD ---

    private fun showPageGrid() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { renderableFile() }
            if (file == null) {
                Toast.makeText(this@DrawingActivity, "Nothing to show yet", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val view = layoutInflater.inflate(R.layout.dialog_page_grid, null)
            val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageGridRecycler)
            val outlineRecycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageOutlineRecycler)
            val emptyText = view.findViewById<TextView>(R.id.pageEmptyText)
            val loading = view.findViewById<View>(R.id.pageLoading)
            val tabs = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.pageTabs)
            val selectBar = view.findViewById<View>(R.id.selectActionBar)
            val selectCount = view.findViewById<TextView>(R.id.selectCount)
            val selectButton = view.findViewById<ImageButton>(R.id.pageSelectButton)

            val cols = (resources.configuration.screenWidthDp / 110).coerceIn(2, 6)
            recycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@DrawingActivity, cols)
            val tw = resources.displayMetrics.widthPixels / cols

            val renderer = PdfThumbnailRenderer(file, strokeManager, strokePageSizes())
            // Dialog-scoped work: cancelled on dismiss so queued thumbnail renders can never
            // run against a closed renderer (the old code used lifecycleScope and a render that
            // started before close() would crash with "Document already closed" on the next bind).
            val gridScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val dialog = android.app.Dialog(this@DrawingActivity, R.style.Theme_OctopusNotes)
            gridDialog = dialog
            gridSelectionMode = false
            gridSelected.clear()

            var currentTab = R.id.tabAll
            var adapter: PageGridAdapter? = null
            var touchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null
            // Set when selection mode is switched on, so the caller can seed a selection.
            var setSelectionMode: (Boolean, Int?) -> Unit = { _, _ -> }

            fun pagesForTab(tab: Int): List<Int> = when (tab) {
                R.id.tabBookmark -> (0 until totalPages).filter { pageMeta.isBookmarked(it) }
                else -> (0 until totalPages).toList()
            }

            fun updateSelectCount() { selectCount.text = "${gridSelected.size} selected" }

            adapter = PageGridAdapter(
                renderer, tw, currentPage, gridScope,
                pages = pagesForTab(R.id.tabAll),
                isBookmarked = { pageMeta.isBookmarked(it) },
                onClick = { page -> jumpToPage(page); dialog.dismiss() },
                onMenu = { page, anchor -> showPageActionsPopup(anchor, page) },
                onBookmarkToggle = { page ->
                    pageMeta.toggleBookmark(page)
                    if (currentTab == R.id.tabBookmark) adapter?.setPages(pagesForTab(currentTab))
                    else adapter?.notifyDataSetChanged()
                },
                isSelectionMode = { gridSelectionMode },
                isSelected = { gridSelected.contains(it) },
                onSelectToggle = { page ->
                    if (!gridSelected.add(page)) gridSelected.remove(page)
                    updateSelectCount()
                    adapter?.notifyDataSetChanged()
                },
                onLongPress = { page, holder ->
                    if (!gridSelectionMode) {
                        // First long press: same as tapping Select, with this page picked.
                        setSelectionMode(true, page)
                    } else if (currentTab == R.id.tabAll) {
                        // Already selecting: a second long press starts a reorder drag.
                        touchHelper?.startDrag(holder)
                    }
                }
            )
            recycler.adapter = adapter
            // Open the grid scrolled to (and highlighting) the page you're currently on.
            (recycler.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)
                ?.scrollToPositionWithOffset(currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0)), 0)

            // --- Grid scroll pill (same coalesced/capped drag pattern as the PDF scroll pill) ---
            val pillTrack = view.findViewById<View>(R.id.gridPillTrack)
            val pillThumb = view.findViewById<ImageView>(R.id.gridPillThumb)

            var pillDragging = false
            var pillMoved = false
            var pillPendingFraction = 0f
            var pillFramePosted = false
            val pillFadeHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val pillFadeRunnable = Runnable {
                if (!pillDragging) pillThumb.animate().alpha(0f).setDuration(300).withEndAction {
                    if (!pillDragging && pillThumb.alpha == 0f) {
                        pillThumb.visibility = View.INVISIBLE
                    }
                }.start()
            }

            fun applyGridDragFraction(fraction: Float): Boolean {
                if ((adapter?.itemCount ?: 0) <= 1) return false
                val maxScroll = recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollExtent()
                if (maxScroll <= 0) return false
                val targetScroll = (fraction * maxScroll).toInt()
                val currentScroll = recycler.computeVerticalScrollOffset()
                var dy = targetScroll - currentScroll
                if (dy == 0) return false
                val maxStep = (recycler.height * 3).coerceAtLeast(1)
                dy = dy.coerceIn(-maxStep, maxStep)
                recycler.scrollBy(0, dy)
                return true
            }

            val pillFrameCallback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    pillFramePosted = false
                    if (!pillDragging) return
                    if (applyGridDragFraction(pillPendingFraction)) {
                        pillFramePosted = true
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }

            fun schedulePillFade() {
                pillFadeHandler.removeCallbacks(pillFadeRunnable)
                pillFadeHandler.postDelayed(pillFadeRunnable, 1500)
            }

            /** Mirrors the PDF scroll-pill thumb: reflect the grid's current scroll fraction. */
            fun updateGridPillPosition() {
                if ((adapter?.itemCount ?: 0) <= 1) {
                    pillThumb.alpha = 0f
                    return
                }
                pillThumb.post {
                    val trackHeight = pillTrack.height.toFloat() - pillThumb.height
                    if (trackHeight <= 0) return@post
                    val maxScroll = recycler.computeVerticalScrollRange() - recycler.computeVerticalScrollExtent()
                    val fraction = if (maxScroll > 0) {
                        (recycler.computeVerticalScrollOffset().toFloat() / maxScroll).coerceIn(0f, 1f)
                    } else 0f
                    val trackTop = pillTrack.top.toFloat()
                    pillThumb.translationY = trackTop + fraction * trackHeight - pillThumb.top
                    if (pillThumb.alpha < 1f) {
                        pillThumb.visibility = View.VISIBLE
                        pillThumb.animate().alpha(1f).setDuration(150).start()
                    }
                    schedulePillFade()
                }
            }

            fun showGridPill(show: Boolean) {
                pillFadeHandler.removeCallbacks(pillFadeRunnable)
                if (!show) {
                    pillDragging = false
                    pillThumb.visibility = View.INVISIBLE
                    return
                }
                updateGridPillPosition()
            }

            pillThumb.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pillDragging = true
                        pillMoved = false
                        pillFadeHandler.removeCallbacks(pillFadeRunnable)
                        pillThumb.animate().alpha(1f).setDuration(100).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val trackTop = pillTrack.top.toFloat()
                        val trackHeight = pillTrack.height.toFloat() - pillThumb.height
                        if (trackHeight > 0 && (adapter?.itemCount ?: 0) > 1) {
                            val loc = IntArray(2)
                            (pillTrack.parent as View).getLocationOnScreen(loc)
                            val relativeY = event.rawY - loc[1] - trackTop - pillThumb.height / 2f
                            val fraction = (relativeY / trackHeight).coerceIn(0f, 1f)
                            pillThumb.translationY = trackTop + fraction * trackHeight - pillThumb.top
                            pillMoved = true
                            pillPendingFraction = fraction
                            if (!pillFramePosted) {
                                pillFramePosted = true
                                Choreographer.getInstance().postFrameCallback(pillFrameCallback)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pillDragging = false
                        if (pillFramePosted) {
                            Choreographer.getInstance().removeFrameCallback(pillFrameCallback)
                            pillFramePosted = false
                        }
                        if (pillMoved) {
                            // Land on the finger's target: scrollToPosition anchors straight at
                            // the destination item without binding/measuring every row between.
                            val count = adapter?.itemCount ?: 0
                            val target = (pillPendingFraction * count).toInt()
                                .coerceIn(0, (count - 1).coerceAtLeast(0))
                            recycler.scrollToPosition(target)
                        }
                        schedulePillFade()
                        true
                    }
                    else -> false
                }
            }

            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (pillDragging) return
                    updateGridPillPosition()
                }

                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) updateGridPillPosition()
                }
            })

            val reloadButton = view.findViewById<ImageButton>(R.id.outlineReloadButton)

            fun showTab(tab: Int) {
                currentTab = tab
                // Only the All tab keeps the button; elsewhere selection is long-press only.
                selectButton.visibility = if (tab == R.id.tabAll) View.VISIBLE else View.GONE
                reloadButton.visibility = if (tab == R.id.tabOutline) View.VISIBLE else View.GONE
                // Leaving a tab shouldn't strand the selection bar over a different list.
                if (gridSelectionMode) setSelectionMode(false, null)
                if (tab == R.id.tabOutline) {
                    recycler.visibility = View.GONE
                    showGridPill(false)
                    loadOutlineTab(outlineRecycler, emptyText, loading, file, dialog, renderer, gridScope)
                } else {
                    loading.visibility = View.GONE
                    outlineRecycler.visibility = View.GONE
                    val list = pagesForTab(tab)
                    adapter?.setPages(list)
                    recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                    emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    emptyText.text = if (tab == R.id.tabBookmark) "No bookmarked pages" else ""
                    // Only the page grids (All / Bookmark) get the scroll pill — the outline
                    // list is a separate RecyclerView and scrolls normally.
                    showGridPill(list.size > 1)
                }
            }

            reloadButton.setOnClickListener {
                reloadOutlineFromPdf(file)
                loadOutlineTab(outlineRecycler, emptyText, loading, file, dialog, renderer, gridScope, forceReload = true)
            }

            tabs.setOnCheckedStateChangeListener { _, checkedIds ->
                showTab(checkedIds.firstOrNull() ?: R.id.tabAll)
            }

            setSelectionMode = { on, seedPage ->
                gridSelectionMode = on
                gridSelected.clear()
                if (on && seedPage != null) gridSelected.add(seedPage)
                updateSelectCount()
                selectBar.visibility = if (on) View.VISIBLE else View.GONE
                selectButton.setColorFilter(
                    if (on) com.google.android.material.color.MaterialColors.getColor(
                        selectButton, com.google.android.material.R.attr.colorPrimary, 0
                    ) else androidx.core.content.ContextCompat.getColor(this@DrawingActivity, android.R.color.darker_gray)
                )
                adapter?.notifyDataSetChanged()
            }
            selectButton.setOnClickListener { setSelectionMode(!gridSelectionMode, null) }

            // Drag to reorder. Long press is handled by the adapter so the first one can
            // open selection mode instead of immediately starting a drag.
            val dragCallback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                        androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                        androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                        androidx.recyclerview.widget.ItemTouchHelper.RIGHT,
                0
            ) {
                private var dragFrom = -1

                override fun isLongPressDragEnabled() = false

                override fun onMove(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ): Boolean {
                    if (dragFrom < 0) dragFrom = vh.adapterPosition
                    adapter?.moveItem(vh.adapterPosition, target.adapterPosition)
                    return true
                }

                override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {}

                override fun clearView(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ) {
                    super.clearView(rv, vh)
                    val from = dragFrom
                    val to = vh.adapterPosition
                    dragFrom = -1
                    if (from < 0 || to < 0 || from == to) return
                    // On the All tab position == page index, which is what makes a
                    // straight position-to-page move valid here.
                    movePage(from, to) {
                        renderer.reload(strokePageSizes())
                        setSelectionMode(false, null)
                        adapter?.refreshAfterReorder(pagesForTab(currentTab), currentPage)
                    }
                }
            }
            touchHelper = androidx.recyclerview.widget.ItemTouchHelper(dragCallback)
            touchHelper.attachToRecyclerView(recycler)
            view.findViewById<View>(R.id.selectDelete).setOnClickListener {
                if (gridSelected.isEmpty()) return@setOnClickListener
                val toDelete = gridSelected.sortedDescending()
                MaterialAlertDialogBuilder(this@DrawingActivity)
                    .setTitle("Delete ${toDelete.size} page(s)?")
                    .setMessage("This permanently removes the pages and their notes.")
                    .setPositiveButton("Delete") { _, _ -> deletePages(toDelete) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            view.findViewById<ImageButton>(R.id.pageAddButton).setOnClickListener {
                dialog.dismiss()
                showAddPagePopup(findViewById(R.id.addPageButton))
            }
            view.findViewById<View>(R.id.pageGridClose).setOnClickListener { dialog.dismiss() }

            dialog.setContentView(view)
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // The dialog opens on the All tab without a tab-change event — show the pill now.
            showGridPill(true)
            dialog.setOnDismissListener {
                gridScope.cancel()
                // Drop the pill's coalesced drag frame + fade so nothing fires against a
                // torn-down RecyclerView after the dialog is gone.
                if (pillFramePosted) {
                    Choreographer.getInstance().removeFrameCallback(pillFrameCallback)
                    pillFramePosted = false
                }
                pillFadeHandler.removeCallbacks(pillFadeRunnable)
                renderer.close()
                gridDialog = null
            }
            dialog.show()
        }
    }

    private fun loadOutlineTab(
        outlineRecycler: androidx.recyclerview.widget.RecyclerView,
        emptyText: TextView,
        loading: View,
        file: File,
        dialog: android.app.Dialog,
        renderer: PdfThumbnailRenderer,
        scope: CoroutineScope,
        forceReload: Boolean = false
    ) {
        emptyText.visibility = View.GONE
        outlineRecycler.visibility = View.GONE
        loading.visibility = View.VISIBLE
        scope.launch {
            val embedded = if (forceReload) {
                outlineLoadJob?.join()
                outlineCacheValue ?: withContext(Dispatchers.IO) { readEmbeddedOutline(file) }
            } else {
                withContext(Dispatchers.IO) { readEmbeddedOutline(file) }
            }
            val user = pageMeta.userOutline().map { OutlineUiEntry(it.title, it.page, isUser = true) }
            val roots = (embedded + user).sortedBy { if (it.page >= 0) it.page else Int.MAX_VALUE }
            loading.visibility = View.GONE
            outlineRecycler.layoutManager = LinearLayoutManager(this@DrawingActivity)
            outlineRecycler.adapter = OutlineAdapter(
                roots,
                currentPage,
                renderer,
                scope,
                onClick = { page -> jumpToPage(page); dialog.dismiss() },
                onMenu = { entry, anchor ->
                    val menu = android.widget.PopupMenu(this@DrawingActivity, anchor)
                    menu.menu.add(0, 1, 0, "Go to page")
                    if (entry.isUser) menu.menu.add(0, 2, 1, "Remove from outline")
                    menu.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> { if (entry.page >= 0) { jumpToPage(entry.page); dialog.dismiss() }; true }
                            2 -> {
                                pageMeta.removeOutlineEntry(entry.page, entry.title)
                                loadOutlineTab(outlineRecycler, emptyText, loading, file, dialog, renderer, scope)
                                true
                            }
                            else -> false
                        }
                    }
                    menu.show()
                }
            )
            outlineRecycler.visibility = if (roots.isEmpty()) View.GONE else View.VISIBLE
            emptyText.visibility = if (roots.isEmpty()) View.VISIBLE else View.GONE
            emptyText.text = "No outline yet"
        }
    }

    /**
     * Reads the PDF's embedded outline as a tree via pdfbox.
     *
     * Destination resolution is layered because publishers use different mechanisms:
     * page-object destinations, GoTo actions, named destinations, and page-*number*
     * destinations (where [com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem.findDestinationPage]
     * returns null but the number is retrievable).
     */
    private var outlineCacheValue: List<OutlineUiEntry>? = null
    private var outlineLoadJob: kotlinx.coroutines.Job? = null

    private fun outlineCacheFile(): File = File(cacheDir, "outline_$notebookId.json")

    private fun saveOutlineCache(entries: List<OutlineUiEntry>, pdfLen: Long, pdfMod: Long) {
        try {
            val arr = org.json.JSONArray()
            fun writeList(list: List<OutlineUiEntry>, target: org.json.JSONArray) {
                for (e in list) {
                    val o = org.json.JSONObject()
                    o.put("t", e.title)
                    o.put("p", e.page)
                    o.put("d", e.depth)
                    if (e.children.isNotEmpty()) {
                        val ch = org.json.JSONArray()
                        writeList(e.children, ch)
                        o.put("c", ch)
                    }
                    target.put(o)
                }
            }
            writeList(entries, arr)
            val root = org.json.JSONObject()
            root.put("len", pdfLen)
            root.put("mod", pdfMod)
            root.put("entries", arr)
            outlineCacheFile().writeText(root.toString())
        } catch (_: Exception) {}
    }

    private fun loadOutlineCache(pdfLen: Long, pdfMod: Long): List<OutlineUiEntry>? {
        try {
            val f = outlineCacheFile()
            if (!f.exists()) return null
            val root = org.json.JSONObject(f.readText())
            if (root.getLong("len") != pdfLen || root.getLong("mod") != pdfMod) return null
            fun readList(arr: org.json.JSONArray, depth: Int): List<OutlineUiEntry> {
                val out = mutableListOf<OutlineUiEntry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val children = if (o.has("c")) readList(o.getJSONArray("c"), depth + 1) else emptyList()
                    out.add(OutlineUiEntry(o.getString("t"), o.getInt("p"), isUser = false, depth = depth, children = children))
                }
                return out
            }
            return readList(root.getJSONArray("entries"), 0)
        } catch (_: Exception) {
            return null
        }
    }

    private fun readEmbeddedOutline(file: File): List<OutlineUiEntry> {
        outlineCacheValue?.let { return it }
        val cached = loadOutlineCache(file.length(), file.lastModified())
        if (cached != null) { outlineCacheValue = cached; return cached }
        val result = readEmbeddedOutlineUncached(file)
        outlineCacheValue = result
        saveOutlineCache(result, file.length(), file.lastModified())
        return result
    }

    private fun startBackgroundOutlineLoad() {
        val path = pdfFilePath ?: return
        val file = File(path)
        if (!file.exists()) return
        outlineLoadJob?.cancel()
        val pill = findViewById<TextView>(R.id.outlineLoadingPill)
        pill.visibility = View.VISIBLE
        outlineLoadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readEmbeddedOutline(file) }
            outlineCacheValue = result
            pill.animate().alpha(0f).setDuration(300).withEndAction {
                pill.visibility = View.GONE
                pill.alpha = 1f
            }.start()
        }
    }

    private fun reloadOutlineFromPdf(file: File) {
        outlineCacheValue = null
        outlineCacheFile().delete()
        outlineLoadJob?.cancel()
        outlineLoadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readEmbeddedOutlineUncached(file) }
            outlineCacheValue = result
            withContext(Dispatchers.IO) { saveOutlineCache(result, file.length(), file.lastModified()) }
        }
    }

    private fun readEmbeddedOutlineUncached(file: File): List<OutlineUiEntry> {
        try {
            // Mixed memory setting: parse buffers stay in RAM (up to 32 MB) instead of
            // spooling every read to temp files — much faster for outline-only access.
            val mem = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(32L * 1024 * 1024)
                .setTempDir(cacheDir)
            PDDocument.load(file, mem).use { doc ->
                val outline = doc.documentCatalog.documentOutline ?: return emptyList()

                // O(1) page lookups: map each page's COS dictionary to its index ONCE,
                // instead of an O(n) page-tree walk per outline item.
                val pageIndexOf = HashMap<com.tom_roush.pdfbox.cos.COSDictionary, Int>(doc.numberOfPages * 2)
                for ((i, page) in doc.pages.withIndex()) pageIndexOf[page.cosObject] = i

                fun indexOfPage(pg: com.tom_roush.pdfbox.pdmodel.PDPage?): Int =
                    pg?.let { pageIndexOf[it.cosObject] } ?: -1

                fun resolvePage(item: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem): Int {
                    // 1) Standard resolver (page objects, GoTo actions, named destinations).
                    try {
                        val i = indexOfPage(item.findDestinationPage(doc))
                        if (i >= 0) return i
                    } catch (_: Exception) {}
                    // 2) Destinations that carry a page *number* instead of a page object.
                    try {
                        var dest: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination? =
                            item.destination
                        if (dest == null) {
                            val action = item.action
                            if (action is com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo) {
                                dest = action.destination
                            }
                        }
                        if (dest is com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination) {
                            // Resolve through the catalog's name tree (or the legacy /Dests dict).
                            val name = dest.namedDestination
                            val resolved =
                                try { doc.documentCatalog.names?.dests?.getValue(name) } catch (_: Exception) { null }
                                    ?: try {
                                        doc.documentCatalog.dests?.getDestination(name)
                                            as? com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
                                    } catch (_: Exception) { null }
                            if (resolved != null) dest = resolved
                        }
                        if (dest is com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination) {
                            val i = indexOfPage(dest.page)
                            if (i >= 0) return i
                            val n = dest.retrievePageNumber()
                            if (n >= 0) return n
                        }
                    } catch (_: Exception) {}
                    return -1
                }

                fun walk(
                    start: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem?,
                    depth: Int
                ): List<OutlineUiEntry> {
                    val siblings = mutableListOf<OutlineUiEntry>()
                    var cur = start
                    var guard = 0
                    while (cur != null && guard++ < 5000) {
                        val title = cur.title ?: ""
                        val page = resolvePage(cur)
                        val children = walk(cur.firstChild, depth + 1)
                        // Keep entries even without a resolvable page if they have children
                        // (section headers), so the tree structure survives.
                        if (page >= 0 || children.isNotEmpty()) {
                            siblings.add(OutlineUiEntry(title, page, isUser = false, depth = depth, children = children))
                        }
                        cur = cur.nextSibling
                    }
                    return siblings
                }

                return walk(outline.firstChild, 0)
            }
        } catch (e: Exception) {
            android.util.Log.w("OctopusNotes", "Failed to read PDF outline", e)
        }
        return emptyList()
    }

    private fun showPageActionsPopup(anchor: View, pageIndex: Int) {
        val view = layoutInflater.inflate(R.layout.popup_page_actions, null)
        val popup = android.widget.PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)
        view.findViewById<View>(R.id.actionAddOutline).setOnClickListener { popup.dismiss(); promptAddToOutline(pageIndex) }
        view.findViewById<View>(R.id.actionDuplicate).setOnClickListener { popup.dismiss(); gridDialog?.dismiss(); duplicatePage(pageIndex) }
        view.findViewById<View>(R.id.actionRotateLeft).setOnClickListener { popup.dismiss(); rotatePage(pageIndex, -90) }
        view.findViewById<View>(R.id.actionRotateRight).setOnClickListener { popup.dismiss(); rotatePage(pageIndex, 90) }
        view.findViewById<View>(R.id.actionDelete).setOnClickListener { popup.dismiss(); confirmDeletePage(pageIndex) }
        popup.showAsDropDown(anchor, 0, 0)
    }

    private fun promptAddToOutline(pageIndex: Int) {
        val input = android.widget.EditText(this).apply {
            setText("Page ${pageIndex + 1}"); setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add to outline")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                pageMeta.addOutlineEntry(pageIndex, input.text.toString().trim())
                Toast.makeText(this, "Added to outline", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rotatePage(pageIndex: Int, deltaDeg: Int) {
        val path = pdfFilePath
        if (path == null) {
            Toast.makeText(this, "Can't rotate this page", Toast.LENGTH_SHORT).show()
            return
        }
        // The ink turns with the page, so capture the page's pre-rotation stroke-space size
        // while the old geometry is still what the engine reports.
        val sizeBefore = strokePageSize(pageIndex)
        // Undo entries hold paths in the pre-rotation coordinate space.
        historyManager.clear()
        selectionPopup?.dismiss()
        drawingView.clearSelectionVisuals()
        val busy = showBusyDialog("Rotating page…")
        busy.show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { rotatePageInPdf(File(path), pageIndex, deltaDeg) }
            } catch (e: Exception) {
                e.printStackTrace()
                busy.dismiss()
                Toast.makeText(this@DrawingActivity, "Couldn't rotate the page — please try again", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (sizeBefore != null) {
                strokeManager.rotatePage(pageIndex, deltaDeg, sizeBefore.first, sizeBefore.second)
                strokesDirty = true
                saveDrawing()
            }
            invalidateTextIndex()
            busy.dismiss()
            gridDialog?.dismiss()
            loadPdf()
            Toast.makeText(this@DrawingActivity, "Page rotated", Toast.LENGTH_SHORT).show()
        }
    }

    /** One page's stroke-space size: the laid-out page width, at that page's own aspect. */
    private fun strokePageSize(pageIndex: Int): Pair<Float, Float>? {
        val engine = pdfEngine ?: return null
        val w = currentInkWidth()
        return try {
            val s = engine.getPageSize(pageIndex)
            if (s.width <= 0f || s.height <= 0f) null else Pair(w, w * (s.height / s.width))
        } catch (e: Exception) {
            null
        }
    }

    private fun rotatePageInPdf(file: File, pageIndex: Int, deltaDeg: Int) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        loadPdfLowMemory(file).use { doc ->
            val page = doc.getPage(pageIndex)
            page.rotation = (((page.rotation + deltaDeg) % 360) + 360) % 360
            doc.save(tmp)
        }
        swapPdfIntoPlace(tmp, file)
    }

    /** Deletes several pages at once (indices must be in descending order). */
    private fun deletePages(pagesDesc: List<Int>) {
        if (totalPages - pagesDesc.size < 1) {
            Toast.makeText(this, "Can't delete all pages", Toast.LENGTH_SHORT).show()
            return
        }
        historyManager.clear()
        val path = pdfFilePath
        val busy = showBusyDialog("Deleting pages…")
        busy.show()
        lifecycleScope.launch {
            try {
                for (p in pagesDesc) {
                    if (path != null) withContext(Dispatchers.IO) { removePageFromPdf(File(path), p) }
                    strokeManager.removePage(p, totalPages)
                    strokesDirty = true
                    pageMeta.onPageRemoved(p)
                    totalPages -= 1
                    if (p < currentPage) currentPage--
                }
            } catch (e: Exception) {
                e.printStackTrace()
                busy.dismiss()
                Toast.makeText(this@DrawingActivity, "Couldn't save the PDF — please try again", Toast.LENGTH_LONG).show()
                // The PDF may have been partially modified mid-loop — resync the view.
                loadPdf()
                return@launch
            }
            currentPage = currentPage.coerceIn(0, totalPages - 1)
            statePrefs.edit().putInt("page_count_$notebookId", totalPages).apply()
            saveDrawing()
            invalidateTextIndex()
            busy.dismiss()
            gridDialog?.dismiss()
            loadPdf()
            Toast.makeText(this@DrawingActivity, "Pages deleted", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Commits a page drag: rewrites the PDF, renumbers ink and page metadata, then calls
     * [onDone] so the grid can refresh itself without being torn down.
     */
    private fun movePage(from: Int, to: Int, onDone: () -> Unit) {
        if (from == to || from !in 0 until totalPages || to !in 0 until totalPages) {
            onDone()
            return
        }
        // Undo history is indexed by page and can't survive a renumber.
        historyManager.clear()
        val path = pdfFilePath
        val busy = showBusyDialog("Moving page…")
        busy.show()
        lifecycleScope.launch {
            try {
                if (path != null) withContext(Dispatchers.IO) { movePageInPdf(File(path), from, to) }
            } catch (e: Exception) {
                e.printStackTrace()
                busy.dismiss()
                Toast.makeText(this@DrawingActivity, "Couldn't move the page — please try again", Toast.LENGTH_LONG).show()
                return@launch
            }
            strokeManager.movePage(from, to)
            pageMeta.onPageMoved(from, to)
            strokesDirty = true
            currentPage = pageIndexAfterMove(currentPage, from, to).coerceIn(0, totalPages - 1)
            saveDrawing()
            invalidateTextIndex()
            busy.dismiss()
            loadPdf()
            onDone()
        }
    }

    private var gridDialog: android.app.Dialog? = null
    private val pageMeta by lazy { PageMetaStore(this, notebookId) }
    private var gridSelectionMode = false
    private val gridSelected = mutableSetOf<Int>()

    private fun renderableFile(): File? {
        val path = pdfFilePath
        if (path != null) return File(path).takeIf { it.exists() }
        return try {
            val f = File(cacheDir, "thumbs_blank_$notebookId.pdf")
            f.writeBytes(createBlankPdfBytes(totalPages))
            f
        } catch (e: Exception) { null }
    }

    private fun confirmDeletePage(pageIndex: Int) {
        if (totalPages <= 1) {
            Toast.makeText(this, "Can't delete the only page", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete page ${pageIndex + 1}?")
            .setMessage("This permanently removes the page and its notes.")
            .setPositiveButton("Delete") { _, _ -> gridDialog?.dismiss(); deletePage(pageIndex) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePage(pageIndex: Int) {
        historyManager.clear()
        if (totalPages <= 1) {
            Toast.makeText(this, "Can't delete the only page", Toast.LENGTH_SHORT).show()
            return
        }
        val path = pdfFilePath
        val busy = showBusyDialog("Deleting page…")
        busy.show()
        lifecycleScope.launch {
            try {
                if (path != null) {
                    withContext(Dispatchers.IO) { removePageFromPdf(File(path), pageIndex) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                busy.dismiss()
                Toast.makeText(this@DrawingActivity, "Couldn't delete the page — please try again", Toast.LENGTH_LONG).show()
                return@launch
            }
            strokeManager.removePage(pageIndex, totalPages)
            strokesDirty = true
            pageMeta.onPageRemoved(pageIndex)
            totalPages -= 1
            statePrefs.edit().putInt("page_count_$notebookId", totalPages).apply()
            if (pageIndex < currentPage) currentPage--
            currentPage = currentPage.coerceIn(0, totalPages - 1)
            saveDrawing()
            invalidateTextIndex()
            busy.dismiss()
            loadPdf()
            Toast.makeText(this@DrawingActivity, "Page deleted", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The per-page coordinate space the strokes live in — PDFView's fitted page size (device/view
     * dependent), which is what strokes were authored against. Renderers need this, not the PDF
     * point size, to scale ink correctly.
     */
    private fun strokePageSizes(): List<Pair<Float, Float>> {
        val engine = pdfEngine ?: return emptyList()
        val w = pdfRecyclerView.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()
        // Only query the (few) pages that actually have ink — opening every page of a large
        // PDF on the main thread would hang. Pages without strokes get a harmless default;
        // their size is never used (there's nothing to scale).
        val pagesWithInk = strokeManager.allPagesWithData()
        val defaultSize = Pair(w, w * 1.414f)
        return (0 until engine.pageCount).map { i ->
            if (i in pagesWithInk) {
                val s = engine.getPageSize(i)
                Pair(w, w * (s.height / s.width))
            } else defaultSize
        }
    }

    private fun duplicatePage(pageIndex: Int) {
        historyManager.clear()
        val path = pdfFilePath
        val busy = showBusyDialog("Duplicating page…")
        busy.show()
        lifecycleScope.launch {
            try {
                if (path != null) {
                    withContext(Dispatchers.IO) { duplicatePageInPdf(File(path), pageIndex) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                busy.dismiss()
                Toast.makeText(this@DrawingActivity, "Couldn't duplicate the page — please try again", Toast.LENGTH_LONG).show()
                return@launch
            }
            strokeManager.duplicatePage(pageIndex, totalPages)
            strokesDirty = true
            pageMeta.onPageInserted(pageIndex + 1)
            totalPages += 1
            statePrefs.edit().putInt("page_count_$notebookId", totalPages).apply()
            currentPage = pageIndex + 1
            saveDrawing()
            invalidateTextIndex()
            busy.dismiss()
            loadPdf()
            Toast.makeText(this@DrawingActivity, "Page duplicated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removePageFromPdf(file: File, index: Int) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        loadPdfLowMemory(file).use { doc ->
            if (doc.numberOfPages > 1 && index in 0 until doc.numberOfPages) {
                doc.removePage(index)
            }
            doc.save(tmp)
        }
        swapPdfIntoPlace(tmp, file)
    }

    private fun movePageInPdf(file: File, from: Int, to: Int) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        loadPdfLowMemory(file).use { doc ->
            val n = doc.numberOfPages
            if (from in 0 until n && to in 0 until n && from != to) {
                val moving = doc.getPage(from)
                doc.removePage(from)
                // Indices below shift down once the page is pulled out, so anchor on the
                // neighbour in the *remaining* list rather than the original one.
                if (to > from) doc.pages.insertAfter(moving, doc.getPage(to - 1))
                else doc.pages.insertBefore(moving, doc.getPage(to))
            }
            doc.save(tmp)
        }
        swapPdfIntoPlace(tmp, file)
    }

    private fun duplicatePageInPdf(file: File, index: Int) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        loadPdfLowMemory(file).use { doc ->
            if (index in 0 until doc.numberOfPages) {
                val source = doc.getPage(index)

                // Shallow copy: references existing heavy resources instead of duplicating them
                val pageDict = COSDictionary(source.cosObject)
                pageDict.removeItem(COSName.PARENT)

                val copy = PDPage(pageDict)
                doc.pages.insertAfter(copy, source)
            }
            doc.save(tmp)
        }
        swapPdfIntoPlace(tmp, file)
    }

    // --- SAVE / LOAD STROKES ---

    /**
     * The width strokes are authored against: the laid-out page width, which is the full
     * window width (the page view is edge-to-edge) and therefore differs between portrait
     * and landscape. Matches [PdfPageAdapter]'s own fallback so both agree before layout.
     */
    private fun currentInkWidth(): Float =
        pdfRecyclerView.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()

    /**
     * Assumed authoring width for notebooks saved before the width was recorded. Those were
     * almost always drawn in portrait, i.e. at the display's short side — assuming that (rather
     * than "whatever width we happen to have now") is what lets an old portrait notebook open
     * correctly in landscape.
     */
    private fun legacyInkWidth(): Float {
        val dm = resources.displayMetrics
        return minOf(dm.widthPixels, dm.heightPixels).toFloat()
    }

    /**
     * The page view got wider or narrower (window resize, fold/unfold, or a rotation the
     * activity survived). Stroke coordinates are in the *old* width's px, so rescale them
     * to the new one — otherwise ink drawn in portrait bunches into the middle in landscape,
     * and ink drawn in landscape spills off the right edge in portrait.
     */
    private fun onInkWidthChanged() {
        if (!strokesLoaded) return
        val newWidth = currentInkWidth()
        val old = inkBaseWidth
        if (old <= 0f || newWidth <= 0f || newWidth == old) return
        inkBaseWidth = newWidth
        strokeManager.rescaleAll(newWidth / old) // drops the selection — its transform is stale
        drawingView.clearSelectionVisuals()
        selectionPopup?.dismiss()
        // Undo entries hold paths in the old coordinate space; replaying them would put
        // ink back at the wrong size.
        historyManager.clear()
        updateUndoRedoButtons()
        strokesDirty = true
        invalidateInk()
    }

    private fun loadSavedDrawing() {
        // Must not run before the page view is measured: the decode scales the ink to the
        // width it is about to be drawn at, and a width of 0 would fall back to a guess.
        pdfRecyclerView.doOnLayout { loadSavedDrawingNow() }
    }

    private fun loadSavedDrawingNow() {
        val targetWidth = currentInkWidth()
        val fallbackBaseWidth = legacyInkWidth()
        inkBaseWidth = targetWidth
        lifecycleScope.launch {
            // Pages are installed as they decode so ink appears progressively instead of
            // only after the whole notebook has been parsed.
            val shown = mutableSetOf<Int>()
            val result = withContext(Dispatchers.IO) {
                drawingRepository.load(notebookId, targetWidth, fallbackBaseWidth) { pageIndex, page ->
                    launch(Dispatchers.Main) {
                        strokeManager.applyLoadedPage(pageIndex, page)
                        shown.add(pageIndex)
                        invalidateInk()
                    }
                }
            }
            // Authoritative pass for anything not delivered above (e.g. a backup fallback,
            // which decodes without streaming). Pages already shown are left alone.
            for ((p, page) in result.pages) {
                if (p !in shown) strokeManager.applyLoadedPage(p, page)
            }
            strokesLoaded = true
            invalidateInk()
            if (result.mainFileCorrupt) {
                MaterialAlertDialogBuilder(this@DrawingActivity)
                    .setTitle("Notes recovered")
                    .setMessage(
                        if (result.pages.isEmpty())
                            "This notebook's ink file was damaged and no backup could be read. " +
                                    "The damaged file has been kept (as .corrupt) in case it can be recovered."
                        else
                            "This notebook's ink file was damaged, so the last backup was restored. " +
                                    "Your most recent strokes may be missing."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
            if (result.unsupportedTypeCounts.isNotEmpty()) {
                notifyUnsupportedTools(result.unsupportedTypeCounts)
            }
        }
    }

    private fun notifyUnsupportedTools(counts: Map<String, Int>) {
        val total = counts.values.sum()
        val hidden = counts.entries.joinToString(", ") { "${it.key} (${it.value})" }
        val shown = StrokeType.SUPPORTED.joinToString(", ")
        MaterialAlertDialogBuilder(this)
            .setTitle("Some items are hidden")
            .setMessage(
                "This notebook contains $total item(s) made with tools this version can't display yet: " +
                        "$hidden.\n\nThey have been kept and will reappear in a newer version of the app.\n\n" +
                        "Currently showing: $shown."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        // The home thumbnail is not written here — MainActivity regenerates it when the
        // user comes back to the home screen, which is the only place it is shown.
        saveDrawing()
    }

    // Periodic autosave: guards against process death that skips onPause (force-stop,
    // crash, system kill). Only writes when something actually changed.
    private val AUTOSAVE_INTERVAL_MS = 30_000L
    private val autosaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autosaveRunnable = object : Runnable {
        override fun run() {
            saveDrawing()
            autosaveHandler.postDelayed(this, AUTOSAVE_INTERVAL_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        autosaveHandler.postDelayed(autosaveRunnable, AUTOSAVE_INTERVAL_MS)
    }

    override fun onStop() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        super.onStop()
    }

    private fun saveDrawing() {
        if (notebookId < 0) return
        // Never write before the saved strokes are decoded — the StrokeManager is still
        // empty and the save would replace the notebook file with an empty document.
        if (!strokesLoaded) return
        if (!strokesDirty) return
        strokesDirty = false
        val pages = strokeManager.allPagesWithData()
        val known = pages.associateWith { strokeManager.knownStrokesForPage(it).toList() }
        val unknown = pages.associateWith { strokeManager.unknownStrokesForPage(it).toList() }
        // The page width these stroke coordinates mean, recorded so the next open can
        // rescale them if it happens at a different width (i.e. the other orientation).
        val baseWidth = inkBaseWidth.takeIf { it > 0f } ?: currentInkWidth()
        lastSaveJob = lifecycleScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                drawingRepository.save(
                    notebookId,
                    pages,
                    knownProvider = { known[it] ?: emptyList() },
                    unknownProvider = { unknown[it] ?: emptyList() },
                    baseWidth = baseWidth
                )
            } catch (e: Exception) {
                strokesDirty = true // failed — retry on the next pause/autosave tick
                return@launch
            }
            dataManager.touchModified(notebookId)
        }
    }

    companion object {
        /**
         * The most recent stroke save, which outlives this activity (it runs NonCancellable).
         * MainActivity waits on it before re-rendering the home thumbnail, so the thumbnail is
         * never drawn from ink that hasn't reached disk yet.
         */
        @Volatile
        var lastSaveJob: kotlinx.coroutines.Job? = null
    }

    // --- PAGE STATE ---

    private fun onPageChanged(page: Int, pageCount: Int) {
        if (pageRestorePending) return
        currentPage = page
        updatePageNumberView()
        // The persisted last_page_$notebookId write is delayed until scroll-state IDLE
        // (see setupScrollPill's onScrollStateChanged listener). Writing it here, on every
        // page-border crossing during a fast scroll-pill drag, fired dozens of SharedPreferences
        // apply() calls per gesture, contributing to the main-thread ANR.
    }
    private fun updatePageNumberView() { pageNumberTextView.text = "${currentPage + 1} / $totalPages" }

    /** Persists the current page so the notebook re-opens at the same spot next time. */
    private fun persistLastPage() {
        if (notebookId < 0 || pageRestorePending) return
        statePrefs.edit().putInt("last_page_$notebookId", currentPage).apply()
    }

    // --- ZOOM INDICATOR ---

    /** Updates the persistent "NNN%" zoom pill above the page indicator. */
    private fun showZoomIndicator(zoom: Float) {
        val pill = findViewById<TextView>(R.id.zoomIndicator)
        pill.text = "${(zoom * 100).toInt()}%"
        pill.visibility = View.VISIBLE
    }

    private fun showZoomOptions() {
        val labels = arrayOf("50%", "75%", "100%", "150%", "200%", "500%", "1000%", "Custom…")
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.5f, 2f, 5f, 10f, -1f)
        MaterialAlertDialogBuilder(this)
            .setTitle("Zoom")
            .setItems(labels) { _, which ->
                if (values[which] < 0) showCustomZoomDialog()
                else pdfRecyclerView.setZoomLevel(values[which])
            }
            .show()
    }

    private fun showCustomZoomDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Zoom %"
            setText("${(pdfRecyclerView.zoom * 100).toInt()}")
            selectAll()
        }
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(50, 20, 50, 20)
        input.layoutParams = params
        container.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle("Custom zoom")
            .setView(container)
            .setPositiveButton("Set") { _, _ ->
                val pct = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                pdfRecyclerView.setZoomLevel((pct / 100f).coerceIn(0.5f, 10f))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- SCROLL PILL ---

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollPill() {
        scrollPillTrack = findViewById(R.id.scrollPillTrack)
        scrollPillThumb = findViewById(R.id.scrollPillThumb)

        // Move thumb to reflect current scroll position
        pdfRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (scrollPillDragging) return
                updateScrollPillPosition()
            }
        })

        // Persist last_page only when scrolling actually stops, once per drag — never per
        // page-crossing. Also reposition the pill once scrolling settles.
        pdfRecyclerView.onScrollStateChanged = { newState ->
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                persistLastPage()
                updateScrollPillPosition()
            }
        }

        // Drag-to-scroll on the thumb
        scrollPillThumb.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scrollPillDragging = true
                    dragMoved = false
                    scrollPillFadeHandler.removeCallbacks(scrollPillFadeRunnable)
                    scrollPillThumb.animate().alpha(1f).setDuration(100).start()
                    // Tell the engine & recycler: this is a fast-scroll drag. The single
                    // render thread should serve only the visible-page base renders the
                    // drag will issue; the size-prefetch sweep and the zoomed-in hi-res
                    // tile renderer must stand down.
                    pdfEngine?.renderPausedForDrag = true
                    pdfRecyclerView.dragInProgress = true
                    pdfRecyclerView.cancelHiResForDrag()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val trackTop = scrollPillTrack.top.toFloat()
                    val trackHeight = scrollPillTrack.height.toFloat() - scrollPillThumb.height
                    if (trackHeight > 0 && totalPages > 1) {
                        // event.rawY is in screen coords; convert to parent-relative
                        val loc = IntArray(2)
                        (scrollPillTrack.parent as View).getLocationOnScreen(loc)
                        val relativeY = event.rawY - loc[1] - trackTop - scrollPillThumb.height / 2f
                        val fraction = (relativeY / trackHeight).coerceIn(0f, 1f)

                        // Manually position thumb immediately for smooth visual feedback,
                        // independent of the coalesced scrollBy (which runs once per frame).
                        scrollPillThumb.translationY =
                            trackTop + fraction * trackHeight - scrollPillThumb.top

                        // Coalesce: many ACTION_MOVEs per frame collapse into a single
                        // capped scrollBy consumed once per Choreographer FrameCallback.
                        // Doing one big scrollBy + bind churn per move event (the old code)
                        // on a fast fling overshot the 5s input-dispatch window → ANR.
                        dragMoved = true
                        dragPendingFraction = fraction
                        if (!dragFramePosted) {
                            dragFramePosted = true
                            Choreographer.getInstance().postFrameCallback(dragFrameCallback)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scrollPillDragging = false
                    // Stop the per-frame catch-up loop.
                    if (dragFramePosted) {
                        Choreographer.getInstance().removeFrameCallback(dragFrameCallback)
                        dragFramePosted = false
                    }
                    if (dragMoved) {
                        // Land exactly on the finger's target page. A single giant scrollBy
                        // would bind + measure every page it traversed (seconds of work on a
                        // 4000-page PDF); scrollToPositionWithOffset anchors straight at the
                        // destination and only lays out the pages around it, so the final
                        // jump is instant regardless of distance.
                        val targetPage = (dragPendingFraction * totalPages).toInt()
                            .coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                        jumpToPage(targetPage)
                    }
                    // Let the render thread serve prefetch again and resume the size sweep.
                    pdfRecyclerView.dragInProgress = false
                    pdfEngine?.renderPausedForDrag = false
                    // If the size-prefetch sweep was interrupted by the drag, restart it
                    // so the cache warm-up completes in the background once scrolling settles.
                    pdfEngine?.let { eng ->
                        lifecycleScope.launch { eng.prefetchSizes() }
                    }
                    scheduleScrollPillFade()
                    // Persist the page we ended on (covers the case where the idle-state
                    // listener doesn't fire because no onScrolled moved us after the final
                    // coalesced warp — e.g. landing mid-page on a long PDF).
                    persistLastPage()
                    // Re-arm hi-res tile rendering now that the drag has ended and zoom may
                    // still be > 1.15, so sharp text shows up where it's settled.
                    pdfRecyclerView.scheduleHiResPublic()
                    true
                }
                else -> false
            }
        }

        // NOTE: the track intentionally has NO touch listener. It used to consume the whole
        // right edge (tap-to-jump), which both hijacked the Android back-swipe gesture and let
        // any side tap scroll the PDF. Dragging is now only possible by pressing the pill itself.

        // Start hidden (INVISIBLE so it can't intercept touches near the edge until shown).
        scrollPillThumb.alpha = 0f
        scrollPillThumb.visibility = View.INVISIBLE
    }

    /**
     * Applies a drag fraction as a single CAPPED scrollBy — called once per frame.
     *
     * The per-frame step is limited to a few viewport heights, so even the fastest pull
     * through a long PDF only binds a handful of pages per frame on the main thread (the
     * old code scrolled the full remaining distance every frame, binding every page it
     * traversed — the ANR trigger on 4000-page documents). The thumb is glued to the
     * finger by the touch handler, so the list simply catches up over the next few frames.
     *
     * Returns true while the list still has distance to cover, so the caller keeps posting
     * frame callbacks until it catches up (the finger may be paused mid-drag).
     */
    private fun applyDragFraction(fraction: Float): Boolean {
        if (totalPages <= 1) return false
        val maxScroll = pdfRecyclerView.computeVerticalScrollRange() -
                        pdfRecyclerView.computeVerticalScrollExtent()
        if (maxScroll <= 0) return false
        val targetScroll = (fraction * maxScroll).toInt()
        val currentScroll = pdfRecyclerView.computeVerticalScrollOffset()
        var dy = targetScroll - currentScroll
        if (dy == 0) return false
        val maxStep = (pdfRecyclerView.height * maxDragViewportsPerFrame).coerceAtLeast(1)
        dy = dy.coerceIn(-maxStep, maxStep)
        pdfRecyclerView.scrollBy(0, dy)
        return true
    }

    /** Position the scroll pill thumb to reflect the current scroll offset. */
    private fun updateScrollPillPosition() {
        if (totalPages <= 1) {
            scrollPillThumb.alpha = 0f
            return
        }
        scrollPillThumb.post {
            val trackHeight = scrollPillTrack.height.toFloat() - scrollPillThumb.height
            if (trackHeight <= 0) return@post
            
            val maxScroll = pdfRecyclerView.computeVerticalScrollRange() - pdfRecyclerView.computeVerticalScrollExtent()
            val fraction = if (maxScroll > 0) {
                (pdfRecyclerView.computeVerticalScrollOffset().toFloat() / maxScroll).coerceIn(0f, 1f)
            } else {
                0f
            }
            
            val trackTop = scrollPillTrack.top.toFloat()
            scrollPillThumb.translationY = trackTop + fraction * trackHeight - scrollPillThumb.top

            // Show the pill (briefly) then schedule fade. onScrolled fires many times per
            // second during a scroll, so only kick the fade-in when the thumb isn't already
            // fully visible — restarting the alpha animation on every scroll event was pure
            // main-thread churn during long PDFs.
            if (scrollPillThumb.alpha < 1f) {
                scrollPillThumb.visibility = View.VISIBLE
                scrollPillThumb.animate().alpha(1f).setDuration(150).start()
            }
            scheduleScrollPillFade()
        }
    }

    private fun scheduleScrollPillFade() {
        scrollPillFadeHandler.removeCallbacks(scrollPillFadeRunnable)
        scrollPillFadeHandler.postDelayed(scrollPillFadeRunnable, 1500)
    }

    // --- EDGE TO EDGE ---

    private fun setupEdgeToEdge() {
        val rootLayout: View = findViewById(R.id.drawingRootLayout)
        val topBarStart: View = findViewById(R.id.topBarStart)
        val topBarEnd: View = findViewById(R.id.topBarEnd)
        val toolDock: View = findViewById(R.id.toolDock)
        val searchBar: View = findViewById(R.id.searchBar)
        val pageIndicator: View = findViewById(R.id.pageNumberTextView)
        val searchProgress: View = findViewById(R.id.searchProgressPill)
        val scrollTrack: View = findViewById(R.id.scrollPillTrack)
        val scrollThumb: View = findViewById(R.id.scrollPillThumb)

        val gap = (8 * resources.displayMetrics.density).toInt()
        val isWideScreen = resources.configuration.screenWidthDp >= 600

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val safeInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            // When the keyboard is up, bottom-anchored UI should float above it.
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val bottomFloat = maxOf(safeInsets.bottom, imeInsets.bottom)

            (topBarStart.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                topMargin = safeInsets.top + gap
                leftMargin = safeInsets.left + gap
            }
            (topBarEnd.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                topMargin = safeInsets.top + gap
                rightMargin = safeInsets.right + gap
            }
            // Large screens: top-centre dock below the status bar.
            // Small screens: dock on the left edge, vertically centred.
            (toolDock.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                if (isWideScreen) {
                    topMargin = safeInsets.top + gap
                    leftMargin = 0
                } else {
                    topMargin = 0
                    leftMargin = safeInsets.left + gap
                }
            }
            // Search bar floats just below the (top-anchored) dock, so only a small gap here.
            (searchBar.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                topMargin = gap
                rightMargin = safeInsets.right + gap
            }
            (pageIndicator.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                leftMargin = safeInsets.left + gap * 2
                bottomMargin = bottomFloat + gap * 2
            }
            (searchProgress.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                bottomMargin = bottomFloat + gap * 2
            }

            (scrollTrack.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                rightMargin = safeInsets.right
                bottomMargin = bottomFloat + gap
                topMargin = gap
            }
            (scrollThumb.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                rightMargin = safeInsets.right
            }

            topBarStart.requestLayout()
            topBarEnd.requestLayout()
            toolDock.requestLayout()
            searchBar.requestLayout()
            pageIndicator.requestLayout()
            searchProgress.requestLayout()
            scrollTrack.requestLayout()
            scrollThumb.requestLayout()
            insets
        }
    }

}