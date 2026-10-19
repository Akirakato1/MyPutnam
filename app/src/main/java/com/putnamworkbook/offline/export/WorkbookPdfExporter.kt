package com.putnamworkbook.offline.export

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.webkit.WebView
import android.util.DisplayMetrics
import com.putnamworkbook.offline.content.ui.LocalContentClient
import com.putnamworkbook.offline.content.ui.ContentResourceResolver
import com.putnamworkbook.offline.data.content.ContentResourceRef
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.user.ProgressRepository
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.storage.NotebookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface ExportResult {
    data class Success(val pageCount: Int) : ExportResult
    data class Failure(val message: String) : ExportResult
}

data class PrintRasterGeometry(
    val outputWidthPixels: Int,
    val layoutWidthPixels: Int = PRINT_LAYOUT_WIDTH_CSS_PIXELS,
) {
    init {
        require(outputWidthPixels > 0)
        require(layoutWidthPixels > 0)
    }

    private val scale = outputWidthPixels.toDouble() / layoutWidthPixels.toDouble()

    fun outputHeight(layoutHeightPixels: Int): Int =
        ceil(layoutHeightPixels.toDouble() * scale).toInt()

    fun layoutOffset(outputOffsetPixels: Int): Float = (outputOffsetPixels / scale).toFloat()

    fun canvasScale(): Float = scale.toFloat()

    companion object {
        const val PRINT_LAYOUT_WIDTH_CSS_PIXELS = 860
    }
}

interface ContentRasterizer {
    /** The callback owns each bitmap only for the duration of the call; the rasterizer recycles it afterward. */
    suspend fun render(
        assetPath: String,
        widthPixels: Int,
        firstSliceHeightPixels: Int,
        continuationSliceHeightPixels: Int,
        consumeSlice: (Bitmap) -> Unit,
    ): Int

    suspend fun render(
        resource: ContentResourceRef,
        widthPixels: Int,
        firstSliceHeightPixels: Int,
        continuationSliceHeightPixels: Int,
        consumeSlice: (Bitmap) -> Unit,
    ): Int = render(resource.memberPath, widthPixels, firstSliceHeightPixels, continuationSliceHeightPixels, consumeSlice)
}

