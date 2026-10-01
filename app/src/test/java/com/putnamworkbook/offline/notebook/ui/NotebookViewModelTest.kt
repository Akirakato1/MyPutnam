package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.domain.model.AppSettings
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotebookViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val key = ProblemKey(2025, "A1")

    @Test
    fun firstOpenAutosavesBufferedStrokeAndUndoRedoSurviveReload() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("autosave").toPath())
        val settings = FakeSettingsRepository()
        var viewModel = viewModel(repository, settings)

        viewModel.open(key)
        viewModel.awaitIdle()
        assertEquals(1, viewModel.uiState.value.document!!.pages.size)
        val pageId = viewModel.uiState.value.document!!.pages.single().id

        viewModel.commitStroke(stroke("one"))
        viewModel.awaitIdle()
        assertEquals(listOf("one"), repository.loadStrokes(key, pageId).map { it.id })

        viewModel.undo()
        viewModel.awaitIdle()
        assertTrue(viewModel.uiState.value.strokes.isEmpty())
        viewModel.close()

        viewModel = viewModel(repository, settings)
        viewModel.open(key)
        viewModel.awaitIdle()
        assertFalse(viewModel.uiState.value.document!!.pages.single().strokes.single().active)
        viewModel.redo()
        viewModel.awaitIdle()
        assertEquals(listOf("one"), viewModel.uiState.value.strokes.map { it.id })
        viewModel.close()
    }

    @Test
    fun rapidStrokesAppearImmediatelyAndPersistInOrderAfterDebounce() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("rapid").toPath())
        val gate = CompletableDeferred<Unit>()
        val viewModel = viewModel(repository, FakeSettingsRepository(), wait = { gate.await() })
        viewModel.open(key)
        viewModel.awaitIdle()
        val pageId = viewModel.uiState.value.document!!.pages.single().id

        (1..10).forEach { viewModel.commitStroke(stroke("rapid-$it")) }

        assertEquals((1..10).map { "rapid-$it" }, viewModel.uiState.value.strokes.map { it.id })
        assertTrue(repository.loadStrokes(key, pageId).isEmpty())
        assertEquals(10L, viewModel.uiState.value.uiRevision)
        assertEquals(0L, viewModel.uiState.value.durableRevision)

        gate.complete(Unit)
        viewModel.awaitIdle()
        assertEquals((1..10).map { "rapid-$it" }, repository.loadStrokes(key, pageId).map { it.id })
        assertEquals(10L, viewModel.uiState.value.durableRevision)
        viewModel.close()
    }

    @Test
    fun completedOlderSaveNeverReplacesAStrokeAddedWhileRepositoryIsBlocked() = runBlocking {
        val fileRepository = FileNotebookRepository(temporaryFolder.newFolder("stale").toPath())
        val persistStarted = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val repository = object : NotebookRepository by fileRepository {
            override suspend fun applyEdits(key: ProblemKey, pageId: String, edits: List<com.putnamworkbook.offline.notebook.model.NotebookEdit>) =
                fileRepository.applyEdits(key, pageId, edits).also {
                    persistStarted.complete(Unit)
                    releasePersist.await()
                }
        }
        val viewModel = viewModel(repository, FakeSettingsRepository(), wait = { })
        viewModel.open(key)
        viewModel.awaitIdle()

        viewModel.commitStroke(stroke("first"))
        persistStarted.await()
        viewModel.commitStroke(stroke("second"))
        assertEquals(listOf("first", "second"), viewModel.uiState.value.strokes.map { it.id })

        releasePersist.complete(Unit)
        viewModel.awaitIdle()

        assertEquals(listOf("first", "second"), viewModel.uiState.value.strokes.map { it.id })
        assertEquals(2L, viewModel.uiState.value.durableRevision)
        viewModel.close()
    }

    @Test
    fun newPagesUseCurrentDefaultWithoutChangingExistingPages() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("defaults").toPath())
        val settings = FakeSettingsRepository()
        var viewModel = viewModel(repository, settings)
        viewModel.open(key)
        viewModel.awaitIdle()
        settings.update { it.copy(defaultPaperStyle = PaperStyle.BLANK) }

        viewModel.addPage()
        viewModel.awaitIdle()

        assertEquals(
            listOf(PaperStyle.SQUARE_GRID, PaperStyle.BLANK),
            viewModel.uiState.value.document!!.pages.map { it.paperStyle },
        )
        assertEquals(1, viewModel.uiState.value.activePageIndex)
        viewModel.close()

        viewModel = viewModel(repository, settings)
        viewModel.open(key, initialPageIndex = 1)
        viewModel.awaitIdle()
        assertEquals(1, viewModel.uiState.value.activePageIndex)
        viewModel.close()
    }

    @Test
    fun clearAndDeleteMutateOnlyAfterConfirmation() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("confirm").toPath())
        val viewModel = viewModel(repository, FakeSettingsRepository())
        viewModel.open(key)
        viewModel.awaitIdle()
        viewModel.commitStroke(stroke("keep-until-confirmed"))
        viewModel.awaitIdle()
        val firstPage = viewModel.uiState.value.document!!.pages.single().id

        viewModel.requestClearPage(firstPage)
        assertTrue(viewModel.uiState.value.pendingConfirmation is PendingConfirmation.ClearPage)
        assertEquals(1, viewModel.uiState.value.document!!.pages.single().strokes.size)
        viewModel.confirmClearPage()
        viewModel.awaitIdle()
        assertTrue(viewModel.uiState.value.document!!.pages.single().strokes.isEmpty())
        assertNull(viewModel.uiState.value.pendingConfirmation)

        viewModel.addPage()
        viewModel.awaitIdle()
        val secondPage = viewModel.uiState.value.document!!.pages[1].id
        viewModel.requestDeletePage(secondPage)
        assertEquals(2, viewModel.uiState.value.document!!.pages.size)
        viewModel.confirmDeletePage()
        viewModel.awaitIdle()
        assertEquals(listOf(firstPage), viewModel.uiState.value.document!!.pages.map { it.id })
        viewModel.close()
    }

    private fun viewModel(
        repository: NotebookRepository,
        settings: SettingsRepository,
        wait: suspend (Long) -> Unit = { delay(it) },
    ) = NotebookViewModel(repository, settings, CoroutineScope(Dispatchers.Default), wait)

    private fun stroke(id: String) = StrokeRecord(
        id = id,
        brush = BrushSpec(),
        samples = listOf(
            StrokeSample(0.1f, 0.1f, 0L, null),
            StrokeSample(0.2f, 0.2f, 10L, null),
        ),
    )
}

private class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val settings = MutableStateFlow(initial)

    override fun observe(): Flow<AppSettings> = settings

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        settings.value = transform(settings.value)
    }
}
