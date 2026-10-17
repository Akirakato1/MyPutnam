package com.putnamworkbook.offline.feature.freenotebook

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceEntry
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceNameError
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRecord
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRepository
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceResult
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceStorageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class FreeNotebookEditorCoordinatorTest {
    @Test
    fun opensWorkspaceByUuidAndSaveFlushesBothLayers() = runBlocking {
        val repository = EditorRepository()
        val calls = mutableListOf<String>()
        val notifier = DurableSaveNotifier(testScope(), notify = { calls += "metadata" })
        val coordinator = coordinator(
            repository,
            notifier,
            openNotebook = { calls += "open:${it.problemId}" },
            awaitNotebookIdle = { calls += "notebook" },
        )
        waitUntil { coordinator.uiState.value.record != null }
        notifier.enqueue(1)

        coordinator.save()
        waitUntil { coordinator.uiState.value.statusMessage == "Saved" }

        assertEquals(repository.source.id, coordinator.uiState.value.record!!.id)
        assertEquals("open:${repository.source.id}", calls.first())
        assertTrue("notebook" in calls)
        assertTrue("metadata" in calls)
        coordinator.close()
    }

    @Test
    fun backRemainsInEditorAfterFlushOrMetadataFailure() = runBlocking {
        val repository = EditorRepository()
        var backs = 0
        var notebookFails = true
        val notifier = DurableSaveNotifier(testScope(), notify = {})
        val coordinator = coordinator(
            repository,
            notifier,
            awaitNotebookIdle = {
                if (notebookFails) throw IOException("notebook failed")
            },
            onBackReady = { backs += 1 },
        )
        waitUntil { coordinator.uiState.value.record != null }

        coordinator.back()
        waitUntil { coordinator.uiState.value.errorMessage != null }
        assertEquals(0, backs)
        notebookFails = false
        coordinator.back()
        waitUntil { backs == 1 }
        coordinator.close()
    }

    @Test
    fun saveAsPrefillsExactNameAndDuplicateErrorKeepsDialog() = runBlocking {
        val repository = EditorRepository()
        val coordinator = coordinator(repository, DurableSaveNotifier(testScope(), notify = {}))
        waitUntil { coordinator.uiState.value.record != null }

        coordinator.requestSaveAs()
        waitUntil { coordinator.uiState.value.saveAsDialog != null }
        assertEquals("Exact Name", coordinator.uiState.value.saveAsDialog!!.value)
        coordinator.updateSaveAsName("Taken")
        repository.rejectDuplicate = true
        val before = coordinator.uiState.value.nameErrorAnimationKey
        coordinator.confirmSaveAs()
        waitUntil { coordinator.uiState.value.nameErrorAnimationKey > before }

        assertEquals(FreeWorkspaceNameError.DUPLICATE, coordinator.uiState.value.saveAsDialog!!.nameError)
        assertEquals("Taken", coordinator.uiState.value.saveAsDialog!!.value)
        coordinator.close()
    }

    @Test
    fun successfulSaveAsSwitchesToNewIdentityAndLeavesSource() = runBlocking {
        val repository = EditorRepository()
        var replacement: UUID? = null
        val coordinator = coordinator(
            repository,
            DurableSaveNotifier(testScope(), notify = {}),
            onWorkspaceReplaced = { replacement = it },
        )
        waitUntil { coordinator.uiState.value.record != null }
        coordinator.requestSaveAs()
        waitUntil { coordinator.uiState.value.saveAsDialog != null }
        coordinator.updateSaveAsName("Copy")

        coordinator.confirmSaveAs()
        waitUntil { replacement != null }

        assertEquals(repository.copy.id, replacement)
        assertEquals("Exact Name", repository.source.name)
        assertEquals("Copy", repository.copy.name)
        assertFalse(repository.source.id == repository.copy.id)
        coordinator.close()
    }

    @Test
    fun saveReflushesAnEditThatArrivesDuringMetadataWriteAndReportsItsFailure() = runBlocking {
        val repository = EditorRepository()
        val metadataStarted = CompletableDeferred<Unit>()
        val releaseMetadata = CompletableDeferred<Unit>()
        var notebookRevision = 1L
        var notebookFlushes = 0
        val notifier = DurableSaveNotifier(testScope(), notify = {
            metadataStarted.complete(Unit)
            releaseMetadata.await()
        })
        val coordinator = coordinator(
            repository,
            notifier,
            awaitNotebookIdle = {
                notebookFlushes += 1
                if (notebookFlushes > 1 && notebookRevision == 2L) {
                    throw IOException("new ink failed")
                }
            },
            notebookRevision = { notebookRevision },
        )
        waitUntil { coordinator.uiState.value.record != null }
        notifier.enqueue(1)

        coordinator.save()
        metadataStarted.await()
        notebookRevision = 2L
        releaseMetadata.complete(Unit)
        waitUntil { coordinator.uiState.value.errorMessage != null }

        assertEquals("new ink failed", coordinator.uiState.value.errorMessage)
        assertNull(coordinator.uiState.value.statusMessage)
        assertTrue(notebookFlushes >= 2)
        coordinator.close()
    }

    private fun coordinator(
        repository: EditorRepository,
        notifier: DurableSaveNotifier,
        openNotebook: (ProblemKey) -> Unit = {},
        awaitNotebookIdle: suspend () -> Unit = {},
        notebookRevision: () -> Long = { 0L },
        onBackReady: () -> Unit = {},
        onWorkspaceReplaced: (UUID) -> Unit = {},
    ) = FreeNotebookEditorCoordinator(
        workspaceId = repository.source.id,
        repository = repository,
        notifier = notifier,
        scope = testScope(),
        openNotebook = openNotebook,
        awaitNotebookIdle = awaitNotebookIdle,
        notebookRevision = notebookRevision,
        onBackReady = onBackReady,
        onWorkspaceReplaced = onWorkspaceReplaced,
    )

    private suspend fun waitUntil(predicate: () -> Boolean) {
        repeat(1_000) {
            if (predicate()) return
            delay(5)
        }
        error("Timed out waiting for editor state")
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

private class EditorRepository : FreeWorkspaceRepository {
    val source = record("Exact Name", 1)
    var copy = record("not created", 2)
    var rejectDuplicate = false

    override suspend fun list() = FreeWorkspaceResult.Success(
        listOf(source, copy).filter { it.name != "not created" }
            .map { FreeWorkspaceEntry(it, FreeWorkspaceStorageState.READY) },
    )

    override suspend fun create(name: String, defaultPaper: PaperStyle) = FreeWorkspaceResult.Failure("unused")
    override suspend fun rename(id: UUID, name: String) = FreeWorkspaceResult.Failure("unused")
    override suspend fun duplicate(sourceId: UUID, name: String): FreeWorkspaceResult<FreeWorkspaceRecord> {
        if (rejectDuplicate) return FreeWorkspaceResult.NameRejected(FreeWorkspaceNameError.DUPLICATE)
        copy = copy.copy(name = name)
        return FreeWorkspaceResult.Success(copy)
    }
    override suspend fun delete(id: UUID) = FreeWorkspaceResult.Failure("unused")
    override suspend fun markModified(id: UUID) = FreeWorkspaceResult.Success(source.copy(modifiedAtEpochMillis = 30))
    override fun notebookKey(id: UUID) = ProblemKey("__local__", "free-workspaces", id.toString())
}

private fun record(name: String, last: Int) = FreeWorkspaceRecord(
    UUID.fromString("00000000-0000-0000-0000-${last.toString().padStart(12, '0')}"),
    name,
    10,
    20,
)
