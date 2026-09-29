package com.putnamworkbook.offline.notebook.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.Handedness
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.notebook.model.NotebookPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PalmGuardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun touchesInsidePalmGuardNeverReachTheStrokeRecorder() {
        var finishedStrokeCount = 0
        composeRule.setContent {
            NotebookCanvas(
                page = NotebookPage("page-1", PaperStyle.BLANK),
                strokes = emptyList(),
                brush = BrushSpec(),
                palmGuard = PalmGuardState(
                    side = Handedness.RIGHT,
                    widthFraction = 0.5f,
                    heightFraction = 1f,
                    verticalOffsetFraction = 0f,
                ),
                onStrokeFinished = { finishedStrokeCount += 1 },
                modifier = Modifier,
            )
        }

        composeRule.onNodeWithTag(PALM_GUARD_TEST_TAG).performTouchInput {
            down(center)
            moveTo(Offset(center.x - 20.dp.toPx(), center.y - 20.dp.toPx()))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, finishedStrokeCount)

        composeRule.onNodeWithTag(NOTEBOOK_CANVAS_TEST_TAG).performTouchInput {
            down(Offset(40f, 80f))
            moveTo(Offset(100f, 140f))
            up()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { finishedStrokeCount == 1 }
        assertEquals(1, finishedStrokeCount)
    }
}
