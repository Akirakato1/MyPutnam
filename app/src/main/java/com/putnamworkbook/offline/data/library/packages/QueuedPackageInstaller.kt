package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.library.index.ContentSource
import com.putnamworkbook.offline.data.library.index.InstallError
import com.putnamworkbook.offline.data.library.index.InstallErrorCode
import com.putnamworkbook.offline.data.library.index.InstalledContentIndex
import com.putnamworkbook.offline.data.library.index.InstalledPackage
import com.putnamworkbook.offline.data.library.index.OperationState
import com.putnamworkbook.offline.data.library.index.PackageAction
import com.putnamworkbook.offline.data.library.index.PackageOperation
import com.putnamworkbook.offline.data.library.index.PackageOperationStore
import java.io.FileInputStream
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class QueuedPackageInstaller(
    private val catalogSource: PackageCatalogSource,
    private val downloader: PackageDownloader,
    private val packageStore: PackageStore,
    private val contentIndex: InstalledContentIndex,
    private val operationStore: PackageOperationStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : PackageInstaller {
    private val serialization = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()

    override fun observeOperations(): Flow<List<PackageOperation>> = operationStore.observeOperations()

    override suspend fun enqueue(keys: Set<String>, action: PackageAction) {
        keys.toSortedSet().forEach { key ->
            val operation = PackageOperation(
                id = UUID.randomUUID().toString(),
                action = action,
                contentKey = key,
                state = OperationState.QUEUED,
                bytesCompleted = 0,
                bytesTotal = 0,
                attemptCount = 0,
                error = null,
                updatedAt = clock(),
            )
            operationStore.upsertOperation(operation)
            launch(operation.id)
        }
    }

    override suspend fun retry(operationId: String) {
        val prior = operationStore.operation(operationId) ?: return
        check(prior.state in setOf(OperationState.FAILED, OperationState.CANCELLED)) { "Only failed or cancelled operations can be retried" }
        operationStore.upsertOperation(prior.copy(
            state = OperationState.QUEUED,
            bytesCompleted = 0,
            bytesTotal = 0,
            attemptCount = prior.attemptCount + 1,
            error = null,
            updatedAt = clock(),
        ))
        launch(operationId)
    }

    override suspend fun cancel(operationId: String) {
        jobs.remove(operationId)?.cancel()
        val operation = operationStore.operation(operationId) ?: return
        if (operation.state !in TERMINAL_STATES) {
            operationStore.upsertOperation(operation.copy(state = OperationState.CANCELLED, updatedAt = clock()))
        }
    }

    private fun launch(id: String) {
        jobs[id] = scope.launch {
            serialization.withLock { process(id) }
        }.also { job -> job.invokeOnCompletion { jobs.remove(id, job) } }
    }

    private suspend fun process(id: String) {
        val initial = operationStore.operation(id) ?: return
        try {
            when (initial.action) {
                PackageAction.INSTALL, PackageAction.UPDATE -> installOrUpdate(initial)
                PackageAction.DELETE -> delete(initial)
                PackageAction.RESTORE -> restore(initial)
            }
            update(id) { it.copy(state = OperationState.COMPLETE, error = null, updatedAt = clock()) }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                update(id) { it.copy(state = OperationState.CANCELLED, updatedAt = clock()) }
            }
        } catch (error: Exception) {
            val installError = error.toInstallError()
            contentIndex.recordFailure(initial.contentKey, installError)
            update(id) { it.copy(state = OperationState.FAILED, error = installError, updatedAt = clock()) }
        }
    }

    private suspend fun installOrUpdate(operation: PackageOperation) {
        val entry = catalogSource.entry(operation.contentKey)
            ?: throw IllegalStateException("Content is not available in the current catalog")
        val before = contentIndex.observe().first().items.firstOrNull { it.metadata.key == operation.contentKey }
        update(operation.id) { it.copy(
            state = OperationState.DOWNLOADING,
            bytesCompleted = 0,
            bytesTotal = entry.descriptor.compressedBytes,
            updatedAt = clock(),
        ) }
        var activated: PackageLocation? = null
        try {
            downloader.download(entry) { completed, total ->
                update(operation.id) { current -> current.copy(bytesCompleted = completed, bytesTotal = total, updatedAt = clock()) }
            }.use { downloaded ->
                update(operation.id) { it.copy(state = OperationState.VERIFYING, updatedAt = clock()) }
                val staged = FileInputStream(downloaded.path.toFile()).use { input ->
                    packageStore.stage(entry.metadata.key, entry.descriptor, input)
                }
                update(operation.id) { it.copy(state = OperationState.ACTIVATING, updatedAt = clock()) }
                activated = packageStore.activate(staged)
            }
            val location = checkNotNull(activated)
            contentIndex.activateDownloaded(InstalledPackage(entry.metadata, entry.descriptor, location.root.toString()))
            before?.takeIf { it.activeSource == ContentSource.DOWNLOADED && it.activeDigest != location.digest }
                ?.let { old -> deleteIfUnreferenced(old.activeLocation, old.activeDigest) }
        } catch (error: Exception) {
            activated?.let { location ->
                runCatching { deleteIfUnreferenced(location.root.toString(), location.digest) }
            }
            throw error
        }
    }

    private suspend fun delete(operation: PackageOperation) {
        val before = contentIndex.observe().first().items.firstOrNull { it.metadata.key == operation.contentKey }
            ?: throw IllegalStateException("Content is not indexed")
        update(operation.id) { it.copy(state = OperationState.ACTIVATING, updatedAt = clock()) }
        contentIndex.logicallyRemove(operation.contentKey)
        if (before.activeSource == ContentSource.DOWNLOADED) deleteIfUnreferenced(before.activeLocation, before.activeDigest)
    }

    private suspend fun restore(operation: PackageOperation) {
        val indexed = contentIndex.observe().first().items.firstOrNull { it.metadata.key == operation.contentKey }
            ?: throw IllegalStateException("Content is not indexed")
        val bundled = indexed.bundled ?: throw IllegalStateException("No bundled package is available")
        update(operation.id) { it.copy(state = OperationState.VERIFYING, updatedAt = clock()) }
        packageStore.ensureBundledExtracted(operation.contentKey, bundled.descriptor)
        update(operation.id) { it.copy(state = OperationState.ACTIVATING, updatedAt = clock()) }
        contentIndex.restoreBundled(operation.contentKey)
    }

    private suspend fun deleteIfUnreferenced(location: String?, digest: String?) {
        if (location == null || digest == null) return
        val referenced = contentIndex.observe().first().items.any { it.activeDigest == digest }
        if (!referenced) packageStore.delete(PackageLocation(Paths.get(location), digest))
    }

    private suspend fun update(id: String, transform: (PackageOperation) -> PackageOperation) {
        val current = operationStore.operation(id) ?: return
        operationStore.upsertOperation(transform(current))
    }

    private fun Exception.toInstallError(): InstallError = when (this) {
        is PackageValidationException -> InstallError(InstallErrorCode.INTEGRITY, message.safe())
        is PackageDownloadException -> InstallError(InstallErrorCode.NETWORK, message.safe())
        is FileSystemException -> InstallError(InstallErrorCode.STORAGE, message.safe())
        is IllegalStateException, is IllegalArgumentException -> InstallError(InstallErrorCode.INVALID_PACKAGE, message.safe())
        else -> InstallError(InstallErrorCode.UNKNOWN, message.safe())
    }

    private fun String?.safe(): String = this?.take(512) ?: "Package operation failed"

    private companion object {
        val TERMINAL_STATES = setOf(OperationState.FAILED, OperationState.COMPLETE, OperationState.CANCELLED)
    }
}