class WorkbookPdfExporter(
    private val contentRepository: ContentRepository,
    private val progressRepository: ProgressRepository,
    private val notebookRepository: NotebookRepository,
    private val contentRasterizer: ContentRasterizer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun export(
        key: ProblemKey,
        options: WorkbookExportOptions,
        outputStream: OutputStream,
    ): ExportResult = withContext(ioDispatcher) {
        val pdf = PdfDocument()
        try {
            val content = contentRepository.problem(key)
                ?: throw IOException("Problem ${key.canonical} is unavailable")
            val progress = progressRepository.observe(key).first()
            val notebook = notebookRepository.loadExportSnapshot(key)
                ?: throw IOException("Open this problem once before exporting its workbook")
            val sections = WorkbookPdfLayout.referenceSections(content, progress, options)
            var pdfPageNumber = 0

            sections.forEachIndexed { sectionIndex, section ->
                val firstTop = if (sectionIndex == 0) STATEMENT_CONTENT_TOP else SECTION_CONTENT_TOP
                var sectionSliceIndex = 0
                val renderedSliceCount = contentRasterizer.render(
                    resource = section.resource,
                    widthPixels = RASTER_WIDTH_PIXELS,
                    firstSliceHeightPixels = pointsToPixels(CONTENT_BOTTOM - firstTop),
                    continuationSliceHeightPixels = pointsToPixels(CONTENT_BOTTOM - SECTION_CONTENT_TOP),
                ) { bitmap ->
                    pdfPageNumber += 1
                    val page = pdf.startPage(pageInfo(pdfPageNumber))
                    try {
                        drawReferencePage(
                            canvas = page.canvas,
                            summary = content.summary,
                            section = section,
                            isStatement = sectionIndex == 0,
                            isContinuation = sectionSliceIndex > 0,
                            bitmap = bitmap,
                            pageNumber = pdfPageNumber,
                        )
                    } finally {
                        pdf.finishPage(page)
                    }
                    sectionSliceIndex += 1
                }
                if (renderedSliceCount == 0) throw IOException("Rendered content is empty: ${section.assetPath}")
            }

            notebook.pages.forEachIndexed { index, notebookPage ->
                pdfPageNumber += 1
                val page = pdf.startPage(pageInfo(pdfPageNumber))
                try {
                    drawNotebookPage(
                        canvas = page.canvas,
                        summary = content.summary,
                        paperStyle = notebookPage.paperStyle,
                        strokes = notebookPage.strokes,
                        workbookPage = index + 1,
                        workbookPageCount = notebook.pages.size,
                        pdfPageNumber = pdfPageNumber,
                    )
                } finally {
                    pdf.finishPage(page)
                }
            }
            val checkedOutput = FailureTrackingOutputStream(outputStream)
            pdf.writeTo(checkedOutput)
            checkedOutput.flush()
            checkedOutput.throwIfFailed()
            ExportResult.Success(pdfPageNumber)
        } catch (error: CancellationException) {
            throw error
        } catch (error: OutOfMemoryError) {
            ExportResult.Failure("Not enough memory to export this workbook")
        } catch (error: Exception) {
            ExportResult.Failure(error.message?.take(240)?.ifBlank { "PDF export failed" } ?: "PDF export failed")
        } finally {
            pdf.close()
        }
    }

    private fun drawReferencePage(
        canvas: Canvas,
        summary: ProblemSummary,
        section: ExportSection,
        isStatement: Boolean,
        isContinuation: Boolean,
        bitmap: Bitmap,
        pageNumber: Int,
    ) {
        canvas.drawColor(Color.WHITE)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 35, 53)
            textSize = if (isStatement && !isContinuation) 22f else 16f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(71, 85, 105)
            textSize = 11f
        }
        val top = if (isStatement && !isContinuation) {
            canvas.drawText("${summary.collectionTitle} — ${summary.setTitle} ${summary.label}", PAGE_MARGIN, 46f, titlePaint)
            canvas.drawText(section.title, PAGE_MARGIN, 70f, labelPaint)
            canvas.drawLine(PAGE_MARGIN, 80f, PAGE_RIGHT, 80f, dividerPaint())
            STATEMENT_CONTENT_TOP
        } else {
            val heading = if (isContinuation) "${section.title} (continued)" else section.title
            canvas.drawText("${summary.setTitle} ${summary.label} — $heading", PAGE_MARGIN, 42f, titlePaint)
            canvas.drawLine(PAGE_MARGIN, 54f, PAGE_RIGHT, 54f, dividerPaint())
            SECTION_CONTENT_TOP
        }
        val widthScale = CONTENT_WIDTH / bitmap.width.toFloat()
        val availableHeight = CONTENT_BOTTOM - top
        val scale = min(widthScale, availableHeight / bitmap.height.toFloat())
        val destination = RectF(
            PAGE_MARGIN,
            top,
            PAGE_MARGIN + bitmap.width * scale,
            top + bitmap.height * scale,
        )
        canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        drawFooter(canvas, pageNumber)
    }

    private fun drawNotebookPage(
        canvas: Canvas,
        summary: ProblemSummary,
        paperStyle: PaperStyle,
        strokes: List<StrokeRecord>,
        workbookPage: Int,
        workbookPageCount: Int,
        pdfPageNumber: Int,
    ) {
        canvas.drawColor(Color.WHITE)
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 41, 59)
            textSize = 14f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        canvas.drawText(
            "${summary.setTitle} ${summary.label} — Workbook page $workbookPage of $workbookPageCount",
            PAGE_MARGIN,
            36f,
            headingPaint,
        )
        val box = WorkbookPdfLayout.notebookBox()
        val paperRect = RectF(box.left, box.top, box.right, box.bottom)
        canvas.drawRect(paperRect, Paint().apply { color = Color.WHITE })
        if (paperStyle == PaperStyle.SQUARE_GRID) drawGrid(canvas, box)
        canvas.drawRect(
            paperRect,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(203, 213, 225)
                style = Paint.Style.STROKE
                strokeWidth = 0.8f
            },
        )
        strokes.forEach { stroke -> drawStroke(canvas, box, stroke) }
        drawFooter(canvas, pdfPageNumber)
    }

    private fun drawGrid(canvas: Canvas, box: ExportBox) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 227, 235)
            strokeWidth = 0.55f
        }
        var x = box.left + GRID_SPACING
        while (x < box.right) {
            canvas.drawLine(x, box.top, x, box.bottom, paint)
            x += GRID_SPACING
        }
        var y = box.top + GRID_SPACING
        while (y < box.bottom) {
            canvas.drawLine(box.left, y, box.right, y, paint)
            y += GRID_SPACING
        }
    }

    private fun drawStroke(canvas: Canvas, box: ExportBox, stroke: StrokeRecord) {
        val width = WorkbookPdfLayout.pdfStrokeWidth(stroke.brush.width)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.brush.colorArgb.toInt()
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val points = stroke.samples.map { WorkbookPdfLayout.mapInkSample(it, box, width) }
        if (points.size == 1) {
            canvas.drawCircle(points.single().x, points.single().y, width / 2f, paint.apply { style = Paint.Style.FILL })
            return
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { point -> lineTo(point.x, point.y) }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawFooter(canvas: Canvas, pageNumber: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 9f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("Offline Math Workbook — PDF page $pageNumber", PAGE_RIGHT, 824f, paint)
    }

    private fun dividerPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(203, 213, 225)
        strokeWidth = 1f
    }

    private fun pageInfo(pageNumber: Int) = PdfDocument.PageInfo.Builder(
        WorkbookPdfLayout.PAGE_WIDTH.toInt(),
        WorkbookPdfLayout.PAGE_HEIGHT.toInt(),
        pageNumber,
    ).create()

    private fun pointsToPixels(points: Float): Int = (points * RASTER_SCALE).roundToInt()

    private companion object {
        const val PAGE_MARGIN = WorkbookPdfLayout.PAGE_MARGIN
        const val PAGE_RIGHT = WorkbookPdfLayout.PAGE_WIDTH - PAGE_MARGIN
        const val CONTENT_WIDTH = WorkbookPdfLayout.PAGE_WIDTH - PAGE_MARGIN * 2f
        const val CONTENT_BOTTOM = WorkbookPdfLayout.PAGE_HEIGHT - PAGE_MARGIN
        const val STATEMENT_CONTENT_TOP = 92f
        const val SECTION_CONTENT_TOP = 66f
        const val RASTER_SCALE = 2f
        const val RASTER_WIDTH_PIXELS = (CONTENT_WIDTH * RASTER_SCALE).toInt()
        const val GRID_SPACING = 18f
    }
}

