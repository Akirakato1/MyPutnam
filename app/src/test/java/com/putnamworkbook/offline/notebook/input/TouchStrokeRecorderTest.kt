package com.putnamworkbook.offline.notebook.input

import com.putnamworkbook.offline.domain.model.BrushSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchStrokeRecorderTest {
    @Test
    fun downMoveUpEmitsOneNormalizedStrokeWithNullablePressure() {
        val recorder = recorder()

        assertNull(recorder.onEvent(event(PointerAction.DOWN, 7, 20f, 25f, 100L), 200f, 100f).finishedStroke)
        assertNull(recorder.onEvent(event(PointerAction.MOVE, 7, 100f, 50f, 116L), 200f, 100f).finishedStroke)
        val result = recorder.onEvent(event(PointerAction.UP, 7, 180f, 75f, 132L), 200f, 100f)

        assertTrue(result.consumed)
        val stroke = result.finishedStroke!!
        assertEquals("stroke-fixed", stroke.id)
        assertEquals(BrushSpec(colorArgb = 0xFF000000L, width = 4f), stroke.brush)
        assertEquals(listOf(0.1f, 0.5f, 0.9f), stroke.samples.map { it.x })
        assertEquals(listOf(0.25f, 0.5f, 0.75f), stroke.samples.map { it.y })
        assertEquals(listOf(0L, 16L, 32L), stroke.samples.map { it.elapsedMillis })
        assertEquals(listOf(null, null, null), stroke.samples.map { it.pressure })
    }

    @Test
    fun cancelEmitsNothingAndResetsThePrimaryPointer() {
        val recorder = recorder()
        recorder.onEvent(event(PointerAction.DOWN, 1, 10f, 10f, 0L), 100f, 100f)

        val cancelled = recorder.onEvent(event(PointerAction.CANCEL, 1, 20f, 20f, 10L), 100f, 100f)
        val staleUp = recorder.onEvent(event(PointerAction.UP, 1, 20f, 20f, 11L), 100f, 100f)

        assertTrue(cancelled.consumed)
        assertNull(cancelled.finishedStroke)
        assertFalse(staleUp.consumed)
        assertNull(staleUp.finishedStroke)
    }

    @Test
    fun secondPointerIsIgnoredAndPrimaryLiftFinishesExactlyOnce() {
        val recorder = recorder()
        recorder.onEvent(event(PointerAction.DOWN, 3, 10f, 10f, 0L), 100f, 100f)

        assertFalse(
            recorder.onEvent(event(PointerAction.POINTER_DOWN, 9, 80f, 80f, 2L), 100f, 100f).consumed,
        )
        assertFalse(recorder.onEvent(event(PointerAction.MOVE, 9, 90f, 90f, 4L), 100f, 100f).consumed)
        assertFalse(
            recorder.onEvent(event(PointerAction.POINTER_UP, 9, 90f, 90f, 6L), 100f, 100f).consumed,
        )

        val primaryUp = recorder.onEvent(
            event(PointerAction.POINTER_UP, 3, 30f, 30f, 8L, pressure = 0.6f),
            100f,
            100f,
        )
        val repeatedUp = recorder.onEvent(event(PointerAction.UP, 3, 30f, 30f, 9L), 100f, 100f)

        assertEquals(1, primaryUp.finishedStroke!!.samples.lastIndex)
        assertEquals(0.6f, primaryUp.finishedStroke.samples.last().pressure)
        assertFalse(repeatedUp.consumed)
        assertNull(repeatedUp.finishedStroke)
    }

    private fun recorder() = TouchStrokeRecorder(
        brushProvider = { BrushSpec(colorArgb = 0xFF000000L, width = 4f) },
        idFactory = { "stroke-fixed" },
    )

    private fun event(
        action: PointerAction,
        pointerId: Int,
        x: Float,
        y: Float,
        time: Long,
        pressure: Float? = null,
    ) = PointerSampleEvent(action, pointerId, x, y, time, pressure)
}
