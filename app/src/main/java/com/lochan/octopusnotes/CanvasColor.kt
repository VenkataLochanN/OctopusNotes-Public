package com.lochan.octopusnotes

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.AttrRes

object CanvasColor {

    const val PREFS_NAME = "OctopusNotesPrefs"
    const val PREFS_KEY = "canvas_color"

    const val RESET = Int.MIN_VALUE

    @AttrRes
    fun materialYouAttr(): Int = com.google.android.material.R.attr.colorSurfaceContainerLow

    fun materialYou(context: Context): Int =
        com.google.android.material.color.MaterialColors.getColor(
            context, materialYouAttr(), context.getColor(R.color.pdf_surround)
        )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(context: Context): Int {
        val stored = prefs(context).getInt(PREFS_KEY, RESET)
        return if (stored == RESET) materialYou(context) else stored
    }

    fun isCustom(context: Context): Boolean = prefs(context).getInt(PREFS_KEY, RESET) != RESET

    fun reset(context: Context) {
        prefs(context).edit().remove(PREFS_KEY).apply()
    }
}
