package com.putnamworkbook.offline.feature.freenotebook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FreeNotebookEditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorIsFullWidthAndHasNoReferencePane() {
        composeRule.setContent {
            MaterialTheme {
                FreeNotebookEditorScreen(
                    state = readyState(),
                    onBack = {}, onSave = {}, onSaveAs = {}, onSaveAsNameChanged = {},
                    onSaveAsConfirmed = {}, onSaveAsDismissed = {},
                    notebookContent = {
                        Box(Modifier.fillMaxSize().testTag("complete-notebook-toolbar-and-canvas"))
                    },
                )
            }
        }

        composeRule.onNodeWithTag("free-notebook-notebook-pane").assertIsDisplayed()
        composeRule.onNodeWithTag("complete-notebook-toolbar-and-canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("workspace-reference-pane").assertDoesNotExist()
    }

    @Test
    fun headerShowsNameAndInvokesSaveActions() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                FreeNotebookEditorScreen(
                    state = readyState(),
                    onBack = { calls += "back" },
                    onSave = { calls += "save" },
                    onSaveAs = { calls += "save-as" },
                    onSaveAsNameChanged = {}, onSaveAsConfirmed = {}, onSaveAsDismissed = {},
                    notebookContent = {},
                )
            }
        }

        composeRule.onNodeWithTag("free-notebook-title").assertIsDisplayed()
        composeRule.onNodeWithTag("free-notebook-save").performClick()
        composeRule.onNodeWithTag("free-notebook-save-as").performClick()
        composeRule.onNodeWithTag("free-notebook-back").performClick()
        assertEquals(listOf("save", "save-as", "back"), calls)
    }

    @Test
    fun saveAsUsesSharedErrorDialog() {
        val state = readyState().copy(
            saveAsDialog = FreeWorkspaceNameDialogState(
                purpose = FreeWorkspaceNamePurpose.SAVE_AS,
                value = "Scratch",
                nameError = com.putnamworkbook.offline.freeworkspace.FreeWorkspaceNameError.DUPLICATE,
            ),
            nameErrorAnimationKey = 4,
        )
        composeRule.setContent {
            MaterialTheme {
                FreeNotebookEditorScreen(
                    state = state,
                    onBack = {}, onSave = {}, onSaveAs = {}, onSaveAsNameChanged = {},
                    onSaveAsConfirmed = {}, onSaveAsDismissed = {}, notebookContent = {},
                )
            }
        }

        composeRule.onNodeWithTag("free-workspace-name-dialog-4").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-name-input").assertIsDisplayed()
    }

    private fun readyState() = FreeNotebookEditorUiState(
        record = FreeWorkspaceRecord(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Scratch",
            1,
            1,
        ),
        isLoading = false,
        canEdit = true,
    )
}
