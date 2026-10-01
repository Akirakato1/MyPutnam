package com.putnamworkbook.offline.notebook.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.edit.NotebookSelectionState
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class NotebookSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectModeUsesMarqueeWithoutEmittingInk() {
        var selected: NormalizedRect? = null
        var inkCount = 0
        composeRule.setContent {
            NotebookPane(
                state = state(toolMode = NotebookToolMode.SELECT),
                brush = BrushSpec(),
                onStrokeFinished = { inkCount += 1 },
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
                onSelect = { selected = it },
            )
        }

        composeRule.onNodeWithTag(SELECTION_OVERLAY_TEST_TAG).performTouchInput {
            down(Offset(50f, 80f))
            moveTo(Offset(250f, 280f))
            up()
        }
        composeRule.waitForIdle()

        assertNotNull(selected)
        assertEquals(0, inkCount)
    }

    @Test
    fun contextualCutCopyPasteDeleteAndCancelControlsInvokeOnce() {
        val calls = mutableListOf<String>()
        val stroke = StrokeRecord("selected", BrushSpec(), listOf(StrokeSample(0.5f, 0.5f, 0L, null)))
        composeRule.setContent {
            NotebookPane(
                state = state(
                    toolMode = NotebookToolMode.SELECT,
                    strokes = listOf(stroke),
                    selection = NotebookSelectionState(setOf("selected"), NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)),
                    clipboardHasContent = true,
                ),
                brush = BrushSpec(), onStrokeFinished = {},
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
                onCutSelection = { calls += "cut" },
                onCopySelection = { calls += "copy" },
                onPaste = { calls += "paste" },
                onDeleteSelection = { calls += "delete" },
                onCancelSelection = { calls += "cancel" },
            )
        }

        listOf(
            CUT_SELECTION_TEST_TAG,
            COPY_SELECTION_TEST_TAG,
            PASTE_SELECTION_TEST_TAG,
            DELETE_SELECTION_TEST_TAG,
            CANCEL_SELECTION_TEST_TAG,
        ).forEach { tag -> composeRule.onNodeWithTag(tag).assertIsEnabled().performClick() }
        assertEquals(listOf("cut", "copy", "paste", "delete", "cancel"), calls)
    }

    private fun state(
        toolMode: NotebookToolMode,
        strokes: List<StrokeRecord> = emptyList(),
        selection: NotebookSelectionState? = null,
        clipboardHasContent: Boolean = false,
    ) = NotebookUiState(
        document = NotebookDocument(
            key = ProblemKey(2025, "A1"),
            pages = listOf(NotebookPage("page", PaperStyle.BLANK)),
        ),
        strokes = strokes,
        toolMode = toolMode,
        selection = selection,
        clipboardHasContent = clipboardHasContent,
    )
}
