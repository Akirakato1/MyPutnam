package com.putnamworkbook.offline.notebook.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord

const val NOTEBOOK_TOOLBAR_TEST_TAG = "notebook-toolbar-safe-region"

@Composable
fun NotebookPane(
    state: NotebookUiState,
    brush: BrushSpec,
    palmGuard: PalmGuardState?,
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
    onReorderPage: (String, Int) -> Unit,
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
            onUndo = onUndo,
            onRedo = onRedo,
            onEraseLast = onUndo,
            onAddPage = onAddPage,
            onClearPage = { onRequestClearPage(page.id) },
            onDeletePage = { onRequestDeletePage(page.id) },
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
            modifier = Modifier.weight(1f),
        ) { pageIndex ->
            NotebookCanvas(
                page = document.pages[pageIndex],
                strokes = if (pageIndex == state.activePageIndex) state.strokes else emptyList(),
                brush = brush,
                palmGuard = palmGuard,
                onStrokeFinished = onStrokeFinished,
            )
        }
        PageStrip(document, state.activePageIndex, onSelectPage, onReorderPage)
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
