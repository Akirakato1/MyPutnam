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
) {
    init {
        require(id.matches(SAFE_ID)) { "Page id contains unsupported characters" }
        require(strokes.map { it.strokeId }.distinct().size == strokes.size) {
            "Stroke ids must be unique within a page"
        }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    }
}
