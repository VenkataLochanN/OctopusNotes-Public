package com.lochan.octopusnotes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class InfiniteCanvasActivity : AppCompatActivity() {

    private lateinit var canvasView: InfiniteCanvasView
    private lateinit var strokeManager: StrokeManager
    private lateinit var historyManager: HistoryManager
    private lateinit var drawingRepository: DrawingRepository
    private lateinit var dataManager: DataManager
    private lateinit var zoomIndicator: TextView

    private lateinit var toolbox: ToolboxController

    private lateinit var contentTools: ContentToolsController

    private var notebookId = -1L
    private var tabsController: TabsController? = null

    private var strokesLoaded = false
    private var strokesDirty = false
    private var saveGeneration = 0

    private var barrelStrokeInProgress = false

    private var stylusSettings = StylusSettings()
    private lateinit var stylusSwitcher: StylusToolSwitcher

    private val prefs by lazy { getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_infinite_canvas)

        findViewById<View>(R.id.infiniteRootLayout).setBackgroundColor(CanvasColor.current(this))

        val notesDao = AppDatabase.getDatabase(this).notesDao()
        dataManager = DataManager(notesDao, cacheDir, filesDir)
        drawingRepository = DrawingRepository(this)
        notebookId = intent.getLongExtra("NOTEBOOK_ID", -1L)
        if (notebookId >= 0) lifecycleScope.launch { dataManager.touchOpened(notebookId) }

        strokeManager = StrokeManager()
        strokeManager.imagesDir = File(filesDir, "images").apply { mkdirs() }

        historyManager = HistoryManager()
        historyManager.onMutation = { strokesDirty = true }
        historyManager.onHistoryChanged = { updateUndoRedoButtons() }

        tabsController = TabsController(
            this, lifecycleScope, dataManager, { notebookId }, { saveDrawing() },
            switchInPlace = ::switchNotebookInPlace
        )
        tabsController?.attach()

        setupViews()
        setupEdgeToEdge()
        loadInk()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::canvasView.isInitialized && canvasView.onStylusKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::contentTools.isInitialized) contentTools.dismissPopups()
        strokeManager.clearBitmapCache()
        strokeManager.shutdown()

        val pending = lastSaveJob
        if (pending != null && pending.isActive) {
            Thread {
                try { kotlinx.coroutines.runBlocking { pending.join() } } catch (_: Exception) {}
            }.start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        canvasView = findViewById(R.id.infiniteCanvasView)
        canvasView.strokeManager = strokeManager
        canvasView.singleFingerAction = try {
            InfiniteCanvasView.FingerAction.valueOf(
                prefs.getString(
                    "INFINITE_SINGLE_FINGER_ACTION",
                    InfiniteCanvasView.FingerAction.SCROLL.name
                ) ?: InfiniteCanvasView.FingerAction.SCROLL.name
            )
        } catch (e: Exception) {
            InfiniteCanvasView.FingerAction.SCROLL
        }
        zoomIndicator = findViewById(R.id.zoomIndicator)
        canvasView.onZoomChanged = { z -> zoomIndicator.text = "${(z * 100).roundToInt()}%" }

        stylusSettings = StylusSettings.load(prefs)
        canvasView.stylusLongHoldEnabled = stylusSettings.longHold == StylusLongHold.ERASER
        canvasView.stylusLongPressEraseEnabled = stylusSettings.longPressErase

        contentTools = ContentToolsController(
            this,
            strokeManager,
            historyManager,
            host = object : ContentToolsHost {
                override val anchorView: View get() = canvasView
                override val smallDock: Boolean get() = false
                override val supportsTableStructure: Boolean get() = false
                override fun contentRectToScreen(pageIndex: Int, rect: RectF): RectF =
                    canvasView.worldToScreenRect(rect)
                override fun showPendingTable(pageIndex: Int, rect: RectF, rows: Int, cols: Int, headerRow: Boolean, headerCol: Boolean) =
                    canvasView.showPendingTable(rect, rows, cols, headerRow, headerCol)
                override fun updatePendingTableGrid(rows: Int, cols: Int, headerRow: Boolean, headerCol: Boolean) =
                    canvasView.updatePendingTableGrid(rows, cols, headerRow, headerCol)
                override fun clearPendingTable() = canvasView.clearPendingTable()
                override fun placeTarget(): ContentPlaceTarget {
                    val area = canvasView.visibleWorldRect()
                    val center = canvasView.screenToWorld(canvasView.width / 2f, canvasView.height / 2f)
                    return ContentPlaceTarget(0, area, center)
                }
                override fun textWrapWidth(pageIndex: Int): Float = canvasView.visibleWorldRect().width()
                override fun textColor(): Int = toolbox.activePenColor()
                override fun applyTapeSettings(pattern: String, width: Float) {
                    canvasView.tapePattern = pattern
                    canvasView.tapeWidth = width
                }
                override fun onContentChanged() {
                    canvasView.invalidate()
                    updateUndoRedoButtons()
                }
                override fun onObjectPlaced(pageIndex: Int, stroke: StrokeData) {
                    val result = strokeManager.selectStrokes(0, listOf(stroke)) ?: return
                    canvasView.setSelection(result.first, result.second)
                    canvasView.invalidate()
                }
                override fun onTableReplaced(pageIndex: Int, oldTable: StrokeData, newTable: StrokeData) {
                    if (strokeManager.activeSelectionPageIndex == 0 &&
                        strokeManager.activeSelectionStrokes.any { it.type == StrokeType.TABLE && it.id == oldTable.id }
                    ) {
                        val result = strokeManager.selectStrokes(0, listOf(newTable)) ?: return
                        canvasView.setSelection(result.first, result.second)
                        canvasView.invalidate()
                    }
                }
            },
            prefPrefix = "INFINITE_"
        )
        contentTools.wireImageStrip()
        contentTools.wireTapeStrip()

        toolbox = ToolboxController(
            this,
            InfiniteCanvasToolHost(canvasView),
            onToolActivated = { toolId ->
                stylusSwitcher.onToolActivated(toolId)
                if (toolId == "IMAGE") contentTools.refreshImageToolStrip()
            },
            config = ToolboxController.Config().apply {
                prefPrefix = "INFINITE_"
                lassoButtonId = R.id.lassoButton
            }
        )
        stylusSwitcher = StylusToolSwitcher(
            activateTool = { toolbox.activateTool(it) },
            currentToolId = { toolbox.activeToolId() }
        )
        toolbox.wire()

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.undoButton).setOnClickListener {
            if (historyManager.undo(strokeManager)) {
                strokeManager.cancelSelection()
                canvasView.clearSelection()
                canvasView.invalidate()
                updateUndoRedoButtons()
            }
        }
        findViewById<ImageButton>(R.id.redoButton).setOnClickListener {
            if (historyManager.redo(strokeManager)) {
                canvasView.invalidate()
                updateUndoRedoButtons()
            }
        }
        findViewById<ImageButton>(R.id.fitButton).setOnClickListener { canvasView.fitToContent() }
        zoomIndicator.setOnClickListener { canvasView.fitToContent() }
        findViewById<ImageButton>(R.id.moreButton).setOnClickListener {

            StylusGestureDialog(
                this,
                getFingerAction = { canvasView.singleFingerAction.name },
                setFingerAction = { action ->
                    canvasView.singleFingerAction = InfiniteCanvasView.FingerAction.valueOf(action)
                    prefs.edit().putString("INFINITE_SINGLE_FINGER_ACTION", action).apply()
                },
                getSettings = { stylusSettings },
                setSettings = { s ->
                    stylusSettings = s
                    canvasView.stylusLongHoldEnabled = s.longHold == StylusLongHold.ERASER
                    canvasView.stylusLongPressEraseEnabled = s.longPressErase
                    val editor = prefs.edit()
                    s.save(editor)
                    editor.apply()
                }
            ).show()
        }

        canvasView.onStrokeFinishedListener = { pageIndex, path, paint, contourData ->

            val effectiveId = if (barrelStrokeInProgress) "ERASER" else toolbox.activeToolId()
            val action = when (effectiveId) {
                "ERASER" -> strokeManager.processErase(
                    pageIndex, path, paint,
                    if (toolbox.eraserKind() == ToolKind.PIXEL_ERASER) {
                        DrawingView.Tool.PIXEL_ERASER
                    } else {
                        DrawingView.Tool.STROKE_ERASER
                    }
                )
                "HIGHLIGHTER" -> strokeManager.processPen(
                    pageIndex, path, paint, DrawingView.Tool.HIGHLIGHTER, PenLineStyle.SOLID, contourData
                )
                "SHAPE" -> strokeManager.processPen(
                    pageIndex, path, paint, DrawingView.Tool.SHAPE, toolbox.currentPenLineStyle(), contourData
                )
                "TABLE" -> {
                    contentTools.onTableStrokeFinished(pageIndex, path, paint, toolbox.currentPenLineStyle())
                    null
                }
                "TAPE" -> {
                    contentTools.onTapeStrokeFinished(pageIndex, path, paint, canvasView.tapePattern, canvasView.tapeWidth)
                    null
                }
                "TEXT" -> null
                else -> strokeManager.processPen(
                    pageIndex, path, paint, DrawingView.Tool.PEN, toolbox.currentPenLineStyle(), contourData
                )
            }
            if (action != null) {
                historyManager.execute(action, strokeManager)
                updateUndoRedoButtons()
                canvasView.invalidate()
            }
        }

        canvasView.onTextTapListener = { pageIndex, x, y -> contentTools.onTextTap(pageIndex, x, y) }
        canvasView.onTableCellTapListener = { pageIndex, x, y -> contentTools.onTableCellTap(pageIndex, x, y) }
        canvasView.onTapeTapListener = { pageIndex, x, y -> contentTools.onTapeTap(pageIndex, x, y) }

        canvasView.onBarrelButtonChanged = { pressed ->
            if (pressed) {
                barrelStrokeInProgress = true

                toolbox.applyEraserSettings()
            } else {
                barrelStrokeInProgress = false

                toolbox.restoreActiveToolSettings()
            }
        }
        canvasView.onStylusPrimaryAction = { stylusSwitcher.apply(stylusSettings.primaryAction) }
        canvasView.onStylusSecondaryAction = {

            if (stylusSettings.secondaryAction == StylusAction.ERASER) {
                canvasView.engageBarrel()
            } else {
                stylusSwitcher.apply(stylusSettings.secondaryAction)
            }
        }

        canvasView.onLassoFinishedListener = { lassoPath ->
            val snapshot = strokeManager.snapshotPageStrokes(0)
            val lassoCopy = Path(lassoPath)
            lifecycleScope.launch {
                if (snapshot == null) return@launch
                val result = withContext(Dispatchers.Default) {
                    strokeManager.computeSelectionInPath(lassoCopy, snapshot)
                } ?: return@launch
                if (isDestroyed || isFinishing) return@launch

                strokeManager.beginSelection(0, result.first)
                canvasView.setSelection(result.first, result.second)
                canvasView.invalidate()
            }
        }
        canvasView.onSelectionCommit = { dx, dy ->
            val commit = strokeManager.commitSelection(dx, dy)
            if (commit != null) {
                historyManager.execute(commit.action, strokeManager)
                updateUndoRedoButtons()
            }
            canvasView.clearSelection()
            canvasView.invalidate()
        }
        canvasView.onSelectionDismiss = {
            strokeManager.cancelSelection()
            canvasView.clearSelection()
            canvasView.invalidate()
        }

        updateUndoRedoButtons()
    }

    private fun updateUndoRedoButtons() {
        findViewById<ImageButton>(R.id.undoButton).apply {
            isEnabled = historyManager.canUndo()
            alpha = if (isEnabled) 1f else 0.4f
        }
        findViewById<ImageButton>(R.id.redoButton).apply {
            isEnabled = historyManager.canRedo()
            alpha = if (isEnabled) 1f else 0.4f
        }
    }

    private fun setupEdgeToEdge() {
        val rootLayout: View = findViewById(R.id.infiniteRootLayout)
        val tabStrip: View = findViewById(R.id.tabStripRow)
        val topBarStart: View = findViewById(R.id.topBarStart)
        val topBarEnd: View = findViewById(R.id.topBarEnd)
        val toolDock: View = findViewById(R.id.toolDock)

        val gap = (8 * resources.displayMetrics.density).toInt()

        val tabGap = (4 * resources.displayMetrics.density).toInt()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )

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
                topMargin = safeInsets.top + gap
                leftMargin = gap
                rightMargin = gap
            }

            tabStrip.requestLayout()
            topBarStart.requestLayout()
            topBarEnd.requestLayout()
            toolDock.requestLayout()
            insets
        }
    }

    private fun loadInk() {
        lifecycleScope.launch {

            val pending = lastSaveJob
            if (pending != null && pending.isActive) {
                withContext(Dispatchers.IO) { pending.join() }
            }
            val result = withContext(Dispatchers.IO) { drawingRepository.loadInfinite(notebookId) }
            strokeManager.loadDecodedData(result.pages)
            strokesLoaded = true
            canvasView.fitToContent()
            updateUndoRedoButtons()
        }
    }

    fun switchNotebookInPlace(target: Notebook): Boolean {
        if (target.documentType != DocumentType.INFINITE) return false

        contentTools.dismissPopups()
        canvasView.clearSelection()
        strokeManager.cancelSelection()
        strokeManager.loadDecodedData(emptyMap())
        historyManager.clear()
        updateUndoRedoButtons()

        notebookId = target.id
        if (notebookId >= 0) lifecycleScope.launch { dataManager.touchOpened(notebookId) }
        strokesLoaded = false
        strokesDirty = false
        loadInk()
        return true
    }

    override fun onPause() {
        super.onPause()
        saveDrawing()
    }

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
        val known = strokeManager.knownStrokesForPage(0).toList()
        val unknown = strokeManager.unknownStrokesForPage(0).toList()
        val gen = ++saveGeneration
        lastSaveJob?.cancel()
        lastSaveJob = appSaveScope.launch(NonCancellable) {
            try {
                drawingRepository.saveInfinite(notebookId, known, unknown, generation = gen)
            } catch (e: Exception) {
                strokesDirty = true
                return@launch
            }
            dataManager.touchModified(notebookId)

            SyncFolderManager.mirrorNotebook(this@InfiniteCanvasActivity, notebookId, dataManager.getNotebook(notebookId))
        }
    }

    companion object {

        @Volatile
        var lastSaveJob: kotlinx.coroutines.Job? = null

        private val appSaveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
