package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.notebook.model.NotebookEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NotebookSaveCoordinator(
    private val scope: CoroutineScope,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val persist: suspend (pageId: String, edits: List<NotebookEdit>) -> Unit,
    private val onSaving: (revision: Long) -> Unit = {},
    private val onSaved: (revision: Long) -> Unit = {},
    private val onFailure: (revision: Long, error: Exception) -> Unit = { _, _ -> },
    private val debounceMillis: Long = 350L,
) {
    private data class PendingEdit(
        val revision: Long,
        val pageId: String,
        val edit: NotebookEdit,
    )

    private val stateLock = Any()
    private val flushMutex = Mutex()
    private val pending = mutableListOf<PendingEdit>()
    private var nextRevision = 0L
    private var debounceJob: Job? = null
    private var automaticFlushJob: Job? = null

    val hasPendingEdits: Boolean
        get() = synchronized(stateLock) { pending.isNotEmpty() }

    fun enqueue(pageId: String, edit: NotebookEdit): Long {
        val revision = synchronized(stateLock) {
            nextRevision += 1
            pending += PendingEdit(nextRevision, pageId, edit)
            nextRevision
        }
        scheduleDebouncedFlush()
        return revision
    }

    suspend fun flush() {
        debounceJob?.cancel()
        debounceJob = null
        flushPending()
    }

    suspend fun awaitIdle() {
        debounceJob?.cancel()
        debounceJob = null
        try {
            flushPending()
        } catch (_: Exception) {
            // The failure is reported through onFailure and the edits remain pending for retry.
        }
        automaticFlushJob?.join()
    }

    fun cancel() {
        debounceJob?.cancel()
        automaticFlushJob?.cancel()
    }

    private fun scheduleDebouncedFlush() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            wait(debounceMillis)
            automaticFlushJob = scope.launch {
                try {
                    flushPending()
                } catch (_: Exception) {
                    // The pending queue and error callback carry failure state until an explicit retry.
                }
            }
        }
    }

    private suspend fun flushPending() {
        flushMutex.withLock {
            while (true) {
                val batch = synchronized(stateLock) {
                    val first = pending.firstOrNull()
                    if (first == null) emptyList() else pending.takeWhile { it.pageId == first.pageId }
                }
                if (batch.isEmpty()) return@withLock
            val revision = batch.last().revision
            onSaving(revision)
            try {
                persist(batch.first().pageId, batch.map { it.edit })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(revision, error)
                throw error
            }
            synchronized(stateLock) {
                repeat(batch.size) { pending.removeAt(0) }
            }
            onSaved(revision)
        }
    }
    }
}
