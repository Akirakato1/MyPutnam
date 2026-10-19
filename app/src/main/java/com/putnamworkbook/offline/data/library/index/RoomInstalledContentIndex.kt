package com.putnamworkbook.offline.data.library.index

import androidx.room.withTransaction
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.CatalogProblem
import com.putnamworkbook.offline.data.library.catalog.CatalogReference
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import com.putnamworkbook.offline.data.library.catalog.PublicationCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomInstalledContentIndex(
    private val database: LibraryIndexDatabase,
    private val currentAppVersionCode: Int,
) : InstalledContentIndex, PackageOperationStore {
    private val dao = database.libraryIndexDao()

    override fun observe(): Flow<InstalledContentSnapshot> = combine(
        dao.observeContents(),
        dao.observePackages(),
        dao.observeRevision(),
    ) { contents, packages, revision ->
        val packagesByKey = packages.groupBy(LibraryPackageEntity::contentKey)
        InstalledContentSnapshot(
            revision = revision ?: 0,
            items = contents.map { entity ->
                resolveInstalledContent(entity.toRecord(packagesByKey[entity.contentKey].orEmpty()), currentAppVersionCode)
            },
        )
    }

    override suspend fun reconcileCatalog(catalog: PublicationCatalog) = database.withTransaction {
        val prior = records().associateBy { it.metadata.key }
        dao.markAllCatalogUnlisted()
        prior.values.forEach { record ->
            persistRecord(record.copy(metadata = record.metadata.copy(catalogListed = false)))
        }
        catalog.problems.forEach { problem ->
            val metadata = problem.toMetadata()
            val existing = prior[problem.key]
            persistRecord(existing?.copy(metadata = metadata) ?: ContentIndexRecord(
                metadata, null, null, false, false, null, null, null,
            ))
        }
        catalog.references.forEach { reference ->
            val metadata = reference.toMetadata()
            val existing = prior[reference.key]
            persistRecord(existing?.copy(metadata = metadata) ?: ContentIndexRecord(
                metadata, null, null, false, false, null, null, null,
            ))
        }
        bumpRevision()
    }

    override suspend fun reconcileBundled(catalog: PublicationCatalog) = database.withTransaction {
        val prior = records().associateBy { it.metadata.key }
        dao.markAllCatalogUnlisted()
        dao.deletePackagesBySource(ContentSource.BUNDLED.name)

        prior.values.forEach { record ->
            persistRecord(record.copy(
                metadata = record.metadata.copy(catalogListed = false),
                bundled = null,
                logicalRemoval = false,
            ))
        }
        catalog.problems.forEach { problem ->
            reconcileCatalogEntry(problem.toMetadata(), problem.packageDescriptor, prior[problem.key])
        }
        catalog.references.forEach { reference ->
            reconcileCatalogEntry(reference.toMetadata(), reference.packageDescriptor, prior[reference.key])
        }
        bumpRevision()
    }

    override suspend fun activateDownloaded(entry: InstalledPackage) = database.withTransaction {
        val existing = record(entry.metadata.key)
        val downloaded = StoredPackage(ContentSource.DOWNLOADED, entry.descriptor, entry.location)
        val next = (existing ?: ContentIndexRecord(
            metadata = entry.metadata,
            bundled = null,
            downloaded = null,
            logicalRemoval = false,
            desiredInstalled = true,
            quarantinedDigest = null,
            quarantineError = null,
            lastOperationError = null,
        )).copy(
            metadata = entry.metadata,
            downloaded = downloaded,
            logicalRemoval = false,
            desiredInstalled = true,
            quarantinedDigest = existing?.quarantinedDigest.takeUnless { it == entry.descriptor.sha256 },
            quarantineError = existing?.quarantineError.takeUnless { existing?.quarantinedDigest == entry.descriptor.sha256 },
            lastOperationError = null,
        )
        persistRecord(next)
        bumpRevision()
    }

    override suspend fun logicallyRemove(key: String) = database.withTransaction {
        val existing = record(key) ?: return@withTransaction
        persistRecord(transitionLogicalRemoval(existing))
        bumpRevision()
    }

    override suspend fun restoreBundled(key: String) = database.withTransaction {
        val existing = record(key) ?: return@withTransaction
        persistRecord(transitionRestoreBundled(existing))
        bumpRevision()
    }

    override suspend fun quarantine(key: String, digest: String, error: InstallError) = database.withTransaction {
        val existing = record(key) ?: return@withTransaction
        persistRecord(transitionQuarantine(existing, digest, error))
        bumpRevision()
    }

    override suspend fun recordFailure(key: String, error: InstallError) = database.withTransaction {
        val existing = record(key) ?: return@withTransaction
        persistRecord(existing.copy(lastOperationError = error))
        bumpRevision()
    }

    override suspend fun exportBackupState(): LibraryBackupState = database.withTransaction {
        val all = records()
        LibraryBackupState(
            desiredKeys = all.filter(ContentIndexRecord::desiredInstalled).mapTo(linkedSetOf()) { it.metadata.key },
            logicallyRemovedBundledKeys = all.filter(ContentIndexRecord::logicalRemoval).mapTo(linkedSetOf()) { it.metadata.key },
            downloadedDigests = all.mapNotNull { record ->
                record.downloaded?.descriptor?.sha256?.let { record.metadata.key to it }
            }.toMap(),
        )
    }

    override suspend fun applyBackupState(state: LibraryBackupState): ReinstallPlan = database.withTransaction {
        val remoteCandidates = linkedSetOf<String>()
        val restoredBundled = linkedSetOf<String>()
        records().forEach { record ->
            val key = record.metadata.key
            val wantsInstalled = key in state.desiredKeys
            val wantsRemoved = key in state.logicallyRemovedBundledKeys
            val expectedDigest = state.downloadedDigests[key]
            val matchingDownload = record.downloaded?.takeIf { it.descriptor.sha256 == expectedDigest }
            val next = when {
                wantsRemoved -> transitionLogicalRemoval(record)
                matchingDownload != null -> record.copy(
                    downloaded = matchingDownload,
                    logicalRemoval = false,
                    desiredInstalled = true,
                )
                wantsInstalled && record.bundled != null -> transitionRestoreBundled(record).also { restoredBundled += key }
                wantsInstalled -> record.copy(downloaded = null, logicalRemoval = false, desiredInstalled = true).also {
                    remoteCandidates += key
                }
                else -> transitionLogicalRemoval(record)
            }
            persistRecord(next)
        }
        state.desiredKeys.filterTo(remoteCandidates) { key -> dao.content(key) == null }
        bumpRevision()
        ReinstallPlan(remoteCandidates, restoredBundled)
    }

    override fun observeOperations(): Flow<List<PackageOperation>> =
        dao.observeOperations().map { rows -> rows.map(PackageOperationEntity::toModel) }

    override suspend fun operation(id: String): PackageOperation? = dao.operation(id)?.toModel()

    override suspend fun upsertOperation(operation: PackageOperation) = database.withTransaction {
        check(dao.content(operation.contentKey) != null) { "Operation content is not indexed: ${operation.contentKey}" }
        dao.upsertOperation(operation.toEntity())
    }

    override suspend fun recoverInterruptedOperations(now: Long) = database.withTransaction {
        dao.operations().forEach { row ->
            val recovered = recoverInterruptedOperation(row.toModel(), now)
            if (recovered != row.toModel()) dao.upsertOperation(recovered.toEntity())
        }
    }

    private suspend fun reconcileCatalogEntry(
        metadata: ContentMetadata,
        descriptor: PackageDescriptor,
        previous: ContentIndexRecord?,
    ) {
        val bundled = StoredPackage(
            source = ContentSource.BUNDLED,
            descriptor = descriptor,
            location = "asset://content-library/${descriptor.path}",
        )
        val next = if (previous == null) {
            ContentIndexRecord(metadata, bundled, null, false, true, null, null, null)
        } else {
            previous.copy(
                metadata = metadata,
                bundled = bundled,
                logicalRemoval = previous.logicalRemoval,
            )
        }
        persistRecord(next)
    }

    private suspend fun records(): List<ContentIndexRecord> {
        val packagesByKey = dao.packages().groupBy(LibraryPackageEntity::contentKey)
        return dao.contents().map { it.toRecord(packagesByKey[it.contentKey].orEmpty()) }
    }

    private suspend fun record(key: String): ContentIndexRecord? =
        dao.content(key)?.toRecord(dao.packages(key))

    private suspend fun persistRecord(record: ContentIndexRecord) {
        dao.upsertContent(record.toEntity(currentAppVersionCode))
        dao.deletePackage(record.metadata.key, ContentSource.BUNDLED.name)
        dao.deletePackage(record.metadata.key, ContentSource.DOWNLOADED.name)
        record.bundled?.let { dao.upsertPackage(it.toEntity(record.metadata.key)) }
        record.downloaded?.let { dao.upsertPackage(it.toEntity(record.metadata.key)) }
    }

    private suspend fun bumpRevision() {
        dao.upsertMetadata(LibraryMetadataEntity(revision = (dao.revision() ?: 0L) + 1L))
    }
}

