package com.putnamworkbook.offline.feature.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import com.putnamworkbook.offline.domain.model.ProblemStatus

@Composable
fun ProblemListScreen(
    setKey: ProblemSetKey,
    state: ArchiveUiState,
    onBack: () -> Unit,
    onSelectProblem: (ProblemKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("problem-list-screen"),
    ) {
        ArchiveHeader(title = state.selectedSet?.title ?: setKey.setId, onBack = onBack)
        when {
            state.isLoadingSetProblems || state.selectedSet?.key != setKey -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.setError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.setError, color = MaterialTheme.colorScheme.error)
            }
            state.setProblems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No problems are available for ${state.selectedSet?.title ?: setKey.setId}")
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("problem-list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                itemsIndexed(state.setProblems, key = { _, row -> row.summary.key.canonical }) { index, row ->
                    ProblemRow(index, row, onSelectProblem)
                }
            }
        }
    }
}

@Composable
private fun ProblemRow(
    index: Int,
    row: ProblemRowUiState,
    onSelectProblem: (ProblemKey) -> Unit,
) {
    val label = row.summary.label
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectProblem(row.summary.key) }
            .testTag("library-problem-${row.summary.key.canonical}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.progress.status.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("problem-status-${row.summary.key.canonical}"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(
                        text = "${row.progress.revealedHintCount} ${plural(row.progress.revealedHintCount, "hint", "hints")} revealed",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("problem-hints-${row.summary.key.canonical}"),
                    )
                    Text(
                        text = "${row.progress.notebookPageCount} notebook ${plural(row.progress.notebookPageCount, "page", "pages")}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("problem-pages-${row.summary.key.canonical}"),
                    )
                }
            }
        }
    }
}

private fun ProblemStatus.displayName(): String = when (this) {
    ProblemStatus.UNTOUCHED -> "Untouched"
    ProblemStatus.IN_PROGRESS -> "In progress"
    ProblemStatus.COMPLETED -> "Completed"
}

private fun plural(count: Int, singular: String, plural: String) = if (count == 1) singular else plural
