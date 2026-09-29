package com.putnamworkbook.offline.notebook.input

enum class PointerAction {
    DOWN,
    MOVE,
    UP,
    CANCEL,
    POINTER_DOWN,
    POINTER_UP,
}

data class PointerSampleEvent(
    val action: PointerAction,
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val eventTimeMillis: Long,
    val pressure: Float?,
)
