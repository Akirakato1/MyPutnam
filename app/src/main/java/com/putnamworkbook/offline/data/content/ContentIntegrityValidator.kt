package com.putnamworkbook.offline.data.content

import android.database.Cursor
import com.putnamworkbook.offline.domain.model.ProblemKey

class ContentIntegrityValidator(
    private val database: ContentDatabase,
) {
    suspend fun validate() {
        val sqlite = database.openHelper.writableDatabase
        check(singleString(sqlite.query("PRAGMA integrity_check")) == "ok") {
            "Packaged content database failed SQLite integrity validation"
        }
        sqlite.query("PRAGMA foreign_key_check").use { cursor ->
            check(!cursor.moveToFirst()) { "Packaged content database has foreign-key violations" }
        }

        val dao = database.contentDao()
        val metadata = dao.metadata().associate { it.key to it.value }
        check(metadata.keys.containsAll(REQUIRED_METADATA)) {
            "Packaged content database is missing required metadata"
        }
        check(metadata.getValue("schema_version") == ContentDatabase.SCHEMA_VERSION.toString()) {
            "Unsupported packaged content schema"
        }
        check(metadata.getValue("build_kind") in setOf("set", "archive")) {
            "Invalid packaged content build kind"
        }
        listOf("catalog_sha256", "manifest_sha256").forEach { key ->
            check(SHA_256.matches(metadata.getValue(key))) { "Invalid packaged content $key" }
        }

        val actualCounts = mapOf(
            "collection_count" to dao.collectionCount(),
            "set_count" to dao.setCount(),
            "topic_count" to dao.topicCount(),
            "problem_count" to dao.problemCount(),
            "problem_topic_count" to dao.problemTopicCount(),
            "hint_count" to dao.hintCount(),
            "prerequisite_count" to dao.prerequisiteCount(),
        )
        actualCounts.forEach { (key, actual) ->
            check(metadata.getValue(key).toIntOrNull() == actual) {
                "Packaged content $key does not match its tables"
            }
        }
        check(actualCounts.getValue("topic_count") == EXPECTED_TOPIC_COUNT) {
            "Packaged content must include the complete topic catalog"
        }
        check(dao.nonCanonicalProblemCount() == 0) { "Packaged content has non-canonical problem keys" }
        check(dao.incompleteProblemCount() == 0) { "Packaged content has incomplete problem records" }
        check(dao.invalidHintProgressionCount() == 0) { "Packaged content has invalid hint progression" }
        dao.problemKeys().forEach(ProblemKey::parse)
    }

    private fun singleString(cursor: Cursor): String = cursor.use {
        check(it.moveToFirst()) { "SQLite validation returned no result" }
        it.getString(0)
    }

    private companion object {
        const val EXPECTED_TOPIC_COUNT = 13
        val SHA_256 = Regex("[a-f0-9]{64}")
        val REQUIRED_METADATA = setOf(
            "schema_version",
            "content_version",
            "build_kind",
            "release_id",
            "catalog_sha256",
            "manifest_sha256",
            "collection_count",
            "set_count",
            "topic_count",
            "problem_count",
            "problem_topic_count",
            "hint_count",
            "prerequisite_count",
        )
    }
}
