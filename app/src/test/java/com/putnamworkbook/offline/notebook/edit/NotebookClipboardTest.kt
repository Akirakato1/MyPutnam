package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookClipboardTest {
    private val viewport = CanvasViewport(1_000f, 1_000f)

    @Test
    fun copiedVectorsPasteWithFreshIdsAndAClampedOffset() {
        val clipboard = NotebookClipboard()
        val source = listOf(stroke("source", 0.2f, 0.2f), stroke("edge", 0.99f, 0.99f))
        clipboard.copy(source)
        var next = 0

        val pasted = clipboard.createPaste(emptyList(), viewport) { "paste-${next++}" }

        assertTrue(clipboard.hasContent)
        assertEquals(2, pasted.size)
        assertNotEquals(source.map { it.id }, pasted.map { it.id })
        assertTrue(pasted.flatMap { it.samples }.all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals(source.map { it.brush }, pasted.map { it.brush })
    }

    @Test
    fun emptyClipboardProducesNoPasteAndCopyCanBeReusedAcrossContexts() {
        val clipboard = NotebookClipboard()
        assertFalse(clipboard.hasContent)
        assertTrue(clipboard.createPaste(emptyList(), viewport) { "unused" }.isEmpty())

        clipboard.copy(listOf(stroke("source", 0.4f, 0.4f)))
        val first = clipboard.createPaste(emptyList(), viewport) { "page-a" }
        val second = clipboard.createPaste(first, viewport) { "problem-b" }

        assertEquals("page-a", first.single().id)
        assertEquals("problem-b", second.single().id)
    }

    private fun stroke(id: String, x: Float, y: Float) = StrokeRecord(
        id,
        BrushSpec(),
        listOf(StrokeSample(x, y, 0L, 0.5f)),
    )
}
