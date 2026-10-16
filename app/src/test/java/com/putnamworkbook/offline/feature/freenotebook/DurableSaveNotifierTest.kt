package com.putnamworkbook.offline.feature.freenotebook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DurableSaveNotifierTest {
    @Test
    fun coalescesSuccessfulDurableRevisions() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val notified = mutableListOf<Long>()
        val notifier = DurableSaveNotifier(testScope(), notify = { revision ->
            gate.await()
            notified += revision
        })

        notifier.enqueue(1)
        notifier.enqueue(2)
        notifier.enqueue(3)
        gate.complete(Unit)

        assertTrue(notifier.awaitIdle().isSuccess)
        assertEquals(3L, notified.last())
        assertTrue(notified.size <= 2)
    }

    @Test
    fun failedModifiedTimeNotificationRetriesWithoutDuplicatingNotebookEdits() = runBlocking {
        var attempts = 0
        var notebookEdits = 1
        val notifier = DurableSaveNotifier(testScope(), notify = {
            attempts += 1
            if (attempts == 1) throw IOException("catalog unavailable")
        })

        notifier.enqueue(7)
        val result = notifier.awaitIdle()

        assertTrue(result.isSuccess)
        assertEquals(2, attempts)
        assertEquals(1, notebookEdits)
    }

    @Test
    fun awaitIdleWaitsForLatestMetadataWrite() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var completed = 0L
        val notifier = DurableSaveNotifier(testScope(), notify = { revision ->
            gate.await()
            completed = revision
        })
        notifier.enqueue(9)
        val waiter = CompletableDeferred<Boolean>()
        val scope = testScope()
        scope.launch {
            waiter.complete(notifier.awaitIdle().isSuccess)
        }

        assertTrue(!waiter.isCompleted)
        gate.complete(Unit)
        assertTrue(waiter.await())
        assertEquals(9, completed)
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
