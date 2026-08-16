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
import android.widget.TextView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ColorPickerDialog private constructor(
    private val activity: Activity,
    initialColor: Int,
    private val allowEyedropper: Boolean,
    private val startOnSpectrum: Boolean,
    private val onPicked: (Int) -> Unit
) {

    private val hsv = FloatArray(3).also { Color.colorToHSV(initialColor, it) }

    private var alpha: Int = Color.alpha(initialColor)

    private var syncing = false

    private lateinit var root: View
    private lateinit var preview: View
    private lateinit var grid: ColorPaletteGridView
    private lateinit var spectrumPane: LinearLayout
    private lateinit var satVal: SaturationValueView
    private lateinit var hueBar: HueBarView
    private lateinit var alphaSlider: com.google.android.material.slider.Slider
    private lateinit var alphaValue: TextView
    private lateinit var hexField: EditText
    private lateinit var redField: EditText
    private lateinit var greenField: EditText
    private lateinit var blueField: EditText

    private val color: Int get() = Color.HSVToColor(alpha, hsv)

    private fun build() {
        root = activity.layoutInflater.inflate(R.layout.dialog_color_picker, null)
        preview = root.findViewById(R.id.colorPreview)
        grid = root.findViewById(R.id.paletteGrid)
        spectrumPane = root.findViewById(R.id.spectrumPane)
        satVal = root.findViewById(R.id.saturationValue)
        hueBar = root.findViewById(R.id.hueBar)
        alphaSlider = root.findViewById(R.id.alphaSlider)
        alphaValue = root.findViewById(R.id.alphaValue)
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

        alphaSlider.valueFrom = 0f
        alphaSlider.valueTo = 100f
        alphaSlider.value = (alpha * 100 / 255).toFloat()
        alphaSlider.addOnChangeListener { _, v, _ ->
            if (!syncing) {
                alpha = (v / 100f * 255f).toInt().coerceIn(0, 255)
                syncWidgets(exclude = alphaSlider)
            }
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

            dialog.dismiss()
            startEyedropper(activity, color) { sampled ->

                val next = sampled?.let { (alpha shl 24) or (it and 0xFFFFFF) } ?: color
                ColorPickerDialog(
                    activity, next, allowEyedropper,
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

    private fun setColor(c: Int, keepHueWhenGrey: Boolean) {
        val previousHue = hsv[0]
        Color.colorToHSV(c, hsv)
        if (keepHueWhenGrey && hsv[1] == 0f) hsv[0] = previousHue
    }

    private fun syncWidgets(exclude: View?) {
        syncing = true
        val c = color

        (preview.background as? GradientDrawable)?.setColor(c)

        satVal.setHue(hsv[0])
        if (exclude !== satVal) satVal.setSaturationValue(hsv[1], hsv[2])
        if (exclude !== hueBar) hueBar.setHue(hsv[0])
        grid.setSelectedColor(c)

        if (exclude !== hexField) {

            hexField.setText(
                if (alpha == 255) String.format("#%06X", c and 0xFFFFFF)
                else String.format("#%08X", c)
            )
        }
        if (exclude !== redField) redField.setText(Color.red(c).toString())
        if (exclude !== greenField) greenField.setText(Color.green(c).toString())
        if (exclude !== blueField) blueField.setText(Color.blue(c).toString())

        if (exclude !== alphaSlider) alphaSlider.value = (alpha * 100 / 255).toFloat()
        alphaValue.text = "${alpha * 100 / 255}%"

        syncing = false
    }

    private fun watchHex() {
        hexField.addTextChangedListener(afterChanged {
            val text = hexField.text.toString().trim().removePrefix("#")
            if (text.length == 8) {

                val parsed = text.toIntOrNull(16) ?: return@afterChanged
                alpha = parsed ushr 24
                setColor(parsed or 0xFF000000.toInt(), keepHueWhenGrey = true)
                syncWidgets(exclude = hexField)
            } else if (text.length == 6) {
                val parsed = text.toIntOrNull(16) ?: return@afterChanged
                setColor(parsed or 0xFF000000.toInt(), keepHueWhenGrey = true)
                syncWidgets(exclude = hexField)
            }
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

    private fun afterChanged(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!syncing) action()
        }
    }

    companion object {

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

                overlay.post {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    overlay.release()
                    onResult(result)
                }
            }

            decor.post {
                val w = decor.width
                val h = decor.height
                if (w <= 0 || h <= 0) { onResult(null); return@post }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val origin = IntArray(2).also { decor.getLocationOnScreen(it) }

                PixelCopy.request(
                    activity.window,

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
