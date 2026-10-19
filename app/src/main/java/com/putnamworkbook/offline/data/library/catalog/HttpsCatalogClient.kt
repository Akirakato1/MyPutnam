package com.putnamworkbook.offline.data.library.catalog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

data class CatalogHttpRequest(
    val url: URL,
    val headers: Map<String, List<String>>,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val maxResponseBytes: Int,
    val followRedirects: Boolean = false,
)

data class CatalogHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: InputStream,
)

fun interface CatalogTransport {
    suspend fun execute(request: CatalogHttpRequest): CatalogHttpResponse
}

class HttpsCatalogClient(
    private val originPolicy: CatalogOriginPolicy,
    private val transport: CatalogTransport = UrlConnectionCatalogTransport(),
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maxCatalogBytes: Int = DEFAULT_MAX_CATALOG_BYTES,
) : CatalogClient {
    init {
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(readTimeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(maxCatalogBytes in 1..DEFAULT_MAX_CATALOG_BYTES)
    }

    override suspend fun fetch(validators: CatalogValidators?): CatalogFetchResult {
        val headers = buildMap {
            validators?.etag?.let { put("If-None-Match", listOf(it)) }
            validators?.lastModified?.let { put("If-Modified-Since", listOf(it)) }
            put("Accept", listOf("application/json"))
        }
        return try {
            val response = transport.execute(
                CatalogHttpRequest(
                    url = originPolicy.catalogUrl,
                    headers = headers,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                    maxResponseBytes = maxCatalogBytes,
                    followRedirects = false,
                ),
            )
            response.body.use { body ->
                when (response.statusCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> CatalogFetchResult.NotModified
                    HttpURLConnection.HTTP_OK -> {
                        val bytes = body.readBounded(maxCatalogBytes)
                            ?: return CatalogFetchResult.Failed("Catalog response exceeded $maxCatalogBytes bytes")
                        CatalogFetchResult.Modified(
                            bytes = bytes,
                            validators = CatalogValidators(
                                etag = response.firstHeader("ETag"),
                                lastModified = response.firstHeader("Last-Modified"),
                            ),
                        )
                    }
                    in 300..399 -> CatalogFetchResult.Failed("Catalog redirects are not allowed")
                    else -> CatalogFetchResult.Failed("Catalog request failed with HTTP ${response.statusCode}")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CatalogFetchResult.Failed(error.message?.take(512) ?: "Catalog request failed")
        }
    }

    private fun CatalogHttpResponse.firstHeader(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()?.take(1_024)

    private fun InputStream.readBounded(maximum: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maximum, 32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maximum) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_MAX_CATALOG_BYTES = 4 * 1024 * 1024
        private const val MAX_TIMEOUT_MILLIS = 30_000
    }
}

class UrlConnectionCatalogTransport : CatalogTransport {
    override suspend fun execute(request: CatalogHttpRequest): CatalogHttpResponse = withContext(Dispatchers.IO) {
        require(request.url.protocol == "https") { "Catalog transport requires HTTPS" }
        val connection = (request.url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = request.connectTimeoutMillis
            readTimeout = request.readTimeoutMillis
            instanceFollowRedirects = request.followRedirects
            useCaches = false
            requestMethod = "GET"
            request.headers.forEach { (name, values) -> values.forEach { addRequestProperty(name, it) } }
        }
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection.disconnect()
        }
        try {
            val status = connection.responseCode
            val body = when {
                status == HttpURLConnection.HTTP_NOT_MODIFIED -> ByteArrayInputStream(byteArrayOf())
                status in 200..299 -> connection.inputStream
                else -> connection.errorStream ?: ByteArrayInputStream(byteArrayOf())
            }
            CatalogHttpResponse(
                statusCode = status,
                headers = connection.headerFields.filterKeys { it != null },
                body = DisconnectingInputStream(body, connection, cancellation),
            )
        } catch (error: Exception) {
            cancellation?.dispose()
            connection.disconnect()
            throw error
        }
    }
}

private class DisconnectingInputStream(
    delegate: InputStream,
    private val connection: HttpsURLConnection,
    private val cancellation: kotlinx.coroutines.DisposableHandle?,
) : FilterInputStream(delegate) {
    override fun close() {
        try {
            super.close()
        } finally {
            cancellation?.dispose()
            connection.disconnect()
        }
    }
}
