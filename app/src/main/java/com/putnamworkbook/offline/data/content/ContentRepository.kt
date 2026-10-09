package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import com.putnamworkbook.offline.domain.model.HintStage

data class LibraryCollection(
    val id: String,
    val title: String,
    val kind: String,
    val displayOrder: Int,
)

data class ProblemSetSummary(
    val key: ProblemSetKey,
    val title: String,
    val displayOrder: Int,
    val competitionYear: Int?,
)

data class TopicSummary(
    val id: String,
    val label: String,
    val displayOrder: Int,
)

data class ProblemSummary(
    val key: ProblemKey,
    val label: String,
    val ordinal: Int,
    val collectionTitle: String,
    val setTitle: String,
    val competitionYear: Int?,
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
)

interface ContentRepository {
    suspend fun contentVersion(): String = "unknown"

    suspend fun collections(): List<LibraryCollection>

    suspend fun sets(collectionId: String): List<ProblemSetSummary>

    suspend fun topics(): List<TopicSummary>

    suspend fun problems(setKey: ProblemSetKey): List<ProblemSummary>

    suspend fun problemsByTopic(topicId: String): List<ProblemSummary>

    suspend fun problem(key: ProblemKey): ProblemContent?
}
