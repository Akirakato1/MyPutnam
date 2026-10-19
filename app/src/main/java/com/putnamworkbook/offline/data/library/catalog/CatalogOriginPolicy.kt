package com.putnamworkbook.offline.data.library.catalog

import java.net.URI
import java.net.URL

class CatalogOriginPolicy(
    origin: String = PRODUCTION_ORIGIN,
) {
    private val base = URI(origin).also {
        require(it.scheme == "https" && it.host != null && it.userInfo == null && it.query == null && it.fragment == null)
        require(it.path.endsWith('/'))
    }
    private val contentRoot = base.resolve("content/v1/")
    val catalogUrl: URL = contentRoot.resolve("catalog.json").toURL()

    fun resolvePackage(relativePath: String): URL {
        require(relativePath.isNotBlank()) { "Package path cannot be blank" }
        require(!relativePath.contains('\\') && !relativePath.startsWith('/') && !relativePath.contains(':')) { "Package path must be relative" }
        require(relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Package path is unsafe" }
        val resolved = contentRoot.resolve(relativePath)
        require(
            resolved.scheme == contentRoot.scheme &&
                resolved.host == contentRoot.host &&
                resolved.port == contentRoot.port &&
                resolved.userInfo == null &&
                resolved.query == null &&
                resolved.fragment == null &&
                resolved.path.startsWith(contentRoot.path),
        ) { "Package path escaped the configured catalog origin" }
        return resolved.toURL()
    }

    companion object {
        const val PRODUCTION_ORIGIN = "https://akirakato1.github.io/MyPutnam/"
    }
}
