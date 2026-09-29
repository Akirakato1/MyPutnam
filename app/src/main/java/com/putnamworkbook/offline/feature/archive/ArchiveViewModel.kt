package com.putnamworkbook.offline.feature.archive

import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val years: List<Int> = emptyList(),
    val selectedYear: Int? = null,
    val problems: List<ProblemRowUiState> = emptyList(),
    val isLoadingYears: Boolean = true,
    val isLoadingProblems: Boolean = false,
    val errorMessage: String? = null,
)

class ArchiveViewModel(
    private val contentRepository: ContentRepository,
    progressRepository: ProgressRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableUiState = MutableStateFlow(ArchiveUiState())
    private var summaries: List<ProblemSummary> = emptyList()
    private var progressByProblem: Map<com.putnamworkbook.offline.domain.model.ProblemKey, ProblemProgress> = emptyMap()
    private var problemLoadGeneration = 0

    val uiState: StateFlow<ArchiveUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            progressRepository.observeAll().collect { progress ->
                progressByProblem = progress
                publishProblems()
            }
        }
        loadYears()
    }

    fun selectYear(year: Int) {
        if (mutableUiState.value.selectedYear == year && summaries.isNotEmpty()) return
        val generation = ++problemLoadGeneration
        summaries = emptyList()
        mutableUiState.value = mutableUiState.value.copy(
            selectedYear = year,
            problems = emptyList(),
            isLoadingProblems = true,
            errorMessage = null,
        )
        scope.launch {
            try {
                val loaded = contentRepository.problems(year).sortedBy { it.ordinal }
                if (generation != problemLoadGeneration) return@launch
                summaries = loaded
                publishProblems(isLoading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == problemLoadGeneration) {
                    mutableUiState.value = mutableUiState.value.copy(
                        isLoadingProblems = false,
                        errorMessage = error.message ?: "Problems could not be loaded",
                    )
                }
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun loadYears() {
        scope.launch {
            try {
                val years = contentRepository.years().distinct().sortedDescending()
                mutableUiState.value = mutableUiState.value.copy(
                    years = years,
                    isLoadingYears = false,
                    errorMessage = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    isLoadingYears = false,
                    errorMessage = error.message ?: "Archive years could not be loaded",
                )
            }
        }
    }

    private fun publishProblems(isLoading: Boolean = mutableUiState.value.isLoadingProblems) {
        if (summaries.isEmpty() && mutableUiState.value.selectedYear == null) return
        mutableUiState.value = mutableUiState.value.copy(
            problems = summaries.map { summary ->
                ProblemRowUiState(
                    summary = summary,
                    progress = progressByProblem[summary.key] ?: ProblemProgress.untouched(summary.key),
                )
            },
            isLoadingProblems = isLoading,
        )
    }
}