private class FailureTrackingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    private var failure: IOException? = null

    override fun write(value: Int) {
        writeSafely { delegate.write(value) }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        writeSafely { delegate.write(bytes, offset, length) }
    }

    override fun flush() {
        writeSafely { delegate.flush() }
    }

    fun throwIfFailed() {
        failure?.let { throw it }
    }

    private inline fun writeSafely(write: () -> Unit) {
        if (failure != null) return
        try {
            write()
        } catch (error: IOException) {
            failure = error
        }
    }
}

class WebViewContentRasterizer(
    context: Context,
    assetContext: Context = context,
    private val contentResourceResolver: ContentResourceResolver? = null,
) : ContentRasterizer {
    private val activity = context.findActivity()
        ?: throw IllegalArgumentException("PDF HTML rendering requires an Activity context")
    private val assetContext = assetContext.applicationContext ?: assetContext

    override suspend fun render(
        assetPath: String,
        widthPixels: Int,
        firstSliceHeightPixels: Int,
        continuationSliceHeightPixels: Int,
        consumeSlice: (Bitmap) -> Unit,
    ): Int = withContext(Dispatchers.Main.immediate) {
        renderInternal(assetPath, null, widthPixels, firstSliceHeightPixels, continuationSliceHeightPixels, consumeSlice)
    }

    override suspend fun render(
        resource: ContentResourceRef,
        widthPixels: Int,
        firstSliceHeightPixels: Int,
        continuationSliceHeightPixels: Int,
        consumeSlice: (Bitmap) -> Unit,
    ): Int = withContext(Dispatchers.Main.immediate) {
        renderInternal(null, resource, widthPixels, firstSliceHeightPixels, continuationSliceHeightPixels, consumeSlice)
    }

    private suspend fun renderInternal(
        assetPath: String?,
        resource: ContentResourceRef?,
        widthPixels: Int,
        firstSliceHeightPixels: Int,
        continuationSliceHeightPixels: Int,
        consumeSlice: (Bitmap) -> Unit,
    ): Int {
        require(firstSliceHeightPixels > 0)
        require(continuationSliceHeightPixels > 0)
        val geometry = PrintRasterGeometry(widthPixels)
        return suspendCancellableCoroutine { continuation ->
            var finished = false
            var webView: WebView? = null

            fun dispose(view: WebView?) {
                if (view == null) return
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.destroy()
            }

            fun fail(error: Throwable) {
                if (finished) return
                finished = true
                dispose(webView)
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            val client = LocalContentClient(
                assetContext = assetContext,
                onIntegrityError = { message -> fail(IOException(message)) },
                failOnSubresourceError = true,
                onPageReady = { loadedView ->
                    loadedView.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            loadedView.post capture@{
                                if (finished || !continuation.isActive) return@capture
                                try {
                                    val widthSpec = View.MeasureSpec.makeMeasureSpec(
                                        geometry.layoutWidthPixels,
                                        View.MeasureSpec.EXACTLY,
                                    )
                                    loadedView.measure(
                                        widthSpec,
                                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                    )
                                    val layoutHeight = maxOf(
                                        1,
                                        loadedView.contentHeight,
                                        loadedView.measuredHeight,
                                    )
                                    val outputHeight = geometry.outputHeight(layoutHeight)
                                    val rasterBytes =
                                        widthPixels.toLong() * outputHeight.toLong() * ARGB_BYTES_PER_PIXEL
                                    if (rasterBytes > MAX_RASTER_BYTES) {
                                        throw IOException("Rendered content exceeds the PDF raster memory limit")
                                    }
                                    loadedView.measure(
                                        widthSpec,
                                        View.MeasureSpec.makeMeasureSpec(layoutHeight, View.MeasureSpec.EXACTLY),
                                    )
                                    loadedView.layout(0, 0, geometry.layoutWidthPixels, layoutHeight)
                                    var offset = 0
                                    var sliceCount = 0
                                    while (offset < outputHeight) {
                                        if (!continuation.isActive) return@capture
                                        val limit =
                                            if (offset == 0) firstSliceHeightPixels else continuationSliceHeightPixels
                                        val height = min(limit, outputHeight - offset)
                                        val bitmap = Bitmap.createBitmap(
                                            widthPixels,
                                            height,
                                            Bitmap.Config.ARGB_8888,
                                        )
                                        try {
                                            Canvas(bitmap).apply {
                                                drawColor(Color.WHITE)
                                                scale(geometry.canvasScale(), geometry.canvasScale())
                                                translate(0f, -geometry.layoutOffset(offset))
                                                loadedView.draw(this)
                                            }
                                            consumeSlice(bitmap)
                                            sliceCount += 1
                                        } finally {
                                            bitmap.recycle()
                                        }
                                        offset += height
                                    }
                                    finished = true
                                    dispose(loadedView)
                                    continuation.resume(sliceCount)
                                } catch (error: OutOfMemoryError) {
                                    fail(IOException("Not enough memory to render PDF content", error))
                                } catch (error: Exception) {
                                    fail(error)
                                }
                            }
                        }
                    })
                },
            )
            contentResourceResolver?.let(client::attachResolver)
            val configuration = Configuration(activity.resources.configuration).apply {
                densityDpi = DisplayMetrics.DENSITY_DEFAULT
            }
            val printContext = ContextThemeWrapper(
                activity.createConfigurationContext(configuration),
                activity.applicationInfo.theme,
            )
            val createdView = WebView(printContext).also { view ->
                view.setBackgroundColor(Color.WHITE)
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                view.translationX = -geometry.layoutWidthPixels * 2f
                client.configure(view)
                view.isVerticalScrollBarEnabled = false
                view.isHorizontalScrollBarEnabled = false
                view.settings.builtInZoomControls = false
                view.settings.displayZoomControls = false
                view.settings.loadWithOverviewMode = false
                view.settings.useWideViewPort = false
                view.settings.textZoom = 100
                view.setInitialScale(100)
            }
            webView = createdView
            val host = activity.findViewById<ViewGroup>(android.R.id.content)
                ?: throw IllegalStateException("Activity content view is unavailable")
            host.addView(createdView, ViewGroup.LayoutParams(geometry.layoutWidthPixels, 1))
            continuation.invokeOnCancellation {
                if (!finished) {
                    finished = true
                    createdView.post {
                        dispose(webView)
                    }
                }
            }
            val load = if (resource != null) client.loadResourceData(createdView, resource)
            else client.loadAssetData(createdView, checkNotNull(assetPath))
            load.onFailure(::fail)
        }
    }

    private companion object {
        const val ARGB_BYTES_PER_PIXEL = 4L
        const val MAX_RASTER_BYTES = 48L * 1024L * 1024L
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
