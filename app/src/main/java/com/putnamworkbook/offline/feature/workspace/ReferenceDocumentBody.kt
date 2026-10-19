package com.putnamworkbook.offline.feature.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.putnamworkbook.offline.content.ui.MathContentWebView
import com.putnamworkbook.offline.content.ui.WebDocumentPositionStore
import com.putnamworkbook.offline.content.ui.webDocumentId
import com.putnamworkbook.offline.data.content.ContentResourceRef

@Composable
fun ReferenceDocumentBody(
    resource: ContentResourceRef,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
    positionStore: WebDocumentPositionStore? = null,
) {
    val positions = positionStore ?: androidx.compose.runtime.remember { WebDocumentPositionStore() }
    val documentId = resource.webDocumentId()
    Box(modifier.testTag("reference-document-body")) {
        key(resource) {
            if (resource.contentKey == "legacy/asset/path") {
                MathContentWebView(resource.memberPath, onIntegrityError, Modifier.fillMaxSize())
            } else {
                MathContentWebView(
                    resource = resource,
                    initialScrollY = positions.position(documentId),
                    onScrollYChanged = { positions.update(documentId, it) },
                    onIntegrityError = onIntegrityError,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun ReferenceDocumentBody(
    assetPath: String,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.testTag("reference-document-body")) {
        key(assetPath) {
            MathContentWebView(
                assetPath = assetPath,
                onIntegrityError = onIntegrityError,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