private fun CatalogProblem.toMetadata() = ContentMetadata(
    key = key,
    contentType = CatalogContentType.PROBLEM,
    label = label,
    collectionId = collectionId,
    setId = setId,
    problemId = problemId,
    topics = topics,
    categoryId = null,
    articleId = null,
    aliases = emptyList(),
    keywords = emptyList(),
    displayOrder = ordinal,
    catalogListed = true,
)

private fun CatalogReference.toMetadata() = ContentMetadata(
    key = key,
    contentType = CatalogContentType.REFERENCE,
    label = title,
    collectionId = null,
    setId = null,
    problemId = null,
    topics = emptyList(),
    categoryId = categoryId,
    articleId = articleId,
    aliases = aliases,
    keywords = keywords,
    displayOrder = displayOrder,
    catalogListed = true,
)

private fun LibraryContentEntity.toRecord(packages: List<LibraryPackageEntity>): ContentIndexRecord {
    val stored = packages.map(LibraryPackageEntity::toModel).associateBy(StoredPackage::source)
    return ContentIndexRecord(
        metadata = ContentMetadata(
            key = contentKey,
            contentType = CatalogContentType.valueOf(contentType),
            label = label,
            collectionId = collectionId,
            setId = setId,
            problemId = problemId,
            topics = decodeStringList(topicsJson),
            categoryId = categoryId,
            articleId = articleId,
            aliases = decodeStringList(aliasesJson),
            keywords = decodeStringList(keywordsJson),
            displayOrder = displayOrder,
            catalogListed = catalogListed,
        ),
        bundled = stored[ContentSource.BUNDLED],
        downloaded = stored[ContentSource.DOWNLOADED],
        logicalRemoval = logicalRemoval,
        desiredInstalled = desiredInstalled,
        quarantinedDigest = quarantinedDigest,
        quarantineError = error(quarantineErrorCode, quarantineErrorMessage),
        lastOperationError = error(lastErrorCode, lastErrorMessage),
    )
}

