package com.putnamworkbook.offline.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.content.ui.MathContentWebView

@Composable
fun HelpPane(
    state: WorkspaceUiState,
    onRevealNextStage: () -> Unit,
    onSelectHint: (Int) -> Unit,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = state.content
    val progress = state.progress
    var confirmSolution by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content?.hints
                ?.take(progress?.revealedHintCount ?: 0)
                ?.forEachIndexed { index, hint ->
                    OutlinedButton(
                        onClick = { onSelectHint(index) },
                        enabled = index != state.activeHintIndex,
                        modifier = Modifier.testTag("help-hint-${index + 1}"),
                    ) {
                        Text(hint.title)
                    }
                }
            when (state.nextReveal) {
                RevealStage.HINT -> Button(
                    onClick = onRevealNextStage,
                    modifier = Modifier.testTag("help-reveal-next"),
                ) { Text("Reveal next hint") }
                RevealStage.SOLUTION -> Button(
                    onClick = { confirmSolution = true },
                    modifier = Modifier.testTag("help-reveal-solution"),
                ) { Text("Reveal solution") }
                RevealStage.NONE -> Unit
            }
        }
        val activeHint = content?.hints?.getOrNull(state.activeHintIndex)
        if (activeHint == null) {
            Text(
                "Hints appear here one at a time. Try the problem first, then reveal only what you need.",
                modifier = Modifier.padding(20.dp),
            )
        } else {
            MathContentWebView(activeHint.assetPath, onIntegrityError, Modifier.fillMaxSize())
        }
    }

    if (confirmSolution) {
        AlertDialog(
            onDismissRequest = { confirmSolution = false },
            title = { Text("Reveal the full solution?") },
            text = { Text("This is the fifth and final help stage.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSolution = false
                    onRevealNextStage()
                }, modifier = Modifier.testTag("help-confirm-solution")) { Text("Reveal solution") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSolution = false }) { Text("Keep working") }
            },
        )
    }
}
