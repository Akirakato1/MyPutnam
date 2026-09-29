package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.data.user.SettingsRepository
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
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

class NotebookViewModel(
    private val repository: NotebookRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableUiState = MutableStateFlow(NotebookUiState())
    private val operationMutex = Mutex()
    private var currentKey: ProblemKey? = null
    private var latestOperation: Job? = null

    val uiState: StateFlow<NotebookUiState> = mutableUiState.asStateFlow()

    fun open(key: ProblemKey, initialPageIndex: Int = 0) {
        currentKey = key
        launchOperation(NotebookSaveState.LOADING) {
            val defaultPaper = settingsRepository.observe().first().defaultPaperStyle
            val document = repository.loadOrCreate(key, defaultPaper)
            publishDocument(document, activePageIndex = initialPageIndex.coerceIn(document.pages.indices))
        }
    }

    fun commitStroke(stroke: StrokeRecord) = launchSaving {
        val (key, pageId) = currentPage()
        publishDocument(repository.commitStroke(key, pageId, stroke), mutableUiState.value.activePageIndex)
    }

    fun undo() = launchSaving {
        val (key, pageId) = currentPage()
        val reference = requireDocument().pages[mutableUiState.value.activePageIndex]
            .strokes.lastOrNull { it.active } ?: return@launchSaving
        publishDocument(
            repository.setStrokeActive(key, pageId, reference.strokeId, active = false),
            mutableUiState.value.activePageIndex,
        )
    }

    fun redo() = launchSaving {
        val (key, pageId) = currentPage()
        val reference = requireDocument().pages[mutableUiState.value.activePageIndex]
            .strokes.firstOrNull { !it.active } ?: return@launchSaving
        publishDocument(
            repository.setStrokeActive(key, pageId, reference.strokeId, active = true),
            mutableUiState.value.activePageIndex,
        )
    }

    fun addPage() = launchSaving {
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
            val document = repository.clearPage(requireNotNull(currentKey), confirmation.pageId)
            publishDocument(document, mutableUiState.value.activePageIndex, pendingConfirmation = null)
        }
    }

    fun dismissConfirmation() {
        mutableUiState.value = mutableUiState.value.copy(pendingConfirmation = null)
    }

    fun reorderPage(pageId: String, newIndex: Int) = launchSaving {
        val document = requireDocument()
        val activePageId = document.pages[mutableUiState.value.activePageIndex].id
        val changed = repository.reorderPage(requireNotNull(currentKey), pageId, newIndex)
        publishDocument(changed, changed.pages.indexOfFirst { it.id == activePageId })
    }

    fun selectPage(index: Int) = launchOperation(NotebookSaveState.IDLE) {
        val document = requireDocument()
        require(index in document.pages.indices) { "Page index is outside the notebook" }
        publishDocument(document, index)
    }

    suspend fun awaitIdle() {
        while (true) {
            val operation = latestOperation ?: return
            operation.join()
            if (operation === latestOperation) return
        }
    }

    fun close() {
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
}
