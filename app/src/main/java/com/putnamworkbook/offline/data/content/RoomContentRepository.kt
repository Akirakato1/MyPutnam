package com.putnamworkbook.offline.data.content

import androidx.room.withTransaction
import com.putnamworkbook.offline.domain.model.ProblemKey
import org.json.JSONArray

class RoomContentRepository(
    private val database: ContentDatabase,
) : ContentRepository {
    private val dao = database.contentDao()

    override suspend fun years(): List<Int> = dao.years()

    override suspend fun problems(year: Int): List<ProblemSummary> =
        dao.problems(year).map { it.toSummary() }

    override suspend fun problem(key: ProblemKey): ProblemContent? = database.withTransaction {
        val problem = dao.problem(key.year, key.code) ?: return@withTransaction null
        ProblemContent(
            summary = problem.toSummary(),
            statementAssetPath = problem.statementAssetPath,
            statementSha256 = problem.statementSha256,
            hints = dao.hints(key.year, key.code).map { it.toDocument() },
            prerequisites = dao.prerequisites(key.year, key.code).map { it.toDocument() },
            solutionAssetPath = problem.solutionAssetPath,
            solutionSha256 = problem.solutionSha256,
            originalPdf = OriginalPdf(
                assetPath = problem.originalPdfPath,
                sha256 = problem.originalPdfSha256,
                pageIndex = problem.originalPdfPageIndex,
            ),
            attribution = problem.attributionJson.toAttribution(),
        )
    }
}

private fun ProblemEntity.toSummary() = ProblemSummary(ProblemKey(year, code), ordinal)

private fun HintEntity.toDocument() = ContentDocument(order, title, assetPath, sha256)

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
