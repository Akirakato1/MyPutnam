package com.putnamworkbook.offline.feature.settings

import com.putnamworkbook.offline.backup.BackupCoordinator
import com.putnamworkbook.offline.backup.BackupResult
import com.putnamworkbook.offline.backup.RestoreResult
import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.data.library.index.PackageAction
import com.putnamworkbook.offline.data.library.network.NetworkStatusMonitor
import com.putnamworkbook.offline.data.library.packages.PackageInstaller
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
    data class ReinstallReview(
        val candidates: List<String>,
        val selected: Set<String>,
        val isOnline: Boolean,
        val restoredBundledCount: Int,
    ) : BackupUiState
}

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val backupCoordinator: BackupCoordinator? = null,
    private val packageInstaller: PackageInstaller? = null,
    networkStatusMonitor: NetworkStatusMonitor? = null,
) {
    private val mutableUiState = MutableStateFlow(AppSettings())
    private val mutableBackupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    private val loaded = CompletableDeferred<Unit>()
    private val updateMutex = Mutex()
    private var latestUpdate: Job? = null
    private var latestBackup: Job? = null
    private var isOnline = (networkStatusMonitor?.isOnline as? StateFlow<Boolean>)?.value ?: false

    val uiState: StateFlow<AppSettings> = mutableUiState.asStateFlow()
    val backupState: StateFlow<BackupUiState> = mutableBackupState.asStateFlow()

    init {
        scope.launch {
            repository.observe().collect { settings ->
                mutableUiState.value = settings
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }
        networkStatusMonitor?.let { monitor ->
            scope.launch {
                monitor.isOnline.collect { online ->
                    isOnline = online
                    val current = mutableBackupState.value
                    if (current is BackupUiState.ReinstallReview) {
                        mutableBackupState.value = current.copy(isOnline = online)
                    }
                }
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
                    is RestoreResult.Success -> {
                        val candidates = result.reinstallPlan.remoteCandidates.sorted()
                        if (candidates.isEmpty()) {
                            BackupUiState.Complete("Backup restored")
                        } else {
                            BackupUiState.ReinstallReview(
                                candidates = candidates,
                                selected = candidates.toSet(),
                                isOnline = isOnline,
                                restoredBundledCount = result.reinstallPlan.restoredBundledKeys.size,
                            )
                        }
                    }
                    is RestoreResult.Failure -> BackupUiState.Error(result.message)
                }
            }
        }
    }

    fun toggleReinstallCandidate(key: String) {
        val current = mutableBackupState.value as? BackupUiState.ReinstallReview ?: return
        if (key !in current.candidates) return
        mutableBackupState.value = current.copy(
            selected = current.selected.toMutableSet().apply { if (!add(key)) remove(key) },
        )
    }

    fun dismissReinstallReview() {
        if (mutableBackupState.value is BackupUiState.ReinstallReview) {
            mutableBackupState.value = BackupUiState.Complete("Backup restored; optional downloads skipped")
        }
    }

    fun confirmReinstall() {
        val current = mutableBackupState.value as? BackupUiState.ReinstallReview ?: return
        val installer = packageInstaller ?: return
        if (!current.isOnline || current.selected.isEmpty()) return
        latestBackup = scope.launch {
            installer.enqueue(current.selected, PackageAction.INSTALL)
            mutableBackupState.value = BackupUiState.Complete(
                "Backup restored; ${current.selected.size} library download${if (current.selected.size == 1) "" else "s"} queued",
            )
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
