package com.putnamworkbook.offline.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.LibraryCollection
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSetSummary
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.content.TopicSummary
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ReferenceTab
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceEntry
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRecord
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceRepository
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceResult
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceStorageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FreeNotebookNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun homeOpensLibraryAndBackReturnsHome() {
        setContent()

        composeRule.onNodeWithTag("home-free-notebook").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("free-workspace-library").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test
    fun creatingWorkspaceNavigatesToItsUuidRoute() {
        val repository = NavigationFreeWorkspaceRepository()
        setContent(repository)

        composeRule.onNodeWithTag("home-free-notebook").performClick()
        composeRule.onNodeWithTag("free-workspace-new").performClick()
        composeRule.onNodeWithTag("free-workspace-name-input").performTextInput("Trip work")
        composeRule.onNodeWithTag("free-workspace-name-confirm").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag("free-workspace-placeholder-${repository.id}"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun malformedUuidRouteShowsStableError() {
        setContent(startDestination = "free-notebooks/not-a-uuid")

        composeRule.onNodeWithTag("free-workspace-invalid-route").assertIsDisplayed()
    }

    private fun setContent(
        repository: FreeWorkspaceRepository = NavigationFreeWorkspaceRepository(),
        startDestination: String = AppRoute.Home.path,
    ) {
        composeRule.setContent {
            MaterialTheme {
                PutnamNavHost(
                    contentRepository = EmptyNavigationContent,
                    progressRepository = EmptyNavigationProgress,
                    freeWorkspaceRepository = repository,
                    startDestination = startDestination,
                )
            }
        }
    }
}

private class NavigationFreeWorkspaceRepository : FreeWorkspaceRepository {
    val id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000123")
    private var record: FreeWorkspaceRecord? = null

    override suspend fun list() = FreeWorkspaceResult.Success(
        record?.let { listOf(FreeWorkspaceEntry(it, FreeWorkspaceStorageState.READY)) }.orEmpty(),
    )

    override suspend fun create(name: String, defaultPaper: PaperStyle): FreeWorkspaceResult<FreeWorkspaceRecord> {
        val created = FreeWorkspaceRecord(id, name, 1, 1)
        record = created
        return FreeWorkspaceResult.Success(created)
    }

    override suspend fun rename(id: UUID, name: String) = FreeWorkspaceResult.Failure("unused")
    override suspend fun duplicate(sourceId: UUID, name: String) = FreeWorkspaceResult.Failure("unused")
    override suspend fun delete(id: UUID) = FreeWorkspaceResult.Failure("unused")
    override suspend fun markModified(id: UUID) = FreeWorkspaceResult.Failure("unused")
    override fun notebookKey(id: UUID) = ProblemKey("__local__", "free-workspaces", id.toString())
}

private object EmptyNavigationContent : ContentRepository {
    override suspend fun collections(): List<LibraryCollection> = emptyList()
    override suspend fun sets(collectionId: String): List<ProblemSetSummary> = emptyList()
    override suspend fun topics(): List<TopicSummary> = emptyList()
    override suspend fun problems(setKey: com.putnamworkbook.offline.domain.model.ProblemSetKey): List<ProblemSummary> = emptyList()
    override suspend fun problemsByTopic(topicId: String): List<ProblemSummary> = emptyList()
    override suspend fun problem(key: ProblemKey): ProblemContent? = null
}

private object EmptyNavigationProgress : ProgressRepository {
    override fun observe(key: ProblemKey): Flow<ProblemProgress> = MutableStateFlow(ProblemProgress.untouched(key))
    override fun observeAll(): Flow<Map<ProblemKey, ProblemProgress>> = MutableStateFlow(emptyMap())
    override fun observeRecent(): Flow<ProblemProgress?> = MutableStateFlow(null)
    override suspend fun markOpened(key: ProblemKey) = Unit
    override suspend fun revealNextHint(key: ProblemKey) = 0
    override suspend fun revealSolution(key: ProblemKey) = Unit
    override suspend fun markCompleted(key: ProblemKey, completed: Boolean) = Unit
    override suspend fun setNotebookState(key: ProblemKey, pageCount: Int, pageIndex: Int, referenceTab: ReferenceTab) = Unit
}
