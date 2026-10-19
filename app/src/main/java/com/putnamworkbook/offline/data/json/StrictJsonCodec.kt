package com.putnamworkbook.offline.data.json

import java.io.IOException

data class JsonLimits(
    val maxInputChars: Int = 4 * 1024 * 1024,
    val maxDepth: Int = 32,
    val maxStringChars: Int = 256 * 1024,
    val maxNumberChars: Int = 64,
    val maxArrayItems: Int = 20_000,
    val maxObjectEntries: Int = 20_000,
)

class StrictJsonException(message: String) : IOException(message)

object StrictJsonCodec {
    fun parse(text: String, limits: JsonLimits = JsonLimits()): JsonValue {
        if (text.length > limits.maxInputChars) throw StrictJsonException("JSON input is too large")
        return Parser(text, limits).parse()
    }

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

    private class Parser(
        private val text: String,
        private val limits: JsonLimits,
    ) {
        private var index = 0

        fun parse(): JsonValue {
            val value = parseValue(0)
            skipWhitespace()
            if (index != text.length) fail("Unexpected trailing JSON content")
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > limits.maxDepth) fail("JSON nesting is too deep")
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
                if (values.size >= limits.maxObjectEntries) fail("JSON object has too many entries")
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
                if (values.size >= limits.maxArrayItems) fail("JSON array has too many items")
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
                    if (length > limits.maxStringChars) fail("JSON string is too long")
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
            if (index - start > limits.maxNumberChars) fail("JSON number is too long")
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

        private fun fail(message: String): Nothing = throw StrictJsonException("$message at offset $index")
    }
}
