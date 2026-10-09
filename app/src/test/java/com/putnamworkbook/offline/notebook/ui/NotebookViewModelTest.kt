package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.domain.model.AppSettings
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
import com.putnamworkbook.offline.notebook.edit.NotebookClipboard
import com.putnamworkbook.offline.notebook.edit.CanvasViewport
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import java.io.IOException

class NotebookViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val key = ProblemKey("putnam", "2025", "A1")

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
    fun persistentSaveFailurePropagatesWhileOptimisticStrokeRemainsVisible() = runBlocking {
        val fileRepository = FileNotebookRepository(temporaryFolder.newFolder("save-failure").toPath())
        val repository = object : NotebookRepository by fileRepository {
            override suspend fun applyEdits(
                key: ProblemKey,
                pageId: String,
                edits: List<com.putnamworkbook.offline.notebook.model.NotebookEdit>,
            ) = throw IOException("disk unavailable")
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val viewModel = NotebookViewModel(
            repository,
            FakeSettingsRepository(),
            scope,
            saveWait = { CompletableDeferred<Unit>().await() },
        )
        viewModel.open(key)
        viewModel.awaitIdle()
        viewModel.commitStroke(stroke("unsaved"))

        val result = runCatching { viewModel.awaitIdle() }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(listOf("unsaved"), viewModel.uiState.value.strokes.map { it.id })
        assertEquals(NotebookSaveState.ERROR, viewModel.uiState.value.saveState)
        scope.cancel()
    }

    @Test
    fun strokeAcceptedDuringDelayedUndoRemainsVisibleAndDurable() = runBlocking {
        val fileRepository = FileNotebookRepository(temporaryFolder.newFolder("undo-race").toPath())
        val undoStarted = CompletableDeferred<Unit>()
        val releaseUndo = CompletableDeferred<Unit>()
        val repository = object : NotebookRepository by fileRepository {
            override suspend fun undo(key: ProblemKey, pageId: String) =
                fileRepository.undo(key, pageId).also {
                    undoStarted.complete(Unit)
                    releaseUndo.await()
                }
        }
        val saveGate = CompletableDeferred<Unit>()
        val viewModel = viewModel(repository, FakeSettingsRepository(), wait = { saveGate.await() })
        viewModel.open(key)
        viewModel.awaitIdle()
        val pageId = viewModel.uiState.value.document!!.pages.single().id
        viewModel.commitStroke(stroke("before-undo"))
        viewModel.awaitIdle()

        viewModel.undo()
        undoStarted.await()
        viewModel.commitStroke(stroke("during-undo"))
        releaseUndo.complete(Unit)
        viewModel.awaitIdle()

        assertEquals(listOf("during-undo"), viewModel.uiState.value.strokes.map { it.id })
        assertEquals(listOf("during-undo"), fileRepository.loadStrokes(key, pageId).map { it.id })
        viewModel.close()
    }

    @Test
    fun closeFlushesDebouncedStrokeBeforeCancellingItsScope() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("close-flush").toPath())
        val waitForever = CompletableDeferred<Unit>()
        val viewModel = viewModel(repository, FakeSettingsRepository(), wait = { waitForever.await() })
        viewModel.open(key)
        viewModel.awaitIdle()
        val pageId = viewModel.uiState.value.document!!.pages.single().id
        viewModel.commitStroke(stroke("before-close"))
        assertTrue(repository.loadStrokes(key, pageId).isEmpty())

        viewModel.close()

        assertEquals(listOf("before-close"), repository.loadStrokes(key, pageId).map { it.id })
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

        val viewport = CanvasViewport(320f, 640f)
        viewModel.updateViewport(viewport)
        viewModel.setToolMode(NotebookToolMode.SELECT)
        viewModel.select(NormalizedRect(0.45f, 0.45f, 0.55f, 0.55f), viewport)
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
        first.updateViewport(CanvasViewport(320f, 640f))
        first.commitStroke(stroke("copied"))
        first.awaitIdle()
        first.setToolMode(NotebookToolMode.SELECT)
        first.select(NormalizedRect(0f, 0f, 1f, 1f), CanvasViewport(320f, 640f))
        first.copySelection()
        first.close()

        val secondRepository = FileNotebookRepository(temporaryFolder.newFolder("clipboard-b").toPath())
        val second = viewModel(secondRepository, FakeSettingsRepository(), wait = { }, clipboard = clipboard)
        second.open(ProblemKey("putnam", "2025", "A2"))
        second.awaitIdle()
        assertFalse(second.uiState.value.clipboardHasContent)
        second.updateViewport(CanvasViewport(1_200f, 600f))
        assertTrue(second.uiState.value.clipboardHasContent)
        second.paste()
        assertEquals(1, second.uiState.value.strokes.size)
        assertNotEquals("copied", second.uiState.value.strokes.single().id)
        assertTrue(second.uiState.value.selection != null)
        second.setToolMode(NotebookToolMode.PEN)
        assertNull(second.uiState.value.selection)
        second.close()
    }

    @Test
    fun viewportIsRetainedAcrossPagesAndResizeRecomputesWholeStrokeSelection() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("viewport-selection").toPath())
        val viewModel = viewModel(repository, FakeSettingsRepository(), wait = { })
        val portrait = CanvasViewport(320f, 640f)
        val landscape = CanvasViewport(1_200f, 600f)
        viewModel.open(key)
        viewModel.awaitIdle()
        viewModel.updateViewport(portrait)
        viewModel.commitStroke(
            StrokeRecord(
                id = "thick",
                brush = BrushSpec(width = 20f),
                samples = listOf(StrokeSample(0.4f, 0.4f, 0L, null)),
            ),
        )
        viewModel.awaitIdle()
        viewModel.setToolMode(NotebookToolMode.SELECT)
        viewModel.select(NormalizedRect(0.39f, 0.39f, 0.41f, 0.41f), portrait)
        val portraitBounds = viewModel.uiState.value.selection!!.bounds

        viewModel.updateViewport(landscape)
        val landscapeBounds = viewModel.uiState.value.selection!!.bounds
        assertTrue(landscapeBounds.width < portraitBounds.width)
        assertTrue(landscapeBounds.height > portraitBounds.height)

        viewModel.copySelection()
        viewModel.addPage()
        viewModel.awaitIdle()
        assertEquals(landscape, viewModel.uiState.value.canvasViewport)
        assertNull(viewModel.uiState.value.selection)
        viewModel.paste()
        assertEquals(1, viewModel.uiState.value.strokes.size)
        viewModel.awaitIdle()

        viewModel.selectPage(0)
        viewModel.awaitIdle()
        assertEquals(landscape, viewModel.uiState.value.canvasViewport)
        assertNull(viewModel.uiState.value.selection)
        viewModel.close()
    }

    @Test
    fun workspaceBrushLoadsFromSettingsAndPersistsTheLatestLiveChange() = runBlocking {
        val initialBrush = BrushSpec(colorArgb = 0xFF1D4ED8L, width = 4f)
        val settings = FakeSettingsRepository(AppSettings(brush = initialBrush))
        val repository = FileNotebookRepository(temporaryFolder.newFolder("brush").toPath())
        val viewModel = viewModel(repository, settings)

        viewModel.open(key)
        viewModel.awaitIdle()
        assertEquals(initialBrush, viewModel.uiState.value.brush)

        viewModel.setBrush(BrushSpec(colorArgb = 0xFFB91C1CL, width = 7f))
        val finalBrush = BrushSpec(colorArgb = 0xFF15803DL, width = 9f)
        viewModel.setBrush(finalBrush)

        assertEquals(finalBrush, viewModel.uiState.value.brush)
        viewModel.awaitIdle()
        assertEquals(finalBrush, settings.observe().first().brush)
        viewModel.close()
    }

    @Test
    fun liveBrushWinsWhenLoadOrCreateFinishesAfterTheToolbarChange() = runBlocking {
        assertBrushSurvivesDelayedOperation(
            folder = "brush-load-or-create",
            blockedCall = GatedCall.LOAD_OR_CREATE,
            startOperation = { it.open(key) },
        )
    }

    @Test
    fun liveBrushWinsWhenLoadExistingFinishesAfterTheToolbarChange() = runBlocking {
        assertBrushSurvivesDelayedOperation(
            folder = "brush-load-existing",
            blockedCall = GatedCall.LOAD_EXISTING,
            startOperation = { it.open(key) },
        )
    }

    @Test
    fun liveBrushWinsWhenLoadStrokesFinishesAfterTheToolbarChange() = runBlocking {
        assertBrushSurvivesDelayedOperation(
            folder = "brush-load-strokes",
            blockedCall = GatedCall.LOAD_STROKES,
            startOperation = { it.open(key) },
        )
    }

    @Test
    fun liveBrushWinsWhenPageSelectionPublicationFinishesAfterTheToolbarChange() = runBlocking {
        val fileRepository = FileNotebookRepository(temporaryFolder.newFolder("brush-page-select").toPath())
        val repository = GatedNotebookRepository(fileRepository)
        val settings = FakeSettingsRepository(AppSettings(brush = OLD_BRUSH))
        val viewModel = viewModel(repository, settings, wait = { })
        viewModel.open(key)
        viewModel.awaitIdle()
        viewModel.addPage()
        viewModel.awaitIdle()
        val gate = repository.blockNext(GatedCall.LOAD_EXISTING)

        viewModel.selectPage(0)
        gate.started.await()
        viewModel.setBrush(NEW_BRUSH)
        gate.release.complete(Unit)
        viewModel.awaitIdle()

        assertEquals(NEW_BRUSH, viewModel.uiState.value.brush)
        assertEquals(NEW_BRUSH, settings.observe().first().brush)
        viewModel.close()
    }

    @Test
    fun liveBrushWinsWhenUndoFinishesAfterTheToolbarChange() = runBlocking {
        val fileRepository = FileNotebookRepository(temporaryFolder.newFolder("brush-undo").toPath())
        val repository = GatedNotebookRepository(fileRepository)
        val settings = FakeSettingsRepository(AppSettings(brush = OLD_BRUSH))
        val viewModel = viewModel(repository, settings, wait = { })
        viewModel.open(key)
        viewModel.awaitIdle()
        viewModel.commitStroke(stroke("undo-brush"))
        viewModel.awaitIdle()
        val gate = repository.blockNext(GatedCall.UNDO)

        viewModel.undo()
        gate.started.await()
        viewModel.setBrush(NEW_BRUSH)
        gate.release.complete(Unit)
        viewModel.awaitIdle()

        assertEquals(NEW_BRUSH, viewModel.uiState.value.brush)
        assertEquals(NEW_BRUSH, settings.observe().first().brush)
        viewModel.close()
    }

    @Test
    fun rapidBrushWritesPersistLatestAndRetryAfterASettingsFailure() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("brush-retry").toPath())
        val settings = FakeSettingsRepository(AppSettings(brush = OLD_BRUSH))
        val viewModel = viewModel(repository, settings, wait = { })
        viewModel.open(key)
        viewModel.awaitIdle()
        settings.failNextUpdate = true

        viewModel.setBrush(BrushSpec(colorArgb = 0xFFB91C1CL, width = 5f))
        viewModel.awaitIdle()
        assertEquals(NotebookSaveState.ERROR, viewModel.uiState.value.saveState)

        val writes = listOf(
            BrushSpec(colorArgb = 0xFF15803DL, width = 6f),
            BrushSpec(colorArgb = 0xFF7E22CEL, width = 8f),
            NEW_BRUSH,
        )
        writes.forEach(viewModel::setBrush)
        viewModel.awaitIdle()
        assertEquals(NEW_BRUSH, viewModel.uiState.value.brush)
        assertEquals(NEW_BRUSH, settings.observe().first().brush)
        assertEquals(NotebookSaveState.IDLE, viewModel.uiState.value.saveState)
        assertNull(viewModel.uiState.value.errorMessage)
        viewModel.close()

        val reopened = viewModel(repository, settings, wait = { })
        reopened.open(key)
        reopened.awaitIdle()
        assertEquals(NEW_BRUSH, reopened.uiState.value.brush)
        reopened.close()
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
        val viewport = CanvasViewport(1_000f, 1_000f)
        viewModel.updateViewport(viewport)
        viewModel.select(NormalizedRect(0.1f, 0.1f, 0.6f, 0.4f), viewport)

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

    private suspend fun assertBrushSurvivesDelayedOperation(
        folder: String,
        blockedCall: GatedCall,
        startOperation: (NotebookViewModel) -> Unit,
    ) {
        val fileRepository = FileNotebookRepository(temporaryFolder.newFolder(folder).toPath())
        val repository = GatedNotebookRepository(fileRepository)
        val settings = FakeSettingsRepository(AppSettings(brush = OLD_BRUSH))
        val viewModel = viewModel(repository, settings, wait = { })
        val gate = repository.blockNext(blockedCall)

        startOperation(viewModel)
        gate.started.await()
        viewModel.setBrush(NEW_BRUSH)
        gate.release.complete(Unit)
        viewModel.awaitIdle()

        assertEquals(NEW_BRUSH, viewModel.uiState.value.brush)
        assertEquals(NEW_BRUSH, settings.observe().first().brush)
        viewModel.close()
    }

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

    private companion object {
        val OLD_BRUSH = BrushSpec(colorArgb = 0xFF1D4ED8L, width = 4f)
        val NEW_BRUSH = BrushSpec(colorArgb = 0xFF0F766EL, width = 11f)
    }
}

