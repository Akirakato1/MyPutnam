package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrokeCodecTest {
    @Test
    fun brushAndNormalizedSamplesRoundTripExactly() {
        val stroke = StrokeRecord(
            id = "stroke-1",
            brush = BrushSpec(colorArgb = 0xFFAABBCCL, width = 5.5f),
            samples = listOf(
                StrokeSample(0f, 1f, 0L, null),
                StrokeSample(0.25f, 0.75f, 16L, 0.4f),
                StrokeSample(1f, 0f, 32L, 1f),
            ),
        )

        assertEquals(stroke, StrokeCodec.decode(StrokeCodec.encode(stroke)))
    }

    @Test
    fun coordinatesMustRemainNormalized() {
        assertThrows(IllegalArgumentException::class.java) {
            StrokeSample(-0.01f, 0.5f, 0L, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StrokeSample(0.5f, 1.01f, 0L, null)
        }
    }
}
