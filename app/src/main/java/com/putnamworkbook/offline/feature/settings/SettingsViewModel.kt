package com.putnamworkbook.offline.feature.settings

import com.putnamworkbook.offline.backup.BackupCoordinator
import com.putnamworkbook.offline.backup.BackupResult
import com.putnamworkbook.offline.backup.RestoreResult
import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.domain.model.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream

enum class BackupAction {
    CREATE,
    RESTORE,
}

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data class Working(val action: BackupAction) : BackupUiState
    data class Complete(val message: String) : BackupUiState
    data class Error(val message: String) : BackupUiState
}

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val backupCoordinator: BackupCoordinator? = null,
) {
    private val mutableUiState = MutableStateFlow(AppSettings())
    private val mutableBackupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    private val loaded = CompletableDeferred<Unit>()
    private val updateMutex = Mutex()
    private var latestUpdate: Job? = null
    private var latestBackup: Job? = null

    val uiState: StateFlow<AppSettings> = mutableUiState.asStateFlow()
    val backupState: StateFlow<BackupUiState> = mutableBackupState.asStateFlow()

    init {
        scope.launch {
            repository.observe().collect { settings ->
                mutableUiState.value = settings
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        latestUpdate = scope.launch {
            updateMutex.withLock {
                val next = transform(mutableUiState.value)
                repository.update { next }
                mutableUiState.value = next
            }
        }
    }

    fun createBackup(destination: OutputStream) {
        latestBackup = scope.launch {
            updateMutex.withLock {
                val coordinator = backupCoordinator
                if (coordinator == null) {
                    runCatching { destination.close() }
                    mutableBackupState.value = BackupUiState.Error("Backup is unavailable")
                    return@withLock
                }
                mutableBackupState.value = BackupUiState.Working(BackupAction.CREATE)
                val result = runCatching { destination.use { coordinator.create(it) } }
                    .getOrElse { BackupResult.Failure(it.message ?: "Backup could not be created") }
                mutableBackupState.value = when (result) {
                    is BackupResult.Success -> BackupUiState.Complete("Backup saved")
                    is BackupResult.Failure -> BackupUiState.Error(result.message)
                }
            }
        }
    }

    fun restoreBackup(source: InputStream) {
        latestBackup = scope.launch {
            updateMutex.withLock {
                val coordinator = backupCoordinator
                if (coordinator == null) {
                    runCatching { source.close() }
                    mutableBackupState.value = BackupUiState.Error("Restore is unavailable")
                    return@withLock
                }
                mutableBackupState.value = BackupUiState.Working(BackupAction.RESTORE)
                val result = runCatching { source.use { coordinator.validateAndRestore(it) } }
                    .getOrElse { RestoreResult.Failure(it.message ?: "Backup could not be restored") }
                mutableBackupState.value = when (result) {
                    is RestoreResult.Success -> BackupUiState.Complete("Backup restored")
                    is RestoreResult.Failure -> BackupUiState.Error(result.message)
                }
            }
        }
    }

    fun reportStorageError(message: String) {
        mutableBackupState.value = BackupUiState.Error(message)
    }

    suspend fun awaitLoaded() {
        loaded.await()
    }

    suspend fun awaitIdle() {
        latestUpdate?.join()
        latestBackup?.join()
    }

    fun close() {
        scope.cancel()
    }
}
