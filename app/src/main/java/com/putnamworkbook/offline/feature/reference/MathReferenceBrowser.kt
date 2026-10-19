package com.putnamworkbook.offline.feature.reference

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.data.content.ReferenceArticleContent
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceKey

@Composable
fun MathReferenceBrowser(
    state: MathReferenceUiState,
    onQueryChanged: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenArticle: (ReferenceKey) -> Unit,
    onOpenRelatedReference: (ReferenceKey) -> Unit,
    onOpenRelatedProblem: (ProblemKey) -> Unit,
    onUnavailableContent: (String) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onIntegrityError: (String) -> Unit,
    modifier: Modifier = Modifier,
    articleBody: (@Composable (ReferenceArticleContent) -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().testTag("math-reference-browser")) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.canGoBack || state.location is ReferenceLocation.Article) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("math-reference-back")) { Text("Back") }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                label = { Text("Search formulas and theorems") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("math-reference-search"),
            )
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> ErrorPane(state.error, onRetry, onUnavailableContent, state.focusedLibraryRequest)
            state.location is ReferenceLocation.Article && state.currentArticle != null -> ReferenceArticlePane(
                article = state.currentArticle,
                installedReferenceKeys = state.articles.mapTo(hashSetOf()) { it.key },
                onOpenRelatedReference = onOpenRelatedReference,
                onOpenRelatedProblem = onOpenRelatedProblem,
                onUnavailableContent = onUnavailableContent,
                onIntegrityError = onIntegrityError,
                modifier = Modifier.weight(1f),
                articleBody = articleBody ?: { article -> MathContentArticleBody(article, onIntegrityError) },
            )
            state.location is ReferenceLocation.Category -> ArticleList(
                state.articles.filter { it.key.categoryId == state.location.id }, onOpenArticle,
            )
            state.location is ReferenceLocation.Results -> ArticleList(
                state.searchResults.map { it.article }, onOpenArticle, emptyMessage = "No matching references",
            )
            else -> CategoryList(state, onOpenCategory)
        }
    }
}

@Composable
private fun MathContentArticleBody(article: ReferenceArticleContent, onIntegrityError: (String) -> Unit) {
    com.putnamworkbook.offline.content.ui.MathContentWebView(
        article.document,
        onIntegrityError,
        Modifier.fillMaxSize(),
    )
}

@Composable
private fun CategoryList(state: MathReferenceUiState, onOpenCategory: (String) -> Unit) {
    if (state.categories.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No installed math references") }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("math-reference-categories")) {
        items(state.categories, key = { it.id }) { category ->
            ListItem(
                headlineContent = { Text(category.title) },
                supportingContent = { Text("${state.articles.count { it.key.categoryId == category.id }} articles") },
                modifier = Modifier
                    .clickable { onOpenCategory(category.id) }
                    .testTag("math-reference-category-${category.id}"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ArticleList(
    articles: List<com.putnamworkbook.offline.data.content.ReferenceArticleSummary>,
    onOpenArticle: (ReferenceKey) -> Unit,
    emptyMessage: String = "No installed references in this category",
) {
    if (articles.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emptyMessage) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("math-reference-articles")) {
        items(articles, key = { it.key.canonical }) { article ->
            ListItem(
                headlineContent = { Text(article.title) },
                supportingContent = { Text(article.aliases.joinToString()) },
                modifier = Modifier
                    .clickable { onOpenArticle(article.key) }
                    .testTag("math-reference-article-${article.key.canonical}"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ErrorPane(error: String, onRetry: () -> Unit, onUnavailableContent: (String) -> Unit, key: String?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("math-reference-error"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(error, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            key?.let { OutlinedButton(onClick = { onUnavailableContent(it) }) { Text("Manage download") } }
        }
    }
}
