package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.domain.model.AppSettings
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
    fun firstOpenAutosavesEveryStrokeAndUndoRedoSurviveReload() = runBlocking {
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
    fun newPagesUseCurrentDefaultWithoutChangingExistingPages() = runBlocking {
        val repository = FileNotebookRepository(temporaryFolder.newFolder("defaults").toPath())
        val settings = FakeSettingsRepository()
        val viewModel = viewModel(repository, settings)
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
        repository: FileNotebookRepository,
        settings: SettingsRepository,
    ) = NotebookViewModel(repository, settings, CoroutineScope(Dispatchers.Default))

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
