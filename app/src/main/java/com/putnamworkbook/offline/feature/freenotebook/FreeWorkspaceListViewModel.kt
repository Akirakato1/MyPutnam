package com.putnamworkbook.offline.feature.freenotebook

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceEntry
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceNameError
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRecord
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRepository
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceResult
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
import java.util.UUID

enum class FreeWorkspaceNamePurpose {
    CREATE,
    RENAME,
    SAVE_AS,
}

data class FreeWorkspaceNameDialogState(
    val purpose: FreeWorkspaceNamePurpose,
    val value: String,
    val workspaceId: UUID? = null,
    val nameError: FreeWorkspaceNameError? = null,
    val failureMessage: String? = null,
)

data class FreeWorkspaceListUiState(
    val entries: List<FreeWorkspaceEntry> = emptyList(),
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val errorMessage: String? = null,
    val nameDialog: FreeWorkspaceNameDialogState? = null,
    val deleteConfirmation: FreeWorkspaceRecord? = null,
    val nameErrorAnimationKey: Long = 0,
)

class FreeWorkspaceListViewModel(
    private val repository: FreeWorkspaceRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val onWorkspaceCreated: (UUID) -> Unit = {},
) {
    private val mutableUiState = MutableStateFlow(FreeWorkspaceListUiState())
    private var operation: Job? = null

    val uiState: StateFlow<FreeWorkspaceListUiState> = mutableUiState.asStateFlow()

    init {
        refresh()
    }

    fun requestCreate() {
        mutableUiState.value = mutableUiState.value.copy(
            nameDialog = FreeWorkspaceNameDialogState(FreeWorkspaceNamePurpose.CREATE, ""),
            errorMessage = null,
        )
    }

    fun requestRename(id: UUID) {
        val record = mutableUiState.value.entries.firstOrNull { it.record.id == id }?.record ?: return
        mutableUiState.value = mutableUiState.value.copy(
            nameDialog = FreeWorkspaceNameDialogState(
                purpose = FreeWorkspaceNamePurpose.RENAME,
                value = record.name,
                workspaceId = id,
            ),
            errorMessage = null,
        )
    }

    fun updateName(value: String) {
        val dialog = mutableUiState.value.nameDialog ?: return
        mutableUiState.value = mutableUiState.value.copy(
            nameDialog = dialog.copy(value = value, nameError = null, failureMessage = null),
        )
    }

    fun submitName() {
        when (mutableUiState.value.nameDialog?.purpose) {
            FreeWorkspaceNamePurpose.CREATE -> create(mutableUiState.value.nameDialog!!.value)
            FreeWorkspaceNamePurpose.RENAME -> rename(mutableUiState.value.nameDialog!!.value)
            FreeWorkspaceNamePurpose.SAVE_AS, null -> Unit
        }
    }

    fun create(name: String) = mutateName {
        when (val result = repository.create(name, PaperStyle.SQUARE_GRID)) {
            is FreeWorkspaceResult.Success -> {
                finishNameMutation()
                onWorkspaceCreated(result.value.id)
            }
            is FreeWorkspaceResult.NameRejected -> rejectName(result.error)
            is FreeWorkspaceResult.Failure -> failName(result.message)
        }
    }

    fun rename(name: String) {
        val id = mutableUiState.value.nameDialog?.workspaceId ?: return
        mutateName {
            when (val result = repository.rename(id, name)) {
                is FreeWorkspaceResult.Success -> finishNameMutation()
                is FreeWorkspaceResult.NameRejected -> rejectName(result.error)
                is FreeWorkspaceResult.Failure -> failName(result.message)
            }
        }
    }

    fun dismissNameDialog() {
        if (mutableUiState.value.isWorking) return
        mutableUiState.value = mutableUiState.value.copy(nameDialog = null)
    }

    fun requestDelete(id: UUID) {
        val record = mutableUiState.value.entries.firstOrNull { it.record.id == id }?.record ?: return
        mutableUiState.value = mutableUiState.value.copy(deleteConfirmation = record, errorMessage = null)
    }

    fun dismissDelete() {
        if (mutableUiState.value.isWorking) return
        mutableUiState.value = mutableUiState.value.copy(deleteConfirmation = null)
    }

    fun confirmDelete() {
        val record = mutableUiState.value.deleteConfirmation ?: return
        if (mutableUiState.value.isWorking) return
        operation = scope.launch {
            mutableUiState.value = mutableUiState.value.copy(isWorking = true, errorMessage = null)
            when (val result = repository.delete(record.id)) {
                is FreeWorkspaceResult.Success -> {
                    val listed = repository.list()
                    mutableUiState.value = when (listed) {
                        is FreeWorkspaceResult.Success -> mutableUiState.value.copy(
                            entries = listed.value,
                            deleteConfirmation = null,
                            isWorking = false,
                        )
                        is FreeWorkspaceResult.Failure -> mutableUiState.value.copy(
                            deleteConfirmation = null,
                            isWorking = false,
                            errorMessage = listed.message,
                        )
                        is FreeWorkspaceResult.NameRejected -> mutableUiState.value.copy(
                            deleteConfirmation = null,
                            isWorking = false,
                            errorMessage = "Free notebooks could not be refreshed",
                        )
                    }
                }
                is FreeWorkspaceResult.Failure -> mutableUiState.value = mutableUiState.value.copy(
                    isWorking = false,
                    errorMessage = result.message,
                )
                is FreeWorkspaceResult.NameRejected -> mutableUiState.value = mutableUiState.value.copy(
                    isWorking = false,
                    errorMessage = "Free notebook could not be deleted",
                )
            }
        }
    }

    fun retry() = refresh()

    fun close() {
        operation?.cancel()
        scope.cancel()
    }

    private fun refresh() {
        operation?.cancel()
        operation = scope.launch {
            mutableUiState.value = mutableUiState.value.copy(isLoading = true, errorMessage = null)
            try {
                when (val result = repository.list()) {
                    is FreeWorkspaceResult.Success -> mutableUiState.value = mutableUiState.value.copy(
                        entries = result.value,
                        isLoading = false,
                    )
                    is FreeWorkspaceResult.Failure -> mutableUiState.value = mutableUiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message,
                    )
                    is FreeWorkspaceResult.NameRejected -> mutableUiState.value = mutableUiState.value.copy(
                        isLoading = false,
                        errorMessage = "Free notebooks could not be loaded",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Free notebooks could not be loaded",
                )
            }
        }
    }

    private fun mutateName(block: suspend () -> Unit) {
        if (mutableUiState.value.isWorking || mutableUiState.value.nameDialog == null) return
        operation = scope.launch {
            mutableUiState.value = mutableUiState.value.copy(isWorking = true)
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failName(error.message ?: "Free notebook could not be saved")
            }
        }
    }

    private suspend fun finishNameMutation() {
        val listed = repository.list()
        mutableUiState.value = when (listed) {
            is FreeWorkspaceResult.Success -> mutableUiState.value.copy(
                entries = listed.value,
                nameDialog = null,
                isWorking = false,
                errorMessage = null,
            )
            is FreeWorkspaceResult.Failure -> mutableUiState.value.copy(
                nameDialog = null,
                isWorking = false,
                errorMessage = listed.message,
            )
            is FreeWorkspaceResult.NameRejected -> mutableUiState.value.copy(
                nameDialog = null,
                isWorking = false,
                errorMessage = "Free notebooks could not be refreshed",
            )
        }
    }

    private fun rejectName(error: FreeWorkspaceNameError) {
        val dialog = mutableUiState.value.nameDialog ?: return
        mutableUiState.value = mutableUiState.value.copy(
            isWorking = false,
            nameDialog = dialog.copy(nameError = error, failureMessage = null),
            nameErrorAnimationKey = mutableUiState.value.nameErrorAnimationKey + 1,
        )
    }

    private fun failName(message: String) {
        val dialog = mutableUiState.value.nameDialog ?: return
        mutableUiState.value = mutableUiState.value.copy(
            isWorking = false,
            nameDialog = dialog.copy(failureMessage = message),
            nameErrorAnimationKey = mutableUiState.value.nameErrorAnimationKey + 1,
        )
    }
}
