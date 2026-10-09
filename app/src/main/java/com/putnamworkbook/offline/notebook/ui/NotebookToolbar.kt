package com.putnamworkbook.offline.notebook.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import kotlin.math.roundToInt

const val UNDO_TEST_TAG = "notebook-undo"
const val REDO_TEST_TAG = "notebook-redo"
const val TOOL_MODE_TOGGLE_TEST_TAG = "notebook-tool-mode-toggle"
const val BRUSH_WIDTH_TEST_TAG = "notebook-brush-width"
const val CUT_SELECTION_TEST_TAG = "notebook-cut-selection"
const val COPY_SELECTION_TEST_TAG = "notebook-copy-selection"
const val PASTE_SELECTION_TEST_TAG = "notebook-paste-selection"
const val DELETE_SELECTION_TEST_TAG = "notebook-delete-selection"

@Composable
fun NotebookToolbar(
    saveState: NotebookSaveState,
    toolMode: NotebookToolMode,
    brush: BrushSpec,
    hasSelection: Boolean,
    clipboardHasContent: Boolean,
    onSetToolMode: (NotebookToolMode) -> Unit,
    onBrushChanged: (BrushSpec) -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDeleteSelection: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
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
            TextButton(
                onClick = {
                    onSetToolMode(
                        if (toolMode == NotebookToolMode.PEN) NotebookToolMode.SELECT else NotebookToolMode.PEN,
                    )
                },
                modifier = Modifier.testTag(TOOL_MODE_TOGGLE_TEST_TAG),
            ) {
                Text(if (toolMode == NotebookToolMode.PEN) "Pen mode" else "Select mode")
            }
            TextButton(onClick = onUndo, modifier = Modifier.testTag(UNDO_TEST_TAG)) { Text("Undo") }
            TextButton(onClick = onRedo, modifier = Modifier.testTag(REDO_TEST_TAG)) { Text("Redo") }
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
        if (toolMode == NotebookToolMode.PEN) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Color")
                NOTEBOOK_BRUSH_COLORS.forEach { choice ->
                    FilterChip(
                        selected = brush.colorArgb == choice.argb,
                        onClick = { onBrushChanged(brush.copy(colorArgb = choice.argb)) },
                        label = { Text(choice.label) },
                        leadingIcon = {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(Color(choice.argb)),
                            )
                        },
                        modifier = Modifier.testTag("notebook-brush-color-${choice.tag}"),
                    )
                }
                Text("Width ${brush.width.roundToInt()}")
                Slider(
                    value = brush.width,
                    onValueChange = { width -> onBrushChanged(brush.copy(width = width)) },
                    valueRange = 1f..12f,
                    steps = 10,
                    modifier = Modifier.width(180.dp).testTag(BRUSH_WIDTH_TEST_TAG),
                )
            }
        } else if (hasSelection || clipboardHasContent) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onCut, enabled = hasSelection, modifier = Modifier.testTag(CUT_SELECTION_TEST_TAG)) { Text("Cut") }
                TextButton(onClick = onCopy, enabled = hasSelection, modifier = Modifier.testTag(COPY_SELECTION_TEST_TAG)) { Text("Copy") }
                TextButton(onClick = onPaste, enabled = clipboardHasContent, modifier = Modifier.testTag(PASTE_SELECTION_TEST_TAG)) { Text("Paste") }
                TextButton(onClick = onDeleteSelection, enabled = hasSelection, modifier = Modifier.testTag(DELETE_SELECTION_TEST_TAG)) { Text("Delete selection") }
            }
        }
    }
}

private data class NotebookBrushColor(
    val label: String,
    val tag: String,
    val argb: Long,
)

private val NOTEBOOK_BRUSH_COLORS = listOf(
    NotebookBrushColor("Ink", "ink", 0xFF1F2937L),
    NotebookBrushColor("Blue", "blue", 0xFF1D4ED8L),
    NotebookBrushColor("Red", "red", 0xFFB91C1CL),
    NotebookBrushColor("Green", "green", 0xFF15803DL),
    NotebookBrushColor("Purple", "purple", 0xFF7E22CEL),
    NotebookBrushColor("Orange", "orange", 0xFFC2410CL),
    NotebookBrushColor("Teal", "teal", 0xFF0F766EL),
    NotebookBrushColor("Pink", "pink", 0xFFBE185DL),
)
