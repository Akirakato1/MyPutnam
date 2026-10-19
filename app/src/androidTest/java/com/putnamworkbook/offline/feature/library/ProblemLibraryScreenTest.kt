package com.putnamworkbook.offline.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.index.PackageAction
import org.junit.Rule
import org.junit.Test

class ProblemLibraryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun problemMetadataOfflineExplanationAndDeleteConfirmationAreVisible() {
        val row = LibraryEntryRow(
            key = "putnam/2025/A1", contentType = CatalogContentType.PROBLEM, title = "A1",
            identity = "Putnam 2025 A1", collectionId = "putnam", setId = "2025",
            topics = listOf("algebra"), categoryId = null, aliases = emptyList(), keywords = emptyList(),
            compressedBytes = 2048, status = LibraryEntryStatus.INSTALLED, primaryAction = PackageAction.DELETE,
            actionEnabled = true, bundled = true, hasProgress = true, catalogListed = true, operation = null,
        )
        val state = ProblemLibraryUiState(
            allEntries = listOf(row), visibleEntries = listOf(row), isOnline = false,
            confirmation = LibraryActionConfirmation(setOf(row.key), PackageAction.DELETE, 1),
        )
        composeRule.setContent {
            MaterialTheme {
                ProblemLibraryScreen(state, {})
            }
        }

        composeRule.onNodeWithTag("problem-library-row-putnam/2025/A1").assertIsDisplayed()
        composeRule.onNodeWithText("algebra").assertIsDisplayed()
        composeRule.onNodeWithText("2 KB").assertIsDisplayed()
        composeRule.onNodeWithText("Offline: downloads and updates are unavailable. Local delete and bundled restore still work.").assertIsDisplayed()
        composeRule.onNodeWithText("Notebook pages and progress will be retained.", substring = true).assertIsDisplayed()
    }
}
