package com.lochan.octopusnotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PenInputMonitorActivity : AppCompatActivity() {

    private val lines = ArrayDeque<String>()
    private var counter = 0
    private var monitoring = false
    private lateinit var logText: TextView

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            logBackKey()
        }
    }

    private val saveLogLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) saveLog(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_pen_input_monitor)

        val root = findViewById<android.view.View>(R.id.monitorRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        logText = findViewById(R.id.monitorLogText)

        findViewById<ImageButton>(R.id.monitorBackButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.monitorClearButton).setOnClickListener {
            lines.clear()
            counter = 0
            logText.text = ""
        }
        findViewById<TextView>(R.id.monitorShareButton).setOnClickListener {
            if (lines.isEmpty()) {
                Toast.makeText(
                    this, "The log is empty. Start monitor and capture some events first.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            saveLogLauncher.launch("input_log_$ts.txt")
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        findViewById<Button>(R.id.monitorStartButton).setOnClickListener {
            monitoring = true
            updateMonitorState()
        }
        findViewById<Button>(R.id.monitorStopButton).setOnClickListener {
            monitoring = false
            updateMonitorState()
        }
        updateMonitorState()
    }

    private fun updateMonitorState() {
        findViewById<Button>(R.id.monitorStartButton).isEnabled = !monitoring
        findViewById<Button>(R.id.monitorStopButton).isEnabled = monitoring
        backPressedCallback.isEnabled = monitoring
        findViewById<TextView>(R.id.monitorStatusText).text = if (monitoring) {
            "Monitoring — press pen buttons, tap, or hover the pen over the screen. Back is captured too; use ← to leave."
        } else {
            "Stopped — press Start monitor to begin logging."
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (monitoring) appendLine(describeMotion("TOUCH", event))
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (monitoring) appendLine(describeMotion("MOTION", event))
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!monitoring) return super.dispatchKeyEvent(event)
        appendLine(describeKey(event))

        return if (event.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(event)
    }

    private fun logBackKey() {
        val now = SystemClock.uptimeMillis()
        appendLine(
            String.format("KEY   DOWN      BACK (code=%d, repeat=0) t=%d", KeyEvent.KEYCODE_BACK, now)
        )
        appendLine(
            String.format("KEY   UP        BACK (code=%d, repeat=0) t=%d", KeyEvent.KEYCODE_BACK, now)
        )
    }

    private fun appendLine(line: String) {
        val entry = String.format("#%04d  %s", counter++, line)

        lines.addFirst(entry)
        while (lines.size > 2000) lines.removeLast()
        logText.text = lines.joinToString("\n")
    }

    private fun saveLog(targetUri: Uri) {
        val content = logText.text.toString()
        try {
            contentResolver.openOutputStream(targetUri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Could not open the chosen file.")
        } catch (t: Throwable) {
            Toast.makeText(this, "Save failed: ${t.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Log saved.", Toast.LENGTH_SHORT).show()
        shareLog(content)
    }

    private fun shareLog(content: String) {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val name = "input_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
            val file = File(dir, name)
            file.writeText(content)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Input monitor log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share log"))
        } catch (t: Throwable) {
            Toast.makeText(this, "Share failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun describeMotion(tag: String, event: MotionEvent): String {
        val action = actionName(event.actionMasked)
        val tool = toolTypeName(event.getToolType(0))
        val buttons = describeButtonState(event.buttonState)
        val source = "0x" + Integer.toHexString(event.source)
        val pressure = if (event.pointerCount > 0) event.getPressure(0) else 0f
        return String.format(
            "%s %-12s tool=%-7s buttons=%-16s x=%7.1f y=%7.1f p=%.2f src=%s t=%d",
            tag, action, tool, buttons, event.x, event.y, pressure, source, event.eventTime
        )
    }

    private fun describeKey(event: KeyEvent): String {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> "ACTION_${event.action}"
        }
        val name = KeyEvent.keyCodeToString(event.keyCode)
        return String.format(
            "KEY   %-8s %s (code=%d, repeat=%d) t=%d",
            action, name, event.keyCode, event.repeatCount, event.eventTime
        )
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
        MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
        MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
        MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
        MotionEvent.ACTION_SCROLL -> "SCROLL"
        MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
        MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
        else -> "ACTION_$action"
    }

    private fun toolTypeName(toolType: Int): String = when (toolType) {
        MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        else -> "TOOL_$toolType"
    }

    private fun describeButtonState(state: Int): String {
        if (state == 0) return "0"
        val names = mutableListOf<String>()
        if (state and MotionEvent.BUTTON_PRIMARY != 0) names.add("PRIMARY")
        if (state and MotionEvent.BUTTON_SECONDARY != 0) names.add("SECONDARY")
        if (state and MotionEvent.BUTTON_TERTIARY != 0) names.add("TERTIARY")
        if (state and MotionEvent.BUTTON_BACK != 0) names.add("BACK")
        if (state and MotionEvent.BUTTON_FORWARD != 0) names.add("FORWARD")
        if (state and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) names.add("STYLUS_PRIMARY")
        if (state and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) names.add("STYLUS_SECONDARY")
        return if (names.isEmpty()) "0x" + Integer.toHexString(state) else names.joinToString("+")
    }
}
