package com.putnamworkbook.offline.data.library.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpsCatalogClientTest {
    @Test
    fun productionRequestDisablesRedirectsAndUsesHttps() = runBlocking {
        var observed: CatalogHttpRequest? = null
        val client = HttpsCatalogClient(CatalogOriginPolicy(), CatalogTransport { request ->
            observed = request
            CatalogHttpResponse(304, emptyMap(), ByteArrayInputStream(byteArrayOf()))
        })

        client.fetch(null)

        assertTrue(observed!!.url.toString().startsWith("https://akirakato1.github.io/MyPutnam/"))
        assertFalse(observed!!.followRedirects)
    }
}
