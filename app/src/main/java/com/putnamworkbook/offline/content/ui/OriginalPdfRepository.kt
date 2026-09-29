package com.putnamworkbook.offline.content.ui

import android.content.res.AssetManager
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class OriginalPdfRepository(
    private val assets: AssetManager,
    private val cacheDirectory: File,
) {
    @WorkerThread
    fun materialize(assetPath: String, sha256: String): Result<File> = runCatching {
        require(isSafeAssetPath(assetPath)) { "Unsafe PDF asset path: $assetPath" }
        require(SHA_256.matches(sha256)) { "PDF checksum must be 64 lowercase hexadecimal characters" }

        Files.createDirectories(cacheDirectory.toPath())
        val expectedRoot = cacheDirectory.canonicalFile
        val destination = File(expectedRoot, "$sha256.pdf").canonicalFile
        require(destination.parentFile == expectedRoot) { "PDF cache destination escaped its root" }

        if (destination.exists()) {
            if (destination.isFile && destination.sha256() == sha256) {
                return@runCatching destination
            }
            if (!destination.delete()) {
                throw IOException("Invalid cached PDF could not be replaced")
            }
        }

        val temporary = File(expectedRoot, ".${sha256}.${UUID.randomUUID()}.tmp")
        try {
            val actualDigest = copyAndDigest(assetPath, temporary)
            if (actualDigest != sha256) {
                throw IOException("Bundled PDF checksum mismatch for $assetPath")
            }
            moveAtomically(temporary, destination)
            if (destination.sha256() != sha256) {
                destination.delete()
                throw IOException("Cached PDF failed verification for $assetPath")
            }
            destination
        } finally {
            temporary.delete()
        }
    }

    private fun copyAndDigest(assetPath: String, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        return digest.digest().toHex()
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun isSafeAssetPath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.contains('\\') &&
            path.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
