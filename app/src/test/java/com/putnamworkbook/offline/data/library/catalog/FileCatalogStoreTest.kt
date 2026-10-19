package com.putnamworkbook.offline.data.library.catalog

import com.putnamworkbook.offline.data.library.storage.AtomicBytesStore
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileCatalogStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun embeddedCatalogIsImmediatelyUsableWithoutCache() = runBlocking {
        val store = store(embeddedCatalog("e"))
        store.initialize()

        assertEquals(CatalogSource.EMBEDDED, store.state.value.source)
        assertEquals("e".repeat(64), store.state.value.catalog?.catalogRevision)
        assertNull(store.state.value.lastError)
    }

    @Test
    fun validCachedCatalogOverridesEmbeddedAndRetainsValidators() = runBlocking {
        val root = temporaryFolder.newFolder("cached").toPath()
        val first = store(embeddedCatalog("e"), root)
        first.initialize()
        first.commitRemote(embeddedCatalog("f"), CatalogValidators("etag-r", "yesterday"), 42L)

        val reopened = store(embeddedCatalog("e"), root)
        reopened.initialize()

        assertEquals(CatalogSource.REMOTE_CACHE, reopened.state.value.source)
        assertEquals("f".repeat(64), reopened.state.value.catalog?.catalogRevision)
        assertEquals(CatalogValidators("etag-r", "yesterday"), reopened.state.value.validators)
        assertEquals(42L, reopened.state.value.lastSuccessfulRefreshAt)
    }

    @Test
    fun invalidCacheFallsBackAndStaleTemporaryFilesAreRemoved() = runBlocking {
        val root = temporaryFolder.newFolder("invalid").toPath()
        Files.write(root.resolve(FileCatalogStore.REMOTE_CATALOG_FILE), "{".encodeToByteArray())
        val stale = root.resolve("orphan.tmp")
        Files.write(stale, byteArrayOf(1))

        val store = store(embeddedCatalog("e"), root)
        store.initialize()

        assertEquals(CatalogSource.EMBEDDED, store.state.value.source)
        assertTrue(store.state.value.lastError!!.contains("cached", ignoreCase = true))
        assertTrue(Files.notExists(stale))
    }

    @Test
    fun failedReplacementPreservesPriorBytesAndFailureMetadata() = runBlocking {
        val root = temporaryFolder.newFolder("preserve").toPath()
        val store = store(embeddedCatalog("e"), root)
        store.initialize()
        val good = embeddedCatalog("f")
        store.commitRemote(good, CatalogValidators("good", null), 50L)

        try {
            store.commitRemote("{".encodeToByteArray(), CatalogValidators("bad", null), 60L)
        } catch (_: CatalogFormatException) {
            // Expected: invalid bytes are rejected before the atomic store is touched.
        }
        store.recordFailure("network down", 70L)

        assertArrayEquals(good, Files.readAllBytes(root.resolve(FileCatalogStore.REMOTE_CATALOG_FILE)))
        assertEquals("f".repeat(64), store.state.value.catalog?.catalogRevision)
        assertEquals(70L, store.state.value.lastAttemptedRefreshAt)
        assertEquals("network down", store.state.value.lastError)
    }

    @Test
    fun failedAtomicWriteLeavesPriorCatalogAndStateActive() {
        val root = temporaryFolder.newFolder("atomic-failure").toPath()
        val good = embeddedCatalog("f")
        runBlocking {
            store(embeddedCatalog("e"), root).apply {
                initialize()
                commitRemote(good, CatalogValidators("good", null), 50L)
            }
        }
        val failingBytesStore = object : AtomicBytesStore() {
            override fun write(target: java.nio.file.Path, bytes: ByteArray) {
                if (target.fileName.toString() == FileCatalogStore.REMOTE_CATALOG_FILE) {
                    throw IOException("simulated atomic replacement failure")
                }
                super.write(target, bytes)
            }
        }
        val store = FileCatalogStore(root, { embeddedCatalog("e") }, CatalogCodec(), failingBytesStore)
        runBlocking { store.initialize() }

        assertThrows(IOException::class.java) {
            runBlocking { store.commitRemote(embeddedCatalog("b"), CatalogValidators("new", null), 60L) }
        }

        assertArrayEquals(good, Files.readAllBytes(root.resolve(FileCatalogStore.REMOTE_CATALOG_FILE)))
        assertEquals("f".repeat(64), store.state.value.catalog?.catalogRevision)
        assertEquals(CatalogValidators("good", null), store.state.value.validators)
    }

    private fun store(bytes: ByteArray, root: java.nio.file.Path = temporaryFolder.newFolder().toPath()) =
        FileCatalogStore(root = root, embeddedCatalog = { bytes }, codec = CatalogCodec())

    private fun embeddedCatalog(revisionChar: String) = """
        {"schemaVersion":1,"catalogId":"test","catalogRevision":"${revisionChar.repeat(64)}","generatedAt":"2026-10-18T00:00:00.000Z","minAppVersionCode":1,"collections":[],"topics":[],"referenceCategories":[],"problems":[],"references":[]}
    """.trimIndent().encodeToByteArray()
}
