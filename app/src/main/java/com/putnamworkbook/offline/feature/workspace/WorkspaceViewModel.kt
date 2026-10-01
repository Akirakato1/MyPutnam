package com.putnamworkbook.offline.feature.workspace

import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkspaceViewModel(
    private val contentRepository: ContentRepository,
    private val progressRepository: ProgressRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableUiState = MutableStateFlow(WorkspaceUiState())
    private val operationMutex = Mutex()
    private var currentKey: ProblemKey? = null
    private var notebookPageCount = 1
    private var notebookPageIndex = 0
    private var latestOperation: Job? = null

    val uiState: StateFlow<WorkspaceUiState> = mutableUiState.asStateFlow()

    fun open(key: ProblemKey) = launchOperation {
        currentKey = key
        mutableUiState.value = WorkspaceUiState(key = key, isLoading = true)
        val content = requireNotNull(contentRepository.problem(key)) { "Problem ${key.year} ${key.code} is unavailable" }
        progressRepository.markOpened(key)
        var progress = progressRepository.observe(key).first()
        notebookPageCount = progress.notebookPageCount
        notebookPageIndex = progress.activePageIndex
        val restoredTab = progress.activeReferenceTab.normalizedForDisplay().takeIf { tab ->
            tab != ReferenceTab.SOLUTION || progress.solutionRevealed
        } ?: ReferenceTab.PROBLEM
        if (restoredTab != progress.activeReferenceTab) {
            progressRepository.setNotebookState(
                key,
                notebookPageCount,
                notebookPageIndex,
                restoredTab,
            )
            progress = progressRepository.observe(key).first()
        }
        mutableUiState.value = WorkspaceUiState(
            key = key,
            content = content,
            progress = progress,
            selectedReferenceTab = restoredTab,
            activeHintIndex = if (progress.revealedHintCount > 0) progress.revealedHintCount - 1 else -1,
            activePrerequisiteIndex = if (content.prerequisites.isEmpty()) -1 else 0,
        )
    }

    fun revealNextStage() = launchOperation {
        val key = requireOpenKey()
        when (mutableUiState.value.nextReveal) {
            RevealStage.HINT -> {
                val revealedCount = progressRepository.revealNextHint(key)
                refreshProgress(
                    selectedTab = ReferenceTab.HELP,
                    activeHintIndex = revealedCount - 1,
                )
            }
            RevealStage.SOLUTION -> {
                progressRepository.revealSolution(key)
                refreshProgress(selectedTab = ReferenceTab.SOLUTION)
            }
            RevealStage.NONE -> Unit
        }
    }

    fun setCompleted(completed: Boolean) = launchOperation {
        progressRepository.markCompleted(requireOpenKey(), completed)
        refreshProgress()
    }

    fun selectHint(index: Int) = launchOperation {
        val revealedCount = mutableUiState.value.progress?.revealedHintCount ?: 0
        require(index in 0 until revealedCount) { "Hint has not been revealed" }
        progressRepository.setNotebookState(
            requireOpenKey(),
            notebookPageCount,
            notebookPageIndex,
            ReferenceTab.HELP,
        )
        refreshProgress(selectedTab = ReferenceTab.HELP, activeHintIndex = index)
    }

    fun selectPrerequisite(index: Int) {
        val prerequisites = mutableUiState.value.content?.prerequisites.orEmpty()
        require(index in prerequisites.indices) { "Prerequisite is unavailable" }
        mutableUiState.value = mutableUiState.value.copy(activePrerequisiteIndex = index)
    }

    fun selectReferenceTab(tab: ReferenceTab) = launchOperation {
        val visibleTab = tab.normalizedForDisplay()
        val state = mutableUiState.value
        if (visibleTab == ReferenceTab.SOLUTION && state.progress?.solutionRevealed != true) return@launchOperation
        progressRepository.setNotebookState(
            requireOpenKey(),
            notebookPageCount,
            notebookPageIndex,
            visibleTab,
        )
        refreshProgress(selectedTab = visibleTab)
    }

    fun updateNotebookState(pageCount: Int, pageIndex: Int) = launchOperation {
        require(pageCount >= 1)
        require(pageIndex in 0 until pageCount)
        notebookPageCount = pageCount
        notebookPageIndex = pageIndex
        progressRepository.setNotebookState(
            requireOpenKey(),
            pageCount,
            pageIndex,
            mutableUiState.value.selectedReferenceTab,
        )
        refreshProgress()
    }

    fun reportIntegrityError(message: String) {
        mutableUiState.value = mutableUiState.value.copy(errorMessage = message)
    }

    suspend fun awaitIdle() {
        while (true) {
            val operation = latestOperation ?: return
            operation.join()
            if (operation === latestOperation) return
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun launchOperation(block: suspend () -> Unit) {
        latestOperation = scope.launch {
            operationMutex.withLock {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableUiState.value = mutableUiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Workspace operation failed",
                    )
                }
            }
        }
    }

    private suspend fun refreshProgress(
        selectedTab: ReferenceTab = mutableUiState.value.selectedReferenceTab,
        activeHintIndex: Int = mutableUiState.value.activeHintIndex,
    ) {
        val progress = progressRepository.observe(requireOpenKey()).first()
        mutableUiState.value = mutableUiState.value.copy(
            progress = progress,
            selectedReferenceTab = selectedTab,
            activeHintIndex = activeHintIndex,
            errorMessage = null,
        )
    }

    private fun requireOpenKey(): ProblemKey =
        requireNotNull(currentKey) { "Open a problem before changing workspace state" }
}
