package com.putnamworkbook.offline.notebook.model

data class NotebookEdit(
    val group: NotebookEditGroup,
    val newStrokes: List<StrokeRecord>,
) {
    init {
        require(newStrokes.map { it.id }.distinct().size == newStrokes.size) {
            "New stroke ids must be unique within an edit"
        }
        require(newStrokes.all { it.id in group.activatedStrokeIds }) {
            "Every new stroke must be activated by its edit group"
        }
    }
}
