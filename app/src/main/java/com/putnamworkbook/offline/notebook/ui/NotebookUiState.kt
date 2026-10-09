package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.edit.NotebookSelectionState
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import com.putnamworkbook.offline.notebook.edit.CanvasViewport

sealed interface PendingConfirmation {
    val pageId: String

    data class DeletePage(override val pageId: String) : PendingConfirmation

    data class ClearPage(override val pageId: String) : PendingConfirmation
}

enum class NotebookSaveState {
    IDLE,
    LOADING,
    SAVING,
    SAVED,
    ERROR,
}

data class NotebookUiState(
    val document: NotebookDocument? = null,
    val activePageIndex: Int = 0,
    val strokes: List<StrokeRecord> = emptyList(),
    val pendingConfirmation: PendingConfirmation? = null,
    val saveState: NotebookSaveState = NotebookSaveState.IDLE,
    val errorMessage: String? = null,
    val uiRevision: Long = 0L,
    val durableRevision: Long = 0L,
    val toolMode: NotebookToolMode = NotebookToolMode.PEN,
    val selection: NotebookSelectionState? = null,
    val clipboardHasContent: Boolean = false,
    val brush: BrushSpec = BrushSpec(),
    val canvasViewport: CanvasViewport? = null,
)
