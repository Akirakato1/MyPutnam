package com.putnamworkbook.offline.content.ui

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.putnamworkbook.offline.data.content.ContentResourceRef
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

fun interface LocalContentClientFactory {
    fun create(
        assetContext: android.content.Context,
        onIntegrityError: (String) -> Unit,
        onRendererGone: () -> Unit,
        onPageReady: (WebView) -> Unit,
    ): LocalContentClient
}

private object DefaultLocalContentClientFactory : LocalContentClientFactory {
    override fun create(
        assetContext: android.content.Context,
        onIntegrityError: (String) -> Unit,
        onRendererGone: () -> Unit,
        onPageReady: (WebView) -> Unit,
    ) = LocalContentClient(
        assetContext = assetContext,
        onIntegrityError = onIntegrityError,
        onRendererGone = onRendererGone,
        onPageReady = onPageReady,
    )
}

val LocalContentClientFactoryProvider = staticCompositionLocalOf<LocalContentClientFactory> {
    DefaultLocalContentClientFactory
}

val ContentResourceResolverProvider = staticCompositionLocalOf<ContentResourceResolver?> { null }

@Composable
fun MathContentWebView(
    resource: ContentResourceRef,
    initialScrollY: Int,
    onScrollYChanged: (Int) -> Unit,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolver = checkNotNull(ContentResourceResolverProvider.current) { "ContentResourceResolver is not provided" }
    val context = LocalContext.current
    val clientFactory = LocalContentClientFactoryProvider.current
    val latestIntegrityError = rememberUpdatedState(onIntegrityError)
    val latestInitialScrollY = rememberUpdatedState(initialScrollY.coerceAtLeast(0))
    val latestScrollChanged = rememberUpdatedState(onScrollYChanged)
    var renderGeneration by remember { mutableIntStateOf(0) }
    val client = remember(context.applicationContext, clientFactory, resolver) {
        clientFactory.create(
            context.applicationContext,
            { latestIntegrityError.value(it) },
            { renderGeneration += 1 },
            { webView -> webView.post { webView.scrollTo(0, latestInitialScrollY.value) } },
        ).also { it.attachResolver(resolver) }
    }
    key(resource, renderGeneration) {
        AndroidView(
            factory = {
                WebView(it).also { webView ->
                    client.configure(webView)
                    webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                        latestScrollChanged.value(scrollY.coerceAtLeast(0))
                    }
                }
            },
            update = { webView ->
                if (webView.getTag(CONTENT_PATH_TAG) != resource) {
                    webView.setTag(CONTENT_PATH_TAG, resource)
                    client.loadResource(webView, resource)
                }
            },
            onRelease = {
                latestScrollChanged.value(it.scrollY.coerceAtLeast(0))
                it.stopLoading()
                it.destroy()
            },
            modifier = modifier,
        )
    }
}

@Composable
fun MathContentWebView(
    assetPath: String,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialScrollY: Int = 0,
    onScrollYChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val clientFactory = LocalContentClientFactoryProvider.current
    val latestIntegrityError = rememberUpdatedState(onIntegrityError)
    val latestInitialScrollY = rememberUpdatedState(initialScrollY.coerceAtLeast(0))
    val latestScrollChanged = rememberUpdatedState(onScrollYChanged)
    var readyAssetPath by remember { mutableStateOf<String?>(null) }
    var renderGeneration by remember { mutableIntStateOf(0) }
    val client = remember(context.applicationContext, clientFactory) {
        clientFactory.create(
            assetContext = context.applicationContext,
            onIntegrityError = { message -> latestIntegrityError.value(message) },
            onRendererGone = {
                readyAssetPath = null
                renderGeneration += 1
            },
            onPageReady = { webView ->
                val loadedPath = webView.getTag(CONTENT_PATH_TAG) as? String
                val expectedUrl = loadedPath?.let { path ->
                    LocalContentClient.ASSET_BASE_URL + android.net.Uri.encode(path, "/")
                }
                if (loadedPath != null && webView.url == expectedUrl) {
                    readyAssetPath = loadedPath
                    webView.post { webView.scrollTo(0, latestInitialScrollY.value) }
                }
            },
        )
    }

    key(assetPath, renderGeneration) {
        AndroidView(
            factory = { context ->
                WebView(context).also { webView ->
                    client.configure(webView)
                    webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                        latestScrollChanged.value(scrollY.coerceAtLeast(0))
                    }
                }
            },
            update = { webView ->
                if (webView.getTag(CONTENT_PATH_TAG) != assetPath) {
                    readyAssetPath = null
                    webView.setTag(CONTENT_PATH_TAG, assetPath)
                    client.loadAsset(webView, assetPath)
                }
            },
            onRelease = { webView ->
                latestScrollChanged.value(webView.scrollY.coerceAtLeast(0))
                webView.stopLoading()
                webView.destroy()
            },
            modifier = if (readyAssetPath == assetPath) modifier.testTag("math-content-ready") else modifier,
        )
    }
}

private const val CONTENT_PATH_TAG = 0x5055544e
