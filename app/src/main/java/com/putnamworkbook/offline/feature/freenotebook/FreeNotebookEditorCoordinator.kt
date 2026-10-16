package com.putnamworkbook.offline.feature.freenotebook

import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceNameError
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRecord
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRepository
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceResult
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceStorageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class FreeNotebookEditorUiState(
    val record: FreeWorkspaceRecord? = null,
    val isLoading: Boolean = true,
    val canEdit: Boolean = false,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val saveAsDialog: FreeWorkspaceNameDialogState? = null,
    val nameErrorAnimationKey: Long = 0,
)

class FreeNotebookEditorCoordinator(
    private val workspaceId: UUID,
    private val repository: FreeWorkspaceRepository,
    private val notifier: DurableSaveNotifier,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val openNotebook: (ProblemKey) -> Unit,
    private val awaitNotebookIdle: suspend () -> Unit,
    private val onBackReady: () -> Unit,
    private val onWorkspaceReplaced: (UUID) -> Unit,
) {
    private val mutableUiState = MutableStateFlow(FreeNotebookEditorUiState())
    private var operation: Job? = null
    val uiState: StateFlow<FreeNotebookEditorUiState> = mutableUiState.asStateFlow()

    init {
        load()
    }

    fun save() = runOperation {
        flushAll()
        mutableUiState.value = mutableUiState.value.copy(statusMessage = "Saved")
    }

    fun back() = runOperation {
        flushAll()
        onBackReady()
    }

    fun requestSaveAs() = runOperation {
        flushAll()
        val record = requireNotNull(mutableUiState.value.record)
        mutableUiState.value = mutableUiState.value.copy(
            saveAsDialog = FreeWorkspaceNameDialogState(
                purpose = FreeWorkspaceNamePurpose.SAVE_AS,
                value = record.name,
                workspaceId = record.id,
            ),
        )
    }

    fun updateSaveAsName(value: String) {
        val dialog = mutableUiState.value.saveAsDialog ?: return
        mutableUiState.value = mutableUiState.value.copy(
            saveAsDialog = dialog.copy(value = value, nameError = null, failureMessage = null),
        )
    }

    fun dismissSaveAs() {
        if (mutableUiState.value.isWorking) return
        mutableUiState.value = mutableUiState.value.copy(saveAsDialog = null)
    }

    fun confirmSaveAs() {
        val dialog = mutableUiState.value.saveAsDialog ?: return
        runOperation(keepDialogOnFailure = true) {
            flushAll()
            when (val result = repository.duplicate(workspaceId, dialog.value)) {
                is FreeWorkspaceResult.Success -> onWorkspaceReplaced(result.value.id)
                is FreeWorkspaceResult.NameRejected -> rejectName(result.error)
                is FreeWorkspaceResult.Failure -> failName(result.message)
            }
        }
    }

    fun reportSaveFailure(error: Throwable) {
        mutableUiState.value = mutableUiState.value.copy(
            errorMessage = error.message ?: "Workspace metadata could not be saved",
            statusMessage = null,
        )
    }

    fun close() {
        operation?.cancel()
    }

    private fun load() {
        operation = scope.launch {
            when (val result = repository.list()) {
                is FreeWorkspaceResult.Success -> {
                    val entry = result.value.firstOrNull { it.record.id == workspaceId }
                    when {
                        entry == null -> mutableUiState.value = FreeNotebookEditorUiState(
                            isLoading = false,
                            errorMessage = "Free notebook was not found",
                        )
                        entry.storageState == FreeWorkspaceStorageState.MISSING ->
                            mutableUiState.value = FreeNotebookEditorUiState(
                                record = entry.record,
                                isLoading = false,
                                errorMessage = "This free notebook's data is missing",
                            )
                        else -> {
                            mutableUiState.value = FreeNotebookEditorUiState(
                                record = entry.record,
                                isLoading = false,
                                canEdit = true,
                            )
                            openNotebook(repository.notebookKey(workspaceId))
                        }
                    }
                }
                is FreeWorkspaceResult.Failure -> mutableUiState.value = FreeNotebookEditorUiState(
                    isLoading = false,
                    errorMessage = result.message,
                )
                is FreeWorkspaceResult.NameRejected -> mutableUiState.value = FreeNotebookEditorUiState(
                    isLoading = false,
                    errorMessage = "Free notebook could not be opened",
                )
            }
        }
    }

    private fun runOperation(
        keepDialogOnFailure: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (mutableUiState.value.isWorking || mutableUiState.value.isLoading) return
        operation = scope.launch {
            mutableUiState.value = mutableUiState.value.copy(
                isWorking = true,
                errorMessage = null,
                statusMessage = null,
            )
            try {
                block()
                mutableUiState.value = mutableUiState.value.copy(isWorking = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    isWorking = false,
                    errorMessage = error.message ?: "Free notebook could not be saved",
                    saveAsDialog = if (keepDialogOnFailure) mutableUiState.value.saveAsDialog else null,
                )
            }
        }
    }

    private suspend fun flushAll() {
        awaitNotebookIdle()
        notifier.awaitIdle().getOrThrow()
    }

    private fun rejectName(error: FreeWorkspaceNameError) {
        val dialog = mutableUiState.value.saveAsDialog ?: return
        mutableUiState.value = mutableUiState.value.copy(
            isWorking = false,
            saveAsDialog = dialog.copy(nameError = error, failureMessage = null),
            nameErrorAnimationKey = mutableUiState.value.nameErrorAnimationKey + 1,
        )
    }

    private fun failName(message: String) {
        val dialog = mutableUiState.value.saveAsDialog ?: return
        mutableUiState.value = mutableUiState.value.copy(
            isWorking = false,
            saveAsDialog = dialog.copy(failureMessage = message),
            nameErrorAnimationKey = mutableUiState.value.nameErrorAnimationKey + 1,
        )
    }
}
