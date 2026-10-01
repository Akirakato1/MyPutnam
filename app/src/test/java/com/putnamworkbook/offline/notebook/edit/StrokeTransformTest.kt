package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeTransformTest {
    @Test
    fun moveClampsTheWholeSelectionAtEveryPageEdgeAndPreservesStrokeData() {
        val original = stroke("old", 0.2f to 0.3f, 0.4f to 0.5f)
        val moved = StrokeTransform.move(listOf(original), dx = -1f, dy = 1f) { "moved" }.single()

        assertEquals("moved", moved.id)
        assertEquals(original.brush, moved.brush)
        assertEquals(original.samples.map { it.pressure }, moved.samples.map { it.pressure })
        assertEquals(original.samples.map { it.elapsedMillis }, moved.samples.map { it.elapsedMillis })
        assertTrue(moved.samples.all { it.x in 0f..1f && it.y in 0f..1f })
        val bounds = listOf(moved).selectionBounds()!!
        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(1f, bounds.bottom, 0.0001f)
    }

    @Test
    fun everyCornerScalesProportionallyAroundItsOppositeCorner() {
        val original = stroke("old", 0.3f to 0.3f, 0.5f to 0.5f)
        val originalBounds = listOf(original).selectionBounds()!!
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
            ) { "scaled-$index" }
            val bounds = scaled.selectionBounds()!!
            assertEquals(originalBounds.width / originalBounds.height, bounds.width / bounds.height, 0.001f)
            assertTrue(scaled.single().samples.all { it.x in 0f..1f && it.y in 0f..1f })
            assertEquals(original.brush, scaled.single().brush)
        }
    }

    @Test
    fun scalingCannotCollapseBelowMinimumOrEscapeThePage() {
        val original = stroke("old", 0.8f to 0.8f, 0.9f to 0.9f)
        val bounds = listOf(original).selectionBounds()!!

        val tiny = StrokeTransform.scaleFromCorner(
            listOf(original), bounds, SelectionCorner.TOP_LEFT, NormalizedPoint(0.8999f, 0.8999f),
        ) { "tiny" }
        val huge = StrokeTransform.scaleFromCorner(
            listOf(original), bounds, SelectionCorner.TOP_LEFT, NormalizedPoint(-5f, -5f),
        ) { "huge" }

        assertTrue(tiny.selectionBounds()!!.width >= StrokeTransform.MIN_SELECTION_SIZE - 0.0001f)
        assertTrue(huge.single().samples.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    private fun stroke(id: String, vararg points: Pair<Float, Float>) = StrokeRecord(
        id = id,
        brush = BrushSpec(width = 2f),
        samples = points.mapIndexed { index, point ->
            StrokeSample(point.first, point.second, index * 10L, 0.4f + index * 0.1f)
        },
    )
}
