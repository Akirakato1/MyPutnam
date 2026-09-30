package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.domain.model.ProblemKey

data class ProblemSummary(
    val key: ProblemKey,
    val ordinal: Int,
)

data class ContentDocument(
    val order: Int,
    val title: String,
    val assetPath: String,
    val sha256: String,
)

data class OriginalPdf(
    val assetPath: String,
    val sha256: String,
    val pageIndex: Int,
)

data class Attribution(
    val kind: String,
    val label: String,
    val url: String,
)

data class ProblemContent(
    val summary: ProblemSummary,
    val statementAssetPath: String,
    val statementSha256: String,
    val hints: List<ContentDocument>,
    val prerequisites: List<ContentDocument>,
    val solutionAssetPath: String,
    val solutionSha256: String,
    val originalPdf: OriginalPdf,
    val attribution: List<Attribution>,
)

interface ContentRepository {
    suspend fun contentVersion(): String = "unknown"

    suspend fun years(): List<Int>

    suspend fun problems(year: Int): List<ProblemSummary>

    suspend fun problem(key: ProblemKey): ProblemContent?
}
