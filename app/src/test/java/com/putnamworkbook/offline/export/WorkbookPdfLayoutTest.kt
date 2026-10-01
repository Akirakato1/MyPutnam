package com.putnamworkbook.offline.export

import com.putnamworkbook.offline.data.content.HintContent
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.user.ProblemProgress
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.HintStage
import com.putnamworkbook.offline.domain.model.ProblemStatus
import com.putnamworkbook.offline.domain.model.ReferenceTab
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbookPdfLayoutTest {
    private val key = ProblemKey(2025, "A1")

    @Test
    fun optionalSectionsNeverIncludeUnrevealedMaterial() {
        val content = content()
        val progress = progress(revealedHints = 2, solutionRevealed = false)

        val sections = WorkbookPdfLayout.referenceSections(
            content,
            progress,
            WorkbookExportOptions(includeRevealedHints = true, includeRevealedSolution = true),
        )

        assertEquals(
            listOf("Statement", "Hint 1: Hint 1", "Hint 2: Hint 2"),
            sections.map { it.title },
        )
        assertEquals(listOf("statement.html", "hint-1.html", "hint-2.html"), sections.map { it.assetPath })
        val afterSolutionReveal = WorkbookPdfLayout.referenceSections(
            content,
            progress.copy(solutionRevealed = true),
            WorkbookExportOptions(includeRevealedHints = false, includeRevealedSolution = true),
        )
        assertEquals(listOf("Statement", "Solution"), afterSolutionReveal.map { it.title })
    }

    @Test
    fun notebookPlanPreservesPageOrderAndPaperStyles() {
        val document = NotebookDocument(
            key = key,
            pages = listOf(
                NotebookPage("first", PaperStyle.BLANK),
                NotebookPage("second", PaperStyle.SQUARE_GRID),
            ),
        )

        assertEquals(
            listOf(
                NotebookPagePlan(0, "first", PaperStyle.BLANK),
                NotebookPagePlan(1, "second", PaperStyle.SQUARE_GRID),
            ),
            WorkbookPdfLayout.notebookPages(document),
        )
    }

    @Test
    fun normalizedInkScalesInsidePrintableBoundsWithoutClipping() {
        val box = WorkbookPdfLayout.notebookBox(pageWidth = 595f, pageHeight = 842f)
        val start = WorkbookPdfLayout.mapInkSample(StrokeSample(0f, 0f, 0L, null), box, 12f)
        val end = WorkbookPdfLayout.mapInkSample(StrokeSample(1f, 1f, 1L, null), box, 12f)
        val thinQuarter = WorkbookPdfLayout.mapInkSample(StrokeSample(0.25f, 0.25f, 1L, null), box, 1f)
        val thickQuarter = WorkbookPdfLayout.mapInkSample(StrokeSample(0.25f, 0.25f, 1L, null), box, 12f)

        assertTrue(start.x > box.left)
        assertTrue(start.y > box.top)
        assertTrue(end.x < box.right)
        assertTrue(end.y < box.bottom)
        assertEquals(box.left + 7f, start.x, 0.001f)
        assertEquals(box.bottom - 7f, end.y, 0.001f)
        assertEquals(thinQuarter, thickQuarter)
    }

    @Test
    fun printRasterGeometryUsesAFixedCssViewportAndTwoPixelsPerPdfPoint() {
        val geometry = PrintRasterGeometry(outputWidthPixels = 1_046)

        assertEquals(860, geometry.layoutWidthPixels)
        assertEquals(2_092, geometry.outputHeight(1_720))
        assertEquals(860f, geometry.layoutOffset(1_046), 0.001f)
    }

    private fun content() = ProblemContent(
        summary = ProblemSummary(key, 1),
        statementAssetPath = "statement.html",
        statementSha256 = "a",
        hints = (1..4).map { index ->
            HintContent(index, HintStage.entries[index - 1], "Hint $index", "hint-$index.html", "h$index")
        },
        prerequisites = emptyList(),
        solutionAssetPath = "solution.html",
        solutionSha256 = "s",
        attribution = emptyList(),
    )

    private fun progress(revealedHints: Int, solutionRevealed: Boolean) = ProblemProgress(
        key = key,
        status = ProblemStatus.IN_PROGRESS,
        revealedHintCount = revealedHints,
        solutionRevealed = solutionRevealed,
        notebookPageCount = 1,
        activePageIndex = 0,
        activeReferenceTab = ReferenceTab.PROBLEM,
        lastOpenedAt = 1L,
    )
}
