package com.putnamworkbook.offline.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupLimits(
    val maxMemberBytes: Long = 64L * 1024L * 1024L,
    val maxTotalBytes: Long = 512L * 1024L * 1024L,
    val maxMembers: Int = 100_000,
) {
    init {
        require(maxMemberBytes > 0)
        require(maxTotalBytes >= maxMemberBytes)
        require(maxMembers > 0)
    }
}

data class BackupSource(
    val path: String,
    val size: Long,
    val open: () -> InputStream,
)

class ValidatedBackup internal constructor(
    val stagingDirectory: Path,
    val manifest: BackupManifest,
) {
    val memberPaths: Set<String> = manifest.members.mapTo(linkedSetOf()) { it.path }

    fun resolve(path: String): Path {
        if (path !in memberPaths) throw BackupFormatException("Archive member is not declared: $path")
        return resolveSafe(stagingDirectory, path)
    }
}

class BackupArchive(
    private val limits: BackupLimits = BackupLimits(),
) {
    fun write(members: Map<String, ByteArray>, destination: OutputStream) {
        write(
            members.map { (path, bytes) ->
                BackupSource(path, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
            },
            destination,
        )
    }

    fun write(sources: List<BackupSource>, destination: OutputStream) {
        if (sources.size > limits.maxMembers) throw BackupFormatException("Backup has too many members")
        val duplicate = sources.groupBy { canonicalPath(it.path) }.values.firstOrNull { it.size > 1 }
        if (duplicate != null) throw BackupFormatException("Backup contains duplicate member names")
        if (sources.none { it.path == USER_DATA_PATH }) {
            throw BackupFormatException("Backup is missing $USER_DATA_PATH")
        }
        var totalBytes = 0L
        sources.forEach { source ->
            validateMemberPath(source.path)
            if (source.path == MANIFEST_PATH) throw BackupFormatException("Payload cannot replace the manifest")
            if (source.size !in 0..limits.maxMemberBytes) {
                throw BackupFormatException("Backup member is too large: ${source.path}")
            }
            totalBytes = checkedTotal(totalBytes, source.size)
        }

        ZipOutputStream(destination).use { zip ->
            val manifestMembers = sources.sortedBy { it.path }.map { source ->
                val digest = MessageDigest.getInstance(SHA_256)
                var written = 0L
                zip.putNextEntry(ZipEntry(source.path).apply { time = 0L })
                source.open().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > source.size || written > limits.maxMemberBytes) {
                            throw BackupFormatException("Backup source changed size while being read: ${source.path}")
                        }
                        digest.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                if (written != source.size) {
                    throw BackupFormatException("Backup source changed size while being read: ${source.path}")
                }
                BackupMember(source.path, written, digest.digest().toHex())
            }
            val manifestBytes = BackupManifestCodec.encode(
                BackupManifest(CURRENT_SCHEMA_VERSION, manifestMembers),
            )
            if (manifestBytes.size > limits.maxMemberBytes) {
                throw BackupFormatException("Backup manifest is too large")
            }
            checkedTotal(totalBytes, manifestBytes.size.toLong())
            zip.putNextEntry(ZipEntry(MANIFEST_PATH).apply { time = 0L })
            zip.write(manifestBytes)
            zip.closeEntry()
        }
    }

    fun read(source: InputStream, stagingDirectory: Path): ValidatedBackup {
        prepareEmptyDirectory(stagingDirectory)
        val actualMembers = linkedMapOf<String, BackupMember>()
        val canonicalNames = mutableSetOf<String>()
        var manifestBytes: ByteArray? = null
        var totalBytes = 0L
        var entryCount = 0

        try {
            ZipInputStream(source).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > limits.maxMembers + 1) {
                        throw BackupFormatException("Backup has too many members")
                    }
                    if (entry.isDirectory) throw BackupFormatException("Backup contains a directory entry")
                    val path = entry.name ?: throw BackupFormatException("Backup member has no name")
                    validateMemberPath(path)
                    if (!canonicalNames.add(canonicalPath(path))) {
                        throw BackupFormatException("Backup contains duplicate member names")
                    }
                    if (entry.size > limits.maxMemberBytes) {
                        throw BackupFormatException("Backup member is too large: $path")
                    }

                    val digest = MessageDigest.getInstance(SHA_256)
                    var memberBytes = 0L
                    val target = if (path == MANIFEST_PATH) null else resolveSafe(stagingDirectory, path)
                    val manifestOutput = if (target == null) ByteArrayOutputStream() else null
                    target?.parent?.let(Files::createDirectories)
                    val output = target?.let(Files::newOutputStream) ?: requireNotNull(manifestOutput)
                    output.use {
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            memberBytes += count
                            totalBytes += count
                            if (memberBytes > limits.maxMemberBytes || totalBytes > limits.maxTotalBytes) {
                                throw BackupFormatException("Backup exceeds the uncompressed size limit")
                            }
                            digest.update(buffer, 0, count)
                            it.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                    if (path == MANIFEST_PATH) {
                        manifestBytes = requireNotNull(manifestOutput).toByteArray()
                    } else {
                        actualMembers[path] = BackupMember(path, memberBytes, digest.digest().toHex())
                    }
                }
            }
        } catch (error: BackupFormatException) {
            throw error
        } catch (error: Exception) {
            throw BackupFormatException("Backup ZIP could not be read", error)
        }

        val manifest = BackupManifestCodec.decode(
            manifestBytes ?: throw BackupFormatException("Backup manifest is missing"),
        )
        if (manifest.schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw BackupFormatException("Unsupported backup schema version: ${manifest.schemaVersion}")
        }
        if (manifest.members.size > limits.maxMembers) throw BackupFormatException("Backup has too many members")
        val declared = linkedMapOf<String, BackupMember>()
        val declaredCanonical = mutableSetOf<String>()
        manifest.members.forEach { member ->
            validateMemberPath(member.path)
            if (member.path == MANIFEST_PATH) throw BackupFormatException("Manifest cannot declare itself")
            if (!declaredCanonical.add(canonicalPath(member.path))) {
                throw BackupFormatException("Manifest declares duplicate member names")
            }
            declared[member.path] = member
        }
        if (USER_DATA_PATH !in declared) throw BackupFormatException("Backup is missing $USER_DATA_PATH")
        if (declared.keys != actualMembers.keys) {
            throw BackupFormatException("Backup members do not match the manifest")
        }
        declared.forEach { (path, expected) ->
            val actual = actualMembers.getValue(path)
            if (expected.size != actual.size || expected.sha256 != actual.sha256) {
                throw BackupFormatException("Backup member failed verification: $path")
            }
        }
        return ValidatedBackup(stagingDirectory, manifest)
    }

    private fun checkedTotal(current: Long, additional: Long): Long {
        if (additional < 0 || current > limits.maxTotalBytes - additional) {
            throw BackupFormatException("Backup exceeds the uncompressed size limit")
        }
        return current + additional
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val MANIFEST_PATH = "backup-manifest.json"
        const val USER_DATA_PATH = "user-data.json"
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance(SHA_256).digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun validateMemberPath(path: String) {
    if (path.isBlank() || path.length > MAX_PATH_LENGTH) throw BackupFormatException("Unsafe backup member path")
    if (path.startsWith('/') || path.startsWith('\\') || '\\' in path || ':' in path || '\u0000' in path) {
        throw BackupFormatException("Unsafe backup member path: $path")
    }
    val parts = path.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
        throw BackupFormatException("Unsafe backup member path: $path")
    }
}

private fun canonicalPath(path: String): String = path

private fun prepareEmptyDirectory(directory: Path) {
    Files.createDirectories(directory)
    Files.newDirectoryStream(directory).use { entries ->
        if (entries.iterator().hasNext()) throw BackupFormatException("Backup staging directory is not empty")
    }
}

private fun resolveSafe(root: Path, memberPath: String): Path {
    validateMemberPath(memberPath)
    val normalizedRoot = root.toAbsolutePath().normalize()
    val resolved = normalizedRoot.resolve(memberPath).normalize()
    if (!resolved.startsWith(normalizedRoot)) throw BackupFormatException("Unsafe backup member path: $memberPath")
    return resolved
}

private const val SHA_256 = "SHA-256"
private const val BUFFER_SIZE = 32 * 1024
private const val MAX_PATH_LENGTH = 512
