package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.notebook.model.StrokeRecord

enum class NotebookToolMode {
    PEN,
    SELECT,
}

data class NotebookSelectionState(
    val selectedStrokeIds: Set<String>,
    val bounds: NormalizedRect,
    val previewStrokes: List<StrokeRecord>? = null,
) {
    init {
        require(selectedStrokeIds.isNotEmpty()) { "A selection must contain at least one stroke" }
    }
}
