package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilePackageStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun stagesThenAtomicallyActivatesAndIsIdempotentByDigest() = runBlocking {
        val root = temporaryFolder.newFolder("packages").toPath()
        val archive = archiveBytes()
        val descriptor = descriptor(archive)
        val store = store(root, archive)

        val first = store.activate(store.stage(KEY, descriptor, ByteArrayInputStream(archive)))
        val second = store.activate(store.stage(KEY, descriptor, ByteArrayInputStream(archive)))

        assertEquals(first, second)
        assertArrayEquals("{}\n".encodeToByteArray(), Files.readAllBytes(first.root.resolve("source/problem.json")))
        assertEquals(descriptor.sha256, first.root.fileName.toString())
        assertTrue(Files.list(root).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".staging-") } })
    }

    @Test
    fun bundledExtractionUsesTheSameVerifiedStagingPath() = runBlocking {
        val root = temporaryFolder.newFolder("bundled").toPath()
        val archive = archiveBytes()
        val location = store(root, archive).ensureBundledExtracted(KEY, descriptor(archive))
        assertTrue(Files.isRegularFile(location.root.resolve("package.json")))
    }

    @Test
    fun recoveryRemovesOnlyOwnedStagingAndTombstoneSiblings() = runBlocking {
        val root = temporaryFolder.newFolder("recover").toPath()
        Files.createDirectories(root.resolve(".staging-old"))
        Files.createDirectories(root.resolve(".tombstone-old"))
        Files.createDirectories(root.resolve("keep"))

        store(root, archiveBytes()).recoverInterruptedState()

        assertFalse(Files.exists(root.resolve(".staging-old")))
        assertFalse(Files.exists(root.resolve(".tombstone-old")))
        assertTrue(Files.isDirectory(root.resolve("keep")))
    }

    @Test
    fun failedActivationCleansStageWithoutOverwritingCollision() = runBlocking {
        val root = temporaryFolder.newFolder("collision").toPath()
        val archive = archiveBytes()
        val descriptor = descriptor(archive)
        val stage = store(root, archive).stage(KEY, descriptor, ByteArrayInputStream(archive))
        Files.write(root.resolve(descriptor.sha256), byteArrayOf(7))

        assertThrows(Exception::class.java) { runBlocking { store(root, archive).activate(stage) } }
        assertFalse(Files.exists(stage.stagingRoot))
        assertArrayEquals(byteArrayOf(7), Files.readAllBytes(root.resolve(descriptor.sha256)))
    }

    @Test
    fun deleteRejectsLocationsOutsideTheImmutablePackageRoot() = runBlocking {
        val root = temporaryFolder.newFolder("containment").toPath()
        val outside = temporaryFolder.newFile("outside").toPath()
        val store = store(root, archiveBytes())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.delete(PackageLocation(outside, "a".repeat(64))) }
        }
        assertTrue(Files.exists(outside))
    }

    private fun store(root: java.nio.file.Path, archive: ByteArray) = FilePackageStore(
        root = root,
        verifier = PackageVerifier { _, descriptor, contentKey ->
            assertEquals(KEY, contentKey)
            assertEquals(sha256(archive), descriptor.sha256)
            VerifiedPackage(envelope(descriptor.sha256))
        },
        bundledPackageOpener = { ByteArrayInputStream(archive) },
    )

    private fun archiveBytes(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            listOf("package.json" to "{}\n", "source/problem.json" to "{}\n").forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.encodeToByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun descriptor(bytes: ByteArray) = PackageDescriptor(
        CatalogContentType.PROBLEM, "b".repeat(64), 1, 3, 1,
        "packages/problem/test.zip", bytes.size.toLong(), 3, sha256(bytes), 1,
    )

    private fun envelope(digest: String) = PackageEnvelope(
        1, CatalogContentType.PROBLEM, KEY, 3, 1, 1, 3,
        listOf(PackageMemberDeclaration("source/problem.json", "application/json", 3, digest)),
        "b".repeat(64),
    )

    companion object { private const val KEY = "putnam/2025/A1" }
}
