package com.putnamworkbook.offline.notebook.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.notebook.model.NotebookDocument

const val PREVIOUS_PAGE_TEST_TAG = "previous-page"
const val NEXT_PAGE_TEST_TAG = "next-page"

@Composable
fun PageStrip(
    document: NotebookDocument,
    activePageIndex: Int,
    onSelectPage: (Int) -> Unit,
    onReorderPage: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onSelectPage(activePageIndex - 1) },
            enabled = activePageIndex > 0,
            modifier = Modifier.testTag(PREVIOUS_PAGE_TEST_TAG),
        ) { Text("Previous") }
        document.pages.forEachIndexed { index, page ->
            if (index == activePageIndex) {
                Button(onClick = { onSelectPage(index) }) { Text("Page ${index + 1}") }
            } else {
                OutlinedButton(onClick = { onSelectPage(index) }) { Text("Page ${index + 1}") }
            }
            if (index > 0) {
                TextButton(onClick = { onReorderPage(page.id, index - 1) }) { Text("Move left") }
            }
        }
        OutlinedButton(
            onClick = { onSelectPage(activePageIndex + 1) },
            enabled = activePageIndex < document.pages.lastIndex,
            modifier = Modifier.testTag(NEXT_PAGE_TEST_TAG),
        ) { Text("Next") }
    }
}
