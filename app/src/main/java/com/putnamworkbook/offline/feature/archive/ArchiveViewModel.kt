package com.putnamworkbook.offline.feature.archive

import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.LibraryCollection
import com.putnamworkbook.offline.data.content.ProblemSetSummary
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.content.TopicSummary
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProblemRowUiState(
    val summary: ProblemSummary,
    val progress: ProblemProgress,
)

data class ArchiveUiState(
    val collections: List<LibraryCollection> = emptyList(),
    val setsByCollection: Map<String, List<ProblemSetSummary>> = emptyMap(),
    val topics: List<TopicSummary> = emptyList(),
    val selectedTopicId: String? = null,
    val topicProblems: List<ProblemRowUiState> = emptyList(),
    val selectedSet: ProblemSetSummary? = null,
    val setProblems: List<ProblemRowUiState> = emptyList(),
    val isLoadingCatalog: Boolean = true,
    val isLoadingTopicProblems: Boolean = false,
    val isLoadingSetProblems: Boolean = false,
    val catalogError: String? = null,
    val topicError: String? = null,
    val setError: String? = null,
    val progressError: String? = null,
)

class ArchiveViewModel(
    private val contentRepository: ContentRepository,
    private val progressRepository: ProgressRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableUiState = MutableStateFlow(ArchiveUiState())
    private var topicSummaries: List<ProblemSummary> = emptyList()
    private var setSummaries: List<ProblemSummary> = emptyList()
    private var progressByProblem: Map<ProblemKey, ProblemProgress> = emptyMap()
    private var catalogGeneration = 0
    private var topicGeneration = 0
    private var setGeneration = 0
    private var progressGeneration = 0
    private var catalogJob: Job? = null
    private var topicJob: Job? = null
    private var setJob: Job? = null
    private var progressJob: Job? = null
    private var installedRevisionJob: Job? = null

    val uiState: StateFlow<ArchiveUiState> = mutableUiState.asStateFlow()

    init {
        installedRevisionJob = scope.launch {
            contentRepository.observeInstalledRevision().collect {
                refreshInstalledContent()
            }
        }
        loadProgress()
    }

    fun selectTopic(topicId: String?) {
        val knownTopic = topicId?.takeIf { candidate -> mutableUiState.value.topics.any { it.id == candidate } }
        val selected = if (knownTopic == mutableUiState.value.selectedTopicId) null else knownTopic
        topicJob?.cancel()
        val generation = ++topicGeneration
        topicSummaries = emptyList()
        mutableUiState.value = mutableUiState.value.copy(
            selectedTopicId = selected,
            topicProblems = emptyList(),
            isLoadingTopicProblems = selected != null,
            topicError = null,
        )
        if (selected == null) return
        loadTopic(selected, generation)
    }

    private fun loadTopic(selected: String, generation: Int = ++topicGeneration) {
        topicJob?.cancel()
        mutableUiState.value = mutableUiState.value.copy(
            selectedTopicId = selected,
            isLoadingTopicProblems = true,
            topicError = null,
        )
        topicJob = scope.launch {
            try {
                val loaded = contentRepository.problemsByTopic(selected)
                if (generation != topicGeneration || mutableUiState.value.selectedTopicId != selected) return@launch
                topicSummaries = loaded
                publishTopicProblems(isLoading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == topicGeneration && mutableUiState.value.selectedTopicId == selected) {
                    mutableUiState.value = mutableUiState.value.copy(
                        isLoadingTopicProblems = false,
                        topicError = error.message ?: "Topic problems could not be loaded",
                    )
                }
            }
        }
    }

    fun selectSet(key: ProblemSetKey) {
        val selected = mutableUiState.value.setsByCollection[key.collectionId]
            ?.firstOrNull { it.key == key }
            ?: return
        loadSet(selected)
    }

    private fun loadSet(selected: ProblemSetSummary, generation: Int = ++setGeneration) {
        setJob?.cancel()
        setSummaries = emptyList()
        mutableUiState.value = mutableUiState.value.copy(
            selectedSet = selected,
            setProblems = emptyList(),
            isLoadingSetProblems = true,
            setError = null,
        )
        setJob = scope.launch {
            try {
                val loaded = contentRepository.problems(selected.key)
                if (generation != setGeneration || mutableUiState.value.selectedSet?.key != selected.key) return@launch
                setSummaries = loaded
                publishSetProblems(isLoading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == setGeneration && mutableUiState.value.selectedSet?.key == selected.key) {
                    mutableUiState.value = mutableUiState.value.copy(
                        isLoadingSetProblems = false,
                        setError = error.message ?: "Set problems could not be loaded",
                    )
                }
            }
        }
    }

    fun retryCatalog() = loadCatalog()
    fun retryTopic() { mutableUiState.value.selectedTopicId?.let { selected -> selectTopic(null); selectTopic(selected) } }
    fun retrySet() { mutableUiState.value.selectedSet?.key?.let(::selectSet) }
    fun retryProgress() = loadProgress()

    fun close() {
        catalogGeneration += 1
        topicGeneration += 1
        setGeneration += 1
        progressGeneration += 1
        scope.cancel()
    }

    private fun refreshInstalledContent() {
        val selectedTopic = mutableUiState.value.selectedTopicId
        val selectedSet = mutableUiState.value.selectedSet
        loadCatalog()
        selectedTopic?.let { loadTopic(it) }
        selectedSet?.let { loadSet(it) }
    }

    private fun loadCatalog() {
        catalogJob?.cancel()
        val generation = ++catalogGeneration
        mutableUiState.value = mutableUiState.value.copy(isLoadingCatalog = true, catalogError = null)
        catalogJob = scope.launch {
            try {
                val collections = contentRepository.collections()
                val topics = contentRepository.topics()
                val sets = collections.associate { collection -> collection.id to contentRepository.sets(collection.id) }
                if (generation != catalogGeneration) return@launch
                val selectedTopic = mutableUiState.value.selectedTopicId
                    ?.takeIf { selected -> topics.any { it.id == selected } }
                if (selectedTopic != mutableUiState.value.selectedTopicId) {
                    topicJob?.cancel()
                    topicGeneration += 1
                    topicSummaries = emptyList()
                }
                mutableUiState.value = mutableUiState.value.copy(
                    collections = collections,
                    setsByCollection = sets,
                    topics = topics,
                    selectedTopicId = selectedTopic,
                    topicProblems = if (selectedTopic == null) emptyList() else mutableUiState.value.topicProblems,
                    isLoadingCatalog = false,
                    isLoadingTopicProblems = if (selectedTopic == null) false else mutableUiState.value.isLoadingTopicProblems,
                    catalogError = null,
                    topicError = if (selectedTopic == null) null else mutableUiState.value.topicError,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == catalogGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        isLoadingCatalog = false,
                        catalogError = error.message ?: "Library catalog could not be loaded",
                    )
                }
            }
        }
    }

    private fun loadProgress() {
        progressJob?.cancel()
        val generation = ++progressGeneration
        mutableUiState.value = mutableUiState.value.copy(progressError = null)
        progressJob = scope.launch {
            try {
                progressRepository.observeAll().collect { progress ->
                    if (generation != progressGeneration) return@collect
                    progressByProblem = progress
                    publishTopicProblems()
                    publishSetProblems()
                    mutableUiState.value = mutableUiState.value.copy(progressError = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == progressGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        progressError = error.message ?: "Problem progress could not be loaded",
                    )
                }
            }
        }
    }

    private fun publishTopicProblems(isLoading: Boolean = mutableUiState.value.isLoadingTopicProblems) {
        mutableUiState.value = mutableUiState.value.copy(
            topicProblems = topicSummaries.toRows(),
            isLoadingTopicProblems = isLoading,
        )
    }

    private fun publishSetProblems(isLoading: Boolean = mutableUiState.value.isLoadingSetProblems) {
        mutableUiState.value = mutableUiState.value.copy(
            setProblems = setSummaries.toRows(),
            isLoadingSetProblems = isLoading,
        )
    }

    private fun List<ProblemSummary>.toRows(): List<ProblemRowUiState> = map { summary ->
        ProblemRowUiState(
            summary = summary,
            progress = progressByProblem[summary.key] ?: ProblemProgress.untouched(summary.key),
        )
    }
}
