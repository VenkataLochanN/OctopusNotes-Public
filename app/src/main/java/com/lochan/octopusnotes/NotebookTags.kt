package com.lochan.octopusnotes

import android.content.Context

object NotebookTags {

    data class TagColor(val hex: String, val name: String)

    private const val PREFS_NAME = "OctopusNotesPrefs"
    private fun nameKey(hex: String) = "tag_name_$hex"

    val COLORS = listOf(
        TagColor("#FF3B30", "Red"),
        TagColor("#FF9500", "Orange"),
        TagColor("#FFCC00", "Yellow"),
        TagColor("#34C759", "Green"),
        TagColor("#007AFF", "Blue"),
        TagColor("#AF52DE", "Purple"),
        TagColor("#8E8E93", "Gray")
    )

    fun nameOf(context: Context, hex: String?): String? {
        if (hex == null) return null
        val custom = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(nameKey(hex), null)?.trim()
        if (!custom.isNullOrEmpty()) return custom
        return COLORS.firstOrNull { it.hex.equals(hex, ignoreCase = true) }?.name
    }

    fun rename(context: Context, hex: String, newName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(nameKey(hex), newName.trim())
            .apply()
    }
}
