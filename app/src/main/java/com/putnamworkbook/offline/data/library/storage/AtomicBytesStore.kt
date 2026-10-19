package com.putnamworkbook.offline.data.library.storage

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

open class AtomicBytesStore {
    open fun write(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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

    fun removeStaleTemporaryFiles(root: Path) {
        if (!Files.isDirectory(root)) return
        Files.newDirectoryStream(root) { path -> path.fileName.toString().endsWith(".tmp") }.use { entries ->
            entries.forEach(Files::deleteIfExists)
        }
    }
}
