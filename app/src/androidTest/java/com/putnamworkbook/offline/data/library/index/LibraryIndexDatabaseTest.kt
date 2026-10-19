package com.putnamworkbook.offline.data.library.index

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import com.putnamworkbook.offline.data.user.ProblemProgressEntity
import com.putnamworkbook.offline.data.user.UserDatabase
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryIndexDatabaseTest {
    private lateinit var context: Context
    private lateinit var libraryDatabase: LibraryIndexDatabase
    private lateinit var userDatabase: UserDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        libraryDatabase = Room.inMemoryDatabaseBuilder(context, LibraryIndexDatabase::class.java).build()
        userDatabase = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        libraryDatabase.close()
        userDatabase.close()
    }

    @Test
    fun libraryMutationsDoNotChangeUserDatabaseOrNotebookTree() = runBlocking {
        val progress = ProblemProgressEntity("putnam/2025/A1", "IN_PROGRESS", 2, false, 1, 0, "PROBLEM", 10L)
        userDatabase.userDao().upsertProgress(progress)
        val notebookRoot = context.filesDir.toPath().resolve("library-separation-${System.nanoTime()}")
        Files.createDirectories(notebookRoot)
        val notebook = notebookRoot.resolve("page.bin")
        Files.write(notebook, byteArrayOf(1, 2, 3))
        val beforeBytes = Files.readAllBytes(notebook)

        val index = RoomInstalledContentIndex(libraryDatabase, currentAppVersionCode = 4)
        index.activateDownloaded(downloadedPackage())

        assertEquals(progress, userDatabase.userDao().progress(progress.problemKey))
        assertArrayEquals(beforeBytes, Files.readAllBytes(notebook))
        assertEquals(ContentAvailability.INSTALLED, index.observe().first().items.single().availability)
    }

    @Test
    fun operationRecoveryPersistsRetryableFailureWithoutCreatingActiveContent() = runBlocking {
        val index = RoomInstalledContentIndex(libraryDatabase, currentAppVersionCode = 4)
        index.activateDownloaded(downloadedPackage())
        index.logicallyRemove("putnam/2025/A1")
        index.upsertOperation(
            PackageOperation("op", PackageAction.INSTALL, "putnam/2025/A1", OperationState.DOWNLOADING, 50, 100, 0, null, 1),
        )

        index.recoverInterruptedOperations(now = 2)

        val operation = index.observeOperations().first().single()
        assertEquals(OperationState.FAILED, operation.state)
        assertEquals(InstallErrorCode.INTERRUPTED, operation.error?.code)
        assertTrue(index.observe().first().items.single().activeSource == null)
    }

    private fun downloadedPackage() = InstalledPackage(
        metadata = ContentMetadata(
            key = "putnam/2025/A1",
            contentType = CatalogContentType.PROBLEM,
            label = "A1",
            collectionId = "putnam",
            setId = "2025",
            problemId = "A1",
            topics = listOf("number-theory"),
            categoryId = null,
            articleId = null,
            aliases = emptyList(),
            keywords = emptyList(),
            displayOrder = 1,
            catalogListed = true,
        ),
        descriptor = PackageDescriptor(
            CatalogContentType.PROBLEM,
            "a".repeat(64),
            1,
            3,
            1,
            "packages/problem/a.zip",
            100,
            200,
            "a".repeat(64),
            1,
        ),
        location = "files/content-library/packages/${"a".repeat(64)}",
    )
}
