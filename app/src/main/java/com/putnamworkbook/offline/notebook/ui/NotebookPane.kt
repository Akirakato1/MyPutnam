package com.putnamworkbook.offline.notebook.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookPage
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
    onPreviewRotation: (Float) -> Unit = {},
    onCommitRotation: () -> Unit = {},
    onCancelPreview: () -> Unit = {},
    onCutSelection: () -> Unit = {},
    onCopySelection: () -> Unit = {},
    onPaste: () -> Unit = {},
    onDeleteSelection: () -> Unit = {},
    onFlipHorizontal: () -> Unit = {},
    onFlipVertical: () -> Unit = {},
    onSetRotationSnapEnabled: (Boolean) -> Unit = {},
    onClearSelection: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val document = state.document
    if (document == null) {
        Text(if (state.saveState == NotebookSaveState.ERROR) state.errorMessage.orEmpty() else "Loading notebook…")
        return
    }
    val page = document.pages.getOrNull(state.activePageIndex)
    if (page == null) {
        Text("Loading notebook…")
        return
    }
    var canvasPlacement by remember { mutableStateOf<NotebookCanvasPlacement?>(null) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NotebookToolbar(
                saveState = state.saveState,
                toolMode = state.toolMode,
                brush = brush,
                hasSelection = state.selection != null,
                clipboardHasContent = state.clipboardHasContent,
                rotationSnapEnabled = state.rotationSnapEnabled,
                onSetToolMode = onSetToolMode,
                onBrushChanged = onBrushChanged,
                onCut = onCutSelection,
                onCopy = onCopySelection,
                onPaste = onPaste,
                onDeleteSelection = onDeleteSelection,
                onFlipHorizontal = onFlipHorizontal,
                onFlipVertical = onFlipVertical,
                onSetRotationSnapEnabled = onSetRotationSnapEnabled,
                onUndo = onUndo,
                onRedo = onRedo,
                onAddPage = onAddPage,
                onClearPage = { onRequestClearPage(page.id) },
                onDeletePage = { onRequestDeletePage(page.id) },
                deletePageEnabled = canDeleteNotebookPage(document.pages.size),
                activePageIndex = state.activePageIndex,
                pageCount = document.pages.size,
                onSelectPage = onSelectPage,
                modifier = Modifier.testTag(NOTEBOOK_TOOLBAR_TEST_TAG),
            )
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
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coordinates ->
                        if (coordinates.size.width > 0 && coordinates.size.height > 0) {
                            val measured = NotebookCanvasPlacement(
                                topLeftInPane = coordinates.positionInParent(),
                                size = coordinates.size,
                            )
                            if (canvasPlacement != measured) canvasPlacement = measured
                        }
                    },
            ) { pageIndex ->
                pageForAnimatedIndex(document, pageIndex)?.let { animatedPage ->
                    NotebookCanvas(
                        page = animatedPage,
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
                        onPreviewRotation = onPreviewRotation,
                        onCommitRotation = onCommitRotation,
                        onCancelPreview = onCancelPreview,
                        onClearSelection = onClearSelection,
                    )
                }
            }
        }
        if (state.toolMode == NotebookToolMode.SELECT) {
            val selection = state.selection
            val placement = canvasPlacement
            if (selection != null && placement != null) {
                SelectionHandleOverlay(
                    selection = selection,
                    canvasPlacement = placement,
                    onPreviewScale = onPreviewScale,
                    onCommitScale = onCommitScale,
                    onPreviewRotation = onPreviewRotation,
                    onCommitRotation = onCommitRotation,
                    onCancelPreview = onCancelPreview,
                    modifier = Modifier.zIndex(SELECTION_HANDLE_LAYER_Z_INDEX),
                )
            }
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

internal fun pageForAnimatedIndex(document: NotebookDocument, pageIndex: Int): NotebookPage? =
    document.pages.getOrNull(pageIndex)

internal fun canDeleteNotebookPage(pageCount: Int): Boolean = pageCount > 1

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
private const val SELECTION_HANDLE_LAYER_Z_INDEX = 10f
