package com.lochan.octopusnotes

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

enum class StylusAction { LAST_USED, ERASER, HIGHLIGHTER, PEN, LASER, LASSO, DISABLED }

enum class StylusLongHold { ERASER, DISABLED }

enum class StylusPenType { S_PEN, OTHER }

enum class StylusBarrelButton { PRIMARY, SECONDARY }

data class StylusSettings(
    val primaryAction: StylusAction = StylusAction.DISABLED,
    val secondaryAction: StylusAction = StylusAction.DISABLED,
    val longHold: StylusLongHold = StylusLongHold.ERASER,
    val penType: StylusPenType = StylusPenType.S_PEN,
    val longPressErase: Boolean = false
) {
    companion object {
        private const val PREFS_PRIMARY = "STYLUS_PRIMARY_ACTION"
        private const val PREFS_SECONDARY = "STYLUS_SECONDARY_ACTION"
        private const val PREFS_LONG_HOLD = "STYLUS_LONG_HOLD_ACTION"
        private const val PREFS_PEN_TYPE = "STYLUS_PEN_TYPE"

        const val PREFS_LONG_PRESS_ERASE = "STYLUS_LONG_PRESS_ERASE"

        fun load(prefs: android.content.SharedPreferences): StylusSettings {
            fun action(name: String?): StylusAction =
                StylusAction.values().firstOrNull { it.name == name } ?: StylusAction.DISABLED
            fun hold(name: String?): StylusLongHold =
                StylusLongHold.values().firstOrNull { it.name == name } ?: StylusLongHold.ERASER
            fun pen(name: String?): StylusPenType =
                StylusPenType.values().firstOrNull { it.name == name } ?: StylusPenType.S_PEN
            return StylusSettings(
                primaryAction = action(prefs.getString(PREFS_PRIMARY, null)),
                secondaryAction = action(prefs.getString(PREFS_SECONDARY, null)),
                longHold = hold(prefs.getString(PREFS_LONG_HOLD, null)),
                penType = pen(prefs.getString(PREFS_PEN_TYPE, null)),
                longPressErase = prefs.getBoolean(PREFS_LONG_PRESS_ERASE, false)
            )
        }
    }

    fun save(editor: android.content.SharedPreferences.Editor) {
        editor.putString(PREFS_PRIMARY, primaryAction.name)
        editor.putString(PREFS_SECONDARY, secondaryAction.name)
        editor.putString(PREFS_LONG_HOLD, longHold.name)
        editor.putString(PREFS_PEN_TYPE, penType.name)
        editor.putBoolean(PREFS_LONG_PRESS_ERASE, longPressErase)
    }
}

class StylusToolSwitcher(
    private val activateTool: (toolId: String) -> Unit,
    private val currentToolId: () -> String?
) {

    private var lastUsedToolId: String? = null

    fun onToolActivated(toolId: String) {
        val current = currentToolId()
        if (current != null && current != toolId) {
            lastUsedToolId = current
        }
    }

    fun apply(action: StylusAction) {
        when (action) {
            StylusAction.DISABLED -> {}
            StylusAction.LAST_USED -> switchTo(lastUsedToolId ?: "PEN")
            else -> action.toolId()?.let(::switchTo)
        }
    }

    private fun switchTo(toolId: String) {
        val current = currentToolId()
        if (current == toolId) {

            val last = lastUsedToolId ?: "PEN"
            if (last != current) {
                lastUsedToolId = current
                activateTool(last)
            }
            return
        }
        if (current != null) lastUsedToolId = current
        activateTool(toolId)
    }

    private fun StylusAction.toolId(): String? = when (this) {
        StylusAction.PEN -> "PEN"
        StylusAction.ERASER -> "ERASER"
        StylusAction.HIGHLIGHTER -> "HIGHLIGHTER"
        StylusAction.LASER -> "LASER"
        StylusAction.LASSO -> "LASSO"
        else -> null
    }
}

