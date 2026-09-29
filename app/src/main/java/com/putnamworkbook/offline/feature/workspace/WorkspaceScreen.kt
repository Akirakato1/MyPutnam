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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.ProblemStatus

@Composable
fun WorkspaceScreen(
    state: WorkspaceUiState,
    onHome: () -> Unit,
    onSetCompleted: (Boolean) -> Unit,
    referenceContent: @Composable () -> Unit,
    notebookContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var compactPane by remember { mutableStateOf(CompactPane.REFERENCE) }
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
                text = state.key?.let { "${it.year} ${it.code}" } ?: "Problem workspace",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
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
