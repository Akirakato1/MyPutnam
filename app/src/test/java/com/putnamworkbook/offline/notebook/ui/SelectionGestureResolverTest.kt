package com.putnamworkbook.offline.notebook.ui

import com.putnamworkbook.offline.notebook.edit.CanvasViewport
import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import com.putnamworkbook.offline.notebook.edit.OrientedSelectionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun rotationGestureKeepsTheDragStartCenterWhenPreviewFrameShifts() {
        val viewport = CanvasViewport(1_000f, 1_000f)
        val startFrame = OrientedSelectionFrame(
            center = NormalizedPoint(0.5f, 0.1f),
            widthPixels = 200f,
            heightPixels = 80f,
            rotationRadians = 0f,
            viewport = viewport,
        )
        val baseline = RotationGestureBaseline.start(
            frame = startFrame,
            pointer = NormalizedPoint(0.5f, 0.02f),
        )
        val shiftedPreviewFrame = startFrame.copy(center = NormalizedPoint(0.5f, 0.2f))
        val currentPointer = NormalizedPoint(0.58f, 0.1f)

        assertEquals(degrees(90f), baseline.angleFor(currentPointer), 0.0001f)
        assertNotEquals(
            baseline.angleFor(currentPointer),
            shiftedPreviewFrame.pointerAngle(currentPointer),
            degrees(1f),
        )
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
