package com.lochan.octopusnotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TabSession {
    const val PREFS_ENABLED = "TABS_MODE_ENABLED"
    private const val PREFS_SESSION = "TABS_SESSION"
    private const val PREFS_NAME = "OctopusNotesPrefs"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREFS_ENABLED, false)

    fun getTabs(context: Context): List<Long> {
        val raw = prefs(context).getString(PREFS_SESSION, null) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(raw).getJSONArray("tabs")
            (0 until arr.length()).map { arr.getLong(it) }.distinct()
        }.getOrDefault(emptyList())
    }

    fun currentId(context: Context): Long {
        val raw = prefs(context).getString(PREFS_SESSION, null) ?: return -1L
        return runCatching { JSONObject(raw).getLong("current") }.getOrDefault(-1L)
    }

    fun addOrFocus(context: Context, notebookId: Long) {
        val tabs = getTabs(context).toMutableList()
        if (!tabs.contains(notebookId)) tabs.add(notebookId)
        save(context, tabs, notebookId)
    }

    fun remove(context: Context, notebookId: Long): Long {
        val tabs = getTabs(context).toMutableList()
        val idx = tabs.indexOf(notebookId)
        val cur = currentId(context)
        if (idx == -1) return cur
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            save(context, tabs, -1L)
            return -1L
        }

        val next = if (notebookId == cur) tabs[minOf(idx, tabs.size - 1)] else cur
        save(context, tabs, next)
        return next
    }

    fun replace(context: Context, tabs: List<Long>, currentId: Long) {
        save(context, tabs.distinct(), currentId)
    }

    private fun save(context: Context, tabs: List<Long>, currentId: Long) {
        val obj = JSONObject()
        obj.put("tabs", JSONArray(tabs))
        obj.put("current", currentId)
        prefs(context).edit().putString(PREFS_SESSION, obj.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
