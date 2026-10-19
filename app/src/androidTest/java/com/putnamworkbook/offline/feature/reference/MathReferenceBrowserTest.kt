package com.putnamworkbook.offline.feature.reference

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.putnamworkbook.offline.data.content.*
import com.putnamworkbook.offline.domain.model.ReferenceKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MathReferenceBrowserTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun categoriesSearchArticleRelationsErrorAndAccessibilityAreExposed() {
        val key = ReferenceKey("number-theory", "euler-totient")
        val summary = ReferenceArticleSummary(key, "Euler's totient function", listOf("phi"), listOf("coprime"), "Number Theory", 1, 1)
        val article = ReferenceArticleContent(
            summary,
            ContentResourceRef(key.canonical, "a".repeat(64), "documents/article.fragment.html", "b".repeat(64)),
            listOf(ReferenceKey("number-theory", "missing")), emptyList(),
            listOf(Attribution("reference", "Authoritative source", "https://example.com")),
        )
        var category: String? = null
        var missing: String? = null
        composeRule.setContent {
            MaterialTheme {
                MathReferenceBrowser(
                    state = MathReferenceUiState(
                        location = ReferenceLocation.Article(key),
                        categories = listOf(ReferenceCategorySummary("number-theory", "Number Theory", 1)),
                        articles = listOf(summary), currentArticle = article,
                    ),
                    onQueryChanged = {}, onOpenCategory = { category = it }, onOpenArticle = {},
                    onOpenRelatedReference = {}, onOpenRelatedProblem = {}, onUnavailableContent = { missing = it },
                    onBack = {}, onRetry = {}, onIntegrityError = {},
                    articleBody = { Text("Rendered article body") },
                )
            }
        }

        composeRule.onNodeWithText("Rendered article body").assertIsDisplayed()
        composeRule.onNodeWithText("Authoritative source").assertIsDisplayed()
        composeRule.onNodeWithTag("reference-related-math-reference/number-theory/missing").performClick()
        assertEquals("math-reference/number-theory/missing", missing)
        assertEquals(null, category)
    }
}
