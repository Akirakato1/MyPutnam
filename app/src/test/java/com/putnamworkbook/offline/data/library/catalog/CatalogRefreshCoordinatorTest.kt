package com.putnamworkbook.offline.data.library.catalog

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRefreshCoordinatorTest {
    @Test
    fun clientSendsValidatorsAndAcceptsBounded200() = runBlocking {
        var request: CatalogHttpRequest? = null
        val client = HttpsCatalogClient(
            originPolicy = CatalogOriginPolicy(),
            transport = CatalogTransport {
                request = it
                CatalogHttpResponse(200, mapOf("ETag" to listOf("new"), "Last-Modified" to listOf("today")), ByteArrayInputStream(byteArrayOf(1, 2)))
            },
            maxCatalogBytes = 2,
        )

        val result = client.fetch(CatalogValidators("old", "yesterday")) as CatalogFetchResult.Modified

        assertEquals(listOf("old"), request!!.headers["If-None-Match"])
        assertEquals(listOf("yesterday"), request!!.headers["If-Modified-Since"])
        assertTrue(request!!.connectTimeoutMillis in 1..30_000)
        assertTrue(request!!.readTimeoutMillis in 1..30_000)
        assertEquals(false, request!!.followRedirects)
        assertEquals(CatalogValidators("new", "today"), result.validators)
    }

    @Test
    fun clientHandles304LimitsHttpErrorsAndRejectsRedirects() = runBlocking {
        suspend fun fetch(response: CatalogHttpResponse, max: Int = 4) = HttpsCatalogClient(
            CatalogOriginPolicy(), CatalogTransport { response }, maxCatalogBytes = max,
        ).fetch(null)

        assertEquals(CatalogFetchResult.NotModified, fetch(CatalogHttpResponse(304, emptyMap(), ByteArrayInputStream(byteArrayOf()))))
        assertTrue(fetch(CatalogHttpResponse(500, emptyMap(), ByteArrayInputStream(byteArrayOf()))) is CatalogFetchResult.Failed)
        assertTrue(fetch(CatalogHttpResponse(302, mapOf("Location" to listOf("https://evil.example/catalog.json")), ByteArrayInputStream(byteArrayOf()))) is CatalogFetchResult.Failed)
        assertTrue(fetch(CatalogHttpResponse(200, emptyMap(), ByteArrayInputStream(ByteArray(5))), max = 4) is CatalogFetchResult.Failed)
    }

    @Test
    fun cancellationIsNeverConvertedIntoFailure() {
        val client = HttpsCatalogClient(CatalogOriginPolicy(), CatalogTransport { throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) { runBlocking { client.fetch(null) } }
    }

    @Test
    fun originPolicyRejectsAbsoluteCrossOriginAndEscapingPaths() {
        val policy = CatalogOriginPolicy()
        assertEquals("https://akirakato1.github.io/MyPutnam/content/v1/catalog.json", policy.catalogUrl.toString())
        assertEquals("https://akirakato1.github.io/MyPutnam/content/v1/packages/problem/a.zip", policy.resolvePackage("packages/problem/a.zip").toString())
        listOf("https://evil.example/a.zip", "/absolute.zip", "../escape.zip", "packages\\bad.zip").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { policy.resolvePackage(path) }
        }
    }

    @Test
    fun refreshCommitsOnlyModifiedCatalogAndRecords304() = runBlocking {
        val store = RecordingStore()
        val modifiedBytes = byteArrayOf(7)
        CatalogRefreshCoordinator(store, CatalogClient { CatalogFetchResult.Modified(modifiedBytes, CatalogValidators("e", null)) }, clock = { 100L }).refresh()
        assertEquals(1, store.commitCount)
        assertEquals(0, store.packageMutationCount)

        CatalogRefreshCoordinator(store, CatalogClient { CatalogFetchResult.NotModified }, clock = { 200L }).refresh()
        assertEquals(200L, store.state.value.lastSuccessfulRefreshAt)
        assertEquals(0, store.packageMutationCount)
    }

    private class RecordingStore : CatalogStore {
        override val state = MutableStateFlow(CatalogState())
        var commitCount = 0
        var packageMutationCount = 0
        override suspend fun initialize() = Unit
        override suspend fun commitRemote(bytes: ByteArray, validators: CatalogValidators, refreshedAt: Long) {
            commitCount += 1
            state.value = state.value.copy(validators = validators, lastSuccessfulRefreshAt = refreshedAt, lastAttemptedRefreshAt = refreshedAt)
        }
        override suspend fun recordNotModified(validators: CatalogValidators, refreshedAt: Long) {
            state.value = state.value.copy(validators = validators, lastSuccessfulRefreshAt = refreshedAt, lastAttemptedRefreshAt = refreshedAt)
        }
        override suspend fun recordFailure(message: String, attemptedAt: Long) {
            state.value = state.value.copy(lastAttemptedRefreshAt = attemptedAt, lastError = message)
        }
    }
}
