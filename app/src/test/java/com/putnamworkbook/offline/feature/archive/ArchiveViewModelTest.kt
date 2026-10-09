package com.putnamworkbook.offline.feature.archive

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveViewModelTest {
    private val putnamSet = ProblemSetSummary(ProblemSetKey("putnam", "2025"), "2025", 0, 2025)
    private val courseSet = ProblemSetSummary(ProblemSetKey("course", "unit"), "Unit One", 0, null)
    private val putnamA1 = summary(ProblemKey("putnam", "2025", "A1"), "A1", "Putnam", "2025")
    private val courseA1 = summary(ProblemKey("course", "unit", "A1"), "A1", "Course", "Unit One")

    @Test
    fun allCatalogAndTopicResultsPreserveGenericKeysAndProgress() = runBlocking {
        val progress = FakeArchiveProgress()
        val viewModel = ArchiveViewModel(
            contentRepository = FakeArchiveContent(
                setProblems = mapOf(putnamSet.key to listOf(putnamA1), courseSet.key to listOf(courseA1)),
                topicProblems = mapOf("algebra" to listOf(putnamA1, courseA1)),
            ),
            progressRepository = progress,
            scope = testScope(),
        )
        waitUntil("catalog") { !viewModel.uiState.value.isLoadingCatalog }
        assertEquals(listOf("putnam", "course"), viewModel.uiState.value.collections.map { it.id })
        assertEquals(listOf("2025"), viewModel.uiState.value.setsByCollection.getValue("putnam").map { it.key.setId })

        viewModel.selectTopic("algebra")
        waitUntil("topic") { !viewModel.uiState.value.isLoadingTopicProblems }
        assertEquals(listOf(putnamA1.key, courseA1.key), viewModel.uiState.value.topicProblems.map { it.summary.key })

        viewModel.selectSet(courseSet.key)
        waitUntil("set") { !viewModel.uiState.value.isLoadingSetProblems }
        progress.emit(ProblemProgress.untouched(courseA1.key).copy(notebookPageCount = 3))
        waitUntil("progress") { viewModel.uiState.value.setProblems.singleOrNull()?.progress?.notebookPageCount == 3 }
        assertEquals(3, viewModel.uiState.value.topicProblems.last().progress.notebookPageCount)
        viewModel.close()
    }

    @Test
    fun switchingToAllInvalidatesARepositoryThatIgnoresCancellation() = runBlocking {
        val late = CompletableDeferred<List<ProblemSummary>>()
        val repository = FakeArchiveContent(topicLoader = { late.await() })
        val viewModel = ArchiveViewModel(repository, FakeArchiveProgress(), testScope())
        waitUntil("catalog") { !viewModel.uiState.value.isLoadingCatalog }

        viewModel.selectTopic("algebra")
        viewModel.selectTopic("algebra")
        late.complete(listOf(putnamA1))
        delay(50)

        assertNull(viewModel.uiState.value.selectedTopicId)
        assertEquals(emptyList<ProblemRowUiState>(), viewModel.uiState.value.topicProblems)
        assertNull(viewModel.uiState.value.topicError)
        assertFalse(viewModel.uiState.value.isLoadingTopicProblems)
        viewModel.close()
    }

    @Test
    fun unknownTopicFallsBackToAllWithoutStartingAQuery() = runBlocking {
        var queryCount = 0
        val repository = FakeArchiveContent(topicLoader = {
            queryCount += 1
            emptyList()
        })
        val viewModel = ArchiveViewModel(repository, FakeArchiveProgress(), testScope())
        waitUntil("catalog") { !viewModel.uiState.value.isLoadingCatalog }

        viewModel.selectTopic("not-in-the-catalog")
        delay(25)

        assertNull(viewModel.uiState.value.selectedTopicId)
        assertFalse(viewModel.uiState.value.isLoadingTopicProblems)
        assertEquals(0, queryCount)
        viewModel.close()
    }

    @Test
    fun progressFailureIsVisibleAndRetryRepublishesBothLists() = runBlocking {
        val progress = RetryArchiveProgress(courseA1.key)
        val viewModel = ArchiveViewModel(
            FakeArchiveContent(
                setProblems = mapOf(courseSet.key to listOf(courseA1)),
                topicProblems = mapOf("algebra" to listOf(courseA1)),
            ),
            progress,
            testScope(),
        )
        waitUntil("catalog") { !viewModel.uiState.value.isLoadingCatalog }
        waitUntil("progress failure") { viewModel.uiState.value.progressError == "progress failed" }
        viewModel.selectTopic("algebra")
        viewModel.selectSet(courseSet.key)
        waitUntil("topic") { !viewModel.uiState.value.isLoadingTopicProblems }
        waitUntil("set") { !viewModel.uiState.value.isLoadingSetProblems }

        viewModel.retryProgress()

        waitUntil("progress retry") {
            viewModel.uiState.value.progressError == null &&
                viewModel.uiState.value.topicProblems.singleOrNull()?.progress?.notebookPageCount == 4 &&
                viewModel.uiState.value.setProblems.singleOrNull()?.progress?.notebookPageCount == 4
        }
        viewModel.close()
    }

    private suspend fun waitUntil(label: String, predicate: () -> Boolean) {
        repeat(1_000) {
            if (predicate()) return
            delay(5)
        }
        error("Timed out waiting for ViewModel state: $label")
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
}

private fun summary(key: ProblemKey, label: String, collection: String, set: String) = ProblemSummary(
    key = key,
    label = label,
    ordinal = 1,
    collectionTitle = collection,
    setTitle = set,
    competitionYear = key.setId.toIntOrNull(),
)

private class FakeArchiveContent(
    private val setProblems: Map<ProblemSetKey, List<ProblemSummary>> = emptyMap(),
    private val topicProblems: Map<String, List<ProblemSummary>> = emptyMap(),
    private val topicLoader: (suspend (String) -> List<ProblemSummary>)? = null,
) : ContentRepository {
    override suspend fun collections() = listOf(
        LibraryCollection("putnam", "Putnam", "competition", 0),
        LibraryCollection("course", "Course", "course", 1),
    )

    override suspend fun sets(collectionId: String) = when (collectionId) {
        "putnam" -> listOf(ProblemSetSummary(ProblemSetKey("putnam", "2025"), "2025", 0, 2025))
        "course" -> listOf(ProblemSetSummary(ProblemSetKey("course", "unit"), "Unit One", 0, null))
        else -> emptyList()
    }

    override suspend fun topics() = listOf(TopicSummary("algebra", "Algebra", 0))
    override suspend fun problems(setKey: ProblemSetKey) = setProblems[setKey].orEmpty()
    override suspend fun problemsByTopic(topicId: String) = topicLoader?.invoke(topicId) ?: topicProblems[topicId].orEmpty()
    override suspend fun problem(key: ProblemKey): ProblemContent? = null
}

private class FakeArchiveProgress : ProgressRepository {
    private val values = MutableStateFlow<Map<ProblemKey, ProblemProgress>>(emptyMap())
    fun emit(progress: ProblemProgress) { values.value = values.value + (progress.key to progress) }
    override fun observe(key: ProblemKey): Flow<ProblemProgress> = MutableStateFlow(ProblemProgress.untouched(key))
    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = values
    override fun observeRecent(): Flow<ProblemProgress?> = MutableStateFlow(null)
    override suspend fun markOpened(key: ProblemKey) = Unit
    override suspend fun revealNextHint(key: ProblemKey) = 0
    override suspend fun revealSolution(key: ProblemKey) = Unit
    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = Unit
    override suspend fun setNotebookState(key: ProblemKey, pageCount: Int, pageIndex: Int, referenceTab: ReferenceTab) = Unit
}

private class RetryArchiveProgress(private val key: ProblemKey) : ProgressRepository {
    private var attempts = 0
    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = flow {
        attempts += 1
        if (attempts == 1) error("progress failed")
        emit(mapOf(key to ProblemProgress.untouched(key).copy(notebookPageCount = 4)))
    }
    override fun observe(key: ProblemKey): Flow<ProblemProgress> = MutableStateFlow(ProblemProgress.untouched(key))
    override fun observeRecent(): Flow<ProblemProgress?> = MutableStateFlow(null)
    override suspend fun markOpened(key: ProblemKey) = Unit
    override suspend fun revealNextHint(key: ProblemKey) = 0
    override suspend fun revealSolution(key: ProblemKey) = Unit
    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = Unit
    override suspend fun setNotebookState(key: ProblemKey, pageCount: Int, pageIndex: Int, referenceTab: ReferenceTab) = Unit
}
