package com.putnamworkbook.offline.notebook.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookEdit
import com.putnamworkbook.offline.notebook.model.NotebookEditGroup
import com.putnamworkbook.offline.notebook.model.NotebookReducer
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
import com.putnamworkbook.offline.notebook.edit.NotebookClipboard
import com.putnamworkbook.offline.notebook.edit.CanvasViewport
import com.putnamworkbook.offline.notebook.edit.NotebookSelectionState
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.edit.OrientedSelectionFrame
import com.putnamworkbook.offline.notebook.edit.SelectionCorner
import com.putnamworkbook.offline.notebook.edit.SelectionFlipAxis
import com.putnamworkbook.offline.notebook.edit.StrokeTransform
import com.putnamworkbook.offline.notebook.edit.intersects
import com.putnamworkbook.offline.notebook.edit.selectionBounds
import com.putnamworkbook.offline.notebook.edit.toOrientedFrame
import com.putnamworkbook.offline.notebook.edit.snapRotationRadians
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class NotebookViewModel(
    private val repository: NotebookRepository,
    private val settingsRepository: SettingsRepository,
    private val providedScope: CoroutineScope? = null,
    saveWait: suspend (Long) -> Unit = { delay(it) },
    private val clipboard: NotebookClipboard = NotebookClipboard(),
) : ViewModel() {
    private val scope = providedScope ?: viewModelScope
    private val mutableUiState = MutableStateFlow(NotebookUiState())
    private val operationMutex = Mutex()
    private val brushUpdateMutex = Mutex()
    private var currentKey: ProblemKey? = null
    private var latestOperation: Job? = null
    private var latestBrushUpdate: Job? = null
    private val brushRevision = AtomicLong(0L)
    private var brushPersistenceFailure: BrushPersistenceFailure? = null
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
        mutableUiState.update { it.copy(rotationSnapEnabled = false) }
        launchOperation(NotebookSaveState.LOADING) {
            val openingBrushRevision = brushRevision.get()
            val settings = settingsRepository.observe().first()
            mutableUiState.update { current ->
                if (brushRevision.get() == openingBrushRevision) {
                    current.copy(brush = settings.brush)
                } else {
                    current
                }
            }
            val document = repository.loadOrCreate(key, settings.defaultPaperStyle)
            publishDocument(
                document,
                activePageIndex = initialPageIndex.coerceIn(document.pages.indices),
            )
        }
    }

    fun setBrush(brush: BrushSpec) {
        brushRevision.incrementAndGet()
        mutableUiState.value = mutableUiState.value.copy(brush = brush)
        val previousUpdate = latestBrushUpdate
        latestBrushUpdate = scope.launch {
            previousUpdate?.join()
            brushUpdateMutex.withLock {
                try {
                    val latestBrush = mutableUiState.value.brush
                    settingsRepository.update { settings -> settings.copy(brush = latestBrush) }
                    val recoveredFailure = brushPersistenceFailure
                    brushPersistenceFailure = null
                    if (recoveredFailure != null) {
                        mutableUiState.update { current ->
                            if (
                                current.saveState == NotebookSaveState.ERROR &&
                                current.errorMessage == recoveredFailure.message
                            ) {
                                current.copy(
                                    saveState = recoveredFailure.previousSaveState,
                                    errorMessage = recoveredFailure.previousErrorMessage,
                                )
                            } else {
                                current
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = error.message ?: error::class.simpleName
                    val current = mutableUiState.value
                    if (brushPersistenceFailure == null) {
                        brushPersistenceFailure = BrushPersistenceFailure(
                            previousSaveState = current.saveState,
                            previousErrorMessage = current.errorMessage,
                            message = message,
                        )
                    }
                    mutableUiState.value = mutableUiState.value.copy(
                        saveState = NotebookSaveState.ERROR,
                        errorMessage = message,
                    )
                }
            }
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
            clipboardHasContent = clipboard.hasContent && mutableUiState.value.canvasViewport != null,
        )
    }

    fun setRotationSnapEnabled(enabled: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(rotationSnapEnabled = enabled)
    }

    fun updateViewport(viewport: CanvasViewport) {
        mutableUiState.update { current ->
            val selectedIds = current.selection?.selectedStrokeIds
            val selected = if (selectedIds == null) {
                emptyList()
            } else {
                current.strokes.filter { it.id in selectedIds }
            }
            val viewportChanged = current.canvasViewport != null && current.canvasViewport != viewport
            current.copy(
                canvasViewport = viewport,
                selection = current.selection?.let { selection ->
                    if (viewportChanged) {
                        selected.selectionState(viewport)
                    } else {
                        selection
                    }
                },
                clipboardHasContent = clipboard.hasContent,
            )
        }
    }

    fun select(rect: NormalizedRect, viewport: CanvasViewport) {
        updateViewport(viewport)
        val selected = mutableUiState.value.strokes.filter { it.intersects(rect, viewport) }
        mutableUiState.value = mutableUiState.value.copy(
            selection = selected.selectionState(viewport),
        )
    }

    fun previewMove(dx: Float, dy: Float) {
        val viewport = mutableUiState.value.canvasViewport ?: return
        val selection = mutableUiState.value.selection ?: return
        val selected = selectedStrokes()
        val replacements = StrokeTransform.move(selected, dx, dy, viewport) { "preview-${UUID.randomUUID()}" }
        val replacementBounds = replacements.selectionBounds(viewport) ?: return
        val oldCenter = selection.bounds.center()
        val newCenter = replacementBounds.center()
        updatePreview(
            replacements,
            selection.frame.copy(
                center = NormalizedPoint(
                    x = selection.frame.center.x + newCenter.x - oldCenter.x,
                    y = selection.frame.center.y + newCenter.y - oldCenter.y,
                ),
            ),
        )
    }

    fun commitMove(dx: Float, dy: Float) {
        val viewport = mutableUiState.value.canvasViewport ?: return
        val selection = mutableUiState.value.selection ?: return
        if (selection.previewStrokes != null && selection.previewFrame != null) {
            commitPreviewedReplacement(viewport)
            return
        }
        val selected = selectedStrokes()
        if (selected.isEmpty()) return
        val replacements = StrokeTransform.move(selected, dx, dy, viewport) { UUID.randomUUID().toString() }
        val replacementBounds = replacements.selectionBounds(viewport) ?: return
        val oldCenter = selection.bounds.center()
        val newCenter = replacementBounds.center()
        commitReplacement(
            selected,
            replacements,
            viewport,
            selection.frame.copy(
                center = NormalizedPoint(
                    x = selection.frame.center.x + newCenter.x - oldCenter.x,
                    y = selection.frame.center.y + newCenter.y - oldCenter.y,
                ),
            ),
        )
    }

    fun previewScale(corner: SelectionCorner, target: NormalizedPoint) {
        val viewport = mutableUiState.value.canvasViewport ?: return
        val selection = mutableUiState.value.selection ?: return
        val result = StrokeTransform.scaleFromCorner(
            selectedStrokes(), selection.frame, corner, target, viewport,
        ) { "preview-${UUID.randomUUID()}" } ?: return
        updatePreview(result.strokes, result.frame)
    }

    fun commitScale(corner: SelectionCorner, target: NormalizedPoint) {
        val viewport = mutableUiState.value.canvasViewport ?: return
        val selection = mutableUiState.value.selection ?: return
        if (selection.previewStrokes != null && selection.previewFrame != null) {
            commitPreviewedReplacement(viewport)
            return
        }
        val selected = selectedStrokes()
        if (selected.isEmpty()) return
        val result = StrokeTransform.scaleFromCorner(
            selected, selection.frame, corner, target, viewport,
        ) { UUID.randomUUID().toString() } ?: return
        commitReplacement(
            selected,
            result.strokes,
            viewport,
            result.frame,
        )
    }

    fun previewRotation(requestedAngleRadians: Float) {
        val state = mutableUiState.value
        val viewport = state.canvasViewport ?: return
        val selection = state.selection ?: return
        val selected = selectedStrokes()
        if (selected.isEmpty()) return
        val requested = if (state.rotationSnapEnabled) {
            snapRotationRadians(requestedAngleRadians)
        } else {
            requestedAngleRadians
        }
        val result = StrokeTransform.rotate(
            selected,
            selection.frame,
            requested,
            viewport,
        ) { "preview-${UUID.randomUUID()}" } ?: return
        updatePreview(result.strokes, result.frame)
    }

    fun commitRotationPreview() {
        val viewport = mutableUiState.value.canvasViewport ?: return
        commitPreviewedReplacement(viewport)
    }

    fun flipSelection(axis: SelectionFlipAxis) {
        val state = mutableUiState.value
        val viewport = state.canvasViewport ?: return
        val selection = state.selection ?: return
        val selected = selectedStrokes()
        if (selected.isEmpty()) return
        val result = StrokeTransform.flip(
            selected,
            selection.frame,
            axis,
            viewport,
        ) { UUID.randomUUID().toString() } ?: return
        commitReplacement(selected, result.strokes, viewport, result.frame)
    }

    fun copySelection() {
        clipboard.copy(selectedStrokes())
        mutableUiState.value = mutableUiState.value.copy(
            clipboardHasContent = clipboard.hasContent && mutableUiState.value.canvasViewport != null,
        )
    }

    fun cutSelection() {
        copySelection()
        deleteSelection()
    }

    fun paste() {
        val viewport = mutableUiState.value.canvasViewport ?: return
        val pasted = clipboard.createPaste(
            mutableUiState.value.strokes,
            viewport,
        ) { UUID.randomUUID().toString() }
        if (pasted.isEmpty()) return
        commitOptimisticEdit(
            NotebookEdit(
                NotebookEditGroup(UUID.randomUUID().toString(), pasted.map { it.id }, emptyList()),
                pasted,
            ),
            selectionAfter = pasted.selectionState(viewport),
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

    fun cancelTransformPreview() {
        val selection = mutableUiState.value.selection ?: return
        mutableUiState.value = mutableUiState.value.copy(
            selection = selection.copy(previewStrokes = null, previewFrame = null),
        )
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

    fun selectPage(index: Int) = launchOperation(NotebookSaveState.IDLE) {
        saveCoordinator.flush()
        val document = requireDocument()
        require(index in document.pages.indices) { "Page index is outside the notebook" }
        publishDocument(document, index)
    }

    suspend fun awaitIdle() {
        while (true) {
            val operation = latestOperation
            val brushUpdate = latestBrushUpdate
            operation?.join()
            saveCoordinator.awaitIdle()
            brushUpdate?.join()
            if (operation === latestOperation && brushUpdate === latestBrushUpdate) return
        }
    }

    suspend fun flush() {
        saveCoordinator.flush()
    }

    fun requestFlush() {
        scope.launch {
            try {
                saveCoordinator.flush()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // onFailure has already retained the pending batch and exposed the error state.
            }
        }
    }

    suspend fun close() {
        awaitIdle()
        saveCoordinator.cancel()
        providedScope?.cancel()
    }

    override fun onCleared() {
        saveCoordinator.cancel()
        super.onCleared()
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
        var candidate = document
        while (true) {
            saveCoordinator.flush()
            val revision = mutableUiState.value.uiRevision
            candidate = repository.loadExisting(candidate.key) ?: candidate
            val safeIndex = activePageIndex.coerceIn(candidate.pages.indices)
            val page = candidate.pages[safeIndex]
            val strokes = repository.loadStrokes(candidate.key, page.id)
            var published = false
            mutableUiState.update { current ->
                if (current.uiRevision != revision || saveCoordinator.hasPendingEdits) {
                    current
                } else {
                    published = true
                    current.copy(
                        document = candidate,
                        activePageIndex = safeIndex,
                        strokes = strokes,
                        pendingConfirmation = pendingConfirmation,
                        selection = null,
                        clipboardHasContent = clipboard.hasContent && current.canvasViewport != null,
                    )
                }
            }
            if (published) return
        }
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
        mutableUiState.update { state ->
            if (revision < state.durableRevision) state else state.copy(
                saveState = NotebookSaveState.SAVING,
                errorMessage = null,
            )
        }
    }

    private fun handleSaved(revision: Long) {
        mutableUiState.update { state ->
            if (revision < state.durableRevision) {
                state
            } else {
                val durableRevision = maxOf(state.durableRevision, revision)
                state.copy(
                    durableRevision = durableRevision,
                    saveState = if (durableRevision >= state.uiRevision && !saveCoordinator.hasPendingEdits) {
                        NotebookSaveState.SAVED
                    } else {
                        NotebookSaveState.SAVING
                    },
                    errorMessage = null,
                )
            }
        }
    }

    private fun handleSaveFailure(revision: Long, error: Exception) {
        mutableUiState.update { state ->
            if (revision < state.durableRevision) state else state.copy(
                saveState = NotebookSaveState.ERROR,
                errorMessage = error.message ?: error::class.simpleName,
            )
        }
    }

    private fun NotebookDocument.replacePage(changedPage: com.putnamworkbook.offline.notebook.model.NotebookPage): NotebookDocument =
        copy(pages = pages.map { if (it.id == changedPage.id) changedPage else it })

    private fun commitReplacement(
        originals: List<StrokeRecord>,
        replacements: List<StrokeRecord>,
        viewport: CanvasViewport,
        frame: OrientedSelectionFrame,
    ) {
        commitOptimisticEdit(
            NotebookEdit(
                NotebookEditGroup(
                    UUID.randomUUID().toString(),
                    replacements.map { it.id },
                    originals.map { it.id },
                ),
                replacements,
            ),
            selectionAfter = replacements.selectionState(viewport, frame),
        )
    }

    private fun commitPreviewedReplacement(viewport: CanvasViewport) {
        val selection = mutableUiState.value.selection ?: return
        val replacements = selection.previewStrokes ?: return
        val frame = selection.previewFrame ?: return
        val originals = selectedStrokes()
        if (originals.isEmpty() || replacements.isEmpty()) return
        commitReplacement(originals, replacements, viewport, frame)
    }

    private fun commitOptimisticEdit(
        edit: NotebookEdit,
        selectionAfter: NotebookSelectionState? = mutableUiState.value.selection,
    ) {
        val state = mutableUiState.value
        val document = requireNotNull(state.document) { "Open a problem before editing its notebook" }
        val page = document.pages[state.activePageIndex]
        val revision = saveCoordinator.enqueue(page.id, edit)
        mutableUiState.update { current ->
            val currentDocument = requireNotNull(current.document) {
                "Open a problem before editing its notebook"
            }
            val pageIndex = currentDocument.pages.indexOfFirst { it.id == page.id }
            check(pageIndex >= 0) { "The edited notebook page is no longer available" }
            val changedPage = NotebookReducer.applyEdits(currentDocument.pages[pageIndex], listOf(edit))
            val deactivated = edit.group.deactivatedStrokeIds.toSet()
            val changedStrokes = current.strokes.filterNot { it.id in deactivated } + edit.newStrokes
            current.copy(
                document = currentDocument.replacePage(changedPage),
                strokes = changedStrokes,
                selection = selectionAfter,
                clipboardHasContent = clipboard.hasContent,
                saveState = if (current.durableRevision >= revision && !saveCoordinator.hasPendingEdits) {
                    NotebookSaveState.SAVED
                } else {
                    NotebookSaveState.SAVING
                },
                errorMessage = null,
                uiRevision = maxOf(current.uiRevision, revision),
            )
        }
    }

    private fun selectedStrokes(): List<StrokeRecord> {
        val ids = mutableUiState.value.selection?.selectedStrokeIds ?: return emptyList()
        return mutableUiState.value.strokes.filter { it.id in ids }
    }

    private fun updatePreview(
        replacements: List<StrokeRecord>,
        frame: OrientedSelectionFrame,
    ) {
        val selection = mutableUiState.value.selection ?: return
        mutableUiState.value = mutableUiState.value.copy(
            selection = selection.copy(previewStrokes = replacements, previewFrame = frame),
        )
    }

    private fun List<StrokeRecord>.selectionState(
        viewport: CanvasViewport,
        frame: OrientedSelectionFrame? = null,
    ): NotebookSelectionState? {
        val bounds = selectionBounds(viewport) ?: return null
        return NotebookSelectionState(
            selectedStrokeIds = mapTo(linkedSetOf()) { it.id },
            bounds = bounds,
            frame = frame ?: bounds.toOrientedFrame(viewport),
        )
    }

    private fun NormalizedRect.center(): NormalizedPoint = NormalizedPoint(
        x = (left + right) / 2f,
        y = (top + bottom) / 2f,
    )
}

private data class BrushPersistenceFailure(
    val previousSaveState: NotebookSaveState,
    val previousErrorMessage: String?,
    val message: String?,
)
