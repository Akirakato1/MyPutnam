package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FilePackageStore(
    root: Path,
    private val verifier: PackageVerifier,
    private val bundledPackageOpener: BundledPackageOpener,
) : PackageStore {
    private val root = root.toAbsolutePath().normalize()

    override suspend fun recoverInterruptedState() = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        Files.list(root).use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                name.startsWith(STAGING_PREFIX) || name.startsWith(TOMBSTONE_PREFIX)
            }.forEach(::deleteTree)
        }
    }

    override suspend fun stage(
        contentKey: String,
        descriptor: PackageDescriptor,
        input: InputStream,
    ): StagedPackage = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        val stagingRoot = root.resolve("$STAGING_PREFIX${UUID.randomUUID()}")
        val archive = stagingRoot.resolve(ARCHIVE_FILE)
        val payload = stagingRoot.resolve(PAYLOAD_DIRECTORY)
        try {
            Files.createDirectory(stagingRoot)
            Files.createDirectory(payload)
            writeArchive(input, archive, descriptor.compressedBytes)
            val bytes = Files.readAllBytes(archive)
            val verified = verifier.validate(bytes, descriptor, contentKey)
            extractArchive(archive, payload)
            Files.write(payload.resolve(VERIFIED_MARKER), descriptor.sha256.encodeToByteArray())
            StagedPackage(contentKey, descriptor, verified.envelope, stagingRoot, payload)
        } catch (error: Exception) {
            deleteTree(stagingRoot)
            throw error
        }
    }

    override suspend fun activate(staged: StagedPackage): PackageLocation = withContext(Dispatchers.IO) {
        requireOwnedStaging(staged.stagingRoot)
        val finalRoot = root.resolve(staged.descriptor.sha256).normalize()
        try {
            if (Files.exists(finalRoot)) {
                if (!Files.isDirectory(finalRoot) || readMarker(finalRoot) != staged.descriptor.sha256) {
                    throw IllegalStateException("Immutable package digest collides with an invalid filesystem entry")
                }
                deleteTree(staged.stagingRoot)
                return@withContext PackageLocation(finalRoot, staged.descriptor.sha256)
            }
            try {
                Files.move(staged.payloadRoot, finalRoot, StandardCopyOption.ATOMIC_MOVE)
            } catch (error: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic package activation is not supported", error)
            }
            deleteTree(staged.stagingRoot)
            PackageLocation(finalRoot, staged.descriptor.sha256)
        } catch (error: Exception) {
            deleteTree(staged.stagingRoot)
            throw error
        }
    }

    override suspend fun ensureBundledExtracted(
        contentKey: String,
        descriptor: PackageDescriptor,
    ): PackageLocation = withContext(Dispatchers.IO) {
        val finalRoot = root.resolve(descriptor.sha256)
        if (Files.isDirectory(finalRoot) && readMarker(finalRoot) == descriptor.sha256) {
            return@withContext PackageLocation(finalRoot, descriptor.sha256)
        }
        bundledPackageOpener.open(descriptor.path).use { input -> activate(stage(contentKey, descriptor, input)) }
    }

    override suspend fun delete(location: PackageLocation) = withContext(Dispatchers.IO) {
        require(SHA256.matches(location.digest)) { "Invalid package digest" }
        val target = location.root.toAbsolutePath().normalize()
        require(target.parent == root && target.fileName.toString() == location.digest) { "Package location is outside the immutable root" }
        if (!Files.exists(target)) return@withContext
        val tombstone = root.resolve("$TOMBSTONE_PREFIX${location.digest}-${UUID.randomUUID()}")
        try {
            Files.move(target, tombstone, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Atomic package deletion is not supported", error)
        }
        deleteTree(tombstone)
    }

    private fun writeArchive(input: InputStream, target: Path, expectedBytes: Long) {
        FileOutputStream(target.toFile()).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > expectedBytes || total > MAX_COMPRESSED_BYTES) throw PackageValidationException("Downloaded package exceeds declared size")
                output.write(buffer, 0, count)
            }
            output.fd.sync()
            if (total != expectedBytes) throw PackageValidationException("Downloaded package size does not match descriptor")
        }
    }

    private fun extractArchive(archive: Path, payload: Path) {
        ZipInputStream(FileInputStream(archive.toFile())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name != "package.json" && !isSafeMemberPath(name)) throw PackageValidationException("Unsafe package member path")
                val target = payload.resolve(name).normalize()
                if (!target.startsWith(payload)) throw PackageValidationException("Package member escapes staging root")
                Files.createDirectories(target.parent)
                FileOutputStream(target.toFile()).use { output ->
                    zip.copyTo(output)
                    output.fd.sync()
                }
                zip.closeEntry()
            }
        }
    }

    private fun requireOwnedStaging(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.parent == root && normalized.fileName.toString().startsWith(STAGING_PREFIX)) {
            "Staged package is outside the package root"
        }
    }

    private fun readMarker(packageRoot: Path): String =
        Files.readAllBytes(packageRoot.resolve(VERIFIED_MARKER)).decodeToString()

    private fun deleteTree(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized != root && normalized.startsWith(root)) { "Refusing to delete outside package root" }
        if (!Files.exists(normalized)) return
        Files.walk(normalized).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val STAGING_PREFIX = ".staging-"
        const val TOMBSTONE_PREFIX = ".tombstone-"
        const val ARCHIVE_FILE = "archive.zip"
        const val PAYLOAD_DIRECTORY = "payload"
        const val VERIFIED_MARKER = ".verified-sha256"
        const val MAX_COMPRESSED_BYTES = 16L * 1024 * 1024
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
