package com.putnamworkbook.offline.feature.freenotebook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun FreeNotebookEditorScreen(
    state: FreeNotebookEditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onSaveAsNameChanged: (String) -> Unit,
    onSaveAsConfirmed: () -> Unit,
    onSaveAsDismissed: () -> Unit,
    notebookContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().testTag("free-notebook-editor")) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack, enabled = !state.isWorking, modifier = Modifier.testTag("free-notebook-back")) {
                Text("Back")
            }
            Text(
                state.record?.name ?: "Free Notebook",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).testTag("free-notebook-title"),
            )
            OutlinedButton(
                onClick = onSaveAs,
                enabled = state.canEdit && !state.isWorking,
                modifier = Modifier.testTag("free-notebook-save-as"),
            ) { Text("Save As") }
            Button(
                onClick = onSave,
                enabled = state.canEdit && !state.isWorking,
                modifier = Modifier.testTag("free-notebook-save"),
            ) { Text(if (state.isWorking) "Saving…" else "Save") }
        }
        HorizontalDivider()
        state.errorMessage?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("free-notebook-error"),
            )
        }
        state.statusMessage?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("free-notebook-status"),
            )
        }
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.canEdit -> Box(
                Modifier.fillMaxWidth().weight(1f).testTag("free-notebook-notebook-pane"),
            ) { notebookContent() }
            state.errorMessage == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This free notebook could not be opened")
            }
        }
    }

    state.saveAsDialog?.let { dialog ->
        FreeWorkspaceNameDialog(
            state = dialog,
            animationKey = state.nameErrorAnimationKey,
            onValueChange = onSaveAsNameChanged,
            onConfirm = onSaveAsConfirmed,
            onDismiss = onSaveAsDismissed,
            confirmEnabled = !state.isWorking,
        )
    }
}
