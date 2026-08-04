package com.lochan.octopusnotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where the page currently at [index] ends up once the page at [from] is moved to [to].
 * Shared by every store that keys data by page number, so a reorder can't desync them.
 */
fun pageIndexAfterMove(index: Int, from: Int, to: Int): Int = when {
    index == from -> to
    // Dragged later: everything it passed over shifts down one.
    from < to && index > from && index <= to -> index - 1
    // Dragged earlier: everything it passed over shifts up one.
    from > to && index >= to && index < from -> index + 1
    else -> index
}

/**
 * Per-notebook page metadata: bookmarked pages and user-added outline entries.
 *
 * Page indices are kept in sync as pages are inserted/removed via [onPageInserted] /
 * [onPageRemoved] (call these alongside the matching PDF + stroke edits).
 */
class PageMetaStore(context: Context, private val notebookId: Long) {

    data class OutlineEntry(val page: Int, val title: String)

    private val prefs = context.getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
    private val bmKey = "page_bookmarks_$notebookId"
    private val outlineKey = "page_outline_$notebookId"

    // --- Bookmarks ---

    fun bookmarks(): MutableSet<Int> {
        val raw = prefs.getString(bmKey, "") ?: ""
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableSet()
    }

    fun isBookmarked(page: Int): Boolean = bookmarks().contains(page)

    fun toggleBookmark(page: Int) {
        val set = bookmarks()
        if (!set.add(page)) set.remove(page)
        saveBookmarks(set)
    }

    private fun saveBookmarks(set: Set<Int>) {
        prefs.edit().putString(bmKey, set.sorted().joinToString(",")).apply()
    }

    // --- User outline entries ---

    fun userOutline(): MutableList<OutlineEntry> {
        val raw = prefs.getString(outlineKey, "[]") ?: "[]"
        val out = mutableListOf<OutlineEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(OutlineEntry(o.getInt("p"), o.optString("t")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun addOutlineEntry(page: Int, title: String) {
        val list = userOutline()
        list.add(OutlineEntry(page, title))
        saveOutline(list)
    }

    fun removeOutlineEntry(page: Int, title: String) {
        saveOutline(userOutline().filterNot { it.page == page && it.title == title })
    }

    private fun saveOutline(list: List<OutlineEntry>) {
        val arr = JSONArray()
        for (e in list.sortedBy { it.page }) {
            arr.put(JSONObject().put("p", e.page).put("t", e.title))
        }
        prefs.edit().putString(outlineKey, arr.toString()).apply()
    }

    // --- Index maintenance ---

    /** A page was inserted at [at]: shift bookmarks/outline at or after it up by one. */
    fun onPageInserted(at: Int) {
        saveBookmarks(bookmarks().map { if (it >= at) it + 1 else it }.toSet())
        saveOutline(userOutline().map { if (it.page >= at) it.copy(page = it.page + 1) else it })
    }

    /** The page at [from] was dragged to [to]: renumber everything it moved past. */
    fun onPageMoved(from: Int, to: Int) {
        if (from == to) return
        saveBookmarks(bookmarks().map { pageIndexAfterMove(it, from, to) }.toSet())
        saveOutline(userOutline().map { it.copy(page = pageIndexAfterMove(it.page, from, to)) })
    }

    /** The page at [at] was removed: drop its entries and shift later ones down by one. */
    fun onPageRemoved(at: Int) {
        saveBookmarks(bookmarks().filter { it != at }.map { if (it > at) it - 1 else it }.toSet())
        saveOutline(userOutline().filter { it.page != at }.map { if (it.page > at) it.copy(page = it.page - 1) else it })
    }
}
