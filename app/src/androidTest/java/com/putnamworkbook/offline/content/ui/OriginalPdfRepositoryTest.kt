package com.putnamworkbook.offline.content.ui

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginalPdfRepositoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assetContext = instrumentation.context
    private val targetContext = instrumentation.targetContext
    private lateinit var cacheRoot: File
    private lateinit var repository: OriginalPdfRepository

    @Before
    fun setUp() {
        cacheRoot = File(targetContext.cacheDir, "pdf-repository-test-${UUID.randomUUID()}")
        repository = OriginalPdfRepository(assetContext.assets, cacheRoot)
    }

    @After
    fun tearDown() {
        cacheRoot.deleteRecursively()
    }

    @Test
    fun materializeVerifiesChecksumCachesByDigestAndOpensAllPages() {
        val source = assetContext.assets.open(FIXTURE_PATH).use { it.readBytes() }
        val digest = source.sha256()
        cacheRoot.mkdirs()
        File(cacheRoot, "$digest.pdf").writeText("damaged cache")

        val first = repository.materialize(FIXTURE_PATH, digest).getOrThrow()
        val second = repository.materialize(FIXTURE_PATH, digest).getOrThrow()

        assertEquals(first.canonicalFile, second.canonicalFile)
        assertEquals("$digest.pdf", first.name)
        assertArrayEquals(source, first.readBytes())
        ParcelFileDescriptor.open(first, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(2, renderer.pageCount)
            }
        }
    }

    @Test
    fun corruptOrMissingPdfFailsWithoutDisablingNormalizedContent() {
        val wrongDigest = "0".repeat(64)

        assertTrue(repository.materialize(FIXTURE_PATH, wrongDigest).isFailure)
        assertTrue(repository.materialize("pdf/missing.pdf", wrongDigest).isFailure)
        assertFalse(File(cacheRoot, "$wrongDigest.pdf").exists())

        val localContent = LocalContentClient(assetContext)
        val normalized = localContent.interceptForTest(
            Uri.parse("${LocalContentClient.ASSET_BASE_URL}content/fixture/index.html"),
        )
        assertNotNull(normalized)
        assertEquals(200, normalized!!.statusCode)
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val FIXTURE_PATH = "pdf/fixture.pdf"
    }
}
