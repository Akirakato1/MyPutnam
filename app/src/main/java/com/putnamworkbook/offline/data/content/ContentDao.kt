package com.putnamworkbook.offline.data.content

import androidx.room.Dao
import androidx.room.Query

@Dao
interface ContentDao {
    @Query("SELECT * FROM collection ORDER BY display_order, id")
    suspend fun collections(): List<CollectionEntity>

    @Query(
        "SELECT * FROM problem_set WHERE collection_id = :collectionId " +
            "ORDER BY display_order, set_id",
    )
    suspend fun sets(collectionId: String): List<ProblemSetEntity>

    @Query("SELECT * FROM topic ORDER BY display_order, id")
    suspend fun topics(): List<TopicEntity>

    @Query(
        "SELECT p.*, c.title AS collection_title, s.title AS set_title, " +
            "s.competition_year AS competition_year " +
            "FROM problem p " +
            "JOIN collection c ON c.id = p.collection_id " +
            "JOIN problem_set s ON s.collection_id = p.collection_id AND s.set_id = p.set_id " +
            "WHERE p.collection_id = :collectionId AND p.set_id = :setId " +
            "ORDER BY p.ordinal, p.problem_id",
    )
    suspend fun problems(collectionId: String, setId: String): List<ProblemSummaryRow>

    @Query(
        "SELECT p.*, c.title AS collection_title, s.title AS set_title, " +
            "s.competition_year AS competition_year " +
            "FROM problem p " +
            "JOIN collection c ON c.id = p.collection_id " +
            "JOIN problem_set s ON s.collection_id = p.collection_id AND s.set_id = p.set_id " +
            "WHERE EXISTS (SELECT 1 FROM problem_topic pt " +
            "WHERE pt.problem_key = p.key AND pt.topic_id = :topicId) " +
            "ORDER BY c.display_order, c.id, s.display_order, s.set_id, p.ordinal, p.problem_id",
    )
    suspend fun problemsByTopic(topicId: String): List<ProblemSummaryRow>

    @Query(
        "SELECT p.*, c.title AS collection_title, s.title AS set_title, " +
            "s.competition_year AS competition_year " +
            "FROM problem p " +
            "JOIN collection c ON c.id = p.collection_id " +
            "JOIN problem_set s ON s.collection_id = p.collection_id AND s.set_id = p.set_id " +
            "WHERE p.key = :key",
    )
    suspend fun problemSummary(key: String): ProblemSummaryRow?

    @Query("SELECT * FROM problem WHERE `key` = :key")
    suspend fun problemEntity(key: String): ProblemEntity?

    @Query("SELECT * FROM hint WHERE problem_key = :key ORDER BY hint_order")
    suspend fun hints(key: String): List<HintEntity>

    @Query("SELECT * FROM prerequisite WHERE problem_key = :key ORDER BY prerequisite_order")
    suspend fun prerequisites(key: String): List<PrerequisiteEntity>

    @Query("SELECT value FROM content_meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Query("SELECT * FROM content_meta ORDER BY `key`")
    suspend fun metadata(): List<ContentMetaEntity>

    @Query("SELECT COUNT(*) FROM collection")
    suspend fun collectionCount(): Int

    @Query("SELECT COUNT(*) FROM problem_set")
    suspend fun setCount(): Int

    @Query("SELECT COUNT(*) FROM topic")
    suspend fun topicCount(): Int

    @Query("SELECT COUNT(*) FROM problem")
    suspend fun problemCount(): Int

    @Query("SELECT COUNT(*) FROM problem_topic")
    suspend fun problemTopicCount(): Int

    @Query("SELECT COUNT(*) FROM hint")
    suspend fun hintCount(): Int

    @Query("SELECT COUNT(*) FROM prerequisite")
    suspend fun prerequisiteCount(): Int

    @Query("SELECT COUNT(*) FROM problem WHERE `key` != collection_id || '/' || set_id || '/' || problem_id")
    suspend fun nonCanonicalProblemCount(): Int

    @Query("SELECT `key` FROM problem ORDER BY `key`")
    suspend fun problemKeys(): List<String>

    @Query(
        "SELECT COUNT(*) FROM problem p WHERE " +
            "(SELECT COUNT(*) FROM hint h WHERE h.problem_key = p.key) != 4 OR " +
            "(SELECT COUNT(DISTINCT h.stage) FROM hint h WHERE h.problem_key = p.key) != 4 OR " +
            "(SELECT COUNT(*) FROM prerequisite r WHERE r.problem_key = p.key) < 1 OR " +
            "(SELECT COUNT(*) FROM problem_topic pt WHERE pt.problem_key = p.key) < 1",
    )
    suspend fun incompleteProblemCount(): Int

    @Query(
        "SELECT COUNT(*) FROM hint WHERE hint_order NOT BETWEEN 1 AND 4 OR stage != " +
            "CASE hint_order WHEN 1 THEN 'nudge' WHEN 2 THEN 'strategy' " +
            "WHEN 3 THEN 'knowledge' WHEN 4 THEN 'outline' END",
    )
    suspend fun invalidHintProgressionCount(): Int
}

data class ProblemSummaryRow(
    val key: String,
    @androidx.room.ColumnInfo(name = "collection_id") val collectionId: String,
    @androidx.room.ColumnInfo(name = "set_id") val setId: String,
    @androidx.room.ColumnInfo(name = "problem_id") val problemId: String,
    val label: String,
    val ordinal: Int,
    @androidx.room.ColumnInfo(name = "collection_title") val collectionTitle: String,
    @androidx.room.ColumnInfo(name = "set_title") val setTitle: String,
    @androidx.room.ColumnInfo(name = "competition_year") val competitionYear: Int?,
    @androidx.room.ColumnInfo(name = "statement_asset_path") val statementAssetPath: String,
    @androidx.room.ColumnInfo(name = "statement_sha256") val statementSha256: String,
    @androidx.room.ColumnInfo(name = "solution_asset_path") val solutionAssetPath: String,
    @androidx.room.ColumnInfo(name = "solution_sha256") val solutionSha256: String,
    @androidx.room.ColumnInfo(name = "attribution_json") val attributionJson: String,
)
