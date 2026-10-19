package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.data.library.catalog.CatalogCollection
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.CatalogTopic
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import com.putnamworkbook.offline.data.library.catalog.PublicationCatalog
import com.putnamworkbook.offline.data.library.index.ContentAvailability
import com.putnamworkbook.offline.data.library.index.ContentMetadata
import com.putnamworkbook.offline.data.library.index.ContentSource
import com.putnamworkbook.offline.data.library.index.InstalledContentSnapshot
import com.putnamworkbook.offline.data.library.index.ResolvedInstalledContent
import com.putnamworkbook.offline.data.library.index.StoredPackage
import com.putnamworkbook.offline.data.library.packages.PackageEnvelope
import com.putnamworkbook.offline.data.library.packages.PackageMemberDeclaration
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeContentRepositoryTest {
    @Test
    fun catalogRepositoryListsOnlyActiveLocalProblemsAndRetainsRemovedMetadata() = runBlocking {
        val state = MutableStateFlow(InstalledContentSnapshot(7, listOf(
            item("putnam/2025/A1", ContentAvailability.INSTALLED, listed = true, digest = "a"),
            item("putnam/2025/A2", ContentAvailability.NOT_INSTALLED, listed = true, digest = null),
            item("putnam/2024/B1", ContentAvailability.INSTALLED, listed = false, digest = "b"),
        )))
        val repository = CatalogProblemRepository({ catalog() }, state, FakePackageReader())

        assertEquals(listOf("putnam"), repository.collections().map { it.id })
        assertEquals(listOf("2024", "2025"), repository.sets("putnam").map { it.key.setId })
        assertEquals(listOf("A1"), repository.problems(ProblemSetKey("putnam", "2025")).map { it.label })
        assertEquals(listOf("B1"), repository.problems(ProblemSetKey("putnam", "2024")).map { it.label })
        assertEquals(7L, repository.observeInstalledRevision().first())
    }

    @Test
    fun problemDecodingProducesHashBoundLogicalResources() = runBlocking {
        val state = MutableStateFlow(InstalledContentSnapshot(1, listOf(
            item("putnam/2025/A1", ContentAvailability.INSTALLED, listed = true, digest = "a"),
        )))
        val repository = CatalogProblemRepository({ catalog() }, state, FakePackageReader())
        val content = repository.problem(ProblemKey("putnam", "2025", "A1"))

        assertNotNull(content)
        assertEquals("documents/statement.fragment.html", content!!.statement.memberPath)
        assertEquals("a".repeat(64), content.statement.packageSha256)
        assertEquals(4, content.hints.size)
        assertEquals("documents/solution.fragment.html", content.solution.memberPath)
        assertEquals("MAA", content.attribution.single().label)
    }

    @Test
    fun compositeUsesPackagedRepositoryWhenAvailableAndLegacyOnlyForCompatibility() = runBlocking {
        val packaged = FakeRepository(listOf(LibraryCollection("putnam", "Putnam", "competition", 1)))
        val legacy = FakeRepository(listOf(LibraryCollection("legacy", "Legacy", "competition", 1)))
        assertEquals("putnam", CompositeContentRepository(packaged, legacy).collections().single().id)
        assertEquals("legacy", CompositeContentRepository(FakeRepository(emptyList()), legacy).collections().single().id)
    }

    private class FakePackageReader : InstalledPackageReader {
        override suspend fun envelope(item: ResolvedInstalledContent): PackageEnvelope = envelopeFor(item)
        override suspend fun read(item: ResolvedInstalledContent, declaration: PackageMemberDeclaration): ByteArray =
            if (declaration.path == "source/problem.json") SOURCE.encodeToByteArray() else byteArrayOf()
        override suspend fun quarantine(item: ResolvedInstalledContent, message: String) = Unit

        private fun envelopeFor(item: ResolvedInstalledContent): PackageEnvelope {
            val paths = listOf(
                "source/problem.json",
                "documents/statement.fragment.html",
                "documents/hint-1.fragment.html",
                "documents/hint-2.fragment.html",
                "documents/hint-3.fragment.html",
                "documents/hint-4.fragment.html",
                "documents/prerequisite-1.fragment.html",
                "documents/solution.fragment.html",
            )
            return PackageEnvelope(1, CatalogContentType.PROBLEM, item.metadata.key, 3, 1, 1, 2, paths.map {
                PackageMemberDeclaration(it, if (it.endsWith("json")) "application/json" else "text/html; charset=utf-8", 2, "c".repeat(64))
            }, item.activeDigest!!)
        }
    }

    private class FakeRepository(private val collectionRows: List<LibraryCollection>) : ContentRepository {
        override suspend fun collections() = collectionRows
        override suspend fun sets(collectionId: String) = emptyList<ProblemSetSummary>()
        override suspend fun topics() = emptyList<TopicSummary>()
        override suspend fun problems(setKey: ProblemSetKey) = emptyList<ProblemSummary>()
        override suspend fun problemsByTopic(topicId: String) = emptyList<ProblemSummary>()
        override suspend fun problem(key: ProblemKey) = null
    }

    private fun item(key: String, availability: ContentAvailability, listed: Boolean, digest: String?): ResolvedInstalledContent {
        val parts = key.split('/')
        val descriptor = digest?.let { PackageDescriptor(CatalogContentType.PROBLEM, it.repeat(64), 1, 3, 1, "packages/$it.zip", 2, 2, it.repeat(64), 1) }
        val stored = descriptor?.let { StoredPackage(ContentSource.DOWNLOADED, it, "/packages/${it.sha256}") }
        return ResolvedInstalledContent(
            ContentMetadata(key, CatalogContentType.PROBLEM, parts[2], parts[0], parts[1], parts[2], listOf("algebra"), null, null, emptyList(), emptyList(), 1, listed),
            null, stored, availability, stored?.source, descriptor?.sha256, stored?.location, null, null, null,
        )
    }

    private fun catalog() = PublicationCatalog(
        1, "test", "d".repeat(64), "2026-10-18T00:00:00Z", 1,
        listOf(CatalogCollection("putnam", "Putnam", "competition", 1)),
        listOf(CatalogTopic("algebra", "Algebra", 1)), emptyList(), emptyList(), emptyList(),
    )

    companion object {
        val SOURCE = """{"schemaVersion":3,"collectionId":"putnam","setId":"2025","problemId":"A1","label":"A1","ordinal":1,"topics":["algebra"],"statement":{"blocks":[]},"hints":[{"order":1,"stage":"nudge","title":"N","document":{"blocks":[]}},{"order":2,"stage":"strategy","title":"S","document":{"blocks":[]}},{"order":3,"stage":"knowledge","title":"K","document":{"blocks":[]}},{"order":4,"stage":"outline","title":"O","document":{"blocks":[]}}],"prerequisites":[{"order":1,"title":"P","document":{"blocks":[]}}],"solution":{"blocks":[]},"sources":[{"kind":"problem","label":"MAA","url":"https://maa.org"}]}"""
    }
}
