package com.putnamworkbook.offline.freeworkspace

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.notebook.storage.AtomicFileWriter
import com.putnamworkbook.offline.notebook.storage.DurableAtomicFileWriter
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

class FileFreeWorkspaceRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createListsAndOpensOnePageWorkspace() = runBlocking {
        val fixture = fixture()

        val created = fixture.repository.create("  Scratch  ", PaperStyle.SQUARE_GRID).success()
        val listed = fixture.repository.list().success()
        val notebook = fixture.notebooks.loadExisting(fixture.repository.notebookKey(created.id))

        assertEquals("Scratch", created.name)
        assertEquals(listOf(FreeWorkspaceEntry(created, FreeWorkspaceStorageState.READY)), listed)
        assertEquals(PaperStyle.SQUARE_GRID, notebook!!.pages.single().paperStyle)
    }

    @Test
    fun listSortsByModifiedNameThenUuid() = runBlocking {
        val ids = ArrayDeque(
            listOf(
                uuid("3"),
                uuid("2"),
                uuid("1"),
            ),
        )
        val fixture = fixture(idFactory = { ids.removeFirst() })
        fixture.clock.value = 10
        fixture.repository.create("Zulu", PaperStyle.BLANK).success()
        fixture.repository.create("alpha", PaperStyle.BLANK).success()
        fixture.repository.create("Beta", PaperStyle.BLANK).success()

        assertEquals(
            listOf("alpha", "Beta", "Zulu"),
            fixture.repository.list().success().map { it.record.name },
        )

        fixture.clock.value = 20
        fixture.repository.markModified(uuid("3")).success()
        assertEquals(
            listOf("Zulu", "alpha", "Beta"),
            fixture.repository.list().success().map { it.record.name },
        )
    }

    @Test
    fun missingDirectoryListsAsDamaged() = runBlocking {
        val fixture = fixture()
        val created = fixture.repository.create("Damaged", PaperStyle.BLANK).success()
        NioFreeWorkspaceFileOps.deleteRecursively(fixture.workspaceDirectory(created.id))

        assertEquals(
            FreeWorkspaceStorageState.MISSING,
            fixture.repository.list().success().single().storageState,
        )
    }

    @Test
    fun renameChangesOnlyMetadata() = runBlocking {
        val fixture = fixture()
        val created = fixture.repository.create("Before", PaperStyle.BLANK).success()
        val manifest = fixture.workspaceDirectory(created.id).resolve("manifest.bin")
        val before = Files.readAllBytes(manifest)
        fixture.clock.value = 30

        val renamed = fixture.repository.rename(created.id, "After").success()

        assertEquals("After", renamed.name)
        assertEquals(30, renamed.modifiedAtEpochMillis)
        assertTrue(before.contentEquals(Files.readAllBytes(manifest)))
    }

    @Test
    fun duplicateClonesAndUsesNewIdentity() = runBlocking {
        val ids = ArrayDeque(listOf(uuid("1"), uuid("2")))
        val fixture = fixture(idFactory = { ids.removeFirst() })
        val source = fixture.repository.create("Original", PaperStyle.SQUARE_GRID).success()
        val sourceDocument = fixture.notebooks.loadExisting(fixture.repository.notebookKey(source.id))!!
        fixture.clock.value = 40

        val copy = fixture.repository.duplicate(source.id, "Copy").success()
        val copyDocument = fixture.notebooks.loadExisting(fixture.repository.notebookKey(copy.id))!!

        assertEquals(uuid("2"), copy.id)
        assertEquals(sourceDocument.pages, copyDocument.pages)
        assertEquals(fixture.repository.notebookKey(copy.id), copyDocument.key)
    }

    @Test
    fun deleteRemovesCatalogAndNotebook() = runBlocking {
        val fixture = fixture()
        val created = fixture.repository.create("Disposable", PaperStyle.BLANK).success()

        fixture.repository.delete(created.id).success()

        assertTrue(fixture.repository.list().success().isEmpty())
        assertFalse(Files.exists(fixture.workspaceDirectory(created.id)))
    }

    @Test
    fun markModifiedUsesInjectedClock() = runBlocking {
        val fixture = fixture()
        val created = fixture.repository.create("Timed", PaperStyle.BLANK).success()
        fixture.clock.value = 99

        val changed = fixture.repository.markModified(created.id).success()

        assertEquals(99, changed.modifiedAtEpochMillis)
    }

    @Test
    fun catalogFailureRollsBackCreateAndDuplicate() = runBlocking {
        val fixture = fixture()
        val source = fixture.repository.create("Source", PaperStyle.BLANK).success()
        fixture.failCatalogWrites.value = true

        assertTrue(fixture.repository.create("Failed create", PaperStyle.BLANK) is FreeWorkspaceResult.Failure)
        assertTrue(fixture.repository.duplicate(source.id, "Failed copy") is FreeWorkspaceResult.Failure)

        val ordinaryDirectories = Files.list(fixture.libraryRoot).use { paths ->
            paths.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.toList()
        }
        assertEquals(listOf(source.id.toString()), ordinaryDirectories.map { it.fileName.toString() })
    }

    @Test
    fun deleteCatalogFailureRestoresTombstoneBeforeDeletion() = runBlocking {
        val fixture = fixture()
        val created = fixture.repository.create("Keep", PaperStyle.BLANK).success()
        fixture.failCatalogWrites.value = true

        assertTrue(fixture.repository.delete(created.id) is FreeWorkspaceResult.Failure)

        assertTrue(Files.isDirectory(fixture.workspaceDirectory(created.id)))
        fixture.failCatalogWrites.value = false
        assertEquals("Keep", fixture.repository.list().success().single().record.name)
    }

    @Test
    fun tombstoneCleanupFailureLeavesRecoverableTombstone() = runBlocking {
        val root = temporaryFolder.newFolder("cleanup-failure").toPath()
        val notebooks = FileNotebookRepository(root)
        val baseOps = NioFreeWorkspaceFileOps
        val failingOps = object : FreeWorkspaceFileOps by baseOps {
            override fun deleteRecursively(path: Path) {
                if (path.fileName.toString().startsWith(".delete-")) throw IOException("injected cleanup failure")
                baseOps.deleteRecursively(path)
            }
        }
        val repository = FileFreeWorkspaceRepository(
            root = root,
            notebookRepository = notebooks,
            clock = EpochMillisClock { 10 },
            fileOps = failingOps,
        )
        val created = repository.create("Delete", PaperStyle.BLANK).success()

        repository.delete(created.id).success()
        val library = root.resolve("__local__/free-workspaces")
        assertTrue(Files.list(library).use { it.anyMatch { path -> path.fileName.toString().startsWith(".delete-") } })

        val recovered = FileFreeWorkspaceRepository(root, notebooks, clock = EpochMillisClock { 20 })
        assertTrue(recovered.list().success().isEmpty())
        assertFalse(Files.list(library).use { it.anyMatch { path -> path.fileName.toString().startsWith(".delete-") } })
    }

    @Test
    fun startupReconcilesStagingOrphansAndTombstones() = runBlocking {
        val fixture = fixture()
        val created = fixture.repository.create("Recover", PaperStyle.BLANK).success()
        val tombstone = fixture.libraryRoot.resolve(".delete-${created.id}-${UUID.randomUUID()}")
        Files.move(fixture.workspaceDirectory(created.id), tombstone, StandardCopyOption.ATOMIC_MOVE)
        val abandonedStage = fixture.libraryRoot.resolve(".stage-abandoned")
        Files.createDirectories(abandonedStage)
        Files.writeString(abandonedStage.resolve("partial"), "x")

        val restarted = FileFreeWorkspaceRepository(fixture.root, fixture.notebooks, clock = fixture.clock)
        val listed = restarted.list().success()

        assertEquals(FreeWorkspaceStorageState.READY, listed.single().storageState)
        assertTrue(Files.isDirectory(fixture.workspaceDirectory(created.id)))
        assertFalse(Files.exists(tombstone))
        assertFalse(Files.exists(abandonedStage))
    }

    @Test
    fun missingCatalogMeansEmptyLibrary() = runBlocking {
        assertTrue(fixture().repository.list().success().isEmpty())
    }

    @Test
    fun corruptCatalogIsPreservedAndReported() = runBlocking {
        val fixture = fixture()
        Files.createDirectories(fixture.libraryRoot)
        Files.writeString(fixture.libraryRoot.resolve("catalog.json"), "not json")

        assertTrue(fixture.repository.list() is FreeWorkspaceResult.Failure)
        assertFalse(Files.exists(fixture.libraryRoot.resolve("catalog.json")))
        assertNotNull(Files.list(fixture.libraryRoot).use { paths ->
            paths.filter { it.fileName.toString().startsWith("catalog.json.corrupt") }.findFirst().orElse(null)
        })
        assertTrue(fixture.repository.list() is FreeWorkspaceResult.Failure)
    }

    private fun fixture(idFactory: () -> UUID = { uuid("1") }): Fixture {
        val root = temporaryFolder.newFolder().toPath()
        val notebooks = FileNotebookRepository(root)
        val clock = MutableClock(10)
        val failCatalogWrites = MutableFlag(false)
        val durableWriter = DurableAtomicFileWriter()
        val writer = AtomicFileWriter { target, bytes ->
            if (failCatalogWrites.value && target.fileName.toString() == "catalog.json") {
                throw IOException("injected catalog failure")
            }
            durableWriter.write(target, bytes)
        }
        return Fixture(
            root,
            notebooks,
            clock,
            failCatalogWrites,
            FileFreeWorkspaceRepository(
                root = root,
                notebookRepository = notebooks,
                writer = writer,
                clock = clock,
                ioDispatcher = Dispatchers.Unconfined,
                idFactory = idFactory,
            ),
        )
    }

    private data class Fixture(
        val root: Path,
        val notebooks: FileNotebookRepository,
        val clock: MutableClock,
        val failCatalogWrites: MutableFlag,
        val repository: FileFreeWorkspaceRepository,
    ) {
        val libraryRoot: Path = root.resolve("__local__/free-workspaces")
        fun workspaceDirectory(id: UUID): Path = libraryRoot.resolve(id.toString())
    }

    private class MutableClock(var value: Long) : EpochMillisClock {
        override fun now(): Long = value
    }

    private data class MutableFlag(var value: Boolean)

    @Suppress("UNCHECKED_CAST")
    private fun <T> FreeWorkspaceResult<T>.success(): T =
        (this as FreeWorkspaceResult.Success<T>).value

    private fun uuid(last: String): UUID = UUID.fromString(
        "00000000-0000-0000-0000-${last.padStart(12, '0')}",
    )
}
