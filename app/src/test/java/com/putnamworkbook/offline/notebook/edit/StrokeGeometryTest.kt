package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeGeometryTest {
    private val marquee = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f)
    private val square = CanvasViewport(1_000f, 1_000f)

    @Test
    fun partialCrossingTouchingAndEnclosingSegmentsIntersect() {
        assertTrue(stroke("cross", 3f, 0.1f to 0.5f, 0.9f to 0.5f).intersects(marquee, square))
        assertTrue(stroke("touch", 3f, 0.1f to 0.4f, 0.4f to 0.4f).intersects(marquee, square))
        assertTrue(stroke("inside", 3f, 0.45f to 0.45f, 0.55f to 0.55f).intersects(marquee, square))
        assertFalse(stroke("outside", 3f, 0.1f to 0.1f, 0.2f to 0.2f).intersects(marquee, square))
    }

    @Test
    fun onePointThickBrushAndDegenerateMarqueeAreHitTested() {
        assertTrue(stroke("point", 3f, 0.5f to 0.5f).intersects(marquee, square))
        assertTrue(stroke("thick", 100f, 0.2f to 0.35f, 0.8f to 0.35f).intersects(marquee, square))
        assertTrue(
            stroke("degenerate", 3f, 0.1f to 0.5f, 0.9f to 0.5f)
                .intersects(NormalizedRect(0.5f, 0.5f, 0.5f, 0.5f), square),
        )
    }

    @Test
    fun renderedBrushEdgeUsesTheActualPortraitAndLandscapeViewport() {
        val portrait = CanvasViewport(320f, 640f)
        val landscape = CanvasViewport(1_200f, 600f)
        val portraitTouch = stroke(
            "portrait-touch",
            12f,
            (0.4f - 6f / portrait.widthPixels) to 0.45f,
            (0.4f - 6f / portrait.widthPixels) to 0.55f,
        )
        val landscapeTouch = stroke(
            "landscape-touch",
            12f,
            0.45f to (0.4f - 6f / landscape.heightPixels),
            0.55f to (0.4f - 6f / landscape.heightPixels),
        )
        val portraitMiss = stroke(
            "portrait-miss",
            12f,
            (0.4f - 6.5f / portrait.widthPixels) to 0.45f,
            (0.4f - 6.5f / portrait.widthPixels) to 0.55f,
        )

        assertTrue(portraitTouch.intersects(marquee, portrait))
        assertTrue(landscapeTouch.intersects(marquee, landscape))
        assertFalse(portraitMiss.intersects(marquee, portrait))
    }

    @Test
    fun roundBrushRadiusRejectsADiagonalCornerNearMiss() {
        val viewport = CanvasViewport(1_000f, 1_000f)
        val nearCornerButOutsideRadius = stroke("corner-miss", 12f, 0.394f to 0.394f)

        assertFalse(nearCornerButOutsideRadius.intersects(marquee, viewport))
    }

    @Test
    fun selectionBoundsIncludeRenderedBrushWidthAndClampToPage() {
        val bounds = listOf(
            stroke("a", 20f, 0.005f to 0.2f),
            stroke("b", 20f, 0.8f to 0.995f),
        ).selectionBounds(square)!!

        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(0.19f, bounds.top, 0.0001f)
        assertEquals(0.81f, bounds.right, 0.0001f)
        assertEquals(1f, bounds.bottom, 0.0001f)
        assertEquals(null, emptyList<StrokeRecord>().selectionBounds(square))
    }

    @Test
    fun selectionBoundsUseIndependentPixelRadiiAndClampToThePage() {
        val portrait = CanvasViewport(320f, 640f)
        val bounds = listOf(stroke("a", 20f, 0.02f to 0.02f)).selectionBounds(portrait)!!

        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(0.004375f, bounds.top, 0.0001f)
        assertEquals(0.05125f, bounds.right, 0.0001f)
        assertEquals(0.035625f, bounds.bottom, 0.0001f)
    }

    @Test
    fun viewportRejectsNonFiniteAndNonPositiveDimensions() {
        listOf(
            0f to 100f,
            -1f to 100f,
            Float.NaN to 100f,
            100f to Float.POSITIVE_INFINITY,
        ).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) {
                CanvasViewport(width, height)
            }
        }
    }

    private fun stroke(id: String, width: Float, vararg points: Pair<Float, Float>) = StrokeRecord(
        id = id,
        brush = BrushSpec(width = width),
        samples = points.mapIndexed { index, point ->
            StrokeSample(point.first, point.second, index.toLong(), 0.5f)
        },
    )
}
