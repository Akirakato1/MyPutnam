package com.putnamworkbook.offline.notebook.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
        val finished = mutableListOf<StrokeRecord>()
        composeRule.setContent {
            NotebookCanvas(
                page = NotebookPage("rapid-page", PaperStyle.BLANK),
                strokes = emptyList(),
                brush = BrushSpec(width = 4f),
                onStrokeFinished = { finished += it },
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

        composeRule.waitUntil(timeoutMillis = 5_000) { finished.size == 10 }
        assertEquals(10, finished.map { it.id }.distinct().size)
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
