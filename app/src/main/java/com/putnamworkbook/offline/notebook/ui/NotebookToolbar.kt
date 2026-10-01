package com.putnamworkbook.offline.notebook.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode

const val UNDO_TEST_TAG = "notebook-undo"
const val REDO_TEST_TAG = "notebook-redo"
const val ERASER_TEST_TAG = "notebook-eraser"
const val PEN_MODE_TEST_TAG = "notebook-pen-mode"
const val SELECT_MODE_TEST_TAG = "notebook-select-mode"
const val CUT_SELECTION_TEST_TAG = "notebook-cut-selection"
const val COPY_SELECTION_TEST_TAG = "notebook-copy-selection"
const val PASTE_SELECTION_TEST_TAG = "notebook-paste-selection"
const val DELETE_SELECTION_TEST_TAG = "notebook-delete-selection"
const val CANCEL_SELECTION_TEST_TAG = "notebook-cancel-selection"

@Composable
fun NotebookToolbar(
    saveState: NotebookSaveState,
    toolMode: NotebookToolMode,
    hasSelection: Boolean,
    clipboardHasContent: Boolean,
    onSetToolMode: (NotebookToolMode) -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDeleteSelection: () -> Unit,
    onCancelSelection: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onEraseLast: () -> Unit,
    onAddPage: () -> Unit,
    onClearPage: () -> Unit,
    onDeletePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onSetToolMode(NotebookToolMode.PEN) }, modifier = Modifier.testTag(PEN_MODE_TEST_TAG)) {
                Text(if (toolMode == NotebookToolMode.PEN) "Pen ✓" else "Pen")
            }
            TextButton(onClick = { onSetToolMode(NotebookToolMode.SELECT) }, modifier = Modifier.testTag(SELECT_MODE_TEST_TAG)) {
                Text(if (toolMode == NotebookToolMode.SELECT) "Select ✓" else "Select")
            }
            TextButton(onClick = onUndo, modifier = Modifier.testTag(UNDO_TEST_TAG)) { Text("Undo") }
            TextButton(onClick = onRedo, modifier = Modifier.testTag(REDO_TEST_TAG)) { Text("Redo") }
            TextButton(onClick = onEraseLast, modifier = Modifier.testTag(ERASER_TEST_TAG)) { Text("Erase last") }
            TextButton(onClick = onAddPage) { Text("Add page") }
            TextButton(onClick = onClearPage) { Text("Clear") }
            TextButton(onClick = onDeletePage) { Text("Delete page") }
            Text(
                when (saveState) {
                    NotebookSaveState.SAVING -> "Saving…"
                    NotebookSaveState.SAVED -> "Saved"
                    NotebookSaveState.ERROR -> "Save failed"
                    NotebookSaveState.LOADING -> "Loading…"
                    NotebookSaveState.IDLE -> ""
                },
            )
        }
        if (hasSelection || clipboardHasContent) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onCut, enabled = hasSelection, modifier = Modifier.testTag(CUT_SELECTION_TEST_TAG)) { Text("Cut") }
                TextButton(onClick = onCopy, enabled = hasSelection, modifier = Modifier.testTag(COPY_SELECTION_TEST_TAG)) { Text("Copy") }
                TextButton(onClick = onPaste, enabled = clipboardHasContent, modifier = Modifier.testTag(PASTE_SELECTION_TEST_TAG)) { Text("Paste") }
                TextButton(onClick = onDeleteSelection, enabled = hasSelection, modifier = Modifier.testTag(DELETE_SELECTION_TEST_TAG)) { Text("Delete selection") }
                TextButton(onClick = onCancelSelection, enabled = hasSelection, modifier = Modifier.testTag(CANCEL_SELECTION_TEST_TAG)) { Text("Cancel") }
            }
        }
    }
}
