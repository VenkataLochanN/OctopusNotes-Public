package com.lochan.octopusnotes

import android.app.Activity
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ProgressDialogController(
    activity: Activity,
    title: String,
    private val onCancel: () -> Unit
) {
    @Volatile var isCancelled = false
        private set

    private val bar: ProgressBar
    private val percent: TextView
    private val dialog: androidx.appcompat.app.AlertDialog

    init {
        val view = activity.layoutInflater.inflate(R.layout.dialog_progress, null)
        view.findViewById<TextView>(R.id.progressTitle).text = title
        bar = view.findViewById(R.id.progressBar)
        percent = view.findViewById(R.id.progressPercent)
        bar.isIndeterminate = true
        percent.text = ""
        dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .create()
    }

    fun show() = dialog.show()

    private fun cancel() {
        if (isCancelled) return
        isCancelled = true
        onCancel()
        dismiss()
    }

    fun setProgress(done: Int, total: Int) {
        if (total <= 0) { bar.isIndeterminate = true; percent.text = ""; return }
        bar.isIndeterminate = false
        val pct = ((done.toLong() * 100) / total).toInt().coerceIn(0, 100)
        bar.progress = pct
        percent.text = "$pct%"
    }

    fun dismiss() {
        try { dialog.dismiss() } catch (_: Exception) {}
    }
}
