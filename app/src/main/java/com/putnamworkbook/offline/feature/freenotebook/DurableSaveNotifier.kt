package com.putnamworkbook.offline.feature.freenotebook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DurableSaveNotifier(
    private val scope: CoroutineScope,
    private val notify: suspend (revision: Long) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val stateLock = Any()
    private val notifyMutex = Mutex()
    private var pendingRevision: Long? = null
    private var deliveredRevision = 0L
    private var worker: Job? = null

    fun enqueue(revision: Long) {
        if (revision <= 0) return
        synchronized(stateLock) {
            if (revision <= deliveredRevision) return
            pendingRevision = maxOf(pendingRevision ?: revision, revision)
            if (worker?.isActive != true) {
                worker = scope.launch { drainUntilFailure() }
            }
        }
    }

    suspend fun awaitIdle(): Result<Unit> {
        worker?.join()
        return drainUntilFailure()
    }

    private suspend fun drainUntilFailure(): Result<Unit> = notifyMutex.withLock {
        while (true) {
            val target = synchronized(stateLock) { pendingRevision } ?: return@withLock Result.success(Unit)
            try {
                notify(target)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onFailure(error)
                return@withLock Result.failure(error)
            }
            synchronized(stateLock) {
                deliveredRevision = maxOf(deliveredRevision, target)
                if ((pendingRevision ?: 0L) <= target) pendingRevision = null
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.success(Unit)
    }
}