private fun ContentIndexRecord.toEntity(currentAppVersionCode: Int): LibraryContentEntity {
    val resolved = resolveInstalledContent(this, currentAppVersionCode)
    return LibraryContentEntity(
        contentKey = metadata.key,
        contentType = metadata.contentType.name,
        label = metadata.label,
        collectionId = metadata.collectionId,
        setId = metadata.setId,
        problemId = metadata.problemId,
        topicsJson = encodeStringList(metadata.topics),
        categoryId = metadata.categoryId,
        articleId = metadata.articleId,
        aliasesJson = encodeStringList(metadata.aliases),
        keywordsJson = encodeStringList(metadata.keywords),
        displayOrder = metadata.displayOrder,
        catalogListed = metadata.catalogListed,
        logicalRemoval = logicalRemoval,
        desiredInstalled = desiredInstalled,
        activeSource = resolved.activeSource?.name,
        activeDigest = resolved.activeDigest,
        quarantinedDigest = quarantinedDigest,
        quarantineErrorCode = quarantineError?.code?.name,
        quarantineErrorMessage = quarantineError?.safeMessage?.take(MAX_SAFE_ERROR_LENGTH),
        lastErrorCode = lastOperationError?.code?.name,
        lastErrorMessage = lastOperationError?.safeMessage?.take(MAX_SAFE_ERROR_LENGTH),
    )
}

private fun LibraryPackageEntity.toModel() = StoredPackage(
    source = ContentSource.valueOf(source),
    descriptor = PackageDescriptor(
        contentType = CatalogContentType.valueOf(contentType),
        packageRevision = packageRevision,
        packageSchemaVersion = packageSchemaVersion,
        contentSchemaVersion = contentSchemaVersion,
        richDocumentVersion = richDocumentVersion,
        path = path,
        compressedBytes = compressedBytes,
        uncompressedBytes = uncompressedBytes,
        sha256 = sha256,
        minAppVersionCode = minAppVersionCode,
    ),
    location = location,
)

private fun StoredPackage.toEntity(key: String) = LibraryPackageEntity(
    contentKey = key,
    source = source.name,
    contentType = descriptor.contentType.name,
    packageRevision = descriptor.packageRevision,
    packageSchemaVersion = descriptor.packageSchemaVersion,
    contentSchemaVersion = descriptor.contentSchemaVersion,
    richDocumentVersion = descriptor.richDocumentVersion,
    path = descriptor.path,
    compressedBytes = descriptor.compressedBytes,
    uncompressedBytes = descriptor.uncompressedBytes,
    sha256 = descriptor.sha256,
    minAppVersionCode = descriptor.minAppVersionCode,
    location = location,
)

private fun PackageOperationEntity.toModel() = PackageOperation(
    id = operationId,
    action = PackageAction.valueOf(action),
    contentKey = contentKey,
    state = OperationState.valueOf(state),
    bytesCompleted = bytesCompleted,
    bytesTotal = bytesTotal,
    attemptCount = attemptCount,
    error = error(errorCode, errorMessage),
    updatedAt = updatedAt,
)

private fun PackageOperation.toEntity() = PackageOperationEntity(
    operationId = id,
    action = action.name,
    contentKey = contentKey,
    state = state.name,
    bytesCompleted = bytesCompleted,
    bytesTotal = bytesTotal,
    attemptCount = attemptCount,
    errorCode = error?.code?.name,
    errorMessage = error?.safeMessage?.take(MAX_SAFE_ERROR_LENGTH),
    updatedAt = updatedAt,
)

private fun error(code: String?, message: String?): InstallError? =
    code?.let { InstallError(InstallErrorCode.valueOf(it), message.orEmpty()) }

private const val MAX_SAFE_ERROR_LENGTH = 2_048
