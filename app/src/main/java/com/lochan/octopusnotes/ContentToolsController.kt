package com.lochan.octopusnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ContentPlaceTarget(val pageIndex: Int, val area: RectF, val center: PointF)

interface ContentToolsHost {

    val anchorView: View

    val smallDock: Boolean

    val supportsTableStructure: Boolean

    fun contentRectToScreen(pageIndex: Int, rect: RectF): RectF?

    fun showPendingTable(pageIndex: Int, rect: RectF, rows: Int, cols: Int, headerRow: Boolean, headerCol: Boolean)
    fun updatePendingTableGrid(rows: Int, cols: Int, headerRow: Boolean, headerCol: Boolean)
    fun clearPendingTable()

    fun placeTarget(): ContentPlaceTarget

    fun textWrapWidth(pageIndex: Int): Float

    fun textColor(): Int

    fun onContentChanged()

    fun onObjectPlaced(pageIndex: Int, stroke: StrokeData)

    fun applyTapeSettings(pattern: String, width: Float)

    fun onTableReplaced(pageIndex: Int, oldTable: StrokeData, newTable: StrokeData) {}

    fun onOpenTableStructure(pageIndex: Int, table: StrokeData, row: Int, col: Int) {}
}

class ContentToolsController(
    private val activity: AppCompatActivity,
    private val strokeManager: StrokeManager,
    private val historyManager: HistoryManager,
    private val host: ContentToolsHost,
    private val prefPrefix: String = "",
) {
    private val prefs = activity.getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
    private fun key(suffix: String) = prefPrefix + suffix

    private val insertImagePickerLauncher =
        activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { insertImageFromUri(it) }
        }
    private val imagePermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshImageToolStrip()
        }

    private var cameraImageUri: Uri? = null
    private val takePictureLauncher =
        activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraImageUri
            cameraImageUri = null
            if (success && uri != null) openImageCrop(uri)
        }

    fun wireImageStrip() {
        activity.findViewById<View>(R.id.imageGrantButton).setOnClickListener {
            imagePermissionLauncher.launch(imagesPermission())
        }
        activity.findViewById<ImageButton>(R.id.imagePickButton).setOnClickListener {
            insertImagePickerLauncher.launch("image/*")
        }
        activity.findViewById<ImageButton>(R.id.imageCameraButton).setOnClickListener { launchCamera() }
    }

    fun imagesPermission(): String =
        if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    fun imagePermissionGranted(): Boolean =
        activity.checkSelfPermission(imagesPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun refreshImageToolStrip() {
        val granted = imagePermissionGranted()
        activity.findViewById<View>(R.id.imagePermissionGroup).visibility = if (granted) View.GONE else View.VISIBLE
        activity.findViewById<View>(R.id.imagePickButton).visibility = if (granted) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.imageStripDivider).visibility = if (granted) View.VISIBLE else View.GONE
        val recentsRow = activity.findViewById<LinearLayout>(R.id.imageRecentsRow)
        recentsRow.visibility = if (granted) View.VISIBLE else View.GONE

        if (host.smallDock) {
            recentsRow.orientation = LinearLayout.VERTICAL
            activity.findViewById<LinearLayout>(R.id.imagePermissionGroup).orientation =
                LinearLayout.VERTICAL
        }
        if (granted) loadRecentImagePreviews(recentsRow)
    }

    private fun loadRecentImagePreviews(row: LinearLayout) {
        activity.lifecycleScope.launch {
            val thumbs = withContext(Dispatchers.IO) {
                val list = mutableListOf<Pair<Uri, Bitmap>>()
                try {
                    activity.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Images.Media._ID),
                        null, null,
                        "${MediaStore.Images.Media.DATE_ADDED} DESC"
                    )?.use { c ->
                        while (c.moveToNext() && list.size < 8) {
                            val uri = android.content.ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                            )
                            val bmp = loadPreviewThumb(uri) ?: continue
                            list.add(uri to bmp)
                        }
                    }
                } catch (_: Exception) {}
                list
            }
            row.removeAllViews()
            val density = activity.resources.displayMetrics.density
            val size = (40 * density).toInt()
            val margin = (3 * density).toInt()
            val corner = 6 * density
            for ((uri, bmp) in thumbs) {
                val iv = android.widget.ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginStart = margin; marginEnd = margin
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, v.width, v.height, corner)
                        }
                    }
                    setImageBitmap(bmp)
                    setOnClickListener { insertImageFromUri(uri) }
                }
                row.addView(iv)
            }
        }
    }

    private fun loadPreviewThumb(uri: Uri): Bitmap? = try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            activity.contentResolver.loadThumbnail(uri, android.util.Size(128, 128), null)
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / sample > 256 || o.outHeight / sample > 256) sample *= 2
            activity.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) { null }

    private fun insertImageFromUri(uri: Uri) {
        openImageCrop(uri)
    }

    fun launchCamera() {
        val dir = File(activity.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "camera_${java.util.UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        cameraImageUri = uri
        try {
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            cameraImageUri = null
            Toast.makeText(activity, activity.getString(R.string.image_no_camera), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openImageCrop(uri: Uri) {
        val dialog = ImageCropDialog(activity, uri) { cropped -> saveCroppedImageAndPlace(cropped) }
        dialog.show()
    }

    private fun saveCroppedImageAndPlace(cropped: Bitmap) {
        activity.lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                try {
                    val dir = strokeManager.imagesDir ?: return@withContext null
                    val name = "img_${java.util.UUID.randomUUID()}"
                    val f = File(dir, name)

                    val fmt = if (cropped.hasAlpha()) Bitmap.CompressFormat.PNG
                    else Bitmap.CompressFormat.JPEG
                    val written = f.outputStream().use {
                        cropped.compress(fmt, 90, it)
                    }
                    if (!written) { f.delete(); return@withContext null }

                    if (strokeManager.preloadBitmap(name) == null) { f.delete(); return@withContext null }
                    Triple(name, cropped.width, cropped.height)
                } catch (e: Exception) { null }
            }
            cropped.recycle()
            if (prepared == null) {
                Toast.makeText(activity, "Couldn't save that image", Toast.LENGTH_SHORT).show()
                return@launch
            }
            placeImageOnCanvas(prepared.first, prepared.second, prepared.third)
        }
    }

    private fun placeImageOnCanvas(imageFile: String, imgW: Int, imgH: Int) {
        val target = host.placeTarget()
        val pageW = target.area.width()
        val pageH = target.area.height()
        if (pageW <= 0f || pageH <= 0f) return

        var w = pageW * 0.5f
        var h = w * imgH / imgW
        if (h > pageH * 0.6f) { h = pageH * 0.6f; w = h * imgW / imgH }

        val cx = target.center.x
        val cy = target.center.y
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        val path = Path().apply { addRect(rect, Path.Direction.CW) }
        val paint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        val stroke = StrokeData(path = path, paint = paint, type = StrokeType.IMAGE, imageFile = imageFile)
        commitAddStroke(target.pageIndex, stroke)
    }

    private var pendingTablePage = -1
    private var pendingTableRectPath: Path? = null

    private var pendingTableColor = Color.BLACK

    private var pendingTableThickness = 2f
    private var pendingTableLineStyle = PenLineStyle.SOLID
    private var tableConfigPopup: PopupWindow? = null

    fun tableRowsColsPref(): Pair<Int, Int> {
        val p = prefs
        return (p.getInt(key("TABLE_ROWS"), 3).coerceIn(1, 10)) to (p.getInt(key("TABLE_COLS"), 3).coerceIn(1, 8))
    }

    fun onTableStrokeFinished(pageIndex: Int, path: Path, paint: Paint, lineStyle: String) {
        val bounds = RectF().apply { path.computeBounds(this, true) }
        if (bounds.width() < 12f || bounds.height() < 12f) return
        pendingTablePage = pageIndex
        pendingTableColor = paint.color
        pendingTableThickness = paint.strokeWidth.coerceAtLeast(1f)
        pendingTableLineStyle = lineStyle
        pendingTableRectPath = Path().apply { addRect(bounds, Path.Direction.CW) }
        showTableConfigPopup(pageIndex, bounds)
    }

    private fun showTableConfigPopup(pageIndex: Int, pageBounds: RectF) {
        tableConfigPopup?.dismiss()
        val view = activity.layoutInflater.inflate(R.layout.popup_table_config, null)
        val (initRows, initCols) = tableRowsColsPref()
        host.showPendingTable(pageIndex, pageBounds, initRows, initCols, true, false)

        val rowsValue = view.findViewById<TextView>(R.id.tableRowsValue)
        val colsValue = view.findViewById<TextView>(R.id.tableColsValue)
        val preview = view.findViewById<TableGridPreviewView>(R.id.tablePreview)
        val headerBtn = view.findViewById<TextView>(R.id.tableHeaderButton)
        val colHeaderBtn = view.findViewById<TextView>(R.id.tableColHeaderButton)
        val primary = com.google.android.material.color.MaterialColors.getColor(
            activity, com.google.android.material.R.attr.colorPrimary, Color.BLACK
        )
        val onSurfaceVariant = com.google.android.material.color.MaterialColors.getColor(
            activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY
        )
        var rows = initRows
        var cols = initCols
        var headerRow = true
        var headerCol = false

        fun refresh() {
            rowsValue.text = rows.toString()
            colsValue.text = cols.toString()
            preview.setGrid(rows, cols, headerRow, headerCol, pendingTableLineStyle)
            host.updatePendingTableGrid(rows, cols, headerRow, headerCol)
            headerBtn.text = activity.getString(if (headerRow) R.string.table_header_on else R.string.table_header_off)
            headerBtn.setTextColor(if (headerRow) primary else onSurfaceVariant)
            colHeaderBtn.text = activity.getString(if (headerCol) R.string.table_col_header_on else R.string.table_col_header_off)
            colHeaderBtn.setTextColor(if (headerCol) primary else onSurfaceVariant)
        }

        view.findViewById<View>(R.id.tableRowsMinus).setOnClickListener { rows = (rows - 1).coerceAtLeast(1); refresh() }
        view.findViewById<View>(R.id.tableRowsPlus).setOnClickListener { rows = (rows + 1).coerceAtMost(10); refresh() }
        view.findViewById<View>(R.id.tableColsMinus).setOnClickListener { cols = (cols - 1).coerceAtLeast(1); refresh() }
        view.findViewById<View>(R.id.tableColsPlus).setOnClickListener { cols = (cols + 1).coerceAtMost(8); refresh() }
        headerBtn.setOnClickListener { headerRow = !headerRow; refresh() }
        colHeaderBtn.setOnClickListener { headerCol = !headerCol; refresh() }

        var cancelRequested = false
        view.findViewById<View>(R.id.tableCancelButton).setOnClickListener {
            cancelRequested = true
            tableConfigPopup?.dismiss()
        }

        tableConfigPopup = PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isFocusable = true
            elevation = 20f
            setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.bg_popup_menu))
            setOnDismissListener {
                if (cancelRequested) {
                    cancelPendingTable()
                } else {
                    commitPendingTable(rows, cols, headerRow, headerCol)
                }
            }
        }

        val screen = host.contentRectToScreen(pageIndex, pageBounds)
        if (screen != null) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val screenW = activity.resources.displayMetrics.widthPixels
            val x = (screen.centerX() - view.measuredWidth / 2f).toInt()
                .coerceIn(8, (screenW - view.measuredWidth - 8).coerceAtLeast(8))
            val y = (screen.top - view.measuredHeight - 12 * activity.resources.displayMetrics.density).coerceAtLeast(8f).toInt()
            tableConfigPopup?.showAtLocation(host.anchorView, android.view.Gravity.NO_GRAVITY, x, y)
        }
        refresh()
    }

    private fun commitPendingTable(rows: Int, cols: Int, headerRow: Boolean = true, headerCol: Boolean = false) {
        val rectPath = pendingTableRectPath ?: return
        val page = pendingTablePage
        if (page < 0) return
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND

            strokeWidth = pendingTableThickness
            pathEffect = PenLineStyle.pathEffect(pendingTableLineStyle, pendingTableThickness)

            color = pendingTableColor
        }
        val stroke = StrokeData(
            path = rectPath,
            paint = paint,
            type = StrokeType.TABLE,

            lineStyle = pendingTableLineStyle,
            tableData = TableData(rows, cols, headerRow = headerRow, headerCol = headerCol)
        )
        commitAddStroke(page, stroke)
        prefs.edit().putInt(key("TABLE_ROWS"), rows).putInt(key("TABLE_COLS"), cols).apply()
        cancelPendingTable()
    }

    fun cancelPendingTable() {
        pendingTablePage = -1
        pendingTableColor = Color.BLACK
        pendingTableThickness = 2f
        pendingTableLineStyle = PenLineStyle.SOLID
        pendingTableRectPath = null
        host.clearPendingTable()
    }

    private var cellEditorPopup: PopupWindow? = null
    private var editingCellPage = -1
    private var editingCellStroke: StrokeData? = null
    private var editingCellRow = 0
    private var editingCellCol = 0

    fun onTableCellTap(pageIndex: Int, pageX: Float, pageY: Float) {
        val table = strokeManager.knownStrokesForPage(pageIndex).firstOrNull { s ->
            if (s.type != StrokeType.TABLE) return@firstOrNull false
            val b = RectF()
            s.path.computeBounds(b, true)
            b.contains(pageX, pageY)
        } ?: return
        val td = table.tableData ?: return
        val b = RectF()
        table.path.computeBounds(b, true)
        val (row, col) = resolveTableCell(td, b, pageX, pageY)
        showTableCellEditor(pageIndex, table, row, col)
    }

    private fun resolveTableCell(td: TableData, b: RectF, pageX: Float, pageY: Float): Pair<Int, Int> {
        val rows = td.rows.coerceAtLeast(1)
        val cols = td.cols.coerceAtLeast(1)
        val rowY = td.rowBoundaries(b.height())
        val colX = td.colBoundaries(b.width())
        var col = cols - 1
        for (i in 0 until cols) if (pageX - b.left <= colX[i + 1]) { col = i; break }
        var row = rows - 1
        for (j in 0 until rows) if (pageY - b.top <= rowY[j + 1]) { row = j; break }
        val anchor = td.mergeAnchor(row, col)
        return (anchor?.first ?: row) to (anchor?.second ?: col)
    }

    private fun showTableCellEditor(pageIndex: Int, table: StrokeData, row: Int, col: Int) {
        cellEditorPopup?.dismiss()
        editingCellPage = pageIndex
        editingCellStroke = table
        editingCellRow = row
        editingCellCol = col
        val view = activity.layoutInflater.inflate(R.layout.popup_table_cell_editor, null)
        val input = view.findViewById<EditText>(R.id.cellTextInput)
        input.setText(table.tableData?.cells?.get("$row,$col") ?: "")
        input.setSelection(input.text.length)
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitCellInput(input.text.toString())
                cellEditorPopup?.dismiss()
                true
            } else false
        }
        val optionsBtn = view.findViewById<View>(R.id.cellOptionsButton)
        if (host.supportsTableStructure) {
            optionsBtn.setOnClickListener {

                val r = editingCellRow
                val c = editingCellCol
                cellEditorPopup?.dismiss()
                host.onOpenTableStructure(pageIndex, table, r, c)
            }
        } else {
            optionsBtn.visibility = View.GONE
        }
        view.findViewById<View>(R.id.cellCancelButton).setOnClickListener { cellEditorPopup?.dismiss() }
        view.findViewById<View>(R.id.cellInsertButton).setOnClickListener {
            commitCellInput(input.text.toString())
            cellEditorPopup?.dismiss()
        }

        cellEditorPopup = PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            elevation = 20f
            setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.bg_popup_menu))
            setOnDismissListener {
                editingCellPage = -1
                editingCellStroke = null
                editingCellRow = 0
                editingCellCol = 0
            }
        }

        val b = RectF()
        table.path.computeBounds(b, true)
        val td = table.tableData ?: TableData(1, 1)
        val rowY = td.rowBoundaries(b.height())
        val colX = td.colBoundaries(b.width())
        val span = td.merges["$row,$col"]
        val c1 = if (span != null && span.size >= 2 && span[1] > 1) (col + span[1]).coerceAtMost(td.cols) else col + 1
        val r1 = if (span != null && span.size >= 2 && span[0] > 1) (row + span[0]).coerceAtMost(td.rows) else row + 1
        val cell = RectF(
            b.left + colX[col], b.top + rowY[row],
            b.left + colX[c1], b.top + rowY[r1]
        )
        val screen = host.contentRectToScreen(pageIndex, cell)
        if (screen == null) {

            editingCellPage = -1
            editingCellStroke = null
            editingCellRow = 0
            editingCellCol = 0
            cellEditorPopup?.dismiss()
            return
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val screenW = activity.resources.displayMetrics.widthPixels
        val x = (screen.centerX() - view.measuredWidth / 2f).toInt()
            .coerceIn(8, (screenW - view.measuredWidth - 8).coerceAtLeast(8))
        val y = (screen.top - view.measuredHeight - 12 * activity.resources.displayMetrics.density).coerceAtLeast(8f).toInt()
        cellEditorPopup?.showAtLocation(host.anchorView, android.view.Gravity.NO_GRAVITY, x, y)
        input.requestFocus()
    }

    private fun commitCellInput(text: String) {
        val page = editingCellPage
        val table = editingCellStroke
        val row = editingCellRow
        val col = editingCellCol
        editingCellPage = -1
        editingCellStroke = null
        if (page < 0 || table == null) return
        val td = table.tableData ?: return
        val newCells = HashMap(td.cells)
        val cellKey = "$row,$col"
        if (text.isBlank()) newCells.remove(cellKey) else newCells[cellKey] = text.trimEnd()
        val newStroke = table.copy(tableData = td.copy(cells = newCells))
        historyManager.execute(
            DrawingAction.ReplaceStrokes(page, listOf(table), listOf(newStroke)),
            strokeManager
        )
        host.onContentChanged()
        host.onTableReplaced(page, table, newStroke)
    }

    private val TEXT_SIZES = floatArrayOf(18f, 26f, 36f)
    private var textEditorPopup: PopupWindow? = null
    private var editingTextPage = -1
    private var editingTextStroke: StrokeData? = null

    private var editingTextAnchor: PointF? = null

    fun textSizePref(): Float = prefs.getFloat(key("TEXT_SIZE"), TEXT_SIZES[1])

    fun textFontPref(): String? = prefs.getString(key("TEXT_FONT"), null)?.takeIf { it.isNotBlank() }

    fun onTextTap(pageIndex: Int, pageX: Float, pageY: Float) {
        val existing = strokeManager.knownStrokesForPage(pageIndex).firstOrNull { s ->
            if (s.type != StrokeType.TEXT) return@firstOrNull false
            val b = RectF()
            s.path.computeBounds(b, true)
            b.contains(pageX, pageY)
        }
        if (existing != null) showTextEditor(pageIndex, existing, null)
        else showTextEditor(pageIndex, null, PointF(pageX, pageY))
    }

    private fun showTextEditor(pageIndex: Int, existing: StrokeData?, anchor: PointF?) {
        textEditorPopup?.dismiss()
        editingTextPage = pageIndex
        editingTextStroke = existing
        editingTextAnchor = anchor
        val view = activity.layoutInflater.inflate(R.layout.popup_text_editor, null)
        val input = view.findViewById<EditText>(R.id.textInput)
        val sizeValue = view.findViewById<TextView>(R.id.textSizeValue)
        val fontValue = view.findViewById<TextView>(R.id.textFontValue)
        var size = existing?.textData?.size ?: textSizePref()

        var font = if (existing != null) existing.textData?.font else textFontPref()
        input.setText(existing?.textData?.text ?: "")
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitTextInput(input.text.toString(), size, font)
                textEditorPopup?.dismiss()
                true
            } else false
        }

        fun fontLabel(font: String?): String =
            if (font.isNullOrBlank()) activity.getString(R.string.font_default)
            else FontManager.label(font)

        fun refresh() {
            sizeValue.text = size.toInt().toString()
            fontValue.text = fontLabel(font)
        }
        refresh()

        view.findViewById<View>(R.id.textSizeMinus).setOnClickListener { size = (size - 2).coerceAtLeast(8f); refresh() }
        view.findViewById<View>(R.id.textSizePlus).setOnClickListener { size = (size + 2).coerceAtMost(80f); refresh() }
        view.findViewById<View>(R.id.textFontRow).setOnClickListener {
            val fonts = FontManager.list(activity)
            val labels = ArrayList<String>().apply {
                add(activity.getString(R.string.font_default))
                for (f in fonts) add(FontManager.label(f.name))
            }
            val selected = if (font.isNullOrBlank()) 0
            else fonts.indexOfFirst { it.name == font }.let { if (it >= 0) it + 1 else 0 }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.font_title)
                .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                    font = if (which == 0) null else fonts[which - 1].name
                    refresh()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.table_cancel, null)
                .show()
        }
        view.findViewById<View>(R.id.textCancelButton).setOnClickListener { textEditorPopup?.dismiss() }
        view.findViewById<View>(R.id.textInsertButton).setOnClickListener {
            commitTextInput(input.text.toString(), size, font)
            textEditorPopup?.dismiss()
        }

        textEditorPopup = PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            elevation = 20f
            setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.bg_popup_menu))
            setOnDismissListener {
                editingTextPage = -1
                editingTextStroke = null
                editingTextAnchor = null
            }
        }

        val px: Float
        val py: Float
        if (existing != null) {
            val b = RectF()
            existing.path.computeBounds(b, true)
            px = b.centerX(); py = b.centerY()
        } else {
            px = anchor?.x ?: 0f; py = anchor?.y ?: 0f
        }
        val screen = host.contentRectToScreen(pageIndex, RectF(px, py, px, py))
        if (screen == null) {
            editingTextPage = -1
            editingTextStroke = null
            editingTextAnchor = null
            textEditorPopup?.dismiss()
            return
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val x = (screen.centerX() - view.measuredWidth / 2f).toInt()
            .coerceIn(8, (screenW - view.measuredWidth - 8).coerceAtLeast(8))
        val y = (screen.centerY() - view.measuredHeight / 2f).toInt()
            .coerceIn(8, (screenH - view.measuredHeight - 8).coerceAtLeast(8))
        textEditorPopup?.showAtLocation(host.anchorView, android.view.Gravity.NO_GRAVITY, x, y)
        input.requestFocus()
    }

    private fun measureTextRect(text: String, size: Float, maxWidthPx: Float, font: String?): RectF {
        val measurePaint = android.text.TextPaint().apply { isAntiAlias = true; textSize = size }
        FontManager.typeface(FontManager.dir(activity), font)?.let { measurePaint.typeface = it }
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, measurePaint, maxWidthPx.coerceAtLeast(1f).toInt())
            .build()
        var w = 0f
        for (i in 0 until layout.lineCount) w = maxOf(w, layout.getLineWidth(i))
        val pad = size * 0.35f
        return RectF(0f, 0f, w + pad * 2, layout.height + pad * 2)
    }

    private fun commitTextInput(text: String, size: Float, font: String?) {
        val trimmed = text.trimEnd()

        val page = editingTextPage
        val old = editingTextStroke
        val anchor = editingTextAnchor
        editingTextPage = -1
        editingTextStroke = null
        editingTextAnchor = null
        if (page < 0 || trimmed.isBlank()) return

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.5f
            color = host.textColor()
        }
        val pageW = host.textWrapWidth(page) * 0.92f
        if (old != null) {

            val oldBounds = RectF()
            old.path.computeBounds(oldBounds, true)
            val m = measureTextRect(trimmed, size, pageW, font)
            val path = Path().apply {
                addRect(oldBounds.left, oldBounds.top, oldBounds.left + m.width(), oldBounds.top + m.height(), Path.Direction.CW)
            }
            val stroke = StrokeData(path = path, paint = paint, type = StrokeType.TEXT, textData = TextData(trimmed, size, font))
            historyManager.execute(DrawingAction.ReplaceStrokes(page, listOf(old), listOf(stroke)), strokeManager)
            host.onContentChanged()
            host.onObjectPlaced(page, stroke)
        } else {
            if (anchor == null) return

            val m = measureTextRect(trimmed, size, pageW, font)
            val path = Path().apply {
                addRect(anchor.x - m.width() / 2f, anchor.y - m.height() / 2f, anchor.x + m.width() / 2f, anchor.y + m.height() / 2f, Path.Direction.CW)
            }
            val stroke = StrokeData(path = path, paint = paint, type = StrokeType.TEXT, textData = TextData(trimmed, size, font))
            commitAddStroke(page, stroke)
        }
        prefs.edit().putFloat(key("TEXT_SIZE"), size)
            .putString(key("TEXT_FONT"), font ?: "").apply()
    }

    private val tapeWidths = floatArrayOf(20f, 32f, 48f)

    private val tapePatternChoices = listOf(
        R.id.tapePopupSolid to TapePattern.SOLID,
        R.id.tapePopupStripes to TapePattern.STRIPES,
        R.id.tapePopupDots to TapePattern.DOTS,
        R.id.tapePopupGrid to TapePattern.GRID,
        R.id.tapePopupChecks to TapePattern.CHECKS
    )

    private fun tapePatternIcon(pattern: String): Int = when (pattern) {
        TapePattern.STRIPES -> R.drawable.ic_tape_stripes
        TapePattern.DOTS -> R.drawable.ic_tape_dots
        TapePattern.GRID -> R.drawable.ic_tape_grid
        TapePattern.CHECKS -> R.drawable.ic_tape_checks
        else -> R.drawable.ic_tape_solid
    }

    fun tapePatternPref(): String =
        prefs.getString(key("TAPE_PATTERN"), TapePattern.SOLID) ?: TapePattern.SOLID

    fun tapeWidthPref(): Float {
        val stored = prefs.getFloat(key("TAPE_WIDTH"), tapeWidths[1])
        return tapeWidths.firstOrNull { kotlin.math.abs(it - stored) < 0.5f } ?: tapeWidths[1]
    }

    fun wireTapeStrip() {
        activity.findViewById<ImageButton>(R.id.tapePatternButton).setOnClickListener { anchor ->
            showTapePatternPopup(anchor as ImageButton)
        }
        val widthButtons = listOf(
            R.id.tapeWidthSmallButton, R.id.tapeWidthMediumButton, R.id.tapeWidthLargeButton
        )
        for ((idx, id) in widthButtons.withIndex()) {
            activity.findViewById<ImageButton>(id).setOnClickListener {
                prefs.edit().putFloat(key("TAPE_WIDTH"), tapeWidths[idx]).apply()
                applyTapePrefs()
            }
        }
        applyTapePrefs()
    }

    private fun showTapePatternPopup(anchor: ImageButton) {
        val view = activity.layoutInflater.inflate(R.layout.popup_tape_pattern, null)
        val popup = android.widget.PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 20f
        popup.setBackgroundDrawable(null)
        fun refresh() {
            val current = tapePatternPref()
            for ((id, value) in tapePatternChoices) {
                view.findViewById<View>(id).isSelected = value == current
            }
        }
        refresh()
        for ((id, pattern) in tapePatternChoices) {
            view.findViewById<View>(id).setOnClickListener {
                prefs.edit().putString(key("TAPE_PATTERN"), pattern).apply()
                applyTapePrefs()
                refresh()
            }
        }
        popup.showAsDropDown(anchor, 0, 8)
    }

    fun applyTapePrefs() {
        val pattern = tapePatternPref()
        val width = tapeWidthPref()
        host.applyTapeSettings(pattern, width)
        activity.findViewById<ImageButton>(R.id.tapePatternButton).setImageResource(tapePatternIcon(pattern))
        activity.findViewById<ImageButton>(R.id.tapeWidthSmallButton).isSelected = width == tapeWidths[0]
        activity.findViewById<ImageButton>(R.id.tapeWidthMediumButton).isSelected = width == tapeWidths[1]
        activity.findViewById<ImageButton>(R.id.tapeWidthLargeButton).isSelected = width == tapeWidths[2]
    }

    fun onTapeStrokeFinished(pageIndex: Int, path: Path, paint: Paint, pattern: String, width: Float) {
        if (pageIndex < 0) return
        val measure = PathMeasure(path, false)
        val start = FloatArray(2)
        val end = FloatArray(2)
        if (measure.length <= 0f) {

            val b = RectF()
            path.computeBounds(b, true)
            start[0] = b.centerX(); start[1] = b.centerY()
            end[0] = start[0]; end[1] = start[1]
        } else {
            measure.getPosTan(0f, start, null)
            measure.getPosTan(measure.length, end, null)
        }
        val dx = end[0] - start[0]
        val dy = end[1] - start[1]
        val dragLen = kotlin.math.hypot(dx, dy)

        if (dragLen >= 12f) {

            val angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
            commitTape(pageIndex, (start[0] + end[0]) / 2f, (start[1] + end[1]) / 2f,
                angle, dragLen, paint.color, pattern, width)
        } else {
            placeDefaultTape(pageIndex, start[0], start[1], paint.color, pattern, width)
        }
    }

    fun onTapeTap(pageIndex: Int, x: Float, y: Float) {
        if (pageIndex >= 0) {
            val hit = strokeManager.tapeAt(pageIndex, x, y)
            if (hit != null) {
                val td = hit.tapeData ?: TapeData()
                val flipped = hit.copy(tapeData = td.copy(hollow = !td.hollow))
                historyManager.execute(DrawingAction.ReplaceStrokes(pageIndex, listOf(hit), listOf(flipped)), strokeManager)
                host.onContentChanged()
                return
            }
        }

        placeDefaultTape(pageIndex, x, y, host.textColor(), tapePatternPref(), tapeWidthPref())
    }

    private fun placeDefaultTape(pageIndex: Int, cx: Float, cy: Float, color: Int, pattern: String, width: Float) {
        val target = host.placeTarget()
        commitTape(pageIndex, cx, cy, 0f, target.area.width() * 0.3f, color, pattern, width)
    }

    private fun commitTape(
        pageIndex: Int, cx: Float, cy: Float, angle: Float, length: Float,
        color: Int, pattern: String, width: Float
    ) {
        val tapeWidth = width.coerceAtLeast(6f)
        val rect = Path().apply {
            addRect(cx - length / 2f, cy - tapeWidth / 2f, cx + length / 2f, cy + tapeWidth / 2f, Path.Direction.CW)
        }
        val tapePaint = Paint().apply {
            isAntiAlias = true; style = Paint.Style.FILL; this.color = color
        }
        val stroke = StrokeData(
            path = rect, paint = tapePaint, type = StrokeType.TAPE,
            imageRotation = angle, tapeData = TapeData(pattern)
        )
        commitAddStroke(pageIndex, stroke)
    }

    private fun commitAddStroke(pageIndex: Int, stroke: StrokeData) {
        historyManager.execute(DrawingAction.AddStroke(pageIndex, stroke), strokeManager)
        host.onContentChanged()
        host.onObjectPlaced(pageIndex, stroke)
    }

    fun dismissPopups() {
        tableConfigPopup?.dismiss()
        cellEditorPopup?.dismiss()
        textEditorPopup?.dismiss()
    }
}
