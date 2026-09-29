package com.putnamworkbook.offline.content.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

data class OriginalPdfUiState(
    val isLoading: Boolean = false,
    val pageCount: Int = 0,
    val currentPageIndex: Int = 0,
    val visiblePage: Bitmap? = null,
    val errorMessage: String? = null,
)

class OriginalPdfViewModel(
    private val repository: OriginalPdfRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableUiState = MutableStateFlow(OriginalPdfUiState())
    private var pdfFile: File? = null
    private var operation: Job? = null

    val uiState: StateFlow<OriginalPdfUiState> = mutableUiState.asStateFlow()

    fun open(assetPath: String, sha256: String) {
        operation?.cancel()
        operation = scope.launch {
            replaceState(OriginalPdfUiState(isLoading = true))
            try {
                val opened = withContext(workerDispatcher) {
                    val file = repository.materialize(assetPath, sha256).getOrThrow()
                    file to readPageCount(file)
                }
                pdfFile = opened.first
                mutableUiState.value = OriginalPdfUiState(
                    isLoading = true,
                    pageCount = opened.second,
                )
                renderPage(0)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                replaceState(
                    OriginalPdfUiState(
                        errorMessage = error.message ?: "Original paper could not be opened",
                    ),
                )
            }
        }
    }

    fun selectPage(index: Int) {
        val file = pdfFile ?: return
        if (index !in 0 until mutableUiState.value.pageCount || index == mutableUiState.value.currentPageIndex) return
        operation?.cancel()
        operation = scope.launch {
            mutableUiState.value = mutableUiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val bitmap = withContext(workerDispatcher) { render(file, index) }
                replaceVisiblePage(index, bitmap)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Original paper page could not be rendered",
                )
            }
        }
    }

    suspend fun awaitIdle() {
        operation?.join()
    }

    fun close() {
        operation?.cancel()
        mutableUiState.value.visiblePage?.recycle()
        scope.cancel()
    }

    private suspend fun renderPage(index: Int) {
        val file = requireNotNull(pdfFile)
        val bitmap = withContext(workerDispatcher) { render(file, index) }
        replaceVisiblePage(index, bitmap)
    }

    private fun replaceVisiblePage(index: Int, bitmap: Bitmap) {
        mutableUiState.value = mutableUiState.value.copy(
            isLoading = false,
            currentPageIndex = index,
            visiblePage = bitmap,
            errorMessage = null,
        )
    }

    private fun replaceState(state: OriginalPdfUiState) {
        mutableUiState.value = state
    }

    private fun readPageCount(file: File): Int = withRenderer(file) { renderer ->
        require(renderer.pageCount > 0) { "Original paper has no pages" }
        renderer.pageCount
    }

    private fun render(file: File, index: Int): Bitmap = withRenderer(file) { renderer ->
        require(index in 0 until renderer.pageCount) { "PDF page is outside the document" }
        renderer.openPage(index).use { page ->
            val scale = min(
                RENDER_SCALE,
                min(MAX_BITMAP_WIDTH.toFloat() / page.width, MAX_BITMAP_HEIGHT.toFloat() / page.height),
            )
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    private inline fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use(block)
        }

    private companion object {
        const val RENDER_SCALE = 2f
        const val MAX_BITMAP_WIDTH = 1800
        const val MAX_BITMAP_HEIGHT = 2600
    }
}
