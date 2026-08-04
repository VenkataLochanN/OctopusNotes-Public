package com.lochan.octopusnotes

class HistoryManager(private val maxActions: Int = 100) {
    private val undoStack = ArrayDeque<DrawingAction>()
    private val redoStack = ArrayDeque<DrawingAction>()

    /** Fired whenever the stroke data changes (execute/undo/redo) — used for dirty tracking. */
    var onMutation: (() -> Unit)? = null

    fun execute(action: DrawingAction, manager: StrokeManager) {
        action.execute(manager)
        undoStack.addLast(action)
        redoStack.clear() // Clear redo stack on new action
        trimStacks()
        onMutation?.invoke()
    }

    fun undo(manager: StrokeManager): Boolean {
        if (undoStack.isEmpty()) return false
        val action = undoStack.removeLast()
        action.undo(manager)
        redoStack.addLast(action)
        onMutation?.invoke()
        return true
    }

    fun redo(manager: StrokeManager): Boolean {
        if (redoStack.isEmpty()) return false
        val action = redoStack.removeLast()
        action.execute(manager)
        undoStack.addLast(action)
        onMutation?.invoke()
        return true
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