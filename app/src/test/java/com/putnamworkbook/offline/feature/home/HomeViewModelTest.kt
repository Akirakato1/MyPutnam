package com.putnamworkbook.offline.feature.home

import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.LibraryCollection
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSetSummary
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.content.TopicSummary
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {
    private val key = ProblemKey("putnam", "2025", "A1")
    private val summary = ProblemSummary(key, "A1", 1, "Putnam", "2025", 2025)

    @Test
    fun recentProblemChangesBetweenUnavailableAndAvailableWhenInstallRevisionChanges() = runBlocking {
        val content = MutableHomeContent(summary)
        val progress = HomeProgress(ProblemProgress.untouched(key).copy(lastOpenedAt = 1L))
        val viewModel = HomeViewModel(progress, content, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        waitUntil { viewModel.uiState.value.recentProblem is RecentProblemState.Unavailable }
        assertEquals(key, (viewModel.uiState.value.recentProblem as RecentProblemState.Unavailable).key)

        content.setInstalled(true)

        waitUntil { viewModel.uiState.value.recentProblem is RecentProblemState.Available }
        assertEquals(summary, (viewModel.uiState.value.recentProblem as RecentProblemState.Available).problem)
        viewModel.close()
    }

    @Test
    fun noRecentProblemRemainsNone() = runBlocking {
        val viewModel = HomeViewModel(
            HomeProgress(null),
            MutableHomeContent(summary),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        waitUntil { viewModel.uiState.value.recentProblem == RecentProblemState.None }
        assertTrue(viewModel.uiState.value.recentProblem is RecentProblemState.None)
        viewModel.close()
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        repeat(500) {
            if (predicate()) return
            delay(5)
        }
        error("Timed out waiting for home state")
    }
}

private class MutableHomeContent(private val summary: ProblemSummary) : ContentRepository {
    private val revision = MutableStateFlow(0L)
    private var installed = false
    fun setInstalled(value: Boolean) { installed = value; revision.value += 1 }
    override fun observeInstalledRevision(): Flow<Long> = revision
    override suspend fun collections() = emptyList<LibraryCollection>()
    override suspend fun sets(collectionId: String) = emptyList<ProblemSetSummary>()
    override suspend fun topics() = emptyList<TopicSummary>()
    override suspend fun problems(setKey: ProblemSetKey) = emptyList<ProblemSummary>()
    override suspend fun problemsByTopic(topicId: String) = emptyList<ProblemSummary>()
    override suspend fun problem(key: ProblemKey): ProblemContent? = if (installed && key == summary.key) {
        ProblemContent(summary, "statement.html", "0".repeat(64), emptyList(), emptyList(), "solution.html", "1".repeat(64), emptyList())
    } else null
}

private class HomeProgress(initial: ProblemProgress?) : ProgressRepository {
    private val recent = MutableStateFlow(initial)
    override fun observe(key: ProblemKey): Flow<ProblemProgress> = MutableStateFlow(ProblemProgress.untouched(key))
    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = MutableStateFlow(emptyMap())
    override fun observeRecent(): Flow<ProblemProgress?> = recent
    override suspend fun markOpened(key: ProblemKey) = Unit
    override suspend fun revealNextHint(key: ProblemKey) = 0
    override suspend fun revealSolution(key: ProblemKey) = Unit
    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = Unit
    override suspend fun setNotebookState(key: ProblemKey, pageCount: Int, pageIndex: Int, referenceTab: ReferenceTab) = Unit
}
