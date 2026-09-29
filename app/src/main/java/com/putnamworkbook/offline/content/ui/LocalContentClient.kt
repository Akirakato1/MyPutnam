package com.putnamworkbook.offline.content.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

class LocalContentClient(
    private val assetContext: Context,
    private val onIntegrityError: (String) -> Unit = {},
    private val onPageReady: (WebView) -> Unit = {},
    private val failOnSubresourceError: Boolean = false,
) : WebViewClient() {
    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(ASSET_HOST)
        .addPathHandler(ASSET_PATH, WebViewAssetLoader.AssetsPathHandler(assetContext))
        .build()

    @MainThread
    fun configure(webView: WebView) {
        with(webView.settings) {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            blockNetworkLoads = true
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.webViewClient = this
    }

    @MainThread
    fun loadAsset(webView: WebView, assetPath: String): Result<Unit> = runCatching {
        require(isSafeAssetPath(assetPath)) { "Unsafe bundled asset path: $assetPath" }
        try {
            assetContext.assets.open(assetPath).use { input -> input.read() }
        } catch (error: FileNotFoundException) {
            throw IllegalStateException("Bundled content is missing: $assetPath", error)
        }
        webView.loadUrl(ASSET_BASE_URL + Uri.encode(assetPath, "/"))
    }.onFailure { error ->
        onIntegrityError(error.message ?: "Bundled content could not be opened: $assetPath")
    }

    @MainThread
    fun loadAssetData(webView: WebView, assetPath: String): Result<Unit> = runCatching {
        require(isSafeAssetPath(assetPath)) { "Unsafe bundled asset path: $assetPath" }
        val html = try {
            assetContext.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: FileNotFoundException) {
            throw IllegalStateException("Bundled content is missing: $assetPath", error)
        }
        val parent = assetPath.substringBeforeLast('/', missingDelimiterValue = "")
        val baseUrl = ASSET_BASE_URL + if (parent.isEmpty()) "" else Uri.encode("$parent/", "/")
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
    }.onFailure { error ->
        onIntegrityError(error.message ?: "Bundled content could not be opened: $assetPath")
    }

    internal fun isNavigationAllowed(uri: Uri): Boolean =
        uri.scheme == "https" &&
            uri.host == ASSET_HOST &&
            uri.port == -1 &&
            uri.path?.startsWith(ASSET_PATH) == true &&
            !containsTraversal(uri)

    internal fun interceptForTest(uri: Uri): WebResourceResponse? {
        if (!isNavigationAllowed(uri)) return errorResponse(403, "Forbidden")
        return assetLoader.shouldInterceptRequest(uri) ?: errorResponse(404, "Not Found")
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
        interceptForTest(request.url)

    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? =
        url?.let(Uri::parse)?.let(::interceptForTest)

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
        !isNavigationAllowed(request.url)

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
        url == null || !isNavigationAllowed(Uri.parse(url))

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null && !isNavigationAllowed(Uri.parse(url))) {
            view?.stopLoading()
            onIntegrityError("Blocked navigation outside bundled content")
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if ((request.isForMainFrame || failOnSubresourceError) && isNavigationAllowed(request.url)) {
            onIntegrityError("Bundled content could not be read: ${request.url.lastPathSegment.orEmpty()}")
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (
            (request.isForMainFrame || failOnSubresourceError) &&
            isNavigationAllowed(request.url) &&
            errorResponse.statusCode >= 400
        ) {
            onIntegrityError("Bundled content failed its integrity check: ${request.url.lastPathSegment.orEmpty()}")
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (view != null && url != null && isNavigationAllowed(Uri.parse(url))) {
            onPageReady(view)
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onIntegrityError("The offline math renderer stopped unexpectedly")
        view?.destroy()
        return true
    }

    private fun containsTraversal(uri: Uri): Boolean {
        val decoded = Uri.decode(uri.encodedPath.orEmpty())
        return decoded.split('/').any { segment -> segment == ".." || segment == "." }
    }

    private fun isSafeAssetPath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.contains('\\') &&
            path.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." }

    private fun errorResponse(status: Int, reason: String) = WebResourceResponse(
        "text/plain",
        "utf-8",
        status,
        reason,
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(reason.toByteArray()),
    )

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ASSET_PATH = "/assets/"
        const val ASSET_BASE_URL = "https://$ASSET_HOST$ASSET_PATH"
    }
}
