package com.putnamworkbook.offline.data.library.packages

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class CentralZipEntry(
    val name: String,
    val flags: Int,
    val compressionMethod: Int,
    val uncompressedSize: Long,
)

internal class ZipCentralDirectoryValidator {
    fun validate(bytes: ByteArray, limits: PackageLimits): List<CentralZipEntry> {
        val eocd = findSignatureBackward(bytes, EOCD_SIGNATURE)
        if (eocd < 0 || eocd + 22 > bytes.size) fail("Invalid ZIP central directory")
        val disk = ushort(bytes, eocd + 4)
        val centralDisk = ushort(bytes, eocd + 6)
        val entriesOnDisk = ushort(bytes, eocd + 8)
        val entryCount = ushort(bytes, eocd + 10)
        val centralSize = uint(bytes, eocd + 12)
        val centralOffset = uint(bytes, eocd + 16)
        val commentLength = ushort(bytes, eocd + 20)
        if (disk != 0 || centralDisk != 0 || entriesOnDisk != entryCount) fail("Multi-disk ZIPs are forbidden")
        if (entryCount == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL) fail("ZIP64 packages are forbidden")
        if (entryCount > limits.maxEntries || centralOffset + centralSize > eocd || eocd + 22 + commentLength != bytes.size) {
            fail("Invalid ZIP central directory bounds")
        }
        val entries = ArrayList<CentralZipEntry>(entryCount)
        val folded = hashSetOf<String>()
        var offset = centralOffset.toInt()
        repeat(entryCount) {
            if (offset + 46 > bytes.size || uint(bytes, offset) != CENTRAL_SIGNATURE.toLong()) fail("Invalid ZIP central entry")
            val versionMadeBy = ushort(bytes, offset + 4)
            val flags = ushort(bytes, offset + 8)
            val method = ushort(bytes, offset + 10)
            val uncompressed = uint(bytes, offset + 24)
            val nameLength = ushort(bytes, offset + 28)
            val extraLength = ushort(bytes, offset + 30)
            val entryCommentLength = ushort(bytes, offset + 32)
            val externalAttributes = uint(bytes, offset + 38)
            val end = offset + 46 + nameLength + extraLength + entryCommentLength
            if (end > bytes.size) fail("Invalid ZIP central entry bounds")
            val name = decodeName(bytes.copyOfRange(offset + 46, offset + 46 + nameLength))
            if (name != "package.json" && !isSafeMemberPath(name)) fail("$name: unsafe ZIP entry path")
            if (name.endsWith('/')) fail("$name: directory entries are forbidden")
            if (!folded.add(name.lowercase())) fail("$name: duplicate or case-colliding ZIP entry")
            if (flags and 1 != 0) fail("$name: encrypted ZIP entries are forbidden")
            if (method !in setOf(0, 8)) fail("$name: unsupported compression method")
            if (uncompressed > limits.maxEntryBytes) fail("$name: exceeds maximum entry bytes")
            val platform = versionMadeBy ushr 8
            if (platform == 3) {
                val mode = (externalAttributes ushr 16).toInt() and 0xffff
                if (mode and 0xf000 != 0x8000) fail("$name: directory, symlink, or special ZIP entries are forbidden")
            }
            entries += CentralZipEntry(name, flags, method, uncompressed)
            offset = end
        }
        if (offset.toLong() != centralOffset + centralSize) fail("Invalid ZIP central directory size")
        if (entries.map { it.name } != entries.map { it.name }.sorted()) fail("ZIP entries are not in canonical sorted order")
        return entries
    }

    private fun decodeName(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: Exception) {
        throw PackageValidationException("ZIP entry name is not valid UTF-8", error)
    }

    private fun findSignatureBackward(bytes: ByteArray, signature: Int): Int {
        val minimum = (bytes.size - 65_557).coerceAtLeast(0)
        for (index in bytes.size - 22 downTo minimum) if (uint(bytes, index) == signature.toLong()) return index
        return -1
    }

    private fun ushort(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) fail("Invalid ZIP structure")
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }

    private fun uint(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) fail("Invalid ZIP structure")
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }

    private companion object {
        const val EOCD_SIGNATURE = 0x06054b50
        const val CENTRAL_SIGNATURE = 0x02014b50
    }
}

private fun fail(message: String): Nothing = throw PackageValidationException(message)
