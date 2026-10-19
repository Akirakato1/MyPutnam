package com.putnamworkbook.offline.backup

import com.putnamworkbook.offline.data.json.JsonValue

object LibraryBackupStateCodec {
    private const val SCHEMA_VERSION = 1
    private val keyPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*){2}")
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun encode(state: LibraryBackupState): ByteArray {
        validate(state)
        val root = JsonValue.ObjectValue(
            linkedMapOf(
                "schemaVersion" to JsonValue.NumberValue(SCHEMA_VERSION.toString()),
                "desiredKeys" to strings(state.desiredKeys),
                "logicallyRemovedBundledKeys" to strings(state.logicallyRemovedBundledKeys),
                "activeDigests" to JsonValue.ObjectValue(
                    linkedMapOf<String, JsonValue>().apply {
                        state.activeDigests.toSortedMap().forEach { (key, digest) ->
                            put(key, JsonValue.StringValue(digest))
                        }
                    },
                ),
            ),
        )
        return JsonCodec.stringify(root).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): LibraryBackupState {
        val root = JsonCodec.parse(bytes.decodeToString()).asObject("library backup state")
        root.requireOnly("schemaVersion", "desiredKeys", "logicallyRemovedBundledKeys", "activeDigests")
        val version = root.requiredInt("schemaVersion")
        if (version != SCHEMA_VERSION) throw BackupFormatException("Unsupported library backup schema version: $version")
        val state = LibraryBackupState(
            desiredKeys = parseUniqueKeys(root.requiredArray("desiredKeys"), "desiredKeys"),
            logicallyRemovedBundledKeys = parseUniqueKeys(
                root.requiredArray("logicallyRemovedBundledKeys"),
                "logicallyRemovedBundledKeys",
            ),
            activeDigests = (root["activeDigests"]
                ?: throw BackupFormatException("Missing JSON field: activeDigests"))
                .asObject("activeDigests")
                .mapValues { (key, value) ->
                    validateKey(key)
                    value.asString("digest for $key").also { digest ->
                        if (!digestPattern.matches(digest)) throw BackupFormatException("Invalid active package digest")
                    }
                },
        )
        validate(state)
        return state
    }

    private fun strings(values: Set<String>) = JsonValue.ArrayValue(
        values.sorted().map(JsonValue::StringValue),
    )

    private fun parseUniqueKeys(values: List<JsonValue>, field: String): Set<String> {
        val result = linkedSetOf<String>()
        values.forEach { value ->
            val key = value.asString(field)
            validateKey(key)
            if (!result.add(key)) throw BackupFormatException("Duplicate library content key")
        }
        return result
    }

    private fun validate(state: LibraryBackupState) {
        state.desiredKeys.forEach(::validateKey)
        state.logicallyRemovedBundledKeys.forEach(::validateKey)
        if (state.desiredKeys.intersect(state.logicallyRemovedBundledKeys).isNotEmpty()) {
            throw BackupFormatException("A library item cannot be both desired and removed")
        }
        state.activeDigests.forEach { (key, digest) ->
            validateKey(key)
            if (!digestPattern.matches(digest)) throw BackupFormatException("Invalid active package digest")
        }
    }

    private fun validateKey(key: String) {
        if (key.length > 240 || !keyPattern.matches(key) || key.split('/').any { it == "." || it == ".." }) {
            throw BackupFormatException("Invalid library content key")
        }
    }
}
