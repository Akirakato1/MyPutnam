package com.putnamworkbook.offline.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso.pressBack
import com.putnamworkbook.offline.data.content.Attribution
import com.putnamworkbook.offline.data.content.ContentDocument
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.OriginalPdf
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ArchiveNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun yearFirstArchiveShowsProgressAndSystemBackFollowsTheHierarchy() {
        val content = FakeContentRepository()
        val progress = FakeProgressRepository(
            mapOf(
                ProblemKey(2025, "A2") to progress(2025, "A2", ProblemStatus.IN_PROGRESS, hints = 2, pages = 3),
                ProblemKey(2025, "B6") to progress(2025, "B6", ProblemStatus.COMPLETED, hints = 4, pages = 5),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                PutnamNavHost(contentRepository = content, progressRepository = progress)
            }
        }

        composeRule.onNodeWithTag("home-screen").assertExists()
        composeRule.onNodeWithTag("home-continue").assertIsNotEnabled()
        composeRule.onNodeWithTag("home-select-problem").assertIsEnabled().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("archive-year-2025").fetchSemanticsNodes().isNotEmpty()
        }
        val newest = composeRule.onNodeWithTag("archive-year-2025").fetchSemanticsNode().boundsInRoot
        val previous = composeRule.onNodeWithTag("archive-year-2024").fetchSemanticsNode().boundsInRoot
        assertTrue("Years should be displayed newest first", newest.top < previous.top)

        composeRule.onNodeWithTag("archive-year-2025").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("problem-position-0-A1").fetchSemanticsNodes().isNotEmpty()
        }

        val codes = listOf("A1", "A2", "A3", "A4", "A5", "A6", "B1", "B2", "B3", "B4", "B5", "B6")
        codes.forEachIndexed { index, code ->
            composeRule.onNodeWithTag("problem-list").performScrollToNode(hasTestTag("problem-position-$index-$code"))
            composeRule.onNodeWithTag("problem-position-$index-$code").assertExists()
        }
        composeRule.onNodeWithTag("problem-list").performScrollToNode(hasTestTag("problem-position-0-A1"))
        composeRule.onNodeWithTag("problem-status-A1", useUnmergedTree = true).assertTextEquals("Untouched")
        composeRule.onNodeWithTag("problem-list").performScrollToNode(hasTestTag("problem-position-1-A2"))
        composeRule.onNodeWithTag("problem-status-A2", useUnmergedTree = true).assertTextEquals("In progress")
        composeRule.onNodeWithTag("problem-hints-A2", useUnmergedTree = true).assertTextEquals("2 hints revealed")
        composeRule.onNodeWithTag("problem-pages-A2", useUnmergedTree = true).assertTextEquals("3 notebook pages")
        composeRule.onNodeWithTag("problem-list").performScrollToNode(hasTestTag("problem-position-11-B6"))
        composeRule.onNodeWithTag("problem-status-B6", useUnmergedTree = true).assertTextEquals("Completed")

        composeRule.onNodeWithTag("problem-list").performScrollToNode(hasTestTag("problem-position-0-A1"))
        composeRule.onNodeWithTag("problem-position-0-A1").performClick()
        composeRule.onNodeWithTag("workspace-placeholder-2025-A1").assertExists()

        pressBack()
        composeRule.onNodeWithTag("problem-list-screen").assertExists()
        pressBack()
        composeRule.onNodeWithTag("archive-years-screen").assertExists()
        pressBack()
        composeRule.onNodeWithTag("home-screen").assertExists()
        composeRule.onNodeWithTag("home-settings").assertExists()
    }

    private fun progress(
        year: Int,
        code: String,
        status: ProblemStatus,
        hints: Int,
        pages: Int,
    ) = ProblemProgress(
        key = ProblemKey(year, code),
        status = status,
        revealedHintCount = hints,
        solutionRevealed = status == ProblemStatus.COMPLETED,
        notebookPageCount = pages,
        activePageIndex = 0,
        activeReferenceTab = ReferenceTab.PROBLEM,
        lastOpenedAt = null,
    )
}

private class FakeContentRepository : ContentRepository {
    private val summaries = listOf("A1", "A2", "A3", "A4", "A5", "A6", "B1", "B2", "B3", "B4", "B5", "B6")
        .mapIndexed { index, code -> ProblemSummary(ProblemKey(2025, code), ordinal = index + 1) }

    override suspend fun years(): List<Int> = listOf(1938, 2024, 2025)

    override suspend fun problems(year: Int): List<ProblemSummary> = if (year == 2025) summaries else emptyList()

    override suspend fun problem(key: ProblemKey): ProblemContent? = summaries.firstOrNull { it.key == key }?.let { summary ->
        ProblemContent(
            summary = summary,
            statementAssetPath = "content/${key.year}/${key.code}/statement.html",
            statementSha256 = "0".repeat(64),
            hints = emptyList<ContentDocument>(),
            prerequisites = emptyList<ContentDocument>(),
            solutionAssetPath = "content/${key.year}/${key.code}/solution.html",
            solutionSha256 = "0".repeat(64),
            originalPdf = OriginalPdf("pdf/${key.year}.pdf", "0".repeat(64), 0),
            attribution = emptyList<Attribution>(),
        )
    }
}

private class FakeProgressRepository(initial: Map<ProblemKey, ProblemProgress>) : ProgressRepository {
    private val all = MutableStateFlow(initial)

    override fun observe(key: ProblemKey): Flow<ProblemProgress> =
        MutableStateFlow(all.value[key] ?: ProblemProgress.untouched(key))

    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = all

    override fun observeRecent(): Flow<ProblemProgress?> = MutableStateFlow(null)

    override suspend fun markOpened(key: ProblemKey) = Unit
    override suspend fun revealNextHint(key: ProblemKey): Int = 0
    override suspend fun revealSolution(key: ProblemKey) = Unit
    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = Unit
    override suspend fun setNotebookState(
        key: ProblemKey,
        pageCount: Int,
        pageIndex: Int,
        referenceTab: ReferenceTab,
    ) = Unit
}
