package com.putnamworkbook.offline.notebook.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionGestureResolverTest {
    @Test
    fun requestedRotationIncludesMotionBeforeTouchSlop() {
        val result = resolveRotationAngle(
            initialFrameAngle = degrees(15f),
            initialPointerAngle = degrees(-90f),
            currentPointerAngle = degrees(0f),
        )

        assertEquals(degrees(105f), result, 0.0001f)
    }

    @Test
    fun rotationHandleHasHighestDragPriorityAndDoesNotClearSelection() {
        assertEquals(
            SelectionDragIntent.ROTATE,
            resolveSelectionDragIntent(
                isRotationHandle = true,
                isCorner = true,
                isInsideSelection = true,
            ),
        )
    }

    @Test
    fun rotatedInteriorStartsMove() {
        assertEquals(
            SelectionDragIntent.MOVE,
            resolveSelectionDragIntent(
                isRotationHandle = false,
                isCorner = false,
                isInsideSelection = true,
            ),
        )
    }

    @Test
    fun outsideRotatedFrameStartsMarquee() {
        assertEquals(
            SelectionDragIntent.MARQUEE,
            resolveSelectionDragIntent(
                isRotationHandle = false,
                isCorner = false,
                isInsideSelection = false,
            ),
        )
        assertEquals(
            SelectionDragIntent.SCALE,
            resolveSelectionDragIntent(
                isRotationHandle = false,
                isCorner = true,
                isInsideSelection = false,
            ),
        )
    }

    private fun degrees(value: Float): Float = Math.toRadians(value.toDouble()).toFloat()
}
