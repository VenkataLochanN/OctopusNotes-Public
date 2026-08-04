package com.lochan.octopusnotes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
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

        setupPreferences()
        loadStorageUsage()
        findViewById<View>(R.id.changelogRow).setOnClickListener { showChangelog() }
        findViewById<View>(R.id.licensesRow).setOnClickListener { showLicenses() }
        findViewById<View>(R.id.exportDataButton).setOnClickListener { startExport() }
        findViewById<View>(R.id.importDataButton).setOnClickListener { pickImportFile() }
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

    private fun setupPreferences() {
        val prefs = getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)

        // Editing: auto-append a page when writing on the last page
        val continuousSwitch =
            findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.continuousPagesSwitch)
        continuousSwitch.isChecked = prefs.getBoolean("continuous_pages", false)
        continuousSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("continuous_pages", isChecked).apply()
        }

        // View mode: GRID (default) or LIST
        val viewModeGroup = findViewById<android.widget.RadioGroup>(R.id.viewModeGroup)
        val columnsContainer = findViewById<View>(R.id.columnsContainer)
        val isListMode = prefs.getString("home_view_mode", "GRID") == "LIST"
        viewModeGroup.check(if (isListMode) R.id.viewList else R.id.viewGrid)
        columnsContainer.visibility = if (isListMode) View.GONE else View.VISIBLE
        viewModeGroup.setOnCheckedChangeListener { _, id ->
            val mode = if (id == R.id.viewList) "LIST" else "GRID"
            prefs.edit().putString("home_view_mode", mode).apply()
            columnsContainer.visibility = if (mode == "LIST") View.GONE else View.VISIBLE
        }

        // Items per row: 2..5, or 6 == "Auto" (stored as 0).
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

        // Notebook thumbnail page
        val nbGroup = findViewById<android.widget.RadioGroup>(R.id.notebookThumbGroup)
        when (prefs.getString("thumb_notebook", "LAST")) {
            "FIRST" -> nbGroup.check(R.id.nbFirst)
            "LAST_USED" -> nbGroup.check(R.id.nbLastUsed)
            else -> nbGroup.check(R.id.nbLast)
        }
        nbGroup.setOnCheckedChangeListener { _, id ->
            val value = when (id) {
                R.id.nbFirst -> "FIRST"
                R.id.nbLastUsed -> "LAST_USED"
                else -> "LAST"
            }
            prefs.edit().putString("thumb_notebook", value).apply()
        }

        // Imported PDF thumbnail page
        val pdfGroup = findViewById<android.widget.RadioGroup>(R.id.pdfThumbGroup)
        when (prefs.getString("thumb_pdf", "FIRST")) {
            "LAST" -> pdfGroup.check(R.id.pdfLast)
            else -> pdfGroup.check(R.id.pdfFirst)
        }
        pdfGroup.setOnCheckedChangeListener { _, id ->
            val value = if (id == R.id.pdfLast) "LAST" else "FIRST"
            prefs.edit().putString("thumb_pdf", value).apply()
        }
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

    // --- Export / Import ---

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
                    // Checkpoint the WAL into the main database file so the copied trio
                    // (db + -wal + -shm) is as consistent as possible WITHOUT closing the
                    // app's Room singleton. Closing it and then getting the (still-cached)
                    // instance back used to leave every later query dead — the app crashed
                    // the moment you returned home.
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
                    // Close the database AND clear the cached singleton so the on-disk files
                    // can be replaced, then the next getDatabase() builds a fresh instance
                    // from the restored files instead of handing back the closed one.
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
                    // Prime a fresh instance for the relaunched app. Note: Room builds
                    // lazily, so this constructs the object without touching the file yet;
                    // a corrupt restored DB would surface on the first query after relaunch.
                    AppDatabase.getDatabase(this@SettingsActivity)
                }
                progress.dismiss()
                // The restored data lives in a fresh database, but MainActivity/DrawingActivity
                // still hold DAOs from the old (closed) instance and their lists are stale.
                // Relaunch the app fresh instead of leaving a broken back stack behind.
                Toast.makeText(this@SettingsActivity, "Import complete — restarting…", Toast.LENGTH_LONG).show()
                restartAfterImport()
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(this@SettingsActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Relaunches the app in a clean task so the restored data is picked up. */
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
<p>OctopusNotes is open source and licensed under the MIT License.</p>

<h3>Third-party libraries</h3>
<p>This app uses the following open source software:</p>
<ul>
<li><b>AndroidX Core (core-ktx)</b> — Apache License 2.0</li>
<li><b>AndroidX AppCompat</b> — Apache License 2.0</li>
<li><b>Material Components for Android</b> — Apache License 2.0</li>
<li><b>AndroidX ConstraintLayout</b> — Apache License 2.0</li>
<li><b>AndroidX Lifecycle</b> — Apache License 2.0</li>
<li><b>AndroidX Room</b> — Apache License 2.0</li>
<li><b>AndroidX Compose</b> — Apache License 2.0</li>
<li><b>PDFBox-Android</b> (com.tom-roush:pdfbox-android) — Apache License 2.0</li>
<li><b>Kotlin Standard Library</b> — Apache License 2.0</li>
<li><b>Kotlin Coroutines</b> — Apache License 2.0</li>
</ul>
<p>The full text of the Apache License 2.0 is available at
<a href="https://www.apache.org/licenses/LICENSE-2.0">https://www.apache.org/licenses/LICENSE-2.0</a>.</p>
        """.trimIndent()

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
        val html = """
<h3>v2026.8.4</h3>
<ul>
<li>Fixed a bug where Exporting or Importing a backup closed the app's database forever — after Export, going back to the home screen crashed the app; after Import, the database stayed broken until a manual restart. Export now flushes the database through SQLite's WAL checkpoint without closing it, and Import properly reopens the restored database and relaunches the app automatically so everything is rebuilt from the restored data.</li>
<li>Fixed a bug where exporting a large page (PDF/PNG/ZIP) could fail cryptically: if rendering the page threw (e.g. an out-of-memory moment on a very large raster), the PDF page was left open, which made PdfRenderer.close() throw "Current page not closed" and masked the real error. Pages are now always released even when rendering fails, so the export either completes or reports the true error.</li>
<li>Fixed a small renderer resource leak: if a page render failed partway through (a bad PDF page, an out-of-memory moment, or an ink-drawing error), the bitmap borrowed from the reuse pool was never handed back, silently shrinking the pool until the next render reallocated a fresh one. A failed render now returns its bitmap to the pool before rethrowing, so the reuse pool stays intact across transient render errors.</li>
<li>Redesigned the Create New dialog with a Material 3 Expressive look. New Folder, New Notebook, and Import PDF each appear as a tinted, color-coded card (blue folder, purple notebook, red PDF) with icon, title, and description, a springy scale-in animation when the dialog opens, and a tactile press effect — the plain text-only list is gone.</li>
<li>The notebook title you type in the basic create dialog is now carried over to the Advanced Template dialog when you tap "Choose / Edit Template", so you don't have to retype it.</li>
<li>Shrunk the template preview in the basic create dialog so the dialog feels lighter and the title field gets more visual priority.</li>
<li>Fixed a crash in the Pages view: fast-scrolling the page grid and closing it could crash with "Document already closed" because thumbnail renders were still queued on the app-wide scope and ran against a closed PDF renderer. The grid now runs on its own dialog-scoped coroutine that is cancelled the moment the dialog closes, and the thumbnail renderer is closed-safe — an in-flight render simply gives up instead of touching the closed document.</li>
<li>Made the Pages view much faster on large PDFs. Thumbnails used to render one-at-a-time behind a single native renderer lock (a fast fling could queue dozens of renders waiting their turn). The renderer now keeps a small pool of independent renderers so several thumbnails draw in parallel, duplicate renders of the same page are skipped, and thumbnails for holders that scrolled off-screen are never wasted.</li>
<li>Thumbnail loading in the Pages view now follows the same engine design that makes the main PDF view fast: renders run on their own dedicated worker threads (instead of the app-wide IO pool fighting over one lock), a page that scrolls off-screen cancels its queued render so a fast fling only draws what you're looking at, and rendered bitmaps are reused from a small pool instead of allocating fresh memory for every thumbnail — so the grid fills in noticeably faster and scrolling through huge PDFs stays smooth.</li>
<li>Added a draggable scroll pill to the Pages view. Just like the one in the PDF view, it appears on the right edge of the page grid: drag it to glide through hundreds or thousands of pages, release to land exactly on the page you pointed at, and it fades away when you stop scrolling.</li>
<li>Fixed a memory leak in the zoomed-in view: quickly pinching, panning, or starting a scroll-pill drag could cancel a hi-res tile render partway through, and the page bitmaps it had already produced were never recycled — rapid zooming silently churned native memory. A cancelled render now recycles its finished tiles before the job stops.</li>
<li>Fixed a crash when working with several large inserted images at once: the image cache could evict (and recycle) a selected picture's bitmap while the selection was still drawing it, and the next redraw threw "trying to use a recycled bitmap". Selected pictures now survive cache evictions — a recycled bitmap is re-decoded on the spot instead of drawn.</li>
<li>Fixed opening PDFs from other apps (Open with… → Octopus Notes): the import could crash or leave an empty notebook when the file couldn't be read (e.g. a permission grant that expired between tapping and reading). Failed imports now show an error and roll back the half-created notebook.</li>
<li>Fixed heavy stutter when a page holds several large photos — inserting or lasso-selecting them used to decode each photo on the main thread, freezing frames for up to a second (and the frozen frames made the system skip touch events, spamming "Error processing scroll; pointer index for id 1 not found"). Photos are now decoded on a background thread, the selection appears instantly with frame outlines that fill in as pictures load, and two-finger events are no longer leaked to the list while a selection is being moved.</li>
</ul>

<h3>v2026.8.2</h3>
<ul>
<li>Fixed a bug where fast-dragging the side scroll pill in a long PDF froze the app and triggered repeated "Application Not Responding" dialogs. Each touch-move event used to issue its own giant <code>scrollBy</code> plus dozens of page binds on the main thread, fast enough to overrun Android's 5-second input-dispatch window. The move handler now coalesces into a single <code>scrollBy</code> per Choreographer frame, the engine's size-prefetch sweep and the zoomed-in hi-res tile renderer stand down for the duration of the drag so the single render thread is dedicated to whichever page you land on, and the per-page "last viewed page" persistence is deferred until scrolling actually stops instead of firing on every page crossing.</li>
<li>Fixed the remaining main-thread work that still caused scrolling jank and ANRs on long PDFs even after the drag fix above: every page bind used to open PDF pages natively on the UI thread, copy the whole bitmap cache on each bind/recycle, and force a layout pass — which all adds up fast when a drag flies past dozens of pages. Page sizes are now only read on the render thread, evicted-bitmap reuse uses a constant-time lookup instead of scanning the cache, item heights only re-request layout when they actually change, next-page prefetching stands down during a drag so the single render thread stays on the pages you're looking at, and the scroll-pill fade is no longer restarted on every scroll event.</li>
<li>Fixed a bug where a single failed page render (e.g. an out-of-memory moment while scrolling fast, or a render error on one page) could permanently freeze PDF rendering for the rest of the session. The renderer only allows one page open at a time, and the engine now always releases the page it opened — even when rendering throws — so a temporary glitch can no longer wedge it and make every later page fail to load.</li>
<li>Fixed a bug where pixel-erasing part of a closed loop (e.g. a circle or an infinity drawn as one stroke) filled the remaining loops solid with the pen color. The pixel eraser used to carve the stroke's outline and then fill the leftover pieces, which flooded the loop's interior with ink. It now cuts the stroke along its centerline where the eraser passes, so a loop that loses a small arc stays a clean open arc with a gap — nothing gets filled.</li>
<li>Fixed a bug where a failed save during page operations (add, delete, duplicate, move, rotate, or template change) could delete the notebook's PDF and leave nothing behind — data loss. PDF edits now write to a temp file, keep a backup, verify the rename, and restore the backup if anything fails.</li>
<li>Fixed the pixel eraser crashing or corrupting ink when used alongside other tools. The pixel eraser ran its path-splitting math on a background thread while reading the live stroke list and shared Path/Paint objects, racing the main thread's drawing, stroke eraser, and pen strokes — which could throw ConcurrentModificationException or crash Skia. Ink is now deep-copied on the main thread and the math runs only on those private copies.</li>
<li>Fixed a bug where Export crashed on the tst (test) flavor with Couldn't find meta-data for provider with authority ${'$'}{applicationId}.fileprovider.</li>
<li>Fixed a bug where Images donot appear in thumbnails.</li>
<li>Fixed a bug where undo/redo worked for moving images but failed when undoing the addition of a new image.</li>
<li>Fixed a bug where duplicating a notebook doesnt copy the strokes.</li>
<li>Fixed `imageBitmapCache` in `StrokeManager` by replacing the unbounded `MutableMap` with an `LruCache` to auto-evict bitmaps under memory pressure.</li>
<li>Fixed a bug where duplicating a notebook caused it to display first-page thumbnails like imported PDFs instead of retaining the original's page preferences.</li>
<li>Fixed auto-appending in continuous scroll mode for imported PDFs by persisting the fallback template on the first auto-append.</li>
<li>Fixed a bug where applying a template to a single page incorrectly saved it as the default template for all pages.</li>
<li>thumb_<notebookId>.png is now copied alongside the PDF and ink files during duplication. The duplicate notebook will show the original's cover preview instantly instead of showing a blank placeholder until the next render cycle.</li>
<li>Fixed a bug where grabbing the selection's corner resize handle slightly off-centre made the box jump to a different size on the first drag. The scale is now baselined from where the pen lands on the handle, so the box starts exactly at your grab point and resizes smoothly instead of snapping.</li>
</ul>

<h3>v2026.8.1</h3>
<ul>
<li>Important Bug fixes - Rare: Unerasable strokes | Template change failure.</li>
</ul>

<h3>v2026.21.7</h3>
<ul>
<li>Many fixes and improvements.</li>
</ul>

<h3>v1.9</h3>
<ul>
<li>fixed a bug where going back continuosly deforms curves.</li>
</ul>

<h3>v1.8</h3>
<ul>
<li>scribble to erase.</li>
<li>fixed stoke shifts onPause().</li>
<li>persistent tool settings and highlights.</li>
</ul>

<h3>v1.7</h3>
<ul>
<li>Fixed bugs of features introduced in 1.6 on non AOSP devices.</li>
<li>Changed all icons to rounded, Material 3 ones.</li>
</ul>
<h3>v1.6</h3>
<ul>
<li>Hold the stylus button to temporarily switch to the eraser. Release it to instantly return to your previously selected tool.</li>
<li>Tool settings are now remembered. Each tool saves its own preferences, including size, style, and other settings.</li>
<li>Improved the toolbar layout on small devices. Tools are now on the left, while tool options appear on the right for a more natural workflow.</li>
<li>Added <b>Backup &amp; Restore</b>.
  <ul>
  <li>Export all app data as a <code>.ocd</code> backup file.</li>
  <li>Import a backup with <b>Overwrite</b>, which replaces all existing app data.</li>
  </ul>
</li>
<li>Fixed scrolling between pages while zoomed in.</li>
<li>Fixed delayed loading of the next page when zoomed out.</li>
<li>Fixed the text highlighter fallback. When selectable text isn't detected, it now correctly switches to freehand highlighting.</li>
<li>Added a changelog viewer in Settings.</li>
<li>Delete confirmation now shows how much data is being deleted.</li>
<li>Various bug fixes, stability improvements, and performance enhancements.</li>
</ul>
<h3>v1.5</h3>
<ul>
<li>Completely reworked the PDF engine for significantly better performance and efficiency.</li>
<li>Built a custom PDF search engine for faster searches, while keeping the previous search engine as a fallback for older devices.</li>
<li>Fixed several small bugs introduced during the PDF engine rewrite.</li>
<li>The Highlighter tool now properly highlights text instead of drawing over it.</li>
<li>Fixed an issue where custom templates could appear as black pages.</li>
<li>Added new loading animations throughout the app for a smoother user experience.</li>
<li>Introduced page outlines along with new optimization algorithms to improve speed and responsiveness.</li>
<li>Added <b>Hold to Draw</b>, allowing you to easily create straight lines, arrows, circles, and squares.</li>
<li>Fixed screen flashing on older and slower devices when pages are automatically added with <b>Continuous Write</b> enabled.</li>
<li>Resolved the conflict between the scroll pill and the back gesture.</li>
<li>Redesigned the tool and tool options panels with scrolling support, making them work much better on smaller screens.</li>
</ul>
<h3>v1.4</h3>
<ul>
<li>Built a brand-new custom PDF engine from scratch for better performance and stability.</li>
<li>Added subtle new animations across the app.</li>
<li>Switched to a native PDF renderer with smoother scrolling, zooming, and faster page rendering.</li>
<li>Fixed freezes when closing large PDFs.</li>
<li>New notebooks now start with 2 pages by default.</li>
<li>Added an option to automatically add a new page when you reach the end of your notebook.</li>
<li>Auto-added pages now appear silently in the background without interrupting your workflow or changing your scroll position.</li>
<li>Imported PDFs now show the full template picker when adding pages if no default template has been selected.</li>
<li>Added solid, dotted, and dashed pen styles that are saved with your notes.</li>
<li>Completely redesigned the toolbar with a cleaner top-center layout.</li>
<li>Pen settings now include quick access to line styles, colors, thickness, and stroke stabilization.</li>
<li>Highlighter settings now support freehand and straight modes, text highlighting, custom colors, and adjustable sizes.</li>
<li>Eraser now includes Pixel and Stroke modes with customizable size presets.</li>
<li>Lasso tool now supports freehand, rectangular, and circular selections.</li>
<li>Re-tapping the active tool now quickly shows or hides its options.</li>
<li>Notebook thumbnails are now generated immediately after creating or importing a notebook.</li>
<li>Added animated loading placeholders while thumbnails are being generated.</li>
<li>Thumbnails now refresh automatically after editing.</li>
<li>Improved selected tool highlighting with a cleaner rounded design.</li>
<li>Adjusted the zoom indicator layout for a cleaner interface.</li>
<li>Removed unused resources and performed general code cleanup to improve performance.</li>
<li>Fixed numerous bugs and improved overall stability.</li>
</ul>
<h3>v1.3</h3>
<ul>
<li><b>Folder Design Overhaul</b>: Completely revamped folder appearance for better visual hierarchy.</li>
<li><b>Removed Sidebar</b>: Cleaner, more focused interface without the navigation sidebar.</li>
<li><b>Breadcrumbs Navigation</b>: Added breadcrumb trail for easier folder navigation.</li>
<li><b>Reduced Page Gap</b>: Decreased spacing between pages with darker grey separator for better visual distinction.</li>
<li>Choice of showing first page, last page, and last used page previews.</li>
<li>Added search functionality in homescreen.</li>
<li><b>Zoom Control</b>: New zoom slider for pages and homescreen views.</li>
<li><b>Fixed Subfolder Creation</b>: Resolved issue where subfolders weren't being created properly.</li>
<li><b>New Color Selection</b>: 5 curated standard color options plus custom color picker. Colors synchronized with folder long-press color options.</li>
<li><b>Dropdown Menu</b>: New dropdown arrow near the name on the right side for quick access.</li>
<li><b>New Selection Mode</b>: Long press now triggers selection with checkmark indicator and highlighted background.</li>
<li><b>Floating Dock</b>: Replaces FAB during selection mode with Delete, Move, and Lock options.</li>
<li><b>Export Functionality</b>: Fixed export issues across all formats.</li>
<li><b>FAB Alignment</b>: Corrected bottom FAB positioning issue.</li>
<li><b>Smoothened Animations</b>: Many UI transitions now follow Material 3 motion guidelines.</li>
<li>Zoom out PDF.</li>
<li>Added sorting options.</li>
</ul>
        """.trimIndent()

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
