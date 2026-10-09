package com.putnamworkbook.offline.notebook.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InkStressTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tenRapidStrokesAreDeliveredOnceWithoutWaitingForPersistence() {
        val delivered = mutableListOf<StrokeRecord>()
        val rendered = mutableStateListOf<StrokeRecord>()
        val events = mutableListOf<InkHandoffEvent>()
        val scheduler = ManualInkRemovalScheduler()
        var page by mutableStateOf(NotebookPage("rapid-page", PaperStyle.BLANK))
        composeRule.setContent {
            NotebookCanvas(
                page = page,
                strokes = rendered,
                brush = BrushSpec(width = 4f),
                onStrokeFinished = { delivered += it },
                inkRemovalScheduler = scheduler,
                onInkHandoffEvent = { events += it },
            )
        }

        composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG).performTouchInput {
            repeat(10) { index ->
                val y = 30f + index * 24f
                down(Offset(30f, y))
                moveTo(Offset(180f, y + 8f), 8L)
                up()
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { delivered.size == 10 }
        assertEquals(10, delivered.map { it.id }.distinct().size)

        composeRule.runOnIdle {
            page = NotebookPage("recomposed-page", PaperStyle.SQUARE_GRID)
            rendered += delivered
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            events.count { it == InkHandoffEvent.REMOVAL_SCHEDULED } == 1
        }
        composeRule.runOnIdle { scheduler.advanceAllFrames() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            events.count { it == InkHandoffEvent.PLATFORM_REMOVED } == 1
        }
        assertEquals(10, rendered.map { it.id }.distinct().size)
    }

    @Test
    fun twoThousandStrokesRenderOnceWithoutCrashing() {
        val strokes = List(2_000) { index ->
            val x = (index % 100) / 100f
            val y = (index / 100) / 20f
            StrokeRecord(
                id = "stress-$index",
                brush = BrushSpec(width = 2f),
                samples = listOf(
                    StrokeSample(x, y.coerceAtMost(0.99f), 0L, null),
                    StrokeSample((x + 0.008f).coerceAtMost(1f), (y + 0.008f).coerceAtMost(1f), 8L, null),
                ),
            )
        }
        composeRule.setContent {
            NotebookCanvas(
                page = NotebookPage("stress-page", PaperStyle.BLANK),
                strokes = strokes,
                brush = BrushSpec(),
                onStrokeFinished = {},
                modifier = Modifier,
            )
        }

        val node = composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG)
        composeRule.waitForIdle()
        assertEquals(2_000, node.fetchSemanticsNode().config[RenderedStrokeCountKey])
        val image = node.captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }
}
