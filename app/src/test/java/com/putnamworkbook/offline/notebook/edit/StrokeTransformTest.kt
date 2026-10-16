package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeTransformTest {
    private val square = CanvasViewport(1_000f, 1_000f)

    @Test
    fun moveClampsTheWholeSelectionAtEveryPageEdgeAndPreservesStrokeData() {
        val original = stroke("old", 0.2f to 0.3f, 0.4f to 0.5f)
        val moved = StrokeTransform.move(listOf(original), dx = -1f, dy = 1f, square) { "moved" }.single()

        assertEquals("moved", moved.id)
        assertEquals(original.brush, moved.brush)
        assertEquals(original.samples.map { it.pressure }, moved.samples.map { it.pressure })
        assertEquals(original.samples.map { it.elapsedMillis }, moved.samples.map { it.elapsedMillis })
        assertTrue(moved.samples.all { it.x in 0f..1f && it.y in 0f..1f })
        val bounds = listOf(moved).selectionBounds(square)!!
        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(1f, bounds.bottom, 0.0001f)
    }

    @Test
    fun everyCornerScalesProportionallyAroundItsOppositeCorner() {
        val original = stroke("old", 0.3f to 0.3f, 0.5f to 0.5f)
        val originalBounds = listOf(original).selectionBounds(square)!!
        val cases = listOf(
            SelectionCorner.TOP_LEFT to NormalizedPoint(0.1f, 0.1f),
            SelectionCorner.TOP_RIGHT to NormalizedPoint(0.7f, 0.1f),
            SelectionCorner.BOTTOM_LEFT to NormalizedPoint(0.1f, 0.7f),
            SelectionCorner.BOTTOM_RIGHT to NormalizedPoint(0.7f, 0.7f),
        )

        cases.forEachIndexed { index, (corner, target) ->
            val scaled = StrokeTransform.scaleFromCorner(
                listOf(original),
                originalBounds,
                corner,
                target,
                square,
            ) { "scaled-$index" }
            val bounds = scaled.selectionBounds(square)!!
            assertEquals(originalBounds.width / originalBounds.height, bounds.width / bounds.height, 0.001f)
            assertTrue(scaled.single().samples.all { it.x in 0f..1f && it.y in 0f..1f })
            assertEquals(original.brush, scaled.single().brush)
        }
    }

    @Test
    fun scalingCannotCollapseBelowMinimumOrEscapeThePage() {
        val original = stroke("old", 0.8f to 0.8f, 0.9f to 0.9f)
        val bounds = listOf(original).selectionBounds(square)!!

        val tiny = StrokeTransform.scaleFromCorner(
            listOf(original), bounds, SelectionCorner.TOP_LEFT, NormalizedPoint(0.8999f, 0.8999f), square,
        ) { "tiny" }
        val huge = StrokeTransform.scaleFromCorner(
            listOf(original), bounds, SelectionCorner.TOP_LEFT, NormalizedPoint(-5f, -5f), square,
        ) { "huge" }

        assertTrue(tiny.selectionBounds(square)!!.width >= StrokeTransform.MIN_SELECTION_SIZE - 0.0001f)
        assertTrue(huge.single().samples.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test
    fun moveAndScaleClampRenderedBrushEdgesOnRectangularViewports() {
        val viewport = CanvasViewport(320f, 640f)
        val original = stroke("wide", 20f, 0.08f to 0.08f, 0.2f to 0.2f)

        val moved = StrokeTransform.move(listOf(original), -1f, -1f, viewport) { "moved" }
        val movedBounds = moved.selectionBounds(viewport)!!
        assertEquals(0f, movedBounds.left, 0.0001f)
        assertEquals(0f, movedBounds.top, 0.0001f)

        val scaled = StrokeTransform.scaleFromCorner(
            moved,
            movedBounds,
            SelectionCorner.BOTTOM_RIGHT,
            NormalizedPoint(5f, 5f),
            viewport,
        ) { "scaled" }
        val scaledBounds = scaled.selectionBounds(viewport)!!
        assertTrue(scaledBounds.left >= 0f && scaledBounds.top >= 0f)
        assertTrue(scaledBounds.right <= 1f && scaledBounds.bottom <= 1f)
        assertEquals(original.brush.width, scaled.single().brush.width)
    }

    @Test
    fun rotationUsesPixelSpaceOnRectangularViewport() {
        val viewport = CanvasViewport(320f, 640f)
        val frame = OrientedSelectionFrame(
            center = NormalizedPoint(0.5f, 0.5f),
            widthPixels = 160f,
            heightPixels = 320f,
            rotationRadians = 0f,
            viewport = viewport,
        )
        val original = stroke("old", 2f, 0.25f to 0.5f, 0.5f to 0.625f)

        val result = StrokeTransform.rotate(
            listOf(original), frame, degrees(90f), viewport,
        ) { "rotated" }!!

        assertPoint(0.5f, 0.375f, result.strokes.single().samples[0])
        assertPoint(0.25f, 0.5f, result.strokes.single().samples[1])
        assertEquals(degrees(90f), result.frame.rotationRadians, 0.0001f)
    }

    @Test
    fun snapRoundsAbsoluteRotationToNearestFifteenDegrees() {
        assertEquals(0f, snapRotationRadians(degrees(7f)), 0.0001f)
        assertEquals(degrees(15f), snapRotationRadians(degrees(8f)), 0.0001f)
        assertEquals(degrees(-15f), snapRotationRadians(degrees(-8f)), 0.0001f)
    }

    @Test
    fun rotationPreservesBrushPressureAndTiming() {
        val brush = BrushSpec(colorArgb = 0xFF123456L, width = 7f)
        val original = StrokeRecord(
            id = "old",
            brush = brush,
            samples = listOf(
                StrokeSample(0.4f, 0.5f, 17L, null),
                StrokeSample(0.6f, 0.5f, 31L, 0.75f),
            ),
        )
        val frame = NormalizedRect(0.35f, 0.45f, 0.65f, 0.55f).toOrientedFrame(square)

        val rotated = StrokeTransform.rotate(
            listOf(original), frame, degrees(30f), square,
        ) { "new" }!!.strokes.single()

        assertEquals("new", rotated.id)
        assertEquals(brush, rotated.brush)
        assertEquals(listOf<Long>(17L, 31L), rotated.samples.map { it.elapsedMillis })
        assertEquals(listOf<Float?>(null, 0.75f), rotated.samples.map { it.pressure })
    }

    @Test
    fun rotationFitsTheWholeGroupWithoutClampingSamples() {
        val viewport = CanvasViewport(100f, 100f)
        val frame = OrientedSelectionFrame(
            center = NormalizedPoint(0.85f, 0.5f),
            widthPixels = 10f,
            heightPixels = 70f,
            rotationRadians = 0f,
            viewport = viewport,
        )
        val original = stroke("edge", 10f, 0.85f to 0.2f, 0.85f to 0.8f)

        val result = StrokeTransform.rotate(
            listOf(original), frame, degrees(90f), viewport,
        ) { "rotated" }!!

        assertPoint(0.95f, 0.5f, result.strokes.single().samples[0])
        assertPoint(0.35f, 0.5f, result.strokes.single().samples[1])
        assertEquals(0.65f, result.frame.center.x, 0.0001f)
        assertEquals(0.6f, kotlin.math.abs(
            result.strokes.single().samples[0].x - result.strokes.single().samples[1].x,
        ), 0.0001f)
    }

    @Test
    fun rotationReturnsNullWhenRequestedAngleCannotFit() {
        val viewport = CanvasViewport(100f, 100f)
        val original = stroke("diagonal", 20f, 0.1f to 0.1f, 0.9f to 0.9f)
        val frame = OrientedSelectionFrame(
            center = NormalizedPoint(0.5f, 0.5f),
            widthPixels = 100f,
            heightPixels = 100f,
            rotationRadians = 0f,
            viewport = viewport,
        )

        assertNull(
            StrokeTransform.rotate(listOf(original), frame, degrees(45f), viewport) { "unused" },
        )
    }

    @Test
    fun horizontalFlipMirrorsLocalXAtNinetyDegrees() {
        val frame = OrientedSelectionFrame(
            NormalizedPoint(0.5f, 0.5f), 200f, 100f, degrees(90f), square,
        )
        val original = stroke("asymmetric", 2f, 0.48f to 0.58f)

        val result = StrokeTransform.flip(
            listOf(original), frame, SelectionFlipAxis.HORIZONTAL, square,
        ) { "horizontal" }

        assertPoint(0.48f, 0.42f, result.strokes.single().samples.single())
        assertEquals(frame, result.frame)
    }

    @Test
    fun verticalFlipMirrorsLocalYAtNinetyDegrees() {
        val frame = OrientedSelectionFrame(
            NormalizedPoint(0.5f, 0.5f), 200f, 100f, degrees(90f), square,
        )
        val original = stroke("asymmetric", 2f, 0.48f to 0.58f)

        val result = StrokeTransform.flip(
            listOf(original), frame, SelectionFlipAxis.VERTICAL, square,
        ) { "vertical" }

        assertPoint(0.52f, 0.58f, result.strokes.single().samples.single())
        assertEquals(frame, result.frame)
    }

    @Test
    fun rotatedCornerScalingUsesTheOppositeLocalCorner() {
        val frame = OrientedSelectionFrame(
            NormalizedPoint(0.5f, 0.5f), 200f, 100f, degrees(90f), square,
        )
        val original = stroke("diagonal", 2f, 0.45f to 0.4f, 0.55f to 0.6f)

        val result = StrokeTransform.scaleFromCorner(
            listOf(original),
            frame,
            SelectionCorner.TOP_RIGHT,
            NormalizedPoint(0.65f, 0.8f),
            square,
        ) { "scaled" }

        assertPoint(0.45f, 0.4f, result.strokes.single().samples[0])
        assertPoint(0.65f, 0.8f, result.strokes.single().samples[1])
        assertEquals(400f, result.frame.widthPixels, 0.001f)
        assertEquals(200f, result.frame.heightPixels, 0.001f)
        assertEquals(degrees(90f), result.frame.rotationRadians, 0.0001f)
        assertEquals(0.55f, result.frame.center.x, 0.0001f)
        assertEquals(0.6f, result.frame.center.y, 0.0001f)
    }

    @Test
    fun orientedScaleStopsAtPageBoundary() {
        val frame = OrientedSelectionFrame(
            NormalizedPoint(0.8f, 0.5f), 200f, 100f, degrees(90f), square,
        )
        val original = stroke("edge", 20f, 0.75f to 0.4f, 0.85f to 0.6f)

        val result = StrokeTransform.scaleFromCorner(
            listOf(original),
            frame,
            SelectionCorner.TOP_RIGHT,
            NormalizedPoint(0.95f, 2f),
            square,
        ) { "scaled" }
        val visibleBounds = result.strokes.selectionBounds(square)!!

        assertTrue(visibleBounds.left >= 0f)
        assertTrue(visibleBounds.top >= 0f)
        assertTrue(visibleBounds.right <= 1f)
        assertTrue(visibleBounds.bottom <= 1f)
        assertEquals(frame.rotationRadians, result.frame.rotationRadians, 0f)
    }

    private fun stroke(id: String, vararg points: Pair<Float, Float>) =
        stroke(id, 2f, *points)

    private fun stroke(id: String, width: Float, vararg points: Pair<Float, Float>) = StrokeRecord(
        id = id,
        brush = BrushSpec(width = width),
        samples = points.mapIndexed { index, point ->
            StrokeSample(point.first, point.second, index * 10L, 0.4f + index * 0.1f)
        },
    )

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: StrokeSample) {
        assertEquals(expectedX, actual.x, 0.0001f)
        assertEquals(expectedY, actual.y, 0.0001f)
    }

    private fun degrees(value: Float): Float = Math.toRadians(value.toDouble()).toFloat()
}
