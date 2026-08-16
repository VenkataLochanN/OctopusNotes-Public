package com.lochan.octopusnotes

enum class ToolKind {
    PEN, PIXEL_ERASER, STROKE_ERASER, HIGHLIGHTER, SHAPE, LASSO, LASER, TABLE, TEXT, TAPE
}

interface CanvasToolHost {
    fun setTool(kind: ToolKind)
    fun setDrawingMode(enabled: Boolean)
    fun setBrushColor(color: Int)
    fun setBrushSize(size: Float)
    fun setEraserSize(size: Float)
    fun setHighlighterColor(color: Int)
    fun setHighlighterSize(size: Float)
    fun setHighlighterStraight(straight: Boolean)
    fun setStabilizationLevel(level: Int)
    fun setPenLineStyle(style: String)
    fun setShapeType(type: String)
    fun setLassoShape(shape: String)
    fun setTableLineStyle(style: String, thickness: Float)
    fun setTableGrid(rows: Int, cols: Int)
    fun setTapePattern(pattern: String)
    fun setTapeWidth(width: Float)
}

class DrawingViewToolHost(private val view: DrawingView) : CanvasToolHost {
    override fun setTool(kind: ToolKind) = view.setTool(
        when (kind) {
            ToolKind.PEN -> DrawingView.Tool.PEN
            ToolKind.PIXEL_ERASER -> DrawingView.Tool.PIXEL_ERASER
            ToolKind.STROKE_ERASER -> DrawingView.Tool.STROKE_ERASER
            ToolKind.HIGHLIGHTER -> DrawingView.Tool.HIGHLIGHTER
            ToolKind.SHAPE -> DrawingView.Tool.SHAPE
            ToolKind.LASSO -> DrawingView.Tool.LASSO
            ToolKind.LASER -> DrawingView.Tool.LASER
            ToolKind.TABLE -> DrawingView.Tool.TABLE
            ToolKind.TEXT -> DrawingView.Tool.TEXT
            ToolKind.TAPE -> DrawingView.Tool.TAPE
        }
    )
    override fun setDrawingMode(enabled: Boolean) = view.setDrawingMode(enabled)
    override fun setBrushColor(color: Int) = view.setBrushColor(color)
    override fun setBrushSize(size: Float) = view.setBrushSize(size)

    override fun setEraserSize(size: Float) = view.setBrushSize(size)
    override fun setHighlighterColor(color: Int) = view.setHighlighterColor(color)
    override fun setHighlighterSize(size: Float) = view.setHighlighterSize(size)
    override fun setHighlighterStraight(straight: Boolean) = view.setHighlighterStraight(straight)
    override fun setStabilizationLevel(level: Int) = view.setStabilizationLevel(level)
    override fun setPenLineStyle(style: String) = view.setPenLineStyle(style)
    override fun setShapeType(type: String) = view.setShapeType(type)
    override fun setLassoShape(shape: String) = view.setLassoShape(shape)
    override fun setTableLineStyle(style: String, thickness: Float) = view.setTableLineStyle(style, thickness)
    override fun setTableGrid(rows: Int, cols: Int) {
        view.tableGridRows = rows
        view.tableGridCols = cols
    }
    override fun setTapePattern(pattern: String) { view.tapePattern = pattern }
    override fun setTapeWidth(width: Float) { view.tapeWidth = width }
}

class InfiniteCanvasToolHost(private val view: InfiniteCanvasView) : CanvasToolHost {
    override fun setTool(kind: ToolKind) = view.setTool(
        when (kind) {
            ToolKind.PEN -> InfiniteCanvasView.Tool.PEN
            ToolKind.PIXEL_ERASER -> InfiniteCanvasView.Tool.PIXEL_ERASER
            ToolKind.STROKE_ERASER -> InfiniteCanvasView.Tool.STROKE_ERASER
            ToolKind.HIGHLIGHTER -> InfiniteCanvasView.Tool.HIGHLIGHTER
            ToolKind.SHAPE -> InfiniteCanvasView.Tool.SHAPE
            ToolKind.LASSO -> InfiniteCanvasView.Tool.LASSO
            ToolKind.LASER -> InfiniteCanvasView.Tool.LASER
            ToolKind.TABLE -> InfiniteCanvasView.Tool.TABLE
            ToolKind.TEXT -> InfiniteCanvasView.Tool.TEXT
            ToolKind.TAPE -> InfiniteCanvasView.Tool.TAPE
        }
    )
    override fun setDrawingMode(enabled: Boolean) = view.setDrawingMode(enabled)
    override fun setBrushColor(color: Int) = view.setBrushColor(color)
    override fun setBrushSize(size: Float) = view.setBrushSize(size)
    override fun setEraserSize(size: Float) = view.setEraserSize(size)
    override fun setHighlighterColor(color: Int) = view.setHighlighterColor(color)
    override fun setHighlighterSize(size: Float) = view.setHighlighterSize(size)
    override fun setHighlighterStraight(straight: Boolean) = view.setHighlighterStraight(straight)
    override fun setStabilizationLevel(level: Int) = view.setStabilizationLevel(level)
    override fun setPenLineStyle(style: String) { view.penLineStyle = style }
    override fun setShapeType(type: String) { view.shapeType = type }
    override fun setLassoShape(shape: String) = view.setLassoShape(shape)
    override fun setTableLineStyle(style: String, thickness: Float) = view.setTableLineStyle(style, thickness)
    override fun setTableGrid(rows: Int, cols: Int) = view.setTableGrid(rows, cols)
    override fun setTapePattern(pattern: String) { view.tapePattern = pattern }
    override fun setTapeWidth(width: Float) { view.tapeWidth = width }
}
