package com.putnamworkbook.offline.notebook.edit

data class NormalizedPoint(val x: Float, val y: Float)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f) {
            "Selection bounds must be page-normalized"
        }
        require(left <= right && top <= bottom) { "Selection bounds must be ordered" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        fun fromPoints(first: NormalizedPoint, second: NormalizedPoint): NormalizedRect = NormalizedRect(
            left = minOf(first.x, second.x).coerceIn(0f, 1f),
            top = minOf(first.y, second.y).coerceIn(0f, 1f),
            right = maxOf(first.x, second.x).coerceIn(0f, 1f),
            bottom = maxOf(first.y, second.y).coerceIn(0f, 1f),
        )
    }
}

enum class SelectionCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}
