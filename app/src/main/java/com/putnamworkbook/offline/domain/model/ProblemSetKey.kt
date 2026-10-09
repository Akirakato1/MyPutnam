package com.putnamworkbook.offline.domain.model

data class ProblemSetKey(
    val collectionId: String,
    val setId: String,
) {
    init {
        requireSafeId(collectionId, "collectionId")
        requireSafeId(setId, "setId")
    }

    val canonical: String = "$collectionId/$setId"

    companion object {
        fun parse(canonical: String): ProblemSetKey {
            val parts = canonical.split('/')
            require(parts.size == 2) { "Invalid canonical set key: $canonical" }
            return ProblemSetKey(parts[0], parts[1])
        }
    }
}
