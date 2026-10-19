package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.json.JsonLimits
import com.putnamworkbook.offline.data.json.JsonValue
import com.putnamworkbook.offline.data.json.StrictJsonCodec
import com.putnamworkbook.offline.data.json.asObject
import com.putnamworkbook.offline.data.json.requireOnly
import com.putnamworkbook.offline.data.json.requiredArray
import com.putnamworkbook.offline.data.json.requiredInt
import com.putnamworkbook.offline.data.json.requiredLong
import com.putnamworkbook.offline.data.json.requiredString
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.TreeMap

data class PackageMemberDeclaration(
    val path: String,
    val mediaType: String,
    val bytes: Long,
    val sha256: String,
)

data class PackageEnvelope(
    val packageSchemaVersion: Int,
    val contentType: CatalogContentType,
    val contentKey: String,
    val contentSchemaVersion: Int,
    val richDocumentVersion: Int,
    val minAppVersionCode: Int,
    val declaredUncompressedBytes: Long,
    val members: List<PackageMemberDeclaration>,
    val packageRevision: String,
)

class PackageEnvelopeCodec {
    fun decode(bytes: ByteArray): PackageEnvelope {
        val source = decodeUtf8(bytes)
        val value = try {
            StrictJsonCodec.parse(source, JsonLimits(maxInputChars = MAX_ENVELOPE_BYTES))
        } catch (error: Exception) {
            throw PackageValidationException("Invalid package.json", error)
        }
        if (source != canonical(value) + "\n") throw PackageValidationException("package.json is not canonical JSON")
        val row = value.asObject("package envelope")
        row.requireOnly(
            "packageSchemaVersion", "contentType", "contentKey", "contentSchemaVersion",
            "richDocumentVersion", "minAppVersionCode", "declaredUncompressedBytes", "members", "packageRevision",
        )
        val members = row.requiredArray("members").mapIndexed { index, memberValue ->
            val member = memberValue.asObject("members[$index]")
            member.requireOnly("path", "mediaType", "bytes", "sha256")
            PackageMemberDeclaration(
                path = member.requiredString("path"),
                mediaType = member.requiredString("mediaType"),
                bytes = member.requiredLong("bytes").also { if (it < 0) fail("Negative member size") },
                sha256 = member.requiredString("sha256").requireSha256("member sha256"),
            )
        }
        val envelope = PackageEnvelope(
            packageSchemaVersion = row.requiredInt("packageSchemaVersion"),
            contentType = row.requiredString("contentType").let { wire ->
                CatalogContentType.entries.singleOrNull { it.wireValue == wire }
                    ?: fail("Unsupported content type")
            },
            contentKey = row.requiredString("contentKey"),
            contentSchemaVersion = row.requiredInt("contentSchemaVersion"),
            richDocumentVersion = row.requiredInt("richDocumentVersion"),
            minAppVersionCode = row.requiredInt("minAppVersionCode"),
            declaredUncompressedBytes = row.requiredLong("declaredUncompressedBytes"),
            members = members,
            packageRevision = row.requiredString("packageRevision").requireSha256("package revision"),
        )
        validate(envelope)
        val withoutRevision = JsonValue.ObjectValue(LinkedHashMap(row).apply { remove("packageRevision") })
        if (sha256(canonical(withoutRevision).encodeToByteArray()) != envelope.packageRevision) {
            fail("Package revision does not match canonical envelope payload")
        }
        return envelope
    }

    private fun validate(envelope: PackageEnvelope) {
        if (envelope.packageSchemaVersion != 1) fail("Unsupported package schema version")
        val supported = when (envelope.contentType) {
            CatalogContentType.PROBLEM -> envelope.contentSchemaVersion == 3 && envelope.richDocumentVersion == 1
            CatalogContentType.REFERENCE -> envelope.contentSchemaVersion == 1 && envelope.richDocumentVersion == 2
        }
        if (!supported) fail("Unsupported content schema combination")
        if (!isCanonicalContentKey(envelope.contentType, envelope.contentKey)) fail("Invalid canonical content key")
        if (envelope.minAppVersionCode < 1 || envelope.declaredUncompressedBytes < 0) fail("Invalid package compatibility values")
        val folded = hashSetOf<String>()
        var declared = 0L
        envelope.members.forEach { member ->
            if (!isSafeMemberPath(member.path) || member.path == "package.json") fail("Unsafe package member path")
            if (!folded.add(member.path.lowercase())) fail("Duplicate or case-colliding package member path")
            declared = Math.addExact(declared, member.bytes)
        }
        if (declared != envelope.declaredUncompressedBytes) fail("Declared uncompressed bytes do not match members")
    }

    private fun canonical(value: JsonValue): String = StrictJsonCodec.stringify(sort(value))

    private fun sort(value: JsonValue): JsonValue = when (value) {
        is JsonValue.ObjectValue -> JsonValue.ObjectValue(LinkedHashMap(TreeMap(value.values).mapValues { sort(it.value) }))
        is JsonValue.ArrayValue -> JsonValue.ArrayValue(value.values.map(::sort))
        else -> value
    }

    companion object { private const val MAX_ENVELOPE_BYTES = 1024 * 1024 }
}

internal fun isSafeMemberPath(path: String): Boolean {
    if (path.isEmpty() || path.startsWith('/') || path.contains('\\') || path.contains(':') || path.contains('?') || path.contains('#')) return false
    if (Regex("^[A-Za-z]:").containsMatchIn(path)) return false
    if (path.any { it.code < 0x20 }) return false
    val segments = path.split('/')
    return segments.all { it.isNotEmpty() && it != "." && it != ".." }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (error: Exception) {
    throw PackageValidationException("Content is not valid UTF-8", error)
}

private fun String.requireSha256(label: String): String = also {
    if (!Regex("^[0-9a-f]{64}$").matches(it)) throw PackageValidationException("Invalid $label")
}

private fun isCanonicalContentKey(type: CatalogContentType, key: String): Boolean {
    val parts = key.split('/')
    if (parts.size != 3 || parts.any { !Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$").matches(it) }) return false
    return when (type) {
        CatalogContentType.PROBLEM -> true
        CatalogContentType.REFERENCE -> parts.first() == "math-reference"
    }
}

private fun fail(message: String): Nothing = throw PackageValidationException(message)
