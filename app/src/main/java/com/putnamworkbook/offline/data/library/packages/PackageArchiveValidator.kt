package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.json.StrictJsonCodec
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class PackageLimits(
    val maxCompressedBytes: Long = 16L * 1024 * 1024,
    val maxUncompressedBytes: Long = 64L * 1024 * 1024,
    val maxEntries: Int = 256,
    val maxEntryBytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(maxCompressedBytes > 0 && maxUncompressedBytes > 0 && maxEntries > 0 && maxEntryBytes > 0)
    }
}

class PackageValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class VerifiedPackage(val envelope: PackageEnvelope)

fun interface PackageVerifier {
    fun validate(bytes: ByteArray, expected: PackageDescriptor, expectedContentKey: String): VerifiedPackage
}

class PackageArchiveValidator(
    private val currentAppVersionCode: Int,
    private val limits: PackageLimits = PackageLimits(),
    private val envelopeCodec: PackageEnvelopeCodec = PackageEnvelopeCodec(),
    private val fragmentAuditor: HtmlFragmentAuditor = HtmlFragmentAuditor(),
) : PackageVerifier {
    private val centralDirectoryValidator = ZipCentralDirectoryValidator()
    override fun validate(bytes: ByteArray, expected: PackageDescriptor, expectedContentKey: String): VerifiedPackage {
        try {
            validateDescriptor(bytes, expected)
            val central = centralDirectoryValidator.validate(bytes, limits)
            val actual = linkedMapOf<String, ActualMember>()
            var packageJson: ByteArray? = null
            var totalBytes = 0L
            val streamNames = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    streamNames += entry.name
                    val payload = readBounded(zip, entry.name)
                    totalBytes = Math.addExact(totalBytes, payload.size.toLong())
                    if (totalBytes > limits.maxUncompressedBytes) reject("Package exceeds maximum uncompressed bytes")
                    if (entry.name == "package.json") {
                        if (packageJson != null) reject("Duplicate package.json")
                        packageJson = payload
                    } else {
                        actual[entry.name] = ActualMember(payload.size.toLong(), sha256(payload), payload)
                    }
                    zip.closeEntry()
                }
            }
            if (streamNames != central.map(CentralZipEntry::name)) reject("ZIP local entries do not match central directory")
            if (streamNames.size != central.size) reject("ZIP entry count mismatch")
            val envelope = envelopeCodec.decode(packageJson ?: reject("Package is missing package.json"))
            validatePair(envelope, expected, expectedContentKey)
            if (actual.size != envelope.members.size) reject("ZIP contains undeclared or missing package members")
            val declarations = envelope.members.associateBy(PackageMemberDeclaration::path)
            actual.forEach { (path, member) ->
                val declaration = declarations[path] ?: reject("$path: undeclared package member")
                if (member.bytes != declaration.bytes) reject("$path: member byte length does not match envelope")
                if (member.sha256 != declaration.sha256) reject("$path: member hash does not match envelope")
                auditMember(declaration, member.payload)
            }
            envelope.members.forEach { if (it.path !in actual) reject("${it.path}: declared package member is missing") }
            return VerifiedPackage(envelope)
        } catch (error: PackageValidationException) {
            throw error
        } catch (error: ZipException) {
            throw PackageValidationException("Invalid ZIP payload", error)
        } catch (error: Exception) {
            throw PackageValidationException(error.message ?: "Invalid package archive", error)
        }
    }

    private fun validateDescriptor(bytes: ByteArray, descriptor: PackageDescriptor) {
        if (bytes.size.toLong() > limits.maxCompressedBytes) reject("Package exceeds maximum compressed bytes")
        if (descriptor.compressedBytes != bytes.size.toLong()) reject("Descriptor compressedBytes does not match archive")
        if (descriptor.sha256 != sha256(bytes)) reject("Descriptor sha256 does not match archive")
        if (descriptor.uncompressedBytes > limits.maxUncompressedBytes) reject("Descriptor uncompressedBytes exceeds limit")
        if (!isSafeMemberPath(descriptor.path)) reject("Unsafe package descriptor path")
        if (descriptor.minAppVersionCode > currentAppVersionCode) reject("Package requires a newer app version")
    }

    private fun validatePair(envelope: PackageEnvelope, descriptor: PackageDescriptor, expectedKey: String) {
        if (envelope.contentKey != expectedKey) reject("Package content key does not match catalog entry")
        if (envelope.contentType != descriptor.contentType ||
            envelope.packageRevision != descriptor.packageRevision ||
            envelope.packageSchemaVersion != descriptor.packageSchemaVersion ||
            envelope.contentSchemaVersion != descriptor.contentSchemaVersion ||
            envelope.richDocumentVersion != descriptor.richDocumentVersion ||
            envelope.minAppVersionCode != descriptor.minAppVersionCode ||
            envelope.declaredUncompressedBytes != descriptor.uncompressedBytes
        ) reject("Package envelope does not match catalog descriptor")
        if (envelope.minAppVersionCode > currentAppVersionCode) reject("Package requires a newer app version")
        if (envelope.members.size + 1 > limits.maxEntries) reject("Package exceeds maximum entry count")
        if (envelope.members.any { it.bytes > limits.maxEntryBytes }) reject("Package member exceeds maximum entry bytes")
        if (envelope.declaredUncompressedBytes > limits.maxUncompressedBytes) reject("Package exceeds maximum uncompressed bytes")
    }

    private fun auditMember(declaration: PackageMemberDeclaration, bytes: ByteArray) {
        when {
            declaration.path.startsWith("source/") && declaration.path.endsWith(".json") -> {
                if (declaration.mediaType != "application/json") reject("${declaration.path}: expected application/json")
                try { StrictJsonCodec.parse(decodeUtf8(bytes)) } catch (error: Exception) {
                    throw PackageValidationException("${declaration.path}: invalid JSON", error)
                }
            }
            declaration.path.startsWith("documents/") && declaration.path.endsWith(".fragment.html") -> {
                if (declaration.mediaType != "text/html; charset=utf-8") reject("${declaration.path}: invalid fragment media type")
                val errors = fragmentAuditor.audit(bytes)
                if (errors.isNotEmpty()) reject("${declaration.path}: ${errors.joinToString()}")
            }
            declaration.path.startsWith("assets/") -> auditAsset(declaration, bytes)
            else -> reject("${declaration.path}: unsupported package member path")
        }
    }

    private fun auditAsset(declaration: PackageMemberDeclaration, bytes: ByteArray) {
        val expectedType = when (declaration.path.substringAfterLast('.', "").lowercase()) {
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> reject("${declaration.path}: unsupported asset extension")
        }
        if (declaration.mediaType != expectedType) reject("${declaration.path}: asset media type mismatch")
        when (expectedType) {
            "image/png" -> if (!bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) reject("Invalid PNG signature")
            "image/jpeg" -> if (bytes.size < 3 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() || bytes[2] != 0xff.toByte()) reject("Invalid JPEG signature")
            "image/webp" -> if (bytes.size < 12 || bytes.copyOfRange(0, 4).decodeToString() != "RIFF" || bytes.copyOfRange(8, 12).decodeToString() != "WEBP") reject("Invalid WebP signature")
            "image/svg+xml" -> auditSvg(bytes)
        }
    }

    private fun auditSvg(bytes: ByteArray) {
        val source = decodeUtf8(bytes)
        if (!Regex("^\\s*<svg\\b", RegexOption.IGNORE_CASE).containsMatchIn(source) ||
            !Regex("</svg>\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(source)
        ) reject("SVG must have an explicit svg root")
        if (Regex("<!DOCTYPE|<!ENTITY|</?(?:script|style|foreignObject|iframe|object|embed)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source)) reject("SVG contains active content")
        if (Regex("\\son[a-z][a-z\\d:_-]*\\s*=|\\sstyle\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(source)) reject("SVG contains active attributes")
        if (Regex("(?:https?:|//|data:|javascript:|file:)", RegexOption.IGNORE_CASE).containsMatchIn(source.replace(Regex("\\sxmlns=\"http://www.w3.org/2000/svg\"", RegexOption.IGNORE_CASE), ""))) reject("SVG contains an external URL")
    }

    private fun readBounded(zip: ZipInputStream, name: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            if (total > limits.maxEntryBytes) reject("$name: exceeds maximum entry bytes")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class ActualMember(val bytes: Long, val sha256: String, val payload: ByteArray)
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun reject(message: String): Nothing = throw PackageValidationException(message)
