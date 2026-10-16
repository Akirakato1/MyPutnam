package com.putnamworkbook.offline.freeworkspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.UUID

class FreeWorkspaceCatalogCodecTest {
    @Test
    fun encodingIsDeterministicAndSortedByUuid() {
        val later = record("Later", "00000000-0000-0000-0000-000000000002", 30L, 40L)
        val earlier = record("Earlier", "00000000-0000-0000-0000-000000000001", 10L, 20L)

        val first = FreeWorkspaceCatalogCodec.encode(listOf(later, earlier))
        val second = FreeWorkspaceCatalogCodec.encode(listOf(earlier, later))

        assertArrayEquals(first, second)
        assertEquals(listOf(earlier, later), FreeWorkspaceCatalogCodec.decode(first))
    }

    @Test
    fun emptyCatalogRoundTrips() {
        assertEquals(emptyList<FreeWorkspaceRecord>(), FreeWorkspaceCatalogCodec.decode(FreeWorkspaceCatalogCodec.encode(emptyList())))
    }

    @Test
    fun rejectsUnknownFieldsAndUnsupportedVersion() {
        assertInvalid("""{"schemaVersion":1,"workspaces":[],"extra":true}""")
        assertInvalid("""{"schemaVersion":2,"workspaces":[]}""")
        assertInvalid("""{"schemaVersion":1,"workspaces":[{"id":"00000000-0000-0000-0000-000000000001","name":"A","createdAtEpochMillis":1,"modifiedAtEpochMillis":1,"extra":0}]}""")
    }

    @Test
    fun rejectsDuplicateIdsAndNormalizedNames() {
        val id = "00000000-0000-0000-0000-000000000001"
        assertInvalid(catalog(row(id, "A", 1, 1), row(id, "B", 1, 1)))
        assertInvalid(catalog(row(id, "Math", 1, 1), row("00000000-0000-0000-0000-000000000002", "ＭＡＴＨ", 1, 1)))
    }

    @Test
    fun rejectsInvalidUuidTimestampsAndTrailingContent() {
        assertInvalid(catalog(row("not-a-uuid", "A", 1, 1)))
        assertInvalid(catalog(row("00000000-0000-0000-0000-000000000001", "A", -1, 1)))
        assertInvalid(catalog(row("00000000-0000-0000-0000-000000000001", "A", 2, 1)))
        assertInvalid("""{"schemaVersion":1,"workspaces":[]} trailing""")
    }

    private fun assertInvalid(json: String) {
        assertThrows(IOException::class.java) {
            FreeWorkspaceCatalogCodec.decode(json.encodeToByteArray())
        }
    }

    private fun catalog(vararg rows: String) =
        """{"schemaVersion":1,"workspaces":[${rows.joinToString(",") }]}"""

    private fun row(id: String, name: String, created: Long, modified: Long) =
        """{"id":"$id","name":"$name","createdAtEpochMillis":$created,"modifiedAtEpochMillis":$modified}"""

    private fun record(name: String, id: String, created: Long, modified: Long) = FreeWorkspaceRecord(
        id = UUID.fromString(id),
        name = name,
        createdAtEpochMillis = created,
        modifiedAtEpochMillis = modified,
    )
}
