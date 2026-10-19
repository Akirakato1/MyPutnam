package com.putnamworkbook.offline.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.putnamworkbook.offline.PutnamApplication
import com.putnamworkbook.offline.data.user.RoomProgressRepository
import com.putnamworkbook.offline.data.user.RoomSettingsRepository
import com.putnamworkbook.offline.data.user.UserDatabase
import com.putnamworkbook.offline.di.initializeContent
import com.putnamworkbook.offline.domain.model.AppTheme
import com.putnamworkbook.offline.domain.model.Handedness
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceTab
import com.putnamworkbook.offline.feature.workspace.WorkspaceViewModel
import com.putnamworkbook.offline.freeworkspace.EpochMillisClock
import com.putnamworkbook.offline.freeworkspace.FileFreeWorkspaceRepository
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceResult
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import com.putnamworkbook.offline.data.library.index.ReinstallPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private lateinit var application: PutnamApplication
    private lateinit var database: UserDatabase
    private lateinit var testRoot: Path
    private lateinit var notebookRoot: Path

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        application = context.applicationContext as PutnamApplication
        database = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).build()
        testRoot = context.cacheDir.toPath().resolve("backup-restore-${UUID.randomUUID()}")
        notebookRoot = testRoot.resolve("notebooks")
        Files.createDirectories(testRoot)
    }

    @After
    fun tearDown() {
        database.close()
        deleteTree(testRoot)
    }

    @Test
    fun backupRestoreRoundTripsFreeWorkspaceCatalogAndInk() = runBlocking {
        val notebooks = FileNotebookRepository(notebookRoot)
        var now = 100L
        val workspaces = FileFreeWorkspaceRepository(
            notebookRoot,
            notebooks,
            clock = EpochMillisClock { now },
        )
        val first = (workspaces.create("Algebra scratch", PaperStyle.SQUARE_GRID) as FreeWorkspaceResult.Success).value
        now = 200L
        val second = (workspaces.create("Integral ideas", PaperStyle.BLANK) as FreeWorkspaceResult.Success).value
        val firstKey = workspaces.notebookKey(first.id)
        val firstPage = notebooks.loadExisting(firstKey)!!.pages.single()
        val secondPage = notebooks.addPage(firstKey, firstPage.id, PaperStyle.BLANK).pages.last()
        val ink = StrokeRecord(
            id = "backup-free-ink",
            brush = BrushSpec(colorArgb = 0xFF2563EBL, width = 7f),
            samples = listOf(
                StrokeSample(0.1f, 0.2f, 0L, null),
                StrokeSample(0.4f, 0.6f, 12L, 0.8f),
            ),
        )
        notebooks.commitStroke(firstKey, secondPage.id, ink)
        now = 300L
        workspaces.markModified(first.id)
        val expectedEntries = (workspaces.list() as FreeWorkspaceResult.Success).value

        val backup = ByteArrayOutputStream()
        assertTrue(BackupCoordinator(database, notebookRoot).create(backup) is BackupResult.Success)
        workspaces.delete(first.id)
        workspaces.delete(second.id)

        assertTrue(
            BackupCoordinator(database, notebookRoot).validateAndRestore(
                ByteArrayInputStream(backup.toByteArray()),
            ) is RestoreResult.Success,
        )

        val restoredNotebooks = FileNotebookRepository(notebookRoot)
        val restoredWorkspaces = FileFreeWorkspaceRepository(notebookRoot, restoredNotebooks)
        val restoredEntries = (restoredWorkspaces.list() as FreeWorkspaceResult.Success).value
        assertEquals(expectedEntries, restoredEntries)
        assertEquals(listOf(first.id, second.id), restoredEntries.map { it.record.id })
        val restoredDocument = restoredNotebooks.loadExisting(restoredWorkspaces.notebookKey(first.id))!!
        assertEquals(listOf(PaperStyle.SQUARE_GRID, PaperStyle.BLANK), restoredDocument.pages.map { it.paperStyle })
        assertEquals(listOf(ink), restoredNotebooks.loadStrokes(restoredDocument.key, restoredDocument.pages.last().id))
    }

    @Test
    fun fullStateRoundTripsAndInvalidArchiveCannotChangeExistingData() = runBlocking {
        val progress = RoomProgressRepository(database, clock = { 1234L })
        val settings = RoomSettingsRepository(database)
        val notebooks = FileNotebookRepository(notebookRoot)
        val coordinator = BackupCoordinator(database, notebookRoot)
        val key = ProblemKey("putnam", "2025", "A1")

        progress.markOpened(key)
        progress.revealNextHint(key)
        progress.setNotebookState(key, 1, 0, ReferenceTab.ORIGINAL_PAPER)
        settings.update { it.copy(handedness = Handedness.LEFT, theme = AppTheme.DARK) }
        val firstPage = notebooks.loadOrCreate(key, PaperStyle.SQUARE_GRID).pages.single()
        notebooks.addPage(key, firstPage.id, PaperStyle.BLANK)
        val expectedProgress = progress.observe(key).first()
        val expectedSettings = settings.observe().first()
        val expectedNotebookBytes = snapshot(notebookRoot)

        val output = ByteArrayOutputStream()
        assertTrue(coordinator.create(output) is BackupResult.Success)

        progress.markCompleted(key, true)
        settings.update { it.copy(handedness = Handedness.RIGHT, theme = AppTheme.LIGHT) }
        Files.write(notebookRoot.resolve("unwanted-after-backup.bin"), byteArrayOf(9, 9))

        assertTrue(
            coordinator.validateAndRestore(ByteArrayInputStream(output.toByteArray())) is RestoreResult.Success,
        )
        assertEquals(expectedProgress, progress.observe(key).first())
        assertEquals(ReferenceTab.ORIGINAL_PAPER, progress.observe(key).first().activeReferenceTab)
        assertEquals(expectedSettings, settings.observe().first())
        assertSnapshotEquals(expectedNotebookBytes, snapshot(notebookRoot))

        val workspace = WorkspaceViewModel(
            contentRepository = application.container.initializeContent(),
            progressRepository = progress,
            scope = CoroutineScope(Dispatchers.Default),
        )
        workspace.open(key)
        workspace.awaitIdle()
        assertEquals(ReferenceTab.PROBLEM, workspace.uiState.value.selectedReferenceTab)
        val normalizedProgress = progress.observe(key).first()
        assertEquals(ReferenceTab.PROBLEM, normalizedProgress.activeReferenceTab)
        assertEquals(expectedProgress.status, normalizedProgress.status)
        assertEquals(expectedProgress.revealedHintCount, normalizedProgress.revealedHintCount)
        workspace.close()

        val stateBeforeFailure = progress.observe(key).first()
        val settingsBeforeFailure = settings.observe().first()
        val notebooksBeforeFailure = snapshot(notebookRoot)
        val invalid = ByteArrayOutputStream().also {
            BackupArchive().write(mapOf(BackupArchive.USER_DATA_PATH to "not-json".encodeToByteArray()), it)
        }.toByteArray()

        assertTrue(
            coordinator.validateAndRestore(ByteArrayInputStream(invalid)) is RestoreResult.Failure,
        )
        assertEquals(stateBeforeFailure, progress.observe(key).first())
        assertEquals(settingsBeforeFailure, settings.observe().first())
        assertSnapshotEquals(notebooksBeforeFailure, snapshot(notebookRoot))
    }

    @Test
    fun libraryChoicesRoundTripAndReturnReviewedRemoteCandidates() = runBlocking {
        val state = LibraryBackupState(
            desiredKeys = setOf("putnam/2025/A1", "reference/number-theory/euler-totient"),
            logicallyRemovedBundledKeys = setOf("putnam/2024/B2"),
            activeDigests = mapOf("putnam/2025/A1" to "a".repeat(64)),
        )
        val port = RecordingLibraryStatePort(state)
        val coordinator = BackupCoordinator(database, notebookRoot, libraryStatePort = port)
        val output = ByteArrayOutputStream()

        assertTrue(coordinator.create(output) is BackupResult.Success)
        val result = coordinator.validateAndRestore(ByteArrayInputStream(output.toByteArray()))

        assertEquals(state, port.applied)
        assertTrue(result is RestoreResult.Success)
        assertEquals(setOf("reference/number-theory/euler-totient"), (result as RestoreResult.Success).reinstallPlan.remoteCandidates)
        assertEquals(setOf("putnam/2025/A1"), result.reinstallPlan.restoredBundledKeys)
    }

    private fun snapshot(root: Path): Map<String, ByteArray> {
        if (!Files.exists(root)) return emptyMap()
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).sorted().iterator().asSequence()
                .associate { path -> root.relativize(path).toString() to Files.readAllBytes(path) }
        }
    }

    private fun assertSnapshotEquals(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) ->
            assertTrue("Contents changed for $path", bytes.contentEquals(actual.getValue(path)))
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

private class RecordingLibraryStatePort(
    private val exported: LibraryBackupState,
) : LibraryStateBackupPort {
    var applied: LibraryBackupState? = null
    override suspend fun exportState(): LibraryBackupState = exported
    override suspend fun applyState(state: LibraryBackupState): ReinstallPlan {
        applied = state
        return ReinstallPlan(
            remoteCandidates = setOf("reference/number-theory/euler-totient"),
            restoredBundledKeys = setOf("putnam/2025/A1"),
        )
    }
}
