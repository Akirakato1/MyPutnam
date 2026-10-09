package com.putnamworkbook.offline.feature.home

import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.ProblemSummary
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

data class HomeUiState(
    val recentProblem: ProblemSummary? = null,
    val errorMessage: String? = null,
)

class HomeViewModel(
    progressRepository: ProgressRepository,
    private val contentRepository: ContentRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableUiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            try {
                progressRepository.observeRecent().collect { recent ->
                    mutableUiState.value = HomeUiState(
                        recentProblem = recent?.let { contentRepository.problem(it.key)?.summary },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.value = HomeUiState(
                    errorMessage = error.message ?: "Recent work could not be loaded",
                )
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}
