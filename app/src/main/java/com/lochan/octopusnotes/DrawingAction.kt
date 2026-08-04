package com.lochan.octopusnotes

sealed class DrawingAction {
    abstract fun execute(manager: StrokeManager)
    abstract fun undo(manager: StrokeManager)

    data class AddStroke(val pageIndex: Int, val stroke: StrokeData) : DrawingAction() {
        override fun execute(manager: StrokeManager) = manager.addStrokeToPage(pageIndex, stroke)
        override fun undo(manager: StrokeManager) = manager.removeStrokeFromPage(pageIndex, stroke.id)
    }

    data class DeleteStrokes(val pageIndex: Int, val strokes: List<StrokeData>) : DrawingAction() {
        override fun execute(manager: StrokeManager) {
            strokes.forEach { manager.removeStrokeFromPage(pageIndex, it.id) }
        }
        override fun undo(manager: StrokeManager) {
            strokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
    }

    data class ReplaceStrokes(val pageIndex: Int, val originalStrokes: List<StrokeData>, val newStrokes: List<StrokeData>) : DrawingAction() {
        override fun execute(manager: StrokeManager) {
            originalStrokes.forEach { manager.removeStrokeFromPage(pageIndex, it.id) }
            newStrokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
        override fun undo(manager: StrokeManager) {
            newStrokes.forEach { manager.removeStrokeFromPage(pageIndex, it.id) }
            originalStrokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
    }

    data class MoveStrokes(val pageIndex: Int, val strokeIds: List<String>, val dx: Float, val dy: Float) : DrawingAction() {
        override fun execute(manager: StrokeManager) = manager.translateStrokes(pageIndex, strokeIds, dx, dy)
        override fun undo(manager: StrokeManager) = manager.translateStrokes(pageIndex, strokeIds, -dx, -dy)
    }

    // For pixel eraser: stores original strokes and the resulting split strokes
    data class PixelErase(val pageIndex: Int, val originalStrokes: List<StrokeData>, val newStrokes: List<StrokeData>) : DrawingAction() {
        override fun execute(manager: StrokeManager) {
            originalStrokes.forEach { manager.removeStrokeFromPage(pageIndex, it.id) }
            newStrokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
        override fun undo(manager: StrokeManager) {
            newStrokes.forEach { manager.removeStrokeFromPage(pageIndex, it.id) }
            originalStrokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
    }

    class BatchAction(val actions: List<DrawingAction>) : DrawingAction() {
        override fun execute(manager: StrokeManager) = actions.forEach { it.execute(manager) }
        override fun undo(manager: StrokeManager) = actions.asReversed().forEach { it.undo(manager) }
    }
}