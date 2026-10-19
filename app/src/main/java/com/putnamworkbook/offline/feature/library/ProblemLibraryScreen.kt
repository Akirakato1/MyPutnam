package com.putnamworkbook.offline.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.data.library.index.PackageAction

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProblemLibraryScreen(
    state: ProblemLibraryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onSegmentChanged: (LibraryContentSegment) -> Unit = {},
    onQueryChanged: (String) -> Unit = {},
    onCollectionChanged: (String?) -> Unit = {},
    onSetChanged: (String?) -> Unit = {},
    onTopicChanged: (String?) -> Unit = {},
    onCategoryChanged: (String?) -> Unit = {},
    onInstallFilterChanged: (LibraryInstallFilter) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onItemAction: (String) -> Unit = {},
    onSelectedAction: (PackageAction) -> Unit = {},
    onConfirmAction: () -> Unit = {},
    onDismissAction: () -> Unit = {},
) {
    state.confirmation?.let { LibraryActionDialog(it, onConfirmAction, onDismissAction) }
    Column(Modifier.fillMaxSize().testTag("problem-library-screen")) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("problem-library-back")) { Text("Back") }
            Text("Problem Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh, enabled = state.isOnline && !state.isRefreshing) {
                Text(if (state.isRefreshing) "Refreshing…" else "Refresh catalog")
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 12.dp)) {
            LibraryContentSegment.entries.forEachIndexed { index, segment ->
                SegmentedButton(
                    selected = state.contentSegment == segment,
                    onClick = { onSegmentChanged(segment) },
                    shape = SegmentedButtonDefaults.itemShape(index, LibraryContentSegment.entries.size),
                ) { Text(if (segment == LibraryContentSegment.PROBLEMS) "Problems" else "Math Reference") }
            }
        }
        OutlinedTextField(
            value = state.filters.query,
            onValueChange = onQueryChanged,
            label = { Text("Search titles and metadata") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("problem-library-search"),
        )
        LibraryFilterControls(
            state, onCollectionChanged, onSetChanged, onTopicChanged, onCategoryChanged, onInstallFilterChanged,
        )
        if (!state.isOnline) {
            Text(
                "Offline: downloads and updates are unavailable. Local delete and bundled restore still work.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        state.refreshError?.let {
            Text("Catalog refresh failed: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
        }
        state.batchProgress?.let { progress ->
            Text(
                "Operations: ${progress.completed} complete, ${progress.remaining} remaining, ${progress.failed} failed, ${progress.cancelled} cancelled",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (state.selectedKeys.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${state.selectedKeys.size} selected", modifier = Modifier.align(Alignment.CenterVertically))
                Button(onClick = { onSelectedAction(PackageAction.INSTALL) }, enabled = state.isOnline) { Text("Download") }
                Button(onClick = { onSelectedAction(PackageAction.UPDATE) }, enabled = state.isOnline) { Text("Update") }
                OutlinedButton(onClick = { onSelectedAction(PackageAction.DELETE) }) { Text("Delete") }
                OutlinedButton(onClick = { onSelectedAction(PackageAction.RESTORE) }) { Text("Restore") }
                TextButton(onClick = onClearSelection) { Text("Clear selection") }
            }
        } else if (state.visibleEntries.isNotEmpty()) {
            TextButton(onClick = onSelectAll, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Select all shown") }
        }
        HorizontalDivider()
        if (state.visibleEntries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No catalog items match these filters")
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).testTag("problem-library-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.visibleEntries, key = { it.key }) { row ->
                    ProblemLibraryRow(
                        row = row,
                        selected = row.key in state.selectedKeys,
                        onToggleSelection = { onToggleSelection(row.key) },
                        onAction = { onItemAction(row.key) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryFilterControls(
    state: ProblemLibraryUiState,
    onCollectionChanged: (String?) -> Unit,
    onSetChanged: (String?) -> Unit,
    onTopicChanged: (String?) -> Unit,
    onCategoryChanged: (String?) -> Unit,
    onInstallFilterChanged: (LibraryInstallFilter) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LibraryInstallFilter.entries.forEach { filter ->
            FilterChip(
                selected = state.filters.installState == filter,
                onClick = { onInstallFilterChanged(filter) },
                label = { Text(filter.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) },
            )
        }
        if (state.contentSegment == LibraryContentSegment.PROBLEMS) {
            state.allEntries.mapNotNull { it.collectionId }.distinct().sorted().forEach { id ->
                FilterChip(state.filters.collectionId == id, { onCollectionChanged(if (state.filters.collectionId == id) null else id) }, { Text(id) })
            }
            state.allEntries.filter { state.filters.collectionId == null || it.collectionId == state.filters.collectionId }
                .mapNotNull { it.setId }.distinct().sortedDescending().forEach { id ->
                    FilterChip(state.filters.setId == id, { onSetChanged(if (state.filters.setId == id) null else id) }, { Text(id) })
                }
            state.allEntries.flatMap { it.topics }.distinct().sorted().forEach { id ->
                FilterChip(state.filters.topicId == id, { onTopicChanged(if (state.filters.topicId == id) null else id) }, { Text(id) })
            }
        } else {
            state.allEntries.mapNotNull { it.categoryId }.distinct().sorted().forEach { id ->
                FilterChip(state.filters.referenceCategoryId == id, { onCategoryChanged(if (state.filters.referenceCategoryId == id) null else id) }, { Text(id) })
            }
        }
    }
}
