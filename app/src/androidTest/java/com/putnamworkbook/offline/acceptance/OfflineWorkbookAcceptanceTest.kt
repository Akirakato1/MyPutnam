package com.putnamworkbook.offline.acceptance

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.putnamworkbook.offline.MainActivity
import com.putnamworkbook.offline.PutnamApplication
import com.putnamworkbook.offline.backup.BackupResult
import com.putnamworkbook.offline.backup.RestoreResult
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.export.ExportResult
import com.putnamworkbook.offline.export.WorkbookExportOptions
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
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
        composeRule.onNodeWithText("Page 2").assertExists()
    }

    private fun openA1FromTheArchive() {
        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("home-select-problem").performClick()
        waitForTag("archive-year-2025")
        composeRule.onNodeWithTag("archive-year-2025").performClick()
        waitForTag("problem-list")

        val codes = listOf("A1", "A2", "A3", "A4", "A5", "A6", "B1", "B2", "B3", "B4", "B5", "B6")
        codes.forEachIndexed { index, code ->
            composeRule.onNodeWithTag("problem-list").performScrollToNode(hasTestTag("problem-position-$index-$code"))
            composeRule.onNodeWithTag("problem-position-$index-$code").assertExists()
        }
        composeRule.onNodeWithTag("problem-list").performScrollToNode(hasTestTag("problem-position-0-A1"))
        composeRule.onNodeWithTag("problem-position-0-A1").performClick()
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
        val key = ProblemKey(2025, "A1")
        runBlocking {
            repeat(4) { expected ->
                assertEquals(expected + 1, application.container.progressRepository.revealNextHint(key))
            }
            application.container.progressRepository.revealSolution(key)
        }

        composeRule.onNodeWithTag("workspace-home").performClick()
        waitForTag("home-screen")
        composeRule.onNodeWithTag("home-continue").performClick()
        waitForTag("workspace-screen")
        waitForTag("math-content-ready")
        composeRule.onNodeWithTag("reference-tab-help").performClick()
        repeat(4) { index ->
            waitForTag("help-hint-${index + 1}")
        }
        composeRule.onNodeWithTag("reference-tab-solution").assertIsEnabled()

        composeRule.onNodeWithTag("reference-tab-original_paper").performClick()
        waitForText("Page 1 of 2")
    }

    private fun addNotebookPageAndMarkCompleted() {
        composeRule.onNodeWithText("Add page").performClick()
        waitForText("Page 2")
        composeRule.onNodeWithTag("workspace-completed").performClick()
        composeRule.onNodeWithTag("workspace-completed").assertTextEquals("Completed")
    }

    private fun createStrokeBackupAndPdf() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = targetContext.applicationContext as PutnamApplication
        val key = ProblemKey(2025, "A1")

        runBlocking {
            val notebook = application.container.notebookRepository.loadExisting(key)
                ?: error("The workspace did not create a notebook")
            val firstPage = notebook.pages.first()
            application.container.notebookRepository.commitStroke(
                key,
                firstPage.id,
                StrokeRecord(
                    id = "acceptance-stroke",
                    brush = BrushSpec(colorArgb = 0xFF1D4ED8L, width = 8f),
                    samples = listOf(
                        StrokeSample(0.1f, 0.1f, 0L, null),
                        StrokeSample(0.5f, 0.5f, 10L, null),
                        StrokeSample(0.9f, 0.9f, 20L, null),
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
            val export = application.container.createExportCoordinator(activity).export(
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
