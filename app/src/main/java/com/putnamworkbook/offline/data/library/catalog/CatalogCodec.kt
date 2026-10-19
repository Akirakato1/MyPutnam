package com.putnamworkbook.offline.data.library.catalog

import com.putnamworkbook.offline.data.json.JsonLimits
import com.putnamworkbook.offline.data.json.JsonValue
import com.putnamworkbook.offline.data.json.StrictJsonCodec
import com.putnamworkbook.offline.data.json.StrictJsonException
import com.putnamworkbook.offline.data.json.asObject
import com.putnamworkbook.offline.data.json.optionalString
import com.putnamworkbook.offline.data.json.requireOnly
import com.putnamworkbook.offline.data.json.requiredArray
import com.putnamworkbook.offline.data.json.requiredInt
import com.putnamworkbook.offline.data.json.requiredLong
import com.putnamworkbook.offline.data.json.requiredString
import java.io.IOException

data class CatalogLimits(
    val maxCatalogBytes: Int = 4 * 1024 * 1024,
    val maxCollections: Int = 256,
    val maxTopics: Int = 2_048,
    val maxReferenceCategories: Int = 2_048,
    val maxProblems: Int = 20_000,
    val maxReferences: Int = 20_000,
    val maxStringsPerEntry: Int = 256,
)

class CatalogFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

class CatalogCodec(private val limits: CatalogLimits = CatalogLimits()) {
    fun decode(bytes: ByteArray): PublicationCatalog {
        if (bytes.size > limits.maxCatalogBytes) throw CatalogFormatException("Catalog is too large")
        return try {
            decodeRoot(StrictJsonCodec.parse(bytes.decodeToString(), JsonLimits(maxInputChars = limits.maxCatalogBytes)))
        } catch (error: CatalogFormatException) {
            throw error
        } catch (error: StrictJsonException) {
            throw CatalogFormatException(error.message ?: "Invalid catalog JSON", error)
        } catch (error: IllegalArgumentException) {
            throw CatalogFormatException(error.message ?: "Invalid catalog", error)
        }
    }

    private fun decodeRoot(value: JsonValue): PublicationCatalog {
        val root = value.asObject("catalog")
        root.requireOnly(
            "schemaVersion", "catalogId", "catalogRevision", "generatedAt", "minAppVersionCode",
            "collections", "topics", "referenceCategories", "problems", "references",
        )
        val schemaVersion = root.requiredInt("schemaVersion")
        requireCatalog(schemaVersion == SUPPORTED_CATALOG_SCHEMA, "Unsupported catalog schema version: $schemaVersion")
        val collections = root.requiredArray("collections").bounded("collections", limits.maxCollections).mapIndexed(::decodeCollection)
        val topics = root.requiredArray("topics").bounded("topics", limits.maxTopics).mapIndexed(::decodeTopic)
        val categories = root.requiredArray("referenceCategories").bounded("referenceCategories", limits.maxReferenceCategories).mapIndexed(::decodeCategory)
        val problems = root.requiredArray("problems").bounded("problems", limits.maxProblems).mapIndexed(::decodeProblem)
        val references = root.requiredArray("references").bounded("references", limits.maxReferences).mapIndexed(::decodeReference)
        val catalog = PublicationCatalog(
            schemaVersion = schemaVersion,
            catalogId = root.requiredString("catalogId").safeId("catalogId"),
            catalogRevision = root.requiredString("catalogRevision").sha256("catalogRevision"),
            generatedAt = root.requiredString("generatedAt").also {
                requireCatalog(GENERATED_AT.matches(it), "generatedAt is not a canonical UTC timestamp")
            },
            minAppVersionCode = root.requiredInt("minAppVersionCode").positive("minAppVersionCode"),
            collections = collections,
            topics = topics,
            referenceCategories = categories,
            problems = problems,
            references = references,
        )
        validateRelations(catalog)
        return catalog
    }

    private fun decodeCollection(index: Int, value: JsonValue): CatalogCollection {
        val row = value.asObject("collection $index")
        row.requireOnly("id", "title", "kind", "displayOrder")
        return CatalogCollection(
            id = row.requiredString("id").safeId("collection id"),
            title = row.requiredString("title").nonBlank("collection title"),
            kind = row.requiredString("kind").nonBlank("collection kind"),
            displayOrder = row.requiredInt("displayOrder").positive("collection displayOrder"),
        )
    }

    private fun decodeTopic(index: Int, value: JsonValue): CatalogTopic {
        val row = value.asObject("topic $index")
        row.requireOnly("id", "label", "displayOrder")
        return CatalogTopic(
            id = row.requiredString("id").safeId("topic id"),
            label = row.requiredString("label").nonBlank("topic label"),
            displayOrder = row.requiredInt("displayOrder").positive("topic displayOrder"),
        )
    }

