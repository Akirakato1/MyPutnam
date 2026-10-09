package com.putnamworkbook.offline.domain.model

data class ProblemKey(
    val collectionId: String,
    val setId: String,
    val problemId: String,
) {
    init {
        requireSafeId(collectionId, "collectionId")
        requireSafeId(setId, "setId")
        requireSafeId(problemId, "problemId")
    }

    val canonical: String = "$collectionId/$setId/$problemId"
    val setKey: ProblemSetKey = ProblemSetKey(collectionId, setId)

    companion object {
        fun parse(canonical: String): ProblemKey {
            val parts = canonical.split('/')
            require(parts.size == 3) { "Invalid canonical problem key: $canonical" }
            return ProblemKey(parts[0], parts[1], parts[2])
        }
    }
}

internal fun requireSafeId(value: String, label: String) {
    require(value.isNotEmpty() && value != "." && value != ".." && SAFE_ID.matches(value)) {
        "$label is not a safe ID: $value"
    }
}

private val SAFE_ID = Regex("[A-Za-z0-9._-]+")