private class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val settings = MutableStateFlow(initial)
    var failNextUpdate: Boolean = false

    override fun observe(): Flow<AppSettings> = settings

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        if (failNextUpdate) {
            failNextUpdate = false
            throw IOException("settings unavailable")
        }
        settings.value = transform(settings.value)
    }
}

private enum class GatedCall {
    LOAD_OR_CREATE,
    LOAD_EXISTING,
    LOAD_STROKES,
    UNDO,
}

private class OperationGate {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun pause() {
        started.complete(Unit)
        release.await()
    }
}

private class GatedNotebookRepository(
    private val delegate: NotebookRepository,
) : NotebookRepository by delegate {
    private var blockedCall: GatedCall? = null
    private var gate: OperationGate? = null

    fun blockNext(call: GatedCall): OperationGate = OperationGate().also {
        check(gate == null) { "A repository operation is already blocked" }
        blockedCall = call
        gate = it
    }

    override suspend fun loadOrCreate(key: ProblemKey, defaultPaper: PaperStyle): NotebookDocument {
        pauseIfBlocked(GatedCall.LOAD_OR_CREATE)
        return delegate.loadOrCreate(key, defaultPaper)
    }

    override suspend fun loadExisting(key: ProblemKey): NotebookDocument? {
        pauseIfBlocked(GatedCall.LOAD_EXISTING)
        return delegate.loadExisting(key)
    }

    override suspend fun loadStrokes(key: ProblemKey, pageId: String): List<StrokeRecord> {
        pauseIfBlocked(GatedCall.LOAD_STROKES)
        return delegate.loadStrokes(key, pageId)
    }

    override suspend fun undo(key: ProblemKey, pageId: String): NotebookDocument {
        pauseIfBlocked(GatedCall.UNDO)
        return delegate.undo(key, pageId)
    }

    private suspend fun pauseIfBlocked(call: GatedCall) {
        if (blockedCall != call) return
        val pending = checkNotNull(gate)
        blockedCall = null
        gate = null
        pending.pause()
    }
}
