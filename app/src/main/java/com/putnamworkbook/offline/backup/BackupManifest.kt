package com.putnamworkbook.offline.backup

import java.io.IOException

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

internal sealed interface JsonValue {
    data class ObjectValue(val values: LinkedHashMap<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal object JsonCodec {
    fun parse(text: String): JsonValue = Parser(text).parse()

    fun stringify(value: JsonValue): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: JsonValue) {
        when (value) {
            is JsonValue.ObjectValue -> {
                append('{')
                value.values.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    appendQuoted(key)
                    append(':')
                    appendJson(item)
                }
                append('}')
            }
            is JsonValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendJson(item)
                }
                append(']')
            }
            is JsonValue.StringValue -> appendQuoted(value.value)
            is JsonValue.NumberValue -> append(value.value)
            is JsonValue.BooleanValue -> append(value.value)
            JsonValue.NullValue -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): JsonValue {
            val value = parseValue(0)
            skipWhitespace()
            if (index != text.length) fail("Unexpected trailing JSON content")
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("JSON nesting is too deep")
            skipWhitespace()
            if (index >= text.length) fail("Unexpected end of JSON")
            return when (text[index]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonValue.StringValue(parseString())
                't' -> parseLiteral("true", JsonValue.BooleanValue(true))
                'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
                'n' -> parseLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Unexpected JSON token")
            }
        }

        private fun parseObject(depth: Int): JsonValue.ObjectValue {
            index += 1
            val values = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (consume('}')) return JsonValue.ObjectValue(values)
            while (true) {
                skipWhitespace()
                if (index >= text.length || text[index] != '"') fail("Object key must be a string")
                val key = parseString()
                if (values.containsKey(key)) fail("Duplicate JSON object key: $key")
                skipWhitespace()
                expect(':')
                values[key] = parseValue(depth)
                skipWhitespace()
                if (consume('}')) break
                expect(',')
            }
            return JsonValue.ObjectValue(values)
        }

        private fun parseArray(depth: Int): JsonValue.ArrayValue {
            index += 1
            val values = mutableListOf<JsonValue>()
            skipWhitespace()
            if (consume(']')) return JsonValue.ArrayValue(values)
            while (true) {
                values += parseValue(depth)
                skipWhitespace()
                if (consume(']')) break
                expect(',')
            }
            return JsonValue.ArrayValue(values)
        }

        private fun parseString(): String {
            expect('"')
            return buildString {
                while (index < text.length) {
                    val character = text[index++]
                    when {
                        character == '"' -> return@buildString
                        character == '\\' -> {
                            if (index >= text.length) fail("Unterminated JSON escape")
                            when (val escaped = text[index++]) {
                                '"', '\\', '/' -> append(escaped)
                                'b' -> append('\b')
                                'f' -> append('\u000C')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                'u' -> append(parseUnicode())
                                else -> fail("Invalid JSON escape")
                            }
                        }
                        character.code < 0x20 -> fail("Control character in JSON string")
                        else -> append(character)
                    }
                }
                fail("Unterminated JSON string")
            }
        }

        private fun parseUnicode(): Char {
            if (index + 4 > text.length) fail("Incomplete Unicode escape")
            val digits = text.substring(index, index + 4)
            index += 4
            return digits.toIntOrNull(16)?.toChar() ?: fail("Invalid Unicode escape")
        }

        private fun parseNumber(): JsonValue.NumberValue {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < text.length && text[index].isDigit()) fail("Leading zero in JSON number")
            } else {
                requireDigits()
            }
            if (consume('.')) requireDigits()
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index += 1
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index += 1
                requireDigits()
            }
            return JsonValue.NumberValue(text.substring(start, index))
        }

        private fun requireDigits() {
            val start = index
            while (index < text.length && text[index].isDigit()) index += 1
            if (start == index) fail("Expected digits in JSON number")
        }

        private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) fail("Invalid JSON literal")
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in " \t\r\n") index += 1
        }

        private fun consume(character: Char): Boolean =
            if (index < text.length && text[index] == character) {
                index += 1
                true
            } else {
                false
            }

        private fun expect(character: Char) {
            if (!consume(character)) fail("Expected '$character'")
        }

        private fun fail(message: String): Nothing = throw BackupFormatException("$message at offset $index")

        private companion object {
            const val MAX_DEPTH = 32
        }
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
