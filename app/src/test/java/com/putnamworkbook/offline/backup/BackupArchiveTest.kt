package com.putnamworkbook.offline.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validArchiveRoundTripsEveryPayload() {
        val payloads = linkedMapOf(
            BackupArchive.USER_DATA_PATH to "{\"schemaVersion\":1}".encodeToByteArray(),
            "notebooks/2025-A1/manifest.bin" to byteArrayOf(1, 2, 3, 4),
        )
        val bytes = ByteArrayOutputStream().also { BackupArchive().write(payloads, it) }.toByteArray()
        val staging = temporaryFolder.newFolder("round-trip").toPath()

        val restored = BackupArchive().read(ByteArrayInputStream(bytes), staging)

        assertEquals(payloads.keys, restored.memberPaths)
        payloads.forEach { (path, expected) ->
            assertArrayEquals(expected, Files.readAllBytes(restored.resolve(path)))
        }
    }

    @Test
    fun tamperedHashIsRejected() {
        val userData = "{}".encodeToByteArray()
        val manifest = BackupManifest(
            schemaVersion = 1,
            members = listOf(
                BackupMember(BackupArchive.USER_DATA_PATH, userData.size.toLong(), "0".repeat(64)),
            ),
        )

        assertFormatFailure(
            rawZip(
                BackupArchive.USER_DATA_PATH to userData,
                BackupArchive.MANIFEST_PATH to BackupManifestCodec.encode(manifest),
            ),
        )
    }

    @Test
    fun traversalAndAbsoluteMembersAreRejected() {
        listOf("../escape", "/absolute", "C:\\absolute", "notebooks//alias").forEachIndexed { index, path ->
            assertFormatFailure(rawZip(path to byteArrayOf(1)), "unsafe-$index")
        }
    }

    @Test
    fun duplicateZipMembersAreRejected() {
        val original = rawZip("a.txt" to byteArrayOf(1), "b.txt" to byteArrayOf(2))
        val duplicate = original.replaceAscii("b.txt", "a.txt")

        assertFormatFailure(duplicate)
    }

    @Test
    fun duplicateManifestMembersAreRejected() {
        val payload = byteArrayOf(7)
        val member = BackupMember("notebooks/a", 1, sha256(payload))
        val manifest = BackupManifest(1, listOf(member, member))

        assertFormatFailure(
            rawZip(
                "notebooks/a" to payload,
                BackupArchive.MANIFEST_PATH to BackupManifestCodec.encode(manifest),
            ),
        )
    }

    @Test
    fun uncompressedSizeLimitIsEnforcedWhileStreaming() {
        val archive = BackupArchive(BackupLimits(maxMemberBytes = 8, maxTotalBytes = 8, maxMembers = 4))
        val bytes = rawZip("large.bin" to ByteArray(9))
        val staging = temporaryFolder.newFolder("oversized").toPath()

        assertThrows(BackupFormatException::class.java) {
            archive.read(ByteArrayInputStream(bytes), staging)
        }
    }

    @Test
    fun futureSchemaVersionFailsCleanly() {
        val manifest = BackupManifest(schemaVersion = 2, members = emptyList())
        assertFormatFailure(rawZip(BackupArchive.MANIFEST_PATH to BackupManifestCodec.encode(manifest)))
    }

    private fun assertFormatFailure(bytes: ByteArray, folderName: String = "invalid") {
        val staging = temporaryFolder.newFolder("$folderName-${System.nanoTime()}").toPath()
        assertThrows(BackupFormatException::class.java) {
            BackupArchive().read(ByteArrayInputStream(bytes), staging)
        }
    }

    private fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun ByteArray.replaceAscii(from: String, to: String): ByteArray {
        require(from.length == to.length)
        val result = copyOf()
        val source = from.encodeToByteArray()
        val replacement = to.encodeToByteArray()
        for (index in 0..result.size - source.size) {
            if (source.indices.all { offset -> result[index + offset] == source[offset] }) {
                replacement.copyInto(result, index)
            }
        }
        return result
    }
}
