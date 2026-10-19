package com.putnamworkbook.offline.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.data.library.index.OperationState
import com.putnamworkbook.offline.data.library.index.PackageAction

@Composable
internal fun ProblemLibraryRow(
    row: LibraryEntryRow,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onAction: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("problem-library-row-${row.key}")) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.testTag("problem-library-select-${row.key}"),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                Text(row.identity, style = MaterialTheme.typography.bodyMedium)
                if (row.topics.isNotEmpty()) Text(row.topics.joinToString(" • "))
                if (row.aliases.isNotEmpty()) Text("Aliases: ${row.aliases.joinToString()}")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(row.status.label, color = MaterialTheme.colorScheme.primary)
                    Text(formatLibraryBytes(row.compressedBytes))
                    if (row.bundled) Text("Bundled")
                    if (row.hasProgress) Text("Notebook retained")
                    if (!row.catalogListed) Text("No longer listed")
                }
                row.operation?.let { operation ->
                    val progress = if (operation.bytesTotal > 0) {
                        " ${operation.bytesCompleted}/${operation.bytesTotal} bytes"
                    } else ""
                    Text("${operation.state.name.lowercase().replaceFirstChar(Char::uppercase)}$progress")
                    operation.error?.let { Text(it.safeMessage, color = MaterialTheme.colorScheme.error) }
                }
            }
            row.primaryAction?.let { action ->
                Button(
                    onClick = onAction,
                    enabled = row.actionEnabled,
                    modifier = Modifier.testTag("problem-library-action-${row.key}"),
                ) { Text(if (row.operation?.state == OperationState.FAILED) "Retry" else action.label()) }
            }
        }
    }
}

private fun PackageAction.label() = when (this) {
    PackageAction.INSTALL -> "Download"
    PackageAction.UPDATE -> "Update"
    PackageAction.DELETE -> "Delete"
    PackageAction.RESTORE -> "Restore"
}

internal fun formatLibraryBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
