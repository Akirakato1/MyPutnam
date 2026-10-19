package com.putnamworkbook.offline.feature.reference

import com.putnamworkbook.offline.data.content.ReferenceArticleSummary
import java.util.Locale

enum class ReferenceMatchRank {
    EXACT_TITLE,
    EXACT_ALIAS,
    TITLE_PREFIX,
    ALIAS_PREFIX,
    METADATA,
    ALL,
}

data class ReferenceSearchResult(
    val article: ReferenceArticleSummary,
    val rank: ReferenceMatchRank,
)

fun searchReferences(
    query: String,
    articles: List<ReferenceArticleSummary>,
): List<ReferenceSearchResult> {
    val normalizedQuery = normalizeReferenceSearchTerm(query)
    return articles.mapNotNull { article ->
        val title = normalizeReferenceSearchTerm(article.title)
        val aliases = article.aliases.map(::normalizeReferenceSearchTerm)
        val rank = when {
            normalizedQuery.isEmpty() -> ReferenceMatchRank.ALL
            title == normalizedQuery -> ReferenceMatchRank.EXACT_TITLE
            aliases.any { it == normalizedQuery } -> ReferenceMatchRank.EXACT_ALIAS
            title.startsWith(normalizedQuery) -> ReferenceMatchRank.TITLE_PREFIX
            aliases.any { it.startsWith(normalizedQuery) } -> ReferenceMatchRank.ALIAS_PREFIX
            article.keywords.any { normalizeReferenceSearchTerm(it).contains(normalizedQuery) } ||
                normalizeReferenceSearchTerm(article.categoryTitle).contains(normalizedQuery) -> ReferenceMatchRank.METADATA
            else -> null
        }
        rank?.let { ReferenceSearchResult(article, it) }
    }.sortedWith(
        compareBy<ReferenceSearchResult> { it.rank.ordinal }
            .thenBy { it.article.categoryDisplayOrder }
            .thenBy { it.article.displayOrder }
            .thenBy { normalizeReferenceSearchTerm(it.article.title) }
            .thenBy { it.article.key.canonical },
    )
}

internal fun normalizeReferenceSearchTerm(value: String): String =
    value.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ").lowercase(Locale.ROOT)
