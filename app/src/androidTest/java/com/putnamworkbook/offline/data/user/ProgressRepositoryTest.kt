package com.putnamworkbook.offline.data.user

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressRepositoryTest {
    private lateinit var database: UserDatabase
    private lateinit var progress: ProgressRepository
    private lateinit var settings: SettingsRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        progress = RoomProgressRepository(database) { now }
        settings = RoomSettingsRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun openHintsSolutionAndCompletionHaveIndependentMonotonicState() = runBlocking {
        val key = ProblemKey("putnam", "2025", "A1")

        val untouched = progress.observe(key).first()
        assertEquals(ProblemStatus.UNTOUCHED, untouched.status)
        assertEquals(0, untouched.revealedHintCount)
        assertFalse(untouched.solutionRevealed)
        assertEquals(1, untouched.notebookPageCount)
        assertEquals(0, untouched.activePageIndex)
        assertEquals(ReferenceTab.PROBLEM, untouched.activeReferenceTab)
        assertEquals(null, untouched.lastOpenedAt)

        progress.markOpened(key)
        assertEquals(ProblemStatus.IN_PROGRESS, progress.observe(key).first().status)

        assertEquals(listOf(1, 2, 3, 4, 4, 4), List(6) { progress.revealNextHint(key) })
        var updated = progress.observe(key).first()
        assertEquals(4, updated.revealedHintCount)
        assertFalse(updated.solutionRevealed)

        progress.revealSolution(key)
        updated = progress.observe(key).first()
        assertEquals(4, updated.revealedHintCount)
        assertTrue(updated.solutionRevealed)

        progress.markCompleted(key, true)
        assertEquals(ProblemStatus.COMPLETED, progress.observe(key).first().status)
        progress.markCompleted(key, false)
        assertEquals(ProblemStatus.IN_PROGRESS, progress.observe(key).first().status)
    }

    @Test
    fun notebookStateAndRecentProblemAdvanceTogether() = runBlocking {
        val first = ProblemKey("putnam", "2025", "A1")
        val second = ProblemKey("course", "unit", "A1")
        progress.markOpened(first)

        now = 2_000L
        progress.setNotebookState(
            key = first,
            pageCount = 3,
            pageIndex = 2,
            referenceTab = ReferenceTab.PREREQUISITES,
        )
        var recent = progress.observeRecent().first()!!
        assertEquals(first, recent.key)
        assertEquals(3, recent.notebookPageCount)
        assertEquals(2, recent.activePageIndex)
        assertEquals(ReferenceTab.PREREQUISITES, recent.activeReferenceTab)
        assertEquals(2_000L, recent.lastOpenedAt)

        now = 3_000L
        progress.markOpened(second)
        assertEquals(second, progress.observeRecent().first()!!.key)

        now = 4_000L
        progress.setNotebookState(first, 4, 1, ReferenceTab.ORIGINAL_PAPER)
        recent = progress.observeRecent().first()!!
        assertEquals(first, recent.key)
        assertEquals(4_000L, recent.lastOpenedAt)
        assertEquals(ReferenceTab.ORIGINAL_PAPER, recent.activeReferenceTab)
        assertEquals(setOf(first, second), progress.observeAll().first().keys)
    }

    @Test
    fun changingDefaultPaperDoesNotMutateAnExistingPage() = runBlocking {
        data class ExistingPageFixture(val paperStyle: PaperStyle)

        val existingPage = ExistingPageFixture(PaperStyle.SQUARE_GRID)
        assertEquals(PaperStyle.SQUARE_GRID, settings.observe().first().defaultPaperStyle)

        settings.update { it.copy(defaultPaperStyle = PaperStyle.BLANK) }

        assertEquals(PaperStyle.BLANK, settings.observe().first().defaultPaperStyle)
        assertEquals(PaperStyle.SQUARE_GRID, existingPage.paperStyle)
    }
}
