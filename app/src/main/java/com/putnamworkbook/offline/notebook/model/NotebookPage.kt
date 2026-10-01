package com.putnamworkbook.offline.notebook.model

import com.putnamworkbook.offline.domain.model.PaperStyle

data class StrokeReference(
    val strokeId: String,
    val active: Boolean,
)

data class NotebookPage(
    val id: String,
    val paperStyle: PaperStyle,
    val strokes: List<StrokeReference> = emptyList(),
    val editHistory: List<NotebookEditGroup> = emptyList(),
    val appliedEditCount: Int = editHistory.size,
) {
    init {
        require(id.matches(SAFE_ID)) { "Page id contains unsupported characters" }
        require(strokes.map { it.strokeId }.distinct().size == strokes.size) {
            "Stroke ids must be unique within a page"
        }
        require(editHistory.map { it.id }.distinct().size == editHistory.size) {
            "Edit group ids must be unique within a page"
        }
        require(appliedEditCount in 0..editHistory.size) { "Invalid applied edit cursor" }
        val strokeIds = strokes.mapTo(mutableSetOf()) { it.strokeId }
        require(editHistory.all { group ->
            (group.activatedStrokeIds + group.deactivatedStrokeIds).all(strokeIds::contains)
        }) { "Edit history refers to an unknown stroke" }
        val activeFromHistory = mutableSetOf<String>()
        editHistory.take(appliedEditCount).forEach { group ->
            activeFromHistory += group.activatedStrokeIds
            activeFromHistory -= group.deactivatedStrokeIds.toSet()
        }
        require(strokes.all { it.active == (it.strokeId in activeFromHistory) }) {
            "Stroke active flags do not match the applied edit history"
        }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    }
}
