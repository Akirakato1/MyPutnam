package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.notebook.model.StrokeRecord

class NotebookClipboard {
    private var copiedStrokes: List<StrokeRecord> = emptyList()

    val hasContent: Boolean get() = copiedStrokes.isNotEmpty()

    fun copy(strokes: List<StrokeRecord>) {
        copiedStrokes = strokes.map { stroke -> stroke.copy(samples = stroke.samples.toList()) }
    }

    fun createPaste(
        pageStrokes: List<StrokeRecord>,
        viewport: CanvasViewport,
        idFactory: () -> String,
    ): List<StrokeRecord> {
        if (copiedStrokes.isEmpty()) return emptyList()
        val offset = (0.03f + pageStrokes.size.coerceAtMost(4) * 0.01f)
        return StrokeTransform.move(copiedStrokes, offset, offset, viewport, idFactory)
    }
}
