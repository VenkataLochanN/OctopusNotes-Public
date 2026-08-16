package com.lochan.octopusnotes

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

class ToolSettingsManager(private val context: Context, private val drawingView: DrawingView) {

    private val PREFS_NAME = "OctopusNotesPrefs"
    private val DEFAULT_COLORS = intArrayOf(
        Color.WHITE, Color.BLACK, Color.BLUE, Color.RED, Color.GREEN, Color.YELLOW
    )

    var lastPenColorInt = Color.BLACK
    var lastPenSize = 10f
    var lastEraserSize = 50f
    var singleFingerAction: DrawingView.FingerAction = DrawingView.FingerAction.SCROLL
    var currentEraserType = DrawingView.Tool.PIXEL_ERASER
    private var activeColorSlotIndex = 1

    var lastStabilizationLevel = 5

    var stylusSettings: StylusSettings = StylusSettings()

    private var toolSizeBaseWidth = 0f

    init {
        loadPrefs()
        loadHighlighterPrefs()
    }

    fun applyPenSettings() {
        drawingView.setTool(DrawingView.Tool.PEN)
        drawingView.setBrushColor(lastPenColorInt)
        drawingView.setBrushSize(lastPenSize)
        drawingView.setStabilizationLevel(lastStabilizationLevel)
        drawingView.setDrawingMode(true)
    }

    fun applyEraserSettings() {
        drawingView.setTool(currentEraserType)
        drawingView.setBrushSize(lastEraserSize)
        drawingView.setDrawingMode(true)
    }

    fun showGestureOptionsPopup(anchorView: View) {
        StylusGestureDialog(
            context,
            getFingerAction = { singleFingerAction.name },
            setFingerAction = { action ->
                singleFingerAction = DrawingView.FingerAction.valueOf(action)
                drawingView.singleFingerAction = singleFingerAction
                anchorView.isSelected = singleFingerAction == DrawingView.FingerAction.DRAW
                savePrefs()
            },
            getSettings = { stylusSettings },
            setSettings = { s ->
                stylusSettings = s
                drawingView.stylusLongHoldEnabled = s.longHold == StylusLongHold.ERASER
                drawingView.stylusLongPressEraseEnabled = s.longPressErase
                savePrefs()
            }
        ).show()
    }

    private fun savePrefs() {
        val editor = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        editor.putFloat("PEN_SIZE", lastPenSize)
            .putInt("STABILIZATION_LEVEL", lastStabilizationLevel)
            .putFloat("ERASER_SIZE", lastEraserSize)
            .putString("ERASER_TYPE", currentEraserType.name)
            .putString("SINGLE_FINGER_ACTION", singleFingerAction.name)
            .putFloat("TOOL_SIZE_BASE_WIDTH", toolSizeBaseWidth)
        stylusSettings.save(editor)
        editor.apply()
    }

    private fun loadPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lastPenSize = prefs.getFloat("PEN_SIZE", 10f)
        lastStabilizationLevel = prefs.getInt("STABILIZATION_LEVEL", 5)
        lastEraserSize = prefs.getFloat("ERASER_SIZE", 50f)
        toolSizeBaseWidth = prefs.getFloat("TOOL_SIZE_BASE_WIDTH", 0f)
        val typeName = prefs.getString("ERASER_TYPE", DrawingView.Tool.PIXEL_ERASER.name)
        currentEraserType = try {
            DrawingView.Tool.valueOf(typeName!!)
        } catch (e: Exception) { DrawingView.Tool.PIXEL_ERASER }

        val colors = getSavedColors()
        lastPenColorInt = colors[activeColorSlotIndex]

        val fingerName = prefs.getString("SINGLE_FINGER_ACTION", DrawingView.FingerAction.SCROLL.name)
        singleFingerAction = try {
            DrawingView.FingerAction.valueOf(fingerName!!)
        } catch (e: Exception) { DrawingView.FingerAction.SCROLL }
        drawingView.singleFingerAction = singleFingerAction

        stylusSettings = StylusSettings.load(prefs)
        drawingView.stylusLongHoldEnabled = stylusSettings.longHold == StylusLongHold.ERASER
        drawingView.stylusLongPressEraseEnabled = stylusSettings.longPressErase
    }

    fun toolSizeBaseWidth(): Float = toolSizeBaseWidth

    fun scaleToolSizes(factor: Float, newBaseWidth: Float) {
        if (newBaseWidth > 0f) toolSizeBaseWidth = newBaseWidth
        if (factor <= 0f || factor == 1f) {
            savePrefs(); saveHighlighterPrefs()
            return
        }
        lastPenSize *= factor
        lastEraserSize *= factor
        highlighterSize *= factor
        savePrefs()
        saveHighlighterPrefs()
    }

    private fun getSavedColors(): IntArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedDetails = IntArray(6)
        for (i in 0 until 6) {
            savedDetails[i] = prefs.getInt("PEN_SLOT_$i", DEFAULT_COLORS[i])
        }
        return savedDetails
    }

    var highlighterTextMode = false; private set
    private var highlighterStraight = false
    private var highlighterSize = 28f
    private var highlighterColorInt = android.graphics.Color.parseColor("#66FFEB00")

    private fun loadHighlighterPrefs() {
        val p = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        highlighterTextMode = p.getBoolean("HL_TEXT_MODE", false)
        highlighterStraight = p.getBoolean("HL_STRAIGHT", false)
        highlighterSize = p.getFloat("HL_SIZE", 28f)
        highlighterColorInt = p.getInt("HL_COLOR", android.graphics.Color.parseColor("#66FFEB00"))
    }

    private fun saveHighlighterPrefs() {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("HL_TEXT_MODE", highlighterTextMode)
            .putBoolean("HL_STRAIGHT", highlighterStraight)
            .putFloat("HL_SIZE", highlighterSize)
            .putInt("HL_COLOR", highlighterColorInt)
            .putFloat("TOOL_SIZE_BASE_WIDTH", toolSizeBaseWidth)
            .apply()
    }

    fun applyHighlighterSettings() {
        loadHighlighterPrefs()
        drawingView.setTool(DrawingView.Tool.HIGHLIGHTER)
        drawingView.setHighlighterColor(highlighterColorInt)
        drawingView.setHighlighterSize(highlighterSize)
        drawingView.setHighlighterStraight(highlighterStraight)
        drawingView.setDrawingMode(true)
    }

    fun isHighlighterStraight(): Boolean = highlighterStraight
    fun getHighlighterColor(): Int = highlighterColorInt
    fun getHighlighterSize(): Float = highlighterSize

    fun setHighlighterStraightPref(straight: Boolean) {
        highlighterStraight = straight
        drawingView.setHighlighterStraight(straight)
        saveHighlighterPrefs()
    }

    fun setHighlighterTextModePref(textMode: Boolean) {
        highlighterTextMode = textMode
        saveHighlighterPrefs()
    }

    fun setHighlighterColorPref(raw: Int) {
        highlighterColorInt = raw
        drawingView.setHighlighterColor(highlighterColorInt)
        saveHighlighterPrefs()
    }

    fun setHighlighterSizePref(size: Float) {
        highlighterSize = size.coerceAtLeast(4f)
        drawingView.setHighlighterSize(highlighterSize)
        saveHighlighterPrefs()
    }
}