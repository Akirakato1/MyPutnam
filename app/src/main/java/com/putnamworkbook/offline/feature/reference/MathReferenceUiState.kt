package com.putnamworkbook.offline.feature.reference

import com.putnamworkbook.offline.data.content.ReferenceArticleContent
import com.putnamworkbook.offline.data.content.ReferenceArticleSummary
import com.putnamworkbook.offline.data.content.ReferenceCategorySummary
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceKey

sealed interface ReferenceLocation {
    data object Categories : ReferenceLocation
    data class Category(val id: String) : ReferenceLocation
    data class Results(val query: String) : ReferenceLocation
    data class Article(val key: ReferenceKey) : ReferenceLocation
}

data class MathReferenceUiState(
    val location: ReferenceLocation = ReferenceLocation.Categories,
    val categories: List<ReferenceCategorySummary> = emptyList(),
    val articles: List<ReferenceArticleSummary> = emptyList(),
    val query: String = "",
    val searchResults: List<ReferenceSearchResult> = emptyList(),
    val currentArticle: ReferenceArticleContent? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val canGoBack: Boolean = false,
    val focusedLibraryRequest: String? = null,
    val relatedProblemRequest: ProblemKey? = null,
)
