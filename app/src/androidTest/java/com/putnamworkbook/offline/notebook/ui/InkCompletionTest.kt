package com.putnamworkbook.offline.notebook.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import org.junit.Assert.assertEquals
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
                palmGuard = null,
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
}
