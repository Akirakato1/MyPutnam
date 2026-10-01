package com.putnamworkbook.offline.feature.workspace

import com.putnamworkbook.offline.data.content.Attribution
import com.putnamworkbook.offline.data.content.ContentDocument
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.HintContent
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.HintStage
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceViewModelTest {
    private val key = ProblemKey(2025, "A1")

    @Test
    fun everyPrerequisiteCanBeSelected() = runBlocking {
        val progress = FakeWorkspaceProgressRepository()
        val viewModel = viewModel(progress)
        viewModel.open(key)
        viewModel.awaitIdle()

        assertEquals(0, viewModel.uiState.value.activePrerequisiteIndex)
        viewModel.selectPrerequisite(1)
        viewModel.awaitIdle()

        assertEquals(1, viewModel.uiState.value.activePrerequisiteIndex)
        viewModel.close()
    }

    @Test
    fun visibleReferenceTabsExcludeOriginalPaper() {
        assertEquals(
            listOf(
                ReferenceTab.PROBLEM,
                ReferenceTab.HELP,
                ReferenceTab.PREREQUISITES,
                ReferenceTab.SOLUTION,
            ),
            ReferenceTab.visibleEntries,
        )
    }

    @Test
    fun legacyOriginalPaperProgressOpensAndPersistsProblemTab() = runBlocking {
        val legacy = ProblemProgress.untouched(key).copy(
            status = ProblemStatus.IN_PROGRESS,
            activeReferenceTab = ReferenceTab.ORIGINAL_PAPER,
        )
        val progress = FakeWorkspaceProgressRepository(mapOf(key to legacy))
        val viewModel = viewModel(progress)

        viewModel.open(key)
        viewModel.awaitIdle()

        assertEquals(ReferenceTab.PROBLEM, viewModel.uiState.value.selectedReferenceTab)
        assertEquals(ReferenceTab.PROBLEM, progress.observe(key).first().activeReferenceTab)
        viewModel.close()
    }

    @Test
    fun hintsRevealInOrderThenSolutionIsASeparateFifthRevealAndPersists() = runBlocking {
        val progress = FakeWorkspaceProgressRepository()
        var viewModel = viewModel(progress)

        viewModel.open(key)
        viewModel.awaitIdle()
        assertEquals(ProblemStatus.IN_PROGRESS, viewModel.uiState.value.progress!!.status)

        repeat(4) { index ->
            assertEquals(RevealStage.HINT, viewModel.uiState.value.nextReveal)
            viewModel.revealNextStage()
            viewModel.awaitIdle()
            assertEquals(index + 1, viewModel.uiState.value.progress!!.revealedHintCount)
            assertEquals(index, viewModel.uiState.value.activeHintIndex)
            assertFalse(viewModel.uiState.value.progress!!.solutionRevealed)
        }

        assertEquals(RevealStage.SOLUTION, viewModel.uiState.value.nextReveal)
        viewModel.selectHint(0)
        viewModel.awaitIdle()
        assertEquals(0, viewModel.uiState.value.activeHintIndex)
        viewModel.revealNextStage()
        viewModel.awaitIdle()
        assertTrue(viewModel.uiState.value.progress!!.solutionRevealed)
        assertEquals(ReferenceTab.SOLUTION, viewModel.uiState.value.selectedReferenceTab)
        assertEquals(RevealStage.NONE, viewModel.uiState.value.nextReveal)
        viewModel.close()

        viewModel = viewModel(progress)
        viewModel.open(key)
        viewModel.awaitIdle()
        assertEquals(4, viewModel.uiState.value.progress!!.revealedHintCount)
        assertTrue(viewModel.uiState.value.progress!!.solutionRevealed)
        viewModel.close()
    }

    @Test
    fun completionAndExactNotebookPageAndTabArePersistedForContinue() = runBlocking {
        val progress = FakeWorkspaceProgressRepository()
        val viewModel = viewModel(progress)
        viewModel.open(key)
        viewModel.awaitIdle()

        viewModel.updateNotebookState(pageCount = 3, pageIndex = 2)
        viewModel.awaitIdle()
        viewModel.selectReferenceTab(ReferenceTab.PREREQUISITES)
        viewModel.awaitIdle()
        viewModel.setCompleted(true)
        viewModel.awaitIdle()

        val recent = progress.observeRecent().value()
        assertEquals(key, recent!!.key)
        assertEquals(3, recent.notebookPageCount)
        assertEquals(2, recent.activePageIndex)
        assertEquals(ReferenceTab.PREREQUISITES, recent.activeReferenceTab)
        assertEquals(ProblemStatus.COMPLETED, recent.status)
        assertEquals(recent, progress.observeAll().value()[key])
        viewModel.close()

        val restored = viewModel(progress)
        restored.open(key)
        restored.awaitIdle()
        assertEquals(2, restored.uiState.value.progress!!.activePageIndex)
        assertEquals(ReferenceTab.PREREQUISITES, restored.uiState.value.selectedReferenceTab)
        restored.close()
    }

    private fun viewModel(progress: ProgressRepository) = WorkspaceViewModel(
        contentRepository = FakeWorkspaceContentRepository(key),
        progressRepository = progress,
        scope = CoroutineScope(Dispatchers.Default),
    )

    private suspend fun <T> Flow<T>.value(): T = first()
}

