package com.putnamworkbook.offline.notebook.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
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
                        pages = listOf(
                            NotebookPage("page-1", PaperStyle.BLANK),
                            NotebookPage("page-2", PaperStyle.BLANK),
                        ),
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
    fun singlePageNotebookDisablesDeleteWithoutInvokingIt() {
        var deleteCalls = 0
        composeRule.setContent {
            NotebookPane(
                state = NotebookUiState(
                    document = NotebookDocument(
                        key = ProblemKey("putnam", "2025", "A1"),
                        pages = listOf(NotebookPage("only-page", PaperStyle.BLANK)),
                    ),
                ),
                brush = BrushSpec(),
                onStrokeFinished = {},
                onUndo = {},
                onRedo = {},
                onAddPage = {},
                onRequestDeletePage = { deleteCalls += 1 },
                onRequestClearPage = {},
                onConfirmDeletePage = {},
                onConfirmClearPage = {},
                onDismissConfirmation = {},
                onSelectPage = {},
            )
        }

        composeRule.onNodeWithContentDescription("Delete page")
            .assertIsNotEnabled()
        assertEquals(0, deleteCalls)
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
                state = NotebookUiState(document = document, saveState = NotebookSaveState.SAVED),
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
                modifier = Modifier.width(504.dp),
            )
        }

        composeRule.onNodeWithTag(PAGE_INDEX_TEST_TAG)
            .assertTextEquals("1 / 2")
            .assertContentDescriptionEquals("Page 1 of 2")
        composeRule.onNodeWithTag(PREVIOUS_PAGE_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(NEXT_PAGE_TEST_TAG).assertIsEnabled()
        composeRule.onNodeWithText("Move left").assertDoesNotExist()
        composeRule.onNodeWithText("Move right").assertDoesNotExist()
        composeRule.onNodeWithText("Page 1").assertDoesNotExist()

        val modeBounds = composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).getUnclippedBoundsInRoot()
        val pageBounds = composeRule.onNodeWithTag(PAGE_INDEX_TEST_TAG).getUnclippedBoundsInRoot()
        assertTrue("Page navigation must share the main toolbar row", modeBounds.top < pageBounds.bottom)
        assertTrue("Page navigation must share the main toolbar row", pageBounds.top < modeBounds.bottom)

        val toolbarBounds = composeRule.onNodeWithTag(NOTEBOOK_TOOLBAR_TEST_TAG).getUnclippedBoundsInRoot()
        listOf(
            "Pen mode",
            "Undo",
            "Redo",
            "Add page",
            "Clear page",
            "Delete page",
            "Previous page",
            "Next page",
        ).forEach { label ->
            val bounds = composeRule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue("$label must retain a 48dp target at 504dp pane width", bounds.right - bounds.left >= 48.dp)
            assertTrue("$label must remain inside the toolbar", bounds.left >= toolbarBounds.left)
            assertTrue("$label must remain inside the toolbar", bounds.right <= toolbarBounds.right)
        }
        assertTrue("Page index must remain inside the toolbar", pageBounds.right <= toolbarBounds.right)
        val saveBounds = composeRule.onNodeWithContentDescription("Saved").getUnclippedBoundsInRoot()
        assertTrue("Save indicator must remain inside the toolbar", saveBounds.right <= toolbarBounds.right)

        val paletteBounds = composeRule.onNodeWithTag(BRUSH_COLOR_PALETTE_TEST_TAG).getUnclippedBoundsInRoot()
        val sliderBounds = composeRule.onNodeWithTag(BRUSH_WIDTH_TEST_TAG).getUnclippedBoundsInRoot()
        assertTrue("Eight 48dp colors must remain fully visible", paletteBounds.right - paletteBounds.left >= 384.dp)
        assertTrue("Pen-width slider must retain usable width", sliderBounds.right - sliderBounds.left >= 120.dp)
        assertTrue("Pen-width slider must remain inside the toolbar", sliderBounds.right <= toolbarBounds.right)
    }

    @Test
    fun maximumPageIndexKeepsBothNavigationTargetsAtNarrowSplitWidth() {
        composeRule.setContent {
            NotebookToolbar(
                saveState = NotebookSaveState.SAVED,
                toolMode = NotebookToolMode.PEN,
                brush = BrushSpec(),
                hasSelection = false,
                clipboardHasContent = false,
                onSetToolMode = {},
                onBrushChanged = {},
                onCut = {}, onCopy = {}, onPaste = {}, onDeleteSelection = {},
                onUndo = {}, onRedo = {}, onAddPage = {}, onClearPage = {}, onDeletePage = {},
                activePageIndex = 9_999,
                pageCount = 10_000,
                onSelectPage = {},
                modifier = Modifier.width(504.dp).testTag(NOTEBOOK_TOOLBAR_TEST_TAG),
            )
        }

        val toolbarBounds = composeRule.onNodeWithTag(NOTEBOOK_TOOLBAR_TEST_TAG).getUnclippedBoundsInRoot()
        listOf(PREVIOUS_PAGE_TEST_TAG, NEXT_PAGE_TEST_TAG).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag must retain a 48dp target", bounds.right - bounds.left >= 48.dp)
            assertTrue("$tag must remain inside the toolbar", bounds.right <= toolbarBounds.right)
        }
        composeRule.onNodeWithTag(PAGE_INDEX_TEST_TAG)
            .assertTextEquals("10000 / 10000")
            .assertContentDescriptionEquals("Page 10000 of 10000")
    }

    @Test
    fun selectActionsRetainTouchTargetsAndSnapStateAtNarrowSplitWidth() {
        var snapEnabled by mutableStateOf(false)
        composeRule.setContent {
            NotebookToolbar(
                saveState = NotebookSaveState.SAVED,
                toolMode = NotebookToolMode.SELECT,
                brush = BrushSpec(),
                hasSelection = true,
                clipboardHasContent = true,
                rotationSnapEnabled = snapEnabled,
                onSetToolMode = {},
                onBrushChanged = {},
                onCut = {}, onCopy = {}, onPaste = {}, onDeleteSelection = {},
                onFlipHorizontal = {}, onFlipVertical = {},
                onSetRotationSnapEnabled = { snapEnabled = it },
                onUndo = {}, onRedo = {}, onAddPage = {}, onClearPage = {}, onDeletePage = {},
                activePageIndex = 0,
                pageCount = 1,
                onSelectPage = {},
                modifier = Modifier.width(504.dp).testTag(NOTEBOOK_TOOLBAR_TEST_TAG),
            )
        }

        val toolbarBounds = composeRule.onNodeWithTag(NOTEBOOK_TOOLBAR_TEST_TAG).getUnclippedBoundsInRoot()
        listOf(
            CUT_SELECTION_TEST_TAG,
            COPY_SELECTION_TEST_TAG,
            PASTE_SELECTION_TEST_TAG,
            DELETE_SELECTION_TEST_TAG,
            FLIP_HORIZONTAL_TEST_TAG,
            FLIP_VERTICAL_TEST_TAG,
            SNAP_ROTATION_TEST_TAG,
        ).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag must retain a 48dp target", bounds.right - bounds.left >= 48.dp)
            assertTrue("$tag must remain inside the toolbar", bounds.left >= toolbarBounds.left)
            assertTrue("$tag must remain inside the toolbar", bounds.right <= toolbarBounds.right)
        }
        composeRule.onNodeWithTag(SNAP_ROTATION_TEST_TAG).assertIsNotSelected().performClick()
        composeRule.onNodeWithTag(SNAP_ROTATION_TEST_TAG).assertIsSelected().performClick()
        composeRule.onNodeWithTag(SNAP_ROTATION_TEST_TAG).assertIsNotSelected()
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
