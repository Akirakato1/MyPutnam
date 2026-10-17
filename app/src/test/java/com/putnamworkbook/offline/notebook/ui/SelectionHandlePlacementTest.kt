package com.putnamworkbook.offline.notebook.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionHandlePlacementTest {
    @Test
    fun canvasPointsMapIntoThePaneWithoutClampingToolbarOverflow() {
        val placement = NotebookCanvasPlacement(
            topLeftInPane = Offset(40f, 120f),
            size = IntSize(1_000, 600),
        )

        assertEquals(
            Offset(540f, 120f),
            placement.toPaneOffset(NormalizedPoint(0.5f, 0f)),
        )
        assertEquals(
            Offset(540f, 88f),
            placement.toPaneOffset(NormalizedPoint(0.5f, -32f / 600f)),
        )

        val pointAboveCanvas = placement.toCanvasPoint(Offset(540f, 100f))
        assertEquals(0.5f, pointAboveCanvas.x, 0.0001f)
        assertTrue(pointAboveCanvas.y < 0f)
    }
}
