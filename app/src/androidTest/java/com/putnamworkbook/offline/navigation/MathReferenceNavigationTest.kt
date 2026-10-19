package com.putnamworkbook.offline.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import com.putnamworkbook.offline.data.content.*
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import com.putnamworkbook.offline.domain.model.ReferenceKey
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class MathReferenceNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun homeOpensReferenceBrowserAndInternalBackPrecedesHome() {
        composeRule.setContent {
            MaterialTheme {
                PutnamNavHost(
                    NavigationContentRepository,
                    NavigationProgressRepository,
                    referenceRepository = NavigationReferenceRepository,
                )
            }
        }
        composeRule.onNodeWithTag("home-math-reference").performClick()
        composeRule.onNodeWithTag("math-reference-browser").assertIsDisplayed()
        composeRule.onNodeWithTag("math-reference-category-number-theory").performClick()
        composeRule.onNodeWithTag("math-reference-articles").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag("math-reference-categories").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
    }
}

private object NavigationContentRepository : ContentRepository {
    override suspend fun collections() = emptyList<LibraryCollection>()
    override suspend fun sets(collectionId: String) = emptyList<ProblemSetSummary>()
    override suspend fun topics() = emptyList<TopicSummary>()
    override suspend fun problems(setKey: ProblemSetKey) = emptyList<ProblemSummary>()
    override suspend fun problemsByTopic(topicId: String) = emptyList<ProblemSummary>()
    override suspend fun problem(key: ProblemKey): ProblemContent? = null
}

private object NavigationProgressRepository : ProgressRepository {
    override fun observe(key: ProblemKey): Flow<ProblemProgress> = MutableStateFlow(ProblemProgress.untouched(key))
    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = MutableStateFlow(emptyMap())
    override fun observeRecent(): Flow<ProblemProgress?> = MutableStateFlow(null)
    override suspend fun markOpened(key: ProblemKey) = Unit
    override suspend fun revealNextHint(key: ProblemKey) = 0
    override suspend fun revealSolution(key: ProblemKey) = Unit
    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = Unit
    override suspend fun setNotebookState(key: ProblemKey, pageCount: Int, pageIndex: Int, referenceTab: ReferenceTab) = Unit
}

private object NavigationReferenceRepository : ReferenceRepository {
    private val key = ReferenceKey("number-theory", "euler-totient")
    override fun observeCategories(): Flow<List<ReferenceCategorySummary>> = MutableStateFlow(
        listOf(ReferenceCategorySummary("number-theory", "Number Theory", 1)),
    )
    override fun observeInstalledArticles(): Flow<List<ReferenceArticleSummary>> = MutableStateFlow(
        listOf(ReferenceArticleSummary(key, "Euler's totient function", listOf("phi"), listOf("coprime"), "Number Theory", 1, 1)),
    )
    override suspend fun article(key: ReferenceKey): ReferenceArticleContent? = null
}
