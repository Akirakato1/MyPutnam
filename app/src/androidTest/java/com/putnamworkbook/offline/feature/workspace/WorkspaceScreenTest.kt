package com.putnamworkbook.offline.feature.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab
import com.putnamworkbook.offline.export.WorkbookExportOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkspaceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun wideWorkspaceUsesFixedFortySixtySplit() {
        composeRule.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    state = testState(),
                    onHome = {},
                    onSetCompleted = {},
                    referenceContent = { Box(Modifier.fillMaxSize().testTag("reference-content")) },
                    notebookContent = { Box(Modifier.fillMaxSize().testTag("notebook-content")) },
                    modifier = Modifier.width(1000.dp).height(600.dp),
                )
            }
        }

        val reference = composeRule.onNodeWithTag("workspace-reference-pane").fetchSemanticsNode().boundsInRoot
        val notebook = composeRule.onNodeWithTag("workspace-notebook-pane").fetchSemanticsNode().boundsInRoot
        val contentWidth = reference.width + notebook.width
        assertTrue("Reference pane should occupy 40%", abs(reference.width / contentWidth - 0.4f) < 0.02f)
        assertTrue("Notebook pane should occupy 60%", abs(notebook.width / contentWidth - 0.6f) < 0.02f)
    }

    @Test
    fun compactWorkspaceUsesExplicitPaneToggleAndHomeAction() {
        val homePressed = AtomicBoolean(false)
        composeRule.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    state = testState(),
                    onHome = { homePressed.set(true) },
                    onSetCompleted = {},
                    referenceContent = { Box(Modifier.fillMaxSize()) },
                    notebookContent = { Box(Modifier.fillMaxSize()) },
                    modifier = Modifier.width(700.dp).height(600.dp),
                )
            }
        }

        composeRule.onNodeWithTag("workspace-reference-pane").assertExists()
        composeRule.onNodeWithTag("workspace-notebook-pane").assertDoesNotExist()
        composeRule.onNodeWithTag("workspace-show-notebook").performClick()
        composeRule.onNodeWithTag("workspace-notebook-pane").assertExists()
        composeRule.onNodeWithTag("workspace-reference-pane").assertDoesNotExist()
        composeRule.onNodeWithTag("workspace-home").performClick()
        assertTrue(homePressed.get())
    }

    @Test
    fun exportRequiresConfirmingTheRevealedHelpOptions() {
        var exportedOptions: WorkbookExportOptions? = null
        composeRule.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    state = testState(revealedHints = 2, solutionRevealed = true),
                    onHome = {},
                    onSetCompleted = {},
                    onExportPdf = { exportedOptions = it },
                    referenceContent = { Box(Modifier.fillMaxSize()) },
                    notebookContent = { Box(Modifier.fillMaxSize()) },
                    modifier = Modifier.width(1000.dp).height(600.dp),
                )
            }
        }

        composeRule.onNodeWithTag("workspace-export-pdf").performClick()
        assertEquals(null, exportedOptions)
        composeRule.onNodeWithTag("workspace-export-options").assertExists()
        composeRule.onNodeWithTag("workspace-export-hints").performClick()
        composeRule.onNodeWithTag("workspace-export-confirm").performClick()
        assertEquals(
            WorkbookExportOptions(includeRevealedHints = false, includeRevealedSolution = true),
            exportedOptions,
        )
        composeRule.onNodeWithTag("workspace-export-options").assertDoesNotExist()
    }

    @Test
    fun exportDialogAndSelectionsSurviveSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        var exportedOptions: WorkbookExportOptions? = null
        restorationTester.setContent {
            MaterialTheme {
                WorkspaceScreen(
                    state = testState(revealedHints = 2, solutionRevealed = true),
                    onHome = {},
                    onSetCompleted = {},
                    onExportPdf = { exportedOptions = it },
                    referenceContent = { Box(Modifier.fillMaxSize()) },
                    notebookContent = { Box(Modifier.fillMaxSize()) },
                    modifier = Modifier.width(1000.dp).height(600.dp),
                )
            }
        }

        composeRule.onNodeWithTag("workspace-export-pdf").performClick()
        composeRule.onNodeWithTag("workspace-export-hints").performClick()
        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("workspace-export-options").assertExists()
        composeRule.onNodeWithTag("workspace-export-confirm").performClick()
        assertEquals(
            WorkbookExportOptions(includeRevealedHints = false, includeRevealedSolution = true),
            exportedOptions,
        )
    }

    private fun testState(
        revealedHints: Int = 0,
        solutionRevealed: Boolean = false,
    ): WorkspaceUiState {
        val key = ProblemKey(2025, "A1")
        return WorkspaceUiState(
            key = key,
            progress = ProblemProgress(
                key = key,
                status = ProblemStatus.IN_PROGRESS,
                revealedHintCount = revealedHints,
                solutionRevealed = solutionRevealed,
                notebookPageCount = 1,
                activePageIndex = 0,
                activeReferenceTab = ReferenceTab.PROBLEM,
                lastOpenedAt = 1L,
            ),
        )
    }
}
