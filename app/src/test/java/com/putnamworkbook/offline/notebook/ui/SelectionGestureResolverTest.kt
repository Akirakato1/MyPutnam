package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionGestureResolverTest {
    @Test
    fun dragOutsideExistingSelectionStartsReplacementMarquee() {
        val selectionBounds = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)

        assertEquals(
            SelectionDragIntent.MARQUEE,
            resolveSelectionDragIntent(
                selectionBounds = selectionBounds,
                point = NormalizedPoint(0.1f, 0.1f),
                isCorner = false,
            ),
        )
    }

    @Test
    fun dragInsideSelectionMovesAndCornerDragScales() {
        val selectionBounds = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)

        assertEquals(
            SelectionDragIntent.MOVE,
            resolveSelectionDragIntent(selectionBounds, NormalizedPoint(0.5f, 0.5f), isCorner = false),
        )
        assertEquals(
            SelectionDragIntent.SCALE,
            resolveSelectionDragIntent(selectionBounds, NormalizedPoint(0.4f, 0.4f), isCorner = true),
        )
    }
}
