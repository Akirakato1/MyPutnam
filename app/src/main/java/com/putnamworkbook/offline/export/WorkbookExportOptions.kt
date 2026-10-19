package com.putnamworkbook.offline.export

import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ContentResourceRef
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.StrokeSample

data class WorkbookExportOptions(
    val includeRevealedHints: Boolean = true,
    val includeRevealedSolution: Boolean = true,
)

data class ExportSection(
    val title: String,
    val resource: ContentResourceRef,
) {
    constructor(title: String, assetPath: String) : this(title, ContentResourceRef.legacy(assetPath, ""))
    val assetPath: String get() = resource.memberPath
}

data class NotebookPagePlan(
    val index: Int,
    val pageId: String,
    val paperStyle: PaperStyle,
)

data class ExportBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class ExportPoint(val x: Float, val y: Float)

object WorkbookPdfLayout {
    const val PAGE_WIDTH = 595f
    const val PAGE_HEIGHT = 842f
    const val PAGE_MARGIN = 36f
    const val NOTEBOOK_HEADER_BOTTOM = 58f
    private const val MAX_PDF_STROKE_WIDTH = 12f

    fun referenceSections(
        content: ProblemContent,
        progress: ProblemProgress,
        options: WorkbookExportOptions,
    ): List<ExportSection> = buildList {
        add(ExportSection("Statement", content.statement))
        if (options.includeRevealedHints) {
            content.hints.sortedBy { it.order }
                .take(progress.revealedHintCount.coerceAtLeast(0))
                .forEachIndexed { index, hint ->
                    add(ExportSection("Hint ${index + 1}: ${hint.title}", hint.resource))
                }
        }
        if (options.includeRevealedSolution && progress.solutionRevealed) {
            add(ExportSection("Solution", content.solution))
        }
    }

    fun notebookPages(document: NotebookDocument): List<NotebookPagePlan> =
        document.pages.mapIndexed { index, page -> NotebookPagePlan(index, page.id, page.paperStyle) }

    fun notebookBox(pageWidth: Float = PAGE_WIDTH, pageHeight: Float = PAGE_HEIGHT) = ExportBox(
        left = PAGE_MARGIN,
        top = NOTEBOOK_HEADER_BOTTOM,
        right = pageWidth - PAGE_MARGIN,
        bottom = pageHeight - PAGE_MARGIN,
    )

    fun pdfStrokeWidth(sourceWidth: Float): Float = sourceWidth.coerceIn(1f, 12f)

    fun mapInkSample(sample: StrokeSample, box: ExportBox, strokeWidth: Float): ExportPoint {
        require(strokeWidth.isFinite()) { "Stroke width must be finite" }
        val inset = MAX_PDF_STROKE_WIDTH / 2f + 1f
        val drawingWidth = (box.width - inset * 2f).coerceAtLeast(0f)
        val drawingHeight = (box.height - inset * 2f).coerceAtLeast(0f)
        return ExportPoint(
            x = box.left + inset + sample.x * drawingWidth,
            y = box.top + inset + sample.y * drawingHeight,
        )
    }
}
