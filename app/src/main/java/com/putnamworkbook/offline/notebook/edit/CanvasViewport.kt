package com.putnamworkbook.offline.notebook.edit

data class CanvasViewport(
    val widthPixels: Float,
    val heightPixels: Float,
) {
    init {
        require(widthPixels.isFinite() && widthPixels > 0f) {
            "Canvas width must be finite and positive"
        }
        require(heightPixels.isFinite() && heightPixels > 0f) {
            "Canvas height must be finite and positive"
        }
    }
}
