package com.putnamworkbook.offline.feature.settings

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

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableUiState = MutableStateFlow(AppSettings())
    private val loaded = CompletableDeferred<Unit>()
    private val updateMutex = Mutex()
    private var latestUpdate: Job? = null

    val uiState: StateFlow<AppSettings> = mutableUiState.asStateFlow()

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

    suspend fun awaitLoaded() {
        loaded.await()
    }

    suspend fun awaitIdle() {
        latestUpdate?.join()
    }

    fun close() {
        scope.cancel()
    }
}
