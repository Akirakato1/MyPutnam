package com.putnamworkbook.offline.notebook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotebookPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onlyExplicitControlsTurnPagesAndHorizontalCanvasDragDoesNot() {
        val document = NotebookDocument(
            key = ProblemKey(2025, "A1"),
            pages = listOf(
                NotebookPage("page-1", PaperStyle.BLANK),
                NotebookPage("page-2", PaperStyle.SQUARE_GRID),
            ),
        )
        var selectedPage by mutableStateOf(0)
        composeRule.setContent {
            NotebookPane(
                state = NotebookUiState(
                    document = document,
                    activePageIndex = selectedPage,
                ),
                brush = BrushSpec(),
                onStrokeFinished = {},
                onUndo = {},
                onRedo = {},
                onAddPage = {},
                onRequestDeletePage = {},
                onRequestClearPage = {},
                onConfirmDeletePage = {},
                onConfirmClearPage = {},
                onDismissConfirmation = {},
                onSelectPage = { selectedPage = it },
            )
        }

        composeRule.onNodeWithTag(NEXT_PAGE_TEST_TAG).performClick()
        assertEquals(1, selectedPage)
        composeRule.onNodeWithTag(PREVIOUS_PAGE_TEST_TAG).performClick()
        assertEquals(0, selectedPage)

        composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG).performTouchInput {
            down(Offset(80f, 180f))
            moveTo(Offset(500f, 180f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(0, selectedPage)
    }

    @Test
    fun pageIndexAndBoundaryArrowsAreTopControlsWithoutNumberedOrReorderButtons() {
        val document = NotebookDocument(
            key = ProblemKey(2025, "A1"),
            pages = listOf(
                NotebookPage("page-1", PaperStyle.BLANK),
                NotebookPage("page-2", PaperStyle.BLANK),
            ),
        )
        composeRule.setContent {
            NotebookPane(
                state = NotebookUiState(document = document),
                brush = BrushSpec(),
                onStrokeFinished = {},
                onUndo = {},
                onRedo = {},
                onAddPage = {},
                onRequestDeletePage = {},
                onRequestClearPage = {},
                onConfirmDeletePage = {},
                onConfirmClearPage = {},
                onDismissConfirmation = {},
                onSelectPage = {},
            )
        }

        composeRule.onNodeWithTag(PAGE_INDEX_TEST_TAG).assertTextEquals("Page 1 of 2")
        composeRule.onNodeWithTag(PREVIOUS_PAGE_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(NEXT_PAGE_TEST_TAG).assertIsEnabled()
        composeRule.onNodeWithText("Move left").assertDoesNotExist()
        composeRule.onNodeWithTag("palm-guard").assertDoesNotExist()
    }
}
