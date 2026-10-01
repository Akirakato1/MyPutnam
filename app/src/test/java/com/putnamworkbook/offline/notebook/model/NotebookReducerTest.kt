package com.putnamworkbook.offline.notebook.model

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookReducerTest {
    @Test
    fun replacementEditDeactivatesSourcesAndUndoRedoMovesTheWholeGroup() {
        val page = drawnPage("left", "right")
        val replacementLeft = stroke("moved-left", 0.3f)
        val replacementRight = stroke("moved-right", 0.7f)
        val edit = NotebookEdit(
            group = NotebookEditGroup(
                id = "move-both",
                activatedStrokeIds = listOf(replacementLeft.id, replacementRight.id),
                deactivatedStrokeIds = listOf("left", "right"),
            ),
            newStrokes = listOf(replacementLeft, replacementRight),
        )

        val moved = NotebookReducer.applyEdits(page, listOf(edit))

        assertEquals(listOf(false, false, true, true), moved.strokes.map { it.active })
        assertEquals(2, moved.appliedEditCount)

        val undone = NotebookReducer.undo(moved)
        assertEquals(listOf(true, true, false, false), undone.strokes.map { it.active })
        assertEquals(1, undone.appliedEditCount)

        val redone = NotebookReducer.redo(undone)
        assertEquals(moved, redone)
    }

    @Test
    fun newEditAfterUndoTruncatesRedoHistoryButKeepsImmutableStrokeReference() {
        val first = NotebookReducer.applyEdits(
            drawnPage("original"),
            listOf(
                NotebookEdit(
                    NotebookEditGroup("delete", emptyList(), listOf("original")),
                    emptyList(),
                ),
            ),
        )
        val undone = NotebookReducer.undo(first)

        val branched = NotebookReducer.applyEdits(
            undone,
            listOf(
                NotebookEdit(
                    NotebookEditGroup("replacement-edit", listOf("replacement"), listOf("original")),
                    listOf(stroke("replacement", 0.5f)),
                ),
            ),
        )

        assertEquals(listOf("initial", "replacement-edit"), branched.editHistory.map { it.id })
        assertEquals(listOf("original", "replacement"), branched.strokes.map { it.strokeId })
        assertEquals(listOf(false, true), branched.strokes.map { it.active })
        assertEquals(branched, NotebookReducer.redo(branched))
    }

    @Test
    fun undoAndRedoAtHistoryBoundariesAreNoOps() {
        val page = drawnPage("stroke")

        assertEquals(page, NotebookReducer.redo(page))
        val undone = NotebookReducer.undo(page)
        assertFalse(undone.strokes.single().active)
        assertEquals(undone, NotebookReducer.undo(undone))
        assertTrue(NotebookReducer.redo(undone).strokes.single().active)
    }

    private fun drawnPage(vararg ids: String): NotebookPage = NotebookPage(
        id = "page",
        paperStyle = PaperStyle.BLANK,
        strokes = ids.map { StrokeReference(it, active = true) },
        editHistory = listOf(NotebookEditGroup("initial", ids.toList(), emptyList())),
        appliedEditCount = 1,
    )

    private fun stroke(id: String, x: Float) = StrokeRecord(
        id = id,
        brush = BrushSpec(),
        samples = listOf(StrokeSample(x, 0.5f, 0L, null)),
    )
}
