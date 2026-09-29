package com.putnamworkbook.offline.notebook.input

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import java.util.UUID

data class RecorderResult(
    val consumed: Boolean,
    val finishedStroke: StrokeRecord? = null,
)

class TouchStrokeRecorder(
    private val brushProvider: () -> BrushSpec,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private var primaryPointerId: Int? = null
    private var startedAtMillis = 0L
    private var activeBrush: BrushSpec? = null
    private val samples = mutableListOf<StrokeSample>()

    fun onEvent(
        event: PointerSampleEvent,
        canvasWidth: Float,
        canvasHeight: Float,
    ): RecorderResult {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return RecorderResult(consumed = false)

        if (event.action == PointerAction.CANCEL) {
            val wasActive = primaryPointerId != null
            reset()
            return RecorderResult(consumed = wasActive)
        }

        if (event.action == PointerAction.DOWN && primaryPointerId == null) {
            primaryPointerId = event.pointerId
            startedAtMillis = event.eventTimeMillis
            activeBrush = brushProvider()
            samples.clear()
            appendSample(event, canvasWidth, canvasHeight)
            return RecorderResult(consumed = true)
        }

        if (event.pointerId != primaryPointerId) return RecorderResult(consumed = false)

        return when (event.action) {
            PointerAction.MOVE -> {
                appendSample(event, canvasWidth, canvasHeight)
                RecorderResult(consumed = true)
            }

            PointerAction.UP, PointerAction.POINTER_UP -> {
                appendSample(event, canvasWidth, canvasHeight)
                val stroke = StrokeRecord(
                    id = idFactory(),
                    brush = requireNotNull(activeBrush),
                    samples = samples.toList(),
                )
                reset()
                RecorderResult(consumed = true, finishedStroke = stroke)
            }

            PointerAction.DOWN, PointerAction.POINTER_DOWN, PointerAction.CANCEL ->
                RecorderResult(consumed = false)
        }
    }

    private fun appendSample(event: PointerSampleEvent, width: Float, height: Float) {
        val sample = StrokeSample(
            x = (event.x / width).coerceIn(0f, 1f),
            y = (event.y / height).coerceIn(0f, 1f),
            elapsedMillis = (event.eventTimeMillis - startedAtMillis)
                .coerceAtLeast(samples.lastOrNull()?.elapsedMillis ?: 0L),
            pressure = event.pressure?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        )
        if (sample != samples.lastOrNull()) samples += sample
    }

    private fun reset() {
        primaryPointerId = null
        activeBrush = null
        samples.clear()
    }
}