private class FakeWorkspaceContentRepository(
    private val key: ProblemKey,
) : ContentRepository {
    override suspend fun years() = listOf(key.year)
    override suspend fun problems(year: Int) = listOf(ProblemSummary(key, 1))
    override suspend fun problem(key: ProblemKey): ProblemContent = ProblemContent(
        summary = ProblemSummary(key, 1),
        statementAssetPath = "statement.html",
        statementSha256 = "0".repeat(64),
        hints = (1..4).map { index ->
            HintContent(
                index,
                HintStage.entries[index - 1],
                "Hint $index",
                "hint-$index.html",
                index.toString().repeat(64),
            )
        },
        prerequisites = listOf(
            ContentDocument(1, "Useful theorem", "theorem.html", "a".repeat(64)),
            ContentDocument(2, "Second theorem", "theorem-2.html", "d".repeat(64)),
        ),
        solutionAssetPath = "solution.html",
        solutionSha256 = "b".repeat(64),
        attribution = emptyList<Attribution>(),
    )
}

private class FakeWorkspaceProgressRepository(
    initialRows: Map<ProblemKey, ProblemProgress> = emptyMap(),
) : ProgressRepository {
    private val rows = MutableStateFlow(initialRows)

    override fun observe(key: ProblemKey): Flow<ProblemProgress> =
        rows.map { it[key] ?: ProblemProgress.untouched(key) }

    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = rows
    override fun observeRecent(): Flow<ProblemProgress?> = rows.map { values -> values.values.maxByOrNull { it.lastOpenedAt ?: 0L } }

    override suspend fun markOpened(key: ProblemKey) = mutate(key) { current ->
        current.copy(status = if (current.status == ProblemStatus.UNTOUCHED) ProblemStatus.IN_PROGRESS else current.status)
    }

    override suspend fun revealNextHint(key: ProblemKey): Int {
        var count = 0
        mutate(key) { current ->
            count = (current.revealedHintCount + 1).coerceAtMost(4)
            current.copy(revealedHintCount = count)
        }
        return count
    }

    override suspend fun revealSolution(key: ProblemKey) = mutate(key) { it.copy(solutionRevealed = true) }

    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = mutate(key) {
        it.copy(status = if (completed) ProblemStatus.COMPLETED else ProblemStatus.IN_PROGRESS)
    }

    override suspend fun setNotebookState(
        key: ProblemKey,
        pageCount: Int,
        pageIndex: Int,
        referenceTab: ReferenceTab,
    ) = mutate(key) {
        it.copy(
            notebookPageCount = pageCount,
            activePageIndex = pageIndex,
            activeReferenceTab = referenceTab,
        )
    }

    private fun mutate(key: ProblemKey, transform: (ProblemProgress) -> ProblemProgress) {
        val current = rows.value[key] ?: ProblemProgress.untouched(key)
        rows.value = rows.value + (key to transform(current).copy(lastOpenedAt = System.nanoTime()))
    }
}
