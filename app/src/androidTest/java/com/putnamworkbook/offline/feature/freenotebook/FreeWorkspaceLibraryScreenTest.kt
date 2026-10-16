package com.putnamworkbook.offline.feature.freenotebook

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceEntry
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceNameError
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRecord
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceStorageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FreeWorkspaceLibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateOffersNewWorkspace() {
        composeRule.setContent { library(FreeWorkspaceListUiState(isLoading = false)) }

        composeRule.onNodeWithText("No free notebooks yet. Create one to start writing.").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-new").assertIsDisplayed()
    }

    @Test
    fun rowShowsDatesAndSeparatesOpenRenameDeleteActions() {
        val record = record("Travel notes")
        val calls = mutableListOf<String>()
        composeRule.setContent {
            FreeWorkspaceLibraryScreen(
                state = state(record),
                onBack = {},
                onNew = {},
                onOpen = { calls += "open" },
                onRename = { calls += "rename" },
                onDelete = { calls += "delete" },
                onNameChanged = {},
                onNameConfirmed = {},
                onNameDismissed = {},
                onDeleteConfirmed = {},
                onDeleteDismissed = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Travel notes").assertIsDisplayed()
        composeRule.onNodeWithText("Created ${formatWorkspaceDate(1_000)} · Modified ${formatWorkspaceDate(2_000)}").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-row-${record.id}").performClick()
        composeRule.onNodeWithContentDescription("Rename Travel notes").performClick()
        composeRule.onNodeWithContentDescription("Delete Travel notes").performClick()
        assertEquals(listOf("open", "rename", "delete"), calls)
    }

    @Test
    fun damagedWorkspaceCannotOpenButCanBeManaged() {
        val record = record("Damaged")
        var opened = false
        var renamed = false
        composeRule.setContent {
            FreeWorkspaceLibraryScreen(
                state = state(record, FreeWorkspaceStorageState.MISSING),
                onBack = {}, onNew = {}, onOpen = { opened = true }, onRename = { renamed = true }, onDelete = {},
                onNameChanged = {}, onNameConfirmed = {}, onNameDismissed = {}, onDeleteConfirmed = {},
                onDeleteDismissed = {}, onRetry = {},
            )
        }

        composeRule.onNodeWithTag("free-workspace-damaged-${record.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-row-${record.id}").performClick()
        composeRule.onNodeWithTag("free-workspace-rename-${record.id}").performClick()
        assertTrue(!opened && renamed)
    }

    @Test
    fun deleteConfirmationNamesWorkspace() {
        val record = record("Discard me")
        composeRule.setContent { library(state(record).copy(deleteConfirmation = record)) }

        composeRule.onNodeWithText("Delete Discard me?").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-delete-confirm").assertIsDisplayed()
    }

    @Test
    fun namingDialogShowsInlineErrorsAndChangedAnimationKey() {
        var state by mutableStateOf(
            FreeWorkspaceListUiState(
                isLoading = false,
                nameDialog = FreeWorkspaceNameDialogState(
                    FreeWorkspaceNamePurpose.CREATE,
                    "",
                    nameError = FreeWorkspaceNameError.BLANK,
                ),
                nameErrorAnimationKey = 1,
            ),
        )
        composeRule.setContent {
            library(
                state = state,
                onNameChanged = { value ->
                    state = state.copy(nameDialog = state.nameDialog!!.copy(value = value, nameError = null))
                },
            )
        }

        composeRule.onNodeWithText("Enter a workspace name").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-name-dialog-1").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-name-input").performTextReplacement("Taken")
        composeRule.onNodeWithTag("free-workspace-name-input").assertTextContains("Taken")
        composeRule.runOnIdle {
            state = state.copy(
                nameDialog = state.nameDialog!!.copy(nameError = FreeWorkspaceNameError.DUPLICATE),
                nameErrorAnimationKey = 2,
            )
        }
        composeRule.onNodeWithText("A workspace with this name already exists").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-name-dialog-2").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun library(
        state: FreeWorkspaceListUiState,
        onNameChanged: (String) -> Unit = {},
    ) {
        MaterialTheme {
            FreeWorkspaceLibraryScreen(
                state = state,
                onBack = {}, onNew = {}, onOpen = {}, onRename = {}, onDelete = {},
                onNameChanged = onNameChanged, onNameConfirmed = {}, onNameDismissed = {},
                onDeleteConfirmed = {}, onDeleteDismissed = {}, onRetry = {},
            )
        }
    }

    private fun state(
        record: FreeWorkspaceRecord,
        storageState: FreeWorkspaceStorageState = FreeWorkspaceStorageState.READY,
    ) = FreeWorkspaceListUiState(
        entries = listOf(FreeWorkspaceEntry(record, storageState)),
        isLoading = false,
    )

    private fun record(name: String) = FreeWorkspaceRecord(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        name,
        1_000,
        2_000,
    )
}