class StylusBarrelInput(

    private val onPrimaryQuickPress: () -> Unit,

    private val onSecondaryPress: () -> Unit,

    private val onBarrelChanged: (pressed: Boolean) -> Unit,

    private val onLongHoldFired: () -> Unit
) {

    var longHoldEnabled = true

    var longPressEraseEnabled = false

    var longPressSlopPx: Float = 24f

    val isBarrelActive: Boolean get() = barrelDown

    private var barrelDown = false
    private var primaryPressed = false
    private var secondaryPressed = false
    private var longHoldArmed = false
    private var longHoldFired = false

    private var longPressArmed = false

    private var longPressFired = false

    private var longPressAnchorX = 0f
    private var longPressAnchorY = 0f

    private var hoverDown = false
    private var hoverPrimary = false

    private var pressConsumedByTouch = false

    private var hoverHoldPending = false

    private var pressContextIsHover = false

    private var keyPrimaryDown = false
    private var keySecondaryDown = false

    private var keySecondaryFired = false

    private var secondaryFiredOnHover = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val LONG_HOLD_MS = 350L
    private val LONG_PRESS_ERASE_MS = 450L

    fun armLongPressErase(x: Float, y: Float) {
        if (!longPressEraseEnabled || primaryPressed || secondaryPressed || barrelDown) return
        longPressArmed = true
        longPressFired = false
        longPressAnchorX = x
        longPressAnchorY = y
        handler.removeCallbacks(longPressRunnable)
        handler.postDelayed(longPressRunnable, LONG_PRESS_ERASE_MS)
    }

    fun onStylusMove(x: Float, y: Float) {
        if (!longPressArmed || longPressFired) return
        if (kotlin.math.hypot(x - longPressAnchorX, y - longPressAnchorY) > longPressSlopPx) {
            longPressArmed = false
            handler.removeCallbacks(longPressRunnable)
        }
    }

    fun cancelLongPressErase() {
        longPressArmed = false
        longPressFired = false
        handler.removeCallbacks(longPressRunnable)
    }

    fun onStylusDown(buttonState: Int, toolType: Int) {
        val (bPrimary, bSecondary) = buttonsPressed(buttonState, toolType)

        val primary = bPrimary || keyPrimaryDown
        val secondary = bSecondary || keySecondaryDown
        primaryPressed = primary
        secondaryPressed = secondary

        longPressArmed = false
        longPressFired = false
        handler.removeCallbacks(longPressRunnable)
        longHoldFired = false

        if (primary) pressConsumedByTouch = true
        val pendingFromHover = hoverHoldPending
        hoverHoldPending = false
        hoverDown = false
        hoverPrimary = false

        if (secondary && !primary && !keySecondaryFired && !secondaryFiredOnHover) onSecondaryPress()
        secondaryFiredOnHover = false
        if (pendingFromHover && primary) {

            longHoldFired = true
            engage()
            handler.removeCallbacks(runnable)
            longHoldArmed = false
        } else if (primary && longHoldEnabled) {

            engage()
            if (longHoldArmed) {

                pressContextIsHover = false
            } else {
                longHoldArmed = true
                pressContextIsHover = false
                handler.removeCallbacks(runnable)
                handler.postDelayed(runnable, LONG_HOLD_MS)
            }
        } else if (!primary) {

            handler.removeCallbacks(runnable)
            longHoldArmed = false
        }
        if (barrelDown && !primary && !secondary) release()
    }

    fun onHover(buttonState: Int, toolType: Int, hoverActive: Boolean) {
        if (!hoverActive) {

            hoverDown = false
            hoverPrimary = false
            pressConsumedByTouch = false
            secondaryFiredOnHover = false
            if (barrelDown) release()
            return
        }
        val (primary, secondary) = buttonsPressed(buttonState, toolType)
        if (primary || secondary) {
            if (!hoverDown) {
                hoverDown = true
                hoverPrimary = primary
                if (primary && longHoldEnabled && !longHoldArmed) {
                    longHoldArmed = true
                    pressContextIsHover = true
                    handler.removeCallbacks(runnable)
                    handler.postDelayed(runnable, LONG_HOLD_MS)
                }
                if (secondary && !primary) {
                    onSecondaryPress()
                    secondaryFiredOnHover = true
                }
            }
        } else if (hoverDown) {

            finishHoverPress()
        }
    }

    fun onStrokeEnd(buttonState: Int = 0, toolType: Int = 0) {
        handler.removeCallbacks(runnable)
        handler.removeCallbacks(longPressRunnable)
        longHoldArmed = false
        longPressArmed = false
        longPressFired = false
        val (primary, _) = buttonsPressed(buttonState, toolType)
        if (!primary) pressConsumedByTouch = false
        release()
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacks(longPressRunnable)
        longHoldArmed = false
        longPressArmed = false
        longPressFired = false
        release()
    }

    fun engage() {
        if (barrelDown) return
        barrelDown = true
        onBarrelChanged(true)
    }

    fun onStylusKey(event: KeyEvent, touching: Boolean): Boolean {
        val button = buttonForKeyCode(event.keyCode) ?: return false
        if (event.repeatCount > 0) return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyButtonDown(button, touching)
            KeyEvent.ACTION_UP -> onKeyButtonUp(button)
            else -> {}
        }
        return true
    }

    private fun onKeyButtonDown(button: StylusBarrelButton, touching: Boolean) {
        when (button) {
            StylusBarrelButton.PRIMARY -> {
                if (keyPrimaryDown) return
                keyPrimaryDown = true
                longHoldFired = false
                if (touching) {

                    pressConsumedByTouch = true
                    if (longHoldEnabled) {
                        onLongHoldFired()
                        engage()
                        longHoldArmed = true
                        pressContextIsHover = false
                        handler.removeCallbacks(runnable)
                        handler.postDelayed(runnable, LONG_HOLD_MS)
                    }
                } else {

                    hoverDown = true
                    hoverPrimary = true
                    if (longHoldEnabled && !longHoldArmed) {
                        longHoldArmed = true
                        pressContextIsHover = true
                        handler.removeCallbacks(runnable)
                        handler.postDelayed(runnable, LONG_HOLD_MS)
                    }
                }
            }
            StylusBarrelButton.SECONDARY -> {
                if (keySecondaryDown) return
                keySecondaryDown = true

                if (!keyPrimaryDown && !hoverDown) {
                    keySecondaryFired = true
                    onSecondaryPress()
                }
                if (!touching) {
                    hoverDown = true
                    hoverPrimary = false
                }
            }
        }
    }

    private fun onKeyButtonUp(button: StylusBarrelButton) {
        when (button) {
            StylusBarrelButton.PRIMARY -> {
                if (!keyPrimaryDown) return
                keyPrimaryDown = false
                if (hoverDown && hoverPrimary) {

                    finishHoverPress()
                } else {

                    handler.removeCallbacks(runnable)
                    longHoldArmed = false
                    longHoldFired = false
                    hoverHoldPending = false
                    hoverDown = false
                    hoverPrimary = false
                    pressConsumedByTouch = false
                }
            }
            StylusBarrelButton.SECONDARY -> {
                if (!keySecondaryDown) return
                keySecondaryDown = false
                keySecondaryFired = false
                if (hoverDown && !hoverPrimary) {
                    finishHoverPress()
                } else {
                    hoverDown = false
                    hoverPrimary = false
                }
            }
        }
    }

    private fun finishHoverPress() {
        val wasPrimary = hoverPrimary
        val wasHold = longHoldFired
        val consumedByTouch = pressConsumedByTouch

        pressConsumedByTouch = false
        secondaryFiredOnHover = false
        longHoldFired = false
        hoverDown = false
        hoverPrimary = false
        hoverHoldPending = false
        handler.removeCallbacks(runnable)
        longHoldArmed = false

        if (wasPrimary && !wasHold && !consumedByTouch) onPrimaryQuickPress()

        if (barrelDown) release()
    }

    private fun release() {
        if (barrelDown) {
            barrelDown = false
            onBarrelChanged(false)
        }
        primaryPressed = false

        longHoldFired = false
    }

    private val runnable = Runnable {
        if (!longHoldArmed) return@Runnable
        longHoldArmed = false
        longHoldFired = true
        if (pressContextIsHover) {

            hoverHoldPending = true
        } else if (!barrelDown) {

            onLongHoldFired()
            engage()
        }

    }

    private val longPressRunnable = Runnable {
        if (!longPressArmed || longPressFired) return@Runnable
        longPressArmed = false
        longPressFired = true

        onLongHoldFired()
        engage()
    }

    companion object {

        fun buttonForKeyCode(keyCode: Int): StylusBarrelButton? = when (keyCode) {
            308 -> StylusBarrelButton.PRIMARY
            309 -> StylusBarrelButton.SECONDARY
            310 -> StylusBarrelButton.SECONDARY
            311 -> StylusBarrelButton.SECONDARY
            KeyEvent.KEYCODE_PAGE_UP -> StylusBarrelButton.PRIMARY
            KeyEvent.KEYCODE_PAGE_DOWN -> StylusBarrelButton.SECONDARY
            else -> null
        }

        private fun buttonsPressed(buttonState: Int, toolType: Int): Pair<Boolean, Boolean> {
            val primary = (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            val secondary = (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
            if (!primary && !secondary) {

                if ((buttonState and MotionEvent.BUTTON_TERTIARY) != 0) return false to true
                if ((buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_PRIMARY)) != 0) return true to false

                if ((buttonState and MotionEvent.BUTTON_FORWARD) != 0) return false to true
                if ((buttonState and MotionEvent.BUTTON_BACK) != 0) return true to false

                if (toolType == MotionEvent.TOOL_TYPE_ERASER) return false to true
            }
            return primary to secondary
        }
    }
}

