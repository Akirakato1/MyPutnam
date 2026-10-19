package com.putnamworkbook.offline.feature.library

import com.putnamworkbook.offline.data.library.catalog.*
import com.putnamworkbook.offline.data.library.index.*
import com.putnamworkbook.offline.data.library.network.NetworkStatusMonitor
import com.putnamworkbook.offline.data.library.packages.PackageInstaller
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProblemLibraryViewModelTest {
    @Test
    fun filtersProblemsAndReferencesAndMapsInstallActions() = runBlocking {
        val catalog = sampleCatalog()
        val store = FakeCatalogStore(catalog)
        val index = FakeLibraryIndex(
            InstalledContentSnapshot(
                1,
                listOf(installedProblem(catalog.problems.first()), removedBundledReference(catalog.references.first())),
            ),
        )
        val installer = RecordingInstaller()
        val viewModel = ProblemLibraryViewModel(
            catalogStore = store,
            installedContentIndex = index,
            packageInstaller = installer,
            progressRepository = EmptyLibraryProgress,
            networkStatusMonitor = FakeNetworkStatus(true),
            currentAppVersionCode = 4,
            refreshCatalog = {},
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        waitUntil { viewModel.uiState.value.allEntries.size == 3 }

        viewModel.setTopic("algebra")
        assertEquals(listOf("putnam/2025/A1"), viewModel.uiState.value.visibleEntries.map { it.key })
        assertEquals(LibraryEntryStatus.UPDATE_AVAILABLE, viewModel.uiState.value.visibleEntries.single().status)

        viewModel.setContentSegment(LibraryContentSegment.MATH_REFERENCE)
        viewModel.setTopic(null)
        viewModel.setQuery("totient")
        assertEquals(listOf("reference/number-theory/euler-totient"), viewModel.uiState.value.visibleEntries.map { it.key })
        assertEquals(LibraryEntryStatus.AVAILABLE, viewModel.uiState.value.visibleEntries.single().status)

        viewModel.requestItemAction("reference/number-theory/euler-totient")
        waitUntil { installer.calls.isNotEmpty() }
        assertEquals(setOf("reference/number-theory/euler-totient"), installer.calls.single().first)
        assertEquals(PackageAction.RESTORE, installer.calls.single().second)
        viewModel.close()
    }

    @Test
    fun offlineDisablesNetworkActionsButAllowsDeleteAfterConfirmation() = runBlocking {
        val catalog = sampleCatalog()
        val store = FakeCatalogStore(catalog)
        val downloaded = StoredPackage(
            ContentSource.DOWNLOADED,
            catalog.problems.first().packageDescriptor,
            "downloads/putnam/2025/A1",
        )
        val installed = installedProblem(catalog.problems.first()).copy(
            downloaded = downloaded,
            bundled = null,
            activeSource = ContentSource.DOWNLOADED,
            activeDigest = downloaded.descriptor.sha256,
            activeLocation = downloaded.location,
        )
        val index = FakeLibraryIndex(InstalledContentSnapshot(1, listOf(installed)))
        val installer = RecordingInstaller()
        val viewModel = ProblemLibraryViewModel(
            store, index, installer, EmptyLibraryProgress, FakeNetworkStatus(false), 4, {},
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        waitUntil { viewModel.uiState.value.allEntries.isNotEmpty() }
        val available = viewModel.uiState.value.allEntries.first { it.key.endsWith("A2") }
        assertFalse(available.actionEnabled)

        viewModel.requestItemAction("putnam/2025/A1")
        assertNotNull(viewModel.uiState.value.confirmation)
        viewModel.confirmAction()
        waitUntil { installer.calls.isNotEmpty() }
        assertEquals(PackageAction.DELETE, installer.calls.single().second)
        assertTrue(viewModel.uiState.value.confirmation == null)
        viewModel.close()
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        repeat(500) { if (predicate()) return else delay(5) }
        error("Timed out")
    }
}

private fun sampleCatalog(): PublicationCatalog {
    fun descriptor(type: CatalogContentType, revision: String, minApp: Int = 1) = PackageDescriptor(
        type, revision, 1, 1, 1, "packages/$revision.zip", 1024, 2048,
        revision.padEnd(64, '0').take(64), minApp,
    )
    val rights = CatalogRights("https://example.com", "Example", null, "permission", "allowed")
    return PublicationCatalog(
        1, "catalog", "a".repeat(64), "2026-01-01T00:00:00Z", 1,
        listOf(CatalogCollection("putnam", "Putnam", "competition", 0)),
        listOf(CatalogTopic("algebra", "Algebra", 0), CatalogTopic("number-theory", "Number Theory", 1)),
        listOf(CatalogReferenceCategory("number-theory", "Number Theory", 0, listOf("NT"))),
        listOf(
            CatalogProblem("putnam/2025/A1", "putnam", "2025", "A1", "A1", 1, listOf("algebra"), rights, descriptor(CatalogContentType.PROBLEM, "new-a1")),
            CatalogProblem("putnam/2025/A2", "putnam", "2025", "A2", "A2", 2, listOf("number-theory"), rights, descriptor(CatalogContentType.PROBLEM, "a2", 9)),
        ),
        listOf(
            CatalogReference("reference/number-theory/euler-totient", "number-theory", "euler-totient", "Euler totient function", 1, listOf("phi", "totient"), listOf("coprime"), rights, descriptor(CatalogContentType.REFERENCE, "phi")),
        ),
    )
}

private fun installedProblem(problem: CatalogProblem): ResolvedInstalledContent {
    val old = problem.packageDescriptor.copy(packageRevision = "old", sha256 = "f".repeat(64))
    val stored = StoredPackage(ContentSource.BUNDLED, old, "bundled/${problem.key}")
    return ResolvedInstalledContent(
        ContentMetadata(problem.key, CatalogContentType.PROBLEM, problem.label, problem.collectionId, problem.setId, problem.problemId, problem.topics, null, null, emptyList(), emptyList(), problem.ordinal, true),
        stored, null, ContentAvailability.INSTALLED, ContentSource.BUNDLED, old.sha256, stored.location, null, null, null,
    )
}

private fun removedBundledReference(reference: CatalogReference): ResolvedInstalledContent {
    val stored = StoredPackage(ContentSource.BUNDLED, reference.packageDescriptor, "bundled/${reference.key}")
    return ResolvedInstalledContent(
        ContentMetadata(reference.key, CatalogContentType.REFERENCE, reference.title, null, null, null, emptyList(), reference.categoryId, reference.articleId, reference.aliases, reference.keywords, reference.displayOrder, true),
        stored, null, ContentAvailability.LOGICALLY_REMOVED, null, null, null, null, null, null,
    )
}

private class FakeCatalogStore(catalog: PublicationCatalog) : CatalogStore {
    override val state = MutableStateFlow(CatalogState(catalog = catalog, embeddedCatalog = catalog, source = CatalogSource.EMBEDDED))
    override suspend fun initialize() = Unit
    override suspend fun commitRemote(bytes: ByteArray, validators: CatalogValidators, refreshedAt: Long) = Unit
    override suspend fun recordNotModified(validators: CatalogValidators, refreshedAt: Long) = Unit
    override suspend fun recordFailure(message: String, attemptedAt: Long) = Unit
}

private class FakeLibraryIndex(initial: InstalledContentSnapshot) : InstalledContentIndex {
    private val snapshot = MutableStateFlow(initial)
    override fun observe(): Flow<InstalledContentSnapshot> = snapshot
    override suspend fun reconcileCatalog(catalog: PublicationCatalog) = Unit
    override suspend fun reconcileBundled(catalog: PublicationCatalog) = Unit
    override suspend fun activateDownloaded(entry: InstalledPackage) = Unit
    override suspend fun logicallyRemove(key: String) = Unit
    override suspend fun restoreBundled(key: String) = Unit
    override suspend fun quarantine(key: String, digest: String, error: InstallError) = Unit
    override suspend fun recordFailure(key: String, error: InstallError) = Unit
    override suspend fun exportBackupState() = LibraryBackupState(emptySet(), emptySet(), emptyMap())
    override suspend fun applyBackupState(state: LibraryBackupState) = ReinstallPlan(emptySet(), emptySet())
}

private class RecordingInstaller : PackageInstaller {
    val calls = mutableListOf<Pair<Set<String>, PackageAction>>()
    private val operations = MutableStateFlow<List<PackageOperation>>(emptyList())
    override fun observeOperations(): Flow<List<PackageOperation>> = operations
    override suspend fun enqueue(keys: Set<String>, action: PackageAction) { calls += keys to action }
    override suspend fun retry(operationId: String) = Unit
    override suspend fun cancel(operationId: String) = Unit
}

private class FakeNetworkStatus(online: Boolean) : NetworkStatusMonitor {
    override val isOnline: Flow<Boolean> = MutableStateFlow(online)
}

private object EmptyLibraryProgress : ProgressRepository {
    override fun observe(key: ProblemKey): Flow<ProblemProgress> = MutableStateFlow(ProblemProgress.untouched(key))
    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = MutableStateFlow(emptyMap())
    override fun observeRecent(): Flow<ProblemProgress?> = MutableStateFlow(null)
    override suspend fun markOpened(key: ProblemKey) = Unit
    override suspend fun revealNextHint(key: ProblemKey) = 0
    override suspend fun revealSolution(key: ProblemKey) = Unit
    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = Unit
    override suspend fun setNotebookState(key: ProblemKey, pageCount: Int, pageIndex: Int, referenceTab: ReferenceTab) = Unit
}
