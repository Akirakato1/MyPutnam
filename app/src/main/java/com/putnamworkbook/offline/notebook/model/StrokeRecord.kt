package com.putnamworkbook.offline.notebook.model

import com.putnamworkbook.offline.domain.model.BrushSpec

data class StrokeSample(
    val x: Float,
    val y: Float,
    val elapsedMillis: Long,
    val pressure: Float?,
) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "Stroke coordinates must be normalized" }
        require(elapsedMillis >= 0L) { "Stroke elapsed time cannot be negative" }
        require(pressure == null || pressure in 0f..1f) { "Pressure must be normalized when present" }
    }
}

data class StrokeRecord(
    val id: String,
    val brush: BrushSpec,
    val samples: List<StrokeSample>,
) {
    init {
        require(id.matches(SAFE_ID)) { "Stroke id contains unsupported characters" }
        require(samples.isNotEmpty()) { "A stroke must contain at least one sample" }
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    }
}
