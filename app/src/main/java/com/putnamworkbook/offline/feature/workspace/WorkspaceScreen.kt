package com.putnamworkbook.offline.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.export.WorkbookExportOptions

@Composable
fun WorkspaceScreen(
    state: WorkspaceUiState,
    onHome: () -> Unit,
    onSetCompleted: (Boolean) -> Unit,
    referenceContent: @Composable () -> Unit,
    notebookContent: @Composable () -> Unit,
    onExportPdf: (WorkbookExportOptions) -> Unit = {},
    exportMessage: String? = null,
    exportInProgress: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var compactPane by remember { mutableStateOf(CompactPane.REFERENCE) }
    var showExportOptions by rememberSaveable(state.key?.canonical) { mutableStateOf(false) }
    var includeHints by rememberSaveable(state.key?.canonical) { mutableStateOf(true) }
    var includeSolution by rememberSaveable(state.key?.canonical) { mutableStateOf(true) }
    if (showExportOptions) {
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            title = { Text("Export problem workbook") },
            text = {
                Column(Modifier.testTag("workspace-export-options")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeHints,
                            onCheckedChange = { includeHints = it },
                            enabled = (state.progress?.revealedHintCount ?: 0) > 0,
                            modifier = Modifier.testTag("workspace-export-hints"),
                        )
                        Text("Include revealed hints")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeSolution,
                            onCheckedChange = { includeSolution = it },
                            enabled = state.progress?.solutionRevealed == true,
                            modifier = Modifier.testTag("workspace-export-solution"),
                        )
                        Text("Include revealed solution")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportOptions = false
                        onExportPdf(
                            WorkbookExportOptions(
                                includeRevealedHints = includeHints,
                                includeRevealedSolution = includeSolution,
                            ),
                        )
                    },
                    modifier = Modifier.testTag("workspace-export-confirm"),
                ) { Text("Choose file") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExportOptions = false }) { Text("Cancel") }
            },
        )
    }
    Column(modifier.fillMaxSize().testTag("workspace-screen")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onHome, modifier = Modifier.testTag("workspace-home")) {
                Text("Home")
            }
            Text(
                text = state.content?.summary?.let { "${it.setTitle} ${it.label}" } ?: "Problem workspace",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    includeHints = true
                    includeSolution = true
                    showExportOptions = true
                },
                enabled = state.progress != null && !exportInProgress,
                modifier = Modifier.testTag("workspace-export-pdf"),
            ) {
                Text(if (exportInProgress) "Exporting…" else "Export PDF")
            }
            Button(
                onClick = { onSetCompleted(state.progress?.status != ProblemStatus.COMPLETED) },
                enabled = state.progress != null,
                modifier = Modifier.testTag("workspace-completed"),
            ) {
                Text(if (state.progress?.status == ProblemStatus.COMPLETED) "Completed" else "Mark completed")
            }
        }
        HorizontalDivider()
        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        exportMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("workspace-export-status"),
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= WIDE_WORKSPACE_MIN_WIDTH) {
                Row(Modifier.fillMaxSize()) {
                    PaneBox("workspace-reference-pane", Modifier.weight(0.4f), referenceContent)
                    PaneBox("workspace-notebook-pane", Modifier.weight(0.6f), notebookContent)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (compactPane == CompactPane.REFERENCE) {
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Reference") }
                        } else {
                            OutlinedButton(
                                onClick = { compactPane = CompactPane.REFERENCE },
                                modifier = Modifier.weight(1f).testTag("workspace-show-reference"),
                            ) { Text("Reference") }
                        }
                        if (compactPane == CompactPane.NOTEBOOK) {
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Notebook") }
                        } else {
                            OutlinedButton(
                                onClick = { compactPane = CompactPane.NOTEBOOK },
                                modifier = Modifier.weight(1f).testTag("workspace-show-notebook"),
                            ) { Text("Notebook") }
                        }
                    }
                    when (compactPane) {
                        CompactPane.REFERENCE -> PaneBox(
                            "workspace-reference-pane",
                            Modifier.weight(1f),
                            referenceContent,
                        )
                        CompactPane.NOTEBOOK -> PaneBox(
                            "workspace-notebook-pane",
                            Modifier.weight(1f),
                            notebookContent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneBox(
    tag: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .testTag(tag),
    ) {
        content()
    }
}

private enum class CompactPane {
    REFERENCE,
    NOTEBOOK,
}

private val WIDE_WORKSPACE_MIN_WIDTH = 840.dp
