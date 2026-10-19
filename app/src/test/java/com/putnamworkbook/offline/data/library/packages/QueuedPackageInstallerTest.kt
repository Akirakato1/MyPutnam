package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import com.putnamworkbook.offline.data.library.catalog.PublicationCatalog
import com.putnamworkbook.offline.data.library.index.ContentAvailability
import com.putnamworkbook.offline.data.library.index.ContentMetadata
import com.putnamworkbook.offline.data.library.index.ContentSource
import com.putnamworkbook.offline.data.library.index.InstallError
import com.putnamworkbook.offline.data.library.index.InstalledContentIndex
import com.putnamworkbook.offline.data.library.index.InstalledContentSnapshot
import com.putnamworkbook.offline.data.library.index.InstalledPackage
import com.putnamworkbook.offline.data.library.index.LibraryBackupState
import com.putnamworkbook.offline.data.library.index.OperationState
import com.putnamworkbook.offline.data.library.index.PackageAction
import com.putnamworkbook.offline.data.library.index.PackageOperation
import com.putnamworkbook.offline.data.library.index.PackageOperationStore
import com.putnamworkbook.offline.data.library.index.ReinstallPlan
import com.putnamworkbook.offline.data.library.index.ResolvedInstalledContent
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueuedPackageInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun installProgressesToCompleteOnlyAfterDurableActivationAndIndexCommit() = runBlocking {
        val fixture = fixture()
        fixture.installer.enqueue(setOf(KEY_A), PackageAction.INSTALL)
        val operation = fixture.awaitTerminal(KEY_A)

        assertEquals(OperationState.COMPLETE, operation.state)
        assertEquals(KEY_A, fixture.index.activated.single().metadata.key)
        assertEquals(listOf("stage", "activate", "index"), fixture.events)
    }

    @Test
    fun oneFailedBatchItemDoesNotRollBackSuccessfulSibling() = runBlocking {
        val fixture = fixture(failingDownloads = mutableSetOf(KEY_A))
        fixture.installer.enqueue(setOf(KEY_A, KEY_B), PackageAction.INSTALL)
        val failed = fixture.awaitTerminal(KEY_A)
        val complete = fixture.awaitTerminal(KEY_B)

        assertEquals(OperationState.FAILED, failed.state)
        assertEquals(OperationState.COMPLETE, complete.state)
        assertEquals(listOf(KEY_B), fixture.index.activated.map { it.metadata.key })
        assertTrue(fixture.index.failures.any { it.first == KEY_A })
    }

    @Test
    fun failedUpdateRetainsOldActiveContentAndRetryCanSucceed() = runBlocking {
        val fixture = fixture(failingDownloads = mutableSetOf(KEY_A))
        fixture.index.seedDownloaded(KEY_A, "old")
        fixture.installer.enqueue(setOf(KEY_A), PackageAction.UPDATE)
        val failed = fixture.awaitTerminal(KEY_A)
        assertEquals(OperationState.FAILED, failed.state)
        assertEquals("old".repeat(64), fixture.index.snapshot.value.items.single().activeDigest)

        fixture.failingDownloads.clear()
        fixture.installer.retry(failed.id)
        assertEquals(OperationState.COMPLETE, fixture.awaitOperation(failed.id).state)
        assertEquals("a".repeat(64), fixture.index.snapshot.value.items.single().activeDigest)
    }

    @Test
    fun deleteDeactivatesBeforeStorageRemovalAndRestoreVerifiesBundleFirst() = runBlocking {
        val fixture = fixture()
        fixture.index.seedDownloaded(KEY_A, "a")
        fixture.installer.enqueue(setOf(KEY_A), PackageAction.DELETE)
        assertEquals(OperationState.COMPLETE, fixture.awaitTerminal(KEY_A, PackageAction.DELETE).state)
        assertEquals(listOf("index-remove", "delete"), fixture.events.takeLast(2))

        fixture.events.clear()
        fixture.index.seedBundledRemoved(KEY_A)
        fixture.installer.enqueue(setOf(KEY_A), PackageAction.RESTORE)
        assertEquals(OperationState.COMPLETE, fixture.awaitTerminal(KEY_A, PackageAction.RESTORE).state)
        assertEquals(listOf("bundle-verify", "index-restore"), fixture.events.takeLast(2))
    }

    @Test
    fun cancelStopsRunningDownloadAtSafeCheckpoint() = runBlocking {
        val operations = FakeOperationStore()
        val events = mutableListOf<String>()
        val index = FakeIndex(events)
        val root = temporaryFolder.newFolder().toPath()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val downloader = object : PackageDownloader {
            override suspend fun recoverInterruptedState() = Unit
            override suspend fun download(entry: CatalogInstallEntry, onProgress: suspend (Long, Long) -> Unit): DownloadedPackage {
                started.complete(Unit)
                release.await()
                error("cancelled download resumed unexpectedly")
            }
        }
        val installer = QueuedPackageInstaller(
            FakeCatalogSource(mapOf(KEY_A to entry(KEY_A, "a"))),
            downloader,
            FakeStore(root, events),
            index,
            operations,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        installer.enqueue(setOf(KEY_A), PackageAction.INSTALL)
        started.await()
        val operation = operations.state.value.single()
        installer.cancel(operation.id)
        repeat(200) {
            if (operations.state.value.single().state == OperationState.CANCELLED) return@runBlocking
            delay(5)
        }
        error("operation was not cancelled")
    }

    private fun fixture(failingDownloads: MutableSet<String> = mutableSetOf()): Fixture {
        val operations = FakeOperationStore()
        val events = mutableListOf<String>()
        val index = FakeIndex(events)
        val root = temporaryFolder.newFolder().toPath()
        val downloads = FakeDownloader(root, failingDownloads)
        val store = FakeStore(root, events)
        val source = FakeCatalogSource(mapOf(KEY_A to entry(KEY_A, "a"), KEY_B to entry(KEY_B, "b")))
        val installer = QueuedPackageInstaller(
            catalogSource = source,
            downloader = downloads,
            packageStore = store,
            contentIndex = index,
            operationStore = operations,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { System.nanoTime() },
        )
        return Fixture(installer, operations, index, failingDownloads, events)
    }

    private fun entry(key: String, digest: String) = CatalogInstallEntry(
        metadata = metadata(key),
        descriptor = PackageDescriptor(
            CatalogContentType.PROBLEM, digest.repeat(64), 1, 3, 1,
            "packages/problem/$digest.zip", 3, 3, digest.repeat(64), 1,
        ),
    )

    private fun metadata(key: String) = ContentMetadata(
        key, CatalogContentType.PROBLEM, key.substringAfterLast('/'), "putnam", "2025", key.substringAfterLast('/'),
        listOf("algebra"), null, null, emptyList(), emptyList(), 1, true,
    )

    private suspend fun Fixture.awaitTerminal(key: String, action: PackageAction? = null): PackageOperation {
        repeat(200) {
            operations.state.value.lastOrNull {
                it.contentKey == key && (action == null || it.action == action) && it.state in TERMINAL
            }?.let { return it }
            delay(5)
        }
        error("operation did not finish")
    }

    private suspend fun Fixture.awaitOperation(id: String): PackageOperation {
        repeat(200) {
            operations.state.value.singleOrNull { it.id == id && it.state in TERMINAL }?.let { return it }
            delay(5)
        }
        error("operation did not finish")
    }

    private data class Fixture(
        val installer: QueuedPackageInstaller,
        val operations: FakeOperationStore,
        val index: FakeIndex,
        val failingDownloads: MutableSet<String>,
        val events: MutableList<String>,
    )

    private class FakeCatalogSource(private val entries: Map<String, CatalogInstallEntry>) : PackageCatalogSource {
        override suspend fun entry(key: String) = entries[key]
    }

    private class FakeDownloader(
        private val root: java.nio.file.Path,
        val failures: MutableSet<String>,
    ) : PackageDownloader {
        override suspend fun recoverInterruptedState() = Unit
        override suspend fun download(entry: CatalogInstallEntry, onProgress: suspend (Long, Long) -> Unit): DownloadedPackage {
            if (entry.metadata.key in failures) error("network down")
            val path = root.resolve("download-${entry.metadata.problemId}-${System.nanoTime()}")
            Files.write(path, byteArrayOf(1, 2, 3))
            onProgress(3, 3)
            return DownloadedPackage(path)
        }
    }

    private class FakeStore(private val root: java.nio.file.Path, private val events: MutableList<String>) : PackageStore {
        override suspend fun recoverInterruptedState() = Unit
        override suspend fun stage(contentKey: String, descriptor: PackageDescriptor, input: java.io.InputStream): StagedPackage {
            events += "stage"
            input.readBytes()
            val staging = Files.createDirectory(root.resolve("stage-${System.nanoTime()}"))
            val payload = Files.createDirectory(staging.resolve("payload"))
            return StagedPackage(contentKey, descriptor, PackageEnvelope(1, descriptor.contentType, contentKey, 3, 1, 1, 3, emptyList(), descriptor.packageRevision), staging, payload)
        }
        override suspend fun activate(staged: StagedPackage): PackageLocation {
            events += "activate"
            val target = Files.createDirectory(root.resolve(staged.descriptor.sha256))
            return PackageLocation(target, staged.descriptor.sha256)
        }
        override suspend fun ensureBundledExtracted(contentKey: String, descriptor: PackageDescriptor): PackageLocation {
            events += "bundle-verify"
            return PackageLocation(root.resolve(descriptor.sha256), descriptor.sha256)
        }
        override suspend fun delete(location: PackageLocation) { events += "delete" }
    }

    private class FakeOperationStore : PackageOperationStore {
        val state = MutableStateFlow<List<PackageOperation>>(emptyList())
        override fun observeOperations(): Flow<List<PackageOperation>> = state
        override suspend fun operation(id: String) = state.value.singleOrNull { it.id == id }
        override suspend fun upsertOperation(operation: PackageOperation) {
            state.value = state.value.filterNot { it.id == operation.id } + operation
        }
        override suspend fun recoverInterruptedOperations(now: Long) = Unit
    }

    private class FakeIndex(private val events: MutableList<String>) : InstalledContentIndex {
        val snapshot = MutableStateFlow(InstalledContentSnapshot(0, emptyList()))
        val activated = mutableListOf<InstalledPackage>()
        val failures = mutableListOf<Pair<String, InstallError>>()
        override fun observe(): Flow<InstalledContentSnapshot> = snapshot
        override suspend fun reconcileCatalog(catalog: PublicationCatalog) = Unit
        override suspend fun reconcileBundled(catalog: PublicationCatalog) = Unit
        override suspend fun activateDownloaded(entry: InstalledPackage) {
            events += "index"
            activated += entry
            snapshot.value = InstalledContentSnapshot(snapshot.value.revision + 1, listOf(resolved(entry.metadata, entry.descriptor.sha256, ContentSource.DOWNLOADED, entry.location)))
        }
        override suspend fun logicallyRemove(key: String) {
            events += "index-remove"
            snapshot.value = InstalledContentSnapshot(snapshot.value.revision + 1, snapshot.value.items.map { it.copy(availability = ContentAvailability.NOT_INSTALLED, activeSource = null, activeDigest = null, activeLocation = null) })
        }
        override suspend fun restoreBundled(key: String) {
            events += "index-restore"
            snapshot.value = InstalledContentSnapshot(snapshot.value.revision + 1, snapshot.value.items.map { it.copy(availability = ContentAvailability.INSTALLED, activeSource = ContentSource.BUNDLED, activeDigest = it.bundled?.descriptor?.sha256, activeLocation = it.bundled?.location) })
        }
        override suspend fun quarantine(key: String, digest: String, error: InstallError) = Unit
        override suspend fun recordFailure(key: String, error: InstallError) { failures += key to error }
        override suspend fun exportBackupState() = LibraryBackupState(emptySet(), emptySet(), emptyMap())
        override suspend fun applyBackupState(state: LibraryBackupState) = ReinstallPlan(emptySet(), emptySet())
        fun seedDownloaded(key: String, digest: String) {
            val entry = entryFor(key, digest)
            snapshot.value = InstalledContentSnapshot(1, listOf(resolved(entry.metadata, entry.descriptor.sha256, ContentSource.DOWNLOADED, rootLocation(digest))))
        }
        fun seedBundledRemoved(key: String) {
            val entry = entryFor(key, "a")
            val stored = com.putnamworkbook.offline.data.library.index.StoredPackage(ContentSource.BUNDLED, entry.descriptor, "asset://bundle")
            snapshot.value = InstalledContentSnapshot(1, listOf(resolved(entry.metadata, null, null, null).copy(bundled = stored, availability = ContentAvailability.LOGICALLY_REMOVED)))
        }
        private fun entryFor(key: String, digest: String) = CatalogInstallEntry(
            ContentMetadata(
                key, CatalogContentType.PROBLEM, key.substringAfterLast('/'), "putnam", "2025", key.substringAfterLast('/'),
                listOf("algebra"), null, null, emptyList(), emptyList(), 1, true,
            ),
            PackageDescriptor(CatalogContentType.PROBLEM, digest.repeat(64), 1, 3, 1, "packages/$digest.zip", 3, 3, digest.repeat(64), 1),
        )
        private fun rootLocation(digest: String) = "/packages/${digest.repeat(64)}"
        private fun resolved(metadata: ContentMetadata, digest: String?, source: ContentSource?, location: String?) = ResolvedInstalledContent(
            metadata, null, null, if (source == null) ContentAvailability.NOT_INSTALLED else ContentAvailability.INSTALLED,
            source, digest, location, null, null, null,
        )
    }

    companion object {
        const val KEY_A = "putnam/2025/A1"
        const val KEY_B = "putnam/2025/A2"
        val TERMINAL = setOf(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)
    }
}
