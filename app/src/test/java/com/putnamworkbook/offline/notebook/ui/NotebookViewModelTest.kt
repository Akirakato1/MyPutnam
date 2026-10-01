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
import com.putnamworkbook.offline.notebook.edit.NotebookClipboard
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
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
import org.junit.Assert.assertNotEquals
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
    fun partialMarqueeSelectsWholeStrokeAndDeleteUndoOperateAsOneGroup() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("selection").toPath())
        val viewModel = viewModel(repository, FakeSettingsRepository(), wait = { })
        viewModel.open(key)
        viewModel.awaitIdle()
        viewModel.commitStroke(strokeAt("crossing", 0.3f, 0.5f, 0.7f, 0.5f))
        viewModel.commitStroke(strokeAt("outside", 0.1f, 0.1f, 0.2f, 0.2f))
        viewModel.awaitIdle()

        viewModel.setToolMode(NotebookToolMode.SELECT)
        viewModel.select(NormalizedRect(0.45f, 0.45f, 0.55f, 0.55f))
        assertEquals(setOf("crossing"), viewModel.uiState.value.selection!!.selectedStrokeIds)

        viewModel.deleteSelection()
        assertEquals(listOf("outside"), viewModel.uiState.value.strokes.map { it.id })
        viewModel.awaitIdle()
        viewModel.undo()
        viewModel.awaitIdle()
        assertEquals(setOf("crossing", "outside"), viewModel.uiState.value.strokes.map { it.id }.toSet())
        viewModel.close()
    }

    @Test
    fun clipboardCanPasteAcrossNotebookViewModelsAndPenModeClearsSelection() = runBlocking {
        val clipboard = NotebookClipboard()
        val firstRepository = FileNotebookRepository(temporaryFolder.newFolder("clipboard-a").toPath())
        val first = viewModel(firstRepository, FakeSettingsRepository(), wait = { }, clipboard = clipboard)
        first.open(key)
        first.awaitIdle()
        first.commitStroke(stroke("copied"))
        first.awaitIdle()
        first.setToolMode(NotebookToolMode.SELECT)
        first.select(NormalizedRect(0f, 0f, 1f, 1f))
        first.copySelection()
        first.close()

        val secondRepository = FileNotebookRepository(temporaryFolder.newFolder("clipboard-b").toPath())
        val second = viewModel(secondRepository, FakeSettingsRepository(), wait = { }, clipboard = clipboard)
        second.open(ProblemKey(2025, "A2"))
        second.awaitIdle()
        second.paste()
        assertEquals(1, second.uiState.value.strokes.size)
        assertNotEquals("copied", second.uiState.value.strokes.single().id)
        assertTrue(second.uiState.value.selection != null)
        second.setToolMode(NotebookToolMode.PEN)
        assertNull(second.uiState.value.selection)
        second.close()
    }

    @Test
    fun movePreviewIsNonDestructiveAndCommitCreatesOneUndoableReplacementGroup() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("move").toPath())
        val viewModel = viewModel(repository, FakeSettingsRepository(), wait = { })
        viewModel.open(key)
        viewModel.awaitIdle()
        viewModel.commitStroke(strokeAt("left", 0.2f, 0.2f, 0.3f, 0.3f))
        viewModel.commitStroke(strokeAt("right", 0.4f, 0.2f, 0.5f, 0.3f))
        viewModel.awaitIdle()
        viewModel.setToolMode(NotebookToolMode.SELECT)
        viewModel.select(NormalizedRect(0.1f, 0.1f, 0.6f, 0.4f))

        viewModel.previewMove(0.2f, 0.1f)
        assertEquals(listOf("left", "right"), viewModel.uiState.value.strokes.map { it.id })
        assertEquals(2, viewModel.uiState.value.selection!!.previewStrokes!!.size)
        viewModel.commitMove(0.2f, 0.1f)
        val replacementIds = viewModel.uiState.value.strokes.map { it.id }
        assertTrue(replacementIds.none { it in setOf("left", "right") })
        viewModel.awaitIdle()

        viewModel.undo()
        viewModel.awaitIdle()
        assertEquals(setOf("left", "right"), viewModel.uiState.value.strokes.map { it.id }.toSet())
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
        clipboard: NotebookClipboard = NotebookClipboard(),
    ) = NotebookViewModel(repository, settings, CoroutineScope(Dispatchers.Default), wait, clipboard)

    private fun stroke(id: String) = StrokeRecord(
        id = id,
        brush = BrushSpec(),
        samples = listOf(
            StrokeSample(0.1f, 0.1f, 0L, null),
            StrokeSample(0.2f, 0.2f, 10L, null),
        ),
    )

    private fun strokeAt(id: String, x1: Float, y1: Float, x2: Float, y2: Float) = StrokeRecord(
        id = id,
        brush = BrushSpec(),
        samples = listOf(
            StrokeSample(x1, y1, 0L, null),
            StrokeSample(x2, y2, 10L, null),
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
