package com.putnamworkbook.offline.data.content

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "problem",
    primaryKeys = ["year", "code"],
    indices = [Index(value = ["year", "ordinal"], unique = true)],
)
data class ProblemEntity(
    val year: Int,
    val code: String,
    val ordinal: Int,
    @ColumnInfo(name = "statement_asset_path") val statementAssetPath: String,
    @ColumnInfo(name = "statement_sha256") val statementSha256: String,
    @ColumnInfo(name = "solution_asset_path") val solutionAssetPath: String,
    @ColumnInfo(name = "solution_sha256") val solutionSha256: String,
    @ColumnInfo(name = "attribution_json") val attributionJson: String,
)

@Entity(
    tableName = "hint",
    primaryKeys = ["year", "problem_code", "hint_order"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemEntity::class,
            parentColumns = ["year", "code"],
            childColumns = ["year", "problem_code"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["year", "problem_code"])],
)
data class HintEntity(
    val year: Int,
    @ColumnInfo(name = "problem_code") val problemCode: String,
    @ColumnInfo(name = "hint_order") val order: Int,
    val stage: String,
    val title: String,
    @ColumnInfo(name = "asset_path") val assetPath: String,
    val sha256: String,
)

@Entity(
    tableName = "prerequisite",
    primaryKeys = ["year", "problem_code", "prerequisite_order"],
    foreignKeys = [
        ForeignKey(
            entity = ProblemEntity::class,
            parentColumns = ["year", "code"],
            childColumns = ["year", "problem_code"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["year", "problem_code"])],
)
data class PrerequisiteEntity(
    val year: Int,
    @ColumnInfo(name = "problem_code") val problemCode: String,
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
