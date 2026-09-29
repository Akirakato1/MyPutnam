package com.putnamworkbook.offline.content.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun OriginalPdfPane(
    state: OriginalPdfUiState,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onShowNormalizedContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = state.visiblePage
            when {
                state.errorMessage != null -> PdfError(
                    message = state.errorMessage,
                    onShowNormalizedContent = onShowNormalizedContent,
                )
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Original paper page ${state.currentPageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
            if (state.isLoading && bitmap != null) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPreviousPage,
                enabled = !state.isLoading && state.currentPageIndex > 0,
            ) {
                Text("Previous")
            }
            Text(
                if (state.pageCount == 0) "No page" else "Page ${state.currentPageIndex + 1} of ${state.pageCount}",
                style = MaterialTheme.typography.labelLarge,
            )
            Button(
                onClick = onNextPage,
                enabled = !state.isLoading && state.currentPageIndex + 1 < state.pageCount,
            ) {
                Text("Next")
            }
        }
    }
}

@Composable
private fun PdfError(
    message: String,
    onShowNormalizedContent: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Original paper unavailable", style = MaterialTheme.typography.titleMedium)
            Text(message, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = onShowNormalizedContent, modifier = Modifier.padding(top = 8.dp)) {
                Text("Read normalized problem")
            }
        }
    }
}
