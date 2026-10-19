package com.putnamworkbook.offline.feature.reference

import com.putnamworkbook.offline.data.content.ReferenceArticleSummary
import com.putnamworkbook.offline.data.content.ReferenceCategorySummary
import com.putnamworkbook.offline.data.content.ReferenceRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MathReferenceViewModel(
    private val repository: ReferenceRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val history = ArrayDeque<ReferenceLocation>()
    private val mutableUiState = MutableStateFlow(MathReferenceUiState())
    val uiState: StateFlow<MathReferenceUiState> = mutableUiState.asStateFlow()
    private var categories: List<ReferenceCategorySummary> = emptyList()
    private var articles: List<ReferenceArticleSummary> = emptyList()
    private var loadJob: Job? = null

    init {
        scope.launch {
            repository.observeCategories().collect {
                categories = it
                publishLists()
            }
        }
        scope.launch {
            repository.observeInstalledArticles().collect {
                articles = it
                publishLists()
                val location = mutableUiState.value.location
                if (location is ReferenceLocation.Article && articles.none { article -> article.key == location.key }) {
                    loadJob?.cancel()
                    mutableUiState.value = mutableUiState.value.copy(
                        currentArticle = null,
                        loading = false,
                        error = "This reference is not installed",
                        focusedLibraryRequest = location.key.canonical,
                    )
                }
            }
        }
    }

    fun setQuery(query: String) {
        val state = mutableUiState.value
        if (query.isBlank()) {
            history.clear()
            mutableUiState.value = state.copy(
                location = ReferenceLocation.Categories,
                query = "",
                searchResults = emptyList(),
                currentArticle = null,
                error = null,
                canGoBack = false,
            )
            return
        }
        if (state.location !is ReferenceLocation.Results) history.addLast(state.location)
        mutableUiState.value = state.copy(
            location = ReferenceLocation.Results(query),
            query = query,
            searchResults = searchReferences(query, articles),
            currentArticle = null,
            error = null,
            canGoBack = history.isNotEmpty(),
        )
    }

    fun openCategory(id: String) = navigate(ReferenceLocation.Category(id))

    fun openArticle(key: ReferenceKey) {
        if (articles.none { it.key == key }) {
            mutableUiState.value = mutableUiState.value.copy(focusedLibraryRequest = key.canonical)
            return
        }
        navigate(ReferenceLocation.Article(key))
        loadArticle(key)
    }

    fun openRelatedReference(key: ReferenceKey) = openArticle(key)

    fun openRelatedProblem(key: ProblemKey) {
        mutableUiState.value = mutableUiState.value.copy(relatedProblemRequest = key)
    }

    fun consumeFocusedLibraryRequest() {
        mutableUiState.value = mutableUiState.value.copy(focusedLibraryRequest = null)
    }

    fun consumeRelatedProblemRequest() {
        mutableUiState.value = mutableUiState.value.copy(relatedProblemRequest = null)
    }

    fun back(): Boolean {
        if (history.isEmpty()) return false
        loadJob?.cancel()
        val location = history.removeLast()
        mutableUiState.value = mutableUiState.value.copy(
            location = location,
            query = (location as? ReferenceLocation.Results)?.query.orEmpty(),
            searchResults = (location as? ReferenceLocation.Results)?.let { searchReferences(it.query, articles) }.orEmpty(),
            currentArticle = null,
            loading = false,
            error = null,
            canGoBack = history.isNotEmpty(),
        )
        if (location is ReferenceLocation.Article) loadArticle(location.key)
        return true
    }

    fun retry() {
        (mutableUiState.value.location as? ReferenceLocation.Article)?.let { loadArticle(it.key) }
    }

    fun close() = scope.cancel()

    private fun navigate(location: ReferenceLocation) {
        val current = mutableUiState.value.location
        if (current != location) history.addLast(current)
        mutableUiState.value = mutableUiState.value.copy(
            location = location,
            currentArticle = null,
            loading = location is ReferenceLocation.Article,
            error = null,
            canGoBack = history.isNotEmpty(),
        )
    }

    private fun loadArticle(key: ReferenceKey) {
        loadJob?.cancel()
        mutableUiState.value = mutableUiState.value.copy(
            loading = true,
            error = null,
            currentArticle = null,
            focusedLibraryRequest = null,
        )
        loadJob = scope.launch {
            try {
                val content = repository.article(key)
                if (mutableUiState.value.location != ReferenceLocation.Article(key)) return@launch
                mutableUiState.value = if (content == null) {
                    mutableUiState.value.copy(
                        loading = false,
                        error = "This reference is not installed",
                        focusedLibraryRequest = key.canonical,
                    )
                } else {
                    mutableUiState.value.copy(currentArticle = content, loading = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (mutableUiState.value.location == ReferenceLocation.Article(key)) {
                    mutableUiState.value = mutableUiState.value.copy(
                        loading = false,
                        error = error.message?.take(200) ?: "Reference could not be opened",
                    )
                }
            }
        }
    }

    private fun publishLists() {
        val state = mutableUiState.value
        mutableUiState.value = state.copy(
            categories = categories,
            articles = articles,
            searchResults = if (state.query.isBlank()) emptyList() else searchReferences(state.query, articles),
        )
    }
}
