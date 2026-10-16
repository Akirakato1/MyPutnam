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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FreeWorkspaceListViewModelTest {
    @Test
    fun initialLoadAndCreateSuccessReturnNewUuid() = runBlocking {
        val repository = FakeFreeWorkspaceRepository()
        val opened = mutableListOf<UUID>()
        val viewModel = FreeWorkspaceListViewModel(repository, testScope(), opened::add)
        waitUntil { !viewModel.uiState.value.isLoading }

        viewModel.requestCreate()
        viewModel.updateName("Scratch")
        viewModel.submitName()
        waitUntil { opened.isNotEmpty() }

        assertEquals(repository.records.single().id, opened.single())
        assertEquals("Scratch", viewModel.uiState.value.entries.single().record.name)
        assertNull(viewModel.uiState.value.nameDialog)
        viewModel.close()
    }

    @Test
    fun renameDoesNotChangeIdentityAndRefreshesList() = runBlocking {
        val repository = FakeFreeWorkspaceRepository(listOf(record("Before", 1)))
        val viewModel = FreeWorkspaceListViewModel(repository, testScope())
        waitUntil { !viewModel.uiState.value.isLoading }

        viewModel.requestRename(record("Before", 1).id)
        assertEquals("Before", viewModel.uiState.value.nameDialog!!.value)
        viewModel.updateName("After")
        viewModel.submitName()
        waitUntil { viewModel.uiState.value.nameDialog == null }

        assertEquals(record("After", 1).id, viewModel.uiState.value.entries.single().record.id)
        assertEquals("After", viewModel.uiState.value.entries.single().record.name)
        viewModel.close()
    }

    @Test
    fun deleteRequiresConfirmation() = runBlocking {
        val repository = FakeFreeWorkspaceRepository(listOf(record("Keep", 1)))
        val viewModel = FreeWorkspaceListViewModel(repository, testScope())
        waitUntil { !viewModel.uiState.value.isLoading }

        viewModel.requestDelete(record("Keep", 1).id)
        assertEquals("Keep", viewModel.uiState.value.deleteConfirmation!!.name)
        assertEquals(1, repository.records.size)
        viewModel.confirmDelete()
        waitUntil { viewModel.uiState.value.deleteConfirmation == null }

        assertTrue(repository.records.isEmpty())
        assertTrue(viewModel.uiState.value.entries.isEmpty())
        viewModel.close()
    }

    @Test
    fun duplicateNameKeepsDialogOpenAndAdvancesAnimation() = runBlocking {
        val repository = FakeFreeWorkspaceRepository(listOf(record("Taken", 1)))
        val viewModel = FreeWorkspaceListViewModel(repository, testScope())
        waitUntil { !viewModel.uiState.value.isLoading }
        viewModel.requestCreate()
        val before = viewModel.uiState.value.nameErrorAnimationKey

        viewModel.updateName("Taken")
        viewModel.submitName()
        waitUntil { viewModel.uiState.value.nameErrorAnimationKey > before }

        assertEquals(FreeWorkspaceNameError.DUPLICATE, viewModel.uiState.value.nameDialog!!.nameError)
        assertEquals("Taken", viewModel.uiState.value.nameDialog!!.value)
        viewModel.updateName("Available")
        assertNull(viewModel.uiState.value.nameDialog!!.nameError)
        viewModel.close()
    }

    @Test
    fun repositoryFailureRetainsEntriesAndDialog() = runBlocking {
        val existing = record("Existing", 1)
        val repository = FakeFreeWorkspaceRepository(listOf(existing))
        val viewModel = FreeWorkspaceListViewModel(repository, testScope())
        waitUntil { !viewModel.uiState.value.isLoading }
        repository.nextMutationFailure = "storage unavailable"

        viewModel.requestRename(existing.id)
        viewModel.updateName("Changed")
        viewModel.submitName()
        waitUntil { viewModel.uiState.value.nameDialog?.failureMessage != null }

        assertEquals("Existing", viewModel.uiState.value.entries.single().record.name)
        assertEquals("storage unavailable", viewModel.uiState.value.nameDialog!!.failureMessage)
        assertFalse(viewModel.uiState.value.isLoading)
        viewModel.close()
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        repeat(1_000) {
            if (predicate()) return
            delay(5)
        }
        error("Timed out waiting for state")
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
}

private class FakeFreeWorkspaceRepository(initial: List<FreeWorkspaceRecord> = emptyList()) : FreeWorkspaceRepository {
    val records = initial.toMutableList()
    var nextMutationFailure: String? = null
    private var nextId = 10

    override suspend fun list() = FreeWorkspaceResult.Success(
        records.map { FreeWorkspaceEntry(it, FreeWorkspaceStorageState.READY) },
    )

    override suspend fun create(name: String, defaultPaper: PaperStyle): FreeWorkspaceResult<FreeWorkspaceRecord> {
        mutationFailure()?.let { return it }
        if (records.any { it.name.equals(name, ignoreCase = true) }) {
            return FreeWorkspaceResult.NameRejected(FreeWorkspaceNameError.DUPLICATE)
        }
        val record = record(name, nextId++)
        records += record
        return FreeWorkspaceResult.Success(record)
    }

    override suspend fun rename(id: UUID, name: String): FreeWorkspaceResult<FreeWorkspaceRecord> {
        mutationFailure()?.let { return it }
        if (records.any { it.id != id && it.name.equals(name, ignoreCase = true) }) {
            return FreeWorkspaceResult.NameRejected(FreeWorkspaceNameError.DUPLICATE)
        }
        val index = records.indexOfFirst { it.id == id }
        val changed = records[index].copy(name = name, modifiedAtEpochMillis = records[index].modifiedAtEpochMillis + 1)
        records[index] = changed
        return FreeWorkspaceResult.Success(changed)
    }

    override suspend fun duplicate(sourceId: UUID, name: String) =
        FreeWorkspaceResult.Failure("not used")

    override suspend fun delete(id: UUID): FreeWorkspaceResult<Unit> {
        mutationFailure()?.let { return it }
        records.removeAll { it.id == id }
        return FreeWorkspaceResult.Success(Unit)
    }

    override suspend fun markModified(id: UUID) = FreeWorkspaceResult.Failure("not used")
    override fun notebookKey(id: UUID) = ProblemKey("__local__", "free-workspaces", id.toString())

    private fun mutationFailure(): FreeWorkspaceResult.Failure? =
        nextMutationFailure?.let { message ->
            nextMutationFailure = null
            FreeWorkspaceResult.Failure(message)
        }
}

private fun record(name: String, last: Int) = FreeWorkspaceRecord(
    UUID.fromString("00000000-0000-0000-0000-${last.toString().padStart(12, '0')}"),
    name,
    10,
    20,
)
