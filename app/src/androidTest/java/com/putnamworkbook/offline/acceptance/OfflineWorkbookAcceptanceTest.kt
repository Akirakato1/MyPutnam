package com.putnamworkbook.offline.acceptance

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.putnamworkbook.offline.MainActivity
import com.putnamworkbook.offline.PutnamApplication
import com.putnamworkbook.offline.di.initializeContent
import com.putnamworkbook.offline.backup.BackupResult
import com.putnamworkbook.offline.backup.RestoreResult
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceTab
import com.putnamworkbook.offline.export.ExportResult
import com.putnamworkbook.offline.export.WorkbookExportOptions
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import com.putnamworkbook.offline.notebook.edit.selectionBounds
import com.putnamworkbook.offline.notebook.edit.CanvasViewport
import com.putnamworkbook.offline.notebook.ui.COPY_SELECTION_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.CUT_SELECTION_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.DELETE_SELECTION_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.FLIP_HORIZONTAL_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.FLIP_VERTICAL_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.NEXT_PAGE_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.NOTEBOOK_CANVAS_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.PASTE_SELECTION_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.PREVIOUS_PAGE_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.REDO_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.SNAP_ROTATION_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.RenderedStrokeCountKey
import com.putnamworkbook.offline.notebook.ui.SELECTION_OVERLAY_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.TOOL_MODE_TOGGLE_TEST_TAG
import com.putnamworkbook.offline.notebook.ui.UNDO_TEST_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class OfflineWorkbookAcceptanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private var expectedTransformedStrokes = emptyList<StrokeRecord>()

    @Test
    fun offlineWorkbookFlowKeepsContentInkProgressBackupAndExport() {
        openA1FromTheArchive()
        assertLandscapeSplitWorkspace()
        revealAllHintsAndSolution()
        addNotebookPageAndMarkCompleted()
        createStrokeBackupAndPdf()

        composeRule.onNodeWithTag("workspace-home").performClick()
        waitForTag("home-screen")
        composeRule.activityRule.scenario.recreate()
        waitForTag("home-screen")
        composeRule.onNodeWithTag("home-continue").assertIsDisplayed().performClick()
        waitForTag("workspace-screen")
        composeRule.onNodeWithTag("workspace-completed").assertTextEquals("Completed")
        waitForText("Page 2 of 2")
        exerciseSelectionEditingAndPageNavigation()
        verifyEditedNotebookSurvivesHomeAndContinue()
    }

    private fun openA1FromTheArchive() {
        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        composeRule.onNodeWithText("Browse Problems").assertIsDisplayed()
        composeRule.onNodeWithTag("home-problem-library").assertIsDisplayed()
        composeRule.onNodeWithTag("home-select-problem").performClick()
        waitForTag("library-set-putnam/2025")
        composeRule.onNodeWithTag("library-set-putnam/2025").performClick()
        waitForTag("problem-list")

        val codes = listOf("A1", "A2", "A3", "A4", "A5", "A6", "B1", "B2", "B3", "B4", "B5", "B6")
        codes.forEach { code ->
            val tag = "library-problem-putnam/2025/$code"
            composeRule.onNodeWithTag("problem-list").performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertExists()
        }
        composeRule.onNodeWithTag("problem-list").performScrollToNode(hasTestTag("library-problem-putnam/2025/A1"))
        composeRule.onNodeWithTag("library-problem-putnam/2025/A1").performClick()
        waitForTag("workspace-screen")
        waitForTag("math-content-ready")
    }

    private fun assertLandscapeSplitWorkspace() {
        val reference = composeRule.onNodeWithTag("workspace-reference-pane").fetchSemanticsNode().boundsInRoot
        val notebook = composeRule.onNodeWithTag("workspace-notebook-pane").fetchSemanticsNode().boundsInRoot
        assertTrue("Reference pane should be narrower than the notebook pane", reference.width < notebook.width)
        val ratio = reference.width / (reference.width + notebook.width)
        assertEquals("Reference pane should occupy about 40%", 0.4f, ratio, 0.08f)
    }

    private fun revealAllHintsAndSolution() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = targetContext.applicationContext as PutnamApplication
        val key = ProblemKey("putnam", "2025", "A1")
        runBlocking {
            repeat(4) { expected ->
                assertEquals(expected + 1, application.container.progressRepository.revealNextHint(key))
            }
            application.container.progressRepository.revealSolution(key)
            application.container.progressRepository.setNotebookState(
                key,
                pageCount = 1,
                pageIndex = 0,
                referenceTab = ReferenceTab.ORIGINAL_PAPER,
            )
        }

        composeRule.onNodeWithTag("workspace-home").performClick()
        waitForTag("home-screen")
        composeRule.onNodeWithTag("home-continue").performClick()
        waitForTag("workspace-screen")
        waitForTag("math-content-ready")
        assertLegacyReferenceWasNormalized()
        listOf("problem", "help", "prerequisites", "solution").forEach { tab ->
            composeRule.onNodeWithTag("reference-tab-$tab").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("reference-tab-help").performClick()
        repeat(4) { index ->
            waitForTag("help-hint-${index + 1}")
        }
        composeRule.onNodeWithTag("help-hint-1").performClick()
        waitForTag("math-content-ready")
        val hintHtml = targetContext.assets.open("content/putnam/2025/problems/A1/hint-1.html")
            .bufferedReader().use { it.readText() }
        assertTrue("Hint HTML should contain pre-rendered KaTeX", hintHtml.contains("class=\"katex\""))
        composeRule.onNodeWithTag("reference-tab-solution").assertIsEnabled()

        composeRule.onNodeWithTag("reference-tab-original_paper").assertDoesNotExist()
        composeRule.onNodeWithText("Original PDF").assertDoesNotExist()
        composeRule.onNodeWithText("View original").assertDoesNotExist()
        composeRule.onNodeWithTag("palm-guard").assertDoesNotExist()
        composeRule.onNodeWithText("Move left").assertDoesNotExist()
        composeRule.onNodeWithText("Move right").assertDoesNotExist()
    }

    private fun addNotebookPageAndMarkCompleted() {
        composeRule.onNodeWithContentDescription("Add page").performClick()
        waitForText("Page 2 of 2")
        composeRule.onNodeWithTag("workspace-completed").performClick()
        composeRule.onNodeWithTag("workspace-completed").assertTextEquals("Completed")
    }

    private fun createStrokeBackupAndPdf() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = targetContext.applicationContext as PutnamApplication
        val key = ProblemKey("putnam", "2025", "A1")

        runBlocking {
            val notebook = application.container.notebookRepository.loadExisting(key)
                ?: error("The workspace did not create a notebook")
            val activePage = notebook.pages.last()
            application.container.notebookRepository.commitStroke(
                key,
                activePage.id,
                StrokeRecord(
                    id = "acceptance-stroke-a",
                    brush = BrushSpec(colorArgb = 0xFF1D4ED8L, width = 8f),
                    samples = listOf(
                        StrokeSample(0.15f, 0.25f, 0L, null),
                        StrokeSample(0.35f, 0.35f, 10L, null),
                        StrokeSample(0.55f, 0.45f, 20L, null),
                    ),
                ),
            )
            application.container.notebookRepository.commitStroke(
                key,
                activePage.id,
                StrokeRecord(
                    id = "acceptance-stroke-b",
                    brush = BrushSpec(colorArgb = 0xFFB91C1CL, width = 5f),
                    samples = listOf(
                        StrokeSample(0.20f, 0.42f, 0L, 0.4f),
                        StrokeSample(0.28f, 0.18f, 12L, 0.8f),
                        StrokeSample(0.44f, 0.30f, 24L, 0.6f),
                    ),
                ),
            )
            val beforeProgress = application.container.progressRepository.observe(key).first()
            val beforeNotebook = application.container.notebookRepository.loadExisting(key)

            val backupBytes = ByteArrayOutputStream()
            assertTrue(application.container.backupCoordinator.create(backupBytes) is BackupResult.Success)
            application.container.progressRepository.markCompleted(key, false)
            assertTrue(
                application.container.backupCoordinator.validateAndRestore(
                    ByteArrayInputStream(backupBytes.toByteArray()),
                ) is RestoreResult.Success,
            )
            assertEquals(beforeProgress, application.container.progressRepository.observe(key).first())
            assertEquals(beforeNotebook, application.container.notebookRepository.loadExisting(key))

            lateinit var activity: MainActivity
            composeRule.activityRule.scenario.onActivity { activity = it }
            val pdf = ByteArrayOutputStream()
            val export = application.container.createExportCoordinator(
                activity,
                application.container.initializeContent(),
            ).export(
                key,
                WorkbookExportOptions(includeRevealedHints = true, includeRevealedSolution = true),
                pdf,
            )
            assertTrue("Workbook PDF export failed: $export", export is ExportResult.Success)
            val pdfFile = Files.createTempFile(targetContext.cacheDir.toPath(), "acceptance-", ".pdf").toFile()
            pdfFile.writeBytes(pdf.toByteArray())
            try {
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        assertTrue("Exported PDF should contain the reference and notebook pages", renderer.pageCount >= 3)
                    }
                }
            } finally {
                pdfFile.delete()
            }
        }
    }

    private fun assertLegacyReferenceWasNormalized() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = targetContext.applicationContext as PutnamApplication
        runBlocking {
            val progress = application.container.progressRepository.observe(ProblemKey("putnam", "2025", "A1")).first()
            assertEquals(ReferenceTab.PROBLEM, progress.activeReferenceTab)
            assertEquals(4, progress.revealedHintCount)
            assertTrue(progress.solutionRevealed)
        }
    }

    private fun exerciseSelectionEditingAndPageNavigation() {
        waitForRenderedStrokeCount(2)
        composeRule.onNodeWithTag(NEXT_PAGE_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(PREVIOUS_PAGE_TEST_TAG).assertIsEnabled().performClick()
        waitForText("Page 1 of 2")
        composeRule.onNodeWithTag(PREVIOUS_PAGE_TEST_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(NEXT_PAGE_TEST_TAG).assertIsEnabled().performClick()
        waitForText("Page 2 of 2")
        waitForRenderedStrokeCount(2)

        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).assertContentDescriptionEquals("Pen mode")
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).performClick()
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).assertContentDescriptionEquals("Select mode")

        val overlay = composeRule.onNodeWithTag(SELECTION_OVERLAY_TEST_TAG)
        val overlayBounds = overlay.fetchSemanticsNode().boundsInRoot
        val overlayWidth = overlayBounds.width
        val overlayHeight = overlayBounds.height
        assertTrue(overlayWidth > 0f && overlayHeight > 0f)
        overlay.performTouchInput {
            swipe(
                start = Offset(overlayWidth * 0.05f, overlayHeight * 0.05f),
                end = Offset(overlayWidth * 0.50f, overlayHeight * 0.50f),
                durationMillis = 500L,
            )
        }
        composeRule.onNodeWithTag(COPY_SELECTION_TEST_TAG).assertIsEnabled()
        val selectedOverlayBounds = overlay.fetchSemanticsNode().boundsInRoot
        val selectedOverlayWidth = selectedOverlayBounds.width
        val selectedOverlayHeight = selectedOverlayBounds.height

        overlay.performTouchInput {
            swipe(
                start = Offset(selectedOverlayWidth * 0.35f, selectedOverlayHeight * 0.35f),
                end = Offset(selectedOverlayWidth * 0.43f, selectedOverlayHeight * 0.43f),
                durationMillis = 400L,
            )
        }
        composeRule.onNodeWithTag(COPY_SELECTION_TEST_TAG).assertIsEnabled()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runBlocking {
                val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
                val application = targetContext.applicationContext as PutnamApplication
                val key = ProblemKey("putnam", "2025", "A1")
                val notebook = application.container.notebookRepository.loadExisting(key) ?: return@runBlocking false
                val stored = application.container.notebookRepository.loadStrokes(key, notebook.pages.last().id)
                stored.size == 2 && stored.none { it.id.startsWith("acceptance-stroke-") }
            }
        }
        composeRule.onNodeWithText("Save failed").assertDoesNotExist()
        val movedStrokes = runBlocking {
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            val application = targetContext.applicationContext as PutnamApplication
            val key = ProblemKey("putnam", "2025", "A1")
            val notebook = requireNotNull(application.container.notebookRepository.loadExisting(key))
            application.container.notebookRepository.loadStrokes(key, notebook.pages.last().id)
        }
        val movedBounds = requireNotNull(
            movedStrokes.selectionBounds(CanvasViewport(selectedOverlayWidth, selectedOverlayHeight)),
        )
        overlay.performTouchInput {
            swipe(
                start = Offset(selectedOverlayWidth * movedBounds.right, selectedOverlayHeight * movedBounds.bottom),
                end = Offset(
                    selectedOverlayWidth * (movedBounds.right + 0.10f),
                    selectedOverlayHeight * (movedBounds.bottom + 0.10f),
                ),
                durationMillis = 400L,
            )
        }
        composeRule.onNodeWithTag(COPY_SELECTION_TEST_TAG).assertIsEnabled()

        val scaledStrokes = waitForStoredReplacement(movedStrokes.mapTo(mutableSetOf()) { it.id })
        val scaledBounds = requireNotNull(
            scaledStrokes.selectionBounds(CanvasViewport(selectedOverlayWidth, selectedOverlayHeight)),
        )
        val stemPixels = with(composeRule.density) { 32.dp.toPx() }
        val centerX = selectedOverlayWidth * (scaledBounds.left + scaledBounds.right) / 2f
        val centerY = selectedOverlayHeight * (scaledBounds.top + scaledBounds.bottom) / 2f
        val rotationRadius = selectedOverlayHeight * scaledBounds.height / 2f + stemPixels
        overlay.performTouchInput {
            swipe(
                start = Offset(centerX, centerY - rotationRadius),
                end = Offset(centerX + rotationRadius, centerY),
                durationMillis = 500L,
            )
        }
        val rotatedStrokes = waitForStoredReplacement(scaledStrokes.mapTo(mutableSetOf()) { it.id })
        assertGeometryChanged(scaledStrokes, rotatedStrokes, "Free rotation must change stored geometry")
        composeRule.onNodeWithTag(SNAP_ROTATION_TEST_TAG).performClick().assertIsSelected()
        val rotatedBounds = requireNotNull(
            rotatedStrokes.selectionBounds(CanvasViewport(selectedOverlayWidth, selectedOverlayHeight)),
        )
        overlay.performTouchInput {
            swipe(
                start = Offset(
                    selectedOverlayWidth * rotatedBounds.right + stemPixels,
                    selectedOverlayHeight * (rotatedBounds.top + rotatedBounds.bottom) / 2f,
                ),
                end = Offset(
                    selectedOverlayWidth * (rotatedBounds.left + rotatedBounds.right) / 2f,
                    selectedOverlayHeight * rotatedBounds.bottom + stemPixels,
                ),
                durationMillis = 500L,
            )
        }
        val snappedStrokes = waitForStoredReplacement(rotatedStrokes.mapTo(mutableSetOf()) { it.id })
        assertGeometryChanged(rotatedStrokes, snappedStrokes, "Snapped rotation must change stored geometry")
        composeRule.onNodeWithTag(FLIP_HORIZONTAL_TEST_TAG).assertIsEnabled().performClick()
        val horizontalFlip = waitForStoredReplacement(snappedStrokes.mapTo(mutableSetOf()) { it.id })
        assertGeometryChanged(snappedStrokes, horizontalFlip, "Horizontal flip must change stored geometry")
        composeRule.onNodeWithTag(FLIP_VERTICAL_TEST_TAG).assertIsEnabled().performClick()
        val verticalFlip = waitForStoredReplacement(horizontalFlip.mapTo(mutableSetOf()) { it.id })
        assertGeometryChanged(horizontalFlip, verticalFlip, "Vertical flip must change stored geometry")

        composeRule.onNodeWithTag(UNDO_TEST_TAG).performClick()
        val undoVertical = waitForStoredReplacement(verticalFlip.mapTo(mutableSetOf()) { it.id })
        assertGeometryEquals(horizontalFlip, undoVertical, "Undo must revert only the vertical flip")
        composeRule.onNodeWithTag(UNDO_TEST_TAG).performClick()
        val undoHorizontal = waitForStoredReplacement(undoVertical.mapTo(mutableSetOf()) { it.id })
        assertGeometryEquals(snappedStrokes, undoHorizontal, "Undo must revert only the horizontal flip")
        composeRule.onNodeWithTag(REDO_TEST_TAG).performClick()
        val redoHorizontal = waitForStoredReplacement(undoHorizontal.mapTo(mutableSetOf()) { it.id })
        assertGeometryEquals(horizontalFlip, redoHorizontal, "Redo must restore only the horizontal flip")
        composeRule.onNodeWithTag(REDO_TEST_TAG).performClick()
        val redoVertical = waitForStoredReplacement(redoHorizontal.mapTo(mutableSetOf()) { it.id })
        assertGeometryEquals(verticalFlip, redoVertical, "Redo must restore only the vertical flip")
        expectedTransformedStrokes = redoVertical

        overlay.performTouchInput {
            swipe(
                start = Offset(selectedOverlayWidth * 0.01f, selectedOverlayHeight * 0.01f),
                end = Offset(selectedOverlayWidth * 0.99f, selectedOverlayHeight * 0.99f),
                durationMillis = 500L,
            )
        }
        composeRule.onNodeWithTag(COPY_SELECTION_TEST_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(COPY_SELECTION_TEST_TAG).performClick()
        composeRule.onNodeWithTag(PASTE_SELECTION_TEST_TAG).assertIsEnabled().performClick()
        waitForRenderedStrokeCount(4)
        composeRule.onNodeWithTag(UNDO_TEST_TAG).performClick()
        waitForRenderedStrokeCount(2)
        composeRule.onNodeWithTag(REDO_TEST_TAG).performClick()
        waitForRenderedStrokeCount(4)

        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).performClick()
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).assertContentDescriptionEquals("Pen mode")
        listOf(
            CUT_SELECTION_TEST_TAG,
            COPY_SELECTION_TEST_TAG,
            PASTE_SELECTION_TEST_TAG,
            DELETE_SELECTION_TEST_TAG,
        ).forEach { tag -> composeRule.onNodeWithTag(tag).assertDoesNotExist() }
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).performClick()
        composeRule.onNodeWithTag(TOOL_MODE_TOGGLE_TEST_TAG).assertContentDescriptionEquals("Select mode")
        composeRule.onNodeWithTag(PASTE_SELECTION_TEST_TAG).assertIsEnabled()
    }

    private fun verifyEditedNotebookSurvivesHomeAndContinue() {
        composeRule.onNodeWithTag("workspace-home").performClick()
        waitForTag("home-screen")
        composeRule.onNodeWithTag("home-continue").performClick()
        waitForTag("workspace-screen")
        waitForText("Page 2 of 2")
        waitForRenderedStrokeCount(4)

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = targetContext.applicationContext as PutnamApplication
        runBlocking {
            val key = ProblemKey("putnam", "2025", "A1")
            val notebook = requireNotNull(application.container.notebookRepository.loadExisting(key))
            val strokes = application.container.notebookRepository.loadStrokes(key, notebook.pages.last().id)
            assertEquals(4, strokes.size)
            assertEquals(4, strokes.map { it.id }.distinct().size)
            assertTrue(
                "Move/scale/rotate/flip should replace the original geometry",
                strokes.none { it.id.startsWith("acceptance-stroke-") },
            )
            val persistedTransforms = expectedTransformedStrokes.map { expected ->
                requireNotNull(strokes.singleOrNull { it.id == expected.id })
            }
            assertGeometryEquals(
                expectedTransformedStrokes,
                persistedTransforms,
                "Exact rotated and flipped geometry must survive Home and reopen",
            )
        }
    }

    private fun waitForRenderedStrokeCount(expected: Int) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG)
                .fetchSemanticsNode().config[RenderedStrokeCountKey] == expected
        }
    }

    private fun waitForStoredReplacement(previousIds: Set<String>): List<StrokeRecord> {
        var replacement = emptyList<StrokeRecord>()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            replacement = runBlocking {
                val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
                val application = targetContext.applicationContext as PutnamApplication
                val key = ProblemKey("putnam", "2025", "A1")
                val notebook = application.container.notebookRepository.loadExisting(key) ?: return@runBlocking emptyList()
                application.container.notebookRepository.loadStrokes(key, notebook.pages.last().id)
            }
            replacement.size == previousIds.size && replacement.mapTo(mutableSetOf()) { it.id } != previousIds
        }
        return replacement
    }

    private fun assertGeometryChanged(
        before: List<StrokeRecord>,
        after: List<StrokeRecord>,
        message: String,
    ) {
        assertTrue(message, before.map { it.samples } != after.map { it.samples })
    }

    private fun assertGeometryEquals(
        expected: List<StrokeRecord>,
        actual: List<StrokeRecord>,
        message: String,
    ) {
        assertEquals(message, expected.map { it.brush }, actual.map { it.brush })
        assertEquals(message, expected.map { it.samples }, actual.map { it.samples })
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
