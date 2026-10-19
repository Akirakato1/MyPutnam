package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.data.json.StrictJsonCodec
import com.putnamworkbook.offline.data.json.asObject
import com.putnamworkbook.offline.data.json.asString
import com.putnamworkbook.offline.data.json.requiredArray
import com.putnamworkbook.offline.data.json.requiredInt
import com.putnamworkbook.offline.data.json.requiredString
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.PublicationCatalog
import com.putnamworkbook.offline.data.library.index.ContentAvailability
import com.putnamworkbook.offline.data.library.index.InstalledContentSnapshot
import com.putnamworkbook.offline.data.library.index.ResolvedInstalledContent
import com.putnamworkbook.offline.data.library.packages.PackageMemberDeclaration
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class CompositeReferenceRepository(
    private val catalog: () -> PublicationCatalog?,
    private val snapshots: Flow<InstalledContentSnapshot>,
    private val packageReader: InstalledPackageReader,
) : ReferenceRepository {
    override fun observeCategories(): Flow<List<ReferenceCategorySummary>> = snapshots.map { snapshot ->
        val installedCategoryIds = installedReferences(snapshot).mapNotNullTo(linkedSetOf()) { it.metadata.categoryId }
        val known = catalog()?.referenceCategories.orEmpty().associateBy { it.id }
        installedCategoryIds.map { id ->
            val row = known[id]
            ReferenceCategorySummary(id, row?.label ?: id, row?.displayOrder ?: Int.MAX_VALUE)
        }.sortedWith(compareBy(ReferenceCategorySummary::displayOrder, ReferenceCategorySummary::title, ReferenceCategorySummary::id))
    }.distinctUntilChanged()

    override fun observeInstalledArticles(): Flow<List<ReferenceArticleSummary>> = snapshots.map { snapshot ->
        installedReferences(snapshot).map(::summary).sortedWith(articleOrder)
    }.distinctUntilChanged()

    override suspend fun article(key: ReferenceKey): ReferenceArticleContent? {
        val item = installedReferences(snapshots.first()).firstOrNull { it.metadata.key == key.canonical } ?: return null
        suspend fun reject(message: String): Nothing {
            packageReader.quarantine(item, message)
            throw ContentIntegrityException(message)
        }
        val envelope = packageReader.envelope(item)
        if (envelope.contentType != CatalogContentType.REFERENCE || envelope.contentKey != key.canonical ||
            envelope.contentSchemaVersion != 1 || envelope.richDocumentVersion != 2
        ) reject("Reference package envelope does not match the requested article")
        val declarations = envelope.members.associateBy(PackageMemberDeclaration::path)
        val sourceDeclaration = declarations["source/reference.json"] ?: reject("Reference source is missing")
        val documentDeclaration = declarations[ARTICLE_MEMBER] ?: reject("Reference document is missing")
        val root = try {
            StrictJsonCodec.parse(packageReader.read(item, sourceDeclaration).decodeToString()).asObject("reference")
        } catch (error: Exception) {
            reject("Reference source is invalid: ${error.message?.take(160)}")
        }
        if (root.requiredInt("schemaVersion") != 1 || root.requiredInt("richDocumentVersion") != 2 ||
            root.requiredString("categoryId") != key.categoryId || root.requiredString("articleId") != key.articleId
        ) reject("Reference source identity does not match its package")

        val relatedReferences = root.requiredArray("relatedReferences").mapIndexed { index, value ->
            try {
                ReferenceKey.parse(value.asString("relatedReferences[$index]"))
            } catch (error: IllegalArgumentException) {
                reject("Reference relation is invalid")
            }
        }
        val relatedProblems = root.requiredArray("relatedProblems").mapIndexed { index, value ->
            try {
                ProblemKey.parse(value.asString("relatedProblems[$index]"))
            } catch (error: IllegalArgumentException) {
                reject("Problem relation is invalid")
            }
        }
        val attribution = root.requiredArray("sources").map { value ->
            val row = value.asObject("source")
            Attribution("reference", row.requiredString("label"), row.requiredString("url"))
        }
        return ReferenceArticleContent(
            summary(item),
            ContentResourceRef(item.metadata.key, item.activeDigest!!, ARTICLE_MEMBER, documentDeclaration.sha256),
            relatedReferences,
            relatedProblems,
            attribution,
        )
    }

    private fun installedReferences(snapshot: InstalledContentSnapshot): List<ResolvedInstalledContent> = snapshot.items.filter {
        it.availability == ContentAvailability.INSTALLED && it.metadata.contentType == CatalogContentType.REFERENCE
    }

    private fun summary(item: ResolvedInstalledContent): ReferenceArticleSummary {
        val metadata = item.metadata
        val catalog = catalog()
        val row = catalog?.references?.firstOrNull { it.key == metadata.key }
        val categoryId = row?.categoryId ?: metadata.categoryId.orEmpty()
        val category = catalog?.referenceCategories?.firstOrNull { it.id == categoryId }
        return ReferenceArticleSummary(
            key = ReferenceKey.parse(metadata.key),
            title = row?.title ?: metadata.label,
            aliases = row?.aliases ?: metadata.aliases,
            keywords = row?.keywords ?: metadata.keywords,
            categoryTitle = category?.label ?: categoryId,
            categoryDisplayOrder = category?.displayOrder ?: Int.MAX_VALUE,
            displayOrder = row?.displayOrder ?: metadata.displayOrder,
        )
    }

    private companion object {
        const val ARTICLE_MEMBER = "documents/article.fragment.html"
        val articleOrder = compareBy<ReferenceArticleSummary>(
            ReferenceArticleSummary::categoryDisplayOrder,
            ReferenceArticleSummary::displayOrder,
            ReferenceArticleSummary::title,
        ).thenBy { it.key.canonical }
    }
}
