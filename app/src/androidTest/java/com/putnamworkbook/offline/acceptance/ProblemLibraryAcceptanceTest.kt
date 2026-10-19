package com.putnamworkbook.offline.acceptance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.index.PackageAction
import com.putnamworkbook.offline.feature.library.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProblemLibraryAcceptanceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun offlineCatalogCanDeleteInstalledContentButCannotStartDownloads() {
        val installed = acceptanceRow("putnam/2025/A1", LibraryEntryStatus.INSTALLED, PackageAction.DELETE, true)
        val available = acceptanceRow("putnam/2025/A2", LibraryEntryStatus.AVAILABLE, PackageAction.INSTALL, false)
        var state by mutableStateOf(
            ProblemLibraryUiState(allEntries = listOf(installed, available), visibleEntries = listOf(installed, available), isOnline = false),
        )
        var requested: String? = null
        composeRule.setContent {
            MaterialTheme {
                ProblemLibraryScreen(
                    state = state,
                    onBack = {},
                    onItemAction = { key ->
                        requested = key
                        state = state.copy(confirmation = LibraryActionConfirmation(setOf(key), PackageAction.DELETE, 1))
                    },
                )
            }
        }

        composeRule.onNodeWithTag("problem-library-action-putnam/2025/A2").assertIsNotEnabled()
        composeRule.onNodeWithTag("problem-library-action-putnam/2025/A1").assertIsEnabled().performClick()
        assertEquals("putnam/2025/A1", requested)
        composeRule.onNodeWithText("Notebook pages and progress will be retained.", substring = true).assertIsDisplayed()
    }
}

private fun acceptanceRow(
    key: String,
    status: LibraryEntryStatus,
    action: PackageAction,
    enabled: Boolean,
) = LibraryEntryRow(
    key = key,
    contentType = CatalogContentType.PROBLEM,
    title = key.substringAfterLast('/'),
    identity = key.replace('/', ' '),
    collectionId = "putnam",
    setId = "2025",
    topics = listOf("algebra"),
    categoryId = null,
    aliases = emptyList(),
    keywords = emptyList(),
    compressedBytes = 1024,
    status = status,
    primaryAction = action,
    actionEnabled = enabled,
    bundled = status == LibraryEntryStatus.INSTALLED,
    hasProgress = status == LibraryEntryStatus.INSTALLED,
    catalogListed = true,
    operation = null,
)
