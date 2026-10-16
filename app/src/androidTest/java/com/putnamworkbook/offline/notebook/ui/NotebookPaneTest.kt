package com.putnamworkbook.offline.notebook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotebookPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactToolbarIconsInvokeEveryNotebookActionWithoutVisibleTextLabels() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            NotebookPane(
                state = NotebookUiState(
                    document = NotebookDocument(
                        key = ProblemKey("putnam", "2025", "A1"),
                        pages = listOf(NotebookPage("page-1", PaperStyle.BLANK)),
                    ),
                ),
                brush = BrushSpec(),
                onStrokeFinished = {},
                onUndo = { calls += "undo" },
                onRedo = { calls += "redo" },
                onAddPage = { calls += "add" },
                onRequestDeletePage = { calls += "delete:$it" },
                onRequestClearPage = { calls += "clear:$it" },
                onConfirmDeletePage = {},
                onConfirmClearPage = {},
                onDismissConfirmation = {},
                onSelectPage = {},
            )
        }

        listOf("Undo", "Redo", "Add page", "Clear page", "Delete page").forEach { label ->
            val control = composeRule.onNodeWithContentDescription(label)
            val bounds = control.getUnclippedBoundsInRoot()
            assertTrue("$label touch target is narrower than 48dp", bounds.right - bounds.left >= 48.dp)
            assertTrue("$label touch target is shorter than 48dp", bounds.bottom - bounds.top >= 48.dp)
            control.performClick()
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
        composeRule.onNodeWithText("Clear").assertDoesNotExist()

        assertEquals(listOf("undo", "redo", "add", "clear:page-1", "delete:page-1"), calls)
    }

    @Test
    fun onlyExplicitControlsTurnPagesAndHorizontalCanvasDragDoesNot() {
        val document = NotebookDocument(
            key = ProblemKey("putnam", "2025", "A1"),
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
            key = ProblemKey("putnam", "2025", "A1"),
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
        composeRule.onNodeWithText("Move right").assertDoesNotExist()
        composeRule.onNodeWithText("Page 1").assertDoesNotExist()

        val modeBounds = composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).getUnclippedBoundsInRoot()
        val pageBounds = composeRule.onNodeWithTag(PAGE_INDEX_TEST_TAG).getUnclippedBoundsInRoot()
        assertTrue("Page navigation must share the main toolbar row", modeBounds.top < pageBounds.bottom)
        assertTrue("Page navigation must share the main toolbar row", pageBounds.top < modeBounds.bottom)
    }

    @Test
    fun switchingBetweenPenAndSelectKeepsCanvasTopFixed() {
        var mode by mutableStateOf(NotebookToolMode.PEN)
        composeRule.setContent {
            NotebookPane(
                state = NotebookUiState(
                    document = NotebookDocument(
                        key = ProblemKey("putnam", "2025", "A1"),
                        pages = listOf(NotebookPage("page-1", PaperStyle.BLANK)),
                    ),
                    toolMode = mode,
                ),
                brush = BrushSpec(),
                onStrokeFinished = {},
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
                onSetToolMode = { mode = it },
                modifier = Modifier,
            )
        }

        val penCanvasTop = composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot.top
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).performClick()
        composeRule.waitForIdle()
        val selectCanvasTop = composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot.top

        assertEquals(penCanvasTop, selectCanvasTop, 0.5f)
    }

    @Test
    fun everyCanvasCornerAcceptsPenInputWithoutAnInvisibleBlocker() {
        val document = NotebookDocument(
            key = ProblemKey("putnam", "2025", "A1"),
            pages = listOf(NotebookPage("page-1", PaperStyle.BLANK)),
        )
        val delivered = mutableListOf<StrokeRecord>()
        composeRule.setContent {
            NotebookPane(
                state = NotebookUiState(document = document),
                brush = BrushSpec(),
                onStrokeFinished = { delivered += it },
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
            )
        }

        val canvas = composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG)
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val width = bounds.width
        val height = bounds.height
        val starts = listOf(
            Offset(12f, 12f),
            Offset(width - 20f, 12f),
            Offset(12f, height - 20f),
            Offset(width - 20f, height - 20f),
        )
        canvas.performTouchInput {
            starts.forEach { start ->
                down(start)
                moveTo(start + Offset(6f, 6f), 12L)
                up()
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { delivered.size == 4 }
        assertEquals(4, delivered.map { it.id }.distinct().size)
    }
}
