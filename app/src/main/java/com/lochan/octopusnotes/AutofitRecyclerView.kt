package com.lochan.octopusnotes

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * A custom RecyclerView that automatically calculates the number of columns
 * to fit based on a specified column width. Supports switching to a single-column
 * list mode via [setListMode].
 */
class AutofitRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    private val gridManager = GridLayoutManager(getContext(), 2)
    private val linearManager = LinearLayoutManager(getContext())
    private var columnWidth = -1
    private var columnOverride = 0 // 0 = auto-fit
    private var listMode = false

    /** Force a fixed column count (>=2), or 0 to auto-fit by width. */
    fun setColumnOverride(count: Int) {
        columnOverride = if (count in 2..8) count else 0
        requestLayout()
    }

    /** Switch between single-column list layout and grid layout. */
    fun setListMode(enabled: Boolean) {
        if (listMode == enabled) return
        listMode = enabled
        layoutManager = if (enabled) linearManager else gridManager
    }

    fun isListMode(): Boolean = listMode

    init {
        if (attrs != null) {
            val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.AutofitRecyclerView)
            columnWidth = attrsArray.getDimensionPixelSize(R.styleable.AutofitRecyclerView_columnWidth, -1)
            attrsArray.recycle()
        }

        layoutManager = gridManager
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        if (listMode) return // LinearLayoutManager handles its own layout
        gridManager.spanCount = when {
            columnOverride >= 2 -> columnOverride
            columnWidth > 0 -> max(2, measuredWidth / columnWidth) // never fewer than 2 per row
            else -> 2
        }
    }
}