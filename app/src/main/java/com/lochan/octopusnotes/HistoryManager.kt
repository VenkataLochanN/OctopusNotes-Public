package com.lochan.octopusnotes

class HistoryManager(private val maxActions: Int = 100) {
    private val undoStack = ArrayDeque<DrawingAction>()
    private val redoStack = ArrayDeque<DrawingAction>()

    var onMutation: (() -> Unit)? = null

    var onHistoryChanged: (() -> Unit)? = null

    fun execute(action: DrawingAction, manager: StrokeManager) {
        action.execute(manager)
        undoStack.addLast(action)
        redoStack.clear()
        trimStacks()
        onMutation?.invoke()
        onHistoryChanged?.invoke()
    }

    fun undo(manager: StrokeManager): Boolean {
        if (undoStack.isEmpty()) return false
        val action = undoStack.removeLast()
        action.undo(manager)
        redoStack.addLast(action)
        onMutation?.invoke()
        onHistoryChanged?.invoke()
        return true
    }

    fun redo(manager: StrokeManager): Boolean {
        if (redoStack.isEmpty()) return false
        val action = redoStack.removeLast()
        action.execute(manager)
        undoStack.addLast(action)
        onMutation?.invoke()
        onHistoryChanged?.invoke()
        return true
    }

    fun allReferencedStrokes(): List<StrokeData> {
        val out = ArrayList<StrokeData>()
        val seen = java.util.IdentityHashMap<StrokeData, Boolean>()
        fun add(s: StrokeData) { if (seen.put(s, true) == null) out.add(s) }
        fun walk(a: DrawingAction) {
            when (a) {
                is DrawingAction.AddStroke -> add(a.stroke)
                is DrawingAction.DeleteStrokes -> a.strokes.forEach(::add)
                is DrawingAction.ReplaceStrokes -> {
                    a.originalStrokes.forEach(::add)
                    a.newStrokes.forEach(::add)
                }
                is DrawingAction.PixelErase -> {
                    a.originalStrokes.forEach(::add)
                    a.newStrokes.forEach(::add)
                }
                is DrawingAction.BatchAction -> a.actions.forEach(::walk)

                is DrawingAction.MoveStrokes -> Unit
            }
        }
        (undoStack + redoStack).forEach(::walk)
        return out
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun trimStacks() {
        while (undoStack.size > maxActions) {
            undoStack.removeFirst()
        }
    }
}