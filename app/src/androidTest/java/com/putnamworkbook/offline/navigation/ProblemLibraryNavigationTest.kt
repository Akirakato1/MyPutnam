package com.putnamworkbook.offline.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import com.putnamworkbook.offline.data.content.*
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class ProblemLibraryNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun homeOpensProblemLibraryAndBackReturnsHome() {
        composeRule.setContent {
            MaterialTheme { PutnamNavHost(EmptyContentRepository, EmptyProgressRepository) }
        }
        composeRule.onNodeWithTag("home-problem-library").performClick()
        composeRule.onNodeWithTag("problem-library-placeholder").assertExists()
        pressBack()
        composeRule.onNodeWithTag("home-screen").assertExists()
    }
}

private object EmptyContentRepository : ContentRepository {
    override suspend fun collections() = emptyList<LibraryCollection>()
    override suspend fun sets(collectionId: String) = emptyList<ProblemSetSummary>()
    override suspend fun topics() = emptyList<TopicSummary>()
    override suspend fun problems(setKey: ProblemSetKey) = emptyList<ProblemSummary>()
    override suspend fun problemsByTopic(topicId: String) = emptyList<ProblemSummary>()
    override suspend fun problem(key: ProblemKey): ProblemContent? = null
}

private object EmptyProgressRepository : ProgressRepository {
    override fun observe(key: ProblemKey): Flow<ProblemProgress> = MutableStateFlow(ProblemProgress.untouched(key))
    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = MutableStateFlow(emptyMap())
    override fun observeRecent(): Flow<ProblemProgress?> = MutableStateFlow(null)
    override suspend fun markOpened(key: ProblemKey) = Unit
    override suspend fun revealNextHint(key: ProblemKey) = 0
    override suspend fun revealSolution(key: ProblemKey) = Unit
    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = Unit
    override suspend fun setNotebookState(key: ProblemKey, pageCount: Int, pageIndex: Int, referenceTab: ReferenceTab) = Unit
}
