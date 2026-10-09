package com.putnamworkbook.offline.data.content

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "collection",
    indices = [Index(value = ["display_order"], unique = true)],
)
data class CollectionEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val kind: String,
    @ColumnInfo(name = "display_order") val displayOrder: Int,
)

@Entity(
    tableName = "problem_set",
    primaryKeys = ["collection_id", "set_id"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["collection_id"]),
        Index(value = ["collection_id", "display_order"], unique = true),
    ],
)
data class ProblemSetEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "set_id") val setId: String,
    val title: String,
    @ColumnInfo(name = "display_order") val displayOrder: Int,
    @ColumnInfo(name = "competition_year") val competitionYear: Int?,
)

@Entity(
    tableName = "topic",
    indices = [Index(value = ["display_order"], unique = true)],
)
data class TopicEntity(
    @androidx.room.PrimaryKey val id: String,
    val label: String,
    @ColumnInfo(name = "display_order") val displayOrder: Int,
)

@Entity(
    tableName = "problem",
    foreignKeys = [
        ForeignKey(
            entity = ProblemSetEntity::class,
            parentColumns = ["collection_id", "set_id"],
            childColumns = ["collection_id", "set_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["collection_id", "set_id"]),
        Index(value = ["collection_id", "set_id", "problem_id"], unique = true),
        Index(value = ["collection_id", "set_id", "ordinal"], unique = true),
    ],
)
data class ProblemEntity(
    @androidx.room.PrimaryKey val key: String,
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "set_id") val setId: String,
    @ColumnInfo(name = "problem_id") val problemId: String,
    val label: String,
    val ordinal: Int,
    @ColumnInfo(name = "statement_asset_path") val statementAssetPath: String,
    @ColumnInfo(name = "statement_sha256") val statementSha256: String,
    @ColumnInfo(name = "solution_asset_path") val solutionAssetPath: String,
    @ColumnInfo(name = "solution_sha256") val solutionSha256: String,
    @ColumnInfo(name = "attribution_json") val attributionJson: String,
)

@Entity(
    tableName = "problem_topic",
    primaryKeys = ["problem_key", "topic_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemEntity::class,
            parentColumns = ["key"],
            childColumns = ["problem_key"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TopicEntity::class,
            parentColumns = ["id"],
            childColumns = ["topic_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["topic_id"])],
)
data class ProblemTopicEntity(
    @ColumnInfo(name = "problem_key") val problemKey: String,
    @ColumnInfo(name = "topic_id") val topicId: String,
)

@Entity(
    tableName = "hint",
    primaryKeys = ["problem_key", "hint_order"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemEntity::class,
            parentColumns = ["key"],
            childColumns = ["problem_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["problem_key"])],
)
data class HintEntity(
    @ColumnInfo(name = "problem_key") val problemKey: String,
    @ColumnInfo(name = "hint_order") val order: Int,
    val stage: String,
    val title: String,
    @ColumnInfo(name = "asset_path") val assetPath: String,
    val sha256: String,
)

@Entity(
    tableName = "prerequisite",
    primaryKeys = ["problem_key", "prerequisite_order"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemEntity::class,
            parentColumns = ["key"],
            childColumns = ["problem_key"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["problem_key"])],
)
data class PrerequisiteEntity(
    @ColumnInfo(name = "problem_key") val problemKey: String,
    @ColumnInfo(name = "prerequisite_order") val order: Int,
    val title: String,
    @ColumnInfo(name = "asset_path") val assetPath: String,
    val sha256: String,
)

@Entity(tableName = "content_meta", primaryKeys = ["key"])
data class ContentMetaEntity(
    val key: String,
    val value: String,
)
