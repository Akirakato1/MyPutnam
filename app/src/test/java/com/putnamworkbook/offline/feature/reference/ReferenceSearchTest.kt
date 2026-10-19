package com.putnamworkbook.offline.feature.reference

import com.putnamworkbook.offline.data.content.ReferenceArticleSummary
import com.putnamworkbook.offline.domain.model.ReferenceKey
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceSearchTest {
    private val articles = listOf(
        article("number-theory", "euler-totient", "Euler's totient function", listOf("phi", "totient"), listOf("coprime"), "Number Theory", 2, 2),
        article("algebra", "phi-polynomial", "Phi polynomial", listOf("golden ratio"), listOf("roots"), "Algebra", 1, 3),
        article("number-theory", "fermat-euler", "Fermat Euler theorems", listOf("Euler theorem"), listOf("residue classes"), "Number Theory", 2, 1),
    )

    @Test
    fun ranksExactPrefixAndMetadataMatchesDeterministically() {
        assertEquals("euler-totient", searchReferences("  Euler's   totient function ", articles).first().article.key.articleId)
        assertEquals("euler-totient", searchReferences("phi", articles).first().article.key.articleId)
        assertEquals("phi-polynomial", searchReferences("phi pol", articles).first().article.key.articleId)
        assertEquals("fermat-euler", searchReferences("euler theo", articles).first().article.key.articleId)
        assertEquals(listOf("fermat-euler", "euler-totient"), searchReferences("number theory", articles).map { it.article.key.articleId })
        assertEquals(listOf("fermat-euler"), searchReferences("residue", articles).map { it.article.key.articleId })
    }

    @Test
    fun emptyQueryUsesStableCategoryAndArticleOrder() {
        assertEquals(
            listOf("phi-polynomial", "fermat-euler", "euler-totient"),
            searchReferences("   ", articles).map { it.article.key.articleId },
        )
    }

    @Test
    fun normalizationIsIndependentOfTurkishDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("phi-polynomial", searchReferences("PHI POL", articles).first().article.key.articleId)
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun article(
        category: String,
        id: String,
        title: String,
        aliases: List<String>,
        keywords: List<String>,
        categoryTitle: String,
        categoryOrder: Int,
        order: Int,
    ) = ReferenceArticleSummary(
        ReferenceKey(category, id), title, aliases, keywords, categoryTitle, categoryOrder, order,
    )
}
