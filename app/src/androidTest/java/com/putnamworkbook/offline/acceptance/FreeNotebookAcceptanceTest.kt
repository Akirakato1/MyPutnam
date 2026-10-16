package com.putnamworkbook.offline.acceptance

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import com.putnamworkbook.offline.MainActivity
import com.putnamworkbook.offline.PutnamApplication
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceResult
import com.putnamworkbook.offline.notebook.ui.NOTEBOOK_CANVAS_TEST_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class FreeNotebookAcceptanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun createDrawSaveRenameDuplicateAndDelete() {
        val suffix = System.currentTimeMillis().toString()
        val initialName = "Acceptance $suffix"
        val renamed = "$initialName renamed"
        val copyName = "$initialName copy"
        openLibrary()
        composeRule.onNodeWithTag("free-workspace-new").performClick()
        composeRule.onNodeWithTag("free-workspace-name-input").performTextInput(initialName)
        composeRule.onNodeWithTag("free-workspace-name-confirm").performClick()
        waitForTag("free-notebook-editor")
        composeRule.onNodeWithTag("workspace-reference-pane").assertDoesNotExist()
        waitForTag(NOTEBOOK_CANVAS_TEST_TAG)

        drawStroke(0.15f, 0.25f, 0.55f, 0.45f)
        drawStroke(0.25f, 0.65f, 0.65f, 0.35f)
        composeRule.onNodeWithTag("free-notebook-save").performClick()
        waitForTag("free-notebook-status")
        composeRule.onNodeWithTag("free-notebook-back").performClick()
        waitForTag("free-workspace-library")

        val application = application()
        val source = runBlocking {
            (application.container.freeWorkspaceRepository.list() as FreeWorkspaceResult.Success)
                .value.single { it.record.name == initialName }.record
        }
        composeRule.onNodeWithTag("free-workspace-rename-${source.id}").performClick()
        composeRule.onNodeWithTag("free-workspace-name-input").performTextReplacement(renamed)
        composeRule.onNodeWithTag("free-workspace-name-confirm").performClick()
        waitForText(renamed)
        composeRule.onNodeWithTag("free-workspace-row-${source.id}").performClick()
        waitForTag("free-notebook-editor")

        composeRule.onNodeWithTag("free-notebook-save-as").performClick()
        waitForTag("free-workspace-name-input")
        composeRule.onNodeWithTag("free-workspace-name-confirm").performClick()
        composeRule.onNodeWithText("A workspace with this name already exists").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-name-input").performTextReplacement(copyName)
        composeRule.onNodeWithTag("free-workspace-name-confirm").performClick()
        waitForText(copyName)
        waitForTag(NOTEBOOK_CANVAS_TEST_TAG)
        drawStroke(0.15f, 0.8f, 0.75f, 0.7f)
        composeRule.onNodeWithTag("free-notebook-save").performClick()
        waitForTag("free-notebook-status")
        composeRule.onNodeWithTag("free-notebook-back").performClick()
        waitForTag("free-workspace-library")

        val entries = runBlocking {
            (application.container.freeWorkspaceRepository.list() as FreeWorkspaceResult.Success).value
        }
        val sourceAfter = entries.single { it.record.name == renamed }.record
        val copy = entries.single { it.record.name == copyName }.record
        val counts = runBlocking {
            listOf(sourceAfter, copy).map { record ->
                val key = application.container.freeWorkspaceRepository.notebookKey(record.id)
                val document = application.container.notebookRepository.loadExisting(key)!!
                application.container.notebookRepository.loadStrokes(key, document.pages.single().id).size
            }
        }
        assertEquals(listOf(2, 3), counts)

        composeRule.onNodeWithTag("free-workspace-delete-${copy.id}").performClick()
        composeRule.onNodeWithText("Delete $copyName?").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-delete-confirm").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("free-workspace-row-${copy.id}").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun damagedWorkspaceCanBeRenamedAndDeletedButNotOpened() {
        val application = application()
        val created = runBlocking {
            (application.container.freeWorkspaceRepository.create(
                "Damaged acceptance ${System.currentTimeMillis()}",
                PaperStyle.BLANK,
            ) as FreeWorkspaceResult.Success).value
        }
        val directory = application.container.notebookRoot.resolve("__local__/free-workspaces/${created.id}")
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        openLibrary()

        composeRule.onNodeWithTag("free-workspace-damaged-${created.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("free-workspace-row-${created.id}").performClick()
        composeRule.onNodeWithTag("free-notebook-editor").assertDoesNotExist()
        composeRule.onNodeWithTag("free-workspace-rename-${created.id}").performClick()
        composeRule.onNodeWithTag("free-workspace-name-input").performTextReplacement("Recovered metadata ${created.id}")
        composeRule.onNodeWithTag("free-workspace-name-confirm").performClick()
        composeRule.onNodeWithTag("free-workspace-delete-${created.id}").performClick()
        composeRule.onNodeWithTag("free-workspace-delete-confirm").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("free-workspace-row-${created.id}"))
                .fetchSemanticsNodes().isEmpty()
        }
        assertTrue(runBlocking {
            (application.container.freeWorkspaceRepository.list() as FreeWorkspaceResult.Success)
                .value.none { it.record.id == created.id }
        })
    }

    private fun openLibrary() {
        waitForTag("home-screen")
        composeRule.onNodeWithTag("home-free-notebook").performClick()
        waitForTag("free-workspace-library")
    }

    private fun drawStroke(x1: Float, y1: Float, x2: Float, y2: Float) {
        val canvas = composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG)
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        canvas.performTouchInput {
            swipe(
                Offset(bounds.width * x1, bounds.height * y1),
                Offset(bounds.width * x2, bounds.height * y2),
                durationMillis = 120,
            )
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun application(): PutnamApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PutnamApplication
}
