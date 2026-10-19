package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.data.library.catalog.*
import com.putnamworkbook.offline.data.library.index.*
import com.putnamworkbook.offline.data.library.packages.PackageEnvelope
import com.putnamworkbook.offline.data.library.packages.PackageMemberDeclaration
import com.putnamworkbook.offline.domain.model.ReferenceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CompositeReferenceRepositoryTest {
    @Test
    fun exposesOnlyActiveInstalledArticlesAndTracksCatalogRefresh() = runBlocking {
        var catalog = catalog("Euler totient")
        val state = MutableStateFlow(InstalledContentSnapshot(1, listOf(
            item(ContentAvailability.INSTALLED, ContentSource.DOWNLOADED, "d"),
            item(ContentAvailability.LOGICALLY_REMOVED, ContentSource.BUNDLED, "b", "fermat-euler"),
        )))
        val repository = CompositeReferenceRepository({ catalog }, state, FakeReader())

        assertEquals(listOf("number-theory"), repository.observeCategories().first().map { it.id })
        assertEquals(listOf("Euler totient"), repository.observeInstalledArticles().first().map { it.title })
        catalog = catalog("Euler's totient function")
        state.value = state.value.copy(revision = 2)
        assertEquals("Euler's totient function", repository.observeInstalledArticles().first().single().title)
        assertNull(repository.article(ReferenceKey("number-theory", "fermat-euler")))
    }

    @Test
    fun decodesHashBoundDocumentRelationsAndAttribution() = runBlocking {
        val state = MutableStateFlow(InstalledContentSnapshot(1, listOf(item(ContentAvailability.INSTALLED, ContentSource.DOWNLOADED, "d"))))
        val repository = CompositeReferenceRepository({ catalog("Euler totient") }, state, FakeReader())
        val article = repository.article(ReferenceKey("number-theory", "euler-totient"))!!

        assertEquals("documents/article.fragment.html", article.document.memberPath)
        assertEquals("d".repeat(64), article.document.packageSha256)
        assertEquals("fermat-euler", article.relatedReferences.single().articleId)
        assertEquals("A1", article.relatedProblems.single().problemId)
        assertEquals("Reference source", article.attribution.single().label)
    }

    @Test
    fun rejectsSourceOrEnvelopeIdentityMismatch() {
        val state = MutableStateFlow(InstalledContentSnapshot(1, listOf(item(ContentAvailability.INSTALLED, ContentSource.DOWNLOADED, "d"))))
        val wrongSource = FakeReader(source = SOURCE.replaceFirst("euler-totient", "wrong-id"))
        assertThrows(ContentIntegrityException::class.java) {
            runBlocking { CompositeReferenceRepository({ catalog("Euler totient") }, state, wrongSource)
                .article(ReferenceKey("number-theory", "euler-totient")) }
        }
        val wrongEnvelope = FakeReader(envelopeKey = "math-reference/number-theory/wrong-id")
        assertThrows(ContentIntegrityException::class.java) {
            runBlocking { CompositeReferenceRepository({ catalog("Euler totient") }, state, wrongEnvelope)
                .article(ReferenceKey("number-theory", "euler-totient")) }
        }
    }

    private class FakeReader(
        private val source: String = SOURCE,
        private val envelopeKey: String? = null,
    ) : InstalledPackageReader {
        override suspend fun envelope(item: ResolvedInstalledContent): PackageEnvelope = PackageEnvelope(
            1, CatalogContentType.REFERENCE, envelopeKey ?: item.metadata.key, 1, 2, 1, 2,
            listOf(
                PackageMemberDeclaration("source/reference.json", "application/json", source.length.toLong(), "c".repeat(64)),
                PackageMemberDeclaration("documents/article.fragment.html", "text/html; charset=utf-8", 2, "e".repeat(64)),
            ), item.activeDigest!!,
        )
        override suspend fun read(item: ResolvedInstalledContent, declaration: PackageMemberDeclaration): ByteArray =
            if (declaration.path == "source/reference.json") source.encodeToByteArray() else byteArrayOf()
        override suspend fun quarantine(item: ResolvedInstalledContent, message: String) = Unit
    }

    companion object {
        private val SOURCE = """{"schemaVersion":1,"richDocumentVersion":2,"categoryId":"number-theory","articleId":"euler-totient","title":"Euler totient","displayOrder":1,"aliases":["phi"],"keywords":["coprime"],"notation":{"blocks":[]},"definitions":{"blocks":[]},"results":{"blocks":[]},"conditions":{"blocks":[]},"examples":[{"title":"Example","document":{"blocks":[]}}],"pitfalls":{"blocks":[]},"relatedReferences":["math-reference/number-theory/fermat-euler"],"relatedProblems":["putnam/2025/A1"],"sources":[{"label":"Reference source","url":"https://example.com"}],"rights":{"sourceUrl":"https://example.com","attribution":"Original exposition","copyrightHolder":null,"licenseBasis":"Original prose","redistributionStatus":"cleared"}}"""

        private fun catalog(title: String): PublicationCatalog = PublicationCatalog(
            1, "test", "a".repeat(64), "2026-01-01T00:00:00.000Z", 1,
            emptyList(), emptyList(), listOf(CatalogReferenceCategory("number-theory", "Number Theory", 1, listOf("NT"))),
            emptyList(), listOf(CatalogReference(
                "math-reference/number-theory/euler-totient", "number-theory", "euler-totient", title, 1,
                listOf("phi"), listOf("coprime"), rights(), descriptor("d"),
            )),
        )

        private fun rights() = CatalogRights("https://example.com", "Original exposition", null, "Original prose", "cleared")
        private fun descriptor(digest: String) = PackageDescriptor(
            CatalogContentType.REFERENCE, digest.repeat(64), 1, 1, 2, "packages/$digest.zip", 2, 2,
            digest.repeat(64), 1,
        )

        private fun item(
            availability: ContentAvailability,
            source: ContentSource,
            digest: String,
            articleId: String = "euler-totient",
        ): ResolvedInstalledContent {
            val descriptor = descriptor(digest)
            val stored = StoredPackage(source, descriptor, "/packages/${descriptor.sha256}")
            return ResolvedInstalledContent(
                ContentMetadata(
                    "math-reference/number-theory/$articleId", CatalogContentType.REFERENCE, articleId,
                    null, null, null, emptyList(), "number-theory", articleId, listOf("phi"),
                    listOf("coprime"), 1, true,
                ),
                stored.takeIf { source == ContentSource.BUNDLED }, stored.takeIf { source == ContentSource.DOWNLOADED },
                availability, source.takeIf { availability == ContentAvailability.INSTALLED },
                descriptor.sha256.takeIf { availability == ContentAvailability.INSTALLED },
                stored.location.takeIf { availability == ContentAvailability.INSTALLED }, null, null, null,
            )
        }
    }
}
