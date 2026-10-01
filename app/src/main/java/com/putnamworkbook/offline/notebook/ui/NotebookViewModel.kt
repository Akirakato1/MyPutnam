package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookEdit
import com.putnamworkbook.offline.notebook.model.NotebookEditGroup
import com.putnamworkbook.offline.notebook.model.NotebookReducer
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
import com.putnamworkbook.offline.notebook.edit.NotebookClipboard
import com.putnamworkbook.offline.notebook.edit.NotebookSelectionState
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.edit.SelectionCorner
import com.putnamworkbook.offline.notebook.edit.StrokeTransform
import com.putnamworkbook.offline.notebook.edit.intersects
import com.putnamworkbook.offline.notebook.edit.selectionBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import java.util.UUID

class NotebookViewModel(
    private val repository: NotebookRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    saveWait: suspend (Long) -> Unit = { delay(it) },
    private val clipboard: NotebookClipboard = NotebookClipboard(),
) {
    private val mutableUiState = MutableStateFlow(NotebookUiState())
    private val operationMutex = Mutex()
    private var currentKey: ProblemKey? = null
    private var latestOperation: Job? = null
    private val saveCoordinator = NotebookSaveCoordinator(
        scope = scope,
        wait = saveWait,
        persist = { pageId, edits ->
            repository.applyEdits(requireNotNull(currentKey), pageId, edits)
        },
        onSaving = ::handleSaving,
        onSaved = ::handleSaved,
        onFailure = ::handleSaveFailure,
    )

    val uiState: StateFlow<NotebookUiState> = mutableUiState.asStateFlow()

    fun open(key: ProblemKey, initialPageIndex: Int = 0) {
        currentKey = key
        launchOperation(NotebookSaveState.LOADING) {
            val defaultPaper = settingsRepository.observe().first().defaultPaperStyle
            val document = repository.loadOrCreate(key, defaultPaper)
            publishDocument(document, activePageIndex = initialPageIndex.coerceIn(document.pages.indices))
        }
    }

    fun commitStroke(stroke: StrokeRecord) {
        val edit = NotebookEdit(
            group = NotebookEditGroup(
                id = UUID.randomUUID().toString(),
                activatedStrokeIds = listOf(stroke.id),
                deactivatedStrokeIds = emptyList(),
            ),
            newStrokes = listOf(stroke),
        )
        commitOptimisticEdit(edit)
    }

    fun setToolMode(mode: NotebookToolMode) {
        mutableUiState.value = mutableUiState.value.copy(
            toolMode = mode,
            selection = if (mode == NotebookToolMode.PEN) null else mutableUiState.value.selection,
        )
    }

    fun select(rect: NormalizedRect) {
        val selected = mutableUiState.value.strokes.filter { it.intersects(rect) }
        mutableUiState.value = mutableUiState.value.copy(
            selection = selected.selectionState(),
        )
    }

    fun previewMove(dx: Float, dy: Float) {
        val selected = selectedStrokes()
        val replacements = StrokeTransform.move(selected, dx, dy) { "preview-${UUID.randomUUID()}" }
        updatePreview(replacements)
    }

    fun commitMove(dx: Float, dy: Float) {
        val selected = selectedStrokes()
        if (selected.isEmpty()) return
        commitReplacement(selected, StrokeTransform.move(selected, dx, dy) { UUID.randomUUID().toString() })
    }

    fun previewScale(corner: SelectionCorner, target: NormalizedPoint) {
        val selection = mutableUiState.value.selection ?: return
        val replacements = StrokeTransform.scaleFromCorner(
            selectedStrokes(), selection.bounds, corner, target,
        ) { "preview-${UUID.randomUUID()}" }
        updatePreview(replacements)
    }

    fun commitScale(corner: SelectionCorner, target: NormalizedPoint) {
        val selection = mutableUiState.value.selection ?: return
        val selected = selectedStrokes()
        if (selected.isEmpty()) return
        commitReplacement(
            selected,
            StrokeTransform.scaleFromCorner(selected, selection.bounds, corner, target) {
                UUID.randomUUID().toString()
            },
        )
    }

    fun copySelection() {
        clipboard.copy(selectedStrokes())
        mutableUiState.value = mutableUiState.value.copy(clipboardHasContent = clipboard.hasContent)
    }

    fun cutSelection() {
        copySelection()
        deleteSelection()
    }

    fun paste() {
        val pasted = clipboard.createPaste(mutableUiState.value.strokes) { UUID.randomUUID().toString() }
        if (pasted.isEmpty()) return
        commitOptimisticEdit(
            NotebookEdit(
                NotebookEditGroup(UUID.randomUUID().toString(), pasted.map { it.id }, emptyList()),
                pasted,
            ),
            selectionAfter = pasted.selectionState(),
        )
    }

    fun deleteSelection() {
        val selected = selectedStrokes()
        if (selected.isEmpty()) return
        commitOptimisticEdit(
            NotebookEdit(
                NotebookEditGroup(UUID.randomUUID().toString(), emptyList(), selected.map { it.id }),
                emptyList(),
            ),
            selectionAfter = null,
        )
    }

    fun cancelSelection() {
        mutableUiState.value = mutableUiState.value.copy(selection = null)
    }

    fun undo() = launchSaving {
        saveCoordinator.flush()
        val (key, pageId) = currentPage()
        publishDocument(repository.undo(key, pageId), mutableUiState.value.activePageIndex)
    }

    fun redo() = launchSaving {
        saveCoordinator.flush()
        val (key, pageId) = currentPage()
        publishDocument(repository.redo(key, pageId), mutableUiState.value.activePageIndex)
    }

    fun addPage() = launchSaving {
        saveCoordinator.flush()
        val (key, pageId) = currentPage()
        val oldIds = requireDocument().pages.mapTo(mutableSetOf()) { it.id }
        val paper = settingsRepository.observe().first().defaultPaperStyle
        val document = repository.addPage(key, pageId, paper)
        val newIndex = document.pages.indexOfFirst { it.id !in oldIds }
        publishDocument(document, newIndex)
    }

    fun requestDeletePage(pageId: String) {
        requirePage(pageId)
        mutableUiState.value = mutableUiState.value.copy(
            pendingConfirmation = PendingConfirmation.DeletePage(pageId),
        )
    }

    fun confirmDeletePage() {
        val confirmation = mutableUiState.value.pendingConfirmation as? PendingConfirmation.DeletePage ?: return
        launchSaving {
            saveCoordinator.flush()
            val key = requireNotNull(currentKey)
            val oldDocument = requireDocument()
            val deletedIndex = oldDocument.pages.indexOfFirst { it.id == confirmation.pageId }
            val document = repository.deletePage(key, confirmation.pageId)
            val activeIndex = when {
                deletedIndex < mutableUiState.value.activePageIndex -> mutableUiState.value.activePageIndex - 1
                else -> mutableUiState.value.activePageIndex.coerceAtMost(document.pages.lastIndex)
            }
            publishDocument(document, activeIndex, pendingConfirmation = null)
        }
    }

    fun requestClearPage(pageId: String) {
        requirePage(pageId)
        mutableUiState.value = mutableUiState.value.copy(
            pendingConfirmation = PendingConfirmation.ClearPage(pageId),
        )
    }

    fun confirmClearPage() {
        val confirmation = mutableUiState.value.pendingConfirmation as? PendingConfirmation.ClearPage ?: return
        launchSaving {
            saveCoordinator.flush()
            val document = repository.clearPage(requireNotNull(currentKey), confirmation.pageId)
            publishDocument(document, mutableUiState.value.activePageIndex, pendingConfirmation = null)
        }
    }

    fun dismissConfirmation() {
        mutableUiState.value = mutableUiState.value.copy(pendingConfirmation = null)
    }

    fun reorderPage(pageId: String, newIndex: Int) = launchSaving {
        saveCoordinator.flush()
        val document = requireDocument()
        val activePageId = document.pages[mutableUiState.value.activePageIndex].id
        val changed = repository.reorderPage(requireNotNull(currentKey), pageId, newIndex)
        publishDocument(changed, changed.pages.indexOfFirst { it.id == activePageId })
    }

    fun selectPage(index: Int) = launchOperation(NotebookSaveState.IDLE) {
        saveCoordinator.flush()
        val document = requireDocument()
        require(index in document.pages.indices) { "Page index is outside the notebook" }
        publishDocument(document, index)
    }

    suspend fun awaitIdle() {
        while (true) {
            val operation = latestOperation
            operation?.join()
            saveCoordinator.awaitIdle()
            if (operation === latestOperation) return
        }
    }

    suspend fun flush() {
        saveCoordinator.flush()
    }

    fun close() {
        saveCoordinator.cancel()
        scope.cancel()
    }

    private fun launchSaving(block: suspend () -> Unit) = launchOperation(NotebookSaveState.SAVING, block)

    private fun launchOperation(
        initialState: NotebookSaveState,
        block: suspend () -> Unit,
    ) {
        latestOperation = scope.launch {
            operationMutex.withLock {
                mutableUiState.value = mutableUiState.value.copy(
                    saveState = initialState,
                    errorMessage = null,
                )
                try {
                    block()
                    mutableUiState.value = mutableUiState.value.copy(
                        saveState = if (initialState == NotebookSaveState.SAVING) {
                            NotebookSaveState.SAVED
                        } else {
                            NotebookSaveState.IDLE
                        },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableUiState.value = mutableUiState.value.copy(
                        saveState = NotebookSaveState.ERROR,
                        errorMessage = error.message ?: error::class.simpleName,
                    )
                }
            }
        }
    }

    private suspend fun publishDocument(
        document: NotebookDocument,
        activePageIndex: Int,
        pendingConfirmation: PendingConfirmation? = mutableUiState.value.pendingConfirmation,
    ) {
        val safeIndex = activePageIndex.coerceIn(document.pages.indices)
        val page = document.pages[safeIndex]
        val strokes = repository.loadStrokes(document.key, page.id)
        mutableUiState.value = mutableUiState.value.copy(
            document = document,
            activePageIndex = safeIndex,
            strokes = strokes,
            pendingConfirmation = pendingConfirmation,
            selection = null,
            clipboardHasContent = clipboard.hasContent,
        )
    }

    private fun currentPage(): Pair<ProblemKey, String> {
        val document = requireDocument()
        return requireNotNull(currentKey) to document.pages[mutableUiState.value.activePageIndex].id
    }

    private fun requireDocument(): NotebookDocument =
        requireNotNull(mutableUiState.value.document) { "Open a problem before editing its notebook" }

    private fun requirePage(pageId: String) {
        require(requireDocument().pages.any { it.id == pageId }) { "Unknown page id: $pageId" }
    }

    private fun handleSaving(revision: Long) {
        val state = mutableUiState.value
        if (revision < state.durableRevision) return
        mutableUiState.value = state.copy(saveState = NotebookSaveState.SAVING, errorMessage = null)
    }

    private fun handleSaved(revision: Long) {
        val state = mutableUiState.value
        if (revision < state.durableRevision) return
        val durableRevision = maxOf(state.durableRevision, revision)
        mutableUiState.value = state.copy(
            durableRevision = durableRevision,
            saveState = if (durableRevision >= state.uiRevision && !saveCoordinator.hasPendingEdits) {
                NotebookSaveState.SAVED
            } else {
                NotebookSaveState.SAVING
            },
            errorMessage = null,
        )
    }

    private fun handleSaveFailure(revision: Long, error: Exception) {
        val state = mutableUiState.value
        if (revision < state.durableRevision) return
        mutableUiState.value = state.copy(
            saveState = NotebookSaveState.ERROR,
            errorMessage = error.message ?: error::class.simpleName,
        )
    }

    private fun NotebookDocument.replacePage(changedPage: com.putnamworkbook.offline.notebook.model.NotebookPage): NotebookDocument =
        copy(pages = pages.map { if (it.id == changedPage.id) changedPage else it })

    private fun commitReplacement(originals: List<StrokeRecord>, replacements: List<StrokeRecord>) {
        commitOptimisticEdit(
            NotebookEdit(
                NotebookEditGroup(
                    UUID.randomUUID().toString(),
                    replacements.map { it.id },
                    originals.map { it.id },
                ),
                replacements,
            ),
            selectionAfter = replacements.selectionState(),
        )
    }

    private fun commitOptimisticEdit(
        edit: NotebookEdit,
        selectionAfter: NotebookSelectionState? = mutableUiState.value.selection,
    ) {
        val state = mutableUiState.value
        val document = requireNotNull(state.document) { "Open a problem before editing its notebook" }
        val page = document.pages[state.activePageIndex]
        val changedPage = NotebookReducer.applyEdits(page, listOf(edit))
        val deactivated = edit.group.deactivatedStrokeIds.toSet()
        val changedStrokes = state.strokes.filterNot { it.id in deactivated } + edit.newStrokes
        val revision = state.uiRevision + 1
        mutableUiState.value = state.copy(
            document = document.replacePage(changedPage),
            strokes = changedStrokes,
            selection = selectionAfter,
            clipboardHasContent = clipboard.hasContent,
            saveState = NotebookSaveState.SAVING,
            errorMessage = null,
            uiRevision = revision,
        )
        check(saveCoordinator.enqueue(page.id, edit) == revision) {
            "Notebook save revisions must remain monotonic"
        }
    }

    private fun selectedStrokes(): List<StrokeRecord> {
        val ids = mutableUiState.value.selection?.selectedStrokeIds ?: return emptyList()
        return mutableUiState.value.strokes.filter { it.id in ids }
    }

    private fun updatePreview(replacements: List<StrokeRecord>) {
        val selection = mutableUiState.value.selection ?: return
        mutableUiState.value = mutableUiState.value.copy(
            selection = selection.copy(previewStrokes = replacements),
        )
    }

    private fun List<StrokeRecord>.selectionState(): NotebookSelectionState? {
        val bounds = selectionBounds() ?: return null
        return NotebookSelectionState(mapTo(linkedSetOf()) { it.id }, bounds)
    }
}
