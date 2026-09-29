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

@Composable
fun ArchiveScreen(
    state: ArchiveUiState,
    onBack: () -> Unit,
    onSelectYear: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("archive-years-screen"),
    ) {
        ArchiveHeader(title = "Select a year", onBack = onBack)
        when {
            state.isLoadingYears -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.errorMessage != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
            }
            state.years.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No archive years are available in this build")
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("archive-years-list"),
            ) {
                items(state.years, key = { it }) { year ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectYear(year) }
                            .testTag("archive-year-$year"),
                    ) {
                        Text(
                            text = year.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp),
                        )
                    }
                    HorizontalDivider()
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
