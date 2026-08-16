package com.lochan.octopusnotes

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

class AutofitRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    private val gridManager = GridLayoutManager(getContext(), 2)
    private val linearManager = LinearLayoutManager(getContext())
    private var columnWidth = -1
    private var columnOverride = 0
    private var listMode = false

    fun setColumnOverride(count: Int) {
        columnOverride = if (count in 2..8) count else 0
        requestLayout()
    }

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
        if (listMode) return
        gridManager.spanCount = when {
            columnOverride >= 2 -> columnOverride
            columnWidth > 0 -> max(2, measuredWidth / columnWidth)
            else -> 2
        }
    }
}