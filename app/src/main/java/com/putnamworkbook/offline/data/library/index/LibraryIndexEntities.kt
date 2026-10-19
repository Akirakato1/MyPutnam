package com.putnamworkbook.offline.data.library.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.putnamworkbook.offline.data.json.JsonValue
import com.putnamworkbook.offline.data.json.StrictJsonCodec

@Entity(tableName = "library_content")
data class LibraryContentEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "content_type") val contentType: String,
    val label: String,
    @ColumnInfo(name = "collection_id") val collectionId: String?,
    @ColumnInfo(name = "set_id") val setId: String?,
    @ColumnInfo(name = "problem_id") val problemId: String?,
    @ColumnInfo(name = "topics_json") val topicsJson: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "article_id") val articleId: String?,
    @ColumnInfo(name = "aliases_json") val aliasesJson: String,
    @ColumnInfo(name = "keywords_json") val keywordsJson: String,
    @ColumnInfo(name = "display_order") val displayOrder: Int,
    @ColumnInfo(name = "catalog_listed") val catalogListed: Boolean,
    @ColumnInfo(name = "logical_removal") val logicalRemoval: Boolean,
    @ColumnInfo(name = "desired_installed") val desiredInstalled: Boolean,
    @ColumnInfo(name = "active_source") val activeSource: String?,
    @ColumnInfo(name = "active_digest") val activeDigest: String?,
    @ColumnInfo(name = "quarantined_digest") val quarantinedDigest: String?,
    @ColumnInfo(name = "quarantine_error_code") val quarantineErrorCode: String?,
    @ColumnInfo(name = "quarantine_error_message") val quarantineErrorMessage: String?,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String?,
    @ColumnInfo(name = "last_error_message") val lastErrorMessage: String?,
)

@Entity(
    tableName = "library_package",
    primaryKeys = ["content_key", "source"],
    foreignKeys = [ForeignKey(
        entity = LibraryContentEntity::class,
        parentColumns = ["content_key"],
        childColumns = ["content_key"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("content_key")],
)
data class LibraryPackageEntity(
    @ColumnInfo(name = "content_key") val contentKey: String,
    val source: String,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "package_revision") val packageRevision: String,
    @ColumnInfo(name = "package_schema_version") val packageSchemaVersion: Int,
    @ColumnInfo(name = "content_schema_version") val contentSchemaVersion: Int,
    @ColumnInfo(name = "rich_document_version") val richDocumentVersion: Int,
    val path: String,
    @ColumnInfo(name = "compressed_bytes") val compressedBytes: Long,
    @ColumnInfo(name = "uncompressed_bytes") val uncompressedBytes: Long,
    val sha256: String,
    @ColumnInfo(name = "min_app_version_code") val minAppVersionCode: Int,
    val location: String,
)

@Entity(
    tableName = "package_operation",
    foreignKeys = [ForeignKey(
        entity = LibraryContentEntity::class,
        parentColumns = ["content_key"],
        childColumns = ["content_key"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("content_key")],
)
data class PackageOperationEntity(
    @androidx.room.PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    val action: String,
    @ColumnInfo(name = "content_key") val contentKey: String,
    val state: String,
    @ColumnInfo(name = "bytes_completed") val bytesCompleted: Long,
    @ColumnInfo(name = "bytes_total") val bytesTotal: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "library_metadata")
data class LibraryMetadataEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val revision: Long,
) {
    companion object { const val SINGLETON_ID = 1 }
}

internal fun encodeStringList(values: List<String>): String = StrictJsonCodec.stringify(
    JsonValue.ArrayValue(values.map(JsonValue::StringValue)),
)

internal fun decodeStringList(value: String): List<String> =
    ((StrictJsonCodec.parse(value) as? JsonValue.ArrayValue)?.values
        ?: error("Stored string list is not an array"))
        .map { (it as? JsonValue.StringValue)?.value ?: error("Stored string list contains a non-string") }
