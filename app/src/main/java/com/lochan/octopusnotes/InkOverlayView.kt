package com.lochan.octopusnotes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A lightweight transparent overlay that draws ink strokes and search
 * highlights on top of a single PDF page bitmap.
 *
 * Each RecyclerView item gets its own instance; the adapter sets [pageIndex],
 * [strokeManager], and [highlightsByPage] before the view is displayed.
 */
class InkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pageIndex: Int = -1
    var strokeManager: StrokeManager? = null

    /** Page dimensions in the coordinate space strokes were authored in. */
    var strokePageWidth: Float = 0f
    var strokePageHeight: Float = 0f

    /** Current zoom level for stroke width scaling. */
    var zoomLevel: Float = 1f

    /** Shared search-highlight map — same object the activity uses. */
    var highlightsByPage: Map<Int, List<RectF>>? = null

    var searchFillPaint: Paint? = null
    var activeFillPaint: Paint? = null
    var activeHighlightPage: Int = -1
    var activeHighlightRect: RectF? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageIndex < 0 || strokePageWidth <= 0f || strokePageHeight <= 0f) return

        val sm = strokeManager ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scaleX = viewW / strokePageWidth
        val scaleY = viewH / strokePageHeight

        sm.drawPageStrokes(pageIndex, canvas, scaleX, scaleY)

        val highlights = highlightsByPage?.get(pageIndex) ?: return
        val fillPaint = searchFillPaint ?: return
        val activePaint = activeFillPaint ?: fillPaint

        for (r in highlights) {
            val isActive = activeHighlightPage == pageIndex && activeHighlightRect == r
            val px = RectF(
                r.left * viewW,
                r.top * viewH,
                r.right * viewW,
                r.bottom * viewH
            )
            canvas.drawRect(px, if (isActive) activePaint else fillPaint)
        }
    }
}
