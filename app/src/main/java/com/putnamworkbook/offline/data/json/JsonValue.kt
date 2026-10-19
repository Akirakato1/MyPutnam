package com.putnamworkbook.offline.data.json

sealed interface JsonValue {
    data class ObjectValue(val values: LinkedHashMap<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

fun JsonValue.asObject(context: String): LinkedHashMap<String, JsonValue> =
    (this as? JsonValue.ObjectValue)?.values
        ?: throw StrictJsonException("Expected $context to be a JSON object")

fun JsonValue.asArray(context: String): List<JsonValue> =
    (this as? JsonValue.ArrayValue)?.values
        ?: throw StrictJsonException("Expected $context to be a JSON array")

fun JsonValue.asString(context: String): String =
    (this as? JsonValue.StringValue)?.value
        ?: throw StrictJsonException("Expected $context to be a JSON string")

fun JsonValue.asLong(context: String): Long =
    (this as? JsonValue.NumberValue)?.value?.toLongOrNull()
        ?: throw StrictJsonException("Expected $context to be an integer")

fun JsonValue.asFloat(context: String): Float =
    (this as? JsonValue.NumberValue)?.value?.toFloatOrNull()?.takeIf(Float::isFinite)
        ?: throw StrictJsonException("Expected $context to be a finite number")

fun JsonValue.asBoolean(context: String): Boolean =
    (this as? JsonValue.BooleanValue)?.value
        ?: throw StrictJsonException("Expected $context to be a boolean")

fun Map<String, JsonValue>.requiredString(key: String): String =
    get(key)?.asString(key) ?: throw StrictJsonException("Missing JSON field: $key")

fun Map<String, JsonValue>.optionalString(key: String): String? = when (val value = get(key)) {
    null, JsonValue.NullValue -> null
    else -> value.asString(key)
}

fun Map<String, JsonValue>.requiredLong(key: String): Long =
    get(key)?.asLong(key) ?: throw StrictJsonException("Missing JSON field: $key")

fun Map<String, JsonValue>.requiredInt(key: String): Int =
    requiredLong(key).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        ?: throw StrictJsonException("JSON field $key is outside the integer range")

fun Map<String, JsonValue>.requiredFloat(key: String): Float =
    get(key)?.asFloat(key) ?: throw StrictJsonException("Missing JSON field: $key")

fun Map<String, JsonValue>.requiredBoolean(key: String): Boolean =
    get(key)?.asBoolean(key) ?: throw StrictJsonException("Missing JSON field: $key")

fun Map<String, JsonValue>.requiredArray(key: String): List<JsonValue> =
    get(key)?.asArray(key) ?: throw StrictJsonException("Missing JSON field: $key")

fun Map<String, JsonValue>.requireOnly(vararg allowed: String) {
    val unexpected = keys - allowed.toSet()
    if (unexpected.isNotEmpty()) throw StrictJsonException("Unexpected JSON fields: ${unexpected.sorted()}")
}
