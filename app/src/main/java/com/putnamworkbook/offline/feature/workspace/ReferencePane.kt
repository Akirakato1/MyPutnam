package com.putnamworkbook.offline.feature.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.content.ui.MathContentWebView
import com.putnamworkbook.offline.domain.model.ReferenceTab

@Composable
fun ReferencePane(
    state: WorkspaceUiState,
    onSelectTab: (ReferenceTab) -> Unit,
    onRevealNextStage: () -> Unit,
    onSelectHint: (Int) -> Unit,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            ReferenceTab.visibleEntries.forEach { tab ->
                val enabled = tab != ReferenceTab.SOLUTION || state.progress?.solutionRevealed == true
                if (tab == state.selectedReferenceTab) {
                    Button(
                        onClick = {},
                        enabled = enabled,
                        modifier = Modifier.testTag("reference-tab-${tab.name.lowercase()}"),
                    ) { Text(tab.label()) }
                } else {
                    OutlinedButton(
                        onClick = { onSelectTab(tab) },
                        enabled = enabled,
                        modifier = Modifier.testTag("reference-tab-${tab.name.lowercase()}"),
                    ) { Text(tab.label()) }
                }
            }
        }
        val content = state.content
        when (state.selectedReferenceTab.normalizedForDisplay()) {
            ReferenceTab.PROBLEM -> content?.let {
                MathContentWebView(it.statementAssetPath, onIntegrityError, Modifier.fillMaxSize())
            } ?: LoadingReference()
            ReferenceTab.HELP -> HelpPane(
                state,
                onRevealNextStage,
                onSelectHint,
                onIntegrityError,
                Modifier.fillMaxSize(),
            )
            ReferenceTab.PREREQUISITES -> {
                val prerequisite = content?.prerequisites?.firstOrNull()
                if (prerequisite == null) Text("No prerequisite note is needed", Modifier.padding(20.dp))
                else MathContentWebView(prerequisite.assetPath, onIntegrityError, Modifier.fillMaxSize())
            }
            ReferenceTab.SOLUTION -> if (state.progress?.solutionRevealed == true && content != null) {
                MathContentWebView(content.solutionAssetPath, onIntegrityError, Modifier.fillMaxSize())
            } else {
                Text("Reveal the four hints before opening the solution", Modifier.padding(20.dp))
            }
            else -> Unit
        }
    }
}

@Composable
private fun LoadingReference() {
    Text("Loading problem…", Modifier.padding(20.dp))
}

private fun ReferenceTab.label(): String = when (this) {
    ReferenceTab.PROBLEM -> "Problem"
    ReferenceTab.HELP -> "Help"
    ReferenceTab.PREREQUISITES -> "Prerequisites"
    ReferenceTab.SOLUTION -> "Solution"
    else -> "Problem"
}
