package com.lochan.octopusnotes

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One outline node — from the PDF's embedded outline or a user-added entry. */
data class OutlineUiEntry(
    val title: String,
    val page: Int,
    val isUser: Boolean,
    val depth: Int = 0,
    val children: List<OutlineUiEntry> = emptyList()
)

/**
 * Outline tab list: page thumbnail, title, "From PDF"/"Added by you" subtitle, page number,
 * a chevron to expand nested chapters, and a per-row overflow menu. The entry for the
 * currently open page is tinted with the primary colour.
 */
class OutlineAdapter(
    private val roots: List<OutlineUiEntry>,
    private val currentPage: Int,
    private val renderer: PdfThumbnailRenderer,
    private val scope: CoroutineScope,
    private val onClick: (Int) -> Unit,
    private val onMenu: (OutlineUiEntry, View) -> Unit
) : RecyclerView.Adapter<OutlineAdapter.VH>() {

    private val expanded = HashSet<OutlineUiEntry>()
    private var visible: List<OutlineUiEntry> = flatten()

    private val thumbCache = object : LruCache<Int, Bitmap>(24) {}

    private fun flatten(): List<OutlineUiEntry> {
        val out = ArrayList<OutlineUiEntry>()
        fun add(e: OutlineUiEntry) {
            out.add(e)
            if (e in expanded) e.children.forEach(::add)
        }
        roots.forEach(::add)
        return out
    }

    private fun toggle(e: OutlineUiEntry) {
        if (!expanded.add(e)) expanded.remove(e)
        visible = flatten()
        notifyDataSetChanged()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val row: View = itemView.findViewById(R.id.outlineRow)
        val chevron: ImageButton = itemView.findViewById(R.id.outlineChevron)
        val thumb: ImageView = itemView.findViewById(R.id.outlineThumb)
        val title: TextView = itemView.findViewById(R.id.outlineTitle)
        val sub: TextView = itemView.findViewById(R.id.outlineSub)
        val page: TextView = itemView.findViewById(R.id.outlinePage)
        val menu: ImageButton = itemView.findViewById(R.id.outlineMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_outline_entry, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = visible.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = visible[position]
        val density = holder.itemView.resources.displayMetrics.density

        // Indent nested levels.
        holder.row.setPaddingRelative(
            (8 * density).toInt() + (16 * density).toInt() * e.depth,
            holder.row.paddingTop,
            (4 * density).toInt(),
            holder.row.paddingBottom
        )

        holder.title.text = e.title.ifBlank { "Page ${e.page + 1}" }
        holder.sub.text = if (e.isUser) "Added by you" else "From PDF"
        holder.page.text = if (e.page >= 0) "${e.page + 1}" else ""

        // Current page → primary tint on title + page number (like the reference design).
        val primary = MaterialColors.getColor(holder.title, com.google.android.material.R.attr.colorPrimary)
        val onSurface = MaterialColors.getColor(holder.title, com.google.android.material.R.attr.colorOnSurface)
        val variant = MaterialColors.getColor(holder.title, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val isCurrent = e.page == currentPage
        holder.title.setTextColor(if (isCurrent) primary else onSurface)
        holder.page.setTextColor(if (isCurrent) primary else variant)

        // Chevron only for nodes with children; rotate 90° when expanded.
        if (e.children.isEmpty()) {
            holder.chevron.visibility = View.INVISIBLE
            holder.chevron.setOnClickListener(null)
        } else {
            holder.chevron.visibility = View.VISIBLE
            holder.chevron.rotation = if (e in expanded) 90f else 0f
            holder.chevron.setOnClickListener { toggle(e) }
        }

        holder.row.setOnClickListener {
            if (e.page >= 0) onClick(e.page)
            else if (e.children.isNotEmpty()) toggle(e)
        }
        holder.menu.setOnClickListener { onMenu(e, it) }

        // Page thumbnail (async, small cache).
        holder.thumb.setImageBitmap(null)
        if (e.page >= 0) {
            val cached = thumbCache.get(e.page)
            if (cached != null && !cached.isRecycled) {
                holder.thumb.setImageBitmap(cached)
            } else {
                holder.thumb.tag = e.page
                scope.launch {
                    // Cancellable render on the renderer's own workers — a recycled row's
                    // queued render is skipped instead of running against a closed renderer.
                    val bmp = try {
                        renderer.renderSuspend(e.page, (84 * density).toInt())
                    } catch (t: Throwable) { null } ?: return@launch
                    thumbCache.put(e.page, bmp)
                    if (holder.thumb.tag == e.page) holder.thumb.setImageBitmap(bmp)
                }
            }
        }
    }
}