    private fun decodeCategory(index: Int, value: JsonValue): CatalogReferenceCategory {
        val row = value.asObject("reference category $index")
        row.requireOnly("id", "label", "displayOrder", "aliases")
        return CatalogReferenceCategory(
            id = row.requiredString("id").safeId("reference category id"),
            label = row.requiredString("label").nonBlank("reference category label"),
            displayOrder = row.requiredInt("displayOrder").positive("reference category displayOrder"),
            aliases = row.stringList("aliases"),
        )
    }

    private fun decodeProblem(index: Int, value: JsonValue): CatalogProblem {
        val row = value.asObject("problem $index")
        row.requireOnly("key", "collectionId", "setId", "problemId", "label", "ordinal", "topics", "rights", "package")
        val collectionId = row.requiredString("collectionId").safeId("problem collectionId")
        val setId = row.requiredString("setId").safeId("problem setId")
        val problemId = row.requiredString("problemId").safeId("problem problemId")
        val key = row.requiredString("key")
        requireCatalog(key == "$collectionId/$setId/$problemId", "Problem key does not match its identity: $key")
        return CatalogProblem(
            key = key,
            collectionId = collectionId,
            setId = setId,
            problemId = problemId,
            label = row.requiredString("label").nonBlank("problem label"),
            ordinal = row.requiredInt("ordinal").positive("problem ordinal"),
            topics = row.stringList("topics", safeIds = true),
            rights = decodeRights(row.getValue("rights"), "problem $key rights"),
            packageDescriptor = decodePackage(row.getValue("package"), CatalogContentType.PROBLEM),
        )
    }

    private fun decodeReference(index: Int, value: JsonValue): CatalogReference {
        val row = value.asObject("reference $index")
        row.requireOnly("key", "categoryId", "articleId", "title", "displayOrder", "aliases", "keywords", "rights", "package")
        val categoryId = row.requiredString("categoryId").safeId("reference categoryId")
        val articleId = row.requiredString("articleId").safeId("reference articleId")
        val key = row.requiredString("key")
        requireCatalog(key == "math-reference/$categoryId/$articleId", "Reference key does not match its identity: $key")
        return CatalogReference(
            key = key,
            categoryId = categoryId,
            articleId = articleId,
            title = row.requiredString("title").nonBlank("reference title"),
            displayOrder = row.requiredInt("displayOrder").positive("reference displayOrder"),
            aliases = row.stringList("aliases"),
            keywords = row.stringList("keywords"),
            rights = decodeRights(row.getValue("rights"), "reference $key rights"),
            packageDescriptor = decodePackage(row.getValue("package"), CatalogContentType.REFERENCE),
        )
    }

    private fun decodeRights(value: JsonValue, context: String): CatalogRights {
        val row = value.asObject(context)
        row.requireOnly("sourceUrl", "attribution", "copyrightHolder", "licenseBasis", "redistributionStatus")
        val sourceUrl = row.requiredString("sourceUrl")
        requireCatalog(sourceUrl.startsWith("https://") && sourceUrl.none(Char::isWhitespace), "$context sourceUrl must use HTTPS")
        val status = row.requiredString("redistributionStatus")
        requireCatalog(status in REDISTRIBUTION_STATUSES, "$context has an unsupported redistributionStatus")
        return CatalogRights(
            sourceUrl = sourceUrl,
            attribution = row.requiredString("attribution").nonBlank("$context attribution"),
            copyrightHolder = row.optionalString("copyrightHolder")?.nonBlank("$context copyrightHolder"),
            licenseBasis = row.requiredString("licenseBasis").nonBlank("$context licenseBasis"),
            redistributionStatus = status,
        )
    }

    private fun decodePackage(value: JsonValue, expectedType: CatalogContentType): PackageDescriptor {
        val row = value.asObject("package descriptor")
        row.requireOnly(
            "contentType", "packageRevision", "packageSchemaVersion", "contentSchemaVersion",
            "richDocumentVersion", "path", "compressedBytes", "uncompressedBytes", "sha256", "minAppVersionCode",
        )
        val contentType = CatalogContentType.entries.singleOrNull { it.wireValue == row.requiredString("contentType") }
            ?: throw CatalogFormatException("Unsupported package contentType")
        requireCatalog(contentType == expectedType, "Package contentType does not match catalog entry")
        val contentSchema = row.requiredInt("contentSchemaVersion")
        val richDocument = row.requiredInt("richDocumentVersion")
        val supported = when (contentType) {
            CatalogContentType.PROBLEM -> contentSchema == 3 && richDocument == 1
            CatalogContentType.REFERENCE -> contentSchema == 1 && richDocument == 2
        }
        requireCatalog(supported, "Unsupported content/rich-document schema combination")
        return PackageDescriptor(
            contentType = contentType,
            packageRevision = row.requiredString("packageRevision").sha256("packageRevision"),
            packageSchemaVersion = row.requiredInt("packageSchemaVersion").also {
                requireCatalog(it == 1, "Unsupported package schema version: $it")
            },
            contentSchemaVersion = contentSchema,
            richDocumentVersion = richDocument,
            path = row.requiredString("path").safePackagePath(),
            compressedBytes = row.requiredLong("compressedBytes").boundedBytes("compressedBytes", MAX_COMPRESSED_BYTES),
            uncompressedBytes = row.requiredLong("uncompressedBytes").boundedBytes("uncompressedBytes", MAX_UNCOMPRESSED_BYTES),
            sha256 = row.requiredString("sha256").sha256("package sha256"),
            minAppVersionCode = row.requiredInt("minAppVersionCode").positive("package minAppVersionCode"),
        )
    }

