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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey

@Composable
fun ArchiveScreen(
    state: ArchiveUiState,
    onBack: () -> Unit,
    onSelectTopic: (String?) -> Unit,
    onSelectSet: (ProblemSetKey) -> Unit,
    onSelectProblem: (ProblemKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("library-screen"),
    ) {
        ArchiveHeader(title = "Select a problem", onBack = onBack)
        TopicFilterRow(
            topics = state.topics,
            selectedTopicId = state.selectedTopicId,
            onSelectTopic = onSelectTopic,
        )
        state.progressError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (state.selectedTopicId == null) {
            AllProblemsBody(state, onSelectSet)
        } else {
            TopicProblemsBody(state, onSelectProblem)
        }
    }
}

@Composable
private fun AllProblemsBody(
    state: ArchiveUiState,
    onSelectSet: (ProblemSetKey) -> Unit,
) {
        when {
            state.isLoadingCatalog -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.catalogError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.catalogError, color = MaterialTheme.colorScheme.error)
            }
            state.collections.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No installed problem collections are available")
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("library-catalog-list"),
            ) {
                state.collections.forEach { collection ->
                    item(key = "collection-${collection.id}") {
                        Text(
                            text = collection.title,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 14.dp)
                                .testTag("library-collection-${collection.id}"),
                        )
                    }
                    items(
                        items = state.setsByCollection[collection.id].orEmpty(),
                        key = { it.key.canonical },
                    ) { set ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSet(set.key) }
                                .testTag("library-set-${set.key.canonical}"),
                        ) {
                            Text(
                                text = set.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(horizontal = 40.dp, vertical = 18.dp),
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
}

@Composable
private fun TopicProblemsBody(
    state: ArchiveUiState,
    onSelectProblem: (ProblemKey) -> Unit,
) {
    when {
        state.isLoadingTopicProblems -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.topicError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(state.topicError, color = MaterialTheme.colorScheme.error)
        }
        state.topicProblems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No installed problems match this topic")
        }
        else -> LazyColumn(Modifier.fillMaxSize().testTag("library-topic-results")) {
            state.collections.forEach { collection ->
                state.setsByCollection[collection.id].orEmpty().forEach { set ->
                    val rows = state.topicProblems.filter { it.summary.key.setKey == set.key }
                    if (rows.isNotEmpty()) {
                        item(key = "topic-heading-${set.key.canonical}") {
                            Text(
                                "${collection.title} — ${set.title}",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                        items(rows, key = { it.summary.key.canonical }) { row ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectProblem(row.summary.key) }
                                    .testTag("library-problem-${row.summary.key.canonical}"),
                            ) {
                                Text(
                                    "${row.summary.label} · ${row.progress.status.name.lowercase().replace('_', ' ')}",
                                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 16.dp),
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArchiveHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("archive-back")) {
            Text("Back")
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}
