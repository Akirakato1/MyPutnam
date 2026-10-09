package com.putnamworkbook.offline.feature.archive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.putnamworkbook.offline.data.content.LibraryCollection
import com.putnamworkbook.offline.data.content.ProblemSetSummary
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.content.TopicSummary
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryBrowserTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun topicRowRemainsUsableDuringLoadingErrorAndEmptyStates() {
        var state by mutableStateOf(baseState().copy(selectedTopicId = "algebra", isLoadingTopicProblems = true))
        composeRule.setContent {
            MaterialTheme {
                ArchiveScreen(state, {}, {}, {}, {})
            }
        }

        composeRule.onNodeWithTag("topic-filter-row").assertIsDisplayed()
        composeRule.onNodeWithTag("topic-all").assertIsDisplayed()
        composeRule.onNodeWithTag("topic-algebra").assertIsDisplayed()

        composeRule.runOnIdle {
            state = state.copy(isLoadingTopicProblems = false, topicError = "Topic unavailable")
        }
        composeRule.onNodeWithText("Topic unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("topic-all").assertIsDisplayed()

        composeRule.runOnIdle { state = state.copy(topicError = null) }
        composeRule.onNodeWithText("No installed problems match this topic").assertIsDisplayed()
        composeRule.onNodeWithTag("topic-algebra").assertIsDisplayed()
    }

    @Test
    fun topicResultsPreserveFullCanonicalKeysAcrossCollections() {
        val selected = AtomicReference<ProblemKey>()
        val putnam = problem(ProblemKey("putnam", "2025", "A1"), "Putnam", "2025")
        val course = problem(ProblemKey("course", "unit", "A1"), "Course", "Unit One")
        val state = baseState().copy(
            selectedTopicId = "algebra",
            topicProblems = listOf(putnam, course),
        )
        composeRule.setContent {
            MaterialTheme {
                ArchiveScreen(state, {}, {}, {}, selected::set)
            }
        }

        composeRule.onNodeWithTag("library-problem-putnam/2025/A1").assertIsDisplayed()
        composeRule.onNodeWithTag("library-problem-course/unit/A1").assertIsDisplayed().performClick()
        assertEquals(course.summary.key, selected.get())
    }

    @Test
    fun allCatalogCanOpenANonnumericSet() {
        val selected = AtomicReference<ProblemSetKey>()
        val state = baseState()
        composeRule.setContent {
            MaterialTheme {
                ArchiveScreen(state, {}, {}, selected::set, {})
            }
        }

        composeRule.onNodeWithTag("library-set-course/unit").assertIsDisplayed().performClick()
        assertEquals(ProblemSetKey("course", "unit"), selected.get())
    }

    private fun baseState() = ArchiveUiState(
        collections = listOf(
            LibraryCollection("putnam", "Putnam", "competition", 0),
            LibraryCollection("course", "Course", "course", 1),
        ),
        setsByCollection = mapOf(
            "putnam" to listOf(ProblemSetSummary(ProblemSetKey("putnam", "2025"), "2025", 0, 2025)),
            "course" to listOf(ProblemSetSummary(ProblemSetKey("course", "unit"), "Unit One", 0, null)),
        ),
        topics = listOf(TopicSummary("algebra", "Algebra", 0)),
        isLoadingCatalog = false,
    )

    private fun problem(key: ProblemKey, collection: String, set: String): ProblemRowUiState {
        val summary = ProblemSummary(key, "A1", 1, collection, set, key.setId.toIntOrNull())
        return ProblemRowUiState(summary, ProblemProgress.untouched(key))
    }
}
