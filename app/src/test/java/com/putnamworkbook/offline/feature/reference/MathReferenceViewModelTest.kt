package com.putnamworkbook.offline.feature.reference

import com.putnamworkbook.offline.data.content.*
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MathReferenceViewModelTest {
    @Test
    fun categoryArticleAndResultNavigationConsumesBackInOrder() = runBlocking {
        val repository = FakeReferenceRepository()
        val viewModel = viewModel(repository)
        waitUntil { viewModel.uiState.value.categories.isNotEmpty() }

        viewModel.openCategory("number-theory")
        assertEquals(ReferenceLocation.Category("number-theory"), viewModel.uiState.value.location)
        viewModel.openArticle(EULER)
        waitUntil { viewModel.uiState.value.currentArticle != null }
        assertEquals(ReferenceLocation.Article(EULER), viewModel.uiState.value.location)
        assertTrue(viewModel.back())
        assertEquals(ReferenceLocation.Category("number-theory"), viewModel.uiState.value.location)
        assertTrue(viewModel.back())
        assertEquals(ReferenceLocation.Categories, viewModel.uiState.value.location)
        assertFalse(viewModel.back())

        viewModel.setQuery("phi")
        assertEquals(ReferenceLocation.Results("phi"), viewModel.uiState.value.location)
        assertEquals(listOf(EULER), viewModel.uiState.value.searchResults.map { it.article.key })
        viewModel.openArticle(EULER)
        waitUntil { viewModel.uiState.value.currentArticle != null }
        assertTrue(viewModel.back())
        assertEquals(ReferenceLocation.Results("phi"), viewModel.uiState.value.location)
        viewModel.setQuery("")
        assertEquals(ReferenceLocation.Categories, viewModel.uiState.value.location)
        viewModel.close()
    }

    @Test
    fun relatedTargetsOpenInstalledContentOrRequestFocusedLibrary() = runBlocking {
        val repository = FakeReferenceRepository()
        val viewModel = viewModel(repository)
        waitUntil { viewModel.uiState.value.articles.isNotEmpty() }

        viewModel.openRelatedReference(EULER)
        waitUntil { viewModel.uiState.value.currentArticle != null }
        assertEquals(ReferenceLocation.Article(EULER), viewModel.uiState.value.location)

        val missing = ReferenceKey("number-theory", "missing")
        viewModel.openRelatedReference(missing)
        assertEquals(missing.canonical, viewModel.uiState.value.focusedLibraryRequest)
        viewModel.consumeFocusedLibraryRequest()
        assertNull(viewModel.uiState.value.focusedLibraryRequest)

        val problem = ProblemKey("putnam", "2025", "A1")
        viewModel.openRelatedProblem(problem)
        assertEquals(problem, viewModel.uiState.value.relatedProblemRequest)
        viewModel.consumeRelatedProblemRequest()
        assertNull(viewModel.uiState.value.relatedProblemRequest)
        viewModel.close()
    }

    @Test
    fun repositoryRefreshAndRecoverableLoadFailureUpdateState() = runBlocking {
        val repository = FakeReferenceRepository().apply { failure = IllegalStateException("temporary") }
        val viewModel = viewModel(repository)
        waitUntil { viewModel.uiState.value.articles.isNotEmpty() }
        viewModel.openArticle(EULER)
        waitUntil { viewModel.uiState.value.error != null }
        assertTrue(viewModel.uiState.value.error!!.contains("temporary"))
        repository.failure = null
        viewModel.retry()
        waitUntil { viewModel.uiState.value.currentArticle != null }

        repository.articles.value = repository.articles.value + summary(
            ReferenceKey("number-theory", "fermat-euler"), "Fermat-Euler theorems", 2,
        )
        waitUntil { viewModel.uiState.value.articles.size == 2 }
        assertEquals(2, viewModel.uiState.value.articles.size)
        viewModel.close()
    }

    private fun viewModel(repository: FakeReferenceRepository) = MathReferenceViewModel(
        repository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private suspend fun waitUntil(predicate: () -> Boolean) {
        repeat(500) { if (predicate()) return else delay(5) }
        error("Timed out")
    }

    private class FakeReferenceRepository : ReferenceRepository {
        val categories = MutableStateFlow(listOf(ReferenceCategorySummary("number-theory", "Number Theory", 1)))
        val articles = MutableStateFlow(listOf(summary(EULER, "Euler's totient function", 1)))
        var failure: Throwable? = null

        override fun observeCategories(): Flow<List<ReferenceCategorySummary>> = categories
        override fun observeInstalledArticles(): Flow<List<ReferenceArticleSummary>> = articles
        override suspend fun article(key: ReferenceKey): ReferenceArticleContent? {
            failure?.let { throw it }
            val article = articles.value.firstOrNull { it.key == key } ?: return null
            return ReferenceArticleContent(
                article,
                ContentResourceRef(key.canonical, "a".repeat(64), "documents/article.fragment.html", "b".repeat(64)),
                listOf(ReferenceKey("number-theory", "fermat-euler")),
                listOf(ProblemKey("putnam", "2025", "A1")),
                listOf(Attribution("reference", "Source", "https://example.com")),
            )
        }
    }

    companion object {
        private val EULER = ReferenceKey("number-theory", "euler-totient")
        private fun summary(key: ReferenceKey, title: String, order: Int) = ReferenceArticleSummary(
            key, title, listOf("phi"), listOf("coprime"), "Number Theory", 1, order,
        )
    }
}
