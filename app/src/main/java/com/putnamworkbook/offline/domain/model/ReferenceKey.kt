package com.putnamworkbook.offline.domain.model

data class ReferenceKey(
    val categoryId: String,
    val articleId: String,
) {
    init {
        requireSafeId(categoryId, "categoryId")
        requireSafeId(articleId, "articleId")
    }

    val canonical: String = "math-reference/$categoryId/$articleId"

    companion object {
        fun parse(canonical: String): ReferenceKey {
            val parts = canonical.split('/')
            require(parts.size == 3 && parts[0] == "math-reference") {
                "Invalid canonical reference key: $canonical"
            }
            return ReferenceKey(parts[1], parts[2])
        }
    }
}
