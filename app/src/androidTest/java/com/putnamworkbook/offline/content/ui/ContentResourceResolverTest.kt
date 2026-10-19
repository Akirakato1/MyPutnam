package com.putnamworkbook.offline.content.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.putnamworkbook.offline.data.content.ContentResourceRef
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.index.ContentAvailability
import com.putnamworkbook.offline.data.library.index.ContentMetadata
import com.putnamworkbook.offline.data.library.index.ContentSource
import com.putnamworkbook.offline.data.library.index.InstalledContentSnapshot
import com.putnamworkbook.offline.data.library.index.ResolvedInstalledContent
import com.putnamworkbook.offline.data.library.packages.sha256
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentResourceResolverTest {
    @Test
    fun fixedShellServesOnlyActiveHashBoundDocumentAndDetectsTampering() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val digest = "a".repeat(64)
        val root = context.cacheDir.toPath().resolve("resolver-${System.nanoTime()}")
        val packageRoot = root.resolve(digest)
        val member = packageRoot.resolve("documents/statement.fragment.html")
        Files.createDirectories(member.parent)
        val bytes = "<main><p>Safe math</p></main>".encodeToByteArray()
        Files.write(member, bytes)
        val item = ResolvedInstalledContent(
            ContentMetadata("putnam/2025/A1", CatalogContentType.PROBLEM, "A1", "putnam", "2025", "A1", listOf("algebra"), null, null, emptyList(), emptyList(), 1, true),
            null, null, ContentAvailability.INSTALLED, ContentSource.DOWNLOADED, digest, packageRoot.toString(), null, null, null,
        )
        val quarantined = mutableListOf<String>()
        val resolver = FileContentResourceResolver(
            context = context,
            packagesRoot = root,
            snapshot = { InstalledContentSnapshot(1, listOf(item)) },
            onQuarantine = { _, badDigest, _ -> quarantined += badDigest },
        )
        val ref = ContentResourceRef(item.metadata.key, digest, "documents/statement.fragment.html", sha256(bytes))

        val html = resolver.readDocument(ref)
        assertTrue(html.contains("Content-Security-Policy"))
        assertTrue(html.contains("Safe math"))
        assertTrue(resolver.urlFor(ref).toString().startsWith("https://appassets.androidplatform.net/document/"))

        Files.writeString(member, "tampered")
        assertThrows(ContentResourceIntegrityException::class.java) { resolver.readDocument(ref) }
        assertTrue(quarantined.contains(digest))
    }
}
