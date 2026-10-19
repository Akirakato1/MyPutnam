package com.putnamworkbook.offline.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
            BackupArchive.USER_DATA_PATH to "{\"schemaVersion\":2}".encodeToByteArray(),
            "notebooks/course/unit/P1/manifest.bin" to byteArrayOf(1, 2, 3, 4),
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
    fun schemaThreeLibraryStateRoundTripsDeterministicallyWithoutPackageBytes() {
        val state = LibraryBackupState(
            desiredKeys = setOf("putnam/2025/A1", "reference/number-theory/euler-totient"),
            logicallyRemovedBundledKeys = setOf("putnam/2024/B2"),
            activeDigests = mapOf("putnam/2025/A1" to "a".repeat(64)),
        )
        val first = LibraryBackupStateCodec.encode(state)
        val second = LibraryBackupStateCodec.encode(state.copy(desiredKeys = state.desiredKeys.reversed().toSet()))
        assertArrayEquals(first, second)
        assertEquals(state, LibraryBackupStateCodec.decode(first))

        val archiveBytes = ByteArrayOutputStream().also { output ->
            BackupArchive().write(
                mapOf(
                    BackupArchive.USER_DATA_PATH to "{\"schemaVersion\":2}".encodeToByteArray(),
                    BackupArchive.LIBRARY_STATE_PATH to first,
                ),
                output,
            )
        }.toByteArray()
        val names = java.util.zip.ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
            buildList { while (true) add((zip.nextEntry ?: break).name) }
        }
        assertTrue(BackupArchive.LIBRARY_STATE_PATH in names)
        assertTrue(names.none { it.endsWith(".zip") || it.endsWith(".html") })
    }

    @Test
    fun schemaTwoArchiveWithoutLibraryStateRemainsReadable() {
        val userData = "{\"schemaVersion\":2}".encodeToByteArray()
        val member = BackupMember(BackupArchive.USER_DATA_PATH, userData.size.toLong(), sha256(userData))
        val bytes = rawZip(
            BackupArchive.USER_DATA_PATH to userData,
            BackupArchive.MANIFEST_PATH to BackupManifestCodec.encode(BackupManifest(2, listOf(member))),
        )
        val restored = BackupArchive().read(ByteArrayInputStream(bytes), temporaryFolder.newFolder("v2").toPath())
        assertEquals(2, restored.manifest.schemaVersion)
        assertEquals(setOf(BackupArchive.USER_DATA_PATH), restored.memberPaths)
    }

    @Test
    fun malformedLibraryStateKeysDigestsAndUnknownFieldsAreRejected() {
        val malformed = listOf(
            "{\"schemaVersion\":1,\"desiredKeys\":[\"../escape\"],\"logicallyRemovedBundledKeys\":[],\"activeDigests\":{}}",
            "{\"schemaVersion\":1,\"desiredKeys\":[\"putnam/2025/A1\",\"putnam/2025/A1\"],\"logicallyRemovedBundledKeys\":[],\"activeDigests\":{}}",
            "{\"schemaVersion\":1,\"desiredKeys\":[],\"logicallyRemovedBundledKeys\":[],\"activeDigests\":{\"putnam/2025/A1\":\"bad\"}}",
            "{\"schemaVersion\":1,\"desiredKeys\":[],\"logicallyRemovedBundledKeys\":[],\"activeDigests\":{},\"extra\":true}",
            "{\"schemaVersion\":2,\"desiredKeys\":[],\"logicallyRemovedBundledKeys\":[],\"activeDigests\":{}}",
        )
        malformed.forEach { json ->
            assertThrows(BackupFormatException::class.java) {
                LibraryBackupStateCodec.decode(json.encodeToByteArray())
            }
        }
    }

    @Test
    fun caseDistinctCanonicalProblemPathsArePreservedInTheArchive() {
        val bytes = ByteArrayOutputStream().also { output ->
            BackupArchive().write(
                linkedMapOf(
                    BackupArchive.USER_DATA_PATH to "{\"schemaVersion\":2}".encodeToByteArray(),
                    "notebooks/course/unit/P1/manifest.bin" to byteArrayOf(1),
                    "notebooks/course/unit/p1/manifest.bin" to byteArrayOf(2),
                ),
                output,
            )
        }.toByteArray()

        val names = buildList {
            java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) add((zip.nextEntry ?: break).name)
            }
        }
        assertEquals(1, names.count { it == "notebooks/course/unit/P1/manifest.bin" })
        assertEquals(1, names.count { it == "notebooks/course/unit/p1/manifest.bin" })
    }

    @Test
    fun tamperedHashIsRejected() {
        val userData = "{}".encodeToByteArray()
        val manifest = BackupManifest(
            schemaVersion = 2,
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
        val manifest = BackupManifest(2, listOf(member, member))

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
        val manifest = BackupManifest(schemaVersion = 4, members = emptyList())
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
