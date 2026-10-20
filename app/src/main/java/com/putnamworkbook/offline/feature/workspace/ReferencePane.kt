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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.ReferenceTab
import com.putnamworkbook.offline.content.ui.WebDocumentPositionStore

@Composable
fun ReferencePane(
    state: WorkspaceUiState,
    onSelectTab: (ReferenceTab) -> Unit,
    onRevealNextStage: () -> Unit,
    onSelectHint: (Int) -> Unit,
    onSelectPrerequisite: (Int) -> Unit,
    onIntegrityError: (String) -> Unit,
    onOpenMathReference: () -> Unit = {},
    onCloseMathReference: () -> Unit = {},
    mathReferenceContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val documentPositions = remember(state.key) { WebDocumentPositionStore() }
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(8.dp)
                .testTag("reference-tabs"),
        ) {
            ReferenceTab.visibleEntries.forEach { tab ->
                val enabled = tab != ReferenceTab.SOLUTION || state.progress?.solutionRevealed == true
                if (tab == state.selectedReferenceTab && !state.mathReferenceOpen) {
                    Button(
                        onClick = {},
                        enabled = enabled,
                        modifier = Modifier.testTag("reference-tab-${tab.name.lowercase()}"),
                    ) { Text(tab.label()) }
                } else {
                    OutlinedButton(
                        onClick = {
                            if (state.mathReferenceOpen) onCloseMathReference()
                            onSelectTab(tab)
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("reference-tab-${tab.name.lowercase()}"),
                    ) { Text(tab.label()) }
                }
            }
            if (state.mathReferenceOpen) {
                Button(
                    onClick = onCloseMathReference,
                    modifier = Modifier.testTag("workspace-math-reference"),
                ) { Text("Reference") }
            } else {
                OutlinedButton(
                    onClick = onOpenMathReference,
                    enabled = mathReferenceContent != null,
                    modifier = Modifier.testTag("workspace-math-reference"),
                ) { Text("Reference") }
            }
        }
        if (state.mathReferenceOpen && mathReferenceContent != null) {
            Column(Modifier.fillMaxWidth().weight(1f).testTag("workspace-math-reference-content")) {
                mathReferenceContent()
            }
            return@Column
        }
        val content = state.content
        when (state.selectedReferenceTab.normalizedForDisplay()) {
            ReferenceTab.PROBLEM -> content?.let {
                ReferenceDocumentBody(
                    it.statement,
                    onIntegrityError,
                    Modifier.fillMaxWidth().weight(1f),
                    documentPositions,
                )
            } ?: LoadingReference()
            ReferenceTab.HELP -> HelpPane(
                state,
                onRevealNextStage,
                onSelectHint,
                onIntegrityError,
                Modifier.fillMaxWidth().weight(1f),
                positionStore = documentPositions,
            )
            ReferenceTab.PREREQUISITES -> {
                val prerequisites = content?.prerequisites.orEmpty()
                val prerequisite = prerequisites.getOrNull(state.activePrerequisiteIndex)
                if (prerequisites.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                            .testTag("prerequisite-controls"),
                    ) {
                        prerequisites.forEachIndexed { index, item ->
                            OutlinedButton(
                                onClick = { onSelectPrerequisite(index) },
                                enabled = index != state.activePrerequisiteIndex,
                                modifier = Modifier.testTag("prerequisite-${index + 1}"),
                            ) { Text(item.title) }
                        }
                    }
                }
                if (prerequisite == null) {
                    Text("No prerequisite note is needed", Modifier.padding(20.dp))
                } else {
                    ReferenceDocumentBody(
                        prerequisite.resource,
                        onIntegrityError,
                        Modifier.fillMaxWidth().weight(1f),
                        documentPositions,
                    )
                }
            }
            ReferenceTab.SOLUTION -> if (state.progress?.solutionRevealed == true && content != null) {
                ReferenceDocumentBody(
                    content.solution,
                    onIntegrityError,
                    Modifier.fillMaxWidth().weight(1f),
                    documentPositions,
                )
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
