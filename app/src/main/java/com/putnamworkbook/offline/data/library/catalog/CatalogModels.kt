package com.putnamworkbook.offline.data.library.catalog

data class PublicationCatalog(
    val schemaVersion: Int,
    val catalogId: String,
    val catalogRevision: String,
    val generatedAt: String,
    val minAppVersionCode: Int,
    val collections: List<CatalogCollection>,
    val topics: List<CatalogTopic>,
    val referenceCategories: List<CatalogReferenceCategory>,
    val problems: List<CatalogProblem>,
    val references: List<CatalogReference>,
)

data class CatalogCollection(
    val id: String,
    val title: String,
    val kind: String,
    val displayOrder: Int,
)

data class CatalogTopic(val id: String, val label: String, val displayOrder: Int)

data class CatalogReferenceCategory(
    val id: String,
    val label: String,
    val displayOrder: Int,
    val aliases: List<String>,
)

data class CatalogRights(
    val sourceUrl: String,
    val attribution: String,
    val copyrightHolder: String?,
    val licenseBasis: String,
    val redistributionStatus: String,
)

enum class CatalogContentType(val wireValue: String) {
    PROBLEM("problem"),
    REFERENCE("reference"),
}

data class PackageDescriptor(
    val contentType: CatalogContentType,
    val packageRevision: String,
    val packageSchemaVersion: Int,
    val contentSchemaVersion: Int,
    val richDocumentVersion: Int,
    val path: String,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val sha256: String,
    val minAppVersionCode: Int,
)

data class CatalogProblem(
    val key: String,
    val collectionId: String,
    val setId: String,
    val problemId: String,
    val label: String,
    val ordinal: Int,
    val topics: List<String>,
    val rights: CatalogRights,
    val packageDescriptor: PackageDescriptor,
)

data class CatalogReference(
    val key: String,
    val categoryId: String,
    val articleId: String,
    val title: String,
    val displayOrder: Int,
    val aliases: List<String>,
    val keywords: List<String>,
    val rights: CatalogRights,
    val packageDescriptor: PackageDescriptor,
)
