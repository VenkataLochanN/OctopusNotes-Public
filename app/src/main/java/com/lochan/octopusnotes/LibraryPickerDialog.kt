package com.lochan.octopusnotes

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as MaterialR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryPickerDialog(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val dataManager: DataManager,
    private val onOpenNotebook: (Long) -> Unit
) {

    private val path = mutableListOf<Folder>()
    private val items = mutableListOf<DisplayItem>()
    private val adapter = PickerAdapter()
    private lateinit var crumbRow: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var dialog: AlertDialog

    fun show() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }

        crumbRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(crumbRow)
        })

        emptyView = TextView(activity).apply {
            text = "Nothing here yet"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
            setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant))
            visibility = View.GONE
        }
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@LibraryPickerDialog.adapter
        }
        content.addView(FrameLayout(activity).apply {
            addView(
                list,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                emptyView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        dialog = MaterialAlertDialogBuilder(activity)
            .setView(content)
            .setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP &&
                    path.isNotEmpty()
                ) {
                    path.removeAt(path.lastIndex)
                    load()
                    true
                } else {
                    false
                }
            }
            .create()
        dialog.show()
        load()
    }

    private fun load() {
        scope.launch {
            val screen = dataManager.getDataForScreen(path.lastOrNull()?.id ?: 0L)
            items.clear()
            items.addAll(sortItems(screen.folders, screen.notebooks))
            adapter.notifyDataSetChanged()
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            buildCrumbs()
        }
    }

    private fun buildCrumbs() {
        crumbRow.removeAllViews()
        crumbRow.addView(makeCrumb("All Notes", path.isEmpty()) { path.clear(); load() })
        path.forEachIndexed { index, folder ->
            crumbRow.addView(makeSeparator())
            crumbRow.addView(makeCrumb(folder.name, index == path.lastIndex) {
                while (path.size > index + 1) path.removeAt(path.lastIndex)
                load()
            })
        }
    }

    private fun makeCrumb(label: String, isLast: Boolean, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            text = label
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextColor(
                MaterialColors.getColor(
                    this,
                    if (isLast) android.R.attr.textColorPrimary else MaterialR.attr.colorPrimary
                )
            )
            if (isLast) setTypeface(typeface, Typeface.BOLD)
            if (!isLast) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
    }

    private fun makeSeparator(): TextView {
        return TextView(activity).apply {
            text = "›"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant))
        }
    }

    private fun sortItems(folders: List<Folder>, notebooks: List<Notebook>): List<DisplayItem> {
        val prefs = activity.getSharedPreferences("OctopusNotesPrefs", android.content.Context.MODE_PRIVATE)
        val field = prefs.getString("sort_field", "MODIFIED") ?: "MODIFIED"
        val asc = prefs.getBoolean("sort_asc", false)
        val keepTop = prefs.getBoolean("keep_folders_top", true)

        val folderItems = folders.map { DisplayItem.FolderItem(it) }
        val notebookItems = notebooks.map { DisplayItem.NotebookItem(it) }

        val nameOf: (DisplayItem) -> String = {
            when (it) {
                is DisplayItem.FolderItem -> it.folder.name
                is DisplayItem.NotebookItem -> it.notebook.title
            }.lowercase()
        }
        val timeOf: (DisplayItem) -> Long = {
            when (it) {
                is DisplayItem.FolderItem -> it.folder.createdAt
                is DisplayItem.NotebookItem -> when (field) {
                    "CREATED" -> it.notebook.createdAt
                    "OPENED" -> it.notebook.lastOpened
                    else -> it.notebook.lastModified
                }
            }
        }
        val base: Comparator<DisplayItem> = if (field == "NAME") compareBy(nameOf) else compareBy(timeOf)
        val cmp = if (asc) base else base.reversed()

        return if (keepTop) {
            folderItems.sortedWith(cmp) + notebookItems.sortedWith(cmp)
        } else {
            (folderItems + notebookItems).sortedWith(cmp)
        }
    }

    private fun bindTagDot(tagView: View, hex: String?) {
        if (hex == null) {
            tagView.visibility = View.GONE
            return
        }
        tagView.visibility = View.VISIBLE
        val color = try { Color.parseColor(hex) } catch (e: IllegalArgumentException) { Color.TRANSPARENT }
        (tagView.background as? android.graphics.drawable.LayerDrawable)
            ?.findDrawableByLayerId(R.id.color_shape)
            ?.let { it as? android.graphics.drawable.GradientDrawable }
            ?.setColor(color)
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    private inner class PickerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is DisplayItem.FolderItem -> TYPE_FOLDER
            is DisplayItem.NotebookItem -> TYPE_NOTEBOOK
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_FOLDER) {
                FolderHolder(inflater.inflate(R.layout.list_item_folder, parent, false))
            } else {
                NotebookHolder(inflater.inflate(R.layout.list_item_notebook, parent, false))
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is DisplayItem.FolderItem -> {
                    val h = holder as FolderHolder
                    h.name.text = item.folder.name
                    h.letter.text = item.folder.name.trim().firstOrNull()?.uppercase() ?: "?"
                    h.icon.setColorFilter(
                        try { Color.parseColor(item.folder.colorHex) } catch (e: IllegalArgumentException) { Color.LTGRAY }
                    )
                    bindTagDot(h.tagView, item.folder.tagColorHex)
                }
                is DisplayItem.NotebookItem -> {
                    val h = holder as NotebookHolder
                    h.name.text = item.notebook.title
                    h.name.setMarqueeEnabled(
                        activity.getSharedPreferences("OctopusNotesPrefs", android.content.Context.MODE_PRIVATE)
                            .getBoolean("marquee_text", false)
                    )
                    bindTagDot(h.tagView, item.notebook.tagColorHex)

                    val id = item.notebook.id
                    h.preview.setImageResource(R.drawable.bg_thumb_skeleton)
                    h.preview.tag = id
                    val thumb = File(activity.filesDir, "thumb_$id.png")
                    if (thumb.exists()) {
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(thumb.path) }
                            if (bmp != null && h.preview.tag == id) h.preview.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }
    }

    private inner class FolderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.folderNameTextView)
        val icon: ImageView = itemView.findViewById(R.id.folderIconImageView)
        val letter: TextView = itemView.findViewById(R.id.folderLetter)
        val tagView: View = itemView.findViewById(R.id.folderTagView)
        val chevron: ImageView = itemView.findViewById(R.id.folderMenuButton)

        init {

            chevron.setImageResource(R.drawable.ic_chevron_right)
            chevron.isClickable = false
            chevron.contentDescription = null
            itemView.setOnClickListener {
                val folder = (items.getOrNull(adapterPosition) as? DisplayItem.FolderItem)?.folder
                    ?: return@setOnClickListener
                path.add(folder)
                load()
            }
        }
    }

    private inner class NotebookHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: MarqueeTextView = itemView.findViewById(R.id.notebookNameTextView)
        val preview: ImageView = itemView.findViewById(R.id.notebookPreviewImageView)
        val tagView: View = itemView.findViewById(R.id.notebookTagView)
        private val menu: ImageView = itemView.findViewById(R.id.notebookMenuButton)

        init {
            menu.visibility = View.GONE
            itemView.setOnClickListener {
                val notebook = (items.getOrNull(adapterPosition) as? DisplayItem.NotebookItem)?.notebook
                    ?: return@setOnClickListener
                dialog.dismiss()
                onOpenNotebook(notebook.id)
            }
        }
    }

    companion object {
        private const val TYPE_NOTEBOOK = 0
        private const val TYPE_FOLDER = 1
    }
}
