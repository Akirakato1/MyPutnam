package com.putnamworkbook.offline.backup

import java.io.IOException
import com.putnamworkbook.offline.data.json.JsonValue
import com.putnamworkbook.offline.data.json.StrictJsonCodec
import com.putnamworkbook.offline.data.json.StrictJsonException

data class BackupManifest(
    val schemaVersion: Int,
    val members: List<BackupMember>,
)

data class BackupMember(
    val path: String,
    val size: Long,
    val sha256: String,
)

class BackupFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal object BackupManifestCodec {
    fun encode(manifest: BackupManifest): ByteArray = JsonCodec.stringify(
        JsonValue.ObjectValue(
            linkedMapOf(
                "schemaVersion" to JsonValue.NumberValue(manifest.schemaVersion.toString()),
                "members" to JsonValue.ArrayValue(
                    manifest.members.map { member ->
                        JsonValue.ObjectValue(
                            linkedMapOf(
                                "path" to JsonValue.StringValue(member.path),
                                "size" to JsonValue.NumberValue(member.size.toString()),
                                "sha256" to JsonValue.StringValue(member.sha256),
                            ),
                        )
                    },
                ),
            ),
        ),
    ).encodeToByteArray()

    fun decode(bytes: ByteArray): BackupManifest {
        val root = JsonCodec.parse(bytes.decodeToString()).asObject("backup manifest")
        root.requireOnly("schemaVersion", "members")
        val schemaVersion = root.requiredInt("schemaVersion")
        val members = root.requiredArray("members").mapIndexed { index, value ->
            val member = value.asObject("manifest member $index")
            member.requireOnly("path", "size", "sha256")
            BackupMember(
                path = member.requiredString("path"),
                size = member.requiredLong("size").also {
                    if (it < 0) throw BackupFormatException("Manifest member size cannot be negative")
                },
                sha256 = member.requiredString("sha256").also {
                    if (!it.matches(Regex("[0-9a-f]{64}"))) {
                        throw BackupFormatException("Manifest member has an invalid SHA-256")
                    }
                },
            )
        }
        return BackupManifest(schemaVersion, members)
    }
}

internal object JsonCodec {
    fun parse(text: String): JsonValue = translate { StrictJsonCodec.parse(text) }

    fun stringify(value: JsonValue): String = StrictJsonCodec.stringify(value)

    private inline fun <T> translate(block: () -> T): T = try {
        block()
    } catch (error: StrictJsonException) {
        throw BackupFormatException(error.message ?: "Invalid JSON", error)
    }
}

internal fun JsonValue.asObject(context: String): LinkedHashMap<String, JsonValue> =
    (this as? JsonValue.ObjectValue)?.values
        ?: throw BackupFormatException("Expected $context to be a JSON object")

internal fun JsonValue.asArray(context: String): List<JsonValue> =
    (this as? JsonValue.ArrayValue)?.values
        ?: throw BackupFormatException("Expected $context to be a JSON array")

internal fun JsonValue.asString(context: String): String =
    (this as? JsonValue.StringValue)?.value
        ?: throw BackupFormatException("Expected $context to be a JSON string")

internal fun JsonValue.asLong(context: String): Long =
    (this as? JsonValue.NumberValue)?.value?.toLongOrNull()
        ?: throw BackupFormatException("Expected $context to be an integer")

internal fun JsonValue.asFloat(context: String): Float =
    (this as? JsonValue.NumberValue)?.value?.toFloatOrNull()?.takeIf(Float::isFinite)
        ?: throw BackupFormatException("Expected $context to be a finite number")

internal fun JsonValue.asBoolean(context: String): Boolean =
    (this as? JsonValue.BooleanValue)?.value
        ?: throw BackupFormatException("Expected $context to be a boolean")

internal fun Map<String, JsonValue>.requiredString(key: String) =
    get(key)?.asString(key) ?: throw BackupFormatException("Missing JSON field: $key")

internal fun Map<String, JsonValue>.requiredLong(key: String) =
    get(key)?.asLong(key) ?: throw BackupFormatException("Missing JSON field: $key")

internal fun Map<String, JsonValue>.requiredInt(key: String): Int =
    requiredLong(key).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        ?: throw BackupFormatException("JSON field $key is outside the integer range")

internal fun Map<String, JsonValue>.requiredFloat(key: String) =
    get(key)?.asFloat(key) ?: throw BackupFormatException("Missing JSON field: $key")

internal fun Map<String, JsonValue>.requiredBoolean(key: String) =
    get(key)?.asBoolean(key) ?: throw BackupFormatException("Missing JSON field: $key")

internal fun Map<String, JsonValue>.requiredArray(key: String) =
    get(key)?.asArray(key) ?: throw BackupFormatException("Missing JSON field: $key")

internal fun Map<String, JsonValue>.requireOnly(vararg allowed: String) {
    val unexpected = keys - allowed.toSet()
    if (unexpected.isNotEmpty()) throw BackupFormatException("Unexpected JSON fields: ${unexpected.sorted()}")
}
