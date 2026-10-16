package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookEdit
import com.putnamworkbook.offline.notebook.model.NotebookEditGroup
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.NotebookReducer
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
import java.util.Comparator
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

    override suspend fun createNotebook(
        key: ProblemKey,
        defaultPaper: PaperStyle,
    ): NotebookDocument = locked {
        val target = notebookDirectory(key)
        check(!Files.exists(target)) { "Notebook already exists: ${key.canonical}" }
        val stage = stageDirectory(target)
        try {
            val document = NotebookDocument(
                key = key,
                pages = listOf(NotebookPage(idFactory(), defaultPaper)),
            )
            writeManifest(stage, document)
            publishStage(stage, target)
            document
        } catch (error: Exception) {
            deleteRecursively(stage)
            throw error
        }
    }

    override suspend fun cloneNotebook(
        sourceKey: ProblemKey,
        targetKey: ProblemKey,
    ): NotebookDocument = locked {
        require(sourceKey != targetKey) { "Source and target notebook keys must differ" }
        val sourceDirectory = notebookDirectory(sourceKey)
        val targetDirectory = notebookDirectory(targetKey)
        check(Files.isRegularFile(sourceDirectory.resolve(MANIFEST_FILE))) {
            "Source notebook does not exist: ${sourceKey.canonical}"
        }
        check(!Files.exists(targetDirectory)) { "Notebook already exists: ${targetKey.canonical}" }
        val source = loadExistingDocument(sourceKey)
        val clone = source.copy(key = targetKey)
        val stage = stageDirectory(targetDirectory)
        try {
            source.pages.forEach { page ->
                page.strokes.forEach { reference ->
                    val sourceStroke = strokePath(sourceDirectory, page.id, reference.strokeId)
                    if (!Files.isRegularFile(sourceStroke)) {
                        throw IOException("Referenced stroke file is missing: $sourceStroke")
                    }
                    val bytes = Files.readAllBytes(sourceStroke)
                    val decoded = StrokeCodec.decode(bytes)
                    if (decoded.id != reference.strokeId) {
                        throw IOException("Stroke id does not match its manifest reference")
                    }
                    writer.write(strokePath(stage, page.id, reference.strokeId), bytes)
                }
            }
            writeManifest(stage, clone)
            publishStage(stage, targetDirectory)
            clone
        } catch (error: Exception) {
            deleteRecursively(stage)
            throw error
        }
    }

    override suspend fun loadStrokes(key: ProblemKey, pageId: String): List<StrokeRecord> = locked {
        val document = loadExistingDocument(key)
        val page = document.page(pageId)
        loadActiveStrokes(key, page)
    }

    override suspend fun applyEdits(
        key: ProblemKey,
        pageId: String,
        edits: List<NotebookEdit>,
    ): NotebookDocument = locked {
        val document = loadExistingDocument(key)
        if (edits.isEmpty()) return@locked document
        val changedPage = NotebookReducer.applyEdits(document.page(pageId), edits)
        edits.flatMap { it.newStrokes }.forEach { stroke ->
            writeImmutableStroke(key, pageId, stroke)
        }
        document.replacePage(changedPage).also { writeManifest(it) }
    }

    override suspend fun undo(key: ProblemKey, pageId: String): NotebookDocument = locked {
        val document = loadExistingDocument(key)
        val page = document.page(pageId)
        val changedPage = NotebookReducer.undo(page)
        if (changedPage == page) document else document.replacePage(changedPage).also { writeManifest(it) }
    }

    override suspend fun redo(key: ProblemKey, pageId: String): NotebookDocument = locked {
        val document = loadExistingDocument(key)
        val page = document.page(pageId)
        val changedPage = NotebookReducer.redo(page)
        if (changedPage == page) document else document.replacePage(changedPage).also { writeManifest(it) }
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
    ): NotebookDocument = applyEdits(
        key = key,
        pageId = pageId,
        edits = listOf(
            NotebookEdit(
                group = NotebookEditGroup(idFactory(), listOf(stroke.id), emptyList()),
                newStrokes = listOf(stroke),
            ),
        ),
    )

    private fun writeImmutableStroke(key: ProblemKey, pageId: String, stroke: StrokeRecord) {
        val path = strokePath(key, pageId, stroke.id)
        val encodedStroke = StrokeCodec.encode(stroke)
        if (Files.exists(path)) {
            require(Files.readAllBytes(path).contentEquals(encodedStroke)) {
                "An immutable stroke file already exists with different content"
            }
        } else {
            writer.write(path, encodedStroke)
        }
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
        val reference = page.strokes.first { it.strokeId == strokeId }
        if (reference.active == active) return@locked document
        val changedPage = NotebookReducer.applyEdits(
            page,
            listOf(
                NotebookEdit(
                    group = NotebookEditGroup(
                        id = idFactory(),
                        activatedStrokeIds = if (active) listOf(strokeId) else emptyList(),
                        deactivatedStrokeIds = if (active) emptyList() else listOf(strokeId),
                    ),
                    newStrokes = emptyList(),
                ),
            ),
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
        val changedPage = document.page(pageId).copy(
            strokes = emptyList(),
            editHistory = emptyList(),
            appliedEditCount = 0,
        )
        document.replacePage(changedPage).also { writeManifest(it) }
    }

    private fun loadOrCreateLocked(key: ProblemKey, defaultPaper: PaperStyle): NotebookDocument {
        val manifest = manifestPath(key)
        if (!Files.exists(manifest)) {
            return NotebookDocument(
                key = key,
                pages = listOf(NotebookPage(idFactory(), defaultPaper)),
            ).also { writeManifest(it) }
        }
        val decoded = try {
            readDecodedManifest(key)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            preserveCorruptManifest(manifest)
            return NotebookDocument(
                key = key,
                pages = listOf(NotebookPage(idFactory(), defaultPaper)),
            ).also { writeManifest(it) }
        }
        return decoded.document
    }

    private fun loadExistingDocument(key: ProblemKey): NotebookDocument {
        return readDecodedManifest(key).document
    }

    private fun readDecodedManifest(key: ProblemKey): DecodedNotebookManifest {
        val manifest = manifestPath(key)
        if (!Files.isRegularFile(manifest)) throw IOException("Notebook manifest is missing: $manifest")
        return NotebookManifestCodec.decode(Files.readAllBytes(manifest)).also { decoded ->
            if (decoded.document.key != key) throw IOException("Notebook manifest key does not match its directory")
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

    private fun writeManifest(directory: Path, document: NotebookDocument) {
        writer.write(directory.resolve(MANIFEST_FILE), NotebookManifestCodec.encode(document))
    }

    private fun manifestPath(key: ProblemKey): Path = notebookDirectory(key).resolve(MANIFEST_FILE)

    private fun strokePath(key: ProblemKey, pageId: String, strokeId: String): Path =
        strokePath(notebookDirectory(key), pageId, strokeId)

    private fun strokePath(directory: Path, pageId: String, strokeId: String): Path =
        directory.resolve("strokes").resolve(pageId).resolve("$strokeId.stroke")

    private fun stageDirectory(target: Path): Path =
        target.resolveSibling(".${target.fileName}.stage-${UUID.randomUUID()}")

    private fun publishStage(stage: Path, target: Path) {
        Files.createDirectories(target.parent)
        check(!Files.exists(target)) { "Notebook already exists: $target" }
        Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun deleteRecursively(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun notebookDirectory(key: ProblemKey): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val directory = normalizedRoot
            .resolve(key.collectionId)
            .resolve(key.setId)
            .resolve(key.problemId)
            .normalize()
        require(directory.startsWith(normalizedRoot)) { "Notebook path escapes its configured root" }
        return directory
    }

    private suspend fun <T> locked(block: () -> T): T =
        withContext(ioDispatcher) { mutex.withLock { block() } }

    private fun NotebookDocument.page(pageId: String): NotebookPage =
        pages.firstOrNull { it.id == pageId } ?: throw IllegalArgumentException("Unknown page id: $pageId")

    private fun NotebookDocument.replacePage(changedPage: NotebookPage): NotebookDocument =
        copy(pages = pages.map { if (it.id == changedPage.id) changedPage else it })

    private companion object {
        const val MANIFEST_FILE = "manifest.bin"
    }
}
