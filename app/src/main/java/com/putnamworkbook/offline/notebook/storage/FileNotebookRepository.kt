package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

class FileNotebookRepository(
    private val root: Path,
    private val writer: AtomicFileWriter = DurableAtomicFileWriter(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : NotebookRepository {
    private val mutex = Mutex()

    override suspend fun loadExisting(key: ProblemKey): NotebookDocument? = locked {
        if (Files.exists(manifestPath(key))) loadExistingDocument(key) else null
    }

    override suspend fun loadExportSnapshot(key: ProblemKey): NotebookExportSnapshot? = locked {
        if (!Files.isRegularFile(manifestPath(key))) return@locked null
        val document = loadExistingDocument(key)
        NotebookExportSnapshot(
            key = key,
            pages = document.pages.map { page ->
                NotebookPageSnapshot(
                    id = page.id,
                    paperStyle = page.paperStyle,
                    strokes = loadActiveStrokes(key, page),
                )
            },
        )
    }

    override suspend fun loadOrCreate(key: ProblemKey, defaultPaper: PaperStyle): NotebookDocument = locked {
        loadOrCreateLocked(key, defaultPaper)
    }

    override suspend fun loadStrokes(key: ProblemKey, pageId: String): List<StrokeRecord> = locked {
        val document = loadExistingDocument(key)
        val page = document.page(pageId)
        loadActiveStrokes(key, page)
    }

    private fun loadActiveStrokes(key: ProblemKey, page: NotebookPage): List<StrokeRecord> =
        page.strokes.filter { it.active }.map { reference ->
            val path = strokePath(key, page.id, reference.strokeId)
            if (!Files.isRegularFile(path)) throw IOException("Referenced stroke file is missing: $path")
            StrokeCodec.decode(Files.readAllBytes(path)).also { stroke ->
                if (stroke.id != reference.strokeId) {
                    throw IOException("Stroke id does not match its manifest reference")
                }
            }
        }

    override suspend fun commitStroke(
        key: ProblemKey,
        pageId: String,
        stroke: StrokeRecord,
    ): NotebookDocument = locked {
        val document = loadExistingDocument(key)
        val page = document.page(pageId)
        require(page.strokes.none { it.strokeId == stroke.id }) { "Stroke id already exists on this page" }
        val strokePath = strokePath(key, pageId, stroke.id)
        val encodedStroke = StrokeCodec.encode(stroke)
        if (Files.exists(strokePath)) {
            require(Files.readAllBytes(strokePath).contentEquals(encodedStroke)) {
                "An immutable stroke file already exists with different content"
            }
        } else {
            writer.write(strokePath, encodedStroke)
        }
        val changedPage = page.copy(
            strokes = page.strokes.filter { it.active } + StrokeReference(stroke.id, active = true),
        )
        document.replacePage(changedPage).also { writeManifest(it) }
    }

    override suspend fun setStrokeActive(
        key: ProblemKey,
        pageId: String,
        strokeId: String,
        active: Boolean,
    ): NotebookDocument = locked {
        val document = loadExistingDocument(key)
        val page = document.page(pageId)
        require(page.strokes.any { it.strokeId == strokeId }) { "Unknown stroke id: $strokeId" }
        val changedPage = page.copy(
            strokes = page.strokes.map { reference ->
                if (reference.strokeId == strokeId) reference.copy(active = active) else reference
            },
        )
        document.replacePage(changedPage).also { writeManifest(it) }
    }

    override suspend fun addPage(
        key: ProblemKey,
        afterPageId: String,
        paperStyle: PaperStyle,
    ): NotebookDocument = locked {
        val document = loadExistingDocument(key)
        val afterIndex = document.pages.indexOfFirst { it.id == afterPageId }
        require(afterIndex >= 0) { "Unknown page id: $afterPageId" }
        val changedPages = document.pages.toMutableList().apply {
            add(afterIndex + 1, NotebookPage(idFactory(), paperStyle))
        }
        document.copy(pages = changedPages).also { writeManifest(it) }
    }

    override suspend fun deletePage(key: ProblemKey, pageId: String): NotebookDocument = locked {
        val document = loadExistingDocument(key)
        check(document.pages.size > 1) { "The final notebook page cannot be deleted" }
        require(document.pages.any { it.id == pageId }) { "Unknown page id: $pageId" }
        document.copy(pages = document.pages.filterNot { it.id == pageId }).also { writeManifest(it) }
    }

    override suspend fun clearPage(key: ProblemKey, pageId: String): NotebookDocument = locked {
        val document = loadExistingDocument(key)
        val changedPage = document.page(pageId).copy(strokes = emptyList())
        document.replacePage(changedPage).also { writeManifest(it) }
    }

    override suspend fun reorderPage(
        key: ProblemKey,
        pageId: String,
        newIndex: Int,
    ): NotebookDocument = locked {
        val document = loadExistingDocument(key)
        require(newIndex in document.pages.indices) { "New page index is outside the notebook" }
        val oldIndex = document.pages.indexOfFirst { it.id == pageId }
        require(oldIndex >= 0) { "Unknown page id: $pageId" }
        val changedPages = document.pages.toMutableList()
        val page = changedPages.removeAt(oldIndex)
        changedPages.add(newIndex, page)
        document.copy(pages = changedPages).also { writeManifest(it) }
    }

    private fun loadOrCreateLocked(key: ProblemKey, defaultPaper: PaperStyle): NotebookDocument {
        val manifest = manifestPath(key)
        if (!Files.exists(manifest)) {
            return NotebookDocument(
                key = key,
                pages = listOf(NotebookPage(idFactory(), defaultPaper)),
            ).also { writeManifest(it) }
        }
        return try {
            loadExistingDocument(key)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            preserveCorruptManifest(manifest)
            NotebookDocument(
                key = key,
                pages = listOf(NotebookPage(idFactory(), defaultPaper)),
            ).also { writeManifest(it) }
        }
    }

    private fun loadExistingDocument(key: ProblemKey): NotebookDocument {
        val manifest = manifestPath(key)
        if (!Files.isRegularFile(manifest)) throw IOException("Notebook manifest is missing: $manifest")
        return NotebookManifestCodec.decode(Files.readAllBytes(manifest)).also { document ->
            if (document.key != key) throw IOException("Notebook manifest key does not match its directory")
        }
    }

    private fun preserveCorruptManifest(manifest: Path) {
        var destination = manifest.resolveSibling("${manifest.fileName}.corrupt")
        if (Files.exists(destination)) {
            destination = manifest.resolveSibling("${manifest.fileName}.corrupt-${UUID.randomUUID()}")
        }
        Files.move(manifest, destination, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeManifest(document: NotebookDocument) {
        writer.write(manifestPath(document.key), NotebookManifestCodec.encode(document))
    }

    private fun manifestPath(key: ProblemKey): Path = notebookDirectory(key).resolve(MANIFEST_FILE)

    private fun strokePath(key: ProblemKey, pageId: String, strokeId: String): Path =
        notebookDirectory(key).resolve("strokes").resolve(pageId).resolve("$strokeId.stroke")

    private fun notebookDirectory(key: ProblemKey): Path {
        require(key.year in 1938..2100 && key.code.matches(PROBLEM_CODE)) { "Invalid problem key" }
        return root.resolve("${key.year}-${key.code}")
    }

    private suspend fun <T> locked(block: () -> T): T =
        withContext(ioDispatcher) { mutex.withLock { block() } }

    private fun NotebookDocument.page(pageId: String): NotebookPage =
        pages.firstOrNull { it.id == pageId } ?: throw IllegalArgumentException("Unknown page id: $pageId")

    private fun NotebookDocument.replacePage(changedPage: NotebookPage): NotebookDocument =
        copy(pages = pages.map { if (it.id == changedPage.id) changedPage else it })

    private companion object {
        const val MANIFEST_FILE = "manifest.bin"
        val PROBLEM_CODE = Regex("[AB][1-6]")
    }
}
