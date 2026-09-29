package com.putnamworkbook.offline.backup

import androidx.room.withTransaction
import com.putnamworkbook.offline.data.user.AppSettingEntity
import com.putnamworkbook.offline.data.user.ProblemProgressEntity
import com.putnamworkbook.offline.data.user.UserDatabase
import com.putnamworkbook.offline.domain.model.AppTheme
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.Handedness
import com.putnamworkbook.offline.domain.model.PalmGuardSettings
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

sealed interface BackupResult {
    data class Success(val memberCount: Int, val uncompressedBytes: Long) : BackupResult
    data class Failure(val message: String) : BackupResult
}

sealed interface RestoreResult {
    data class Success(val memberCount: Int) : RestoreResult
    data class Failure(val message: String) : RestoreResult
}

class BackupCoordinator(
    private val database: UserDatabase,
    private val notebookRoot: Path,
    private val archive: BackupArchive = BackupArchive(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun create(destination: OutputStream): BackupResult = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                val snapshot = database.withTransaction { readDatabaseSnapshot() }
                val snapshotBytes = UserSnapshotCodec.encode(snapshot)
                val sources = mutableListOf(
                    BackupSource(BackupArchive.USER_DATA_PATH, snapshotBytes.size.toLong()) {
                        ByteArrayInputStream(snapshotBytes)
                    },
                )
                if (Files.exists(notebookRoot)) {
                    Files.walk(notebookRoot).use { paths ->
                        paths.filter(Files::isRegularFile).sorted().forEach { path ->
                            if (Files.isSymbolicLink(path)) {
                                throw BackupFormatException("Notebook backup cannot contain symbolic links")
                            }
                            val relative = notebookRoot.relativize(path).toString().replace('\\', '/')
                            sources += BackupSource("notebooks/$relative", Files.size(path)) {
                                Files.newInputStream(path)
                            }
                        }
                    }
                }
                archive.write(sources, destination)
                BackupResult.Success(sources.size, sources.sumOf { it.size })
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                BackupResult.Failure(error.safeMessage("Backup could not be created"))
            }
        }
    }

    suspend fun validateAndRestore(source: InputStream): RestoreResult = withContext(ioDispatcher) {
        mutex.withLock {
            val parent = notebookRoot.toAbsolutePath().normalize().parent
                ?: return@withLock RestoreResult.Failure("Notebook storage has no parent directory")
            Files.createDirectories(parent)
            val operationRoot = parent.resolve(".putnam-restore-${UUID.randomUUID()}")
            val extractedRoot = operationRoot.resolve("extracted")
            val rollbackRoot = operationRoot.resolve("previous-notebooks")
            var oldNotebooksMoved = false
            var newNotebooksInstalled = false
            try {
                Files.createDirectories(extractedRoot)
                val validated = archive.read(source, extractedRoot)
                if (validated.memberPaths.any {
                        it != BackupArchive.USER_DATA_PATH && !it.startsWith("notebooks/")
                    }
                ) {
                    throw BackupFormatException("Backup contains an unsupported payload member")
                }
                val snapshot = UserSnapshotCodec.decode(
                    Files.readAllBytes(validated.resolve(BackupArchive.USER_DATA_PATH)),
                )
                val stagedNotebooks = extractedRoot.resolve("notebooks")
                Files.createDirectories(stagedNotebooks)

                withContext(NonCancellable + ioDispatcher) {
                    if (Files.exists(notebookRoot)) {
                        moveAtomically(notebookRoot, rollbackRoot)
                        oldNotebooksMoved = true
                    }
                    moveAtomically(stagedNotebooks, notebookRoot)
                    newNotebooksInstalled = true
                    try {
                        database.withTransaction { replaceDatabaseSnapshot(snapshot) }
                    } catch (error: Exception) {
                        rollbackNotebooks(notebookRoot, rollbackRoot, oldNotebooksMoved)
                        newNotebooksInstalled = false
                        oldNotebooksMoved = false
                        throw error
                    }
                }
                newNotebooksInstalled = false
                oldNotebooksMoved = false
                runCatching { deleteTree(operationRoot) }
                RestoreResult.Success(validated.memberPaths.size)
            } catch (error: Exception) {
                if (newNotebooksInstalled || oldNotebooksMoved) {
                    runCatching { rollbackNotebooks(notebookRoot, rollbackRoot, oldNotebooksMoved) }
                }
                runCatching { deleteTree(operationRoot) }
                if (error is InterruptedException) throw error
                RestoreResult.Failure(error.safeMessage("Backup could not be restored"))
            }
        }
    }

    private suspend fun readDatabaseSnapshot(): UserSnapshot {
        val dao = database.userDao()
        return UserSnapshot(
            progress = dao.allProgressForBackup(),
            settings = dao.settings(),
        )
    }

    private suspend fun replaceDatabaseSnapshot(snapshot: UserSnapshot) {
        val dao = database.userDao()
        dao.deleteAllProgress()
        dao.deleteSettings()
        snapshot.progress.forEach { dao.upsertProgress(it) }
        snapshot.settings?.let { dao.upsertSettings(it) }
    }
}

