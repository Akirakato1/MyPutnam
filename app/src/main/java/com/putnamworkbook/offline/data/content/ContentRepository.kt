package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import com.putnamworkbook.offline.domain.model.HintStage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

data class ContentResourceRef(
    val contentKey: String,
    val packageSha256: String,
    val memberPath: String,
    val memberSha256: String,
) {
    companion object {
        fun legacy(assetPath: String, sha256: String) = ContentResourceRef(
            contentKey = "legacy/asset/path",
            packageSha256 = "0".repeat(64),
            memberPath = assetPath,
            memberSha256 = sha256,
        )
    }
}

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
    val resource: ContentResourceRef,
) {
    constructor(order: Int, title: String, assetPath: String, sha256: String) :
        this(order, title, ContentResourceRef.legacy(assetPath, sha256))
    val assetPath: String get() = resource.memberPath
    val sha256: String get() = resource.memberSha256
}

data class HintContent(
    val order: Int,
    val stage: HintStage,
    val title: String,
    val resource: ContentResourceRef,
) {
    constructor(order: Int, stage: HintStage, title: String, assetPath: String, sha256: String) :
        this(order, stage, title, ContentResourceRef.legacy(assetPath, sha256))
    val assetPath: String get() = resource.memberPath
    val sha256: String get() = resource.memberSha256
}

data class Attribution(
    val kind: String,
    val label: String,
    val url: String,
)

data class ProblemContent(
    val summary: ProblemSummary,
    val statement: ContentResourceRef,
    val hints: List<HintContent>,
    val prerequisites: List<ContentDocument>,
    val solution: ContentResourceRef,
    val attribution: List<Attribution>,
) {
    constructor(
        summary: ProblemSummary,
        statementAssetPath: String,
        statementSha256: String,
        hints: List<HintContent>,
        prerequisites: List<ContentDocument>,
        solutionAssetPath: String,
        solutionSha256: String,
        attribution: List<Attribution>,
    ) : this(
        summary,
        ContentResourceRef.legacy(statementAssetPath, statementSha256),
        hints,
        prerequisites,
        ContentResourceRef.legacy(solutionAssetPath, solutionSha256),
        attribution,
    )
    val statementAssetPath: String get() = statement.memberPath
    val statementSha256: String get() = statement.memberSha256
    val solutionAssetPath: String get() = solution.memberPath
    val solutionSha256: String get() = solution.memberSha256
}

interface ContentRepository {
    fun observeInstalledRevision(): Flow<Long> = flowOf(0L)

    suspend fun contentVersion(): String = "unknown"

    suspend fun collections(): List<LibraryCollection>

    suspend fun sets(collectionId: String): List<ProblemSetSummary>

    suspend fun topics(): List<TopicSummary>

    suspend fun problems(setKey: ProblemSetKey): List<ProblemSummary>

    suspend fun problemsByTopic(topicId: String): List<ProblemSummary>

    suspend fun problem(key: ProblemKey): ProblemContent?
}
