package com.putnamworkbook.offline.feature.freenotebook

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceNameError
import kotlin.math.roundToInt

@Composable
fun FreeWorkspaceNameDialog(
    state: FreeWorkspaceNameDialogState,
    animationKey: Long,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(animationKey) {
        if (animationKey > 0) {
            listOf(-12f, 12f, -8f, 8f, 0f).forEach { target ->
                shake.animateTo(target, tween(45))
            }
        }
    }
    val errorText = state.nameError?.message() ?: state.failureMessage
    AlertDialog(
        modifier = Modifier
            .offset { IntOffset(shake.value.roundToInt(), 0) }
            .testTag("free-workspace-name-dialog-$animationKey"),
        onDismissRequest = onDismiss,
        title = { Text(state.purpose.title()) },
        text = {
            Column {
                OutlinedTextField(
                    value = state.value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    isError = errorText != null,
                    label = { Text("Workspace name") },
                    supportingText = errorText?.let { message ->
                        { Text(message, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.testTag("free-workspace-name-input"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = Modifier.testTag("free-workspace-name-confirm"),
            ) {
                Text(state.purpose.confirmLabel())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = confirmEnabled) { Text("Cancel") }
        },
    )
}

private fun FreeWorkspaceNamePurpose.title(): String = when (this) {
    FreeWorkspaceNamePurpose.CREATE -> "New free notebook"
    FreeWorkspaceNamePurpose.RENAME -> "Rename free notebook"
    FreeWorkspaceNamePurpose.SAVE_AS -> "Save free notebook as"
}

private fun FreeWorkspaceNamePurpose.confirmLabel(): String = when (this) {
    FreeWorkspaceNamePurpose.CREATE -> "Create"
    FreeWorkspaceNamePurpose.RENAME -> "Rename"
    FreeWorkspaceNamePurpose.SAVE_AS -> "Save As"
}

private fun FreeWorkspaceNameError.message(): String = when (this) {
    FreeWorkspaceNameError.BLANK -> "Enter a workspace name"
    FreeWorkspaceNameError.TOO_LONG -> "Use 80 characters or fewer"
    FreeWorkspaceNameError.CONTROL_CHARACTER -> "Remove unsupported control characters"
    FreeWorkspaceNameError.DUPLICATE -> "A workspace with this name already exists"
}