private data class UserSnapshot(
    val progress: List<ProblemProgressEntity>,
    val settings: AppSettingEntity?,
)

private object UserSnapshotCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(snapshot: UserSnapshot): ByteArray {
        val progress = snapshot.progress.sortedWith(compareBy(ProblemProgressEntity::year, ProblemProgressEntity::problemCode))
        val root = JsonValue.ObjectValue(
            linkedMapOf(
                "schemaVersion" to number(SCHEMA_VERSION),
                "progress" to JsonValue.ArrayValue(progress.map(::progressToJson)),
                "settings" to (snapshot.settings?.let(::settingsToJson) ?: JsonValue.NullValue),
            ),
        )
        return JsonCodec.stringify(root).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): UserSnapshot {
        val root = JsonCodec.parse(bytes.decodeToString()).asObject("user snapshot")
        root.requireOnly("schemaVersion", "progress", "settings")
        val schema = root.requiredInt("schemaVersion")
        if (schema != SCHEMA_VERSION) throw BackupFormatException("Unsupported user snapshot schema version: $schema")
        val seen = mutableSetOf<Pair<Int, String>>()
        val progress = root.requiredArray("progress").mapIndexed { index, value ->
            parseProgress(value.asObject("progress row $index")).also { row ->
                if (!seen.add(row.year to row.problemCode)) {
                    throw BackupFormatException("User snapshot contains duplicate progress rows")
                }
            }
        }
        val settingsValue = root["settings"] ?: throw BackupFormatException("Missing JSON field: settings")
        val settings = if (settingsValue == JsonValue.NullValue) null else {
            parseSettings(settingsValue.asObject("settings"))
        }
        return UserSnapshot(progress, settings)
    }

    private fun progressToJson(row: ProblemProgressEntity) = JsonValue.ObjectValue(
        linkedMapOf(
            "year" to number(row.year),
            "problemCode" to string(row.problemCode),
            "status" to string(row.status),
            "revealedHintCount" to number(row.revealedHintCount),
            "solutionRevealed" to boolean(row.solutionRevealed),
            "notebookPageCount" to number(row.notebookPageCount),
            "activePageIndex" to number(row.activePageIndex),
            "activeReferenceTab" to string(row.activeReferenceTab),
            "lastOpenedAt" to number(row.lastOpenedAt),
        ),
    )

    private fun parseProgress(row: Map<String, JsonValue>): ProblemProgressEntity {
        row.requireOnly(
            "year", "problemCode", "status", "revealedHintCount", "solutionRevealed",
            "notebookPageCount", "activePageIndex", "activeReferenceTab", "lastOpenedAt",
        )
        val year = row.requiredInt("year")
        val code = row.requiredString("problemCode")
        val status = enumName<ProblemStatus>(row.requiredString("status"), "problem status")
        val hints = row.requiredInt("revealedHintCount")
        val pageCount = row.requiredInt("notebookPageCount")
        val pageIndex = row.requiredInt("activePageIndex")
        val reference = enumName<ReferenceTab>(row.requiredString("activeReferenceTab"), "reference tab")
        val lastOpened = row.requiredLong("lastOpenedAt")
        if (year !in 1938..2100 || !code.matches(Regex("[AB][1-6]"))) {
            throw BackupFormatException("Invalid problem key in user snapshot")
        }
        if (hints !in 0..4 || pageCount < 1 || pageIndex !in 0 until pageCount || lastOpened < 0) {
            throw BackupFormatException("Invalid progress values in user snapshot")
        }
        return ProblemProgressEntity(
            year = year,
            problemCode = code,
            status = status,
            revealedHintCount = hints,
            solutionRevealed = row.requiredBoolean("solutionRevealed"),
            notebookPageCount = pageCount,
            activePageIndex = pageIndex,
            activeReferenceTab = reference,
            lastOpenedAt = lastOpened,
        )
    }

    private fun settingsToJson(row: AppSettingEntity) = JsonValue.ObjectValue(
        linkedMapOf(
            "handedness" to string(row.handedness),
            "palmGuardEnabled" to boolean(row.palmGuardEnabled),
            "palmGuardWidthFraction" to number(row.palmGuardWidthFraction),
            "palmGuardHeightFraction" to number(row.palmGuardHeightFraction),
            "defaultPaperStyle" to string(row.defaultPaperStyle),
            "brushColorArgb" to number(row.brushColorArgb),
            "brushWidth" to number(row.brushWidth),
            "theme" to string(row.theme),
            "keepAwakeInWorkspace" to boolean(row.keepAwakeInWorkspace),
        ),
    )

    private fun parseSettings(row: Map<String, JsonValue>): AppSettingEntity {
        row.requireOnly(
            "handedness", "palmGuardEnabled", "palmGuardWidthFraction", "palmGuardHeightFraction",
            "defaultPaperStyle", "brushColorArgb", "brushWidth", "theme", "keepAwakeInWorkspace",
        )
        val handedness = enumName<Handedness>(row.requiredString("handedness"), "handedness")
        val width = row.requiredFloat("palmGuardWidthFraction")
        val height = row.requiredFloat("palmGuardHeightFraction")
        val paper = enumName<PaperStyle>(row.requiredString("defaultPaperStyle"), "paper style")
        val brushWidth = row.requiredFloat("brushWidth")
        val theme = enumName<AppTheme>(row.requiredString("theme"), "theme")
        try {
            PalmGuardSettings(row.requiredBoolean("palmGuardEnabled"), width, height)
            BrushSpec(row.requiredLong("brushColorArgb"), brushWidth)
        } catch (error: IllegalArgumentException) {
            throw BackupFormatException("Invalid settings values in user snapshot", error)
        }
        if (brushWidth > 64f) throw BackupFormatException("Invalid brush width in user snapshot")
        return AppSettingEntity(
            handedness = handedness,
            palmGuardEnabled = row.requiredBoolean("palmGuardEnabled"),
            palmGuardWidthFraction = width,
            palmGuardHeightFraction = height,
            defaultPaperStyle = paper,
            brushColorArgb = row.requiredLong("brushColorArgb"),
            brushWidth = brushWidth,
            theme = theme,
            keepAwakeInWorkspace = row.requiredBoolean("keepAwakeInWorkspace"),
        )
    }

    private fun string(value: String) = JsonValue.StringValue(value)
    private fun number(value: Number) = JsonValue.NumberValue(value.toString())
    private fun boolean(value: Boolean) = JsonValue.BooleanValue(value)

    private inline fun <reified T : Enum<T>> enumName(value: String, label: String): String =
        try {
            enumValueOf<T>(value).name
        } catch (error: IllegalArgumentException) {
            throw BackupFormatException("Invalid $label in user snapshot", error)
        }
}

private fun moveAtomically(source: Path, destination: Path) {
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
    } catch (error: AtomicMoveNotSupportedException) {
        throw BackupFormatException("Storage does not support an atomic notebook restore", error)
    }
}

private fun rollbackNotebooks(notebookRoot: Path, rollbackRoot: Path, hadOldNotebooks: Boolean) {
    if (Files.exists(notebookRoot)) deleteTree(notebookRoot)
    if (hadOldNotebooks && Files.exists(rollbackRoot)) moveAtomically(rollbackRoot, notebookRoot)
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private fun Throwable.safeMessage(fallback: String): String = message?.take(240)?.ifBlank { fallback } ?: fallback
