package com.putnamworkbook.offline.notebook.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.edit.NotebookSelectionState
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import com.putnamworkbook.offline.notebook.edit.CanvasViewport
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.edit.toOrientedFrame
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
                onSelect = { rect, _ -> selected = rect },
            )
        }

        composeRule.onNodeWithTag(SELECTION_OVERLAY_TEST_TAG).performTouchInput {
            swipe(
                start = Offset(50f, 80f),
                end = Offset(250f, 280f),
                durationMillis = 400L,
            )
        }
        composeRule.waitForIdle()

        assertNotNull(selected)
        assertEquals(0, inkCount)
    }

    @Test
    fun oneModeToggleAndLivePenControlsReplaceSeparateModeAndEraseButtons() {
        var mode by mutableStateOf(NotebookToolMode.PEN)
        var brush by mutableStateOf(BrushSpec())
        composeRule.setContent {
            NotebookPane(
                state = state(toolMode = mode, brush = brush, clipboardHasContent = true),
                brush = brush,
                onStrokeFinished = {},
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
                onSetToolMode = { mode = it },
                onBrushChanged = { brush = it },
            )
        }

        composeRule.onAllNodesWithTag(TOOL_MODE_TOGGLE_TEST_TAG).assertCountEquals(1)
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).assertContentDescriptionEquals("Pen mode")
        composeRule.onNodeWithText("Pen mode").assertDoesNotExist()
        composeRule.onNodeWithText("Erase last").assertDoesNotExist()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        listOf(
            CUT_SELECTION_TEST_TAG,
            COPY_SELECTION_TEST_TAG,
            PASTE_SELECTION_TEST_TAG,
            DELETE_SELECTION_TEST_TAG,
        ).forEach { tag -> composeRule.onNodeWithTag(tag).assertDoesNotExist() }
        listOf("ink", "blue", "red", "green", "purple", "orange", "teal", "pink").forEach { name ->
            composeRule.onNodeWithTag("notebook-brush-color-$name").assertExists()
        }
        composeRule.onNodeWithTag("notebook-brush-color-palette").assertExists()
        assertTrue(
            composeRule.onNodeWithTag("notebook-brush-color-palette")
                .fetchSemanticsNode().config.contains(SemanticsProperties.SelectableGroup),
        )
        listOf("Ink", "Blue", "Red", "Green", "Purple", "Orange", "Teal", "Pink").forEach { name ->
            composeRule.onNodeWithText(name).assertDoesNotExist()
        }

        composeRule.onNodeWithTag("notebook-brush-color-green").performClick()
        assertEquals(0xFF15803DL, brush.colorArgb)
        composeRule.onNodeWithTag("notebook-brush-color-green").assertIsSelected()
        composeRule.onNodeWithTag("notebook-brush-color-green").assertContentDescriptionEquals("Green")
        val greenBounds = composeRule.onNodeWithTag("notebook-brush-color-green").getUnclippedBoundsInRoot()
        assertTrue(greenBounds.right - greenBounds.left >= 48.dp)
        assertTrue(greenBounds.bottom - greenBounds.top >= 48.dp)
        (1..12).forEach { width ->
            composeRule.onNodeWithTag(BRUSH_WIDTH_TEST_TAG)
                .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                    setProgress(width.toFloat())
                }
            assertEquals(width.toFloat(), brush.width)
        }
        composeRule.onNodeWithTag(BRUSH_WIDTH_TEST_TAG).assertContentDescriptionEquals("Pen width")
        composeRule.onNodeWithText("Width 12").assertDoesNotExist()

        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).performClick()
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).assertContentDescriptionEquals("Select mode")
        composeRule.onNodeWithText("Select mode").assertDoesNotExist()
        composeRule.onNodeWithTag("notebook-brush-color-green").assertDoesNotExist()
        composeRule.onNodeWithTag(BRUSH_WIDTH_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(PASTE_SELECTION_TEST_TAG).assertIsEnabled()
    }

    @Test
    fun contextualCutCopyPasteAndDeleteControlsInvokeOnceWithoutCancelButton() {
        val calls = mutableListOf<String>()
        var snapEnabled by mutableStateOf(false)
        val stroke = StrokeRecord("selected", BrushSpec(), listOf(StrokeSample(0.5f, 0.5f, 0L, null)))
        composeRule.setContent {
            NotebookPane(
                state = state(
                    toolMode = NotebookToolMode.SELECT,
                    strokes = listOf(stroke),
                    selection = selectionState(),
                    clipboardHasContent = true,
                    rotationSnapEnabled = snapEnabled,
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
                onFlipHorizontal = { calls += "flip-horizontal" },
                onFlipVertical = { calls += "flip-vertical" },
                onSetRotationSnapEnabled = {
                    calls += "snap:$it"
                    snapEnabled = it
                },
            )
        }

        listOf(
            CUT_SELECTION_TEST_TAG,
            COPY_SELECTION_TEST_TAG,
            PASTE_SELECTION_TEST_TAG,
            DELETE_SELECTION_TEST_TAG,
            FLIP_HORIZONTAL_TEST_TAG,
            FLIP_VERTICAL_TEST_TAG,
        ).forEach { tag -> composeRule.onNodeWithTag(tag).assertIsEnabled().performClick() }
        composeRule.onNodeWithTag(SNAP_ROTATION_TEST_TAG).assertIsEnabled().assertIsNotSelected().performClick()
        composeRule.onNodeWithTag(SNAP_ROTATION_TEST_TAG).assertIsSelected().performClick()
        composeRule.onNodeWithTag(SNAP_ROTATION_TEST_TAG).assertIsNotSelected()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        assertEquals(
            listOf("cut", "copy", "paste", "delete", "flip-horizontal", "flip-vertical", "snap:true", "snap:false"),
            calls,
        )
    }

    @Test
    fun selectModeAlwaysShowsIconActionsAndDisablesUnavailableOperations() {
        composeRule.setContent {
            NotebookPane(
                state = state(
                    toolMode = NotebookToolMode.SELECT,
                    clipboardHasContent = false,
                ),
                brush = BrushSpec(),
                onStrokeFinished = {},
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
            )
        }

        mapOf(
            CUT_SELECTION_TEST_TAG to "Cut",
            COPY_SELECTION_TEST_TAG to "Copy",
            PASTE_SELECTION_TEST_TAG to "Paste",
            DELETE_SELECTION_TEST_TAG to "Delete selection",
            FLIP_HORIZONTAL_TEST_TAG to "Flip horizontal",
            FLIP_VERTICAL_TEST_TAG to "Flip vertical",
        ).forEach { (tag, label) ->
            composeRule.onNodeWithTag(tag)
                .assertExists()
                .assertIsNotEnabled()
                .assertContentDescriptionEquals(label)
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
        composeRule.onNodeWithTag(SNAP_ROTATION_TEST_TAG)
            .assertExists()
            .assertIsEnabled()
            .assertIsNotSelected()
            .assertContentDescriptionEquals("Snap rotation to 15 degrees")
        composeRule.onNodeWithText("Snap rotation to 15 degrees").assertDoesNotExist()
    }

    @Test
    fun tappingOutsideTheSelectionClearsIt() {
        var clearCount = 0
        val stroke = StrokeRecord("selected", BrushSpec(), listOf(StrokeSample(0.5f, 0.5f, 0L, null)))
        composeRule.setContent {
            NotebookPane(
                state = state(
                    toolMode = NotebookToolMode.SELECT,
                    strokes = listOf(stroke),
                    selection = selectionState(),
                ),
                brush = BrushSpec(), onStrokeFinished = {},
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
                onClearSelection = { clearCount += 1 },
            )
        }

        composeRule.onNodeWithTag(SELECTION_OVERLAY_TEST_TAG).performTouchInput {
            click(Offset(20f, 20f))
        }
        composeRule.waitForIdle()
        assertEquals(1, clearCount)
    }

    @Test
    fun tappingInsideTheSelectionPreservesIt() {
        var clearCount = 0
        val stroke = StrokeRecord("selected", BrushSpec(), listOf(StrokeSample(0.5f, 0.5f, 0L, null)))
        composeRule.setContent {
            NotebookPane(
                state = state(
                    toolMode = NotebookToolMode.SELECT,
                    strokes = listOf(stroke),
                    selection = selectionState(),
                ),
                brush = BrushSpec(), onStrokeFinished = {},
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
                onClearSelection = { clearCount += 1 },
            )
        }

        val overlay = composeRule.onNodeWithTag(SELECTION_OVERLAY_TEST_TAG)
        val bounds = overlay.fetchSemanticsNode().boundsInRoot
        overlay.performTouchInput { click(Offset(bounds.width * 0.5f, bounds.height * 0.5f)) }
        composeRule.waitForIdle()
        assertEquals(0, clearCount)
    }

    @Test
    fun draggingASelectionHandleScalesWithoutClearingTheSelection() {
        var clearCount = 0
        var scaleCommitCount = 0
        val stroke = StrokeRecord("selected", BrushSpec(), listOf(StrokeSample(0.5f, 0.5f, 0L, null)))
        composeRule.setContent {
            NotebookPane(
                state = state(
                    toolMode = NotebookToolMode.SELECT,
                    strokes = listOf(stroke),
                    selection = selectionState(),
                ),
                brush = BrushSpec(), onStrokeFinished = {},
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
                onCommitScale = { _, _ -> scaleCommitCount += 1 },
                onClearSelection = { clearCount += 1 },
            )
        }

        val overlay = composeRule.onNodeWithTag(SELECTION_OVERLAY_TEST_TAG)
        val bounds = overlay.fetchSemanticsNode().boundsInRoot
        overlay.performTouchInput {
            swipe(
                start = Offset(bounds.width * 0.4f, bounds.height * 0.4f),
                end = Offset(bounds.width * 0.3f, bounds.height * 0.3f),
                durationMillis = 400L,
            )
        }
        composeRule.waitForIdle()
        assertEquals(1, scaleCommitCount)
        assertEquals(0, clearCount)
    }

    @Test
    fun draggingRotationHandlePreviewsAndCommitsWithoutClearingSelection() {
        val previews = mutableListOf<Float>()
        var commitCount = 0
        var selectCount = 0
        var clearCount = 0
        val stroke = StrokeRecord("selected", BrushSpec(), listOf(StrokeSample(0.5f, 0.5f, 0L, null)))
        composeRule.setContent {
            NotebookPane(
                state = state(
                    toolMode = NotebookToolMode.SELECT,
                    strokes = listOf(stroke),
                    selection = selectionState(),
                ),
                brush = BrushSpec(), onStrokeFinished = {},
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
                onSelect = { _, _ -> selectCount += 1 },
                onPreviewRotation = { previews += it },
                onCommitRotation = { commitCount += 1 },
                onClearSelection = { clearCount += 1 },
            )
        }

        val overlay = composeRule.onNodeWithTag(SELECTION_OVERLAY_TEST_TAG)
        val bounds = overlay.fetchSemanticsNode().boundsInRoot
        val accessibleHandle = composeRule.onNodeWithTag(ROTATION_HANDLE_TEST_TAG)
        accessibleHandle.assertContentDescriptionEquals("Rotate selection")
        val handleBounds = accessibleHandle.getUnclippedBoundsInRoot()
        assertTrue(handleBounds.right - handleBounds.left >= 48.dp)
        assertTrue(handleBounds.bottom - handleBounds.top >= 48.dp)
        assertEquals(
            listOf("Rotate clockwise 15 degrees", "Rotate counterclockwise 15 degrees"),
            accessibleHandle.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label },
        )
        val stemPixels = with(composeRule.density) { 32.dp.toPx() }
        overlay.performTouchInput {
            swipe(
                start = Offset(bounds.width * 0.5f, bounds.height * 0.4f - stemPixels),
                end = Offset(bounds.width * 0.7f, bounds.height * 0.5f),
                durationMillis = 400L,
            )
        }
        composeRule.waitForIdle()

        assertTrue(previews.isNotEmpty())
        assertEquals(Math.toRadians(90.0).toFloat(), previews.last(), 0.08f)
        assertEquals(1, commitCount)
        assertEquals(0, selectCount)
        assertEquals(0, clearCount)
    }

    @Test
    fun draggingOutsideTheSelectionCreatesAReplacementMarquee() {
        var replacement: NormalizedRect? = null
        val stroke = StrokeRecord("selected", BrushSpec(), listOf(StrokeSample(0.5f, 0.5f, 0L, null)))
        composeRule.setContent {
            NotebookPane(
                state = state(
                    toolMode = NotebookToolMode.SELECT,
                    strokes = listOf(stroke),
                    selection = selectionState(),
                ),
                brush = BrushSpec(), onStrokeFinished = {},
                onUndo = {}, onRedo = {}, onAddPage = {},
                onRequestDeletePage = {}, onRequestClearPage = {},
                onConfirmDeletePage = {}, onConfirmClearPage = {}, onDismissConfirmation = {},
                onSelectPage = {},
                onSelect = { rect, _ -> replacement = rect },
            )
        }

        val overlay = composeRule.onNodeWithTag(SELECTION_OVERLAY_TEST_TAG)
        val bounds = overlay.fetchSemanticsNode().boundsInRoot
        overlay.performTouchInput {
            swipe(
                start = Offset(bounds.width * 0.1f, bounds.height * 0.1f),
                end = Offset(bounds.width * 0.2f, bounds.height * 0.2f),
                durationMillis = 400L,
            )
        }
        composeRule.waitForIdle()
        assertNotNull(replacement)
        assertEquals(0.1f, replacement!!.left, 0.02f)
        assertEquals(0.2f, replacement!!.right, 0.02f)
    }

    private fun state(
        toolMode: NotebookToolMode,
        strokes: List<StrokeRecord> = emptyList(),
        selection: NotebookSelectionState? = null,
        clipboardHasContent: Boolean = false,
        brush: BrushSpec = BrushSpec(),
        rotationSnapEnabled: Boolean = false,
    ) = NotebookUiState(
        document = NotebookDocument(
            key = ProblemKey("putnam", "2025", "A1"),
            pages = listOf(NotebookPage("page", PaperStyle.BLANK)),
        ),
        strokes = strokes,
        toolMode = toolMode,
        selection = selection,
        clipboardHasContent = clipboardHasContent,
        brush = brush,
        rotationSnapEnabled = rotationSnapEnabled,
    )

    private fun selectionState(): NotebookSelectionState {
        val bounds = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)
        return NotebookSelectionState(
            selectedStrokeIds = setOf("selected"),
            bounds = bounds,
            frame = bounds.toOrientedFrame(CanvasViewport(1f, 1f)),
        )
    }
}
