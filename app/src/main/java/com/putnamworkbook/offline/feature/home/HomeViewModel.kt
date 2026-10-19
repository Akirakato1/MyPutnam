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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface RecentProblemState {
    data object None : RecentProblemState
    data class Available(val problem: ProblemSummary) : RecentProblemState
    data class Unavailable(val key: com.putnamworkbook.offline.domain.model.ProblemKey) : RecentProblemState
}

data class HomeUiState(
    val recentProblem: RecentProblemState = RecentProblemState.None,
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
                combine(
                    progressRepository.observeRecent(),
                    contentRepository.observeInstalledRevision(),
                ) { recent, _ -> recent }.collectLatest { recent ->
                    val recentState = when (recent) {
                        null -> RecentProblemState.None
                        else -> contentRepository.problem(recent.key)?.summary
                            ?.let(RecentProblemState::Available)
                            ?: RecentProblemState.Unavailable(recent.key)
                    }
                    mutableUiState.value = HomeUiState(
                        recentProblem = recentState,
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
