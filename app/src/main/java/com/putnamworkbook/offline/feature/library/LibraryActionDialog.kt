package com.putnamworkbook.offline.feature.library

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
internal fun LibraryActionDialog(
    confirmation: LibraryActionConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete downloaded content?") },
        text = {
            Text(
                buildString {
                    append("Remove ${confirmation.keys.size} selected item")
                    if (confirmation.keys.size != 1) append('s')
                    append(" from this device.")
                    if (confirmation.problemCount > 0) {
                        append(" Notebook pages and progress will be retained.")
                    }
                    append(" Bundled items can be restored offline later.")
                },
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Keep") } },
    )
}
