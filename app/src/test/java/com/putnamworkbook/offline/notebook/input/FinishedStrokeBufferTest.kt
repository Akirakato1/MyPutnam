package com.putnamworkbook.offline.notebook.input

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishedStrokeBufferTest {
    @Test
    fun multipleFinishedIdsDrainEveryMatchingStrokeExactlyOnce() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))
        buffer.put("ink-b", stroke("record-b"))

        assertEquals(listOf("record-a", "record-b"), buffer.drain(listOf("ink-a", "ink-b")).map { it.id })
        assertTrue(buffer.drain(listOf("ink-a", "ink-b")).isEmpty())
    }

    @Test
    fun callbacksMayArriveInReverseOrder() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))
        buffer.put("ink-b", stroke("record-b"))

        assertEquals(listOf("record-b"), buffer.drain(listOf("ink-b")).map { it.id })
        assertEquals(listOf("record-a"), buffer.drain(listOf("ink-a")).map { it.id })
    }

    @Test
    fun cancellingNewGestureDoesNotDropEarlierFinishedStroke() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))

        buffer.remove("ink-b")

        assertEquals(listOf("record-a"), buffer.drain(listOf("ink-a")).map { it.id })
    }

    private fun stroke(id: String) = StrokeRecord(
        id = id,
        brush = BrushSpec(),
        samples = listOf(StrokeSample(0.5f, 0.5f, 0L, null)),
    )
}
