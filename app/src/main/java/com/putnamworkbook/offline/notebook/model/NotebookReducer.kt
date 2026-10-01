package com.putnamworkbook.offline.notebook.model

object NotebookReducer {
    fun applyEdits(page: NotebookPage, edits: List<NotebookEdit>): NotebookPage =
        edits.fold(page) { current, edit -> applyEdit(current, edit) }

    fun undo(page: NotebookPage): NotebookPage {
        if (page.appliedEditCount == 0) return page
        val group = page.editHistory[page.appliedEditCount - 1]
        return page.copy(
            strokes = changeActiveState(
                references = page.strokes,
                activate = group.deactivatedStrokeIds,
                deactivate = group.activatedStrokeIds,
            ),
            appliedEditCount = page.appliedEditCount - 1,
        )
    }

    fun redo(page: NotebookPage): NotebookPage {
        if (page.appliedEditCount == page.editHistory.size) return page
        val group = page.editHistory[page.appliedEditCount]
        return page.copy(
            strokes = changeActiveState(
                references = page.strokes,
                activate = group.activatedStrokeIds,
                deactivate = group.deactivatedStrokeIds,
            ),
            appliedEditCount = page.appliedEditCount + 1,
        )
    }

    private fun applyEdit(page: NotebookPage, edit: NotebookEdit): NotebookPage {
        val retainedHistory = page.editHistory.take(page.appliedEditCount)
        require(retainedHistory.none { it.id == edit.group.id }) { "Edit group id already exists" }
        val existingStrokeIds = page.strokes.mapTo(mutableSetOf()) { it.strokeId }
        require(edit.newStrokes.none { it.id in existingStrokeIds }) { "New stroke id already exists" }
        val references = page.strokes + edit.newStrokes.map { StrokeReference(it.id, active = false) }
        val activeIds = references.filterTo(mutableSetOf()) { it.active }.mapTo(mutableSetOf()) { it.strokeId }
        val allIds = references.mapTo(mutableSetOf()) { it.strokeId }
        require(edit.group.activatedStrokeIds.all(allIds::contains)) { "Edit activates an unknown stroke" }
        require(edit.group.deactivatedStrokeIds.all(allIds::contains)) { "Edit deactivates an unknown stroke" }
        require(edit.group.activatedStrokeIds.none(activeIds::contains)) { "Edit activates an active stroke" }
        require(edit.group.deactivatedStrokeIds.all(activeIds::contains)) { "Edit deactivates an inactive stroke" }
        return page.copy(
            strokes = changeActiveState(
                references = references,
                activate = edit.group.activatedStrokeIds,
                deactivate = edit.group.deactivatedStrokeIds,
            ),
            editHistory = retainedHistory + edit.group,
            appliedEditCount = retainedHistory.size + 1,
        )
    }

    private fun changeActiveState(
        references: List<StrokeReference>,
        activate: List<String>,
        deactivate: List<String>,
    ): List<StrokeReference> {
        val activateSet = activate.toSet()
        val deactivateSet = deactivate.toSet()
        return references.map { reference ->
            when (reference.strokeId) {
                in activateSet -> reference.copy(active = true)
                in deactivateSet -> reference.copy(active = false)
                else -> reference
            }
        }
    }
}
