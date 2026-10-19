package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.library.catalog.CatalogHttpRequest
import com.putnamworkbook.offline.data.library.catalog.CatalogOriginPolicy
import com.putnamworkbook.offline.data.library.catalog.CatalogTransport
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import com.putnamworkbook.offline.data.library.catalog.UrlConnectionCatalogTransport
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadedPackage(val path: Path) : AutoCloseable {
    override fun close() { Files.deleteIfExists(path) }
}

interface PackageDownloader {
    suspend fun recoverInterruptedState()
    suspend fun download(entry: CatalogInstallEntry, onProgress: suspend (Long, Long) -> Unit): DownloadedPackage
}

class PackageDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

class HttpsPackageDownloader(
    root: Path,
    private val originPolicy: CatalogOriginPolicy,
    private val transport: CatalogTransport = UrlConnectionCatalogTransport(),
) : PackageDownloader {
    private val root = root.toAbsolutePath().normalize()

    override suspend fun recoverInterruptedState() = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().startsWith(DOWNLOAD_PREFIX) }.forEach(Files::deleteIfExists)
        }
    }

    override suspend fun download(
        entry: CatalogInstallEntry,
        onProgress: suspend (Long, Long) -> Unit,
    ): DownloadedPackage = withContext(Dispatchers.IO) {
        val descriptor = entry.descriptor
        require(descriptor.compressedBytes in 1..MAX_PACKAGE_BYTES)
        Files.createDirectories(root)
        val target = root.resolve("$DOWNLOAD_PREFIX${UUID.randomUUID()}.zip")
        try {
            val response = transport.execute(
                CatalogHttpRequest(
                    url = originPolicy.resolvePackage(descriptor.path),
                    headers = mapOf("Accept" to listOf("application/zip")),
                    connectTimeoutMillis = 10_000,
                    readTimeoutMillis = 30_000,
                    maxResponseBytes = descriptor.compressedBytes.toInt(),
                    followRedirects = false,
                ),
            )
            response.body.use { input ->
                if (response.statusCode in 300..399) throw PackageDownloadException("Package redirects are not allowed")
                if (response.statusCode != HttpURLConnection.HTTP_OK) throw PackageDownloadException("Package request failed with HTTP ${response.statusCode}")
                val contentLength = response.headers.entries
                    .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                    ?.value?.firstOrNull()?.toLongOrNull()
                if (contentLength != null && contentLength != descriptor.compressedBytes) {
                    throw PackageDownloadException("Package Content-Length does not match catalog descriptor")
                }
                FileOutputStream(target.toFile()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > descriptor.compressedBytes) throw PackageDownloadException("Package response exceeded declared size")
                        output.write(buffer, 0, count)
                        onProgress(total, descriptor.compressedBytes)
                    }
                    output.fd.sync()
                    if (total != descriptor.compressedBytes) throw PackageDownloadException("Package response was truncated")
                }
            }
            DownloadedPackage(target)
        } catch (error: Exception) {
            Files.deleteIfExists(target)
            if (error is PackageDownloadException) throw error
            throw PackageDownloadException(error.message ?: "Package download failed", error)
        }
    }

    private companion object {
        const val DOWNLOAD_PREFIX = ".download-"
        const val MAX_PACKAGE_BYTES = 16L * 1024 * 1024
    }
}
