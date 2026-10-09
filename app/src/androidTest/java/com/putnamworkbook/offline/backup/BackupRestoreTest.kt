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
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
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
