package com.lochan.octopusnotes

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageButton
import android.widget.TextView

class ToolboxController(
    private val activity: Activity,
    private val host: CanvasToolHost,
    private val onToolActivated: (toolId: String) -> Unit = {},
    val config: Config = Config(),
) {

    class Config {

        var lassoButtonId: Int = R.id.lassoButton

        var prefPrefix: String = ""

        var supportsHighlighterTextMode: Boolean = false

        var penDefaultColors: IntArray = intArrayOf(
            Color.BLACK,
            Color.parseColor("#1565C0"),
            Color.parseColor("#C62828"),
            Color.parseColor("#2E7D32"),
            Color.parseColor("#F9A825")
        )
        var penDefaultSizes: FloatArray = floatArrayOf(4f, 8f, 14f)
        var hlDefaultColors: IntArray = intArrayOf(
            Color.parseColor("#66FFEB00"),
            Color.parseColor("#6634A853"),
            Color.parseColor("#66F48FB1"),
            Color.parseColor("#664FC3F7"),
            Color.parseColor("#66FFA726")
        )
        var hlDefaultSizes: FloatArray = floatArrayOf(14f, 28f, 48f)
        var eraserDefaultSizes: FloatArray = floatArrayOf(30f, 60f, 110f)
        var laserDefaultColors: IntArray = intArrayOf(
            Color.parseColor("#1565C0"),
            Color.parseColor("#C62828"),
            Color.parseColor("#2E7D32"),
            Color.parseColor("#F9A825"),
            Color.parseColor("#8E24AA")
        )
        var laserDefaultSizes: FloatArray = floatArrayOf(4f, 8f, 14f)
    }

    private val prefs = activity.getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
    private fun key(suffix: String) = config.prefPrefix + suffix

    private var activeToolButton: ImageButton? = null

    private val penColors = IntArray(5)
    private var penActiveColorIndex = 0
    private val penSizes = FloatArray(3)
    private var penActiveSizeIndex = 1
    private var penLineStyle = PenLineStyle.SOLID
    private var stabilizationLevel = 5

    private val hlColors = IntArray(5)
    private var hlActiveColorIndex = 0
    private val hlSizes = FloatArray(3)
    private var hlActiveSizeIndex = 1
    private var hlStraight = false
    private var hlTextMode = false

    private val eraserSizes = FloatArray(3)
    private var eraserActiveSizeIndex = 1
    private var eraserPixel = true

    private var shapeType = ShapeType.RECT
    private var lassoShape = LassoShape.FREE

    private val laserColors = IntArray(5)
    private var laserActiveColorIndex = 0
    private val laserSizes = FloatArray(3)
    private var laserActiveSizeIndex = 1

    init {
        loadPrefs()
    }

    fun wire() {
        bindToolButton(R.id.penButton, "PEN") { applyPenSettings() }
        bindToolButton(R.id.highlighterButton, "HIGHLIGHTER") { applyHighlighterSettings() }
        bindToolButton(R.id.eraserButton, "ERASER") { applyEraserSettings() }
        bindToolButton(R.id.shapeButton, "SHAPE") { applyShapeSettings() }
        bindToolButton(config.lassoButtonId, "LASSO") { applyLassoSettings() }
        bindToolButton(R.id.tableButton, "TABLE") { applyTableSettings() }
        bindToolButton(R.id.textButton, "TEXT") { applyTextSettings() }
        bindToolButton(R.id.imageButton, "IMAGE") { applyImageSettings() }
        bindToolButton(R.id.laserButton, "LASER") { applyLaserSettings() }
        bindToolButton(R.id.tapeButton, "TAPE") { applyTapeSettings() }

        setupPenOptions()
        setupHighlighterOptions()
        setupEraserOptions()
        setupShapeOptions()
        setupLassoOptions()
        setupTextOptions()
        setupLaserOptions()
        setupTapeOptions()

        restoreLastTool()
    }

    private fun bindToolButton(buttonId: Int, toolId: String, apply: () -> Unit) {
        val button = activity.findViewById<ImageButton>(buttonId)
        button.setOnClickListener {
            if (activeToolButton != button) {
                setActiveTool(button)
                apply()
            } else {
                toggleOptions()
            }
        }
    }

    fun activeToolId(): String? = when (activeToolButton?.id) {
        R.id.penButton -> "PEN"
        R.id.highlighterButton -> "HIGHLIGHTER"
        R.id.eraserButton -> "ERASER"
        R.id.shapeButton -> "SHAPE"
        config.lassoButtonId -> "LASSO"
        R.id.tableButton -> "TABLE"
        R.id.textButton -> "TEXT"
        R.id.imageButton -> "IMAGE"
        R.id.laserButton -> "LASER"
        R.id.tapeButton -> "TAPE"
        else -> null
    }

    fun activateTool(toolId: String) {
        val id = when (toolId) {
            "PEN" -> R.id.penButton
            "HIGHLIGHTER" -> R.id.highlighterButton
            "ERASER" -> R.id.eraserButton
            "SHAPE" -> R.id.shapeButton
            "LASSO" -> config.lassoButtonId
            "TABLE" -> R.id.tableButton
            "TEXT" -> R.id.textButton
            "IMAGE" -> R.id.imageButton
            "LASER" -> R.id.laserButton
            "TAPE" -> R.id.tapeButton
            else -> return
        }
        val button = activity.findViewById<ImageButton>(id)
        if (activeToolButton == button) return
        setActiveTool(button)
        restoreActiveToolSettings()
    }

    fun restoreActiveToolSettings() {
        when (activeToolId()) {
            "PEN" -> applyPenSettings()
            "HIGHLIGHTER" -> applyHighlighterSettings()
            "ERASER" -> applyEraserSettings()
            "SHAPE" -> applyShapeSettings()
            "LASSO" -> applyLassoSettings()
            "TABLE" -> applyTableSettings()
            "TEXT" -> applyTextSettings()
            "IMAGE" -> applyImageSettings()
            "LASER" -> applyLaserSettings()
            "TAPE" -> applyTapeSettings()
        }
    }

    private fun setActiveTool(selectedButton: ImageButton) {
        val key = when (selectedButton.id) {
            R.id.penButton -> "PEN"
            R.id.highlighterButton -> "HIGHLIGHTER"
            R.id.eraserButton -> "ERASER"
            R.id.shapeButton -> "SHAPE"
            config.lassoButtonId -> "LASSO"
            R.id.tableButton -> "TABLE"
            R.id.textButton -> "TEXT"
            R.id.imageButton -> "IMAGE"
            R.id.laserButton -> "LASER"
            R.id.tapeButton -> "TAPE"
            else -> return
        }

        onToolActivated(key)
        activeToolButton?.isSelected = false
        selectedButton.isSelected = true
        activeToolButton = selectedButton
        showActiveToolOptions()
        prefs.edit().putString(key("LAST_ACTIVE_TOOL"), key).apply()
    }

    fun toggleOptions() {
        val scroll = activity.findViewById<View>(R.id.optionsScrollH)
        scroll.visibility = if (scroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun showActiveToolOptions() {
        val id = activeToolButton?.id ?: -1
        activity.findViewById<View>(R.id.penPropsStrip).visibility =
            if (id == R.id.penButton || id == R.id.tableButton) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.highlighterPropsStrip).visibility =
            if (id == R.id.highlighterButton) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.eraserPropsStrip).visibility =
            if (id == R.id.eraserButton) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.shapePropsStrip).visibility =
            if (id == R.id.shapeButton) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.lassoPropsStrip).visibility =
            if (id == config.lassoButtonId) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.textPropsStrip).visibility =
            if (id == R.id.textButton) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.imagePropsStrip).visibility =
            if (id == R.id.imageButton) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.laserPropsStrip).visibility =
            if (id == R.id.laserButton) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.tapePropsStrip).visibility =
            if (id == R.id.tapeButton) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.optionsScrollH).visibility =
            if (id != -1) View.VISIBLE else View.GONE
    }

    private fun restoreLastTool() {
        val last = prefs.getString(key("LAST_ACTIVE_TOOL"), "PEN") ?: "PEN"
        val button = activity.findViewById<ImageButton>(
            when (last) {
                "HIGHLIGHTER" -> R.id.highlighterButton
                "ERASER" -> R.id.eraserButton
                "SHAPE" -> R.id.shapeButton
                "LASSO" -> config.lassoButtonId
                "TABLE" -> R.id.tableButton
                "TEXT" -> R.id.textButton
                "IMAGE" -> R.id.imageButton
                "LASER" -> R.id.laserButton
                "TAPE" -> R.id.tapeButton
                else -> R.id.penButton
            }
        )
        setActiveTool(button)
        restoreActiveToolSettings()
    }

    fun eraserKind(): ToolKind = if (eraserPixel) ToolKind.PIXEL_ERASER else ToolKind.STROKE_ERASER

    fun currentPenLineStyle(): String = penLineStyle

    fun activePenColor(): Int = penColors[penActiveColorIndex]

    fun highlighterTextMode(): Boolean = hlTextMode

    fun applyPenSettings() {
        host.setTool(ToolKind.PEN)
        host.setBrushColor(penColors[penActiveColorIndex])
        host.setBrushSize(penSizes[penActiveSizeIndex])
        host.setStabilizationLevel(stabilizationLevel)
        host.setPenLineStyle(penLineStyle)
        host.setDrawingMode(true)
        updatePenIcon(penColors[penActiveColorIndex])
    }

    fun applyHighlighterSettings() {
        host.setTool(ToolKind.HIGHLIGHTER)
        host.setHighlighterColor(hlEffectiveColor(hlColors[hlActiveColorIndex]))
        host.setHighlighterSize(hlSizes[hlActiveSizeIndex])
        host.setHighlighterStraight(hlStraight)
        host.setDrawingMode(true)
    }

    fun applyEraserSettings() {
        host.setTool(eraserKind())
        host.setEraserSize(eraserSizes[eraserActiveSizeIndex])
        host.setDrawingMode(true)
    }

    fun applyShapeSettings() {
        host.setTool(ToolKind.SHAPE)
        host.setBrushColor(penColors[penActiveColorIndex])
        host.setBrushSize(penSizes[penActiveSizeIndex])
        host.setShapeType(shapeType)
        host.setPenLineStyle(penLineStyle)
        host.setDrawingMode(true)
    }

    fun applyLassoSettings() {
        host.setTool(ToolKind.LASSO)
        host.setLassoShape(lassoShape)
        host.setDrawingMode(true)
    }

    fun applyLaserSettings() {
        host.setTool(ToolKind.LASER)
        host.setBrushColor(laserColors[laserActiveColorIndex])
        host.setBrushSize(laserSizes[laserActiveSizeIndex])
        host.setDrawingMode(true)
    }

    fun applyTableSettings() {
        host.setTool(ToolKind.TABLE)
        host.setBrushColor(penColors[penActiveColorIndex])
        host.setBrushSize(penSizes[penActiveSizeIndex])
        host.setPenLineStyle(penLineStyle)
        host.setTableLineStyle(penLineStyle, penSizes[penActiveSizeIndex])
        val rows = prefs.getInt(key("TABLE_ROWS"), 3).coerceIn(1, 10)
        val cols = prefs.getInt(key("TABLE_COLS"), 3).coerceIn(1, 8)
        host.setTableGrid(rows, cols)
        host.setDrawingMode(true)
    }

    fun applyTextSettings() {
        host.setTool(ToolKind.TEXT)
        host.setBrushColor(penColors[penActiveColorIndex])
        host.setDrawingMode(true)
    }

    fun applyImageSettings() {

        host.setDrawingMode(false)
    }

    fun applyTapeSettings() {
        host.setTool(ToolKind.TAPE)

        host.setBrushColor(penColors[penActiveColorIndex])

        val pattern = prefs.getString(key("TAPE_PATTERN"), TapePattern.SOLID) ?: TapePattern.SOLID
        val width = prefs.getFloat(key("TAPE_WIDTH"), 32f).coerceAtLeast(6f)
        host.setTapePattern(pattern)
        host.setTapeWidth(width)
        host.setDrawingMode(true)
    }

    private fun setupPenOptions() {
        val swatches = listOf(
            activity.findViewById<View>(R.id.colorSwatch0),
            activity.findViewById<View>(R.id.colorSwatch1),
            activity.findViewById<View>(R.id.colorSwatch2),
            activity.findViewById<View>(R.id.colorSwatch3),
            activity.findViewById<View>(R.id.colorSwatch4)
        )
        swatches.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (penActiveColorIndex == i) {
                    ColorPickerDialog.show(activity, penColors[i], allowEyedropper = true) { picked ->
                        penColors[i] = picked
                        prefs.edit().putInt(key("PEN_COLOR_$i"), picked).apply()
                        applyDockColor(picked)
                        refreshPenSwatches(swatches)
                    }
                } else {
                    penActiveColorIndex = i
                    prefs.edit().putInt(key("PEN_COLOR_ACTIVE"), i).apply()
                    applyDockColor(penColors[i])
                    refreshPenSwatches(swatches)
                }
            }
        }
        refreshPenSwatches(swatches)

        val sizeButtons = listOf(
            activity.findViewById<ImageButton>(R.id.thinButton),
            activity.findViewById<ImageButton>(R.id.mediumButton),
            activity.findViewById<ImageButton>(R.id.thickButton)
        )
        sizeButtons.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (penActiveSizeIndex == i) {
                    showSizeSliderPopup(b, penSizes[i], 1f, 40f) { v ->
                        penSizes[i] = v
                        prefs.edit().putFloat(key("PEN_SIZE_$i"), v).apply()
                        host.setBrushSize(v)
                        refreshSizeHighlight(sizeButtons, penSizes, penActiveSizeIndex)
                    }
                } else {
                    penActiveSizeIndex = i
                    prefs.edit().putInt(key("PEN_SIZE_ACTIVE"), i).apply()
                    host.setBrushSize(penSizes[i])
                    refreshSizeHighlight(sizeButtons, penSizes, penActiveSizeIndex)
                }
            }
        }
        refreshSizeHighlight(sizeButtons, penSizes, penActiveSizeIndex)

        val styleButton = activity.findViewById<ImageButton>(R.id.lineStyleButton)
        updateLineStyleIcon(styleButton)
        styleButton.setOnClickListener { showLineStylePopup(styleButton) }
    }

    private fun setupHighlighterOptions() {
        val swatches = listOf(
            activity.findViewById<View>(R.id.hlColor0),
            activity.findViewById<View>(R.id.hlColor1),
            activity.findViewById<View>(R.id.hlColor2),
            activity.findViewById<View>(R.id.hlColor3),
            activity.findViewById<View>(R.id.hlColor4)
        )
        swatches.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (hlActiveColorIndex == i) {

                    ColorPickerDialog.show(activity, hlEffectiveColor(hlColors[i]), allowEyedropper = true) { picked ->
                        hlColors[i] = picked
                        prefs.edit().putInt(key("HL_COLOR_$i"), picked).apply()
                        host.setHighlighterColor(hlEffectiveColor(picked))
                        refreshHlSwatches(swatches)
                    }
                } else {
                    hlActiveColorIndex = i
                    prefs.edit().putInt(key("HL_COLOR_ACTIVE"), i).apply()
                    host.setHighlighterColor(hlEffectiveColor(hlColors[i]))
                    refreshHlSwatches(swatches)
                }
            }
        }
        refreshHlSwatches(swatches)

        val sizeButtons = listOf(
            activity.findViewById<ImageButton>(R.id.hlSize0),
            activity.findViewById<ImageButton>(R.id.hlSize1),
            activity.findViewById<ImageButton>(R.id.hlSize2)
        )
        sizeButtons.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (hlActiveSizeIndex == i) {
                    showSizeSliderPopup(b, hlSizes[i], 8f, 60f) { v ->
                        hlSizes[i] = v
                        prefs.edit().putFloat(key("HL_SIZE_$i"), v).apply()
                        host.setHighlighterSize(v)
                        refreshSizeHighlight(sizeButtons, hlSizes, hlActiveSizeIndex)
                    }
                } else {
                    hlActiveSizeIndex = i
                    prefs.edit().putInt(key("HL_SIZE_ACTIVE"), i).apply()
                    host.setHighlighterSize(hlSizes[i])
                    refreshSizeHighlight(sizeButtons, hlSizes, hlActiveSizeIndex)
                }
            }
        }
        refreshSizeHighlight(sizeButtons, hlSizes, hlActiveSizeIndex)

        val shapeButton = activity.findViewById<ImageButton>(R.id.hlShapeButton)
        updateHlShapeIcon(shapeButton)
        shapeButton.setOnClickListener { showHighlighterShapePopup(shapeButton) }
    }

    private fun setupEraserOptions() {
        val pixel = activity.findViewById<TextView>(R.id.eraserPixelButton)
        val stroke = activity.findViewById<TextView>(R.id.eraserStrokeButton)
        fun refresh() {
            pixel.isSelected = eraserPixel
            stroke.isSelected = !eraserPixel
        }
        pixel.setOnClickListener {
            eraserPixel = true
            prefs.edit().putString(key("ERASER_TYPE"), "PIXEL").apply()
            refresh()
            applyEraserSettings()
        }
        stroke.setOnClickListener {
            eraserPixel = false
            prefs.edit().putString(key("ERASER_TYPE"), "STROKE").apply()
            refresh()
            applyEraserSettings()
        }
        refresh()

        val sizeButtons = listOf(
            activity.findViewById<ImageButton>(R.id.eraserSize0),
            activity.findViewById<ImageButton>(R.id.eraserSize1),
            activity.findViewById<ImageButton>(R.id.eraserSize2)
        )
        sizeButtons.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (eraserActiveSizeIndex == i) {
                    showSizeSliderPopup(b, eraserSizes[i], 5f, 120f) { v ->
                        eraserSizes[i] = v
                        prefs.edit().putFloat(key("ERASER_SIZE_$i"), v).apply()
                        host.setEraserSize(v)
                        refreshSizeHighlight(sizeButtons, eraserSizes, eraserActiveSizeIndex)
                    }
                } else {
                    eraserActiveSizeIndex = i
                    prefs.edit().putInt(key("ERASER_SIZE_ACTIVE"), i).apply()
                    host.setEraserSize(eraserSizes[i])
                    refreshSizeHighlight(sizeButtons, eraserSizes, eraserActiveSizeIndex)
                }
            }
        }
        refreshSizeHighlight(sizeButtons, eraserSizes, eraserActiveSizeIndex)
    }

    private fun setupShapeOptions() {
        val buttons = listOf(
            activity.findViewById<ImageButton>(R.id.shapeRectButton),
            activity.findViewById<ImageButton>(R.id.shapeOvalButton),
            activity.findViewById<ImageButton>(R.id.shapeTriangleButton),
            activity.findViewById<ImageButton>(R.id.shapeLineButton),
            activity.findViewById<ImageButton>(R.id.shapeArrowButton)
        )
        val types = listOf(ShapeType.RECT, ShapeType.OVAL, ShapeType.TRIANGLE, ShapeType.LINE, ShapeType.ARROW)
        buttons.forEachIndexed { i, b ->
            b.setOnClickListener {
                shapeType = types[i]
                host.setShapeType(shapeType)
                prefs.edit().putString(key("SHAPE"), shapeType).apply()
                refreshSelectionHighlight(buttons, i)
            }
        }
        refreshSelectionHighlight(buttons, types.indexOf(shapeType).coerceAtLeast(0))
    }

    private fun setupLassoOptions() {
        val free = activity.findViewById<ImageButton>(R.id.lassoFreeButton)
        val rect = activity.findViewById<ImageButton>(R.id.lassoRectButton)
        val circle = activity.findViewById<ImageButton>(R.id.lassoCircleButton)
        fun refresh() {
            free.isSelected = lassoShape == LassoShape.FREE
            rect.isSelected = lassoShape == LassoShape.RECT
            circle.isSelected = lassoShape == LassoShape.CIRCLE
        }
        fun select(shape: String) {
            lassoShape = shape
            host.setLassoShape(shape)
            prefs.edit().putString(key("LASSO_SHAPE"), shape).apply()
            refresh()
        }
        free.setOnClickListener { select(LassoShape.FREE) }
        rect.setOnClickListener { select(LassoShape.RECT) }
        circle.setOnClickListener { select(LassoShape.CIRCLE) }
        refresh()
    }

    private fun setupTextOptions() {
        val sizes = floatArrayOf(18f, 26f, 36f)
        val ids = intArrayOf(R.id.textSizeSmall, R.id.textSizeMedium, R.id.textSizeLarge)
        val current = prefs.getFloat(key("TEXT_SIZE"), sizes[1])
        for (i in sizes.indices) {
            val btn = activity.findViewById<TextView>(ids[i])
            btn.isSelected = current == sizes[i]
            btn.setOnClickListener {
                prefs.edit().putFloat(key("TEXT_SIZE"), sizes[i]).apply()
                for (j in sizes.indices) {
                    activity.findViewById<TextView>(ids[j]).isSelected = i == j
                }
            }
        }
    }

    private fun setupLaserOptions() {
        val swatches = listOf(
            activity.findViewById<View>(R.id.laserColorSwatch0),
            activity.findViewById<View>(R.id.laserColorSwatch1),
            activity.findViewById<View>(R.id.laserColorSwatch2),
            activity.findViewById<View>(R.id.laserColorSwatch3),
            activity.findViewById<View>(R.id.laserColorSwatch4)
        )
        fun refresh() {
            swatches.forEachIndexed { i, v -> updateSwatch(v, laserColors[i], i == laserActiveColorIndex) }
        }
        swatches.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (laserActiveColorIndex == i) {
                    ColorPickerDialog.show(activity, laserColors[i], allowEyedropper = true) { picked ->
                        laserColors[i] = picked
                        prefs.edit().putInt(key("LASER_COLOR_$i"), picked).apply()
                        host.setBrushColor(picked)
                        refresh()
                    }
                } else {
                    laserActiveColorIndex = i
                    prefs.edit().putInt(key("LASER_COLOR_ACTIVE"), i).apply()
                    host.setBrushColor(laserColors[i])
                    refresh()
                }
            }
        }
        refresh()

        val sizeButtons = listOf(
            activity.findViewById<ImageButton>(R.id.laserThinButton),
            activity.findViewById<ImageButton>(R.id.laserMediumButton),
            activity.findViewById<ImageButton>(R.id.laserThickButton)
        )
        sizeButtons.forEachIndexed { i, b ->
            b.setOnClickListener {
                if (laserActiveSizeIndex == i) {

                    showSizeSliderPopup(b, laserSizes[i], 1f, 40f) { v ->
                        laserSizes[i] = v

                        val e = prefs.edit()
                        for (j in 0 until 3) e.putFloat(key("LASER_SIZE_$j"), laserSizes[j])
                        e.apply()
                        host.setBrushSize(v)
                        refreshSizeHighlight(sizeButtons, laserSizes, laserActiveSizeIndex)
                    }
                } else {
                    laserActiveSizeIndex = i
                    prefs.edit().putInt(key("LASER_SIZE"), i).apply()
                    host.setBrushSize(laserSizes[i])
                    refreshSizeHighlight(sizeButtons, laserSizes, laserActiveSizeIndex)
                }
            }
        }
        refreshSizeHighlight(sizeButtons, laserSizes, laserActiveSizeIndex)
    }

    private fun setupTapeOptions() {
        val swatches = listOf(
            activity.findViewById<View>(R.id.tapeColorSwatch0),
            activity.findViewById<View>(R.id.tapeColorSwatch1),
            activity.findViewById<View>(R.id.tapeColorSwatch2),
            activity.findViewById<View>(R.id.tapeColorSwatch3),
            activity.findViewById<View>(R.id.tapeColorSwatch4)
        )
        swatches.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (penActiveColorIndex == i) {
                    ColorPickerDialog.show(activity, penColors[i], allowEyedropper = true) { picked ->
                        penColors[i] = picked
                        prefs.edit().putInt(key("PEN_COLOR_$i"), picked).apply()
                        if (activeToolId() == "TAPE") host.setBrushColor(picked)
                        refreshPenSwatches(swatches)
                    }
                } else {
                    penActiveColorIndex = i
                    prefs.edit().putInt(key("PEN_COLOR_ACTIVE"), i).apply()
                    if (activeToolId() == "TAPE") host.setBrushColor(penColors[i])
                    refreshPenSwatches(swatches)
                }
            }
        }
        refreshPenSwatches(swatches)
    }

    private fun showLineStylePopup(anchor: ImageButton) {
        val view = activity.layoutInflater.inflate(R.layout.popup_line_style, null)
        val widthPx = (280 * activity.resources.displayMetrics.density).toInt()
        val popup = android.widget.PopupWindow(
            view, widthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)
        fun choose(style: String) {
            penLineStyle = style
            host.setPenLineStyle(style)
            prefs.edit().putString(key("PEN_LINE_STYLE"), style).apply()
            updateLineStyleIcon(anchor)
            popup.dismiss()
        }
        view.findViewById<View>(R.id.styleSolid).setOnClickListener { choose(PenLineStyle.SOLID) }
        view.findViewById<View>(R.id.styleDotted).setOnClickListener { choose(PenLineStyle.DOTTED) }
        view.findViewById<View>(R.id.styleDashed).setOnClickListener { choose(PenLineStyle.DASHED) }

        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.stabilizationSlider)
        val valueText = view.findViewById<TextView>(R.id.stabilizationValue)
        slider.value = stabilizationLevel.toFloat()
        valueText.text = "Level $stabilizationLevel"
        slider.addOnChangeListener { _, v, _ ->
            val lvl = v.toInt()
            stabilizationLevel = lvl
            host.setStabilizationLevel(lvl)
            prefs.edit().putInt(key("STABILIZATION"), lvl).apply()
            valueText.text = "Level $lvl"
        }
        popup.showAsDropDown(anchor, 0, 8)
    }

    private fun showHighlighterShapePopup(anchor: ImageButton) {
        val view = activity.layoutInflater.inflate(R.layout.popup_highlighter_line, null)
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
        val modeLabel = view.findViewById<View>(R.id.hlModeLabel)
        val modeDivider = view.findViewById<View>(R.id.hlModeDivider)

        fun refreshShape() {
            straight.isSelected = hlStraight
            free.isSelected = !hlStraight
        }
        fun refreshMode() {
            text.isSelected = hlTextMode
            normal.isSelected = !hlTextMode
        }
        refreshShape()
        refreshMode()

        free.setOnClickListener {
            hlStraight = false
            host.setHighlighterStraight(false)
            prefs.edit().putBoolean(key("HL_STRAIGHT"), false).apply()
            refreshShape()
            updateHlShapeIcon(anchor)
        }
        straight.setOnClickListener {
            hlStraight = true
            host.setHighlighterStraight(true)
            prefs.edit().putBoolean(key("HL_STRAIGHT"), true).apply()
            refreshShape()
            updateHlShapeIcon(anchor)
        }

        if (config.supportsHighlighterTextMode) {
            normal.setOnClickListener {
                hlTextMode = false
                prefs.edit().putBoolean(key("HL_TEXT_MODE"), false).apply()
                refreshMode()
            }
            text.setOnClickListener {
                hlTextMode = true
                prefs.edit().putBoolean(key("HL_TEXT_MODE"), true).apply()
                refreshMode()
            }
        } else {
            modeLabel.visibility = View.GONE
            modeDivider.visibility = View.GONE
            normal.visibility = View.GONE
            text.visibility = View.GONE
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
        val view = activity.layoutInflater.inflate(R.layout.popup_thickness, null)
        val widthPx = (300 * activity.resources.displayMetrics.density).toInt()
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

    private fun loadPrefs() {
        for (i in 0 until 5) penColors[i] = prefs.getInt(key("PEN_COLOR_$i"), config.penDefaultColors[i])
        penActiveColorIndex = prefs.getInt(key("PEN_COLOR_ACTIVE"), 0).coerceIn(0, 4)
        for (i in 0 until 3) penSizes[i] = prefs.getFloat(key("PEN_SIZE_$i"), config.penDefaultSizes[i])
        penActiveSizeIndex = prefs.getInt(key("PEN_SIZE_ACTIVE"), 1).coerceIn(0, 2)
        penLineStyle = prefs.getString(key("PEN_LINE_STYLE"), PenLineStyle.SOLID) ?: PenLineStyle.SOLID
        stabilizationLevel = prefs.getInt(key("STABILIZATION"), 5)

        for (i in 0 until 5) hlColors[i] = prefs.getInt(key("HL_COLOR_$i"), config.hlDefaultColors[i])
        hlActiveColorIndex = prefs.getInt(key("HL_COLOR_ACTIVE"), 0).coerceIn(0, 4)
        for (i in 0 until 3) hlSizes[i] = prefs.getFloat(key("HL_SIZE_$i"), config.hlDefaultSizes[i])
        hlActiveSizeIndex = prefs.getInt(key("HL_SIZE_ACTIVE"), 1).coerceIn(0, 2)
        hlStraight = prefs.getBoolean(key("HL_STRAIGHT"), false)
        hlTextMode = prefs.getBoolean(key("HL_TEXT_MODE"), false)

        for (i in 0 until 3) eraserSizes[i] = prefs.getFloat(key("ERASER_SIZE_$i"), config.eraserDefaultSizes[i])
        eraserActiveSizeIndex = prefs.getInt(key("ERASER_SIZE_ACTIVE"), 1).coerceIn(0, 2)
        eraserPixel = prefs.getString(key("ERASER_TYPE"), "PIXEL") != "STROKE"

        shapeType = prefs.getString(key("SHAPE"), ShapeType.RECT) ?: ShapeType.RECT
        lassoShape = prefs.getString(key("LASSO_SHAPE"), LassoShape.FREE) ?: LassoShape.FREE

        for (i in 0 until 5) laserColors[i] = prefs.getInt(key("LASER_COLOR_$i"), config.laserDefaultColors[i])
        laserActiveColorIndex = prefs.getInt(key("LASER_COLOR_ACTIVE"), 0).coerceIn(0, 4)
        laserActiveSizeIndex = prefs.getInt(key("LASER_SIZE"), 1).coerceIn(0, 2)
        if (prefs.getFloat(key("LASER_SIZE_0"), -1f) > 0f) {
            for (i in 0 until 3) laserSizes[i] = prefs.getFloat(key("LASER_SIZE_$i"), config.laserDefaultSizes[i])
        } else {

            for (i in 0 until 3) laserSizes[i] = penSizes[i]
        }
    }

    private fun hlEffectiveColor(raw: Int): Int = raw

    private fun applyDockColor(color: Int) {
        if (activeToolButton?.id == R.id.highlighterButton) {
            host.setHighlighterColor(hlEffectiveColor(color))
        } else {
            host.setBrushColor(color)
        }
        updatePenIcon(color)
    }

    private fun updatePenIcon(color: Int) {
        activity.findViewById<ImageButton>(R.id.penButton).imageTintList = ColorStateList.valueOf(color)
    }

    private fun updateLineStyleIcon(btn: ImageButton) {
        btn.setImageResource(
            when (penLineStyle) {
                PenLineStyle.DOTTED -> R.drawable.ic_line_dotted
                PenLineStyle.DASHED -> R.drawable.ic_line_dashed
                else -> R.drawable.ic_line_solid
            }
        )
    }

    private fun updateHlShapeIcon(btn: ImageButton) {
        btn.setImageResource(
            if (hlStraight) R.drawable.ic_line_straight else R.drawable.ic_line_wavy
        )
    }

    private fun refreshPenSwatches(swatches: List<View>) {
        swatches.forEachIndexed { i, v -> updateSwatch(v, penColors[i], i == penActiveColorIndex) }
        updatePenIcon(penColors[penActiveColorIndex])
    }

    private fun refreshHlSwatches(swatches: List<View>) {
        swatches.forEachIndexed { i, v -> updateSwatch(v, hlColors[i], i == hlActiveColorIndex) }
    }

    private fun refreshSizeHighlight(buttons: List<ImageButton>, sizes: FloatArray, activeIndex: Int) {
        val max = maxOf(sizes[0], sizes[1], sizes[2])
        buttons.forEachIndexed { i, b ->
            b.isSelected = i == activeIndex
            applySizeIcon(b, sizes[i], max)
        }
    }

    private fun applySizeIcon(btn: ImageButton, size: Float, maxSize: Float) {
        val density = activity.resources.displayMetrics.density
        val iconPx = (24 * density).toInt().coerceAtLeast(4)
        val frac = if (maxSize > 0f) (size / maxSize).coerceIn(0f, 1f) else 1f

        val diameter = maxOf(frac * iconPx * 0.9f, 3f)

        val bmp = android.graphics.Bitmap.createBitmap(iconPx, iconPx, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.density = activity.resources.displayMetrics.densityDpi
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        val radius = diameter / 2f
        val h = iconPx.toFloat()
        c.drawCircle(h / 2f, h / 2f, radius, p)
        btn.setImageDrawable(android.graphics.drawable.BitmapDrawable(activity.resources, bmp))
    }

    private fun refreshSelectionHighlight(buttons: List<ImageButton>, activeIndex: Int) {
        buttons.forEachIndexed { i, b -> b.isSelected = i == activeIndex }
    }

    private fun updateSwatch(view: View, color: Int, selected: Boolean) {
        val bg = view.background as LayerDrawable
        (bg.findDrawableByLayerId(R.id.color_shape) as GradientDrawable).setColor(color)
        val stroke = bg.findDrawableByLayerId(R.id.swatch_border) as GradientDrawable
        if (selected) {
            val primary = com.google.android.material.color.MaterialColors.getColor(
                view, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#2196F3")
            )
            stroke.setStroke(8, androidx.core.graphics.ColorUtils.blendARGB(primary, Color.BLACK, 0.3f))
        } else {
            stroke.setStroke(2, Color.parseColor("#BDBDBD"))
        }
    }
}