    private fun validateRelations(catalog: PublicationCatalog) {
        requireUnique(catalog.collections.map { it.id }, "collection id")
        requireUnique(catalog.collections.map { it.displayOrder }, "collection displayOrder")
        requireUnique(catalog.topics.map { it.id }, "topic id")
        requireUnique(catalog.topics.map { it.displayOrder }, "topic displayOrder")
        requireUnique(catalog.referenceCategories.map { it.id }, "reference category id")
        requireUnique(catalog.referenceCategories.map { it.displayOrder }, "reference category displayOrder")
        requireUnique(catalog.problems.map { it.key }, "problem key")
        requireUnique(catalog.references.map { it.key }, "reference key")
        val collectionIds = catalog.collections.mapTo(mutableSetOf()) { it.id }
        val topicIds = catalog.topics.mapTo(mutableSetOf()) { it.id }
        val categoryIds = catalog.referenceCategories.mapTo(mutableSetOf()) { it.id }
        catalog.problems.forEach { problem ->
            requireCatalog(problem.collectionId in collectionIds, "Problem ${problem.key} has unknown collection ${problem.collectionId}")
            requireUnique(problem.topics, "topic on problem ${problem.key}")
            problem.topics.forEach { requireCatalog(it in topicIds, "Problem ${problem.key} has unknown topic $it") }
        }
        catalog.problems.groupBy { it.collectionId to it.setId }.forEach { (_, values) ->
            requireUnique(values.map { it.ordinal }, "problem ordinal")
        }
        catalog.references.forEach { reference ->
            requireCatalog(reference.categoryId in categoryIds, "Reference ${reference.key} has unknown category ${reference.categoryId}")
            requireUnique(reference.aliases, "alias on reference ${reference.key}")
            requireUnique(reference.keywords, "keyword on reference ${reference.key}")
        }
        catalog.references.groupBy { it.categoryId }.forEach { (_, values) ->
            requireUnique(values.map { it.displayOrder }, "reference displayOrder")
        }
    }

    private fun Map<String, JsonValue>.stringList(key: String, safeIds: Boolean = false): List<String> =
        requiredArray(key).bounded(key, limits.maxStringsPerEntry).mapIndexed { index, value ->
            val string = (value as? JsonValue.StringValue)?.value
                ?: throw CatalogFormatException("$key[$index] must be a string")
            if (safeIds) string.safeId("$key[$index]") else string.nonBlank("$key[$index]")
        }

    private fun <T> List<T>.bounded(name: String, maximum: Int): List<T> = also {
        requireCatalog(size <= maximum, "$name has too many entries")
    }

    private fun String.safeId(name: String): String = also { requireCatalog(SAFE_ID.matches(it), "$name is not path-safe") }
    private fun String.sha256(name: String): String = also { requireCatalog(SHA256.matches(it), "$name is invalid") }
    private fun String.nonBlank(name: String): String = also { requireCatalog(it.isNotBlank(), "$name cannot be blank") }
    private fun Int.positive(name: String): Int = also { requireCatalog(it >= 1, "$name must be positive") }
    private fun Long.boundedBytes(name: String, maximum: Long): Long = also {
        requireCatalog(it in 0..maximum, "$name is outside the supported range")
    }

    private fun String.safePackagePath(): String = also { path ->
        requireCatalog(path.length <= 512, "Package path is too long")
        requireCatalog(!path.contains('\\') && !path.startsWith('/') && !path.contains(':') && !path.contains('?') && !path.contains('#'), "Unsafe package path: $path")
        val segments = path.split('/')
        requireCatalog(segments.isNotEmpty() && segments.first() == "packages" && segments.none { it.isEmpty() || it == "." || it == ".." }, "Unsafe package path: $path")
        requireCatalog(path.endsWith(".zip"), "Package path must name a ZIP")
    }

    private fun requireUnique(values: List<*>, name: String) {
        requireCatalog(values.size == values.toSet().size, "Duplicate $name")
    }

    private fun requireCatalog(condition: Boolean, message: String) {
        if (!condition) throw CatalogFormatException(message)
    }

    private companion object {
        const val SUPPORTED_CATALOG_SCHEMA = 1
        const val MAX_COMPRESSED_BYTES = 16L * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024
        val SAFE_ID = Regex("[A-Za-z0-9._-]+")
        val SHA256 = Regex("[0-9a-f]{64}")
        val GENERATED_AT = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")
        val REDISTRIBUTION_STATUSES = setOf("unresolved", "review", "cleared", "restricted")
    }
}
