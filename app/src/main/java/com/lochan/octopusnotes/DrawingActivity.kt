package com.lochan.octopusnotes

import android.annotation.SuppressLint
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
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
import com.lochan.octopusnotes.pdfedit.IncrementalPdfEditor
import com.lochan.octopusnotes.pdfedit.PdfEditException
import com.lochan.octopusnotes.pdfedit.PdfLinkReader
import com.lochan.octopusnotes.pdfedit.PdfOutlineReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private var tabsController: TabsController? = null

    private val dockThicknessDefaults = floatArrayOf(5f, 7f, 10f)
    private val dockThickness = FloatArray(3)
    private var dockActiveThicknessIndex = 2
    private val dockDefaultColors = intArrayOf(
        Color.BLACK,
        Color.parseColor("#1565C0"),
        Color.parseColor("#C62828"),
        Color.parseColor("#2E7D32"),
        Color.parseColor("#F9A825")
    )
    private val dockColors = IntArray(5)
    private var dockActiveColorIndex = 0

    private var tableThickness: FloatArray? = null
    private var tableActiveThicknessIndex = -1
    private var tableLineStyle: String? = null
    private var laserThickness: FloatArray? = null
    private var laserActiveThicknessIndex = -1

    private var savedDataLoaded = false

    private var strokesLoaded = false

    private var strokesDirty = false

    private var inkBaseWidth = 0f
    private var pageRestorePending = true
    private var pdfFilePath: String? = null

    private var barrelStrokeInProgress = false

    private val stylusSwitcher = StylusToolSwitcher(
        activateTool = { stylusActivateTool(it) },
        currentToolId = { activeToolId() }
    )

    private var pdfTextIndex: PdfTextIndex? = null
    private var searchMatches: List<PdfTextIndex.Match> = emptyList()
    private var searchPos = -1
    private var selectionPopup: android.widget.PopupWindow? = null
    private var selectionTotalDx = 0f
    private var selectionTotalDy = 0f

    private lateinit var scrollPillTrack: View
    private lateinit var scrollPillThumb: View
    private var scrollPillDragging = false

    private var dragPendingFraction: Float = 0f

    private var dragMoved = false
    private var dragFramePosted = false

    private val maxDragViewportsPerFrame = 3

    private val dragFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            dragFramePosted = false
            if (!scrollPillDragging) return
            if (applyDragFraction(dragPendingFraction)) {

                dragFramePosted = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }
    private val scrollPillFadeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scrollPillFadeRunnable = Runnable {
        if (!scrollPillDragging) scrollPillThumb.animate().alpha(0f).setDuration(300)
            .withEndAction {

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

    private val templateImageUriState = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)
    private val templatePickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->

            templateImageUriState.value = uri?.let { PageTemplate.copyTemplateImage(this, it) } ?: uri
        }

    private var selectionTotalScale = 1f

    private lateinit var contentTools: ContentToolsController

    private val searchFillPaint = Paint().apply {
        color = Color.parseColor("#66FFEB3B")
        style = Paint.Style.FILL
    }
    private val activeFillPaint = Paint().apply {
        color = Color.parseColor("#99FF9800")
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

        findViewById<View>(R.id.drawingRootLayout).setBackgroundColor(CanvasColor.current(this))

        val notesDao = AppDatabase.getDatabase(this).notesDao()
        dataManager = DataManager(notesDao, cacheDir, filesDir)
        drawingRepository = DrawingRepository(this)
        notebookId = intent.getLongExtra("NOTEBOOK_ID", -1L)
        if (notebookId >= 0) lifecycleScope.launch { dataManager.touchOpened(notebookId) }

        tabsController = TabsController(
            this, lifecycleScope, dataManager, { notebookId }, { saveDrawing() },
            switchInPlace = ::switchNotebookInPlace
        )
        tabsController?.attach()

        strokeManager = StrokeManager()
        strokeManager.imagesDir = java.io.File(filesDir, "images").apply { mkdirs() }

        strokeManager.onLayerRendered = { invalidateInk() }

        currentPage = statePrefs.getInt("last_page_$notebookId", 0)
        totalPages = statePrefs.getInt("page_count_$notebookId", 1)

        historyManager = HistoryManager()
        historyManager.onMutation = { strokesDirty = true }

        historyManager.onHistoryChanged = { updateUndoRedoButtons() }

        setupViews()
        setupEdgeToEdge()
        loadPdf()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::drawingView.isInitialized && drawingView.onStylusKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun invalidateInk() {
        pdfAdapter?.notifyInkChanged()
    }

    override fun onDestroy() {
        super.onDestroy()

        Choreographer.getInstance().removeFrameCallback(dragFrameCallback)
        dragFramePosted = false
        strokeManager.clearBitmapCache()
        strokeManager.shutdown()
        pdfEngine?.close()

        val pending = lastSaveJob
        if (pending != null && pending.isActive) {
            Thread {
                try { runBlocking { pending.join() } } catch (_: Exception) {}
            }.start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        pdfRecyclerView = findViewById(R.id.pdfRecyclerView)
        drawingView = findViewById(R.id.drawingView)
        pageNumberTextView = findViewById(R.id.pageNumberTextView)

        drawingView.scribbleReversalThreshold = if (
            getSharedPreferences("OctopusNotesPrefs", MODE_PRIVATE)
                .getString("scribble_erase_difficulty", "EASY") == "HARD"
        ) 4 else 3

        toolSettingsManager = ToolSettingsManager(this, drawingView)

        contentTools = ContentToolsController(
            this,
            strokeManager,
            historyManager,
            host = object : ContentToolsHost {
                override val anchorView: View get() = pdfRecyclerView
                override val smallDock: Boolean get() = this@DrawingActivity.smallDock
                override val supportsTableStructure: Boolean get() = true
                override fun contentRectToScreen(pageIndex: Int, rect: RectF): RectF? {
                    val rv = pdfRecyclerView
                    val zoom = rv.zoom
                    var ox = 0f
                    var oy = 0f
                    var found = false
                    for (i in 0 until rv.childCount) {
                        val c = rv.getChildAt(i) ?: continue
                        if (rv.getChildAdapterPosition(c) == pageIndex) { ox = c.left.toFloat(); oy = c.top.toFloat(); found = true; break }
                    }
                    if (!found) {
                        for (i in 0 until rv.childCount) {
                            val c = rv.getChildAt(i) ?: continue
                            if (rv.getChildAdapterPosition(c) >= 0) { ox = c.left.toFloat(); oy = c.top.toFloat(); found = true; break }
                        }
                    }
                    if (!found) return null
                    val m = Matrix().apply {
                        postTranslate(ox, oy)
                        postScale(zoom, zoom)
                        postTranslate(rv.transX, rv.transY)
                    }
                    val screen = RectF(rect)
                    m.mapRect(screen)
                    return screen
                }
                override fun showPendingTable(pageIndex: Int, rect: RectF, rows: Int, cols: Int, headerRow: Boolean, headerCol: Boolean) =
                    drawingView.showPendingTable(pageIndex, rect, rows, cols, headerRow, headerCol)
                override fun updatePendingTableGrid(rows: Int, cols: Int, headerRow: Boolean, headerCol: Boolean) =
                    drawingView.updatePendingTableGrid(rows, cols, headerRow, headerCol)
                override fun clearPendingTable() = drawingView.clearPendingTable()
                override fun placeTarget(): ContentPlaceTarget {
                    val rv = pdfRecyclerView
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
                        for (i in 0 until rv.childCount) {
                            val c = rv.getChildAt(i) ?: continue
                            val pos = rv.getChildAdapterPosition(c)
                            if (pos >= 0) { pageIndex = pos; child = c; break }
                        }
                    }
                    val c = child ?: return ContentPlaceTarget(0, RectF(0f, 0f, 1f, 1f), PointF(0f, 0f))
                    val area = RectF(0f, 0f, c.width.toFloat(), c.height.toFloat())
                    val center = PointF(c.width / 2f, (contentCenterY - c.top).coerceIn(0f, c.height.toFloat()))
                    return ContentPlaceTarget(pageIndex, area, center)
                }
                override fun textWrapWidth(pageIndex: Int): Float =
                    pdfRecyclerView.findViewHolderForAdapterPosition(pageIndex)?.itemView?.width?.toFloat()
                        ?: pdfRecyclerView.width.toFloat()
                override fun textColor(): Int = dockColors[dockActiveColorIndex]
                override fun applyTapeSettings(pattern: String, width: Float) {
                    drawingView.tapePattern = pattern
                    drawingView.tapeWidth = width
                }
                override fun onContentChanged() {
                    invalidateInk()
                    updateUndoRedoButtons()
                }
                override fun onObjectPlaced(pageIndex: Int, stroke: StrokeData) {
                    val result = strokeManager.selectStrokes(pageIndex, listOf(stroke)) ?: return
                    val origin = drawingView.pageOrigin(pageIndex)
                    presentSelection(origin?.x ?: 0f, origin?.y ?: 0f, result.second)
                    invalidateInk()
                }
                override fun onTableReplaced(pageIndex: Int, oldTable: StrokeData, newTable: StrokeData) {
                    if (strokeManager.activeSelectionPageIndex == pageIndex &&
                        strokeManager.activeSelectionStrokes.any { it.type == StrokeType.TABLE && it.id == oldTable.id }
                    ) {
                        strokeManager.selectStrokes(pageIndex, listOf(newTable))
                        drawingView.updateSelectionVisuals(strokeManager.activeSelectionStrokes)
                    }
                }
                override fun onOpenTableStructure(pageIndex: Int, table: StrokeData, row: Int, col: Int) =
                    openTableStructurePopup(pageIndex, table, row, col)
            }
        )

        pdfRecyclerView.onViewportWidthChanged = { newWidth, _ -> onInkWidthChanged(newWidth.toFloat()) }

        pdfRecyclerView.layoutManager = pdfRecyclerView.createZoomAwareLayoutManager()
        pdfRecyclerView.onPageChanged = { page, count -> onPageChanged(page, count) }
        pdfRecyclerView.onZoomChanged = { z -> showZoomIndicator(z) }
        findViewById<TextView>(R.id.zoomIndicator).apply {
            visibility = View.VISIBLE
            text = "100%"
            setOnClickListener { showZoomOptions() }
        }

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
        setupShapeDock()
        setupTextDock()
        configureDockPlacement()
        applyDockTransitions()

        val undoButton: ImageButton = findViewById(R.id.undoButton)
        val redoButton: ImageButton = findViewById(R.id.redoButton)

        undoButton.setOnClickListener {
            if (historyManager.undo(strokeManager)) {

                strokeManager.cancelSelection()
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

        val shapeButton: ImageButton = findViewById(R.id.shapeButton)
        shapeButton.setOnClickListener {
            if (activeToolButton != shapeButton) {
                setActiveTool(shapeButton)
                applyShapeSettings()
            } else {
                toggleToolOptions()
            }
        }

        val tableButton: ImageButton = findViewById(R.id.tableButton)
        tableButton.setOnClickListener {
            if (activeToolButton != tableButton) {
                setActiveTool(tableButton)
                applyTableSettings()
            } else {
                toggleToolOptions()
            }
        }

        val laserButton: ImageButton = findViewById(R.id.laserButton)
        laserButton.setOnClickListener {
            if (activeToolButton != laserButton) {
                setActiveTool(laserButton)
                applyLaserSettings()
            } else {
                toggleToolOptions()
            }
        }

        val measureButton: ImageButton = findViewById(R.id.measureButton)
        measureButton.setOnClickListener {
            if (activeToolButton != measureButton) {
                setActiveTool(measureButton)
                applyMeasureSettings()
            } else {
                toggleToolOptions()
            }
        }

        val tapeButton: ImageButton = findViewById(R.id.tapeButton)
        tapeButton.setOnClickListener {
            if (activeToolButton != tapeButton) {
                setActiveTool(tapeButton)
                applyTapeSettings()
            } else {
                toggleToolOptions()
            }
        }

        val textButton: ImageButton = findViewById(R.id.textButton)
        textButton.setOnClickListener {
            if (activeToolButton != textButton) {
                setActiveTool(textButton)
                applyTextSettings()
            } else {
                toggleToolOptions()
            }
        }

        val imageButton: ImageButton = findViewById(R.id.imageButton)
        imageButton.setOnClickListener {
            if (activeToolButton != imageButton) {
                setActiveTool(imageButton)

                drawingView.setDrawingMode(false)
                drawingView.clearMeasure()
            } else {
                toggleToolOptions()
            }
        }
        contentTools.wireImageStrip()
        contentTools.wireTapeStrip()
        drawingView.selectionBitmapProvider = { strokeManager.bitmapFor(it) }
        drawingView.selectionStrokeRenderer = { canvas, stroke, zoom ->
            when (stroke.type) {
                StrokeType.TABLE -> strokeManager.drawTableStroke(canvas, stroke, zoom = zoom)
                StrokeType.TEXT -> strokeManager.drawTextStroke(canvas, stroke)
                StrokeType.TAPE -> strokeManager.drawTapeStroke(canvas, stroke)
                else -> {}
            }
        }

        drawingView.onSelectionTransformChanged = { repositionSelectionPopup() }
        pdfRecyclerView.onPanned = { dx, dy -> drawingView.translateSelectionScreen(dx, dy) }
        pdfRecyclerView.onZoomed = { drawingView.refreshSelectionForViewport() }
        pdfRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy != 0) drawingView.translateSelectionScreen(-dx.toFloat(), -dy.toFloat())
            }
        })

        addPageButton.setOnClickListener { showAddPagePopup(addPageButton) }
        pageNumberTextView.setOnClickListener { showJumpToPageDialog() }

        setupScrollPill()

        drawingView.onStrokeFinishedListener = { pageIndex, path, paint, contourData ->

            val effectiveToolId = if (barrelStrokeInProgress) R.id.eraserButton else activeToolButton?.id

            val action = when (effectiveToolId) {

                R.id.eraserButton -> strokeManager.processErase(pageIndex, path, paint, toolSettingsManager.currentEraserType)
                R.id.highlighterButton -> handleHighlighterStrokeAction(pageIndex, path, paint, contourData)
                R.id.shapeButton -> strokeManager.processPen(pageIndex, path, paint, DrawingView.Tool.SHAPE, drawingView.penLineStyle, contourData)
                R.id.tableButton -> {
                    contentTools.onTableStrokeFinished(pageIndex, path, paint, drawingView.penLineStyle)
                    null
                }
                R.id.tapeButton -> {
                    contentTools.onTapeStrokeFinished(pageIndex, path, paint, drawingView.tapePattern, drawingView.tapeWidth)
                    null
                }
                else -> handlePenStrokeAction(pageIndex, path, paint, contourData)
            }

            if (action != null) {
                historyManager.execute(action, strokeManager)
                invalidateInk()
                updateUndoRedoButtons()
                maybeAutoAppendPage(pageIndex)

                if (effectiveToolId == R.id.shapeButton && action is DrawingAction.AddStroke) {
                    selectShapeAfterDraw(pageIndex, action.stroke)
                }
            }
        }

        drawingView.onLassoFinishedListener = { lassoScreenPath ->
            handleLassoSelection(lassoScreenPath)
        }
        drawingView.onTextTapListener = { pageIndex, pageX, pageY ->
            contentTools.onTextTap(pageIndex, pageX, pageY)
        }
        drawingView.onTableCellTapListener = { pageIndex, pageX, pageY ->
            contentTools.onTableCellTap(pageIndex, pageX, pageY)
        }
        drawingView.onTapeTapListener = { pageIndex, pageX, pageY ->
            contentTools.onTapeTap(pageIndex, pageX, pageY)
        }
        drawingView.onSelectionMovedListener = { transform ->
            handleSelectionCommit(transform)
        }

        drawingView.onFingerLongPress = { x, y -> showPastePopup(x, y) }

        drawingView.onBarrelButtonChanged = { pressed ->
            if (pressed) {
                barrelStrokeInProgress = true

                drawingView.setTool(toolSettingsManager.currentEraserType)
                drawingView.setBrushSize(toolSettingsManager.lastEraserSize)
                drawingView.setDrawingMode(true)
            } else {
                barrelStrokeInProgress = false

                when (activeToolButton?.id) {
                    R.id.eraserButton -> toolSettingsManager.applyEraserSettings()
                    R.id.highlighterButton -> toolSettingsManager.applyHighlighterSettings()
                    R.id.selectionButton -> {
                        drawingView.setTool(DrawingView.Tool.LASSO)
                        drawingView.setDrawingMode(true)
                    }
                    R.id.shapeButton -> applyShapeSettings()
                    R.id.tableButton -> applyTableSettings()
                    R.id.laserButton -> applyLaserSettings()
                    R.id.textButton -> applyTextSettings()
                    R.id.measureButton -> applyMeasureSettings()
                    else -> toolSettingsManager.applyPenSettings()
                }
            }
        }

        drawingView.onStylusPrimaryAction = { stylusSwitcher.apply(toolSettingsManager.stylusSettings.primaryAction) }
        drawingView.onStylusSecondaryAction = {
            if (toolSettingsManager.stylusSettings.secondaryAction == StylusAction.ERASER) {
                drawingView.engageBarrel()
            } else {
                stylusSwitcher.apply(toolSettingsManager.stylusSettings.secondaryAction)
            }
        }

        val searchButton: ImageButton = findViewById(R.id.searchButton)
        val searchBar: View = findViewById(R.id.searchBar)
        val searchInput: android.widget.EditText = findViewById(R.id.searchInput)
        val searchCount: TextView = findViewById(R.id.searchCount)

        searchButton.setOnClickListener {
            val show = searchBar.visibility != View.VISIBLE
            if (show) animateSearchBar(searchBar, true) { searchInput.requestFocus() }
            else animateSearchBar(searchBar, false)
        }
        findViewById<ImageButton>(R.id.searchClose).setOnClickListener { resetSearchUi() }
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

    private fun runSearch(query: String, countView: TextView) {
        val path = pdfFilePath ?: run {
            Toast.makeText(this, "Open a PDF to search", Toast.LENGTH_SHORT).show()
            return
        }
        if (query.isBlank()) return

        val useNative = NativePdfSearch.isSupported
        val index = if (useNative) null
        else pdfTextIndex ?: PdfTextIndex(File(path)).also { pdfTextIndex = it }

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
                                    focusMatch(0, countView)
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

    private fun resetSearchUi() {
        searchJob?.cancel()
        searchGen++
        animateSearchBar(findViewById(R.id.searchBar), false)
        searchMatches = emptyList(); searchPos = -1; setActiveHighlight(null)
        highlightsByPage.clear(); invalidateInk()
        findViewById<TextView>(R.id.searchCount).text = ""
        setSearchNavEnabled(false)
        findViewById<View>(R.id.searchProgressPill).visibility = View.GONE
    }

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

        content.measure(
            View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxPx = (320 * resources.displayMetrics.density).toInt()
        if (content.measuredHeight > maxPx) popup.height = maxPx

        popup.showAsDropDown(anchor, 0, (4 * resources.displayMetrics.density).toInt())
    }

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

    fun switchNotebookInPlace(target: Notebook): Boolean {
        if (target.documentType == DocumentType.INFINITE) return false

        persistLastPage()

        contentTools.dismissPopups()
        selectionPopup?.dismiss()
        drawingView.clearSelectionVisuals()
        drawingView.clearPendingTable()
        resetSearchUi()
        pdfTextIndex = null
        outlineLoadJob?.cancel()
        outlineCacheValue = null
        findViewById<View>(R.id.outlineLoadingPill).visibility = View.GONE
        findViewById<View>(R.id.zoomIndicator).visibility = View.GONE

        strokeManager.cancelSelection()
        strokeManager.loadDecodedData(emptyMap())
        historyManager.clear()
        updateUndoRedoButtons()

        notebookId = target.id
        if (notebookId >= 0) lifecycleScope.launch { dataManager.touchOpened(notebookId) }
        pdfFilePath = null
        currentPage = statePrefs.getInt("last_page_$notebookId", 0)
        totalPages = statePrefs.getInt("page_count_$notebookId", 1)
        savedDataLoaded = false
        strokesLoaded = false
        strokesDirty = false
        inkBaseWidth = 0f
        templateImageUriState.value = null

        pdfRecyclerView.resetZoom()

        loadPdf()
        return true
    }

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

        lifecycleScope.launch { engine.prefetchSizes() }

        val adapter = PdfPageAdapter(engine, lifecycleScope, strokeManager).apply {
            highlightsByPage = this@DrawingActivity.highlightsByPage
            searchFillPaint = this@DrawingActivity.searchFillPaint
            activeFillPaint = this@DrawingActivity.activeFillPaint
            zoom = pdfRecyclerView.zoom
            onFirstRender = {
                findViewById<View>(R.id.pdfLoadingOverlay).visibility = View.GONE
            }
        }
        pdfAdapter = adapter
        pdfRecyclerView.adapter = adapter
        drawingView.setPdfRecyclerView(pdfRecyclerView, adapter, engine)

        pdfRecyclerView.clearHiResTiles()
        pdfRecyclerView.hiResScope = lifecycleScope
        pdfRecyclerView.hiResRenderer = { page, lx, ly, z, childW, outW, outH ->
            engine.renderRegion(page, lx, ly, z, childW, outW, outH)
        }
        pdfRecyclerView.drawPageInk = { canvas, page, pageW, pageH ->
            strokeManager.drawPageStrokes(page, canvas, 1f, 1f)

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

            showAddPageTemplateDialog(path, insertIndex)
        }
    }

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

                val ctx = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.material3.MaterialTheme(
                    colorScheme = if (dark) androidx.compose.material3.dynamicDarkColorScheme(ctx)
                    else androidx.compose.material3.dynamicLightColorScheme(ctx)
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
                Toast.makeText(this@DrawingActivity, editErrorText(e, "Couldn't save the PDF. Please try again"), Toast.LENGTH_LONG).show()
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

    private fun editPdfSafely(file: File, action: (IncrementalPdfEditor) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            val editor = IncrementalPdfEditor.open(file)
            action(editor)
            editor.save(tmp)
            swapPdfIntoPlace(tmp, file)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun renderTemplateJpeg(tpl: PageTemplate, wPt: Float, hPt: Float): Triple<ByteArray, Int, Int> {
        val scale = 2
        val bmp = tpl.renderBitmap(this, (wPt * scale).toInt(), (hPt * scale).toInt())
        try {
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            return Triple(out.toByteArray(), bmp.width, bmp.height)
        } finally {
            bmp.recycle()
        }
    }

    private fun editErrorText(e: Exception, fallback: String): String =
        if (e is PdfEditException.Unsupported) "This PDF uses an unsupported format (encrypted) and can't be edited."
        else fallback

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

            if (!bak.renameTo(file)) {

                tmp.delete()
                throw java.io.IOException(
                    "Failed to move ${tmp.name} into place (and could not restore ${bak.name})"
                )
            }
            tmp.delete()
            throw java.io.IOException("Failed to move ${tmp.name} into place")
        }

    }

    private fun insertTemplatePageIntoPdf(file: File, insertIndex: Int, tpl: PageTemplate) {
        editPdfSafely(file) { editor ->
            val count = editor.pageCount
            val refIndex = if (insertIndex < count) insertIndex else count - 1
            val size = if (refIndex in 0 until count) editor.pageSize(refIndex) else 595f to 842f
            val jpeg = renderTemplateJpeg(tpl, size.first, size.second)
            editor.insertTemplatePage(insertIndex, size.first, size.second, jpeg.first, jpeg.second, jpeg.third)
        }
    }

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

                val ctx = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.material3.MaterialTheme(
                    colorScheme = if (dark) androidx.compose.material3.dynamicDarkColorScheme(ctx)
                    else androidx.compose.material3.dynamicLightColorScheme(ctx)
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
                    val editor = IncrementalPdfEditor.open(File(path))
                    val range = if (allPages) 0 until editor.pageCount else currentPage..currentPage
                    range.any { it in 0 until editor.pageCount && editor.pageHasFontResources(it) }
                } catch (e: Exception) { false }
            }
            val scope = if (allPages) "all $totalPages pages" else "this page"
            val message = buildString {
                append("Apply this template to $scope?")
                if (hasContent) append("\n\nSome of these pages contain original PDF content. The template will be drawn behind it.")
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
                    editErrorText(e, "Failed to apply template. The file may be closed by another operation.\nPlease try again."),
                    Toast.LENGTH_LONG
                ).show()

                loadPdf()
            }
        }
    }

    private fun applyTemplateToPdf(file: File, tpl: PageTemplate, allPages: Boolean, currentIndex: Int) {
        editPdfSafely(file) { editor ->
            val n = editor.pageCount
            val pages = (if (allPages) 0 until n else currentIndex..currentIndex).filter { it in 0 until n }
            if (pages.isEmpty()) return@editPdfSafely

            val prepend = pages.filter { editor.pageHasFontResources(it) }
            val append = pages.filterNot { it in prepend }
            val size = editor.pageSize(pages.first())
            val jpeg = renderTemplateJpeg(tpl, size.first, size.second)
            if (prepend.isNotEmpty()) {
                editor.applyTemplate(prepend, jpeg.first, jpeg.second, jpeg.third, prepend = true)
            }
            if (append.isNotEmpty()) {
                editor.applyTemplate(append, jpeg.first, jpeg.second, jpeg.third, prepend = false)
            }
        }
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

    private fun activeToolId(): String? = when (activeToolButton?.id) {
        R.id.penButton -> "PEN"
        R.id.eraserButton -> "ERASER"
        R.id.highlighterButton -> "HIGHLIGHTER"
        R.id.selectionButton -> "LASSO"
        R.id.shapeButton -> "SHAPE"
        R.id.tableButton -> "TABLE"
        R.id.laserButton -> "LASER"
        R.id.textButton -> "TEXT"
        R.id.imageButton -> "IMAGE"
        R.id.measureButton -> "MEASURE"
        R.id.tapeButton -> "TAPE"
        else -> null
    }

    private fun stylusActivateTool(toolId: String) {
        val buttonId = when (toolId) {
            "PEN" -> R.id.penButton
            "ERASER" -> R.id.eraserButton
            "HIGHLIGHTER" -> R.id.highlighterButton
            "LASSO" -> R.id.selectionButton
            "SHAPE" -> R.id.shapeButton
            "TABLE" -> R.id.tableButton
            "LASER" -> R.id.laserButton
            "TEXT" -> R.id.textButton
            "IMAGE" -> R.id.imageButton
            "MEASURE" -> R.id.measureButton
            "TAPE" -> R.id.tapeButton
            else -> return
        }
        val btn = findViewById<ImageButton>(buttonId) ?: return
        when (buttonId) {
            R.id.penButton -> { setActiveTool(btn); toolSettingsManager.applyPenSettings() }
            R.id.eraserButton -> { setActiveTool(btn); toolSettingsManager.applyEraserSettings() }
            R.id.highlighterButton -> { setActiveTool(btn); toolSettingsManager.applyHighlighterSettings() }
            R.id.selectionButton -> {
                setActiveTool(btn)
                drawingView.setTool(DrawingView.Tool.LASSO)
                drawingView.setDrawingMode(true)
            }
            R.id.shapeButton -> { setActiveTool(btn); applyShapeSettings() }
            R.id.tableButton -> { setActiveTool(btn); applyTableSettings() }
            R.id.laserButton -> { setActiveTool(btn); applyLaserSettings() }
            R.id.textButton -> { setActiveTool(btn); applyTextSettings() }
            R.id.imageButton -> {
                setActiveTool(btn)
                drawingView.setDrawingMode(false)
            }
            R.id.measureButton -> { setActiveTool(btn); applyMeasureSettings() }
            R.id.tapeButton -> { setActiveTool(btn); applyTapeSettings() }
        }
    }

    private fun setActiveTool(selectedButton: ImageButton) {
        val key = when (selectedButton.id) {
            R.id.penButton -> "PEN"
            R.id.eraserButton -> "ERASER"
            R.id.highlighterButton -> "HIGHLIGHTER"
            R.id.selectionButton -> "LASSO"
            R.id.shapeButton -> "SHAPE"
            R.id.tableButton -> "TABLE"
            R.id.laserButton -> "LASER"
            R.id.textButton -> "TEXT"
            R.id.imageButton -> "IMAGE"
            R.id.measureButton -> "MEASURE"
            R.id.tapeButton -> "TAPE"
            else -> return
        }

        stylusSwitcher.onToolActivated(key)
        activeToolButton?.isSelected = false
        selectedButton.isSelected = true
        activeToolButton = selectedButton

        toolOptionsHidden = !penPrefs().getBoolean("TOOL_OPTIONS_AUTO_SHOW", true)
        showActiveToolOptions()
        penPrefs().edit().putString("LAST_ACTIVE_TOOL", key).apply()

        restoreActiveToolDockState()
    }

    private var smallDock = false
    private var toolOptionsHidden = false

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
        findViewById<View>(R.id.shapePropsStrip).visibility =
            if (id == R.id.shapeButton) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tablePropsStrip).visibility =
            if (id == R.id.tableButton) View.VISIBLE else View.GONE
        findViewById<View>(R.id.laserPropsStrip).visibility =
            if (id == R.id.laserButton) View.VISIBLE else View.GONE
        findViewById<View>(R.id.textPropsStrip).visibility =
            if (id == R.id.textButton) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tapePropsStrip).visibility =
            if (id == R.id.tapeButton) View.VISIBLE else View.GONE
        findViewById<View>(R.id.imagePropsStrip).visibility =
            if (id == R.id.imageButton) View.VISIBLE else View.GONE
        if (id == R.id.imageButton) contentTools.refreshImageToolStrip()

        val optionsVisible = id != -1 && id != R.id.measureButton
        val scroll = if (smallDock) R.id.optionsScrollV else R.id.optionsScrollH
        findViewById<View>(scroll).visibility = if (optionsVisible) View.VISIBLE else View.GONE
        if (optionsVisible) capDockScrolls()
    }

    private fun configureDockPlacement() {

        findViewById<View>(R.id.drawingRootLayout)
            .viewTreeObserver.addOnGlobalLayoutListener { capDockScrolls() }

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

        toolDock.orientation = android.widget.LinearLayout.HORIZONTAL

        toolsScrollH.removeView(toolDockItems)
        toolsScrollV.addView(toolDockItems)
        toolDockItems.orientation = android.widget.LinearLayout.VERTICAL
        toolsScrollH.visibility = View.GONE
        toolsScrollV.visibility = View.VISIBLE

        optionsScrollH.removeView(optionsRow)
        optionsScrollV.addView(optionsRow)
        optionsRow.orientation = android.widget.LinearLayout.VERTICAL
        optionsRow.gravity = android.view.Gravity.CENTER_HORIZONTAL
        optionsScrollH.visibility = View.GONE
        listOf(R.id.penPropsStrip, R.id.eraserPropsStrip, R.id.highlighterPropsStrip, R.id.lassoPropsStrip, R.id.shapePropsStrip, R.id.tablePropsStrip, R.id.laserPropsStrip, R.id.imagePropsStrip)
            .forEach { makeStripVertical(findViewById(it)) }

        toolDock.removeView(optionsScrollV)
        toolDock.addView(optionsScrollV)

        val collapseBtn = findViewById<ImageButton>(R.id.dockCollapseButton)
        collapseBtn.visibility = View.VISIBLE
        collapseBtn.setOnClickListener { setDockCollapsed(!dockCollapsed) }

        val lp = toolDock.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val parent = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        lp.startToStart = parent
        lp.startToEnd = unset
        lp.endToStart = unset
        lp.endToEnd = unset
        lp.topToTop = parent
        lp.topToBottom = unset
        lp.bottomToBottom = parent
        toolDock.layoutParams = lp

        val searchBar = findViewById<View>(R.id.searchBar)
        val slp = searchBar.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        slp.topToBottom = R.id.topBarEnd
        searchBar.layoutParams = slp

        showActiveToolOptions()

        setDockCollapsed(penPrefs().getBoolean("DOCK_COLLAPSED", true))
    }

    private fun applyDockTransitions() {
        val duration = 200L
        val transition = android.animation.LayoutTransition().apply {
            setDuration(duration)
            setInterpolator(
                android.animation.LayoutTransition.CHANGING,
                android.view.animation.DecelerateInterpolator()
            )

            setAnimator(
                android.animation.LayoutTransition.APPEARING,
                android.animation.ObjectAnimator.ofFloat(null, "alpha", 0f, 1f).apply {
                    setDuration(duration)
                }
            )
            setAnimator(
                android.animation.LayoutTransition.DISAPPEARING,
                android.animation.ObjectAnimator.ofFloat(null, "alpha", 1f, 0f).apply {
                    setDuration(duration)
                }
            )
        }

        listOf(
            R.id.optionsScrollH,
            R.id.optionsScrollV,
            R.id.optionsRow
        ).forEach { id -> (findViewById<android.view.ViewGroup>(id)).layoutTransition = transition }
    }

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

                lp.width = (28 * density).toInt()
                lp.height = (1 * density).toInt().coerceAtLeast(1)
                lp.setMargins(0, (5 * density).toInt(), 0, (5 * density).toInt())
            } else {
                lp.setMargins(0, gap, 0, gap)
            }
            child.layoutParams = lp
        }
    }

    private fun capDockScrolls() {
        if (dockCollapsed) return
        val root = findViewById<View>(R.id.drawingRootLayout)
        root.post {
            if (root.height <= 0) return@post
            if (smallDock) {
                val cap = (root.height * 0.82f).toInt()
                if (cap <= 0) return@post
                listOf(R.id.optionsScrollV, R.id.toolsScrollV).forEach { capScrollHeight(it, cap) }
            } else {
                val dock = findViewById<android.widget.LinearLayout>(R.id.toolDock)
                if (dock.width <= 0) return@post
                capScrollWidth(R.id.optionsScrollH, dock.width)
            }
        }
    }

    private fun capScrollHeight(id: Int, cap: Int) {
        val sv = findViewById<android.view.ViewGroup>(id)
        if (sv.visibility != View.VISIBLE) return
        val child = sv.getChildAt(0) ?: return
        child.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val lp = sv.layoutParams
        val newHeight = if (child.measuredHeight > cap) cap
        else android.view.ViewGroup.LayoutParams.WRAP_CONTENT

        if (lp.height != newHeight) {
            lp.height = newHeight
            sv.layoutParams = lp
        }
    }

    private fun capScrollWidth(id: Int, dockWidth: Int) {
        val sv = findViewById<android.view.ViewGroup>(id)
        if (sv.visibility != View.VISIBLE) return
        val child = sv.getChildAt(0) ?: return
        child.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val lp = sv.layoutParams
        val newWidth = if (child.measuredWidth > dockWidth) dockWidth
        else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.width != newWidth) {
            lp.width = newWidth
            sv.layoutParams = lp
        }
    }

    private var dockCollapsed = false

    private fun setDockCollapsed(collapsed: Boolean) {
        dockCollapsed = collapsed
        penPrefs().edit().putBoolean("DOCK_COLLAPSED", collapsed).apply()
        findViewById<View>(R.id.toolsScrollV).visibility = if (collapsed) View.GONE else View.VISIBLE
        showActiveToolOptions()

        if (!collapsed && smallDock) capDockScrolls()

        val v = if (collapsed) View.GONE else View.VISIBLE
        findViewById<View>(R.id.undoButton).visibility = v
        findViewById<View>(R.id.redoButton).visibility = v
        findViewById<View>(R.id.addPageButton).visibility = v

        findViewById<ImageButton>(R.id.dockCollapseButton).setImageResource(
            if (collapsed) R.drawable.arrow_forward_ios_24px else R.drawable.arrow_back_ios_24px
        )
    }

    private fun toggleToolOptions() {
        toolOptionsHidden = !toolOptionsHidden
        showActiveToolOptions()
    }

    private fun penPrefs() = getSharedPreferences("OctopusNotesPrefs", MODE_PRIVATE)

    private fun setupPenDock() {
        val prefs = penPrefs()
        for (i in 0 until 5) dockColors[i] = prefs.getInt("DOCK_COLOR_$i", dockDefaultColors[i])
        dockActiveColorIndex = prefs.getInt("DOCK_COLOR_ACTIVE", 0).coerceIn(0, 4)

        setupDockSwatches()

        for (i in 0 until 3) dockThickness[i] = prefs.getFloat("DOCK_THICK_$i", dockThicknessDefaults[i])
        dockActiveThicknessIndex = prefs.getInt("DOCK_THICK_ACTIVE", -1)
        if (dockActiveThicknessIndex !in 0..2) {

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

        setupDockThickness()

        drawingView.setPenLineStyle(prefs.getString("DOCK_LINE_STYLE", PenLineStyle.SOLID) ?: PenLineStyle.SOLID)
        val lineBtn = findViewById<ImageButton>(R.id.lineStyleButton)
        lineBtn.setOnClickListener { showLineStylePopup(lineBtn) }
        val tableLineBtn = findViewById<ImageButton>(R.id.tableLineStyleButton)
        tableLineBtn.setOnClickListener { showLineStylePopup(tableLineBtn) }
        refreshDockLineStyleIcons()

        tableThickness = loadToolThickness("TABLE")
        tableActiveThicknessIndex = prefs.getInt("TABLE_THICK_ACTIVE", -1)
        tableLineStyle = prefs.getString("TABLE_LINE_STYLE", null)
        laserThickness = loadToolThickness("LASER")
        laserActiveThicknessIndex = prefs.getInt("LASER_THICK_ACTIVE", -1)

        toolSettingsManager.lastPenColorInt = dockColors[dockActiveColorIndex]
        updatePenIcon(dockColors[dockActiveColorIndex])
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
            "SHAPE" -> {
                setActiveTool(findViewById(R.id.shapeButton))
                applyShapeSettings()
            }
            "TABLE" -> {
                setActiveTool(findViewById(R.id.tableButton))
                applyTableSettings()
            }
            "LASER" -> {
                setActiveTool(findViewById(R.id.laserButton))
                applyLaserSettings()
            }
            "TEXT" -> {
                setActiveTool(findViewById(R.id.textButton))
                applyTextSettings()
            }
            "IMAGE" -> {
                setActiveTool(findViewById(R.id.imageButton))
                drawingView.setDrawingMode(false)
            }
            "MEASURE" -> {
                setActiveTool(findViewById(R.id.measureButton))
                applyMeasureSettings()
            }
            "TAPE" -> {
                setActiveTool(findViewById(R.id.tapeButton))
                applyTapeSettings()
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
        updatePenIcon(color)
    }

    private fun restoreActiveToolDockState() {
        val prefs = penPrefs()
        dockActiveColorIndex = prefs.getInt("DOCK_COLOR_ACTIVE", 0).coerceIn(0, 4)
        dockActiveThicknessIndex = toolActiveThicknessIndex()
        val style = when (activeToolButton?.id) {
            R.id.tableButton -> tableLineStyle
                ?: prefs.getString("DOCK_LINE_STYLE", PenLineStyle.SOLID)
            else -> prefs.getString("DOCK_LINE_STYLE", PenLineStyle.SOLID)
        }
        drawingView.setPenLineStyle(style ?: PenLineStyle.SOLID)

        drawingView.setTableLineStyle(style ?: PenLineStyle.SOLID, toolThicknessSize())
        refreshDockSwatches()
        refreshDockThicknessHighlight()
        refreshDockLineStyleIcons()
        updatePenIcon(dockColors[dockActiveColorIndex])
    }

    private fun toolThickness(): FloatArray = when (activeToolButton?.id) {
        R.id.tableButton -> tableThickness ?: dockThickness
        R.id.laserButton -> laserThickness ?: dockThickness
        else -> dockThickness
    }

    private fun toolActiveThicknessIndex(): Int = when (activeToolButton?.id) {
        R.id.tableButton -> if (tableThickness != null) tableActiveThicknessIndex
        else penPrefs().getInt("DOCK_THICK_ACTIVE", 2).coerceIn(0, 2)
        R.id.laserButton -> if (laserThickness != null) laserActiveThicknessIndex
        else penPrefs().getInt("DOCK_THICK_ACTIVE", 2).coerceIn(0, 2)
        else -> penPrefs().getInt("DOCK_THICK_ACTIVE", 2).coerceIn(0, 2)
    }

    private fun toolThicknessSize(): Float = toolThickness()[toolActiveThicknessIndex()]

    private fun loadToolThickness(prefix: String): FloatArray? {
        val p = penPrefs()
        val t0 = p.getFloat("${prefix}_THICK_0", -1f)
        if (t0 <= 0f) return null
        return floatArrayOf(
            t0,
            p.getFloat("${prefix}_THICK_1", 7f),
            p.getFloat("${prefix}_THICK_2", 10f)
        )
    }

    private fun ensureToolSizesSetup() {
        val isTable = activeToolButton?.id == R.id.tableButton
        val isLaser = activeToolButton?.id == R.id.laserButton
        if (!isTable && !isLaser) return
        val existing = if (isTable) tableThickness else laserThickness
        if (existing != null) return
        val copy = floatArrayOf(dockThickness[0], dockThickness[1], dockThickness[2])
        val active = dockActiveThicknessIndex.coerceIn(0, 2)
        if (isTable) {
            tableThickness = copy
            tableActiveThicknessIndex = active
        } else {
            laserThickness = copy
            laserActiveThicknessIndex = active
        }
        val prefix = if (isTable) "TABLE" else "LASER"
        penPrefs().edit()
            .putFloat("${prefix}_THICK_0", copy[0])
            .putFloat("${prefix}_THICK_1", copy[1])
            .putFloat("${prefix}_THICK_2", copy[2])
            .putInt("${prefix}_THICK_ACTIVE", active)
            .apply()
    }

    private fun selectToolThickness(i: Int) {
        val prefs = penPrefs()
        when (activeToolButton?.id) {
            R.id.tableButton -> {
                tableActiveThicknessIndex = i
                prefs.edit().putInt("TABLE_THICK_ACTIVE", i).apply()
            }
            R.id.laserButton -> {
                laserActiveThicknessIndex = i
                prefs.edit().putInt("LASER_THICK_ACTIVE", i).apply()
            }
            else -> prefs.edit().putInt("DOCK_THICK_ACTIVE", i).apply()
        }
        dockActiveThicknessIndex = i
    }

    private fun saveToolThicknessSlot(i: Int, v: Float) {
        val prefs = penPrefs()
        when (activeToolButton?.id) {
            R.id.tableButton -> prefs.edit().putFloat("TABLE_THICK_$i", v).apply()
            R.id.laserButton -> prefs.edit().putFloat("LASER_THICK_$i", v).apply()
            else -> prefs.edit().putFloat("DOCK_THICK_$i", v).apply()
        }
    }

    private fun recordActiveLineStyle(style: String) {
        if (activeToolButton?.id == R.id.tableButton) {
            tableLineStyle = style
            penPrefs().edit().putString("TABLE_LINE_STYLE", style).apply()
        } else {
            penPrefs().edit().putString("DOCK_LINE_STYLE", style).apply()
        }
    }

    private fun updatePenIcon(color: Int) {
        val btn = findViewById<ImageButton>(R.id.penButton)
        val res = btn.resources
        val theme = btn.context.theme
        val outlineColor = com.google.android.material.color.MaterialColors.getColor(
            btn, com.google.android.material.R.attr.colorOutline, Color.parseColor("#9E9E9E")
        )
        fun freshPen() =
            res.getDrawable(R.drawable.ic_pen, theme).constantState!!.newDrawable(res, theme).mutate()
        val outline = freshPen().apply {
            setColorFilter(outlineColor, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        val fill = freshPen().apply {
            setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        val rim = (2 * res.displayMetrics.density).toInt()
        btn.setImageDrawable(
            android.graphics.drawable.LayerDrawable(arrayOf(outline, fill)).apply {
                setLayerInset(1, rim, rim, rim, rim)
            }
        )
    }

    private fun applyDockThickness(size: Float) {
        drawingView.setBrushSize(size)

        drawingView.setTableLineStyle(drawingView.penLineStyle, size)

        if (activeToolButton?.id != R.id.tableButton && activeToolButton?.id != R.id.laserButton) {
            toolSettingsManager.lastPenSize = size
            penPrefs().edit().putFloat("PEN_SIZE", size).apply()
        }
    }

    private val dockThicknessButtons: List<ImageButton> by lazy {
        listOf(
            findViewById<ImageButton>(R.id.thinButton), findViewById<ImageButton>(R.id.mediumButton), findViewById<ImageButton>(R.id.thickButton),
            findViewById<ImageButton>(R.id.tableThinButton), findViewById<ImageButton>(R.id.tableMediumButton), findViewById<ImageButton>(R.id.tableThickButton),
            findViewById<ImageButton>(R.id.laserThinButton), findViewById<ImageButton>(R.id.laserMediumButton), findViewById<ImageButton>(R.id.laserThickButton)
        )
    }

    private fun refreshDockThicknessHighlight() {
        val arr = toolThickness()
        val activeIdx = toolActiveThicknessIndex()
        val max = maxOf(arr[0], arr[1], arr[2])
        dockThicknessButtons.forEachIndexed { index, b ->
            val i = index % 3
            b.isSelected = activeIdx == i
            applySizeIcon(b, arr[i], max)
        }
    }

    private fun setupDockThickness() {
        dockThicknessButtons.forEachIndexed { index, b ->
            val i = index % 3
            b.setOnClickListener {
                if (toolActiveThicknessIndex() == i) {
                    showSizeSliderPopup(b, toolThickness()[i], 1f, 40f) { v ->
                        ensureToolSizesSetup()
                        toolThickness()[i] = v
                        saveToolThicknessSlot(i, v)
                        applyDockThickness(v)
                        refreshDockThicknessHighlight()
                    }
                } else {
                    ensureToolSizesSetup()
                    selectToolThickness(i)
                    applyDockThickness(toolThickness()[i])
                    refreshDockThicknessHighlight()
                }
            }
        }
        refreshDockThicknessHighlight()
    }

    private val dockSwatchViews: List<View> by lazy {
        listOf(
            findViewById<View>(R.id.colorSwatch0), findViewById<View>(R.id.colorSwatch1),
            findViewById<View>(R.id.colorSwatch2), findViewById<View>(R.id.colorSwatch3),
            findViewById<View>(R.id.colorSwatch4),
            findViewById<View>(R.id.tableColorSwatch0), findViewById<View>(R.id.tableColorSwatch1),
            findViewById<View>(R.id.tableColorSwatch2), findViewById<View>(R.id.tableColorSwatch3),
            findViewById<View>(R.id.tableColorSwatch4),
            findViewById<View>(R.id.laserColorSwatch0), findViewById<View>(R.id.laserColorSwatch1),
            findViewById<View>(R.id.laserColorSwatch2), findViewById<View>(R.id.laserColorSwatch3),
            findViewById<View>(R.id.laserColorSwatch4),
            findViewById<View>(R.id.tapeColorSwatch0), findViewById<View>(R.id.tapeColorSwatch1),
            findViewById<View>(R.id.tapeColorSwatch2), findViewById<View>(R.id.tapeColorSwatch3),
            findViewById<View>(R.id.tapeColorSwatch4)
        )
    }

    private fun refreshDockSwatches() {
        dockSwatchViews.forEachIndexed { index, v ->
            val i = index % 5
            updateDockSwatch(v, dockColors[i], i == dockActiveColorIndex)
        }
    }

    private fun setupDockSwatches() {
        dockSwatchViews.forEachIndexed { index, v ->
            val i = index % 5
            v.setOnClickListener {
                if (dockActiveColorIndex == i) {

                    openDockColorPicker(dockColors[i]) { picked ->
                        dockColors[i] = picked
                        penPrefs().edit().putInt("DOCK_COLOR_$i", picked).apply()
                        applyDockColor(picked)
                        refreshDockSwatches()
                    }
                } else {
                    dockActiveColorIndex = i
                    penPrefs().edit().putInt("DOCK_COLOR_ACTIVE", i).apply()
                    applyDockColor(dockColors[i])
                    refreshDockSwatches()
                }
            }
        }
        refreshDockSwatches()
    }

    private fun refreshDockLineStyleIcons() {
        updateLineStyleIcon(findViewById(R.id.lineStyleButton))
        updateLineStyleIcon(findViewById(R.id.tableLineStyleButton))
    }

    private fun applySizeIcon(btn: ImageButton, size: Float, maxSize: Float) {
        val density = resources.displayMetrics.density
        val iconPx = (24 * density).toInt().coerceAtLeast(4)
        val frac = if (maxSize > 0f) (size / maxSize).coerceIn(0f, 1f) else 1f

        val diameter = maxOf(frac * iconPx * 0.9f, 3f)

        val bmp = android.graphics.Bitmap.createBitmap(iconPx, iconPx, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.density = resources.displayMetrics.densityDpi
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        val radius = diameter / 2f
        val h = iconPx.toFloat()
        c.drawCircle(h / 2f, h / 2f, radius, p)
        btn.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp))
    }

    private fun updateDockSwatch(view: View, color: Int, selected: Boolean) {
        val bg = view.background as android.graphics.drawable.LayerDrawable
        (bg.findDrawableByLayerId(R.id.color_shape) as android.graphics.drawable.GradientDrawable).setColor(color)
        val stroke = bg.findDrawableByLayerId(R.id.swatch_border) as android.graphics.drawable.GradientDrawable
        if (selected) {

            val primary = com.google.android.material.color.MaterialColors.getColor(
                view, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#2196F3")
            )
            stroke.setStroke(8, androidx.core.graphics.ColorUtils.blendARGB(primary, Color.BLACK, 0.3f))
        } else stroke.setStroke(2, Color.parseColor("#BDBDBD"))
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
            recordActiveLineStyle(style)

            drawingView.setTableLineStyle(style, toolThicknessSize())
            refreshDockLineStyleIcons()
            popup.dismiss()
        }
        view.findViewById<View>(R.id.styleSolid).setOnClickListener { choose(PenLineStyle.SOLID) }
        view.findViewById<View>(R.id.styleDotted).setOnClickListener { choose(PenLineStyle.DOTTED) }
        view.findViewById<View>(R.id.styleDashed).setOnClickListener { choose(PenLineStyle.DASHED) }

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

        popup.showAsDropDown(anchor, 0, 8)
    }

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
                        refreshEraserSizeHighlight()
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
        val max = maxOf(eraserSizes[0], eraserSizes[1], eraserSizes[2])
        val btns = listOf(
            findViewById<ImageButton>(R.id.eraserSize0),
            findViewById<ImageButton>(R.id.eraserSize1),
            findViewById<ImageButton>(R.id.eraserSize2)
        )
        btns.forEachIndexed { i, b ->
            b.isSelected = eraserActiveSizeIndex == i
            applySizeIcon(b, eraserSizes[i], max)
        }
    }

    private val hlSizeDefaults = floatArrayOf(20f, 30f, 45f)
    private val hlSizes = FloatArray(3)
    private var hlActiveSizeIndex = 1

    private val hlDefaultColors = intArrayOf(
        Color.parseColor("#66FFEB00"),
        Color.parseColor("#6600E676"),
        Color.parseColor("#66FF4081"),
        Color.parseColor("#6640C4FF"),
        Color.parseColor("#66FF9100")
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

                    openDockColorPicker(toolSettingsManager.getHighlighterColor()) { picked ->
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
                        refreshHlSizeHighlight()
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
        val max = maxOf(hlSizes[0], hlSizes[1], hlSizes[2])
        val btns = listOf(
            findViewById<ImageButton>(R.id.hlSize0),
            findViewById<ImageButton>(R.id.hlSize1),
            findViewById<ImageButton>(R.id.hlSize2)
        )
        btns.forEachIndexed { i, b ->
            b.isSelected = hlActiveSizeIndex == i
            applySizeIcon(b, hlSizes[i], max)
        }
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

    private fun setupShapeDock() {
        val saved = penPrefs().getString("DOCK_SHAPE_TYPE", ShapeType.RECT) ?: ShapeType.RECT
        drawingView.setShapeType(saved)

        val rect = findViewById<ImageButton>(R.id.shapeRectButton)
        val oval = findViewById<ImageButton>(R.id.shapeOvalButton)
        val triangle = findViewById<ImageButton>(R.id.shapeTriangleButton)
        val line = findViewById<ImageButton>(R.id.shapeLineButton)
        val arrow = findViewById<ImageButton>(R.id.shapeArrowButton)
        fun refresh() {
            val s = drawingView.shapeType
            rect.isSelected = s == ShapeType.RECT
            oval.isSelected = s == ShapeType.OVAL
            triangle.isSelected = s == ShapeType.TRIANGLE
            line.isSelected = s == ShapeType.LINE
            arrow.isSelected = s == ShapeType.ARROW
        }
        fun select(type: String) {
            drawingView.setShapeType(type)
            penPrefs().edit().putString("DOCK_SHAPE_TYPE", type).apply()
            refresh()
        }
        rect.setOnClickListener { select(ShapeType.RECT) }
        oval.setOnClickListener { select(ShapeType.OVAL) }
        triangle.setOnClickListener { select(ShapeType.TRIANGLE) }
        line.setOnClickListener { select(ShapeType.LINE) }
        arrow.setOnClickListener { select(ShapeType.ARROW) }
        findViewById<View>(R.id.shapeSizeButton).setOnClickListener { showShapeSizeDialog() }
        refresh()
    }

    private fun applyShapeSettings() {
        drawingView.setTool(DrawingView.Tool.SHAPE)
        applyDockColor(dockColors[dockActiveColorIndex])
        applyDockThickness(dockThickness[dockActiveThicknessIndex])
        drawingView.setShapeType(
            penPrefs().getString("DOCK_SHAPE_TYPE", ShapeType.RECT) ?: ShapeType.RECT
        )
        drawingView.setDrawingMode(true)
    }

    private fun applyTableSettings() {
        drawingView.setTool(DrawingView.Tool.TABLE)
        applyDockColor(dockColors[dockActiveColorIndex])
        applyDockThickness(toolThicknessSize())
        val (r, c) = contentTools.tableRowsColsPref()
        drawingView.tableGridRows = r
        drawingView.tableGridCols = c
        drawingView.setDrawingMode(true)
    }

    private fun applyLaserSettings() {
        drawingView.setTool(DrawingView.Tool.LASER)
        applyDockColor(dockColors[dockActiveColorIndex])
        applyDockThickness(toolThicknessSize())
        drawingView.setDrawingMode(true)
    }

    private val TEXT_SIZES = floatArrayOf(18f, 26f, 36f)
    private val TEXT_SIZE_IDS = intArrayOf(R.id.textSizeSmall, R.id.textSizeMedium, R.id.textSizeLarge)

    private fun applyTextSettings() {
        drawingView.setTool(DrawingView.Tool.TEXT)
        applyDockColor(dockColors[dockActiveColorIndex])
        drawingView.setDrawingMode(true)
    }

    private fun applyMeasureSettings() {
        drawingView.setTool(DrawingView.Tool.MEASURE)
        drawingView.setDrawingMode(true)

        if (drawingView.isSelectionActive) {
            strokeManager.cancelSelection()
            drawingView.clearSelectionVisuals()
            selectionPopup?.dismiss()
        }
    }

    private fun applyTapeSettings() {
        drawingView.setTool(DrawingView.Tool.TAPE)
        applyDockColor(dockColors[dockActiveColorIndex])
        drawingView.setDrawingMode(true)
        contentTools.applyTapePrefs()
    }

    private fun setupTextDock() {
        val pref = contentTools.textSizePref()
        for (i in TEXT_SIZES.indices) {
            val btn = findViewById<TextView>(TEXT_SIZE_IDS[i])
            btn.isSelected = pref == TEXT_SIZES[i]
            btn.setOnClickListener {
                penPrefs().edit().putFloat("TEXT_SIZE", TEXT_SIZES[i]).apply()
                for (j in TEXT_SIZES.indices) {
                    findViewById<TextView>(TEXT_SIZE_IDS[j]).isSelected = i == j
                }
            }
        }
    }

    private var structurePopup: android.widget.PopupWindow? = null
    private var editingStructPage = -1
    private var editingStructStroke: StrokeData? = null
    private var editingStructRow = 0
    private var editingStructCol = 0

    private val TABLE_ROW_RESIZE_STEP = 10f
    private val TABLE_COL_RESIZE_STEP = 10f
    private val TABLE_MIN_CELL = 8f

    private fun openTableStructurePopup(pageIndex: Int, table: StrokeData, row: Int, col: Int) {
        structurePopup?.dismiss()
        editingStructPage = pageIndex
        editingStructStroke = table
        editingStructRow = row
        editingStructCol = col
        val view = layoutInflater.inflate(R.layout.popup_table_structure, null)
        val rowLabel = view.findViewById<TextView>(R.id.tableRowSizeLabel)
        val colLabel = view.findViewById<TextView>(R.id.tableColSizeLabel)
        val rowValue = view.findViewById<TextView>(R.id.tableRowSizeValue)
        val colValue = view.findViewById<TextView>(R.id.tableColSizeValue)
        val preview = view.findViewById<TableGridPreviewView>(R.id.tableStructurePreview)

        fun refresh() {
            val cur = editingStructStroke ?: return
            val td = cur.tableData ?: return
            val b = RectF()
            cur.path.computeBounds(b, true)
            val rows = td.rows.coerceAtLeast(1)
            val cols = td.cols.coerceAtLeast(1)
            val rowH = td.rowHeights(b.height())
            val colW = td.colWidths(b.width())
            val r = editingStructRow.coerceIn(0, rows - 1)
            val c = editingStructCol.coerceIn(0, cols - 1)
            rowLabel.text = getString(R.string.table_row_size, r + 1, rows)
            colLabel.text = getString(R.string.table_col_size, c + 1, cols)
            rowValue.text = getString(R.string.table_size_px, rowH[r].toInt())
            colValue.text = getString(R.string.table_size_px, colW[c].toInt())
            preview.setGrid(
                rows, cols, td.headerRow, td.headerCol,
                cur.lineStyle ?: PenLineStyle.SOLID, td.rowWeights, td.colWeights
            )
        }

        view.findViewById<View>(R.id.tableRowMinus).setOnClickListener { resizeTableRow(-TABLE_ROW_RESIZE_STEP); refresh() }
        view.findViewById<View>(R.id.tableRowPlus).setOnClickListener { resizeTableRow(TABLE_ROW_RESIZE_STEP); refresh() }
        view.findViewById<View>(R.id.tableColMinus).setOnClickListener { resizeTableCol(-TABLE_COL_RESIZE_STEP); refresh() }
        view.findViewById<View>(R.id.tableColPlus).setOnClickListener { resizeTableCol(TABLE_COL_RESIZE_STEP); refresh() }
        view.findViewById<View>(R.id.tableAddRowAbove).setOnClickListener { insertTableRow(false); refresh() }
        view.findViewById<View>(R.id.tableAddRowBelow).setOnClickListener { insertTableRow(true); refresh() }
        view.findViewById<View>(R.id.tableAddColLeft).setOnClickListener { insertTableCol(false); refresh() }
        view.findViewById<View>(R.id.tableAddColRight).setOnClickListener { insertTableCol(true); refresh() }
        view.findViewById<View>(R.id.tableDeleteRow).setOnClickListener { deleteTableRow(); refresh() }
        view.findViewById<View>(R.id.tableDeleteCol).setOnClickListener { deleteTableCol(); refresh() }
        view.findViewById<View>(R.id.tableMergeRight).setOnClickListener { mergeTableCells(false); refresh() }
        view.findViewById<View>(R.id.tableMergeDown).setOnClickListener { mergeTableCells(true); refresh() }
        view.findViewById<View>(R.id.tableSplitCell).setOnClickListener { splitTableCell(); refresh() }

        structurePopup = android.widget.PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            elevation = 20f
            setBackgroundDrawable(
                androidx.core.content.ContextCompat.getDrawable(this@DrawingActivity, R.drawable.bg_popup_menu)
            )
            setOnDismissListener {
                editingStructPage = -1
                editingStructStroke = null
            }
        }

        val rv = pdfRecyclerView
        val zoom = rv.zoom
        var ox = 0f
        var oy = 0f
        var found = false
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) ?: continue
            if (rv.getChildAdapterPosition(c) == pageIndex) { ox = c.left.toFloat(); oy = c.top.toFloat(); found = true; break }
        }
        if (!found) {
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i) ?: continue
                if (rv.getChildAdapterPosition(c) >= 0) { ox = c.left.toFloat(); oy = c.top.toFloat(); found = true; break }
            }
        }
        if (found) {
            val b = RectF()
            table.path.computeBounds(b, true)
            val td = table.tableData ?: TableData(1, 1)
            val rowY = td.rowBoundaries(b.height())
            val colX = td.colBoundaries(b.width())
            val span = td.merges["$row,$col"]
            val c1 = if (span != null && span.size >= 2 && span[1] > 1) (col + span[1]).coerceAtMost(td.cols) else col + 1
            val r1 = if (span != null && span.size >= 2 && span[0] > 1) (row + span[0]).coerceAtMost(td.rows) else row + 1
            val cell = RectF(
                b.left + colX[col], b.top + rowY[row],
                b.left + colX[c1], b.top + rowY[r1]
            )
            val toScreen = Matrix().apply {
                postTranslate(ox, oy)
                postScale(zoom, zoom)
                postTranslate(rv.transX, rv.transY)
            }
            val screen = RectF(cell)
            toScreen.mapRect(screen)
            view.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
            val screenW = resources.displayMetrics.widthPixels
            val x = (screen.centerX() - view.measuredWidth / 2f).toInt()
                .coerceIn(8, (screenW - view.measuredWidth - 8).coerceAtLeast(8))
            val y = (screen.top - view.measuredHeight - 12 * resources.displayMetrics.density).coerceAtLeast(8f).toInt()
            structurePopup?.showAtLocation(rv, android.view.Gravity.NO_GRAVITY, x, y)
        } else {
            editingStructPage = -1
            editingStructStroke = null
            structurePopup?.dismiss()
            return
        }
        refresh()
    }

    private fun commitTableStructure(newTd: TableData, newBounds: RectF) {
        val page = editingStructPage
        val table = editingStructStroke ?: return
        if (page < 0) return
        val newPath = Path().apply { addRect(newBounds, Path.Direction.CW) }
        val newStroke = table.copy(path = newPath, tableData = newTd)
        historyManager.execute(DrawingAction.ReplaceStrokes(page, listOf(table), listOf(newStroke)), strokeManager)
        strokesDirty = true
        invalidateInk()
        updateUndoRedoButtons()
        editingStructStroke = newStroke

        if (strokeManager.activeSelectionPageIndex == page &&
            strokeManager.activeSelectionStrokes.any { it.type == StrokeType.TABLE && it.id == table.id }
        ) {
            strokeManager.selectStrokes(page, listOf(newStroke))
            drawingView.updateSelectionVisuals(strokeManager.activeSelectionStrokes)
        }
    }

    private fun resizeTableRow(delta: Float) {
        val table = editingStructStroke ?: return
        val td = table.tableData ?: return
        if (editingStructPage < 0 || td.rows < 1) return
        val b = RectF().apply { table.path.computeBounds(this, true) }
        val r = editingStructRow.coerceIn(0, td.rows - 1)
        val rowH = td.rowHeights(b.height())
        val newH = (rowH[r] + delta).coerceAtLeast(TABLE_MIN_CELL)
        if (newH == rowH[r]) return
        val newHeight = (b.height() + newH - rowH[r]).coerceAtLeast(TABLE_MIN_CELL * td.rows)
        val weights = FloatArray(td.rows) { i -> (if (i == r) newH else rowH[i]) / newHeight }
        commitTableStructure(td.copy(rowWeights = weights), RectF(b.left, b.top, b.right, b.top + newHeight))
    }

    private fun resizeTableCol(delta: Float) {
        val table = editingStructStroke ?: return
        val td = table.tableData ?: return
        if (editingStructPage < 0 || td.cols < 1) return
        val b = RectF().apply { table.path.computeBounds(this, true) }
        val c = editingStructCol.coerceIn(0, td.cols - 1)
        val colW = td.colWidths(b.width())
        val newW = (colW[c] + delta).coerceAtLeast(TABLE_MIN_CELL)
        if (newW == colW[c]) return
        val newWidth = (b.width() + newW - colW[c]).coerceAtLeast(TABLE_MIN_CELL * td.cols)
        val weights = FloatArray(td.cols) { i -> (if (i == c) newW else colW[i]) / newWidth }
        commitTableStructure(td.copy(colWeights = weights), RectF(b.left, b.top, b.left + newWidth, b.bottom))
    }

    private fun insertTableRow(below: Boolean) {
        val table = editingStructStroke ?: return
        val td = table.tableData ?: return
        if (editingStructPage < 0 || td.rows < 1) return
        val b = RectF().apply { table.path.computeBounds(this, true) }
        val r = editingStructRow.coerceIn(0, td.rows - 1)
        val insertAt = if (below) r + 1 else r
        val rowH = td.rowHeights(b.height())
        val newRowH = rowH[r]
        val newHeight = b.height() + newRowH
        val weights = FloatArray(td.rows + 1) { i ->
            when {
                i < insertAt -> rowH[i] / newHeight
                i == insertAt -> newRowH / newHeight
                else -> rowH[i - 1] / newHeight
            }
        }
        val newCells = shiftCells(td.cells, rowShift = { i -> if (i >= insertAt) i + 1 else i })
        val newMerges = shiftMergesRow(td.merges, insertAt)
        commitTableStructure(
            td.copy(rows = td.rows + 1, rowWeights = weights, cells = newCells, merges = newMerges),
            RectF(b.left, b.top, b.right, b.top + newHeight)
        )

        if (insertAt <= r) editingStructRow = r + 1
    }

    private fun insertTableCol(right: Boolean) {
        val table = editingStructStroke ?: return
        val td = table.tableData ?: return
        if (editingStructPage < 0 || td.cols < 1) return
        val b = RectF().apply { table.path.computeBounds(this, true) }
        val c = editingStructCol.coerceIn(0, td.cols - 1)
        val insertAt = if (right) c + 1 else c
        val colW = td.colWidths(b.width())
        val newColW = colW[c]
        val newWidth = b.width() + newColW
        val weights = FloatArray(td.cols + 1) { i ->
            when {
                i < insertAt -> colW[i] / newWidth
                i == insertAt -> newColW / newWidth
                else -> colW[i - 1] / newWidth
            }
        }
        val newCells = shiftCells(td.cells, colShift = { i -> if (i >= insertAt) i + 1 else i })
        val newMerges = shiftMergesCol(td.merges, insertAt)
        commitTableStructure(
            td.copy(cols = td.cols + 1, colWeights = weights, cells = newCells, merges = newMerges),
            RectF(b.left, b.top, b.left + newWidth, b.bottom)
        )
        if (insertAt <= c) editingStructCol = c + 1
    }

    private fun deleteTableRow() {
        val table = editingStructStroke ?: return
        val td = table.tableData ?: return
        if (editingStructPage < 0 || td.rows <= 1) return
        val b = RectF().apply { table.path.computeBounds(this, true) }
        val r = editingStructRow.coerceIn(0, td.rows - 1)
        val rowH = td.rowHeights(b.height())
        val newHeight = (b.height() - rowH[r]).coerceAtLeast(TABLE_MIN_CELL)
        val weights = FloatArray(td.rows - 1) { i -> rowH[if (i < r) i else i + 1] / newHeight }

        val orphanText = HashMap<String, String>()
        for ((k, span) in td.merges) {
            if (span.size < 2) continue
            val parts = k.split(",")
            val ar = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val ac = parts.getOrNull(1)?.toIntOrNull() ?: continue
            val rs = span[0].coerceAtLeast(1)
            if (ar == r && r in ar until ar + rs) {
                td.cells[k]?.takeIf { it.isNotBlank() }?.let { orphanText["${r + 1},$ac"] = it }
            }
        }
        val newCells = HashMap<String, String>()
        for ((k, v) in td.cells) {
            val parts = k.split(",")
            val cr = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val cc = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (cr == r) continue
            newCells["${if (cr > r) cr - 1 else cr},$cc"] = v
        }
        for ((k, v) in orphanText) newCells[k] = v

        val newMerges = HashMap<String, IntArray>()
        for ((k, span) in td.merges) {
            if (span.size < 2) continue
            val parts = k.split(",")
            val ar = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val ac = parts.getOrNull(1)?.toIntOrNull() ?: continue
            val rs = span[0].coerceAtLeast(1)
            val cs = span[1].coerceAtLeast(1)
            if (r in ar until ar + rs) continue
            newMerges["${if (ar > r) ar - 1 else ar},$ac"] = intArrayOf(rs, cs)
        }
        commitTableStructure(
            td.copy(rows = td.rows - 1, rowWeights = weights, cells = newCells, merges = newMerges),
            RectF(b.left, b.top, b.right, b.top + newHeight)
        )
        editingStructRow = if (r < editingStructRow) editingStructRow - 1 else editingStructRow.coerceAtMost(td.rows - 2)
    }

    private fun deleteTableCol() {
        val table = editingStructStroke ?: return
        val td = table.tableData ?: return
        if (editingStructPage < 0 || td.cols <= 1) return
        val b = RectF().apply { table.path.computeBounds(this, true) }
        val c = editingStructCol.coerceIn(0, td.cols - 1)
        val colW = td.colWidths(b.width())
        val newWidth = (b.width() - colW[c]).coerceAtLeast(TABLE_MIN_CELL)
        val weights = FloatArray(td.cols - 1) { i -> colW[if (i < c) i else i + 1] / newWidth }

        val orphanText = HashMap<String, String>()
        for ((k, span) in td.merges) {
            if (span.size < 2) continue
            val parts = k.split(",")
            val ar = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val ac = parts.getOrNull(1)?.toIntOrNull() ?: continue
            val cs = span[1].coerceAtLeast(1)
            if (ac == c && c in ac until ac + cs) {
                td.cells[k]?.takeIf { it.isNotBlank() }?.let { orphanText["$ar,${c + 1}"] = it }
            }
        }
        val newCells = HashMap<String, String>()
        for ((k, v) in td.cells) {
            val parts = k.split(",")
            val cr = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val cc = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (cc == c) continue
            newCells["$cr,${if (cc > c) cc - 1 else cc}"] = v
        }
        for ((k, v) in orphanText) newCells[k] = v

        val newMerges = HashMap<String, IntArray>()
        for ((k, span) in td.merges) {
            if (span.size < 2) continue
            val parts = k.split(",")
            val ar = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val ac = parts.getOrNull(1)?.toIntOrNull() ?: continue
            val rs = span[0].coerceAtLeast(1)
            val cs = span[1].coerceAtLeast(1)
            if (c in ac until ac + cs) continue
            newMerges["$ar,${if (ac > c) ac - 1 else ac}"] = intArrayOf(rs, cs)
        }
        commitTableStructure(
            td.copy(cols = td.cols - 1, colWeights = weights, cells = newCells, merges = newMerges),
            RectF(b.left, b.top, b.left + newWidth, b.bottom)
        )
        editingStructCol = if (c < editingStructCol) editingStructCol - 1 else editingStructCol.coerceAtMost(td.cols - 2)
    }

    private fun mergeTableCells(vertical: Boolean) {
        val table = editingStructStroke ?: return
        val td = table.tableData ?: return
        if (editingStructPage < 0) return
        val b = RectF().apply { table.path.computeBounds(this, true) }
        val r = editingStructRow.coerceIn(0, td.rows - 1)
        val c = editingStructCol.coerceIn(0, td.cols - 1)
        val merges = HashMap(td.merges)
        val anchor = td.mergeAnchor(r, c)
        val ar = anchor?.first ?: r
        val ac = anchor?.second ?: c
        val span = merges["$ar,$ac"] ?: intArrayOf(1, 1)
        val rs = span[0].coerceAtLeast(1)
        val cs = span[1].coerceAtLeast(1)
        if (vertical) {
            if (ar + rs >= td.rows) return
            merges["$ar,$ac"] = intArrayOf(rs + 1, cs)
            commitTableStructure(
                td.copy(merges = merges, cells = absorbMergedText(td, ar, ac, rs + 1, cs)),
                b
            )
        } else {
            if (ac + cs >= td.cols) return
            merges["$ar,$ac"] = intArrayOf(rs, cs + 1)
            commitTableStructure(
                td.copy(merges = merges, cells = absorbMergedText(td, ar, ac, rs, cs + 1)),
                b
            )
        }
    }

    private fun splitTableCell() {
        val table = editingStructStroke ?: return
        val td = table.tableData ?: return
        if (editingStructPage < 0) return
        val b = RectF().apply { table.path.computeBounds(this, true) }
        val r = editingStructRow.coerceIn(0, td.rows - 1)
        val c = editingStructCol.coerceIn(0, td.cols - 1)
        val anchor = td.mergeAnchor(r, c) ?: return
        val merges = HashMap(td.merges)
        merges.remove("${anchor.first},${anchor.second}")
        commitTableStructure(td.copy(merges = merges), b)
    }

    private fun absorbMergedText(td: TableData, ar: Int, ac: Int, rs: Int, cs: Int): MutableMap<String, String> {
        val anchorKey = "$ar,$ac"
        val parts = mutableListOf<String>()
        val keep = HashMap<String, String>()
        for ((k, v) in td.cells) {
            val ps = k.split(",")
            val kr = ps.getOrNull(0)?.toIntOrNull() ?: continue
            val kc = ps.getOrNull(1)?.toIntOrNull() ?: continue
            if (kr in ar until ar + rs && kc in ac until ac + cs) {
                if (v.isNotBlank()) parts += v
            } else keep[k] = v
        }
        if (parts.isNotEmpty()) keep[anchorKey] = parts.joinToString("\n")
        return keep
    }

    private fun shiftCells(
        cells: Map<String, String>,
        rowShift: (Int) -> Int = { it },
        colShift: (Int) -> Int = { it }
    ): HashMap<String, String> {
        val out = HashMap<String, String>()
        for ((k, v) in cells) {
            val ps = k.split(",")
            val r = ps.getOrNull(0)?.toIntOrNull() ?: continue
            val c = ps.getOrNull(1)?.toIntOrNull() ?: continue
            out["${rowShift(r)},${colShift(c)}"] = v
        }
        return out
    }

    private fun shiftMergesRow(merges: MutableMap<String, IntArray>, insertAt: Int): MutableMap<String, IntArray> {
        val out = HashMap<String, IntArray>()
        for ((k, span) in merges) {
            if (span.size < 2) continue
            val parts = k.split(",")
            val ar = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val ac = parts.getOrNull(1)?.toIntOrNull() ?: continue
            val rs = span[0].coerceAtLeast(1)
            val cs = span[1].coerceAtLeast(1)
            if (insertAt > ar && insertAt < ar + rs) {
                out[k] = intArrayOf(rs + 1, cs)
            } else {
                out["${if (insertAt <= ar) ar + 1 else ar},$ac"] = intArrayOf(rs, cs)
            }
        }
        return out
    }

    private fun shiftMergesCol(merges: MutableMap<String, IntArray>, insertAt: Int): MutableMap<String, IntArray> {
        val out = HashMap<String, IntArray>()
        for ((k, span) in merges) {
            if (span.size < 2) continue
            val parts = k.split(",")
            val ar = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val ac = parts.getOrNull(1)?.toIntOrNull() ?: continue
            val rs = span[0].coerceAtLeast(1)
            val cs = span[1].coerceAtLeast(1)
            if (insertAt > ac && insertAt < ac + cs) {
                out[k] = intArrayOf(rs, cs + 1)
            } else {
                out["$ar,${if (insertAt <= ac) ac + 1 else ac}"] = intArrayOf(rs, cs)
            }
        }
        return out
    }

    private fun currentPageChildForCenter(): Pair<Int, View>? {
        val rv = pdfRecyclerView
        val contentCenterY = (rv.height / 2f - rv.transY) / rv.zoom
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(c)
            if (pos < 0) continue
            if (contentCenterY >= c.top && contentCenterY < c.bottom) return pos to c
        }
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(c)
            if (pos >= 0) return pos to c
        }
        return null
    }

    private fun showShapeSizeDialog() {
        val (_, child) = currentPageChildForCenter() ?: return
        val maxW = child.width.coerceAtLeast(1)
        val maxH = child.height.coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val onSurfaceVariant = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY
        )

        val caption = TextView(this).apply {
            text = "Width × height on the page (px at 100% zoom).\nPage size: $maxW × $maxH px."
            textSize = 12f
            setTextColor(onSurfaceVariant)
        }
        fun field(hintText: String, initial: Int): android.widget.EditText = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            this.hint = hintText
            setText(initial.toString())
            selectAll()
        }
        val wInput = field("Width", maxW / 4)
        val hInput = field("Height", maxH / 4)
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = pad
            layoutParams = lp
            addView(
                wInput,
                android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(android.widget.Space(this@DrawingActivity), android.widget.LinearLayout.LayoutParams(pad, 1))
            addView(
                hInput,
                android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(caption)
            addView(row)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Shape size")
            .setView(root)
            .setPositiveButton("Place") { _, _ ->
                val w = (wInput.text.toString().toIntOrNull() ?: maxW / 4).coerceIn(8, maxW)
                val h = (hInput.text.toString().toIntOrNull() ?: maxH / 4).coerceIn(8, maxH)
                placeShapeOfExactSize(w.toFloat(), h.toFloat())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun placeShapeOfExactSize(w: Float, h: Float) {
        val (pageIndex, child) = currentPageChildForCenter() ?: return
        val pageW = child.width.toFloat()
        val pageH = child.height.toFloat()
        val contentCenterY = (pdfRecyclerView.height / 2f - pdfRecyclerView.transY) / pdfRecyclerView.zoom
        val cy = (contentCenterY - child.top.toFloat())
            .coerceIn(h / 2f, (pageH - h / 2f).coerceAtLeast(h / 2f))
        val cx = pageW / 2f
        val l = cx - w / 2f
        val t = cy - h / 2f
        val r = cx + w / 2f
        val b = cy + h / 2f

        val path = when (drawingView.shapeType) {
            ShapeType.OVAL -> Path().apply { addOval(RectF(l, t, r, b), Path.Direction.CW) }
            ShapeType.TRIANGLE -> Path().apply {
                moveTo(cx, t); lineTo(r, b); lineTo(l, b); close()
            }
            ShapeType.LINE -> Path().apply { moveTo(l, cy); lineTo(r, cy) }
            ShapeType.ARROW -> Path().apply {
                val head = (w * 0.28f).coerceIn(6f, 28f)
                moveTo(l, cy); lineTo(r, cy)
                lineTo(r - head, cy - head * 0.42f)
                moveTo(r, cy); lineTo(r - head, cy + head * 0.42f)
            }
            else -> Path().apply { addRect(RectF(l, t, r, b), Path.Direction.CW) }
        }
        val strokeWidth = dockThickness[dockActiveThicknessIndex]
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            this.strokeWidth = strokeWidth
            color = dockColors[dockActiveColorIndex]
            pathEffect = PenLineStyle.pathEffect(drawingView.penLineStyle, strokeWidth)
        }
        val stroke = StrokeData(
            path = path,
            paint = paint,
            type = StrokeType.PEN,
            lineStyle = drawingView.penLineStyle
        )
        historyManager.execute(DrawingAction.AddStroke(pageIndex, stroke), strokeManager)
        strokesDirty = true
        invalidateInk()
        updateUndoRedoButtons()

        val result = strokeManager.selectStrokes(pageIndex, listOf(stroke)) ?: return
        presentSelection(child.left.toFloat(), child.top.toFloat(), result.second)
    }

    private fun selectShapeAfterDraw(pageIndex: Int, stroke: StrokeData) {
        val result = strokeManager.selectStrokes(pageIndex, listOf(stroke)) ?: return
        val origin = drawingView.pageOrigin(pageIndex)
        presentSelection(origin?.x ?: 0f, origin?.y ?: 0f, result.second)

        invalidateInk()
    }

    private fun repositionSelectionPopup() {
        val popup = selectionPopup ?: return
        val bounds = drawingView.currentSelectionScreenBounds() ?: return
        val content = popup.contentView
        val w = content.measuredWidth
        val h = content.measuredHeight
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val x = bounds.centerX().toInt() - w / 2
        val y = (bounds.top.toInt() - h - (40 * density).toInt()).coerceAtLeast(100)
        popup.update(x, y, -1, -1)
    }

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
        view.findViewById<View>(R.id.overflowLinks).setOnClickListener {
            popup.dismiss()
            showLinksDialog()
        }
        view.findViewById<View>(R.id.overflowProperties).setOnClickListener {
            popup.dismiss()
            showPdfPropertiesDialog()
        }
        popup.showAsDropDown(anchor, 0, 8)
    }

    private fun showLinksDialog() {
        val path = pdfFilePath ?: run {
            Toast.makeText(this, "No PDF to read links from", Toast.LENGTH_SHORT).show(); return
        }
        val busy = showBusyDialog("Reading links…")
        busy.show()
        lifecycleScope.launch {
            val links = withContext(Dispatchers.IO) {
                try { PdfLinkReader.read(File(path).readBytes()) } catch (e: Exception) { emptyList() }
            }
            busy.dismiss()
            if (isFinishing || isDestroyed) return@launch
            if (links.isEmpty()) {
                Toast.makeText(this@DrawingActivity, "No links in this PDF", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val view = layoutInflater.inflate(R.layout.popup_links, null)
            val container = view.findViewById<android.widget.LinearLayout>(R.id.linksContainer)
            val dialog = MaterialAlertDialogBuilder(this@DrawingActivity)
                .setTitle("Links (${links.size})")
                .setView(view)
                .setNegativeButton("Close", null)
                .create()

            links.forEachIndexed { index, link ->
                val row = layoutInflater.inflate(R.layout.item_link, container, false)
                val target = link.uri ?: "Page ${link.destPage + 1}"
                row.findViewById<TextView>(R.id.linkLabel).text = target
                row.findViewById<TextView>(R.id.linkPage).text = "PAGE ${link.pageIndex + 1}"
                row.setOnClickListener {
                    dialog.dismiss()
                    if (link.uri != null) openLinkUri(link.uri)
                    else jumpToPage(link.destPage)
                }
                container.addView(row)
            }
            dialog.show()
        }
    }

    private fun openLinkUri(uri: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPdfPropertiesDialog() {
        val path = pdfFilePath ?: run {
            Toast.makeText(this, "No PDF to inspect", Toast.LENGTH_SHORT).show(); return
        }
        val busy = showBusyDialog("Reading properties…")
        busy.show()
        lifecycleScope.launch {
            val props = withContext(Dispatchers.IO) {
                try {
                    val editor = IncrementalPdfEditor.open(File(path))
                    val sizes = (0 until editor.pageCount).map { editor.pageSize(it) }
                    val counts = sizes.groupingBy { it }.eachCount()
                    val sb = StringBuilder("Pages: ${editor.pageCount}\n")
                    for ((size, count) in counts) {
                        sb.append("\n").append(formatPageSize(size)).append(": $count page")
                        if (count > 1) sb.append("s")
                    }
                    sb.toString()
                } catch (e: Exception) {
                    null
                }
            }
            busy.dismiss()
            if (isFinishing || isDestroyed) return@launch
            if (props == null) {
                Toast.makeText(this@DrawingActivity, "Couldn't read PDF properties", Toast.LENGTH_SHORT).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@DrawingActivity)
                .setTitle("PDF properties")
                .setMessage(props)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun formatPageSize(size: Pair<Float, Float>): String {
        val ptW = size.first.toInt().coerceAtLeast(1)
        val ptH = size.second.toInt().coerceAtLeast(1)

        val standard = listOf(
            "A3" to (297f to 420f), "A4" to (210f to 297f), "A5" to (148f to 210f), "A6" to (105f to 148f),
            "Letter" to (216f to 279f), "Legal" to (216f to 356f)
        )
        val mmW = ptW * 25.4f / 72f
        val mmH = ptH * 25.4f / 72f
        val sorted = listOf(minOf(mmW, mmH), maxOf(mmW, mmH))
        val name = standard.firstOrNull { (_, d) ->
            val s = listOf(minOf(d.first, d.second), maxOf(d.first, d.second))
            abs(sorted[0] - s[0]) <= 2f && abs(sorted[1] - s[1]) <= 2f
        }?.first
        return if (name != null) "$name (${mmW.roundToInt()} × ${mmH.roundToInt()} mm)"
        else "Custom (${ptW} × ${ptH} pt)"
    }

    private fun invalidateTextIndex() {
        pdfTextIndex = null
        searchMatches = emptyList(); searchPos = -1; activeHighlight = null
        highlightsByPage.clear()
        outlineCacheValue = null
        outlineCacheFile().delete()
    }

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
                progress.dismiss()
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

    private fun handleLassoSelection(lassoScreenPath: Path) {
        val rv = pdfRecyclerView
        val zoom = rv.zoom
        val tx = rv.transX
        val ty = rv.transY
        val bounds = RectF()
        lassoScreenPath.computeBounds(bounds, true)
        val contentCenterY = (bounds.centerY() - ty) / zoom

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

        val toPage = Matrix().apply {
            postTranslate(-tx, -ty)
            postScale(1 / zoom, 1 / zoom)
            postTranslate(-childLeft, -childTop)
        }
        val lassoPagePath = Path()
        lassoScreenPath.transform(toPage, lassoPagePath)

        val snapshot = strokeManager.snapshotPageStrokes(pageIndex) ?: return
        val lassoCopy = Path(lassoPagePath)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                strokeManager.computeSelectionInPath(lassoCopy, snapshot)
            } ?: return@launch
            if (isDestroyed || isFinishing) return@launch

            strokeManager.beginSelection(pageIndex, result.first)
            invalidateInk()
            presentSelectionForPage(pageIndex, result.second)
        }
    }

    private fun presentSelectionForPage(pageIndex: Int, pdfUnionBounds: RectF) {
        val rv = pdfRecyclerView
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            if (rv.getChildAdapterPosition(child) == pageIndex) {
                presentSelection(child.left.toFloat(), child.top.toFloat(), pdfUnionBounds)
                return
            }
        }
        strokeManager.cancelSelection()
        invalidateInk()
    }

    private fun presentSelection(childLeft: Float, childTop: Float, pdfUnionBounds: RectF) {
        val rv = pdfRecyclerView
        val zoom = rv.zoom

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

        drawingView.startSelection(
            screenPaths, paints, screenBounds, childTop,
            strokeManager.activeSelectionPageIndex, emptyList(), rotations, selectedStrokes
        )
        showSelectionPopup(screenBounds)

        val selectionRef = strokeManager.activeSelectionStrokes

        if (selectionRef.any { it.type == StrokeType.IMAGE }) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { selectionRef.forEach { strokeManager.bitmapFor(it) } }

                if (isDestroyed || strokeManager.activeSelectionStrokes !== selectionRef || selectionRef.isEmpty()) return@launch

                drawingView.updateSelectionVisuals(selectionRef)
            }
        }
    }

    private fun resolveSelectionTarget(
        pdfDx: Float,
        pdfDy: Float,
        transform: DrawingView.SelectionTransform
    ): Triple<Int, Float, Float> {
        val sourcePage = strokeManager.activeSelectionPageIndex
        val unchanged = Triple(sourcePage, 0f, 0f)
        val bounds = strokeManager.selectionBoundsAfter(
            pdfDx, pdfDy, transform.scaleX, transform.scaleY,
            transform.pivotXFrac, transform.pivotYFrac
        ) ?: return unchanged
        val sourceOrigin = drawingView.pageOrigin(sourcePage) ?: return unchanged

        val contentX = sourceOrigin.x + bounds.centerX()
        val contentY = sourceOrigin.y + bounds.centerY()
        val targetPage = drawingView.pageAtContent(contentX, contentY) ?: return unchanged
        if (targetPage == sourcePage) return unchanged
        val targetOrigin = drawingView.pageOrigin(targetPage) ?: return unchanged

        return Triple(targetPage, sourceOrigin.x - targetOrigin.x, sourceOrigin.y - targetOrigin.y)
    }

    private fun handleSelectionCommit(transform: DrawingView.SelectionTransform) {
        val zoom = pdfRecyclerView.zoom

        val (scrollDx, scrollDy) = drawingView.selectionScreenOffset
        val pdfDx = (transform.dx - scrollDx) / zoom
        val pdfDy = (transform.dy - scrollDy) / zoom
        val moved = kotlin.math.abs(pdfDx) > 0.1f ||
            kotlin.math.abs(pdfDy) > 0.1f ||
            transform.scaleX != 1f || transform.scaleY != 1f
        if (moved) {

            val (targetPage, rebaseDx, rebaseDy) = resolveSelectionTarget(pdfDx, pdfDy, transform)
            val result = strokeManager.commitSelection(
                pdfDx, pdfDy, transform.scaleX, transform.scaleY,
                transform.pivotXFrac, transform.pivotYFrac,
                targetPage, rebaseDx, rebaseDy
            )
            if (result != null) {
                historyManager.execute(result.action, strokeManager)
                strokesDirty = true
                invalidateInk()
                updateUndoRedoButtons()
            }
        } else if (strokeManager.selectionMutated) {

            val result = strokeManager.commitSelection(0f, 0f)
            if (result != null) {
                historyManager.execute(result.action, strokeManager)
                strokesDirty = true
                invalidateInk()
                updateUndoRedoButtons()
            }
        } else {

            strokeManager.cancelSelection()
            invalidateInk()
        }
        selectionTotalDx = 0f
        selectionTotalDy = 0f
        selectionTotalScale = 1f
        selectionPopup?.dismiss()
    }

    private fun showSelectionPopup(screenBounds: RectF) {
        selectionPopup?.dismiss()
        val view = layoutInflater.inflate(R.layout.popup_selection, null)

        val singleTable = strokeManager.activeSelectionStrokes.size == 1 &&
            strokeManager.activeSelectionStrokes.firstOrNull()?.type == StrokeType.TABLE
        view.findViewById<View>(R.id.selTableRow).visibility =
            if (singleTable) View.VISIBLE else View.GONE

        view.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)

        selectionPopup = android.widget.PopupWindow(view, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false)
        selectionPopup?.elevation = 20f
        selectionPopup?.setBackgroundDrawable(null)

        val colorBtn = view.findViewById<android.view.View>(R.id.selColorBtn)
        val actionsRow = view.findViewById<android.view.View>(R.id.selActionsRow)
        val colorsRow = view.findViewById<android.view.View>(R.id.selColorsRow)

        fun refreshSelectionSwatch() {
            val bg = colorBtn.background as? android.graphics.drawable.LayerDrawable
            (bg?.findDrawableByLayerId(R.id.color_shape) as? android.graphics.drawable.GradientDrawable)
                ?.setColor(strokeManager.activeSelectionStrokes.firstOrNull()?.paint?.color ?: android.graphics.Color.BLACK)
        }
        refreshSelectionSwatch()

        fun applySelectionColor(color: Int) {
            strokeManager.activeSelectionStrokes.forEach { it.paint.color = color }
            strokeManager.selectionMutated = true
            strokesDirty = true
            drawingView.updateSelectionVisuals(strokeManager.activeSelectionStrokes)
            refreshSelectionSwatch()
        }

        fun relayoutSelectionPopup() {

            view.measure(
                android.view.View.MeasureSpec.UNSPECIFIED,
                android.view.View.MeasureSpec.UNSPECIFIED
            )
            val popup = selectionPopup ?: return
            if (!popup.isShowing) return

            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val cx = loc[0] + view.width / 2
            val screenW = resources.displayMetrics.widthPixels
            val nx = (cx - view.measuredWidth / 2)
                .coerceIn(8, (screenW - view.measuredWidth - 8).coerceAtLeast(8))
            popup.update(nx, loc[1], view.measuredWidth, view.measuredHeight)
        }

        fun showSelectionActions() {
            actionsRow.visibility = View.VISIBLE
            colorsRow.visibility = View.GONE
            relayoutSelectionPopup()
        }

        fun showSelectionColors() {
            val circleIds = intArrayOf(
                R.id.selQuickColor0, R.id.selQuickColor1, R.id.selQuickColor2,
                R.id.selQuickColor3, R.id.selQuickColor4
            )
            dockColors.forEachIndexed { i, c ->
                val circle = view.findViewById<View>(circleIds[i])
                val bg = circle.background as? android.graphics.drawable.LayerDrawable
                (bg?.findDrawableByLayerId(R.id.color_shape) as? android.graphics.drawable.GradientDrawable)
                    ?.setColor(c)
                circle.setOnClickListener { applySelectionColor(c) }
            }
            actionsRow.visibility = View.GONE
            colorsRow.visibility = View.VISIBLE
            relayoutSelectionPopup()
        }

        colorBtn.setOnClickListener { showSelectionColors() }
        view.findViewById<android.view.View>(R.id.selColorCloseBtn).setOnClickListener { showSelectionActions() }
        view.findViewById<android.view.View>(R.id.selColorMoreBtn).setOnClickListener {
            val initialColor = strokeManager.activeSelectionStrokes.firstOrNull()?.paint?.color ?: android.graphics.Color.BLACK
            ColorPickerDialog.show(this, initialColor, allowEyedropper = true) { color ->
                applySelectionColor(color)
            }
        }

        val selectionHasTable = strokeManager.activeSelectionStrokes.any {
            it.type == StrokeType.TABLE || it.type == StrokeType.TEXT
        }
        view.findViewById<android.view.View>(R.id.selAngleBtn).visibility =
            if (selectionHasTable) android.view.View.GONE else android.view.View.VISIBLE

        view.findViewById<android.view.View>(R.id.selAngleBtn).setOnClickListener {
            showAnglePopup(it)
        }

        if (singleTable) {
            val rowsValue = view.findViewById<TextView>(R.id.selTableRowsValue)
            val colsValue = view.findViewById<TextView>(R.id.selTableColsValue)
            val headerBtn = view.findViewById<TextView>(R.id.selTableHeaderBtn)
            val colHeaderBtn = view.findViewById<TextView>(R.id.selTableColHeaderBtn)
            val headerColorBtn = view.findViewById<View>(R.id.selTableHeaderColorBtn)
            val colColorBtn = view.findViewById<View>(R.id.selTableColColorBtn)
            val radiusValue = view.findViewById<TextView>(R.id.selTableRadiusValue)
            val primary = com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorPrimary, Color.BLACK
            )
            val onSurfaceVariant = com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY
            )

            fun setColorDot(btn: View, color: Int) {
                val bg = btn.background as? android.graphics.drawable.LayerDrawable
                (bg?.findDrawableByLayerId(R.id.color_shape) as? android.graphics.drawable.GradientDrawable)
                    ?.setColor(color)
            }

            fun refreshTableRow() {
                val live = strokeManager.activeSelectionStrokes.firstOrNull() ?: return
                val t = live.tableData ?: return
                rowsValue.text = t.rows.toString()
                colsValue.text = t.cols.toString()
                headerBtn.text = getString(if (t.headerRow) R.string.table_header_on else R.string.table_header_off)
                headerBtn.setTextColor(if (t.headerRow) primary else onSurfaceVariant)
                colHeaderBtn.text = getString(if (t.headerCol) R.string.table_col_header_on else R.string.table_col_header_off)
                colHeaderBtn.setTextColor(if (t.headerCol) primary else onSurfaceVariant)
                setColorDot(headerColorBtn, t.headerColor ?: live.paint.color)
                setColorDot(colColorBtn, t.headerColColor ?: live.paint.color)
                radiusValue.text = t.borderRadius.roundToInt().toString()
            }

            fun applyTableEdit(
                newRows: Int? = null,
                newCols: Int? = null,
                newHeader: Boolean? = null,
                newHeaderCol: Boolean? = null,
                newHeaderColor: Int? = null,
                newHeaderColColor: Int? = null,
                clearHeaderColor: Boolean = false,
                clearHeaderColColor: Boolean = false,
                newRadius: Float? = null
            ) {
                val live = strokeManager.activeSelectionStrokes.firstOrNull() ?: return
                val page = strokeManager.activeSelectionPageIndex
                val original = strokeManager.knownStrokesForPage(page)
                    .firstOrNull { it.id == live.id && it.type == StrokeType.TABLE } ?: return
                val td = original.tableData ?: return
                val rows = newRows ?: td.rows
                val cols = newCols ?: td.cols
                val header = newHeader ?: td.headerRow
                val headerCol = newHeaderCol ?: td.headerCol
                val headerColor = if (clearHeaderColor) null else (newHeaderColor ?: td.headerColor)
                val headerColColor = if (clearHeaderColColor) null else (newHeaderColColor ?: td.headerColColor)
                val radius = newRadius ?: td.borderRadius

                val newCells = mutableMapOf<String, String>()
                for ((k, v) in td.cells) {
                    val p = k.split(",")
                    val r = p.getOrNull(0)?.toIntOrNull()
                    val c = p.getOrNull(1)?.toIntOrNull()
                    if (r != null && c != null && r < rows && c < cols) newCells[k] = v
                }
                val newTd = td.copy(
                    rows = rows, cols = cols,
                    headerRow = header, headerCol = headerCol,
                    headerColor = headerColor, headerColColor = headerColColor,
                    borderRadius = radius, cells = newCells
                )
                val newStroke = original.copy(tableData = newTd)
                historyManager.execute(
                    DrawingAction.ReplaceStrokes(page, listOf(original), listOf(newStroke)),
                    strokeManager
                )

                strokeManager.selectStrokes(page, listOf(newStroke))
                drawingView.updateSelectionVisuals(strokeManager.activeSelectionStrokes)
                strokesDirty = true
                invalidateInk()
                updateUndoRedoButtons()
                refreshTableRow()
            }

            view.findViewById<View>(R.id.selTableRowsMinus).setOnClickListener {
                val t = strokeManager.activeSelectionStrokes.firstOrNull()?.tableData ?: return@setOnClickListener
                applyTableEdit(newRows = (t.rows - 1).coerceAtLeast(1))
            }
            view.findViewById<View>(R.id.selTableRowsPlus).setOnClickListener {
                val t = strokeManager.activeSelectionStrokes.firstOrNull()?.tableData ?: return@setOnClickListener
                applyTableEdit(newRows = (t.rows + 1).coerceAtMost(10))
            }
            view.findViewById<View>(R.id.selTableColsMinus).setOnClickListener {
                val t = strokeManager.activeSelectionStrokes.firstOrNull()?.tableData ?: return@setOnClickListener
                applyTableEdit(newCols = (t.cols - 1).coerceAtLeast(1))
            }
            view.findViewById<View>(R.id.selTableColsPlus).setOnClickListener {
                val t = strokeManager.activeSelectionStrokes.firstOrNull()?.tableData ?: return@setOnClickListener
                applyTableEdit(newCols = (t.cols + 1).coerceAtMost(8))
            }
            headerBtn.setOnClickListener {
                val t = strokeManager.activeSelectionStrokes.firstOrNull()?.tableData ?: return@setOnClickListener
                applyTableEdit(newHeader = !t.headerRow)
            }
            colHeaderBtn.setOnClickListener {
                val t = strokeManager.activeSelectionStrokes.firstOrNull()?.tableData ?: return@setOnClickListener
                applyTableEdit(newHeaderCol = !t.headerCol)
            }
            headerColorBtn.setOnClickListener {
                val live = strokeManager.activeSelectionStrokes.firstOrNull() ?: return@setOnClickListener
                val t = live.tableData ?: return@setOnClickListener
                ColorPickerDialog.show(
                    this, t.headerColor ?: live.paint.color, allowEyedropper = true
                ) { color -> applyTableEdit(newHeaderColor = color) }
            }

            headerColorBtn.setOnLongClickListener {
                applyTableEdit(clearHeaderColor = true)
                true
            }
            colColorBtn.setOnClickListener {
                val live = strokeManager.activeSelectionStrokes.firstOrNull() ?: return@setOnClickListener
                val t = live.tableData ?: return@setOnClickListener
                ColorPickerDialog.show(
                    this, t.headerColColor ?: live.paint.color, allowEyedropper = true
                ) { color -> applyTableEdit(newHeaderColColor = color) }
            }
            colColorBtn.setOnLongClickListener {
                applyTableEdit(clearHeaderColColor = true)
                true
            }
            view.findViewById<View>(R.id.selTableRadiusMinus).setOnClickListener {
                val t = strokeManager.activeSelectionStrokes.firstOrNull()?.tableData ?: return@setOnClickListener
                applyTableEdit(newRadius = (t.borderRadius - 4).coerceAtLeast(0f))
            }
            view.findViewById<View>(R.id.selTableRadiusPlus).setOnClickListener {
                val t = strokeManager.activeSelectionStrokes.firstOrNull()?.tableData ?: return@setOnClickListener
                applyTableEdit(newRadius = (t.borderRadius + 4).coerceAtMost(40f))
            }
            refreshTableRow()
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

            val live = drawingView.currentSelectionTransform()

            val (scrollDx, scrollDy) = drawingView.selectionScreenOffset
            val pdfDx = (live.dx - scrollDx) / pdfRecyclerView.zoom
            val pdfDy = (live.dy - scrollDy) / pdfRecyclerView.zoom
            val (targetPage, rebaseDx, rebaseDy) = resolveSelectionTarget(pdfDx, pdfDy, live)
            val commit = strokeManager.commitSelection(
                pdfDx, pdfDy, live.scaleX, live.scaleY,
                live.pivotXFrac, live.pivotYFrac, targetPage, rebaseDx, rebaseDy
            )
            if (commit != null) {
                historyManager.execute(commit.action, strokeManager)

                val dupStrokes = commit.newStrokes.map { s ->
                    val p = Path(s.path)
                    p.transform(Matrix().apply { postTranslate(30f, 30f) })
                    s.copy(path = p, id = java.util.UUID.randomUUID().toString())
                }

                historyManager.execute(
                    DrawingAction.BatchAction(dupStrokes.map { DrawingAction.AddStroke(commit.pageIndex, it) }),
                    strokeManager
                )

                val dupResult = strokeManager.selectStrokes(commit.pageIndex, dupStrokes)
                if (dupResult != null) {
                    val origin = drawingView.pageOrigin(commit.pageIndex)
                    presentSelection(origin?.x ?: 0f, origin?.y ?: 0f, dupResult.second)
                    invalidateInk()
                    updateUndoRedoButtons()
                    return@setOnClickListener
                }
            }
            drawingView.clearSelectionVisuals()
            invalidateInk()
            selectionPopup?.dismiss()
            updateUndoRedoButtons()
        }

        drawingView.onSelectionMovedListener = { transform ->
            selectionTotalDx = transform.dx
            selectionTotalDy = transform.dy
            selectionTotalScale = transform.scaleX
            handleSelectionCommit(transform)
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

        val flipPopupHasTable = strokeManager.activeSelectionStrokes.any {
            it.type == StrokeType.TABLE || it.type == StrokeType.TEXT
        }
        if (flipPopupHasTable) {
            view.findViewById<android.view.View>(R.id.btnFlipH).visibility = android.view.View.GONE
            view.findViewById<android.view.View>(R.id.btnFlipV).visibility = android.view.View.GONE
        }

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

            strokeManager.cancelSelection()
            drawingView.clearSelectionVisuals()
            invalidateInk()
            popup.dismiss()
            selectionPopup?.dismiss()
        }

        popup.showAsDropDown(anchor, 0, 8)
    }

    private var pastePopup: android.widget.PopupWindow? = null

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

            val gridScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val dialog = android.app.Dialog(this@DrawingActivity, R.style.Theme_OctopusNotes)
            gridDialog = dialog
            gridSelectionMode = false
            gridSelected.clear()

            var currentTab = R.id.tabAll
            var adapter: PageGridAdapter? = null
            var touchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null

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

                        setSelectionMode(true, page)
                    } else if (currentTab == R.id.tabAll) {

                        touchHelper?.startDrag(holder)
                    }
                }
            )
            recycler.adapter = adapter

            (recycler.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)
                ?.scrollToPositionWithOffset(currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0)), 0)

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

                selectButton.visibility = if (tab == R.id.tabAll) View.VISIBLE else View.GONE
                reloadButton.visibility = if (tab == R.id.tabOutline) View.VISIBLE else View.GONE

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

            showGridPill(true)
            dialog.setOnDismissListener {
                gridScope.cancel()

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
        return try {
            PdfOutlineReader.read(file.readBytes()).map { toOutlineUi(it) }
        } catch (e: Exception) {
            android.util.Log.w("OctopusNotes", "Failed to read PDF outline", e)
            emptyList()
        }
    }

    private fun toOutlineUi(item: PdfOutlineReader.Item): OutlineUiEntry =
        OutlineUiEntry(
            title = item.title,
            page = item.pageIndex,
            isUser = false,
            depth = item.depth,
            children = item.children.map(::toOutlineUi)
        )

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

        val sizeBefore = strokePageSize(pageIndex)

        historyManager.clear()
        selectionPopup?.dismiss()

        strokeManager.cancelSelection()
        drawingView.clearSelectionVisuals()
        val busy = showBusyDialog("Rotating page…")
        busy.show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { rotatePageInPdf(File(path), pageIndex, deltaDeg) }
            } catch (e: Exception) {
                e.printStackTrace()
                busy.dismiss()
                Toast.makeText(this@DrawingActivity, editErrorText(e, "Couldn't rotate the page. Please try again"), Toast.LENGTH_LONG).show()
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
        editPdfSafely(file) { it.rotatePage(pageIndex, deltaDeg) }
    }

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

            val deleted = try {
                if (path != null) withContext(Dispatchers.IO) { removePagesFromPdf(File(path), pagesDesc) }
                else pagesDesc
            } catch (e: Exception) {
                e.printStackTrace()
                busy.dismiss()
                Toast.makeText(this@DrawingActivity, editErrorText(e, "Couldn't save the PDF. Please try again"), Toast.LENGTH_LONG).show()
                return@launch
            }

            for (p in deleted) {
                strokeManager.removePage(p, totalPages)
                strokesDirty = true
                pageMeta.onPageRemoved(p)
                totalPages -= 1
                if (p < currentPage) currentPage--
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

    private fun movePage(from: Int, to: Int, onDone: () -> Unit) {
        if (from == to || from !in 0 until totalPages || to !in 0 until totalPages) {
            onDone()
            return
        }

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
                Toast.makeText(this@DrawingActivity, editErrorText(e, "Couldn't move the page. Please try again"), Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@DrawingActivity, "Couldn't delete the page. Please try again", Toast.LENGTH_LONG).show()
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

    private fun strokePageSizes(): List<Pair<Float, Float>> {
        val engine = pdfEngine ?: return emptyList()
        val w = pdfRecyclerView.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()

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
                Toast.makeText(this@DrawingActivity, editErrorText(e, "Couldn't duplicate the page. Please try again"), Toast.LENGTH_LONG).show()
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
        editPdfSafely(file) { editor ->
            if (editor.pageCount > 1 && index in 0 until editor.pageCount) {
                editor.deletePages(listOf(index))
            }
        }
    }

    private fun removePagesFromPdf(file: File, indicesDesc: List<Int>): List<Int> {
        var removed = emptyList<Int>()
        editPdfSafely(file) { editor ->
            val valid = indicesDesc.filter { it in 0 until editor.pageCount }
            if (valid.isNotEmpty() && editor.pageCount - valid.size >= 1) {
                editor.deletePages(valid)
                removed = valid
            }
        }
        return removed
    }

    private fun movePageInPdf(file: File, from: Int, to: Int) {
        editPdfSafely(file) { it.movePage(from, to) }
    }

    private fun duplicatePageInPdf(file: File, index: Int) {
        editPdfSafely(file) { editor ->
            if (index in 0 until editor.pageCount) editor.duplicatePage(index)
        }
    }

    private fun currentInkWidth(): Float =
        pdfRecyclerView.width.takeIf { it > 0 }?.toFloat()
            ?: resources.displayMetrics.widthPixels.toFloat()

    private fun legacyInkWidth(): Float {
        val dm = resources.displayMetrics
        return minOf(dm.widthPixels, dm.heightPixels).toFloat()
    }

    private fun onInkWidthChanged(newWidth: Float) {
        if (!strokesLoaded) return
        val old = inkBaseWidth
        if (old <= 0f || newWidth <= 0f || newWidth == old) return
        val factor = newWidth / old
        inkBaseWidth = newWidth

        strokeManager.rescaleAll(factor, historyManager.allReferencedStrokes())

        scaleToolSizes(factor, newWidth)
        drawingView.clearSelectionVisuals()
        selectionPopup?.dismiss()

        contentTools.dismissPopups()
        updateUndoRedoButtons()
        strokesDirty = true
        invalidateInk()

        pdfAdapter?.refreshForWidthChange(newWidth.toInt())
    }

    private fun scaleToolSizes(factor: Float, newBaseWidth: Float) {
        if (factor == 1f || factor <= 0f) {
            toolSettingsManager.scaleToolSizes(1f, newBaseWidth)
            return
        }

        toolSettingsManager.scaleToolSizes(factor, newBaseWidth)
        drawingView.setHighlighterSize(toolSettingsManager.getHighlighterSize())

        for (i in dockThickness.indices) {
            dockThickness[i] *= factor
            penPrefs().edit().putFloat("DOCK_THICK_$i", dockThickness[i]).apply()
        }

        listOf("TABLE" to tableThickness, "LASER" to laserThickness).forEach { (prefix, arr) ->
            if (arr != null) {
                for (i in arr.indices) {
                    arr[i] *= factor
                    penPrefs().edit().putFloat("${prefix}_THICK_$i", arr[i]).apply()
                }
            }
        }

        applyDockThickness(toolThicknessSize())

        penPrefs().edit().putFloat("TEXT_SIZE", contentTools.textSizePref() * factor).apply()

        when (activeToolButton?.id) {
            R.id.eraserButton -> toolSettingsManager.applyEraserSettings()
            R.id.highlighterButton -> toolSettingsManager.applyHighlighterSettings()
            else -> {}
        }
    }

    private fun loadSavedDrawing() {

        pdfRecyclerView.doOnLayout { loadSavedDrawingNow() }
    }

    private fun loadSavedDrawingNow() {

        val pending = lastSaveJob
        val targetWidth = currentInkWidth()
        val fallbackBaseWidth = legacyInkWidth()
        inkBaseWidth = targetWidth

        val toolBase = toolSettingsManager.toolSizeBaseWidth()
        if (toolBase > 0f) {
            if (toolBase != targetWidth) {
                scaleToolSizes(targetWidth / toolBase, targetWidth)
            }
        } else {

            toolSettingsManager.scaleToolSizes(1f, targetWidth)
        }
        lifecycleScope.launch {

            if (pending != null && pending.isActive) {
                withContext(Dispatchers.IO) { pending.join() }
            }

            val shown = mutableSetOf<Int>()
            val result = withContext(Dispatchers.IO) {
                drawingRepository.load(notebookId, targetWidth, fallbackBaseWidth, priorityPage = currentPage) { pageIndex, page ->
                    launch(Dispatchers.Main) {
                        strokeManager.applyLoadedPage(pageIndex, page)
                        shown.add(pageIndex)
                        invalidateInk()
                    }
                }
            }

            if (toolBase <= 0f && result.baseWidth != targetWidth) {
                val base = if (result.baseWidth > 0f) result.baseWidth else fallbackBaseWidth
                if (base > 0f && base != targetWidth) {
                    scaleToolSizes(targetWidth / base, targetWidth)
                }
            }

            for ((p, page) in result.pages) {
                if (p !in shown) strokeManager.applyLoadedPage(p, page)
            }
            strokesLoaded = true

            val currentWidth = currentInkWidth()
            if (currentWidth > 0f && currentWidth != inkBaseWidth) {
                onInkWidthChanged(currentWidth)
            }
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

        contentTools.dismissPopups()

        saveDrawing()
    }

    private var saveGeneration = 0

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
        tabsController?.refresh()
    }

    override fun onStop() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        super.onStop()
    }

    private fun saveDrawing() {
        if (notebookId < 0) return

        if (!strokesLoaded) return
        if (!strokesDirty) return
        strokesDirty = false
        val pages = strokeManager.allPagesWithData()
        val known = pages.associateWith { strokeManager.knownStrokesForPage(it).toList() }
        val unknown = pages.associateWith { strokeManager.unknownStrokesForPage(it).toList() }

        val baseWidth = inkBaseWidth.takeIf { it > 0f } ?: currentInkWidth()

        val gen = ++saveGeneration
        lastSaveJob?.cancel()
        lastSaveJob = appSaveScope.launch(NonCancellable) {
            try {
                drawingRepository.save(
                    notebookId,
                    pages,
                    knownProvider = { known[it] ?: emptyList() },
                    unknownProvider = { unknown[it] ?: emptyList() },
                    baseWidth = baseWidth,
                    generation = gen
                )
            } catch (e: Exception) {
                strokesDirty = true
                return@launch
            }
            dataManager.touchModified(notebookId)

            SyncFolderManager.mirrorNotebook(this@DrawingActivity, notebookId, dataManager.getNotebook(notebookId))
        }
    }

    companion object {

        @Volatile
        var lastSaveJob: kotlinx.coroutines.Job? = null

        private val appSaveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private fun onPageChanged(page: Int, pageCount: Int) {
        if (pageRestorePending) return
        currentPage = page
        updatePageNumberView()

    }
    private fun updatePageNumberView() { pageNumberTextView.text = "${currentPage + 1} / $totalPages" }

    private fun persistLastPage() {
        if (notebookId < 0 || pageRestorePending) return
        statePrefs.edit().putInt("last_page_$notebookId", currentPage).apply()
    }

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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollPill() {
        scrollPillTrack = findViewById(R.id.scrollPillTrack)
        scrollPillThumb = findViewById(R.id.scrollPillThumb)

        pdfRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (scrollPillDragging) return
                updateScrollPillPosition()
            }
        })

        pdfRecyclerView.onScrollStateChanged = { newState ->
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                persistLastPage()
                updateScrollPillPosition()
            }
        }

        scrollPillThumb.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scrollPillDragging = true
                    dragMoved = false
                    scrollPillFadeHandler.removeCallbacks(scrollPillFadeRunnable)
                    scrollPillThumb.animate().alpha(1f).setDuration(100).start()

                    pdfEngine?.renderPausedForDrag = true
                    pdfRecyclerView.dragInProgress = true
                    pdfRecyclerView.cancelHiResForDrag()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val trackTop = scrollPillTrack.top.toFloat()
                    val trackHeight = scrollPillTrack.height.toFloat() - scrollPillThumb.height
                    if (trackHeight > 0 && totalPages > 1) {

                        val loc = IntArray(2)
                        (scrollPillTrack.parent as View).getLocationOnScreen(loc)
                        val relativeY = event.rawY - loc[1] - trackTop - scrollPillThumb.height / 2f
                        val fraction = (relativeY / trackHeight).coerceIn(0f, 1f)

                        scrollPillThumb.translationY =
                            trackTop + fraction * trackHeight - scrollPillThumb.top

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

                    if (dragFramePosted) {
                        Choreographer.getInstance().removeFrameCallback(dragFrameCallback)
                        dragFramePosted = false
                    }
                    if (dragMoved) {

                        val targetPage = (dragPendingFraction * totalPages).toInt()
                            .coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                        jumpToPage(targetPage)
                    }

                    pdfRecyclerView.dragInProgress = false
                    pdfEngine?.renderPausedForDrag = false

                    pdfEngine?.let { eng ->
                        lifecycleScope.launch { eng.prefetchSizes() }
                    }
                    scheduleScrollPillFade()

                    persistLastPage()

                    pdfRecyclerView.scheduleHiResPublic()
                    true
                }
                else -> false
            }
        }

        scrollPillThumb.alpha = 0f
        scrollPillThumb.visibility = View.INVISIBLE
    }

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
        val tabStrip: View = findViewById(R.id.tabStripRow)

        val gap = (8 * resources.displayMetrics.density).toInt()

        val tabGap = (4 * resources.displayMetrics.density).toInt()
        val isWideScreen = resources.configuration.screenWidthDp >= 600

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val safeInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )

            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val bottomFloat = maxOf(safeInsets.bottom, imeInsets.bottom)

            (tabStrip.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                topMargin = safeInsets.top + tabGap
            }

            (topBarStart.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                topMargin = safeInsets.top + tabGap
                leftMargin = safeInsets.left + gap
            }
            (topBarEnd.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                topMargin = safeInsets.top + tabGap
                rightMargin = safeInsets.right + gap
            }

            (toolDock.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                if (isWideScreen) {

                    topMargin = safeInsets.top + tabGap
                    leftMargin = gap
                    rightMargin = gap
                } else {
                    topMargin = 0
                    leftMargin = safeInsets.left + gap
                }
            }

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
            tabStrip.requestLayout()
            insets
        }
    }

}