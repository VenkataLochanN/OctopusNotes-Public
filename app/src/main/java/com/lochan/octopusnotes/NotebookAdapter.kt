package com.lochan.octopusnotes

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.graphics.BitmapFactory
import java.io.File
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotebookAdapter(
    var items: MutableList<DisplayItem>,
    private val listener: OnItemInteractionListener,
    private val scope: CoroutineScope,
    private val viewMode: String = "GRID"
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface OnItemInteractionListener {
        fun onNotebookClick(notebook: Notebook)
        fun onFolderClick(folder: Folder)
        fun onNotebookMenu(notebook: Notebook, anchor: View)
        fun onFolderMenu(folder: Folder, anchor: View)
        // Multi-select
        fun onItemLongPress(item: DisplayItem)
        fun isSelectionMode(): Boolean
        fun isItemSelected(item: DisplayItem): Boolean
        fun toggleSelection(item: DisplayItem)
    }

    companion object {
        private const val TYPE_NOTEBOOK = 0
        private const val TYPE_FOLDER = 1
    }

    // Cache decoded thumbnails (keyed by notebook id) so rebinding on selection changes
    // doesn't flash the skeleton. Keyed by file modtime so updated thumbnails still refresh.
    private val thumbCache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }
    private val thumbMtime = HashMap<Long, Long>()

    inner class NotebookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: MaterialCardView = itemView.findViewById(R.id.notebookCardView)
        val nameTextView: TextView = itemView.findViewById(R.id.notebookNameTextView)
        val previewImageView: ImageView = itemView.findViewById(R.id.notebookPreviewImageView)
        val menuButton: ImageView = itemView.findViewById(R.id.notebookMenuButton)
        val selectCheck: ImageView = itemView.findViewById(R.id.notebookSelectCheck)

        init {
            itemView.setOnClickListener {
                val item = items[adapterPosition]
                if (listener.isSelectionMode()) listener.toggleSelection(item)
                else listener.onNotebookClick((item as DisplayItem.NotebookItem).notebook)
            }
            itemView.setOnLongClickListener {
                // In selection mode, let the drag handler take the long-press.
                if (listener.isSelectionMode()) false
                else { listener.onItemLongPress(items[adapterPosition]); true }
            }
            val openMenu = {
                val item = items[adapterPosition]
                if (listener.isSelectionMode()) listener.toggleSelection(item)
                else listener.onNotebookMenu((item as DisplayItem.NotebookItem).notebook, menuButton)
            }
            menuButton.setOnClickListener { openMenu() }
            nameTextView.setOnClickListener { openMenu() }
        }
    }

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.folderNameTextView)
        val iconImageView: ImageView = itemView.findViewById(R.id.folderIconImageView)
        val letterTextView: TextView = itemView.findViewById(R.id.folderLetter)
        val menuButton: ImageView = itemView.findViewById(R.id.folderMenuButton)
        val selectCheck: ImageView? = itemView.findViewById(R.id.folderSelectCheck)
        val selectOverlay: View? = itemView.findViewById(R.id.folderSelectOverlay)
        val cardView: MaterialCardView? = itemView.findViewById(R.id.folderCardView)

        init {
            itemView.setOnClickListener {
                val item = items[adapterPosition]
                if (listener.isSelectionMode()) listener.toggleSelection(item)
                else listener.onFolderClick((item as DisplayItem.FolderItem).folder)
            }
            itemView.setOnLongClickListener {
                if (listener.isSelectionMode()) false
                else { listener.onItemLongPress(items[adapterPosition]); true }
            }
            val openMenu = {
                val item = items[adapterPosition]
                if (listener.isSelectionMode()) listener.toggleSelection(item)
                else listener.onFolderMenu((item as DisplayItem.FolderItem).folder, menuButton)
            }
            menuButton.setOnClickListener { openMenu() }
            nameTextView.setOnClickListener { openMenu() }
        }
    }

    /** Subtle pulsing shimmer shown on the skeleton placeholder while a thumbnail loads. */
    private fun startSkeletonPulse(v: ImageView) {
        if (v.getTag(R.id.skeleton_anim_tag) == true) return // already pulsing
        v.setTag(R.id.skeleton_anim_tag, true)
        val anim = android.view.animation.AlphaAnimation(1f, 0.4f).apply {
            duration = 700
            repeatMode = android.view.animation.Animation.REVERSE
            repeatCount = android.view.animation.Animation.INFINITE
        }
        v.startAnimation(anim)
    }

    private fun stopSkeletonPulse(v: ImageView) {
        v.setTag(R.id.skeleton_anim_tag, false)
        v.clearAnimation()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DisplayItem.NotebookItem -> TYPE_NOTEBOOK
        is DisplayItem.FolderItem -> TYPE_FOLDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_NOTEBOOK) {
            val layout = if (viewMode == "LIST") R.layout.list_item_notebook else R.layout.grid_item_notebook
            NotebookViewHolder(inflater.inflate(layout, parent, false))
        } else {
            val layout = if (viewMode == "LIST") R.layout.list_item_folder else R.layout.grid_item_folder
            FolderViewHolder(inflater.inflate(layout, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val selectionMode = listener.isSelectionMode()
        when (val currentItem = items[position]) {
            is DisplayItem.NotebookItem -> {
                val h = holder as NotebookViewHolder
                val id = currentItem.notebook.id
                h.nameTextView.text = currentItem.notebook.title

                val thumb = File(h.itemView.context.filesDir, "thumb_$id.png")
                val mtime = if (thumb.exists()) thumb.lastModified() else 0L
                val cached = thumbCache.get(id)
                if (cached != null && thumbMtime[id] == mtime) {
                    stopSkeletonPulse(h.previewImageView)
                    h.previewImageView.setImageBitmap(cached) // synchronous → no flash
                } else {
                    h.previewImageView.setImageResource(R.drawable.bg_thumb_skeleton)
                    startSkeletonPulse(h.previewImageView) // shimmer until the thumbnail is ready
                    h.previewImageView.tag = id
                    if (thumb.exists()) {
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(thumb.path) }
                            if (bmp != null) {
                                thumbCache.put(id, bmp)
                                thumbMtime[id] = mtime
                                if (h.previewImageView.tag == id) {
                                    stopSkeletonPulse(h.previewImageView)
                                    h.previewImageView.setImageBitmap(bmp)
                                }
                            }
                        }
                    }
                }

                val selected = listener.isItemSelected(currentItem)
                
                h.selectCheck.clearAnimation()
                if (selected && h.selectCheck.visibility != View.VISIBLE) {
                    h.selectCheck.visibility = View.VISIBLE
                    h.selectCheck.alpha = 0f
                    h.selectCheck.scaleX = 0f
                    h.selectCheck.scaleY = 0f
                    h.selectCheck.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
                } else if (!selected && h.selectCheck.visibility == View.VISIBLE) {
                    h.selectCheck.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(200).withEndAction {
                        h.selectCheck.visibility = View.GONE
                    }.start()
                } else {
                    h.selectCheck.alpha = if (selected) 1f else 0f
                    h.selectCheck.scaleX = if (selected) 1f else 0f
                    h.selectCheck.scaleY = if (selected) 1f else 0f
                    h.selectCheck.visibility = if (selected) View.VISIBLE else View.GONE
                }
                
                h.menuButton.visibility = if (selectionMode) View.GONE else View.VISIBLE
                h.cardView.strokeColor = MaterialColors.getColor(h.cardView, com.google.android.material.R.attr.colorPrimary, 0)
                h.cardView.strokeWidth = if (selected) (2 * h.itemView.resources.displayMetrics.density).toInt() else 0
            }
            is DisplayItem.FolderItem -> {
                val h = holder as FolderViewHolder
                val folder = currentItem.folder
                h.nameTextView.text = folder.name
                h.letterTextView.text = folder.name.trim().firstOrNull()?.uppercase() ?: "?"
                val color = try { Color.parseColor(folder.colorHex) } catch (e: IllegalArgumentException) { Color.LTGRAY }
                h.iconImageView.setColorFilter(color)

                val selected = listener.isItemSelected(currentItem)
                
                h.selectCheck?.clearAnimation()
                h.selectOverlay?.clearAnimation()
                
                if (h.selectCheck != null && h.selectOverlay != null) {
                    if (selected && h.selectCheck.visibility != View.VISIBLE) {
                        h.selectCheck.visibility = View.VISIBLE
                        h.selectCheck.alpha = 0f
                        h.selectCheck.scaleX = 0f
                        h.selectCheck.scaleY = 0f
                        h.selectCheck.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
                        
                        h.selectOverlay.visibility = View.VISIBLE
                        h.selectOverlay.alpha = 0f
                        h.selectOverlay.animate().alpha(0.15f).setDuration(200).start()
                    } else if (!selected && h.selectCheck.visibility == View.VISIBLE) {
                        h.selectCheck.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(200).withEndAction {
                            h.selectCheck.visibility = View.GONE
                        }.start()
                        
                        h.selectOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                            h.selectOverlay.visibility = View.GONE
                        }.start()
                    } else {
                        h.selectCheck.alpha = if (selected) 1f else 0f
                        h.selectCheck.scaleX = if (selected) 1f else 0f
                        h.selectCheck.scaleY = if (selected) 1f else 0f
                        h.selectCheck.visibility = if (selected) View.VISIBLE else View.GONE
                        
                        h.selectOverlay.alpha = if (selected) 0.15f else 0f
                        h.selectOverlay.visibility = if (selected) View.VISIBLE else View.GONE
                    }
                }
                
                h.menuButton.visibility = if (selectionMode) View.GONE else View.VISIBLE
                
                h.cardView?.let { card ->
                    card.strokeColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimary, 0)
                    card.strokeWidth = if (selected) (2 * h.itemView.resources.displayMetrics.density).toInt() else 0
                }
            }
        }
    }
}
