package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.HintStage

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

data class HintContent(
    val order: Int,
    val stage: HintStage,
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
    val hints: List<HintContent>,
    val prerequisites: List<ContentDocument>,
    val solutionAssetPath: String,
    val solutionSha256: String,
    val attribution: List<Attribution>,
) {
    @Deprecated("Original papers are no longer packaged; retained until the PDF UI is removed")
    val originalPdf: OriginalPdf? get() = null
}

interface ContentRepository {
    suspend fun contentVersion(): String = "unknown"

    suspend fun years(): List<Int>

    suspend fun problems(year: Int): List<ProblemSummary>

    suspend fun problem(key: ProblemKey): ProblemContent?
}
