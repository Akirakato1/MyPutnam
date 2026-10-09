package com.putnamworkbook.offline.data.content

import androidx.room.withTransaction
import com.putnamworkbook.offline.domain.model.HintStage
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import org.json.JSONArray

class RoomContentRepository(
    private val database: ContentDatabase,
) : ContentRepository {
    private val dao = database.contentDao()

    override suspend fun contentVersion(): String = dao.meta("content_version") ?: "unknown"

    override suspend fun collections(): List<LibraryCollection> =
        dao.collections().map { it.toSummary() }

    override suspend fun sets(collectionId: String): List<ProblemSetSummary> =
        dao.sets(collectionId).map { it.toSummary() }

    override suspend fun topics(): List<TopicSummary> = dao.topics().map { it.toSummary() }

    override suspend fun problems(setKey: ProblemSetKey): List<ProblemSummary> =
        dao.problems(setKey.collectionId, setKey.setId).map { it.toSummary() }

    override suspend fun problemsByTopic(topicId: String): List<ProblemSummary> =
        dao.problemsByTopic(topicId).map { it.toSummary() }

    override suspend fun problem(key: ProblemKey): ProblemContent? = database.withTransaction {
        val problem = dao.problemEntity(key.canonical) ?: return@withTransaction null
        val summary = dao.problemSummary(key.canonical) ?: return@withTransaction null
        ProblemContent(
            summary = summary.toSummary(),
            statementAssetPath = problem.statementAssetPath,
            statementSha256 = problem.statementSha256,
            hints = dao.hints(key.canonical).map { it.toHint() },
            prerequisites = dao.prerequisites(key.canonical).map { it.toDocument() },
            solutionAssetPath = problem.solutionAssetPath,
            solutionSha256 = problem.solutionSha256,
            attribution = problem.attributionJson.toAttribution(),
        )
    }
}

private fun CollectionEntity.toSummary() = LibraryCollection(id, title, kind, displayOrder)

private fun ProblemSetEntity.toSummary() = ProblemSetSummary(
    key = ProblemSetKey(collectionId, setId),
    title = title,
    displayOrder = displayOrder,
    competitionYear = competitionYear,
)

private fun TopicEntity.toSummary() = TopicSummary(id, label, displayOrder)

private fun ProblemSummaryRow.toSummary() = ProblemSummary(
    key = ProblemKey.parse(key),
    label = label,
    ordinal = ordinal,
    collectionTitle = collectionTitle,
    setTitle = setTitle,
    competitionYear = competitionYear,
)

private fun HintEntity.toHint() = HintContent(
    order = order,
    stage = HintStage.fromStorageValue(stage),
    title = title,
    assetPath = assetPath,
    sha256 = sha256,
)

private fun PrerequisiteEntity.toDocument() = ContentDocument(order, title, assetPath, sha256)

private fun String.toAttribution(): List<Attribution> {
    val array = JSONArray(this)
    return List(array.length()) { index ->
        array.getJSONObject(index).let { source ->
            Attribution(
                kind = source.getString("kind"),
                label = source.getString("label"),
                url = source.getString("url"),
            )
        }
    }
}
