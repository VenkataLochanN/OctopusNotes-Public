package com.lochan.octopusnotes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        setupEdgeToEdge()

        findViewById<ImageButton>(R.id.settingsBackButton).setOnClickListener { finish() }

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val versionText = findViewById<TextView>(R.id.versionValue)
        versionText.text = versionName

        findViewById<ImageButton>(R.id.copyVersionButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("App version", versionName))
            Toast.makeText(this, "Version copied", Toast.LENGTH_SHORT).show()
        }

        setupDeveloperOptions(versionText)

        setupPreferences()
        setupFonts()
        loadStorageUsage()
        findViewById<View>(R.id.changelogRow).setOnClickListener { showChangelog() }

        findViewById<View>(R.id.showIntroAgainRow).setOnClickListener {
            getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
                .edit().putBoolean("onboarding_completed", false).apply()
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<View>(R.id.licensesRow).setOnClickListener { showLicenses() }
        findViewById<View>(R.id.exportDataButton).setOnClickListener { startExport() }
        findViewById<View>(R.id.importDataButton).setOnClickListener { pickImportFile() }

        setupSyncFolder()
        refreshSyncFolderUi()
    }

    private fun setupDeveloperOptions(versionText: TextView) {
        val devPrefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
        val devSection = findViewById<View>(R.id.devOptionsSection)
        val devToggle =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.devOptionsToggle)
        var devTaps = 0

        fun refreshDevOptions() {
            val enabled = devPrefs.getBoolean("dev_options_enabled", false)
            devSection.visibility = if (enabled) View.VISIBLE else View.GONE
            devToggle.isChecked = enabled
        }
        refreshDevOptions()

        versionText.setOnClickListener {
            if (devPrefs.getBoolean("dev_options_enabled", false)) return@setOnClickListener
            devTaps++
            val remaining = 5 - devTaps
            if (remaining > 0) {
                Toast.makeText(
                    this,
                    "$remaining tap${if (remaining == 1) "" else "s"} to open developer options",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                devPrefs.edit().putBoolean("dev_options_enabled", true).apply()
                refreshDevOptions()
                Toast.makeText(this, "Developer options enabled", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.penInputMonitorRow).setOnClickListener {
            startActivity(Intent(this, PenInputMonitorActivity::class.java))
        }

        findViewById<View>(R.id.creditsRow).setOnClickListener { showCredits() }

        findViewById<View>(R.id.visionRow).setOnClickListener {
            startActivity(Intent(this, VisionActivity::class.java))
        }

        devToggle.setOnCheckedChangeListener { _, isChecked ->
            devPrefs.edit().putBoolean("dev_options_enabled", isChecked).apply()
            if (!isChecked) devTaps = 0
            refreshDevOptions()
        }
    }

    private fun showCredits() {
        val spannable = android.text.SpannableString(
            "Oh! You found me.. LOL!\n\n" +
                "this app might not be a success if few friends were not here to help me " +
                "trest and report bugs and suggest festures\n\n" +
                "1. Vaku, my love.\n" +
                "2. Nick. who i met on telegram ( a great friend now)"
        )
        spannable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 25,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Credits")
            .setMessage(spannable)
            .setPositiveButton("Close", null)
            .show()
    }

    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) runExport(uri)
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) showImportOptions(uri)
    }

    private val fontImportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onFontPicked(uri)
    }

    private val syncFolderLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onSyncFolderPicked(uri)
    }

    private val restoreFolderLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onRestoreFolderPicked(uri)
    }

    private fun setupSyncFolder() {
        findViewById<View>(R.id.chooseSyncFolderButton).setOnClickListener {
            syncFolderLauncher.launch(null)
        }

        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.syncEnabledSwitch)
            .setOnCheckedChangeListener { _, isChecked ->
                if (isChecked == SyncFolderManager.isEnabled(this)) return@setOnCheckedChangeListener
                if (isChecked) {
                    SyncFolderManager.setEnabled(this, true)
                    if (!SyncFolderManager.isActive(this)) syncFolderLauncher.launch(null)
                    refreshSyncFolderUi()
                } else {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Turn off sync folder?")
                        .setMessage("Your notes stay safe in the app — this only stops the folder mirror. You can turn it back on anytime.")
                        .setPositiveButton("Turn off") { _, _ ->
                            SyncFolderManager.setEnabled(this, false)
                            refreshSyncFolderUi()
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.syncEnabledSwitch).isChecked = true
                        }
                        .show()
                }
            }
        findViewById<View>(R.id.syncNowButton).setOnClickListener {
            if (!SyncFolderManager.isEnabled(this)) {
                Toast.makeText(this, "Turn on the sync folder first", Toast.LENGTH_SHORT).show()
            } else if (!SyncFolderManager.isActive(this)) {
                Toast.makeText(this, "Choose a folder first", Toast.LENGTH_SHORT).show()
            } else {
                runSyncWithProgress {
                    Toast.makeText(this@SettingsActivity, "Synced", Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<View>(R.id.restoreFromFolderRow).setOnClickListener { startRestore() }
        findViewById<View>(R.id.stopSyncingRow).setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Stop syncing?")
                .setMessage("Your notes stay in the folder, but the app will stop updating it and forget this folder.")
                .setPositiveButton("Stop") { _, _ ->
                    SyncFolderManager.setEnabled(this, false)
                    SyncFolderManager.clearFolder(this)
                    releaseSyncFolderPermission()
                    refreshSyncFolderUi()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun onSyncFolderPicked(uri: Uri) {
        val flags = SyncFolderManager.grantFlags()
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't access that folder", Toast.LENGTH_SHORT).show()
            return
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Erase data in this folder?")
            .setMessage(
                "OctopusNotes mirrors your notes, PDFs and images into this folder — any existing " +
                    "content in it will be deleted and replaced with the mirror. Only choose a " +
                    "folder you don't use for anything else.\n\n" +
                    "Don't delete or rename this folder while syncing is on: your backups live " +
                    "here and the app keeps writing to it after every edit."
            )
            .setPositiveButton("Use this folder") { _, _ -> enableSyncFolder(uri) }
            .setNegativeButton("Cancel") { _, _ ->
                try { contentResolver.releasePersistableUriPermission(uri, flags) } catch (_: Exception) {}
                syncFolderLauncher.launch(null)
            }
            .show()
    }

    private fun releaseSyncFolderPermission() {
        val s = SyncFolderManager.uriString(this) ?: return
        try {
            contentResolver.releasePersistableUriPermission(Uri.parse(s), SyncFolderManager.grantFlags())
        } catch (_: Exception) {
        }
    }

    private fun enableSyncFolder(uri: Uri) {
        SyncFolderManager.setFolder(this, uri)
        runSyncWithProgress {
            refreshSyncFolderUi()
            Toast.makeText(this@SettingsActivity, "Sync folder set", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runSyncWithProgress(onDone: () -> Unit) {
        showSyncProgress()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                SyncFolderManager.mirrorAll(
                    this@SettingsActivity,
                    AppDatabase.getDatabase(this@SettingsActivity).notesDao().getAllNotebooksIncludingBin()
                ) { done, total ->
                    runOnUiThread { updateSyncProgress(done, total) }
                }
            }
            hideSyncProgress()
            onDone()
        }
    }

    private fun showSyncProgress() {
        findViewById<View>(R.id.syncProgressContainer).visibility = View.VISIBLE
        findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.syncProgressBar)
            .isIndeterminate = true
        findViewById<TextView>(R.id.syncProgressText).text = "Syncing to folder…"
        setSyncActionsEnabled(false)
    }

    private fun hideSyncProgress() {
        findViewById<View>(R.id.syncProgressContainer).visibility = View.GONE
        setSyncActionsEnabled(true)
    }

    private fun updateSyncProgress(done: Long, total: Long) {
        if (isFinishing || isDestroyed) return
        val bar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.syncProgressBar)
        if (total <= 0L) {
            bar.isIndeterminate = true
            return
        }
        bar.isIndeterminate = false
        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
        bar.setProgressCompat(pct, true)
        findViewById<TextView>(R.id.syncProgressText).text = "Syncing to folder… $pct%"
    }

    private fun setSyncActionsEnabled(enabled: Boolean) {
        findViewById<View>(R.id.syncNowButton).isEnabled = enabled
        findViewById<View>(R.id.chooseSyncFolderButton).isEnabled = enabled
    }

    private fun startRestore() {
        val name = SyncFolderManager.folderDisplayName(this)
        if (name == null || !SyncFolderManager.isReachable(this)) {

            if (name != null) {
                Toast.makeText(this, "The sync folder isn't reachable — pick the folder to restore from", Toast.LENGTH_LONG).show()
            }
            restoreFolderLauncher.launch(null)
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Restore from folder")
            .setItems(
                arrayOf(
                    "Restore from sync folder — $name",
                    "Restore from a different folder…"
                )
            ) { _, which ->
                if (which == 0) showRestoreConfirm() else restoreFolderLauncher.launch(null)
            }
            .show()
    }

    private fun showRestoreConfirm() {
        val name = SyncFolderManager.folderDisplayName(this) ?: "your folder"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Restore from folder")
            .setMessage(
                "Import notebooks, PDFs, images, folders and settings from \"$name\" that aren't " +
                    "already in this app. Notes already here are left untouched.\n\n" +
                    "Nothing in the folder is deleted."
            )
            .setPositiveButton("Restore") { _, _ -> runRestore() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onRestoreFolderPicked(uri: Uri) {
        val flags = SyncFolderManager.grantFlags()
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't access that folder", Toast.LENGTH_SHORT).show()
            return
        }

        if (uri.toString() == SyncFolderManager.uriString(this)) {
            showRestoreConfirm()
            return
        }

        releaseSyncFolderPermission()
        SyncFolderManager.clearFolder(this)
        SyncFolderManager.setEnabled(this, false)

        val pickedName = try {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name
        } catch (e: Exception) {
            null
        } ?: "this folder"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Sync to this folder?")
            .setMessage(
                "Restore from \"$pickedName\" and keep syncing your notes here from now on? " +
                    "If not, syncing stays off and the folder is only used for this restore."
            )
            .setPositiveButton("Yes, sync here") { _, _ ->
                SyncFolderManager.setFolder(this, uri)
                SyncFolderManager.setEnabled(this, true)

                runRestore { runSyncWithProgress { refreshSyncFolderUi() } }
            }
            .setNegativeButton("No, just restore") { _, _ ->
                SyncFolderManager.setFolder(this, uri)
                runRestore { refreshSyncFolderUi() }
            }
            .setCancelable(false)
            .show()
    }

    private fun runRestore(onDone: () -> Unit = {}) {
        val progress = ProgressDialogController(this, "Restoring from folder…") {}
        progress.show()
        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val dao = AppDatabase.getDatabase(this@SettingsActivity).notesDao()
                    DataManager(dao, cacheDir, filesDir).restoreFromSyncFolder(this@SettingsActivity) { done, total ->
                        runOnUiThread { progress.setProgress(done.toInt(), total.toInt()) }
                    }
                }
                progress.dismiss()
                val msg = if (summary.notebooks == 0 && summary.pdfs == 0 && summary.images == 0 && summary.templates == 0 && summary.folders == 0) {
                    "Nothing new to restore — the folder matches this app"
                } else {
                    "Restored ${summary.notebooks} notebook(s), ${summary.pdfs} PDF(s), ${summary.images} image(s), ${summary.templates} template(s), ${summary.folders} folder(s)"
                }
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                onDone()
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@SettingsActivity, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshSyncFolderUi() {
        val summary = findViewById<TextView>(R.id.syncFolderSummary)
        val stopRow = findViewById<View>(R.id.stopSyncingRow)
        val stopDivider = findViewById<View>(R.id.stopSyncDivider)
        val chooseButton = findViewById<Button>(R.id.chooseSyncFolderButton)
        val syncNowButton = findViewById<View>(R.id.syncNowButton)
        val restoreRow = findViewById<View>(R.id.restoreFromFolderRow)
        val restoreDivider = findViewById<View>(R.id.restoreFromFolderDivider)
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.syncEnabledSwitch)
        val enabled = SyncFolderManager.isEnabled(this)
        switch.isChecked = enabled
        when {
            !enabled -> {
                findViewById<View>(R.id.syncProgressContainer).visibility = View.GONE
                summary.text = "Off — your notes stay only in this app. Turn it on if you want them mirrored to a folder for manual backup or syncing."
                findViewById<View>(R.id.syncActionsRow).visibility = View.GONE

                restoreRow.visibility = View.VISIBLE
                restoreDivider.visibility = View.GONE
                stopRow.visibility = View.GONE
                stopDivider.visibility = View.GONE
                chooseButton.text = "Choose folder"
            }
            !SyncFolderManager.isActive(this) -> {
                summary.text = "On, but no folder chosen yet. Pick one and your notes, PDFs and images are mirrored there automatically."
                findViewById<View>(R.id.syncActionsRow).visibility = View.VISIBLE
                chooseButton.visibility = View.VISIBLE
                syncNowButton.visibility = View.VISIBLE
                restoreRow.visibility = View.VISIBLE
                restoreDivider.visibility = View.VISIBLE
                stopRow.visibility = View.GONE
                stopDivider.visibility = View.GONE
                chooseButton.text = "Choose folder"
            }
            !SyncFolderManager.isReachable(this) -> {
                summary.text = "Folder unavailable (it may have been deleted or moved). Choose it again or turn it off."
                findViewById<View>(R.id.syncActionsRow).visibility = View.VISIBLE
                chooseButton.visibility = View.VISIBLE
                syncNowButton.visibility = View.VISIBLE
                restoreRow.visibility = View.VISIBLE
                restoreDivider.visibility = View.VISIBLE
                stopRow.visibility = View.VISIBLE
                stopDivider.visibility = View.VISIBLE
                chooseButton.text = "Choose folder"
            }
            else -> {
                val name = SyncFolderManager.folderDisplayName(this) ?: "your folder"
                summary.text = "Syncing to \"$name\". Your notes, PDFs and images are mirrored here automatically after every edit."
                findViewById<View>(R.id.syncActionsRow).visibility = View.VISIBLE
                chooseButton.visibility = View.VISIBLE
                syncNowButton.visibility = View.VISIBLE
                restoreRow.visibility = View.VISIBLE
                restoreDivider.visibility = View.VISIBLE
                stopRow.visibility = View.VISIBLE
                stopDivider.visibility = View.VISIBLE
                chooseButton.text = "Change folder"
            }
        }
    }

    private fun setupPreferences() {
        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)

        val continuousSwitch =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.continuousPagesSwitch)
        val continuousSummary = findViewById<TextView>(R.id.continuousPagesSummary)
        fun updateContinuousSummary(on: Boolean) {
            continuousSummary.text = if (on)
                "Automatically add a new page when you write on the last one"
            else
                "Add new pages manually with the + icon"
        }
        val continuousOn = prefs.getBoolean("continuous_pages", false)
        continuousSwitch.isChecked = continuousOn
        updateContinuousSummary(continuousOn)
        continuousSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("continuous_pages", isChecked).apply()
            updateContinuousSummary(isChecked)
        }

        val canvasColorSwatch = findViewById<View>(R.id.canvasColorSwatch)
        val canvasColorReset = findViewById<View>(R.id.canvasColorReset)
        fun refreshCanvasSwatch() {
            val c = CanvasColor.current(this)
            val bg = canvasColorSwatch.background as? android.graphics.drawable.LayerDrawable
            (bg?.findDrawableByLayerId(R.id.color_shape) as? android.graphics.drawable.GradientDrawable)
                ?.setColor(c)

            canvasColorReset.visibility =
                if (CanvasColor.isCustom(this)) View.VISIBLE else View.INVISIBLE
        }
        refreshCanvasSwatch()
        canvasColorReset.setOnClickListener {
            CanvasColor.reset(this)
            refreshCanvasSwatch()
        }
        findViewById<View>(R.id.canvasColorRow).setOnClickListener {
            ColorPickerDialog.show(
                this,
                CanvasColor.current(this),
                allowEyedropper = false,
                onPicked = { picked ->
                    CanvasColor.prefs(this).edit().putInt(CanvasColor.PREFS_KEY, picked).apply()
                    refreshCanvasSwatch()
                }
            )
        }

        val scribbleEraseGroup =
            findViewById<com.google.android.material.chip.ChipGroup>(R.id.scribbleEraseGroup)
        fun applyScribbleErase(mode: String) {
            prefs.edit().putString("scribble_erase_difficulty", mode).apply()
        }
        val isHardScribble = prefs.getString("scribble_erase_difficulty", "EASY") == "HARD"
        scribbleEraseGroup.check(if (isHardScribble) R.id.scribbleEraseHard else R.id.scribbleEraseEasy)
        scribbleEraseGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            applyScribbleErase(if (checkedIds.contains(R.id.scribbleEraseHard)) "HARD" else "EASY")
        }

        val longPressEraseSwitch =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.longPressEraseSwitch)
        longPressEraseSwitch.isChecked =
            prefs.getBoolean(StylusSettings.PREFS_LONG_PRESS_ERASE, false)
        longPressEraseSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(StylusSettings.PREFS_LONG_PRESS_ERASE, isChecked).apply()
        }

        val toolOptionsSwitch =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.toolOptionsSwitch)
        val toolOptionsSummary = findViewById<TextView>(R.id.toolOptionsSummary)
        fun updateToolOptionsSummary(on: Boolean) {
            toolOptionsSummary.text = if (on)
                "Options appear automatically when you switch tools"
            else
                "Options stay closed when you switch tools until you tap the tool button"
        }
        val autoShowOptions = prefs.getBoolean("TOOL_OPTIONS_AUTO_SHOW", true)
        toolOptionsSwitch.isChecked = autoShowOptions
        updateToolOptionsSummary(autoShowOptions)
        toolOptionsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("TOOL_OPTIONS_AUTO_SHOW", isChecked).apply()
            updateToolOptionsSummary(isChecked)
        }

        val tabsModeSwitch =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.tabsModeSwitch)
        val tabsModeSummary = findViewById<TextView>(R.id.tabsModeSummary)
        fun updateTabsModeSummary(on: Boolean) {
            tabsModeSummary.text = if (on)
                "Switch between open notes from a tab strip above the toolbar"
            else
                "Open notes one at a time from the library"
        }
        val tabsModeOn = prefs.getBoolean(TabSession.PREFS_ENABLED, false)
        tabsModeSwitch.isChecked = tabsModeOn
        updateTabsModeSummary(tabsModeOn)
        tabsModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(TabSession.PREFS_ENABLED, isChecked).apply()
            updateTabsModeSummary(isChecked)
        }

        val viewModeGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.viewModeGroup)
        val columnsContainer = findViewById<View>(R.id.columnsContainer)
        fun applyViewMode(mode: String) {
            prefs.edit().putString("home_view_mode", mode).apply()
            columnsContainer.visibility = if (mode == "LIST") View.GONE else View.VISIBLE
        }
        val isListMode = prefs.getString("home_view_mode", "GRID") == "LIST"
        viewModeGroup.check(if (isListMode) R.id.viewList else R.id.viewGrid)
        columnsContainer.visibility = if (isListMode) View.GONE else View.VISIBLE
        viewModeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            applyViewMode(if (checkedIds.contains(R.id.viewList)) "LIST" else "GRID")
        }

        val columnsSlider = findViewById<com.google.android.material.slider.Slider>(R.id.columnsSlider)
        val columnsValue = findViewById<TextView>(R.id.columnsValue)
        fun labelFor(cols: Int) = if (cols <= 0) "Auto" else cols.toString()
        val storedCols = prefs.getInt("home_columns", 0)
        columnsSlider.value = if (storedCols in 2..5) storedCols.toFloat() else 6f
        columnsValue.text = labelFor(storedCols)
        columnsSlider.setLabelFormatter { v -> if (v.toInt() >= 6) "Auto" else v.toInt().toString() }
        columnsSlider.addOnChangeListener { _, v, _ ->
            val cols = if (v.toInt() >= 6) 0 else v.toInt()
            prefs.edit().putInt("home_columns", cols).apply()
            columnsValue.text = labelFor(cols)
        }

        val notebookThumbValue = findViewById<TextView>(R.id.notebookThumbValue)
        fun notebookThumbLabel(value: String?) = when (value) {
            "FIRST" -> "First page"
            "LAST_USED" -> "Last used page"
            else -> "Last page"
        }
        fun refreshNotebookThumb() {
            notebookThumbValue.text = notebookThumbLabel(prefs.getString("thumb_notebook", "LAST"))
        }
        refreshNotebookThumb()
        findViewById<View>(R.id.notebookThumbRow).setOnClickListener {
            val current = prefs.getString("thumb_notebook", "LAST")
            val options = arrayOf("First page", "Last page", "Last used page")
            showChoiceDialog(
                "Notebook thumbnail",
                options,
                when (current) { "FIRST" -> 0; "LAST_USED" -> 2; else -> 1 }
            ) { index ->
                val value = when (index) { 0 -> "FIRST"; 2 -> "LAST_USED"; else -> "LAST" }
                prefs.edit().putString("thumb_notebook", value).apply()
                refreshNotebookThumb()
            }
        }

        val pdfThumbValue = findViewById<TextView>(R.id.pdfThumbValue)
        fun refreshPdfThumb() {
            pdfThumbValue.text = if (prefs.getString("thumb_pdf", "FIRST") == "LAST") "Last page" else "First page"
        }
        refreshPdfThumb()
        findViewById<View>(R.id.pdfThumbRow).setOnClickListener {
            val options = arrayOf("First page", "Last page")
            val selected = if (prefs.getString("thumb_pdf", "FIRST") == "LAST") 1 else 0
            showChoiceDialog("Imported PDF thumbnail", options, selected) { index ->
                val value = if (index == 1) "LAST" else "FIRST"
                prefs.edit().putString("thumb_pdf", value).apply()
                refreshPdfThumb()
            }
        }

        val marqueeSwitch =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.marqueeTextSwitch)
        marqueeSwitch.isChecked = prefs.getBoolean("marquee_text", false)
        marqueeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("marquee_text", isChecked).apply()
        }
    }

    private fun setupFonts() {
        findViewById<Button>(R.id.fontImportButton).setOnClickListener {
            fontImportLauncher.launch(
                arrayOf(
                    "font/ttf", "font/otf",
                    "application/x-font-ttf", "application/vnd.ms-opentype",
                    "application/octet-stream"
                )
            )
        }
        refreshFontsUi()
    }

    private fun onFontPicked(uri: Uri) {
        val imported = FontManager.import(this, uri)
        if (imported != null) {
            Toast.makeText(
                this,
                getString(R.string.font_imported) + " · " + FontManager.label(imported.name),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, R.string.font_import_failed, Toast.LENGTH_LONG).show()
        }
        refreshFontsUi()
    }

    private fun refreshFontsUi() {
        val container = findViewById<LinearLayout>(R.id.fontsListContainer)
        val emptyHint = findViewById<TextView>(R.id.fontsEmptyHint)
        val fonts = FontManager.list(this)
        container.removeAllViews()
        emptyHint.visibility = if (fonts.isEmpty()) View.VISIBLE else View.GONE
        for (font in fonts) {
            val row = layoutInflater.inflate(R.layout.item_font_row, container, false)
            row.findViewById<TextView>(R.id.fontName).text = FontManager.label(font.name)
            row.findViewById<ImageButton>(R.id.fontDeleteButton).setOnClickListener {
                FontManager.delete(font)
                refreshFontsUi()
            }
            container.addView(row)
        }
    }

    private fun showChoiceDialog(title: String, options: Array<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                onSelect(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupEdgeToEdge() {
        val root: View = findViewById(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun loadStorageUsage() {
        val storageText = findViewById<TextView>(R.id.storageValue)
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                dirSize(filesDir) + dirSize(cacheDir) +
                    dirSize(File(applicationInfo.dataDir, "databases")) +
                    (getExternalFilesDir(null)?.let { dirSize(it) } ?: 0L)
            }
            storageText.text = formatSize(bytes)
        }
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        val files = dir.listFiles() ?: return dir.length()
        for (f in files) {
            total += if (f.isDirectory) dirSize(f) else f.length()
        }
        return total
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun startExport() {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        exportLauncher.launch("OctopusNotes_$ts.ocd")
    }

    private fun runExport(uri: android.net.Uri) {
        val progress = ProgressDialogController(this, "Exporting data…") {}
        progress.show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {

                    val db = AppDatabase.getDatabase(this@SettingsActivity)
                    try {
                        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
                    } catch (_: Exception) {}
                    contentResolver.openOutputStream(uri)?.use { out ->
                        java.util.zip.ZipOutputStream(out).use { zip ->
                            val dbDir = File(applicationInfo.dataDir, "databases")
                            addDirToZip(zip, filesDir, "files")
                            addDirToZip(zip, cacheDir, "cache") { it.name.endsWith(".json") }
                            addDirToZip(zip, dbDir, "databases") { it.name.startsWith(BuildConfig.DB_NAME) }
                            val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
                            addDirToZip(zip, prefsDir, "shared_prefs") {
                                it.name == "OctopusNotesPrefs.xml" || it.name == "notebook_state.xml"
                            }
                        }
                    }
                }
                progress.dismiss()
                Toast.makeText(this@SettingsActivity, "Export complete", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@SettingsActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addDirToZip(
        zip: java.util.zip.ZipOutputStream,
        dir: File,
        prefix: String,
        filter: ((File) -> Boolean)? = null
    ) {
        if (!dir.exists()) return
        val files = dir.listFiles() ?: return
        val buf = ByteArray(8192)
        for (f in files) {
            if (f.isDirectory) { addDirToZip(zip, f, "$prefix/${f.name}", filter); continue }
            if (filter != null && !filter(f)) continue
            zip.putNextEntry(java.util.zip.ZipEntry("$prefix/${f.name}"))
            f.inputStream().use { inp -> var n: Int; while (inp.read(buf).also { n = it } > 0) zip.write(buf, 0, n) }
            zip.closeEntry()
        }
    }

    private fun pickImportFile() {
        importLauncher.launch(arrayOf("*/*"))
    }

    private fun showImportOptions(uri: android.net.Uri) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Import data")
            .setMessage("This replaces all existing app data with the contents of the backup.")
            .setPositiveButton("Overwrite") { _, _ -> runImport(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runImport(uri: android.net.Uri) {
        val progress = ProgressDialogController(this, "Importing data…") {}
        progress.show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {

                    AppDatabase.closeAndReset()

                    contentResolver.openInputStream(uri)?.use { inp ->
                        java.util.zip.ZipInputStream(inp).use { zip ->
                            val buf = ByteArray(8192)
                            var entry = zip.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val dest = resolveImportPath(entry.name)
                                    if (dest != null) {
                                        dest.parentFile?.mkdirs()
                                        dest.outputStream().use { out ->
                                            var n: Int
                                            while (zip.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                                        }
                                    }
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                    }

                    AppDatabase.getDatabase(this@SettingsActivity)
                }
                progress.dismiss()

                Toast.makeText(this@SettingsActivity, "Import complete. Restarting…", Toast.LENGTH_LONG).show()
                restartAfterImport()
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@SettingsActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restartAfterImport() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launch)
        finishAffinity()
    }

    private fun resolveImportPath(zipPath: String): File? {
        val parts = zipPath.split("/", limit = 2)
        if (parts.size < 2) return null
        val baseDir = when (parts[0]) {
            "files" -> filesDir
            "cache" -> cacheDir
            "databases" -> File(applicationInfo.dataDir, "databases")
            "shared_prefs" -> File(applicationInfo.dataDir, "shared_prefs")
            else -> return null
        }
        val target = File(baseDir, parts[1])
        if (!target.canonicalPath.startsWith(baseDir.canonicalPath)) return null
        return target
    }

    private fun showLicenses() {
        val licenses = """
<h3>OctopusNotes</h3>
<p>OctopusNotes is free software and licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).</p>
<p>The full text of the GNU AGPL v3 is available at
<a href="https://www.gnu.org/licenses/agpl-3.0.html">https://www.gnu.org/licenses/agpl-3.0.html</a>.</p>

<h3>Third-party libraries</h3>
<p>This app uses the following open source software:</p>
<ul>
<li><b>AndroidX Core (core-ktx)</b>: Apache License 2.0</li>
<li><b>AndroidX AppCompat</b>: Apache License 2.0</li>
<li><b>Material Components for Android</b>: Apache License 2.0</li>
<li><b>AndroidX ConstraintLayout</b>: Apache License 2.0</li>
<li><b>AndroidX Lifecycle</b>: Apache License 2.0</li>
<li><b>AndroidX Room</b>: Apache License 2.0</li>
<li><b>AndroidX Compose</b>: Apache License 2.0</li>
<li><b>AndroidX RecyclerView</b>: Apache License 2.0</li>
<li><b>Google Material Design Icons</b>: Apache License 2.0</li>
<li><b>Kotlin Standard Library</b>: Apache License 2.0</li>
<li><b>Kotlin Coroutines</b>: Apache License 2.0</li>
<li><b>JUnit</b>: Eclipse Public License 2.0 (used in unit tests)</li>
</ul>
<p>The full text of the Apache License 2.0 is available at
<a href="https://www.apache.org/licenses/LICENSE-2.0">https://www.apache.org/licenses/LICENSE-2.0</a>.</p>
<p>The full text of the Eclipse Public License 2.0 is available at
<a href="https://www.eclipse.org/legal/epl-2.0/">https://www.eclipse.org/legal/epl-2.0/</a>.</p>
        """".trimIndent()

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val tv = android.widget.TextView(this).apply {
            text = android.text.Html.fromHtml(licenses, android.text.Html.FROM_HTML_MODE_COMPACT)
            setTextColor(getColor(android.R.color.white).let {
                com.google.android.material.color.MaterialColors.getColor(
                    this@SettingsActivity, com.google.android.material.R.attr.colorOnSurface, it
                )
            })
            textSize = 14f
            setPadding(pad, pad / 2, pad, pad)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Open source licenses")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showChangelog() {

        val html = assets.open("changelog.html").bufferedReader().use { it.readText() }

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val tv = android.widget.TextView(this).apply {
            text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
            setTextColor(getColor(android.R.color.white).let {
                com.google.android.material.color.MaterialColors.getColor(
                    this@SettingsActivity, com.google.android.material.R.attr.colorOnSurface, it
                )
            })
            textSize = 14f
            setPadding(pad, pad / 2, pad, pad)
            movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Changelog")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }
}
