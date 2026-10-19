package com.putnamworkbook.offline.data.library.packages

import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlFragmentAuditorTest {
    private val auditor = HtmlFragmentAuditor()

    @Test
    fun acceptsPassiveLocalFragment() {
        assertTrue(auditor.audit("<main><p><a href=\"#proof\">Proof</a></p><img src=\"assets/plot.png\"></main>".encodeToByteArray()).isEmpty())
    }

    @Test
    fun rejectsActiveShellAndRemoteOrEncodedUrls() {
        listOf(
            "<script>alert(1)</script>",
            "<iframe src=\"assets/x\"></iframe>",
            "<p onclick=\"go()\">x</p>",
            "<img src=\"https://example.com/x.png\">",
            "<a href=\"assets/a&amp;b\">x</a>",
            "<p style=\"background:url(https://example.com/x)\">x</p>",
        ).forEach { source ->
            assertTrue(source, auditor.audit(source.encodeToByteArray()).isNotEmpty())
        }
    }

    @Test
    fun rejectsInvalidUtf8() {
        assertTrue(auditor.audit(byteArrayOf(0xc3.toByte(), 0x28)).isNotEmpty())
    }
}
