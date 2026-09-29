package com.putnamworkbook.offline.notebook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.putnamworkbook.offline.domain.model.Handedness

const val PALM_GUARD_TEST_TAG = "palm-guard"

data class PalmGuardState(
    val side: Handedness,
    val widthFraction: Float,
    val heightFraction: Float,
    val verticalOffsetFraction: Float,
) {
    init {
        require(widthFraction in 0.1f..0.5f)
        require(heightFraction in 0.1f..1f)
        require(verticalOffsetFraction in 0f..1f)
    }
}

@Composable
fun BoxScope.PalmGuard(
    state: PalmGuardState,
    availableHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val guardHeight = availableHeight * state.heightFraction
    val verticalOffset = (availableHeight - guardHeight) * state.verticalOffsetFraction
    Box(
        modifier = modifier
            .align(if (state.side == Handedness.LEFT) Alignment.TopStart else Alignment.TopEnd)
            .offset(y = verticalOffset)
            .fillMaxWidth(state.widthFraction)
            .fillMaxHeight(state.heightFraction)
            .background(Color(0x227C8AA5))
            .testTag(PALM_GUARD_TEST_TAG)
            .semantics { contentDescription = "Palm guard touch-blocking region" }
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    )
}
