package com.putnamworkbook.offline.data.content

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.HintStage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: ContentDatabase
    private lateinit var repository: ContentRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = Room.databaseBuilder(context, ContentDatabase::class.java, DATABASE_NAME)
            .createFromInputStream {
                InstrumentationRegistry.getInstrumentation().context.assets
                    .open("databases/content-fixture.db")
            }
            .build()
        repository = RoomContentRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun prepackagedFixtureExposesOrderedCompleteContent() = runBlocking {
        assertEquals(listOf(2025), repository.years())
        assertEquals(
            listOf(ProblemSummary(ProblemKey(2025, "A1"), 1)),
            repository.problems(2025),
        )

        val problem = repository.problem(ProblemKey(2025, "A1"))
        assertNotNull(problem)
        problem!!
        assertEquals(2025, problem.summary.key.year)
        assertEquals("A1", problem.summary.key.code)
        assertEquals("content/2025/problems/A1/statement.html", problem.statementAssetPath)
        assertEquals("a".repeat(64), problem.statementSha256)
        assertEquals(listOf(1, 2, 3, 4), problem.hints.map { it.order })
        assertEquals(
            listOf(HintStage.NUDGE, HintStage.STRATEGY, HintStage.KNOWLEDGE, HintStage.OUTLINE),
            problem.hints.map { it.stage },
        )
        assertEquals(listOf("Nudge", "Technique", "Knowledge gap", "Outline"), problem.hints.map { it.title })
        assertEquals(listOf(1, 2), problem.prerequisites.map { it.order })
        assertEquals(listOf("Parity", "Pigeonhole principle"), problem.prerequisites.map { it.title })
        assertEquals("content/2025/problems/A1/solution.html", problem.solutionAssetPath)
        assertEquals("c".repeat(64), problem.solutionSha256)
        assertEquals(listOf("problem", "solution"), problem.attribution.map { it.kind })
        assertEquals(listOf("Official problems", "Official solutions"), problem.attribution.map { it.label })
        assertEquals("2", database.contentDao().meta("contentSchemaVersion"))
        assertEquals("2025.2", database.contentDao().meta("contentVersion"))
        assertEquals(2, database.openHelper.readableDatabase.version)
        val problemColumns = buildList {
            database.openHelper.readableDatabase.query("PRAGMA table_info(problem)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertFalse(problemColumns.any { it.startsWith("original_pdf_") })
    }

    private companion object {
        const val DATABASE_NAME = "content-test.db"
    }
}
