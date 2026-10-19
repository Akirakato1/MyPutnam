package com.putnamworkbook.offline.data.library.index

import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor

enum class ContentSource { BUNDLED, DOWNLOADED }

enum class ContentAvailability {
    INSTALLED,
    LOGICALLY_REMOVED,
    QUARANTINED,
    APP_UPDATE_REQUIRED,
    NOT_INSTALLED,
}

data class ContentMetadata(
    val key: String,
    val contentType: CatalogContentType,
    val label: String,
    val collectionId: String?,
    val setId: String?,
    val problemId: String?,
    val topics: List<String>,
    val categoryId: String?,
    val articleId: String?,
    val aliases: List<String>,
    val keywords: List<String>,
    val displayOrder: Int,
    val catalogListed: Boolean,
)

data class StoredPackage(
    val source: ContentSource,
    val descriptor: PackageDescriptor,
    val location: String,
)

data class ContentIndexRecord(
    val metadata: ContentMetadata,
    val bundled: StoredPackage?,
    val downloaded: StoredPackage?,
    val logicalRemoval: Boolean,
    val desiredInstalled: Boolean,
    val quarantinedDigest: String?,
    val quarantineError: InstallError?,
    val lastOperationError: InstallError?,
)

data class ResolvedInstalledContent(
    val metadata: ContentMetadata,
    val bundled: StoredPackage?,
    val downloaded: StoredPackage?,
    val availability: ContentAvailability,
    val activeSource: ContentSource?,
    val activeDigest: String?,
    val activeLocation: String?,
    val quarantinedDigest: String?,
    val quarantineError: InstallError?,
    val lastOperationError: InstallError?,
)

data class InstalledContentSnapshot(
    val revision: Long,
    val items: List<ResolvedInstalledContent>,
)

data class InstalledPackage(
    val metadata: ContentMetadata,
    val descriptor: PackageDescriptor,
    val location: String,
)

enum class InstallErrorCode {
    INTEGRITY,
    NETWORK,
    STORAGE,
    INVALID_PACKAGE,
    UNSUPPORTED,
    INTERRUPTED,
    UNKNOWN,
}

data class InstallError(val code: InstallErrorCode, val safeMessage: String)

enum class PackageAction { INSTALL, UPDATE, DELETE, RESTORE }

enum class OperationState { QUEUED, DOWNLOADING, VERIFYING, ACTIVATING, FAILED, COMPLETE, CANCELLED }

data class PackageOperation(
    val id: String,
    val action: PackageAction,
    val contentKey: String,
    val state: OperationState,
    val bytesCompleted: Long,
    val bytesTotal: Long,
    val attemptCount: Int,
    val error: InstallError?,
    val updatedAt: Long,
)

data class LibraryBackupState(
    val desiredKeys: Set<String>,
    val logicallyRemovedBundledKeys: Set<String>,
    val downloadedDigests: Map<String, String>,
)

data class ReinstallPlan(
    val remoteCandidates: Set<String>,
    val restoredBundledKeys: Set<String>,
)

fun resolveInstalledContent(record: ContentIndexRecord, currentAppVersionCode: Int): ResolvedInstalledContent {
    val candidate = when {
        record.logicalRemoval || !record.desiredInstalled -> null
        record.downloaded != null -> record.downloaded
        else -> record.bundled
    }
    val candidateDigest = candidate?.descriptor?.sha256
    val availability = when {
        record.logicalRemoval -> ContentAvailability.LOGICALLY_REMOVED
        candidate == null -> ContentAvailability.NOT_INSTALLED
        candidateDigest == record.quarantinedDigest -> ContentAvailability.QUARANTINED
        candidate.descriptor.minAppVersionCode > currentAppVersionCode -> ContentAvailability.APP_UPDATE_REQUIRED
        else -> ContentAvailability.INSTALLED
    }
    val isActive = availability == ContentAvailability.INSTALLED
    return ResolvedInstalledContent(
        metadata = record.metadata,
        bundled = record.bundled,
        downloaded = record.downloaded,
        availability = availability,
        activeSource = candidate?.source?.takeIf { isActive },
        activeDigest = candidateDigest?.takeIf { isActive },
        activeLocation = candidate?.location?.takeIf { isActive },
        quarantinedDigest = record.quarantinedDigest,
        quarantineError = record.quarantineError,
        lastOperationError = record.lastOperationError,
    )
}

fun transitionLogicalRemoval(record: ContentIndexRecord): ContentIndexRecord = record.copy(
    downloaded = null,
    logicalRemoval = record.bundled != null,
    desiredInstalled = false,
    lastOperationError = null,
)

fun transitionRestoreBundled(record: ContentIndexRecord): ContentIndexRecord {
    val bundleDigest = record.bundled?.descriptor?.sha256
    return record.copy(
        downloaded = null,
        logicalRemoval = false,
        desiredInstalled = record.bundled != null,
        quarantinedDigest = record.quarantinedDigest.takeUnless { it == bundleDigest },
        quarantineError = record.quarantineError.takeUnless { record.quarantinedDigest == bundleDigest },
        lastOperationError = null,
    )
}

fun transitionQuarantine(record: ContentIndexRecord, digest: String, error: InstallError): ContentIndexRecord =
    record.copy(quarantinedDigest = digest, quarantineError = error, lastOperationError = error)

fun recoverInterruptedOperation(operation: PackageOperation, now: Long = operation.updatedAt): PackageOperation =
    if (operation.state in setOf(
            OperationState.QUEUED,
            OperationState.DOWNLOADING,
            OperationState.VERIFYING,
            OperationState.ACTIVATING,
        )
    ) {
        operation.copy(
            state = OperationState.FAILED,
            attemptCount = operation.attemptCount + 1,
            error = InstallError(InstallErrorCode.INTERRUPTED, "Operation was interrupted and can be retried"),
            updatedAt = now,
        )
    } else {
        operation
    }
