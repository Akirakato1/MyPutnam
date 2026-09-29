package com.putnamworkbook.offline.notebook.input

import android.view.MotionEvent

object MotionEventAdapter {
    fun toEvents(event: MotionEvent): List<PointerSampleEvent> = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> listOf(event.sample(event.actionIndex, PointerAction.DOWN))
        MotionEvent.ACTION_POINTER_DOWN -> listOf(event.sample(event.actionIndex, PointerAction.POINTER_DOWN))
        MotionEvent.ACTION_MOVE -> List(event.pointerCount) { index -> event.sample(index, PointerAction.MOVE) }
        MotionEvent.ACTION_POINTER_UP -> listOf(event.sample(event.actionIndex, PointerAction.POINTER_UP))
        MotionEvent.ACTION_UP -> listOf(event.sample(event.actionIndex, PointerAction.UP))
        MotionEvent.ACTION_CANCEL -> listOf(
            PointerSampleEvent(
                action = PointerAction.CANCEL,
                pointerId = if (event.pointerCount > 0) event.getPointerId(0) else -1,
                x = 0f,
                y = 0f,
                eventTimeMillis = event.eventTime,
                pressure = null,
            ),
        )
        else -> emptyList()
    }

    private fun MotionEvent.sample(index: Int, action: PointerAction) = PointerSampleEvent(
        action = action,
        pointerId = getPointerId(index),
        x = getX(index),
        y = getY(index),
        eventTimeMillis = eventTime,
        pressure = getPressure(index).takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f),
    )
}
