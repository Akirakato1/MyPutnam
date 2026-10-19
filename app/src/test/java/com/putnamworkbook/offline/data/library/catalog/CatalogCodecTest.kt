package com.putnamworkbook.offline.data.library.catalog

import com.putnamworkbook.offline.data.json.JsonLimits
import com.putnamworkbook.offline.data.json.StrictJsonCodec
import com.putnamworkbook.offline.data.json.StrictJsonException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogCodecTest {
    private val codec = CatalogCodec()

    @Test
    fun decodesEveryPublishedField() {
        val catalog = codec.decode(validCatalog().encodeToByteArray())

        assertEquals("myputnam-public", catalog.catalogId)
        assertEquals(1, catalog.collections.single().displayOrder)
        assertEquals(listOf("NT"), catalog.referenceCategories.single().aliases)
        assertEquals("putnam/2025/A1", catalog.problems.single().key)
        assertEquals("unresolved", catalog.problems.single().rights.redistributionStatus)
        assertEquals(100L, catalog.problems.single().packageDescriptor.compressedBytes)
    }

    @Test
    fun rejectsUnknownFieldsAndDuplicateObjectKeys() {
        assertCatalogFailure(validCatalog().replaceFirst("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"unexpected\": true,"))
        assertCatalogFailure(validCatalog().replaceFirst("\"catalogId\": \"myputnam-public\",", "\"catalogId\": \"one\", \"catalogId\": \"two\","))
    }

    @Test
    fun rejectsDuplicateOrdersKeysAndUnknownTaxonomy() {
        val duplicateProblem = problemJson().replace("putnam/2025/A1", "putnam/2025/A2").replace("\"problemId\": \"A1\"", "\"problemId\": \"A2\"")
        assertCatalogFailure(validCatalog().replace("\"problems\": [${problemJson()}]", "\"problems\": [${problemJson()},$duplicateProblem]"))
        assertCatalogFailure(validCatalog().replace("\"topics\": [\"number-theory\"]", "\"topics\": [\"unknown\"]"))
        assertCatalogFailure(
            validCatalog().replace(
                "\"topics\": [{ \"id\": \"number-theory\", \"label\": \"Number Theory\", \"displayOrder\": 1 }]",
                "\"topics\": [{ \"id\": \"number-theory\", \"label\": \"Number Theory\", \"displayOrder\": 1 }, { \"id\": \"analysis\", \"label\": \"Analysis\", \"displayOrder\": 1 }]",
            ),
        )
    }

    @Test
    fun rejectsUnsafePathsAndIncompatibleSchemas() {
        listOf("../escape.zip", "/absolute.zip", "https://example.com/a.zip", "packages\\bad.zip").forEach { path ->
            assertCatalogFailure(validCatalog().replace(PACKAGE_PATH, path))
        }
        assertCatalogFailure(validCatalog().replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        assertCatalogFailure(validCatalog().replaceFirst("\"contentSchemaVersion\": 3", "\"contentSchemaVersion\": 2"))
    }

    @Test
    fun strictJsonEnforcesStringArrayAndNumberBounds() {
        assertThrows(StrictJsonException::class.java) {
            StrictJsonCodec.parse("{\"x\":\"12345\"}", JsonLimits(maxStringChars = 4))
        }
        assertThrows(StrictJsonException::class.java) {
            StrictJsonCodec.parse("[1,2,3]", JsonLimits(maxArrayItems = 2))
        }
        assertThrows(StrictJsonException::class.java) {
            StrictJsonCodec.parse("12345", JsonLimits(maxNumberChars = 4))
        }
    }

    private fun assertCatalogFailure(json: String) {
        assertThrows(CatalogFormatException::class.java) { codec.decode(json.encodeToByteArray()) }
    }

    private fun validCatalog() = """
        {
          "schemaVersion": 1,
          "catalogId": "myputnam-public",
          "catalogRevision": "${"c".repeat(64)}",
          "generatedAt": "2026-10-18T00:00:00.000Z",
          "minAppVersionCode": 1,
          "collections": [{ "id": "putnam", "title": "Putnam", "kind": "competition", "displayOrder": 1 }],
          "topics": [{ "id": "number-theory", "label": "Number Theory", "displayOrder": 1 }],
          "referenceCategories": [{ "id": "number-theory", "label": "Number Theory", "displayOrder": 1, "aliases": ["NT"] }],
          "problems": [${problemJson()}],
          "references": []
        }
    """.trimIndent()

    private fun problemJson() = """
        {
          "key": "putnam/2025/A1", "collectionId": "putnam", "setId": "2025", "problemId": "A1",
          "label": "A1", "ordinal": 1, "topics": ["number-theory"],
          "rights": {
            "sourceUrl": "https://maa.org/math-competitions/putnam-competition/",
            "attribution": "William Lowell Putnam Mathematical Competition, Mathematical Association of America.",
            "copyrightHolder": "Mathematical Association of America",
            "licenseBasis": "Redistribution permission has not yet been established.",
            "redistributionStatus": "unresolved"
          },
          "package": {
            "contentType": "problem", "packageRevision": "${"a".repeat(64)}", "packageSchemaVersion": 1,
            "contentSchemaVersion": 3, "richDocumentVersion": 1, "path": "$PACKAGE_PATH",
            "compressedBytes": 100, "uncompressedBytes": 123, "sha256": "${"d".repeat(64)}", "minAppVersionCode": 1
          }
        }
    """.trimIndent()

    private companion object {
        const val PACKAGE_PATH = "packages/problem/aa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.zip"
    }
}