fun MotionEvent.isStylusEvent(): Boolean {
    val toolType = getToolType(0)
    return toolType == MotionEvent.TOOL_TYPE_STYLUS ||
        toolType == MotionEvent.TOOL_TYPE_ERASER ||
        (toolType == MotionEvent.TOOL_TYPE_MOUSE &&
            (source and InputDevice.SOURCE_STYLUS) != 0)
}

class StylusGestureDialog(
    private val context: Context,
    private val getFingerAction: () -> String,
    private val setFingerAction: (String) -> Unit,
    private val getSettings: () -> StylusSettings,
    private val setSettings: (StylusSettings) -> Unit
) {
    fun show() {
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_gesture_options, null)

        val group = popupView.findViewById<MaterialButtonToggleGroup>(R.id.singleFingerGroup)
        when (getFingerAction()) {
            "IGNORED" -> group.check(R.id.segFingerIgnored)
            "DRAW" -> group.check(R.id.segFingerDraw)
            else -> group.check(R.id.segFingerScroll)
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val action = when (checkedId) {
                R.id.segFingerIgnored -> "IGNORED"
                R.id.segFingerDraw -> "DRAW"
                else -> "SCROLL"
            }
            if (action != getFingerAction()) setFingerAction(action)
        }

        var settings = getSettings()

        val penGroup = popupView.findViewById<MaterialButtonToggleGroup>(R.id.stylusPenGroup)
        when (settings.penType) {
            StylusPenType.OTHER -> penGroup.check(R.id.segPenOther)
            StylusPenType.S_PEN -> penGroup.check(R.id.segPenSPen)
        }

        val secondarySection = popupView.findViewById<View>(R.id.stylusSecondarySection)
        secondarySection.visibility =
            if (settings.penType == StylusPenType.OTHER) View.VISIBLE else View.GONE

        fun wireActionPill(pill: TextView, title: String, current: StylusAction, onPicked: (Int) -> Unit) {
            styleActionPill(pill, current)
            pill.setOnClickListener { showActionPicker(title, current, onPicked) }
        }
        wireActionPill(
            popupView.findViewById(R.id.stylusPrimaryButton),
            context.getString(R.string.stylus_primary), settings.primaryAction
        ) { position ->
            StylusAction.values().getOrNull(position)?.let {
                settings = settings.copy(primaryAction = it)
                setSettings(settings)
                styleActionPill(popupView.findViewById(R.id.stylusPrimaryButton), it)
            }
        }
        wireActionPill(
            popupView.findViewById(R.id.stylusSecondaryButton),
            context.getString(R.string.stylus_secondary), settings.secondaryAction
        ) { position ->
            StylusAction.values().getOrNull(position)?.let {
                settings = settings.copy(secondaryAction = it)
                setSettings(settings)
                styleActionPill(popupView.findViewById(R.id.stylusSecondaryButton), it)
            }
        }

        val longHoldSwitch = popupView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.stylusLongHoldSwitch
        )
        longHoldSwitch.isChecked = settings.longHold == StylusLongHold.ERASER
        longHoldSwitch.setOnCheckedChangeListener { _, checked ->
            val hold = if (checked) StylusLongHold.ERASER else StylusLongHold.DISABLED
            if (hold != settings.longHold) {
                settings = settings.copy(longHold = hold)
                setSettings(settings)
            }
        }

        penGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val type = when (checkedId) {
                R.id.segPenOther -> StylusPenType.OTHER
                else -> StylusPenType.S_PEN
            }
            if (type != settings.penType) {
                settings = settings.copy(penType = type)
                setSettings(settings)

                secondarySection.visibility =
                    if (type == StylusPenType.OTHER) View.VISIBLE else View.GONE
            }
        }

        MaterialAlertDialogBuilder(context)
            .setView(popupView)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun actionIcon(action: StylusAction): Int = when (action) {
        StylusAction.LAST_USED -> R.drawable.ic_undo
        StylusAction.ERASER -> R.drawable.ic_eraser
        StylusAction.HIGHLIGHTER -> R.drawable.ic_highlighter
        StylusAction.PEN -> R.drawable.ic_pen
        StylusAction.LASER -> R.drawable.ic_laser
        StylusAction.LASSO -> R.drawable.ic_lasso
        StylusAction.DISABLED -> R.drawable.ic_close
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun styleActionPill(pill: TextView, action: StylusAction) {
        val labels = context.resources.getStringArray(R.array.stylus_action_entries)
        val tint = MaterialColors.getColor(
            pill, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.GRAY
        )
        val icon = context.getDrawable(actionIcon(action))
        val chevron = context.getDrawable(R.drawable.ic_arrow_drop_down)
        icon?.setTint(tint)
        chevron?.setTint(tint)
        pill.text = labels[action.ordinal]
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, chevron, null)
        pill.setCompoundDrawablePadding(dp(8))
    }

    private fun showActionPicker(title: String, initial: StylusAction, onPicked: (Int) -> Unit) {
        val labels = context.resources.getStringArray(R.array.stylus_action_entries)
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null
        StylusAction.values().forEachIndexed { index, action ->
            val row = LayoutInflater.from(context).inflate(R.layout.item_action_picker_row, container, false)
            row.findViewById<ImageView>(R.id.actionRowIcon).setImageResource(actionIcon(action))
            row.findViewById<TextView>(R.id.actionRowLabel).text = labels[index]
            row.findViewById<ImageView>(R.id.actionRowCheck).visibility =
                if (index == initial.ordinal) View.VISIBLE else View.GONE
            row.setOnClickListener {
                dialog?.dismiss()
                if (index != initial.ordinal) onPicked(index)
            }
            container.addView(row)
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(container)
            .create()
            .apply { show() }
    }
}
