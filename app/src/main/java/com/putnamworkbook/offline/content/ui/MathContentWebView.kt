package com.putnamworkbook.offline.content.ui

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun MathContentWebView(
    assetPath: String,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestIntegrityError = rememberUpdatedState(onIntegrityError)
    val client = remember(context.applicationContext) {
        LocalContentClient(
            assetContext = context.applicationContext,
            onIntegrityError = { message -> latestIntegrityError.value(message) },
        )
    }

    AndroidView(
        factory = { context ->
            WebView(context).also { webView ->
                client.configure(webView)
            }
        },
        update = { webView ->
            if (webView.getTag(CONTENT_PATH_TAG) != assetPath) {
                webView.setTag(CONTENT_PATH_TAG, assetPath)
                client.loadAsset(webView, assetPath)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
        modifier = modifier,
    )
}

private const val CONTENT_PATH_TAG = 0x5055544e
