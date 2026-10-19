package com.putnamworkbook.offline.data.library.packages

import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.PackageDescriptor
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageArchiveValidatorTest {
    private val validator = PackageArchiveValidator(currentAppVersionCode = 4)

    @Test
    fun validatesOuterDescriptorEnvelopeAndDeclaredMembers() {
        val fixture = packageFixture()
        val verified = validator.validate(fixture.bytes, fixture.descriptor, CONTENT_KEY)
        assertEquals(CONTENT_KEY, verified.envelope.contentKey)
        assertEquals(setOf("source/problem.json"), verified.envelope.members.mapTo(linkedSetOf()) { it.path })
    }

    @Test
    fun rejectsOuterHashSizeAndCompatibilityDrift() {
        val fixture = packageFixture()
        assertFails(fixture.bytes, fixture.descriptor.copy(sha256 = "0".repeat(64)), CONTENT_KEY)
        assertFails(fixture.bytes, fixture.descriptor.copy(compressedBytes = fixture.bytes.size + 1L), CONTENT_KEY)
        assertFails(fixture.bytes, fixture.descriptor, "putnam/2025/A2")
        assertFails(fixture.bytes, fixture.descriptor.copy(minAppVersionCode = 99), CONTENT_KEY)
    }

    @Test
    fun rejectsTraversalAbsoluteBackslashDriveDotAndEmptyPaths() {
        listOf("../escape", "/absolute", "a\\b", "C:/drive", "a/./b", "a//b").forEach { path ->
            val bytes = rawZip(listOf(path to byteArrayOf(1), "package.json" to "{}".encodeToByteArray()))
            assertFails(bytes, descriptor(bytes), CONTENT_KEY)
        }
    }

    @Test
    fun rejectsDuplicatesCaseCollisionsAndSpecialUnixModes() {
        val duplicate = rawZip(listOf(
            "A.txt" to byteArrayOf(1),
            "a.txt" to byteArrayOf(2),
            "package.json" to "{}".encodeToByteArray(),
        ))
        assertFails(duplicate, descriptor(duplicate), CONTENT_KEY)

        val fixture = packageFixture()
        val symlink = patchCentralMode(fixture.bytes, "source/problem.json", 0xA1FF)
        assertFails(symlink, fixture.descriptor.copy(sha256 = sha256(symlink), compressedBytes = symlink.size.toLong()), CONTENT_KEY)
    }

    @Test
    fun rejectsUndeclaredMembersAndInnerSizeOrDigestMismatch() {
        val undeclared = packageFixture(extraEntries = listOf("assets/extra.png" to byteArrayOf(1, 2, 3)))
        assertFails(undeclared.bytes, undeclared.descriptor, CONTENT_KEY)

        val wrongDigest = packageFixture(declaredDigest = "0".repeat(64))
        assertFails(wrongDigest.bytes, wrongDigest.descriptor, CONTENT_KEY)

        val wrongSize = packageFixture(declaredBytes = 99)
        assertFails(wrongSize.bytes, wrongSize.descriptor, CONTENT_KEY)
    }

    @Test
    fun enforcesEntryCountCompressedUncompressedAndPerEntryLimits() {
        val fixture = packageFixture()
        listOf(
            PackageLimits(maxCompressedBytes = fixture.bytes.size - 1L),
            PackageLimits(maxUncompressedBytes = 1),
            PackageLimits(maxEntries = 1),
            PackageLimits(maxEntryBytes = 1),
        ).forEach { limits ->
            assertThrows(PackageValidationException::class.java) {
                PackageArchiveValidator(4, limits).validate(fixture.bytes, fixture.descriptor, CONTENT_KEY)
            }
        }
    }

    @Test
    fun rejectsMalformedZipAndUnsafeFragment() {
        val malformed = "not a zip".encodeToByteArray()
        assertFails(malformed, descriptor(malformed), CONTENT_KEY)

        val fixture = packageFixture(
            members = listOf(
                Member("source/problem.json", "application/json", "{}\n".encodeToByteArray()),
                Member("documents/statement.fragment.html", "text/html; charset=utf-8", "<script>x</script>".encodeToByteArray()),
            ),
        )
        assertFails(fixture.bytes, fixture.descriptor, CONTENT_KEY)
    }

    private fun assertFails(bytes: ByteArray, descriptor: PackageDescriptor, key: String) {
        assertThrows(PackageValidationException::class.java) { validator.validate(bytes, descriptor, key) }
    }

    private fun packageFixture(
        members: List<Member> = listOf(Member("source/problem.json", "application/json", "{}\n".encodeToByteArray())),
        extraEntries: List<Pair<String, ByteArray>> = emptyList(),
        declaredDigest: String? = null,
        declaredBytes: Long? = null,
    ): Fixture {
        val declarations = members.map { member ->
            linkedMapOf<String, Any>(
                "bytes" to (declaredBytes ?: member.bytes.size.toLong()),
                "mediaType" to member.mediaType,
                "path" to member.path,
                "sha256" to (declaredDigest ?: sha256(member.bytes)),
            )
        }
        val base = linkedMapOf<String, Any>(
            "contentKey" to CONTENT_KEY,
            "contentSchemaVersion" to 3,
            "contentType" to "problem",
            "declaredUncompressedBytes" to declarations.sumOf { it["bytes"] as Long },
            "members" to declarations,
            "minAppVersionCode" to 1,
            "packageSchemaVersion" to 1,
            "richDocumentVersion" to 1,
        )
        val envelope = TreeMap(base).apply { put("packageRevision", sha256(canonical(base).encodeToByteArray())) }
        val entries = buildList {
            addAll(members.map { it.path to it.bytes })
            addAll(extraEntries)
            add("package.json" to (canonical(envelope) + "\n").encodeToByteArray())
        }.sortedBy { it.first }
        val bytes = rawZip(entries)
        val revision = envelope["packageRevision"] as String
        return Fixture(bytes, descriptor(bytes).copy(packageRevision = revision, uncompressedBytes = base["declaredUncompressedBytes"] as Long))
    }

    private fun descriptor(bytes: ByteArray) = PackageDescriptor(
        contentType = CatalogContentType.PROBLEM,
        packageRevision = "a".repeat(64),
        packageSchemaVersion = 1,
        contentSchemaVersion = 3,
        richDocumentVersion = 1,
        path = "packages/problem/test.zip",
        compressedBytes = bytes.size.toLong(),
        uncompressedBytes = 3,
        sha256 = sha256(bytes),
        minAppVersionCode = 1,
    )

    private fun rawZip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun patchCentralMode(bytes: ByteArray, target: String, mode: Int): ByteArray {
        val patched = bytes.copyOf()
        var offset = 0
        while (offset + 46 <= patched.size) {
            if (readInt(patched, offset) != 0x02014b50) {
                offset += 1
                continue
            }
            val nameLength = readShort(patched, offset + 28)
            val extraLength = readShort(patched, offset + 30)
            val commentLength = readShort(patched, offset + 32)
            val name = String(patched, offset + 46, nameLength, StandardCharsets.UTF_8)
            if (name == target) {
                patched[offset + 5] = 3
                writeInt(patched, offset + 38, mode shl 16)
                return patched
            }
            offset += 46 + nameLength + extraLength + commentLength
        }
        error("central entry not found")
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Number, is Boolean -> value.toString()
        is List<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        is Map<*, *> -> value.entries.sortedBy { it.key as String }
            .joinToString(",", "{", "}") { canonical(it.key) + ":" + canonical(it.value) }
        else -> error("unsupported canonical value")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readShort(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readInt(bytes: ByteArray, offset: Int) = readShort(bytes, offset) or (readShort(bytes, offset + 2) shl 16)

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private data class Member(val path: String, val mediaType: String, val bytes: ByteArray)
    private data class Fixture(val bytes: ByteArray, val descriptor: PackageDescriptor)

    companion object { private const val CONTENT_KEY = "putnam/2025/A1" }
}
