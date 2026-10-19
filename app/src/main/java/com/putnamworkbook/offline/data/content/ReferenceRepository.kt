package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceKey
import kotlinx.coroutines.flow.Flow

data class ReferenceCategorySummary(
    val id: String,
    val title: String,
    val displayOrder: Int,
)

data class ReferenceArticleSummary(
    val key: ReferenceKey,
    val title: String,
    val aliases: List<String>,
    val keywords: List<String>,
    val categoryTitle: String,
    val categoryDisplayOrder: Int,
    val displayOrder: Int,
)

data class ReferenceArticleContent(
    val summary: ReferenceArticleSummary,
    val document: ContentResourceRef,
    val relatedReferences: List<ReferenceKey>,
    val relatedProblems: List<ProblemKey>,
    val attribution: List<Attribution>,
)

interface ReferenceRepository {
    fun observeCategories(): Flow<List<ReferenceCategorySummary>>
    fun observeInstalledArticles(): Flow<List<ReferenceArticleSummary>>
    suspend fun article(key: ReferenceKey): ReferenceArticleContent?
}
