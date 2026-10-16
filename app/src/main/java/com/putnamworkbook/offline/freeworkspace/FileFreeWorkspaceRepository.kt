package com.putnamworkbook.offline.freeworkspace

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.storage.AtomicFileWriter
import com.putnamworkbook.offline.notebook.storage.DurableAtomicFileWriter
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
import kotlinx.coroutines.CancellationException
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

internal interface FreeWorkspaceFileOps {
    fun exists(path: Path): Boolean
    fun isDirectory(path: Path): Boolean
    fun isRegularFile(path: Path): Boolean
    fun list(path: Path): List<Path>
    fun move(source: Path, target: Path)
    fun deleteRecursively(path: Path)
}

internal object NioFreeWorkspaceFileOps : FreeWorkspaceFileOps {
    override fun exists(path: Path): Boolean = Files.exists(path)
    override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)
    override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)

    override fun list(path: Path): List<Path> =
        if (!Files.isDirectory(path)) emptyList() else Files.list(path).use { stream ->
            stream.iterator().asSequence().toList()
        }

    override fun move(source: Path, target: Path) {
        Files.createDirectories(target.parent)
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

class FileFreeWorkspaceRepository internal constructor(
    root: Path,
    private val notebookRepository: NotebookRepository,
    private val writer: AtomicFileWriter = DurableAtomicFileWriter(),
    private val clock: EpochMillisClock = EpochMillisClock(System::currentTimeMillis),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idFactory: () -> UUID = UUID::randomUUID,
    internal val fileOps: FreeWorkspaceFileOps = NioFreeWorkspaceFileOps,
) : FreeWorkspaceRepository {
    private val normalizedRoot = root.toAbsolutePath().normalize()
    private val libraryRoot = normalizedRoot.resolve(LOCAL_COLLECTION).resolve(FREE_WORKSPACE_SET).normalize()
    private val catalogPath = libraryRoot.resolve(CATALOG_FILE)
    private val mutex = Mutex()

    init {
        require(libraryRoot.startsWith(normalizedRoot)) { "Free workspace path escapes its configured root" }
    }

    override fun notebookKey(id: UUID): ProblemKey =
        ProblemKey(LOCAL_COLLECTION, FREE_WORKSPACE_SET, id.toString())

    override suspend fun list(): FreeWorkspaceResult<List<FreeWorkspaceEntry>> = resultLocked {
        val records = loadAndReconcile()
        records.sortedWith(recordComparator).map { record ->
            FreeWorkspaceEntry(
                record = record,
                storageState = if (fileOps.isDirectory(workspaceDirectory(record.id))) {
                    FreeWorkspaceStorageState.READY
                } else {
                    FreeWorkspaceStorageState.MISSING
                },
            )
        }
    }

    override suspend fun create(
        name: String,
        defaultPaper: PaperStyle,
    ): FreeWorkspaceResult<FreeWorkspaceRecord> = resultLocked {
        val records = loadAndReconcile()
        val validated = validateOrThrow(name, records)
        val id = uniqueId(records)
        val timestamp = validNow()
        val record = FreeWorkspaceRecord(id, validated.display, timestamp, timestamp)
        notebookRepository.createNotebook(notebookKey(id), defaultPaper)
        try {
            publishCatalog(records + record)
        } catch (error: Exception) {
            rollbackUnpublishedWorkspace(id)
            throw error
        }
        record
    }

    override suspend fun rename(
        id: UUID,
        name: String,
    ): FreeWorkspaceResult<FreeWorkspaceRecord> = resultLocked {
        val records = loadAndReconcile()
        val current = records.requireRecord(id)
        val validated = validateOrThrow(name, records, id)
        val changed = current.copy(
            name = validated.display,
            modifiedAtEpochMillis = nextTimestamp(current.createdAtEpochMillis),
        )
        publishCatalog(records.replace(changed))
        changed
    }

    override suspend fun duplicate(
        sourceId: UUID,
        name: String,
    ): FreeWorkspaceResult<FreeWorkspaceRecord> = resultLocked {
        val records = loadAndReconcile()
        records.requireRecord(sourceId)
        require(fileOps.isDirectory(workspaceDirectory(sourceId))) { "Source workspace data is missing" }
        val validated = validateOrThrow(name, records)
        val id = uniqueId(records)
        val timestamp = validNow()
        val record = FreeWorkspaceRecord(id, validated.display, timestamp, timestamp)
        notebookRepository.cloneNotebook(notebookKey(sourceId), notebookKey(id))
        try {
            publishCatalog(records + record)
        } catch (error: Exception) {
            rollbackUnpublishedWorkspace(id)
            throw error
        }
        record
    }

    override suspend fun delete(id: UUID): FreeWorkspaceResult<Unit> = resultLocked {
        val records = loadAndReconcile()
        records.requireRecord(id)
        val directory = workspaceDirectory(id)
        val tombstone = libraryRoot.resolve("$DELETE_PREFIX$id-${UUID.randomUUID()}")
        val moved = if (fileOps.isDirectory(directory)) {
            fileOps.move(directory, tombstone)
            true
        } else {
            false
        }
        try {
            publishCatalog(records.filterNot { it.id == id })
        } catch (error: Exception) {
            if (moved && fileOps.isDirectory(tombstone) && !fileOps.exists(directory)) {
                fileOps.move(tombstone, directory)
            }
            throw error
        }
        if (moved) runCatching { fileOps.deleteRecursively(tombstone) }
    }

    override suspend fun markModified(id: UUID): FreeWorkspaceResult<FreeWorkspaceRecord> = resultLocked {
        val records = loadAndReconcile()
        val current = records.requireRecord(id)
        val changed = current.copy(modifiedAtEpochMillis = nextTimestamp(current.createdAtEpochMillis))
        publishCatalog(records.replace(changed))
        changed
    }

    private suspend fun <T> resultLocked(block: suspend () -> T): FreeWorkspaceResult<T> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    FreeWorkspaceResult.Success(block())
                } catch (error: FreeWorkspaceNameException) {
                    FreeWorkspaceResult.NameRejected(error.error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    FreeWorkspaceResult.Failure(safeMessage(error))
                }
            }
        }

    private fun loadAndReconcile(): List<FreeWorkspaceRecord> {
        val records = loadCatalog()
        reconcile(records)
        return records
    }

    private fun loadCatalog(): List<FreeWorkspaceRecord> {
        if (!fileOps.isRegularFile(catalogPath)) {
            if (fileOps.list(libraryRoot).any { it.fileName.toString().startsWith(CORRUPT_PREFIX) }) {
                throw FreeWorkspaceCatalogException("The free notebook catalog is corrupt; its preserved diagnostic copy must be restored or removed")
            }
            return emptyList()
        }
        return try {
            FreeWorkspaceCatalogCodec.decode(Files.readAllBytes(catalogPath))
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            val preserved = uniqueSibling(CORRUPT_PREFIX)
            try {
                fileOps.move(catalogPath, preserved)
            } catch (moveError: Exception) {
                error.addSuppressed(moveError)
            }
            throw FreeWorkspaceCatalogException("The free notebook catalog is corrupt and was preserved", error)
        }
    }

    private fun reconcile(records: List<FreeWorkspaceRecord>) {
        val recordIds = records.mapTo(mutableSetOf()) { it.id }
        fileOps.list(libraryRoot).filter(fileOps::isDirectory).forEach { path ->
            val name = path.fileName.toString()
            when {
                name.startsWith(STAGE_PREFIX) || (name.startsWith(".") && name.contains(".stage-")) ->
                    fileOps.deleteRecursively(path)
                name.startsWith(DELETE_PREFIX) -> reconcileTombstone(path, recordIds)
            }
        }
    }

    private fun reconcileTombstone(tombstone: Path, recordIds: Set<UUID>) {
        val id = tombstone.fileName.toString().removePrefix(DELETE_PREFIX).take(UUID_TEXT_LENGTH)
            .let { text -> runCatching { UUID.fromString(text) }.getOrNull() }
        if (id == null || id !in recordIds) {
            fileOps.deleteRecursively(tombstone)
            return
        }
        val target = workspaceDirectory(id)
        if (fileOps.exists(target)) {
            fileOps.deleteRecursively(tombstone)
        } else {
            fileOps.move(tombstone, target)
        }
    }

    private fun publishCatalog(records: List<FreeWorkspaceRecord>) {
        writer.write(catalogPath, FreeWorkspaceCatalogCodec.encode(records))
    }

    private fun rollbackUnpublishedWorkspace(id: UUID) {
        val directory = workspaceDirectory(id)
        if (!fileOps.exists(directory)) return
        val stage = uniqueSibling("$STAGE_PREFIX${id}-rollback-")
        runCatching { fileOps.move(directory, stage) }
            .onSuccess { runCatching { fileOps.deleteRecursively(stage) } }
    }

    private fun validateOrThrow(
        name: String,
        records: List<FreeWorkspaceRecord>,
        selfId: UUID? = null,
    ): ValidatedWorkspaceName = validateWorkspaceName(name, records, selfId).getOrThrow()

    private fun uniqueId(records: List<FreeWorkspaceRecord>): UUID {
        repeat(MAX_ID_ATTEMPTS) {
            val id = idFactory()
            if (records.none { it.id == id } && !fileOps.exists(workspaceDirectory(id))) return id
        }
        throw IOException("Could not allocate a unique free workspace identity")
    }

    private fun validNow(): Long = clock.now().also {
        if (it < 0) throw IOException("Workspace clock returned a negative timestamp")
    }

    private fun nextTimestamp(createdAt: Long): Long = maxOf(validNow(), createdAt)

    private fun workspaceDirectory(id: UUID): Path = libraryRoot.resolve(id.toString())

    private fun uniqueSibling(prefix: String): Path =
        libraryRoot.resolve("$prefix${UUID.randomUUID()}")

    private fun List<FreeWorkspaceRecord>.requireRecord(id: UUID): FreeWorkspaceRecord =
        firstOrNull { it.id == id } ?: throw IOException("Free workspace was not found")

    private fun List<FreeWorkspaceRecord>.replace(record: FreeWorkspaceRecord): List<FreeWorkspaceRecord> =
        map { if (it.id == record.id) record else it }

    private fun safeMessage(error: Exception): String =
        (error.message ?: "Free workspace operation failed").take(MAX_FAILURE_MESSAGE_LENGTH)

    private companion object {
        const val LOCAL_COLLECTION = "__local__"
        const val FREE_WORKSPACE_SET = "free-workspaces"
        const val CATALOG_FILE = "catalog.json"
        const val CORRUPT_PREFIX = "catalog.json.corrupt"
        const val STAGE_PREFIX = ".stage-"
        const val DELETE_PREFIX = ".delete-"
        const val UUID_TEXT_LENGTH = 36
        const val MAX_ID_ATTEMPTS = 32
        const val MAX_FAILURE_MESSAGE_LENGTH = 240

        val recordComparator = compareByDescending<FreeWorkspaceRecord> { it.modifiedAtEpochMillis }
            .thenBy { workspaceNameUniquenessKey(it.name) }
            .thenBy { it.id.toString() }
    }
}
