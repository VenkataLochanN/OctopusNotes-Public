package com.lochan.octopusnotes

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The app's colour picker: a fixed palette ("Grid"), a hue/saturation/value area
 * ("Spectrum"), hex + RGB entry, and an eyedropper that samples the page underneath.
 *
 * Colour is held as HSV for the lifetime of the dialog. Round-tripping through a packed
 * sRGB int on every drag quantises the value and makes the selector creep, most visibly
 * in the dark and desaturated corners of the spectrum.
 */
class ColorPickerDialog private constructor(
    private val activity: Activity,
    initialColor: Int,
    private val allowEyedropper: Boolean,
    private val startOnSpectrum: Boolean,
    private val onPicked: (Int) -> Unit
) {

    private val hsv = FloatArray(3).also { Color.colorToHSV(initialColor, it) }

    /** Set while syncing widgets, so their listeners don't feed the change back in. */
    private var syncing = false

    private lateinit var root: View
    private lateinit var preview: View
    private lateinit var grid: ColorPaletteGridView
    private lateinit var spectrumPane: LinearLayout
    private lateinit var satVal: SaturationValueView
    private lateinit var hueBar: HueBarView
    private lateinit var hexField: EditText
    private lateinit var redField: EditText
    private lateinit var greenField: EditText
    private lateinit var blueField: EditText

    private val color: Int get() = Color.HSVToColor(hsv)

    private fun build() {
        root = activity.layoutInflater.inflate(R.layout.dialog_color_picker, null)
        preview = root.findViewById(R.id.colorPreview)
        grid = root.findViewById(R.id.paletteGrid)
        spectrumPane = root.findViewById(R.id.spectrumPane)
        satVal = root.findViewById(R.id.saturationValue)
        hueBar = root.findViewById(R.id.hueBar)
        hexField = root.findViewById(R.id.hexField)
        redField = root.findViewById(R.id.redField)
        greenField = root.findViewById(R.id.greenField)
        blueField = root.findViewById(R.id.blueField)

        val toggle = root.findViewById<MaterialButtonToggleGroup>(R.id.modeToggle)
        toggle.check(if (startOnSpectrum) R.id.tabSpectrum else R.id.tabGrid)
        showPane(startOnSpectrum)
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) showPane(checkedId == R.id.tabSpectrum)
        }

        grid.onPick = { picked ->
            setColor(picked, keepHueWhenGrey = false)
            syncWidgets(exclude = null)
        }
        satVal.onChange = { s, v ->
            hsv[1] = s
            hsv[2] = v
            syncWidgets(exclude = satVal)
        }
        hueBar.onChange = { h ->
            hsv[0] = h
            syncWidgets(exclude = hueBar)
        }

        watchHex()
        watchRgb()

        val eyedropper = root.findViewById<ImageButton>(R.id.eyedropperButton)
        eyedropper.isVisible(allowEyedropper)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> onPicked(color) }
            .create()

        eyedropper.setOnClickListener {
            // Hand off to the overlay; the picker comes back with whatever was sampled.
            dialog.dismiss()
            startEyedropper(activity, color) { sampled ->
                ColorPickerDialog(
                    activity, sampled ?: color, allowEyedropper,
                    startOnSpectrum = spectrumPane.visibility == View.VISIBLE,
                    onPicked = onPicked
                ).build()
            }
        }

        dialog.show()
        syncWidgets(exclude = null)
    }

    private fun View.isVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showPane(spectrum: Boolean) {
        grid.isVisible(!spectrum)
        spectrumPane.isVisible(spectrum)
    }

    /**
     * Adopts [c]. Greys carry no meaningful hue, so with [keepHueWhenGrey] the hue bar
     * stays where the user left it instead of snapping back to red.
     */
    private fun setColor(c: Int, keepHueWhenGrey: Boolean) {
        val previousHue = hsv[0]
        Color.colorToHSV(c, hsv)
        if (keepHueWhenGrey && hsv[1] == 0f) hsv[0] = previousHue
    }

    /** Pushes the current HSV out to every widget except the one that just changed. */
    private fun syncWidgets(exclude: View?) {
        syncing = true
        val c = color

        (preview.background as? GradientDrawable)?.setColor(c)
        // The spectrum area is tinted by hue, so it tracks the bar even while the bar is
        // the thing being dragged; only its own selector position is left alone.
        satVal.setHue(hsv[0])
        if (exclude !== satVal) satVal.setSaturationValue(hsv[1], hsv[2])
        if (exclude !== hueBar) hueBar.setHue(hsv[0])
        grid.setSelectedColor(c)

        if (exclude !== hexField) {
            hexField.setText(String.format("#%06X", c and 0xFFFFFF))
        }
        if (exclude !== redField) redField.setText(Color.red(c).toString())
        if (exclude !== greenField) greenField.setText(Color.green(c).toString())
        if (exclude !== blueField) blueField.setText(Color.blue(c).toString())

        syncing = false
    }

    private fun watchHex() {
        hexField.addTextChangedListener(afterChanged {
            val text = hexField.text.toString().trim().removePrefix("#")
            if (text.length != 6) return@afterChanged
            val parsed = text.toIntOrNull(16) ?: return@afterChanged
            setColor(parsed or 0xFF000000.toInt(), keepHueWhenGrey = true)
            syncWidgets(exclude = hexField)
        })
    }

    private fun watchRgb() {
        for (field in listOf(redField, greenField, blueField)) {
            field.addTextChangedListener(afterChanged {
                val r = redField.value()
                val g = greenField.value()
                val b = blueField.value()
                setColor(Color.rgb(r, g, b), keepHueWhenGrey = true)
                syncWidgets(exclude = field)
            })
        }
    }

    private fun EditText.value(): Int =
        text.toString().toIntOrNull()?.coerceIn(0, 255) ?: 0

    /** TextWatcher that ignores edits we made ourselves. */
    private fun afterChanged(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!syncing) action()
        }
    }

    companion object {
        /**
         * Shows the picker. [onPicked] fires only on OK.
         *
         * [allowEyedropper] must be false where there is no page to sample — the overlay
         * reads the activity's own window, so outside the drawing screen it would only
         * ever return the colour of the UI behind the dialog.
         */
        fun show(
            activity: Activity,
            initialColor: Int,
            allowEyedropper: Boolean = false,
            onPicked: (Int) -> Unit
        ) {
            ColorPickerDialog(
                activity, initialColor, allowEyedropper,
                startOnSpectrum = false, onPicked = onPicked
            ).build()
        }

        /**
         * Covers the activity with a sampling overlay. [onResult] gets null if the user
         * backs out. The snapshot is taken from the activity window only, so the picker's
         * own dialog (a separate window) is never in frame.
         */
        private fun startEyedropper(
            activity: Activity,
            currentColor: Int,
            onResult: (Int?) -> Unit
        ) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val decor = activity.window.decorView
            val overlay = EyedropperOverlayView(activity)

            var finished = false
            fun finish(result: Int?) {
                if (finished) return
                finished = true
                overlay.onPicked = null
                overlay.onCancelled = null
                overlay.setOnKeyListener(null)
                // finish() is reached from inside the overlay's own touch/key dispatch.
                // Detaching a view while its parent is dispatching to it leaves
                // removeFromArray() with a null mParent, so hand the teardown to the
                // next loop iteration instead.
                overlay.post {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    overlay.release()
                    onResult(result)
                }
            }

            // Post so the dismissed dialog is off-screen before the snapshot is taken.
            decor.post {
                val w = decor.width
                val h = decor.height
                if (w <= 0 || h <= 0) { onResult(null); return@post }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val origin = IntArray(2).also { decor.getLocationOnScreen(it) }

                PixelCopy.request(
                    activity.window,
                    // Whole surface: a source rect would be in window space, which only
                    // coincides with the screen space we sample in when the activity is
                    // fullscreen at the origin.
                    null,
                    bmp,
                    { result ->
                        if (result != PixelCopy.SUCCESS) {
                            bmp.recycle()
                            onResult(null)
                            return@request
                        }
                        overlay.setSnapshot(bmp, origin[0], origin[1])
                        overlay.onPicked = { finish(it) }
                        overlay.onCancelled = { finish(null) }
                        content.addView(
                            overlay,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        // Let the back key abandon the pick rather than trapping the user.
                        overlay.isFocusableInTouchMode = true
                        overlay.requestFocus()
                        overlay.setOnKeyListener { _, keyCode, event ->
                            if (keyCode == KeyEvent.KEYCODE_BACK &&
                                event.action == KeyEvent.ACTION_UP
                            ) {
                                finish(null); true
                            } else false
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }
        }
    }
}
