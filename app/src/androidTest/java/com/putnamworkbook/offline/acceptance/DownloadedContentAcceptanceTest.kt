package com.putnamworkbook.offline.acceptance

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.putnamworkbook.offline.data.library.catalog.*
import com.putnamworkbook.offline.data.library.index.*
import com.putnamworkbook.offline.data.user.*
import com.putnamworkbook.offline.domain.model.ProblemKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadedContentAcceptanceTest {
    @Test
    fun bundledDeleteRestoreAndInterruptedRecoveryNeverAlterProblemProgress() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val indexDb = Room.inMemoryDatabaseBuilder(context, LibraryIndexDatabase::class.java).build()
        val userDb = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).build()
        try {
            val index = RoomInstalledContentIndex(indexDb, 4)
            val key = ProblemKey("putnam", "2025", "A1")
            val progress = RoomProgressRepository(userDb, clock = { 10L })
            index.reconcileBundled(acceptanceCatalog())
            progress.markOpened(key)
            progress.revealNextHint(key)
            val expected = progress.observe(key).first()

            index.logicallyRemove(key.canonical)
            assertEquals(ContentAvailability.LOGICALLY_REMOVED, index.observe().first().items.single().availability)
            assertEquals(expected, progress.observe(key).first())

            index.restoreBundled(key.canonical)
            assertEquals(ContentAvailability.INSTALLED, index.observe().first().items.single().availability)
            assertEquals(expected, progress.observe(key).first())

            index.upsertOperation(
                PackageOperation("interrupted", PackageAction.UPDATE, key.canonical, OperationState.VERIFYING, 10, 20, 0, null, 1),
            )
            index.recoverInterruptedOperations(2)
            val recovered = index.observeOperations().first().single()
            assertEquals(OperationState.FAILED, recovered.state)
            assertEquals(InstallErrorCode.INTERRUPTED, recovered.error?.code)
            assertTrue(index.observe().first().items.single().availability == ContentAvailability.INSTALLED)
        } finally {
            indexDb.close()
            userDb.close()
        }
    }
}

private fun acceptanceCatalog(): PublicationCatalog {
    val descriptor = PackageDescriptor(
        CatalogContentType.PROBLEM, "revision", 1, 3, 1, "packages/a.zip",
        100, 200, "a".repeat(64), 1,
    )
    val rights = CatalogRights("https://example.com", "Example", null, "permission", "cleared")
    return PublicationCatalog(
        1, "test", "b".repeat(64), "2026-01-01T00:00:00Z", 1,
        listOf(CatalogCollection("putnam", "Putnam", "competition", 0)),
        listOf(CatalogTopic("algebra", "Algebra", 0)),
        emptyList(),
        listOf(CatalogProblem("putnam/2025/A1", "putnam", "2025", "A1", "A1", 1, listOf("algebra"), rights, descriptor)),
        emptyList(),
    )
}
