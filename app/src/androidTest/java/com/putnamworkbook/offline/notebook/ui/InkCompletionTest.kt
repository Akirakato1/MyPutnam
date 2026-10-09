package com.putnamworkbook.offline.notebook.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InkCompletionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun twoRapidStrokesAreBothDeliveredExactlyOnce() {
        val finished = mutableListOf<StrokeRecord>()
        composeRule.setContent {
            NotebookCanvas(
                page = NotebookPage("page", PaperStyle.BLANK),
                strokes = emptyList(),
                brush = BrushSpec(),
                onStrokeFinished = { finished += it },
            )
        }

        composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG).performTouchInput {
            down(Offset(40f, 40f))
            moveTo(Offset(140f, 140f), 12L)
            up()
            down(Offset(180f, 40f))
            moveTo(Offset(280f, 140f), 12L)
            up()
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { finished.size == 2 }
        assertEquals(2, finished.map { it.id }.distinct().size)
    }

    @Test
    fun platformInkIsRemovedOnlyAfterModelRenderAndTheFollowingFrame() {
        val delivered = mutableListOf<StrokeRecord>()
        val rendered = mutableStateListOf<StrokeRecord>()
        val events = mutableListOf<InkHandoffEvent>()
        val scheduler = ManualInkRemovalScheduler()
        composeRule.setContent {
            NotebookCanvas(
                page = NotebookPage("page", PaperStyle.BLANK),
                strokes = rendered,
                brush = BrushSpec(),
                onStrokeFinished = { delivered += it },
                inkRemovalScheduler = scheduler,
                onInkHandoffEvent = { events += it },
            )
        }

        composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG).performTouchInput {
            down(Offset(40f, 40f))
            moveTo(Offset(140f, 140f), 12L)
            up()
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { delivered.size == 1 }
        assertEquals(listOf(InkHandoffEvent.MODEL_DELIVERED), events)
        assertFalse(scheduler.hasPendingRemoval)

        composeRule.runOnIdle { rendered += delivered.single() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            events.contains(InkHandoffEvent.REMOVAL_SCHEDULED)
        }
        assertEquals(
            listOf(
                InkHandoffEvent.MODEL_DELIVERED,
                InkHandoffEvent.RENDERED_ID_ACKNOWLEDGED,
                InkHandoffEvent.REMOVAL_SCHEDULED,
            ),
            events,
        )
        assertTrue(scheduler.hasPendingRemoval)
        assertFalse(events.contains(InkHandoffEvent.PLATFORM_REMOVED))

        composeRule.runOnIdle { scheduler.advanceFrame() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            events.lastOrNull() == InkHandoffEvent.PLATFORM_REMOVED
        }
    }
}

internal class ManualInkRemovalScheduler : InkRemovalScheduler {
    private val pending = ArrayDeque<() -> Unit>()

    val hasPendingRemoval: Boolean
        get() = pending.isNotEmpty()

    override fun schedule(host: View, removal: () -> Unit) {
        pending += removal
    }

    fun advanceFrame() {
        check(pending.isNotEmpty()) { "No platform-ink removal was scheduled" }
        pending.removeFirst().invoke()
    }

    fun advanceAllFrames() {
        while (pending.isNotEmpty()) {
            pending.removeFirst().invoke()
        }
    }
}
