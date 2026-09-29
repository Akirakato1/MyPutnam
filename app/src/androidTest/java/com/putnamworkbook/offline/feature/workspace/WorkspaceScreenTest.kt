package com.putnamworkbook.offline.feature.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.ProblemKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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
                    state = WorkspaceUiState(key = ProblemKey(2025, "A1")),
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
                    state = WorkspaceUiState(key = ProblemKey(2025, "A1")),
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
}
