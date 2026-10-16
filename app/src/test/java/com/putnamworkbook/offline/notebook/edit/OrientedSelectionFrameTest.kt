package com.putnamworkbook.offline.notebook.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientedSelectionFrameTest {
    @Test
    fun cornersRotateAroundCenterInPixelSpace() {
        val square = OrientedSelectionFrame(
            center = NormalizedPoint(0.5f, 0.5f),
            widthPixels = 200f,
            heightPixels = 400f,
            rotationRadians = HALF_TURN_QUARTER,
            viewport = CanvasViewport(1_000f, 1_000f),
        )
        assertPoint(0.7f, 0.4f, square.corner(SelectionCorner.TOP_LEFT))
        assertPoint(0.7f, 0.6f, square.corner(SelectionCorner.TOP_RIGHT))
        assertPoint(0.3f, 0.4f, square.corner(SelectionCorner.BOTTOM_LEFT))
        assertPoint(0.3f, 0.6f, square.corner(SelectionCorner.BOTTOM_RIGHT))

        val portrait = NormalizedRect(0.4f, 0.3f, 0.6f, 0.7f)
            .toOrientedFrame(CanvasViewport(320f, 640f))
            .copy(rotationRadians = HALF_TURN_QUARTER)
        assertPoint(0.9f, 0.45f, portrait.corner(SelectionCorner.TOP_LEFT))
        assertPoint(0.9f, 0.55f, portrait.corner(SelectionCorner.TOP_RIGHT))
        assertPoint(0.1f, 0.45f, portrait.corner(SelectionCorner.BOTTOM_LEFT))
        assertPoint(0.1f, 0.55f, portrait.corner(SelectionCorner.BOTTOM_RIGHT))
    }

    @Test
    fun containsInverseRotatesThePoint() {
        val frame = OrientedSelectionFrame(
            center = NormalizedPoint(0.5f, 0.5f),
            widthPixels = 200f,
            heightPixels = 100f,
            rotationRadians = HALF_TURN_QUARTER,
            viewport = CanvasViewport(1_000f, 1_000f),
        )

        assertTrue(frame.contains(NormalizedPoint(0.5f, 0.59f)))
        assertTrue(frame.contains(NormalizedPoint(0.451f, 0.5f)))
        assertFalse(frame.contains(NormalizedPoint(0.44f, 0.5f)))
        assertFalse(frame.contains(NormalizedPoint(0.5f, 0.61f)))
    }

    @Test
    fun cornerHitUsesPixelRadius() {
        val viewport = CanvasViewport(320f, 640f)
        val frame = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)
            .toOrientedFrame(viewport)
        val nearTopLeft = NormalizedPoint((80f + 23f) / 320f, 160f / 640f)
        val outsideTopLeft = NormalizedPoint((80f + 25f) / 320f, 160f / 640f)

        assertEquals(SelectionCorner.TOP_LEFT, frame.hitCorner(nearTopLeft, radiusPixels = 24f))
        assertEquals(null, frame.hitCorner(outsideTopLeft, radiusPixels = 24f))
    }

    @Test
    fun rotationHandleExtendsFromLocalTopCenter() {
        val viewport = CanvasViewport(320f, 640f)
        val frame = OrientedSelectionFrame(
            center = NormalizedPoint(0.5f, 0.5f),
            widthPixels = 160f,
            heightPixels = 200f,
            rotationRadians = HALF_TURN_QUARTER,
            viewport = viewport,
        )
        val handle = frame.rotationHandle(stemPixels = 32f)

        assertPoint((160f + 132f) / 320f, 0.5f, handle)
        assertTrue(frame.hitRotationHandle(NormalizedPoint((292f - 20f) / 320f, 0.5f), 24f, 32f))
        assertFalse(frame.hitRotationHandle(NormalizedPoint((292f - 25f) / 320f, 0.5f), 24f, 32f))
    }

    @Test
    fun rejectsNonFiniteOrNonPositiveGeometry() {
        val viewport = CanvasViewport(320f, 640f)
        val validCenter = NormalizedPoint(0.5f, 0.5f)

        assertThrows(IllegalArgumentException::class.java) {
            OrientedSelectionFrame(validCenter, 0f, 20f, 0f, viewport)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrientedSelectionFrame(validCenter, 20f, Float.NaN, 0f, viewport)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrientedSelectionFrame(validCenter, 20f, 20f, Float.POSITIVE_INFINITY, viewport)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrientedSelectionFrame(NormalizedPoint(Float.NaN, 0.5f), 20f, 20f, 0f, viewport)
        }
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: NormalizedPoint) {
        assertEquals(expectedX, actual.x, 0.0001f)
        assertEquals(expectedY, actual.y, 0.0001f)
    }

    private companion object {
        const val HALF_TURN_QUARTER = (Math.PI / 2.0).toFloat()
    }
}
