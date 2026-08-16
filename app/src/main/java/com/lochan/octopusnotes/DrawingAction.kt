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

            manager.removeStrokesFromPage(pageIndex, strokes.mapTo(HashSet()) { it.id })
        }
        override fun undo(manager: StrokeManager) {
            strokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
    }

    data class ReplaceStrokes(val pageIndex: Int, val originalStrokes: List<StrokeData>, val newStrokes: List<StrokeData>) : DrawingAction() {
        override fun execute(manager: StrokeManager) {
            manager.removeStrokesFromPage(pageIndex, originalStrokes.mapTo(HashSet()) { it.id })
            newStrokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
        override fun undo(manager: StrokeManager) {
            manager.removeStrokesFromPage(pageIndex, newStrokes.mapTo(HashSet()) { it.id })
            originalStrokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
    }

    data class MoveStrokes(val pageIndex: Int, val strokeIds: List<String>, val dx: Float, val dy: Float) : DrawingAction() {
        override fun execute(manager: StrokeManager) = manager.translateStrokes(pageIndex, strokeIds, dx, dy)
        override fun undo(manager: StrokeManager) = manager.translateStrokes(pageIndex, strokeIds, -dx, -dy)
    }

    data class PixelErase(val pageIndex: Int, val originalStrokes: List<StrokeData>, val newStrokes: List<StrokeData>) : DrawingAction() {
        override fun execute(manager: StrokeManager) {
            manager.removeStrokesFromPage(pageIndex, originalStrokes.mapTo(HashSet()) { it.id })
            newStrokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
        override fun undo(manager: StrokeManager) {
            manager.removeStrokesFromPage(pageIndex, newStrokes.mapTo(HashSet()) { it.id })
            originalStrokes.forEach { manager.addStrokeToPage(pageIndex, it) }
        }
    }

    class BatchAction(val actions: List<DrawingAction>) : DrawingAction() {
        override fun execute(manager: StrokeManager) = actions.forEach { it.execute(manager) }
        override fun undo(manager: StrokeManager) = actions.asReversed().forEach { it.undo(manager) }
    }
}