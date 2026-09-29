package com.putnamworkbook.offline.notebook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val UNDO_TEST_TAG = "notebook-undo"
const val REDO_TEST_TAG = "notebook-redo"
const val ERASER_TEST_TAG = "notebook-eraser"

@Composable
fun NotebookToolbar(
    saveState: NotebookSaveState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onEraseLast: () -> Unit,
    onAddPage: () -> Unit,
    onClearPage: () -> Unit,
    onDeletePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onUndo, modifier = Modifier.testTag(UNDO_TEST_TAG)) { Text("Undo") }
        TextButton(onClick = onRedo, modifier = Modifier.testTag(REDO_TEST_TAG)) { Text("Redo") }
        TextButton(onClick = onEraseLast, modifier = Modifier.testTag(ERASER_TEST_TAG)) { Text("Erase last") }
        TextButton(onClick = onAddPage) { Text("Add page") }
        TextButton(onClick = onClearPage) { Text("Clear") }
        TextButton(onClick = onDeletePage) { Text("Delete") }
        Text(
            text = when (saveState) {
                NotebookSaveState.SAVING -> "Saving…"
                NotebookSaveState.SAVED -> "Saved"
                NotebookSaveState.ERROR -> "Save failed"
                NotebookSaveState.LOADING -> "Loading…"
                NotebookSaveState.IDLE -> ""
            },
        )
    }
}
