package com.putnamworkbook.offline.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.ProblemKey

@Composable
fun HomeScreen(
    state: HomeUiState,
    onContinue: (ProblemKey) -> Unit,
    onSelectProblem: () -> Unit,
    onFreeNotebook: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("home-screen")
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Putnam Workbook", style = MaterialTheme.typography.displaySmall)
        Text(
            "Offline problems, hints, solutions, and scratch work",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Button(
                onClick = { state.recentProblem?.key?.let(onContinue) },
                enabled = state.recentProblem != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("home-continue"),
            ) {
                val recent = state.recentProblem
                Text(if (recent == null) "Continue most recent" else "Continue ${recent.setTitle} ${recent.label}")
            }
            Button(
                onClick = onSelectProblem,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("home-select-problem"),
            ) {
                Text("Select problem")
            }
            Button(
                onClick = onFreeNotebook,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("home-free-notebook"),
            ) {
                Text("Free Notebook")
            }
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("home-settings"),
            ) {
                Text("Settings")
            }
        }
        if (state.errorMessage != null) {
            Spacer(Modifier.height(16.dp))
            Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
        }
    }
}
