package com.putnamworkbook.offline.notebook.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

fun interface AtomicFileWriter {
    fun write(target: Path, bytes: ByteArray)
}

class DurableAtomicFileWriter : AtomicFileWriter {
    override fun write(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            check(Files.isRegularFile(target) && Files.size(target) == bytes.size.toLong()) {
                "Atomic write verification failed for $target"
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
