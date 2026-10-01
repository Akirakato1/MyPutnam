package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeGeometryTest {
    private val marquee = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)

    @Test
    fun partialCrossingTouchingAndEnclosingSegmentsIntersect() {
        assertTrue(stroke("cross", 3f, 0.1f to 0.5f, 0.9f to 0.5f).intersects(marquee))
        assertTrue(stroke("touch", 3f, 0.1f to 0.4f, 0.4f to 0.4f).intersects(marquee))
        assertTrue(stroke("inside", 3f, 0.45f to 0.45f, 0.55f to 0.55f).intersects(marquee))
        assertFalse(stroke("outside", 3f, 0.1f to 0.1f, 0.2f to 0.2f).intersects(marquee))
    }

    @Test
    fun onePointThickBrushAndDegenerateMarqueeAreHitTested() {
        assertTrue(stroke("point", 3f, 0.5f to 0.5f).intersects(marquee))
        assertTrue(stroke("thick", 100f, 0.2f to 0.35f, 0.8f to 0.35f).intersects(marquee))
        assertTrue(
            stroke("degenerate", 3f, 0.1f to 0.5f, 0.9f to 0.5f)
                .intersects(NormalizedRect(0.5f, 0.5f, 0.5f, 0.5f)),
        )
    }

    @Test
    fun selectionBoundsIncludeRenderedBrushWidthAndClampToPage() {
        val bounds = listOf(
            stroke("a", 20f, 0.005f to 0.2f),
            stroke("b", 20f, 0.8f to 0.995f),
        ).selectionBounds()!!

        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(0.19f, bounds.top, 0.0001f)
        assertEquals(0.81f, bounds.right, 0.0001f)
        assertEquals(1f, bounds.bottom, 0.0001f)
        assertEquals(null, emptyList<StrokeRecord>().selectionBounds())
    }

    private fun stroke(id: String, width: Float, vararg points: Pair<Float, Float>) = StrokeRecord(
        id = id,
        brush = BrushSpec(width = width),
        samples = points.mapIndexed { index, point ->
            StrokeSample(point.first, point.second, index.toLong(), 0.5f)
        },
    )
}
