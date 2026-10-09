package com.putnamworkbook.offline.notebook.input

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishedStrokeBufferTest {
    @Test
    fun finishedStrokeIsRetainedUntilItsCommittedRenderIsAcknowledged() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))

        assertEquals(listOf("record-a"), buffer.finish(listOf("ink-a")).values.map { it.id })
        assertTrue(buffer.acknowledgeRendered(emptySet()).isEmpty())
        assertEquals(listOf("ink-a"), buffer.acknowledgeRendered(setOf("record-a")))
        assertTrue(buffer.acknowledgeRendered(setOf("record-a")).isEmpty())
    }

    @Test
    fun overlappingCallbacksRemainIndependentAndMayBeAcknowledgedInReverseOrder() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))
        buffer.put("ink-b", stroke("record-b"))

        assertEquals(listOf("record-a", "record-b"), buffer.finish(listOf("ink-a", "ink-b")).values.map { it.id })
        assertEquals(listOf("ink-b"), buffer.acknowledgeRendered(setOf("record-b")))
        assertEquals(listOf("ink-a"), buffer.acknowledgeRendered(setOf("record-a")))
    }

    @Test
    fun finishCallbacksMayArriveInTheOppositeOrderFromRecording() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))
        buffer.put("ink-b", stroke("record-b"))

        assertEquals(listOf("record-b"), buffer.finish(listOf("ink-b")).values.map { it.id })
        assertEquals(listOf("record-a"), buffer.finish(listOf("ink-a")).values.map { it.id })
        assertEquals(listOf("ink-b", "ink-a"), buffer.acknowledgeRendered(setOf("record-a", "record-b")))
    }

    @Test
    fun duplicateAndUnknownCompletionIdsAreIgnoredWithoutDisturbingTrackedInk() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))
        buffer.put("ink-b", stroke("record-b"))

        assertEquals(
            listOf("record-a", "record-b"),
            buffer.finish(listOf("ink-a", "unknown", "ink-a", "ink-b")).values.map { it.id },
        )
        assertTrue(buffer.finish(listOf("unknown", "ink-a")).isEmpty())
        assertEquals(listOf("ink-a", "ink-b"), buffer.acknowledgeRendered(setOf("record-a", "record-b")))
    }

    @Test
    fun cancellationBeforeFinishRemovesOnlyTheRequestedRecording() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))
        buffer.put("ink-b", stroke("record-b"))

        assertEquals("record-a", buffer.remove("ink-a")?.id)
        assertTrue(buffer.finish(listOf("ink-a")).isEmpty())
        assertEquals(listOf("record-b"), buffer.finish(listOf("ink-b")).values.map { it.id })
    }

    @Test
    fun cancellationWhileAwaitingRenderRemovesOnlyTheRequestedFinishedStroke() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))
        buffer.put("ink-b", stroke("record-b"))
        buffer.finish(listOf("ink-a", "ink-b"))

        assertEquals("record-a", buffer.remove("ink-a")?.id)
        assertTrue(buffer.acknowledgeRendered(setOf("record-a")).isEmpty())
        assertEquals(listOf("ink-b"), buffer.acknowledgeRendered(setOf("record-b")))
    }

    @Test
    fun pendingInkIdCannotBeReusedWhileRecordingOrAwaitingRender() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))

        assertThrows(IllegalArgumentException::class.java) {
            buffer.put("ink-a", stroke("duplicate-recording"))
        }

        buffer.finish(listOf("ink-a"))

        assertThrows(IllegalArgumentException::class.java) {
            buffer.put("ink-a", stroke("duplicate-awaiting-render"))
        }
    }

    @Test
    fun cancellingNewGestureDoesNotDropEarlierFinishedStroke() {
        val buffer = FinishedStrokeBuffer<String>()
        buffer.put("ink-a", stroke("record-a"))
        buffer.finish(listOf("ink-a"))

        buffer.remove("ink-b")

        assertEquals(listOf("ink-a"), buffer.acknowledgeRendered(setOf("record-a")))
    }

    @Test
    fun untrackedFinishedInkCanBeRemovedImmediately() {
        val buffer = FinishedStrokeBuffer<String>()

        assertTrue(buffer.finish(listOf("unknown")).isEmpty())
    }

    private fun stroke(id: String) = StrokeRecord(
        id = id,
        brush = BrushSpec(),
        samples = listOf(StrokeSample(0.5f, 0.5f, 0L, null)),
    )
}
