package com.putnamworkbook.offline.data.library.index

import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledContentResolutionTest {
    @Test
    fun downloadedPackageWinsOverBundledPackage() {
        val record = baseRecord(
            bundled = storedPackage(ContentSource.BUNDLED, "a", "asset://a"),
            downloaded = storedPackage(ContentSource.DOWNLOADED, "b", "files://b"),
        )
        val resolved = resolveInstalledContent(record, currentAppVersionCode = 4)
        assertEquals(ContentSource.DOWNLOADED, resolved.activeSource)
        assertEquals("b".repeat(64), resolved.activeDigest)
        assertEquals(ContentAvailability.INSTALLED, resolved.availability)
    }

    @Test
    fun bundledIsDefaultAndCanBeRemovedThenRestoredOffline() {
        val initial = baseRecord(bundled = storedPackage(ContentSource.BUNDLED, "a", "asset://a"))
        val removed = transitionLogicalRemoval(initial)
        val restored = transitionRestoreBundled(removed)
        assertEquals(ContentSource.BUNDLED, resolveInstalledContent(initial, 4).activeSource)
        assertEquals(ContentAvailability.LOGICALLY_REMOVED, resolveInstalledContent(removed, 4).availability)
        assertFalse(removed.desiredInstalled)
        assertEquals(ContentSource.BUNDLED, resolveInstalledContent(restored, 4).activeSource)
        assertTrue(restored.desiredInstalled)
    }

    @Test
    fun deletingDownloadedOverrideSuppressesBundledFallback() {
        val record = baseRecord(
            bundled = storedPackage(ContentSource.BUNDLED, "a", "asset://a"),
            downloaded = storedPackage(ContentSource.DOWNLOADED, "b", "files://b"),
        )
        val removed = transitionLogicalRemoval(record)
        assertNull(removed.downloaded)
        assertEquals(ContentAvailability.LOGICALLY_REMOVED, resolveInstalledContent(removed, 4).availability)
        assertNull(resolveInstalledContent(removed, 4).activeSource)
    }

    @Test
    fun deletingDownloadedOnlyItemLeavesItUnavailableWithoutLosingMetadata() {
        val record = baseRecord(downloaded = storedPackage(ContentSource.DOWNLOADED, "b", "files://b"))
        val removed = transitionLogicalRemoval(record)
        assertNull(removed.downloaded)
        assertEquals("Putnam 2025 A1", removed.metadata.label)
        assertEquals(listOf("number-theory"), removed.metadata.topics)
        assertEquals(ContentAvailability.NOT_INSTALLED, resolveInstalledContent(removed, 4).availability)
    }

    @Test
    fun quarantinedActiveDigestDoesNotFallBackToBundled() {
        val record = baseRecord(
            bundled = storedPackage(ContentSource.BUNDLED, "a", "asset://a"),
            downloaded = storedPackage(ContentSource.DOWNLOADED, "b", "files://b"),
        )
        val quarantined = transitionQuarantine(
            record,
            digest = "b".repeat(64),
            error = InstallError(InstallErrorCode.INTEGRITY, "member digest mismatch"),
        )
        val resolved = resolveInstalledContent(quarantined, 4)
        assertEquals(ContentAvailability.QUARANTINED, resolved.availability)
        assertNull(resolved.activeSource)
        assertEquals("b".repeat(64), resolved.quarantinedDigest)
    }

    @Test
    fun removedCatalogEntryRetainsItsLastKnownBrowseMetadata() {
        val record = baseRecord(
            catalogListed = false,
            bundled = storedPackage(ContentSource.BUNDLED, "a", "asset://a"),
        )
        val resolved = resolveInstalledContent(record, 4)
        assertFalse(resolved.metadata.catalogListed)
        assertEquals("putnam", resolved.metadata.collectionId)
        assertEquals("2025", resolved.metadata.setId)
        assertEquals(listOf("number-theory"), resolved.metadata.topics)
        assertEquals(ContentAvailability.INSTALLED, resolved.availability)
    }

    @Test
    fun unsupportedPackageIsVisibleButNotActive() {
        val record = baseRecord(bundled = storedPackage(ContentSource.BUNDLED, "a", "asset://a", minAppVersionCode = 99))
        val resolved = resolveInstalledContent(record, currentAppVersionCode = 4)
        assertEquals(ContentAvailability.APP_UPDATE_REQUIRED, resolved.availability)
        assertNull(resolved.activeSource)
    }

    @Test
    fun interruptedOperationsBecomeRetryableWithoutInstallingAnything() {
        listOf(OperationState.QUEUED, OperationState.DOWNLOADING, OperationState.VERIFYING, OperationState.ACTIVATING).forEach { state ->
            val recovered = recoverInterruptedOperation(operation(state))
            assertEquals(OperationState.FAILED, recovered.state)
            assertEquals(InstallErrorCode.INTERRUPTED, recovered.error?.code)
            assertEquals(1, recovered.attemptCount)
        }
        assertEquals(OperationState.FAILED, recoverInterruptedOperation(operation(OperationState.FAILED)).state)
        assertEquals(OperationState.COMPLETE, recoverInterruptedOperation(operation(OperationState.COMPLETE)).state)
    }

    private fun baseRecord(
        catalogListed: Boolean = true,
        bundled: StoredPackage? = null,
        downloaded: StoredPackage? = null,
    ) = ContentIndexRecord(
        metadata = ContentMetadata(
            key = "putnam/2025/A1",
            contentType = CatalogContentType.PROBLEM,
            label = "Putnam 2025 A1",
            collectionId = "putnam",
            setId = "2025",
            problemId = "A1",
            topics = listOf("number-theory"),
            categoryId = null,
            articleId = null,
            aliases = emptyList(),
            keywords = emptyList(),
            displayOrder = 1,
            catalogListed = catalogListed,
        ),
        bundled = bundled,
        downloaded = downloaded,
        logicalRemoval = false,
        desiredInstalled = true,
        quarantinedDigest = null,
        quarantineError = null,
        lastOperationError = null,
    )

    private fun storedPackage(source: ContentSource, digestChar: String, location: String, minAppVersionCode: Int = 1) =
        StoredPackage(source, descriptor(digestChar, minAppVersionCode), location)

    private fun descriptor(digestChar: String, minAppVersionCode: Int) = PackageDescriptor(
        contentType = CatalogContentType.PROBLEM,
        packageRevision = digestChar.repeat(64),
        packageSchemaVersion = 1,
        contentSchemaVersion = 3,
        richDocumentVersion = 1,
        path = "packages/problem/$digestChar.zip",
        compressedBytes = 100,
        uncompressedBytes = 200,
        sha256 = digestChar.repeat(64),
        minAppVersionCode = minAppVersionCode,
    )

    private fun operation(state: OperationState) = PackageOperation(
        id = "operation-$state",
        action = PackageAction.INSTALL,
        contentKey = "putnam/2025/A1",
        state = state,
        bytesCompleted = 0,
        bytesTotal = 100,
        attemptCount = 0,
        error = null,
        updatedAt = 1,
    )
}
