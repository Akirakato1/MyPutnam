package com.putnamworkbook.offline.freeworkspace

import com.putnamworkbook.offline.backup.JsonCodec
import com.putnamworkbook.offline.backup.JsonValue
import com.putnamworkbook.offline.backup.asObject
import com.putnamworkbook.offline.backup.requireOnly
import com.putnamworkbook.offline.backup.requiredArray
import com.putnamworkbook.offline.backup.requiredInt
import com.putnamworkbook.offline.backup.requiredLong
import com.putnamworkbook.offline.backup.requiredString
import java.io.IOException
import java.util.UUID

class FreeWorkspaceCatalogException(message: String, cause: Throwable? = null) : IOException(message, cause)

object FreeWorkspaceCatalogCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(records: Collection<FreeWorkspaceRecord>): ByteArray = JsonCodec.stringify(
        JsonValue.ObjectValue(
            linkedMapOf(
                "schemaVersion" to JsonValue.NumberValue(SCHEMA_VERSION.toString()),
                "workspaces" to JsonValue.ArrayValue(
                    records.sortedBy { it.id.toString() }.map { record ->
                        JsonValue.ObjectValue(
                            linkedMapOf(
                                "id" to JsonValue.StringValue(record.id.toString()),
                                "name" to JsonValue.StringValue(record.name),
                                "createdAtEpochMillis" to JsonValue.NumberValue(record.createdAtEpochMillis.toString()),
                                "modifiedAtEpochMillis" to JsonValue.NumberValue(record.modifiedAtEpochMillis.toString()),
                            ),
                        )
                    },
                ),
            ),
        ),
    ).encodeToByteArray()

    fun decode(bytes: ByteArray): List<FreeWorkspaceRecord> {
        val root = JsonCodec.parse(bytes.decodeToString()).asObject("free workspace catalog")
        root.requireOnly("schemaVersion", "workspaces")
        if (root.requiredInt("schemaVersion") != SCHEMA_VERSION) {
            throw FreeWorkspaceCatalogException("Unsupported free workspace catalog schema")
        }

        val records = mutableListOf<FreeWorkspaceRecord>()
        val ids = mutableSetOf<UUID>()
        root.requiredArray("workspaces").forEachIndexed { index, value ->
            val row = value.asObject("free workspace $index")
            row.requireOnly("id", "name", "createdAtEpochMillis", "modifiedAtEpochMillis")
            val id = try {
                UUID.fromString(row.requiredString("id"))
            } catch (error: IllegalArgumentException) {
                throw FreeWorkspaceCatalogException("Free workspace $index has an invalid UUID", error)
            }
            if (!ids.add(id)) throw FreeWorkspaceCatalogException("Duplicate free workspace UUID: $id")

            val name = try {
                validateWorkspaceName(row.requiredString("name"), records).getOrThrow().display
            } catch (error: FreeWorkspaceNameException) {
                throw FreeWorkspaceCatalogException("Free workspace $index has an invalid or duplicate name", error)
            }
            val created = row.requiredLong("createdAtEpochMillis")
            val modified = row.requiredLong("modifiedAtEpochMillis")
            if (created < 0 || modified < 0) {
                throw FreeWorkspaceCatalogException("Free workspace timestamps cannot be negative")
            }
            if (modified < created) {
                throw FreeWorkspaceCatalogException("Free workspace modified time precedes its creation time")
            }
            records += FreeWorkspaceRecord(id, name, created, modified)
        }
        return records
    }
}
