package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files

class FileNotebookRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val key = ProblemKey(2025, "A1")

    @Test
    fun undoRedoPersistsAndACommitAfterUndoDiscardsOnlyTheRedoReference() = runBlocking {
        val root = temporaryFolder.newFolder("notebooks").toPath()
        var repository = FileNotebookRepository(root)
        val created = repository.loadOrCreate(key, PaperStyle.SQUARE_GRID)
        val pageId = created.pages.single().id

        repository.commitStroke(key, pageId, stroke("first", 0.1f))
        repository.commitStroke(key, pageId, stroke("second", 0.2f))
        repository.setStrokeActive(key, pageId, "second", false)

        repository = FileNotebookRepository(root)
        var loaded = repository.loadOrCreate(key, PaperStyle.BLANK)
        assertEquals(listOf(true, false), loaded.pages.single().strokes.map { it.active })
        assertEquals(PaperStyle.SQUARE_GRID, loaded.pages.single().paperStyle)

        repository.setStrokeActive(key, pageId, "second", true)
        loaded = FileNotebookRepository(root).loadOrCreate(key, PaperStyle.BLANK)
        assertEquals(listOf(true, true), loaded.pages.single().strokes.map { it.active })

        repository.setStrokeActive(key, pageId, "second", false)
        loaded = repository.commitStroke(key, pageId, stroke("third", 0.3f))
        assertEquals(listOf("first", "second", "third"), loaded.pages.single().strokes.map { it.strokeId })
        assertEquals(listOf(true, false, true), loaded.pages.single().strokes.map { it.active })
        assertEquals(
            listOf("first", "third"),
            repository.loadStrokes(key, pageId).map { it.id },
        )
        assertTrue(Files.exists(root.resolve("2025-A1/strokes/$pageId/second.stroke")))
    }

    @Test
    fun failedManifestWriteKeepsPriorManifestAndLeavesRecoverableOrphanStroke() = runBlocking {
        val root = temporaryFolder.newFolder("atomic").toPath()
        val durableWriter = DurableAtomicFileWriter()
        val repository = FileNotebookRepository(root, durableWriter)
        val pageId = repository.loadOrCreate(key, PaperStyle.BLANK).pages.single().id
        val manifest = root.resolve("2025-A1/manifest.bin")
        val before = Files.readAllBytes(manifest)
        val failingWriter = AtomicFileWriter { target, bytes ->
            if (target.fileName.toString() == "manifest.bin") throw IOException("injected failure")
            durableWriter.write(target, bytes)
        }

        val error = assertThrows(IOException::class.java) {
            runBlocking {
                FileNotebookRepository(root, failingWriter)
                    .commitStroke(key, pageId, stroke("orphan", 0.4f))
            }
        }
        assertEquals("injected failure", error.message)
        assertArrayEquals(before, Files.readAllBytes(manifest))
        assertTrue(Files.exists(root.resolve("2025-A1/strokes/$pageId/orphan.stroke")))
        assertTrue(FileNotebookRepository(root).loadStrokes(key, pageId).isEmpty())
    }

    @Test
    fun corruptManifestIsRetainedBeforeAReplacementNotebookIsCreated() = runBlocking {
        val root = temporaryFolder.newFolder("corrupt").toPath()
        val repository = FileNotebookRepository(root)
        repository.loadOrCreate(key, PaperStyle.BLANK)
        val manifest = root.resolve("2025-A1/manifest.bin")
        val corruptBytes = byteArrayOf(1, 2, 3, 4, 5)
        Files.write(manifest, corruptBytes)

        val recovered = repository.loadOrCreate(key, PaperStyle.SQUARE_GRID)

        assertEquals(PaperStyle.SQUARE_GRID, recovered.pages.single().paperStyle)
        assertArrayEquals(corruptBytes, Files.readAllBytes(root.resolve("2025-A1/manifest.bin.corrupt")))
        assertFalse(Files.readAllBytes(manifest).contentEquals(corruptBytes))
    }

    @Test
    fun versionOneManifestIsBackedUpOnceAndMigratedAtomically() = runBlocking {
        val root = temporaryFolder.newFolder("migration").toPath()
        val notebookDirectory = root.resolve("2025-A1")
        Files.createDirectories(notebookDirectory)
        val manifest = notebookDirectory.resolve("manifest.bin")
        val legacyBytes = versionOneManifest(listOf("visible" to true, "undone" to false))
        Files.write(manifest, legacyBytes)

        val repository = FileNotebookRepository(root)
        val migrated = repository.loadOrCreate(key, PaperStyle.SQUARE_GRID)

        assertEquals(2, migrated.schemaVersion)
        assertEquals(listOf("visible"), migrated.pages.single().strokes.map { it.strokeId })
        val backup = notebookDirectory.resolve("manifest.bin.v1.bak")
        assertArrayEquals(legacyBytes, Files.readAllBytes(backup))
        assertFalse(legacyBytes.contentEquals(Files.readAllBytes(manifest)))

        val sentinelBackup = byteArrayOf(9, 8, 7)
        Files.write(backup, sentinelBackup)
        FileNotebookRepository(root).loadExisting(key)
        assertArrayEquals(sentinelBackup, Files.readAllBytes(backup))
    }

    @Test
    fun failedVersionOneMigrationLeavesOriginalManifestInPlace() = runBlocking {
        val root = temporaryFolder.newFolder("failed-migration").toPath()
        val notebookDirectory = root.resolve("2025-A1")
        Files.createDirectories(notebookDirectory)
        val manifest = notebookDirectory.resolve("manifest.bin")
        val legacyBytes = versionOneManifest(listOf("visible" to true))
        Files.write(manifest, legacyBytes)
        val durableWriter = DurableAtomicFileWriter()
        val failingWriter = AtomicFileWriter { target, bytes ->
            if (target.fileName.toString() == "manifest.bin") throw IOException("injected migration failure")
            durableWriter.write(target, bytes)
        }

        val error = assertThrows(IOException::class.java) {
            runBlocking { FileNotebookRepository(root, failingWriter).loadOrCreate(key, PaperStyle.BLANK) }
        }

        assertEquals("injected migration failure", error.message)
        assertArrayEquals(legacyBytes, Files.readAllBytes(manifest))
        assertFalse(Files.exists(notebookDirectory.resolve("manifest.bin.corrupt")))
        assertArrayEquals(legacyBytes, Files.readAllBytes(notebookDirectory.resolve("manifest.bin.v1.bak")))
    }

    @Test
    fun pageOperationsPreserveAtLeastOnePageAndStableOrder() = runBlocking {
        val root = temporaryFolder.newFolder("pages").toPath()
        val repository = FileNotebookRepository(root)
        val first = repository.loadOrCreate(key, PaperStyle.BLANK).pages.single()
        val withSecond = repository.addPage(key, first.id, PaperStyle.SQUARE_GRID)
        val second = withSecond.pages[1]
        val reordered = repository.reorderPage(key, second.id, 0)
        assertEquals(listOf(second.id, first.id), reordered.pages.map { it.id })

        repository.commitStroke(key, second.id, stroke("to-clear", 0.5f))
        assertTrue(repository.clearPage(key, second.id).pages[0].strokes.isEmpty())
        repository.deletePage(key, first.id)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deletePage(key, second.id) }
        }
        Unit
    }

    private fun stroke(id: String, x: Float) = StrokeRecord(
        id = id,
        brush = BrushSpec(),
        samples = listOf(
            StrokeSample(x, 0.25f, 0L, null),
            StrokeSample(x, 0.5f, 10L, 0.5f),
        ),
    )

    private fun versionOneManifest(strokes: List<Pair<String, Boolean>>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x50574E42)
                output.writeInt(1)
                output.writeInt(key.year)
                output.writeUTF(key.code)
                output.writeInt(1)
                output.writeUTF("legacy-page")
                output.writeUTF(PaperStyle.BLANK.name)
                output.writeInt(strokes.size)
                strokes.forEach { (id, active) ->
                    output.writeUTF(id)
                    output.writeBoolean(active)
                }
            }
            bytes.toByteArray()
        }
}
