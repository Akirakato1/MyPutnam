package com.putnamworkbook.offline.feature.reference

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.content.ui.MathContentWebView
import com.putnamworkbook.offline.content.ui.WebDocumentPositionStore
import com.putnamworkbook.offline.content.ui.webDocumentId
import com.putnamworkbook.offline.data.content.ReferenceArticleContent
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceKey

@Composable
fun ReferenceArticlePane(
    article: ReferenceArticleContent,
    installedReferenceKeys: Set<ReferenceKey>,
    onOpenRelatedReference: (ReferenceKey) -> Unit,
    onOpenRelatedProblem: (ProblemKey) -> Unit,
    onUnavailableContent: (String) -> Unit,
    onIntegrityError: (String) -> Unit,
    positionStore: WebDocumentPositionStore,
    modifier: Modifier = Modifier,
    articleBody: (@Composable (ReferenceArticleContent) -> Unit)? = null,
) {
    Column(modifier.testTag("reference-article")) {
        Text(article.summary.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth().testTag("reference-article-body")) {
            if (articleBody != null) {
                articleBody(article)
            } else {
                val documentId = article.document.webDocumentId()
                MathContentWebView(
                    resource = article.document,
                    initialScrollY = positionStore.position(documentId),
                    onScrollYChanged = { scrollY -> positionStore.update(documentId, scrollY) },
                    onIntegrityError = onIntegrityError,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        HorizontalDivider()
        FlowRow(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            article.relatedReferences.forEach { key ->
                val installed = key in installedReferenceKeys
                TextButton(
                    onClick = { if (installed) onOpenRelatedReference(key) else onUnavailableContent(key.canonical) },
                    modifier = Modifier.testTag("reference-related-${key.canonical}"),
                ) { Text(if (installed) "Open ${key.articleId}" else "Install ${key.articleId}") }
            }
            article.relatedProblems.forEach { key ->
                TextButton(
                    onClick = { onOpenRelatedProblem(key) },
                    modifier = Modifier.testTag("reference-related-${key.canonical}"),
                ) { Text("Open ${key.canonical}") }
            }
        }
        article.attribution.forEach { source ->
            Text(source.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }
    }
}
