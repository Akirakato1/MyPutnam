package com.putnamworkbook.offline.acceptance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.putnamworkbook.offline.di.AppContainer
import com.putnamworkbook.offline.di.initializeContent
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.freeworkspace.FreeWorkspaceResult
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InPlaceUpgradeAcceptanceTest {
    @Test
    fun packageLibraryInitializationPreservesExistingUserWorkAndRemovesOnlyLegacyContent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer(context)
        val problemKey = ProblemKey("putnam", "2025", "A1")
        container.progressRepository.markOpened(problemKey)
        val seededHintCount = container.progressRepository.revealNextHint(problemKey)
        val notebook = container.notebookRepository.loadOrCreate(problemKey, PaperStyle.SQUARE_GRID)
        val workspace = container.freeWorkspaceRepository.create(
            "Upgrade preservation ${System.nanoTime()}",
            PaperStyle.BLANK,
        )
        assertTrue(workspace is FreeWorkspaceResult.Success)

        val legacyDatabase = context.getDatabasePath("content.db").toPath()
        Files.createDirectories(legacyDatabase.parent)
        Files.writeString(legacyDatabase, "obsolete immutable content")
        context.getSharedPreferences("immutable-content-state", Context.MODE_PRIVATE)
            .edit()
            .putString("asset-sha256", "obsolete")
            .commit()

        val repository = container.initializeContent()

        var problemCount = 0
        for (set in repository.sets("putnam")) {
            problemCount += repository.problems(set.key).size
        }
        assertEquals(72, problemCount)
        assertFalse(Files.exists(legacyDatabase))
        assertFalse(
            context.getSharedPreferences("immutable-content-state", Context.MODE_PRIVATE)
                .contains("asset-sha256"),
        )
        assertEquals(seededHintCount, container.progressRepository.observe(problemKey).first().revealedHintCount)
        assertEquals(notebook, container.notebookRepository.loadExisting(problemKey))
        val workspaces = container.freeWorkspaceRepository.list()
        assertTrue(workspaces is FreeWorkspaceResult.Success)
        val createdId = (workspace as FreeWorkspaceResult.Success).value.id
        assertNotNull((workspaces as FreeWorkspaceResult.Success).value.singleOrNull { it.record.id == createdId })
    }
}
