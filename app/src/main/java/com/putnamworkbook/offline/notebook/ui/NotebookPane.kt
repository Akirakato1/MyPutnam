package com.putnamworkbook.offline.notebook.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import com.putnamworkbook.offline.notebook.edit.CanvasViewport
import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.edit.SelectionCorner

const val NOTEBOOK_TOOLBAR_TEST_TAG = "notebook-toolbar-safe-region"
const val PREVIOUS_PAGE_TEST_TAG = "previous-page"
const val NEXT_PAGE_TEST_TAG = "next-page"
const val PAGE_INDEX_TEST_TAG = "notebook-page-index"

@Composable
fun NotebookPane(
    state: NotebookUiState,
    brush: BrushSpec,
    onStrokeFinished: (StrokeRecord) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPage: () -> Unit,
    onRequestDeletePage: (String) -> Unit,
    onRequestClearPage: (String) -> Unit,
    onConfirmDeletePage: () -> Unit,
    onConfirmClearPage: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onSetToolMode: (NotebookToolMode) -> Unit = {},
    onBrushChanged: (BrushSpec) -> Unit = {},
    onSelect: (NormalizedRect, CanvasViewport) -> Unit = { _, _ -> },
    onViewportChanged: (CanvasViewport) -> Unit = {},
    onPreviewMove: (Float, Float) -> Unit = { _, _ -> },
    onCommitMove: (Float, Float) -> Unit = { _, _ -> },
    onPreviewScale: (SelectionCorner, NormalizedPoint) -> Unit = { _, _ -> },
    onCommitScale: (SelectionCorner, NormalizedPoint) -> Unit = { _, _ -> },
    onCancelPreview: () -> Unit = {},
    onCutSelection: () -> Unit = {},
    onCopySelection: () -> Unit = {},
    onPaste: () -> Unit = {},
    onDeleteSelection: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val document = state.document
    if (document == null) {
        Text(if (state.saveState == NotebookSaveState.ERROR) state.errorMessage.orEmpty() else "Loading notebook…")
        return
    }
    val page = document.pages[state.activePageIndex]
    Column(modifier.fillMaxSize()) {
        NotebookToolbar(
            saveState = state.saveState,
            toolMode = state.toolMode,
            brush = brush,
            hasSelection = state.selection != null,
            clipboardHasContent = state.clipboardHasContent,
            onSetToolMode = onSetToolMode,
            onBrushChanged = onBrushChanged,
            onCut = onCutSelection,
            onCopy = onCopySelection,
            onPaste = onPaste,
            onDeleteSelection = onDeleteSelection,
            onUndo = onUndo,
            onRedo = onRedo,
            onAddPage = onAddPage,
            onClearPage = { onRequestClearPage(page.id) },
            onDeletePage = { onRequestDeletePage(page.id) },
            modifier = Modifier.testTag(NOTEBOOK_TOOLBAR_TEST_TAG),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { onSelectPage(state.activePageIndex - 1) },
                enabled = state.activePageIndex > 0,
                modifier = Modifier.testTag(PREVIOUS_PAGE_TEST_TAG),
            ) { Text("←") }
            Text(
                "Page ${state.activePageIndex + 1} of ${document.pages.size}",
                modifier = Modifier.testTag(PAGE_INDEX_TEST_TAG),
            )
            TextButton(
                onClick = { onSelectPage(state.activePageIndex + 1) },
                enabled = state.activePageIndex < document.pages.lastIndex,
                modifier = Modifier.testTag(NEXT_PAGE_TEST_TAG),
            ) { Text("→") }
        }
        AnimatedContent(
            targetState = state.activePageIndex,
            transitionSpec = {
                val direction = if (targetState > initialState) {
                    AnimatedContentTransitionScope.SlideDirection.Left
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Right
                }
                slideIntoContainer(direction, tween(PAGE_TURN_MILLIS)) togetherWith
                    slideOutOfContainer(direction, tween(PAGE_TURN_MILLIS))
            },
            label = "notebook-page-turn",
            modifier = Modifier.weight(1f),
        ) { pageIndex ->
            NotebookCanvas(
                page = document.pages[pageIndex],
                strokes = if (pageIndex == state.activePageIndex) state.strokes else emptyList(),
                brush = brush,
                onStrokeFinished = onStrokeFinished,
                toolMode = state.toolMode,
                selection = state.selection,
                onSelect = onSelect,
                onViewportChanged = onViewportChanged,
                onPreviewMove = onPreviewMove,
                onCommitMove = onCommitMove,
                onPreviewScale = onPreviewScale,
                onCommitScale = onCommitScale,
                onCancelPreview = onCancelPreview,
                onClearSelection = onClearSelection,
            )
        }
    }

    when (state.pendingConfirmation) {
        is PendingConfirmation.DeletePage -> ConfirmationDialog(
            title = "Delete this page?",
            text = "The page will be removed from this notebook.",
            onConfirm = onConfirmDeletePage,
            onDismiss = onDismissConfirmation,
        )
        is PendingConfirmation.ClearPage -> ConfirmationDialog(
            title = "Clear this page?",
            text = "Every stroke on this page will be removed.",
            onConfirm = onConfirmClearPage,
            onDismiss = onDismissConfirmation,
        )
        null -> Unit
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val PAGE_TURN_MILLIS = 160
