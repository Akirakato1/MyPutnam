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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun MathContentWebView(
    assetPath: String,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestIntegrityError = rememberUpdatedState(onIntegrityError)
    var readyAssetPath by remember { mutableStateOf<String?>(null) }
    var renderGeneration by remember { mutableIntStateOf(0) }
    val client = remember(context.applicationContext) {
        LocalContentClient(
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
                }
            },
        )
    }

    key(assetPath, renderGeneration) {
        AndroidView(
            factory = { context ->
                WebView(context).also { webView ->
                    client.configure(webView)
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
                webView.stopLoading()
                webView.destroy()
            },
            modifier = if (readyAssetPath == assetPath) modifier.testTag("math-content-ready") else modifier,
        )
    }
}

private const val CONTENT_PATH_TAG = 0x5055544e
