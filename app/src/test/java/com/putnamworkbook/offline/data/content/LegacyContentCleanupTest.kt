package com.putnamworkbook.offline.data.content

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyContentCleanupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cleanupWaitsForSuccessfulPackageInitializationAndDeletesOnlyLegacyContentState() {
        val root = temporaryFolder.newFolder("app-data").toPath()
        val database = root.resolve("databases/content.db")
        val userDatabase = root.resolve("databases/user.db")
        val notebook = root.resolve("files/notebooks/workbook.json")
        val packageFile = root.resolve("files/content-library/packages/keep.zip")
        listOf(database, userDatabase, notebook, packageFile).forEach { path ->
            Files.createDirectories(path.parent)
            Files.writeString(path, "keep-or-delete")
        }
        val sidecars = listOf("-wal", "-shm", "-journal").map { suffix ->
            database.resolveSibling("${database.fileName}$suffix").also { Files.writeString(it, "legacy") }
        }
        val cleared = AtomicInteger(0)
        val cleanup = LegacyContentCleanup(database) { cleared.incrementAndGet() }

        cleanup.cleanupAfterPackageInitialization(packageInitializationSucceeded = false)

        assertTrue(Files.exists(database))
        sidecars.forEach { assertTrue(Files.exists(it)) }
        assertEquals(0, cleared.get())

        cleanup.cleanupAfterPackageInitialization(packageInitializationSucceeded = true)
        cleanup.cleanupAfterPackageInitialization(packageInitializationSucceeded = true)

        assertFalse(Files.exists(database))
        sidecars.forEach { assertFalse(Files.exists(it)) }
        assertTrue(Files.exists(userDatabase))
        assertTrue(Files.exists(notebook))
        assertTrue(Files.exists(packageFile))
        assertEquals(2, cleared.get())
    }
}
