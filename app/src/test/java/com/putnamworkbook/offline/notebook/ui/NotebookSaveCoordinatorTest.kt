package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.NotebookEdit
import com.putnamworkbook.offline.notebook.model.NotebookEditGroup
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NotebookSaveCoordinatorTest {
    @Test
    fun rapidEditsWaitForInactivityThenPersistAsOneOrderedBatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val debounceStarted = CompletableDeferred<Unit>()
        val releaseDebounce = CompletableDeferred<Unit>()
        val batches = mutableListOf<List<String>>()
        val coordinator = NotebookSaveCoordinator(
            scope = scope,
            wait = {
                debounceStarted.complete(Unit)
                releaseDebounce.await()
            },
            persist = { _, edits -> batches += edits.map { it.newStrokes.single().id } },
        )

        (1..10).forEach { coordinator.enqueue("page", drawEdit("stroke-$it")) }
        debounceStarted.await()
        assertTrue(batches.isEmpty())

        releaseDebounce.complete(Unit)
        coordinator.awaitIdle()

        assertEquals(listOf((1..10).map { "stroke-$it" }), batches)
        scope.cancel()
    }

    @Test
    fun failedBatchRemainsPendingAndExplicitFlushRetriesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var attempts = 0
        val coordinator = NotebookSaveCoordinator(
            scope = scope,
            wait = { },
            persist = { _, _ ->
                attempts += 1
                if (attempts == 1) throw IOException("injected")
            },
        )
        coordinator.enqueue("page", drawEdit("stroke"))
        repeat(100) {
            if (attempts == 1) return@repeat
            delay(5)
        }
        assertEquals(1, attempts)
        assertTrue(coordinator.hasPendingEdits)

        coordinator.flush()

        assertEquals(2, attempts)
        assertTrue(!coordinator.hasPendingEdits)
        scope.cancel()
    }

    @Test
    fun awaitIdlePropagatesPersistentFailureAndKeepsBatchForRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val waitForever = CompletableDeferred<Unit>()
        val coordinator = NotebookSaveCoordinator(
            scope = scope,
            wait = { waitForever.await() },
            persist = { _, _ -> throw IOException("disk unavailable") },
        )
        coordinator.enqueue("page", drawEdit("stroke"))

        val result = runCatching { coordinator.awaitIdle() }

        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(coordinator.hasPendingEdits)
        scope.cancel()
    }

    private fun drawEdit(id: String) = NotebookEdit(
        NotebookEditGroup("edit-$id", listOf(id), emptyList()),
        listOf(
            StrokeRecord(
                id,
                BrushSpec(),
                listOf(StrokeSample(0.5f, 0.5f, 0L, null)),
            ),
        ),
    )
}
