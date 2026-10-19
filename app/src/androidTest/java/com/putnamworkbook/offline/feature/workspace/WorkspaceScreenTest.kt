package com.putnamworkbook.offline.feature.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.content.ContentDocument
import com.putnamworkbook.offline.data.content.HintContent
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.domain.model.HintStage
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab
import com.putnamworkbook.offline.export.WorkbookExportOptions
import com.putnamworkbook.offline.content.ui.LocalContentClientFactoryProvider
import androidx.test.platform.app.InstrumentationRegistry
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
    fun hintControlsStayAboveTheDocumentBodyAcrossAssetChangesAndIntegrityFailure() {
        var state by mutableStateOf(referenceState(ReferenceTab.HELP))
        val integrityFailed = AtomicBoolean(false)
        composeRule.setContent {
            MaterialTheme {
                ReferencePane(
                    state = state,
                    onSelectTab = { state = state.copy(selectedReferenceTab = it) },
                    onRevealNextStage = {},
                    onSelectHint = { state = state.copy(activeHintIndex = it) },
                    onSelectPrerequisite = {},
                    onIntegrityError = { integrityFailed.set(true) },
                    modifier = Modifier.width(650.dp).height(500.dp),
                )
            }
        }

        val controls = composeRule.onNodeWithTag("help-controls").fetchSemanticsNode().boundsInRoot
        val body = composeRule.onNodeWithTag("reference-document-body").fetchSemanticsNode().boundsInRoot
        assertTrue("Hint controls must end above the document body", controls.bottom <= body.top + 1f)
        (1..4).forEach { composeRule.onNodeWithTag("help-hint-$it").assertExists() }

        composeRule.onNodeWithTag("help-hint-2").performClick()
        composeRule.waitForIdle()
        (1..4).forEach { composeRule.onNodeWithTag("help-hint-$it").assertExists() }

        state = state.copy(
            activeHintIndex = 0,
            content = state.content!!.copy(
                hints = state.content!!.hints.mapIndexed { index, hint ->
                    if (index == 0) hint.copy(resource = hint.resource.copy(memberPath = "content/putnam/2025/problems/A1/missing-hint.html")) else hint
                },
            ),
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { integrityFailed.get() }
        composeRule.onNodeWithTag("reference-tabs").assertExists()
        composeRule.onNodeWithTag("help-controls").assertExists()
    }

    @Test
    fun everyPrerequisiteHasAStableSelectorAboveTheDocumentBody() {
        var state by mutableStateOf(referenceState(ReferenceTab.PREREQUISITES))
        composeRule.setContent {
            MaterialTheme {
                ReferencePane(
                    state = state,
                    onSelectTab = { state = state.copy(selectedReferenceTab = it) },
                    onRevealNextStage = {},
                    onSelectHint = {},
                    onSelectPrerequisite = { state = state.copy(activePrerequisiteIndex = it) },
                    onIntegrityError = {},
                    modifier = Modifier.width(650.dp).height(500.dp),
                )
            }
        }

        composeRule.onNodeWithTag("prerequisite-1").assertExists()
        composeRule.onNodeWithTag("prerequisite-2").assertExists().performClick()
        composeRule.onNodeWithTag("prerequisite-1").assertExists()
        val controls = composeRule.onNodeWithTag("prerequisite-controls").fetchSemanticsNode().boundsInRoot
        val body = composeRule.onNodeWithTag("reference-document-body").fetchSemanticsNode().boundsInRoot
        assertTrue("Prerequisite controls must end above the document body", controls.bottom <= body.top + 1f)
    }

    @Test
    fun fixedReferenceHeadersSurviveDelayedLoadsScrollingFailuresTabsAndRendererRecreation() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        ReferenceRendererFixture.requireRepresentativeKatex(targetContext)
        val clientFactory = ControlledLocalContentClientFactory()
        var state by mutableStateOf(ReferenceRendererFixture.state(ReferenceTab.HELP))
        var showPane by mutableStateOf(true)
        val integrityErrors = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalContentClientFactoryProvider provides clientFactory) {
                if (showPane) {
                    MaterialTheme {
                        ReferencePane(
                            state = state,
                            onSelectTab = { state = state.copy(selectedReferenceTab = it) },
                            onRevealNextStage = {},
                            onSelectHint = { state = state.copy(activeHintIndex = it) },
                            onSelectPrerequisite = { state = state.copy(activePrerequisiteIndex = it) },
                            onIntegrityError = integrityErrors::add,
                            modifier = Modifier.width(650.dp).height(420.dp),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("help-controls").assertExists()
        composeRule.onNodeWithTag("math-content-ready").assertDoesNotExist()
        composeRule.runOnIdle(clientFactory::releaseLoads)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("math-content-ready").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("reference-document-body").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("help-controls").assertExists()

        composeRule.runOnIdle(clientFactory::holdNextLoads)
        composeRule.onNodeWithTag("help-hint-2").performClick()
        composeRule.onNodeWithTag("help-controls").assertExists()
        composeRule.runOnIdle(clientFactory::releaseLoads)
        composeRule.onNodeWithTag("reference-tab-prerequisites").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("math-content-ready").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("prerequisite-controls").assertExists()

        listOf(ReferenceTab.PROBLEM, ReferenceTab.SOLUTION).forEach { tab ->
            composeRule.runOnIdle { state = state.copy(selectedReferenceTab = tab) }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("math-content-ready").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("reference-tabs").assertExists()
        }

        composeRule.runOnIdle(clientFactory::simulateRendererLoss)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("math-content-ready").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("reference-tabs").assertExists()

        composeRule.runOnIdle { showPane = false }
        composeRule.onNodeWithTag("reference-tabs").assertDoesNotExist()
        composeRule.runOnIdle { showPane = true }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("math-content-ready").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("reference-tabs").assertExists()

        composeRule.runOnIdle {
            state = ReferenceRendererFixture.state(ReferenceTab.HELP).let { fixture ->
                fixture.copy(
                    content = fixture.content!!.copy(
                        hints = fixture.content!!.hints.mapIndexed { index, hint ->
                            if (index == 0) hint.copy(resource = hint.resource.copy(memberPath = "content/putnam/2025/problems/A1/missing.html")) else hint
                        },
                    ),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { integrityErrors.isNotEmpty() }
        composeRule.onNodeWithTag("help-controls").assertExists()
    }

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
        val key = ProblemKey("putnam", "2025", "A1")
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

    private fun referenceState(tab: ReferenceTab): WorkspaceUiState {
        val key = ProblemKey("putnam", "2025", "A1")
        val hints = (1..4).map { index ->
            HintContent(
                order = index,
                stage = HintStage.entries[index - 1],
                title = "Hint $index",
                assetPath = "content/putnam/2025/problems/A1/hint-$index.html",
                sha256 = index.toString().repeat(64),
            )
        }
        val prerequisites = listOf(
            ContentDocument(
                1,
                "First theorem",
                "content/putnam/2025/problems/A1/prerequisite-1.html",
                "a".repeat(64),
            ),
            ContentDocument(
                2,
                "Second theorem",
                "content/putnam/2025/problems/A1/prerequisite-2.html",
                "b".repeat(64),
            ),
        )
        return WorkspaceUiState(
            key = key,
            content = ProblemContent(
                summary = ProblemSummary(key, "A1", 1, "Putnam Competition", "2025", 2025),
                statementAssetPath = "content/putnam/2025/problems/A1/statement.html",
                statementSha256 = "0".repeat(64),
                hints = hints,
                prerequisites = prerequisites,
                solutionAssetPath = "content/putnam/2025/problems/A1/solution.html",
                solutionSha256 = "c".repeat(64),
                attribution = emptyList(),
            ),
            progress = ProblemProgress(
                key = key,
                status = ProblemStatus.IN_PROGRESS,
                revealedHintCount = 4,
                solutionRevealed = false,
                notebookPageCount = 1,
                activePageIndex = 0,
                activeReferenceTab = tab,
                lastOpenedAt = 1L,
            ),
            selectedReferenceTab = tab,
            activeHintIndex = 0,
            activePrerequisiteIndex = 0,
        )
    }
}
