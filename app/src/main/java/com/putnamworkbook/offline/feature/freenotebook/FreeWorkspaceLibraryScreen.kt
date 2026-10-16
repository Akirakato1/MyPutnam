package com.putnamworkbook.offline.feature.freenotebook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceEntry
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceStorageState
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
fun FreeWorkspaceLibraryScreen(
    state: FreeWorkspaceListUiState,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (UUID) -> Unit,
    onRename: (UUID) -> Unit,
    onDelete: (UUID) -> Unit,
    onNameChanged: (String) -> Unit,
    onNameConfirmed: () -> Unit,
    onNameDismissed: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().testTag("free-workspace-library")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("free-workspace-back")) { Text("Back") }
            Text("Free Notebook", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNew, modifier = Modifier.testTag("free-workspace-new")) { Text("New workspace") }
        }

        state.errorMessage?.let { message ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No free notebooks yet. Create one to start writing.")
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("free-workspace-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.entries, key = { it.record.id }) { entry ->
                    WorkspaceRow(entry, onOpen, onRename, onDelete)
                }
            }
        }
    }

    state.nameDialog?.let { dialog ->
        FreeWorkspaceNameDialog(
            state = dialog,
            animationKey = state.nameErrorAnimationKey,
            onValueChange = onNameChanged,
            onConfirm = onNameConfirmed,
            onDismiss = onNameDismissed,
            confirmEnabled = !state.isWorking,
        )
    }

    state.deleteConfirmation?.let { record ->
        AlertDialog(
            onDismissRequest = onDeleteDismissed,
            title = { Text("Delete ${record.name}?") },
            text = { Text("This permanently removes this free notebook and all of its pages.") },
            confirmButton = {
                TextButton(
                    onClick = onDeleteConfirmed,
                    enabled = !state.isWorking,
                    modifier = Modifier.testTag("free-workspace-delete-confirm"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = onDeleteDismissed, enabled = !state.isWorking) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun WorkspaceRow(
    entry: FreeWorkspaceEntry,
    onOpen: (UUID) -> Unit,
    onRename: (UUID) -> Unit,
    onDelete: (UUID) -> Unit,
) {
    val record = entry.record
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.storageState == FreeWorkspaceStorageState.READY) { onOpen(record.id) }
            .testTag("free-workspace-row-${record.id}"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(record.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Created ${formatWorkspaceDate(record.createdAtEpochMillis)} · Modified ${formatWorkspaceDate(record.modifiedAtEpochMillis)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (entry.storageState == FreeWorkspaceStorageState.MISSING) {
                    Text(
                        "Notebook data is missing. You can rename or delete this entry.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("free-workspace-damaged-${record.id}"),
                    )
                }
            }
            WorkspaceGlyphButton(
                glyph = WorkspaceGlyph.RENAME,
                description = "Rename ${record.name}",
                tag = "free-workspace-rename-${record.id}",
                onClick = { onRename(record.id) },
            )
            WorkspaceGlyphButton(
                glyph = WorkspaceGlyph.DELETE,
                description = "Delete ${record.name}",
                tag = "free-workspace-delete-${record.id}",
                onClick = { onDelete(record.id) },
            )
        }
    }
}

@Composable
private fun WorkspaceGlyphButton(
    glyph: WorkspaceGlyph,
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface
    IconButton(
        onClick = onClick,
        modifier = Modifier.testTag(tag).semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.dp.toPx()
            when (glyph) {
                WorkspaceGlyph.RENAME -> {
                    drawLine(color, Offset(size.width * .2f, size.height * .8f), Offset(size.width * .75f, size.height * .25f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(size.width * .65f, size.height * .2f), Offset(size.width * .8f, size.height * .35f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(size.width * .18f, size.height * .82f), Offset(size.width * .38f, size.height * .77f), stroke, StrokeCap.Round)
                }
                WorkspaceGlyph.DELETE -> {
                    drawRect(color, Offset(size.width * .28f, size.height * .3f), androidx.compose.ui.geometry.Size(size.width * .44f, size.height * .52f), style = Stroke(stroke))
                    drawLine(color, Offset(size.width * .2f, size.height * .24f), Offset(size.width * .8f, size.height * .24f), stroke, StrokeCap.Round)
                    drawLine(color, Offset(size.width * .4f, size.height * .14f), Offset(size.width * .6f, size.height * .14f), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

internal fun formatWorkspaceDate(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

private enum class WorkspaceGlyph { RENAME, DELETE }
