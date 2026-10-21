package com.putnamworkbook.offline.content.ui

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import com.putnamworkbook.offline.data.content.ContentResourceRef
import com.putnamworkbook.offline.data.library.index.ContentAvailability
import com.putnamworkbook.offline.data.library.index.InstallError
import com.putnamworkbook.offline.data.library.index.InstallErrorCode
import com.putnamworkbook.offline.data.library.index.InstalledContentSnapshot
import com.putnamworkbook.offline.data.library.packages.PackageEnvelopeCodec
import com.putnamworkbook.offline.data.library.packages.isSafeMemberPath
import com.putnamworkbook.offline.data.library.packages.sha256
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

interface ContentResourceResolver {
    fun urlFor(resource: ContentResourceRef): Uri
    fun intercept(uri: Uri): WebResourceResponse?
    fun readDocument(resource: ContentResourceRef): String
}

class ContentResourceIntegrityException(message: String) : IllegalStateException(message)

class FileContentResourceResolver(
    private val context: Context,
    packagesRoot: Path,
    private val snapshot: () -> InstalledContentSnapshot,
    private val onQuarantine: (String, String, InstallError) -> Unit,
    private val envelopeCodec: PackageEnvelopeCodec = PackageEnvelopeCodec(),
    private val shell: RendererShell = RendererShell(),
) : ContentResourceResolver {
    private val packagesRoot = packagesRoot.toAbsolutePath().normalize()
    private val documents = ConcurrentHashMap<String, ContentResourceRef>()

    override fun urlFor(resource: ContentResourceRef): Uri {
        requireValidRef(resource)
        val uri = uriBuilder(DOCUMENT_PREFIX, resource.packageSha256, resource.contentKey)
            .apply { resource.memberPath.split('/').forEach(::appendPath) }
            .build()
        documents[uri.toString()] = resource
        return uri
    }

    override fun readDocument(resource: ContentResourceRef): String {
        require(resource.memberPath.startsWith("documents/") && resource.memberPath.endsWith(".fragment.html"))
        val fragment = readMember(resource.contentKey, resource.packageSha256, resource.memberPath, resource.memberSha256).decodeToString()
        return shell.wrap(fragment, packageBaseUrl(resource.packageSha256, resource.contentKey))
    }

    override fun intercept(uri: Uri): WebResourceResponse? {
        if (!isLocalUri(uri)) return null
        val segments = uri.pathSegments
        return when (segments.firstOrNull()) {
            RENDERER_PREFIX -> serveRendererStatic(segments.drop(1))
            DOCUMENT_PREFIX -> documents[uri.toString()]?.let { ref ->
                response("text/html", readDocument(ref).encodeToByteArray(), mapOf(
                    "Content-Security-Policy" to RendererShell.CSP,
                    "Cache-Control" to "no-store",
                ))
            }
            PACKAGE_PREFIX -> servePackageAsset(segments)
            else -> null
        }
    }

    private fun servePackageAsset(segments: List<String>): WebResourceResponse? {
        if (segments.size < 6) return null
        val digest = segments[1]
        val key = segments.subList(2, 5).joinToString("/")
        val path = segments.drop(5).joinToString("/")
        if (!path.startsWith("assets/") || !isSafeMemberPath(path)) return null
        val item = activeItem(key, digest)
        val root = packageRoot(digest)
        val envelopeBytes = Files.readAllBytes(root.resolve("package.json"))
        val envelope = envelopeCodec.decode(envelopeBytes)
        val activePackage = when (item.activeSource) {
            com.putnamworkbook.offline.data.library.index.ContentSource.BUNDLED -> item.bundled
            com.putnamworkbook.offline.data.library.index.ContentSource.DOWNLOADED -> item.downloaded
            null -> null
        }
        if (envelope.contentKey != key || envelope.packageRevision != activePackage?.descriptor?.packageRevision) {
            integrity(key, digest, "Package envelope no longer matches the active index")
        }
        val declaration = envelope.members.firstOrNull { it.path == path } ?: return null
        val bytes = readMember(item.metadata.key, digest, path, declaration.sha256)
        return response(declaration.mediaType.substringBefore(';'), bytes, mapOf("Cache-Control" to "no-store"))
    }

    private fun serveRendererStatic(pathSegments: List<String>): WebResourceResponse? {
        val path = pathSegments.joinToString("/")
        if (!isSafeMemberPath(path)) return null
        val bytes = try { context.assets.open("content-library/renderer/$path").use { it.readBytes() } } catch (_: Exception) { return null }
        return response(mediaType(path), bytes, mapOf("Cache-Control" to "no-store"))
    }

    private fun readMember(key: String, digest: String, path: String, expectedSha256: String): ByteArray {
        activeItem(key, digest)
        val target = packageRoot(digest).resolve(path).normalize()
        if (!target.startsWith(packageRoot(digest)) || !Files.isRegularFile(target)) integrity(key, digest, "Package member is missing")
        val bytes = Files.readAllBytes(target)
        if (sha256(bytes) != expectedSha256) integrity(key, digest, "Package member failed its runtime hash check")
        return bytes
    }

    private fun activeItem(key: String, digest: String) = snapshot().items.firstOrNull {
        it.metadata.key == key && it.activeDigest == digest && it.availability == ContentAvailability.INSTALLED
    } ?: integrity(key, digest, "Package revision is not active")

    private fun packageRoot(digest: String): Path {
        if (!SHA256.matches(digest)) throw ContentResourceIntegrityException("Invalid package digest")
        val root = packagesRoot.resolve(digest).normalize()
        if (root.parent != packagesRoot) throw ContentResourceIntegrityException("Package root escaped containment")
        return root
    }

    private fun requireValidRef(resource: ContentResourceRef) {
        if (!SHA256.matches(resource.packageSha256) || !SHA256.matches(resource.memberSha256) || !isSafeMemberPath(resource.memberPath)) {
            throw IllegalArgumentException("Invalid content resource reference")
        }
        if (resource.contentKey.split('/').size != 3) throw IllegalArgumentException("Invalid content key")
    }

    private fun integrity(key: String, digest: String, message: String): Nothing {
        onQuarantine(key, digest, InstallError(InstallErrorCode.INTEGRITY, message))
        throw ContentResourceIntegrityException(message)
    }

    private fun isLocalUri(uri: Uri): Boolean = uri.scheme == "https" && uri.host == HOST && uri.port == -1 &&
        uri.query == null && uri.fragment == null && !Uri.decode(uri.encodedPath.orEmpty()).split('/').any { it == "." || it == ".." }

    private fun uriBuilder(prefix: String, digest: String, key: String): Uri.Builder = Uri.Builder()
        .scheme("https").authority(HOST).appendPath(prefix).appendPath(digest)
        .apply { key.split('/').forEach(::appendPath) }

    private fun packageBaseUrl(digest: String, key: String): String =
        uriBuilder(PACKAGE_PREFIX, digest, key).appendPath("").build().toString()

    private fun response(type: String, bytes: ByteArray, headers: Map<String, String>) = WebResourceResponse(
        type, if (type.startsWith("text/")) "utf-8" else null, 200, "OK", headers, ByteArrayInputStream(bytes),
    )

    private fun mediaType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "css" -> "text/css"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val DOCUMENT_PREFIX = "document"
        const val PACKAGE_PREFIX = "package"
        const val RENDERER_PREFIX = "renderer-static"
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
