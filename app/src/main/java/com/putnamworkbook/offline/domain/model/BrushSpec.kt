package com.putnamworkbook.offline.domain.model

data class BrushSpec(
    val colorArgb: Long = 0xFF1F2937L,
    val width: Float = 3f,
) {
    init {
        require(width > 0f) { "Brush width must be positive" }
    }
}
