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

        fun onItemLongPress(item: DisplayItem)

        fun onSelectionLongPress(item: DisplayItem): Boolean
        fun isSelectionMode(): Boolean
        fun isItemSelected(item: DisplayItem): Boolean
        fun toggleSelection(item: DisplayItem)
    }

    companion object {
        private const val TYPE_NOTEBOOK = 0
        private const val TYPE_FOLDER = 1
    }

    private val thumbCache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }
    private val thumbMtime = HashMap<Long, Long>()

    inner class NotebookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val thumbCard: MaterialCardView = itemView.findViewById(R.id.notebookThumbCard) ?: itemView as MaterialCardView
        val nameTextView: MarqueeTextView = itemView.findViewById(R.id.notebookNameTextView)
        val previewImageView: ImageView = itemView.findViewById(R.id.notebookPreviewImageView)
        val menuButton: ImageView = itemView.findViewById(R.id.notebookMenuButton)
        val selectCheck: ImageView = itemView.findViewById(R.id.notebookSelectCheck)
        val tagView: View = itemView.findViewById(R.id.notebookTagView)

        init {
            val onLongPress = {

                if (listener.isSelectionMode()) listener.onSelectionLongPress(items[adapterPosition])
                else { listener.onItemLongPress(items[adapterPosition]); true }
            }
            itemView.setOnClickListener {
                val item = items[adapterPosition]
                if (listener.isSelectionMode()) listener.toggleSelection(item)
                else listener.onNotebookClick((item as DisplayItem.NotebookItem).notebook)
            }
            itemView.setOnLongClickListener { onLongPress() }
            val openMenu = {
                val item = items[adapterPosition]
                if (listener.isSelectionMode()) listener.toggleSelection(item)
                else listener.onNotebookMenu((item as DisplayItem.NotebookItem).notebook, menuButton)
            }
            menuButton.setOnClickListener { openMenu() }
            nameTextView.setOnClickListener { openMenu() }
            nameTextView.setOnLongClickListener { onLongPress() }

            itemView.findViewById<View>(R.id.notebookPill)?.let { pill ->
                pill.setOnClickListener { openMenu() }
                pill.setOnLongClickListener { onLongPress() }
            }
        }
    }

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.folderNameTextView)
        val iconImageView: ImageView = itemView.findViewById(R.id.folderIconImageView)
        val letterTextView: TextView = itemView.findViewById(R.id.folderLetter)
        val tagView: View = itemView.findViewById(R.id.folderTagView)
        val menuButton: ImageView = itemView.findViewById(R.id.folderMenuButton)
        val selectCheck: ImageView? = itemView.findViewById(R.id.folderSelectCheck)
        val selectOverlay: View? = itemView.findViewById(R.id.folderSelectOverlay)

        val iconCard: MaterialCardView = itemView.findViewById(R.id.folderIconCard) ?: itemView as MaterialCardView

        init {
            val onLongPress = {

                if (listener.isSelectionMode()) listener.onSelectionLongPress(items[adapterPosition])
                else { listener.onItemLongPress(items[adapterPosition]); true }
            }
            itemView.setOnClickListener {
                val item = items[adapterPosition]
                if (listener.isSelectionMode()) listener.toggleSelection(item)
                else listener.onFolderClick((item as DisplayItem.FolderItem).folder)
            }
            itemView.setOnLongClickListener { onLongPress() }
            val openMenu = {
                val item = items[adapterPosition]
                if (listener.isSelectionMode()) listener.toggleSelection(item)
                else listener.onFolderMenu((item as DisplayItem.FolderItem).folder, menuButton)
            }
            menuButton.setOnClickListener { openMenu() }
            nameTextView.setOnClickListener { openMenu() }
            nameTextView.setOnLongClickListener { onLongPress() }

            itemView.findViewById<View>(R.id.folderNamePill)?.let { pill ->
                pill.setOnClickListener { openMenu() }
                pill.setOnLongClickListener { onLongPress() }
            }
        }
    }

    private fun startSkeletonPulse(v: ImageView) {
        if (v.getTag(R.id.skeleton_anim_tag) == true) return
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

                h.nameTextView.setMarqueeEnabled(
                    h.itemView.context.getSharedPreferences("OctopusNotesPrefs", android.content.Context.MODE_PRIVATE)
                        .getBoolean("marquee_text", false)
                )

                val thumb = File(h.itemView.context.filesDir, "thumb_$id.png")
                val mtime = if (thumb.exists()) thumb.lastModified() else 0L
                val cached = thumbCache.get(id)
                if (cached != null && thumbMtime[id] == mtime) {
                    stopSkeletonPulse(h.previewImageView)
                    h.previewImageView.setImageBitmap(cached)
                } else {
                    h.previewImageView.setImageResource(R.drawable.bg_thumb_skeleton)
                    startSkeletonPulse(h.previewImageView)
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

                val tagHex = currentItem.notebook.tagColorHex
                if (tagHex == null) {
                    h.tagView.visibility = View.GONE
                } else {
                    h.tagView.visibility = View.VISIBLE
                    val tagColor = try { Color.parseColor(tagHex) } catch (e: IllegalArgumentException) { Color.TRANSPARENT }
                    (h.tagView.background as? android.graphics.drawable.LayerDrawable)
                        ?.findDrawableByLayerId(R.id.color_shape)
                        ?.let { it as? android.graphics.drawable.GradientDrawable }
                        ?.setColor(tagColor)
                }

                val selected = listener.isItemSelected(currentItem)
                bindSelectionVisuals(h.selectCheck, null, selected, itemKey(currentItem))

                h.menuButton.visibility = if (selectionMode) View.GONE else View.VISIBLE

                val thumbDensity = h.itemView.resources.displayMetrics.density
                h.thumbCard.strokeColor = MaterialColors.getColor(
                    h.thumbCard,
                    if (selected) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOutlineVariant,
                    0
                )
                h.thumbCard.strokeWidth = ((if (selected) 2 else 1) * thumbDensity).toInt()
            }
            is DisplayItem.FolderItem -> {
                val h = holder as FolderViewHolder
                val folder = currentItem.folder
                h.nameTextView.text = folder.name
                h.letterTextView.text = folder.name.trim().firstOrNull()?.uppercase() ?: "?"
                val color = try { Color.parseColor(folder.colorHex) } catch (e: IllegalArgumentException) { Color.LTGRAY }
                h.iconImageView.setColorFilter(color)

                val tagHex = folder.tagColorHex
                if (tagHex == null) {
                    h.tagView.visibility = View.GONE
                } else {
                    h.tagView.visibility = View.VISIBLE
                    val tagColor = try { Color.parseColor(tagHex) } catch (e: IllegalArgumentException) { Color.TRANSPARENT }
                    (h.tagView.background as? android.graphics.drawable.LayerDrawable)
                        ?.findDrawableByLayerId(R.id.color_shape)
                        ?.let { it as? android.graphics.drawable.GradientDrawable }
                        ?.setColor(tagColor)
                }

                val selected = listener.isItemSelected(currentItem)
                bindSelectionVisuals(h.selectCheck, h.selectOverlay, selected, itemKey(currentItem))

                h.menuButton.visibility = if (selectionMode) View.GONE else View.VISIBLE

                h.iconCard.strokeColor = MaterialColors.getColor(h.iconCard, com.google.android.material.R.attr.colorPrimary, 0)
                h.iconCard.strokeWidth = if (selected) (2 * h.itemView.resources.displayMetrics.density).toInt() else 0
            }
        }
    }

    private val popInKeys = HashSet<String>()

    private fun itemKey(item: DisplayItem): String = when (item) {
        is DisplayItem.FolderItem -> "f${item.folder.id}"
        is DisplayItem.NotebookItem -> "n${item.notebook.id}"
    }

    fun animateTickIn(item: DisplayItem) {
        popInKeys.add(itemKey(item))
    }

    private fun bindSelectionVisuals(check: View?, overlay: View?, selected: Boolean, key: String) {
        val popIn = selected && popInKeys.remove(key)
        if (!selected) popInKeys.remove(key)
        bindTick(check, selected, popIn, key)
        bindOverlay(overlay, selected, popIn, key)
    }

    private fun bindTick(check: View?, selected: Boolean, popIn: Boolean, key: String) {
        if (check == null) return
        check.animate().cancel()
        if (selected) {
            check.setTag(R.id.tick_item_key, key)
            check.visibility = View.VISIBLE
            if (popIn) {
                check.alpha = 0f
                check.scaleX = 0f
                check.scaleY = 0f
                check.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
            } else {
                check.alpha = 1f
                check.scaleX = 1f
                check.scaleY = 1f
            }
        } else {
            val tickBelongsToThisItem = check.getTag(R.id.tick_item_key) == key
            check.setTag(R.id.tick_item_key, null)
            if (check.visibility == View.VISIBLE && tickBelongsToThisItem) {

                check.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(200)
                    .withEndAction { if (check.visibility != View.GONE) check.visibility = View.GONE }
                    .start()
            } else {

                check.alpha = 0f
                check.scaleX = 0f
                check.scaleY = 0f
                check.visibility = View.GONE
            }
        }
    }

    private fun bindOverlay(overlay: View?, selected: Boolean, popIn: Boolean, key: String) {
        if (overlay == null) return
        overlay.animate().cancel()
        if (selected) {
            overlay.setTag(R.id.tick_item_key, key)
            overlay.visibility = View.VISIBLE
            if (popIn) {
                overlay.alpha = 0f
                overlay.animate().alpha(0.15f).setDuration(200).start()
            } else {
                overlay.alpha = 0.15f
            }
        } else {
            val tintBelongsToThisItem = overlay.getTag(R.id.tick_item_key) == key
            overlay.setTag(R.id.tick_item_key, null)
            if (overlay.visibility == View.VISIBLE && tintBelongsToThisItem) {
                overlay.animate().alpha(0f).setDuration(200)
                    .withEndAction { if (overlay.visibility != View.GONE) overlay.visibility = View.GONE }
                    .start()
            } else {
                overlay.alpha = 0f
                overlay.visibility = View.GONE
            }
        }
    }
}
