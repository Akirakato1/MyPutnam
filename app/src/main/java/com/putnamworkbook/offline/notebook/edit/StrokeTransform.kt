package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.notebook.model.StrokeRecord

object StrokeTransform {
    const val MIN_SELECTION_SIZE = 0.01f

    fun move(
        strokes: List<StrokeRecord>,
        dx: Float,
        dy: Float,
        viewport: CanvasViewport,
        idFactory: () -> String,
    ): List<StrokeRecord> {
        val bounds = strokes.visibleBounds(viewport) ?: return emptyList()
        val clampedDx = dx.coerceIn(-bounds.left, 1f - bounds.right)
        val clampedDy = dy.coerceIn(-bounds.top, 1f - bounds.bottom)
        return strokes.map { stroke ->
            stroke.copy(
                id = idFactory(),
                samples = stroke.samples.map { sample ->
                    sample.copy(
                        x = (sample.x + clampedDx).coerceIn(0f, 1f),
                        y = (sample.y + clampedDy).coerceIn(0f, 1f),
                    )
                },
            )
        }
    }

    fun scaleFromCorner(
        strokes: List<StrokeRecord>,
        bounds: NormalizedRect,
        corner: SelectionCorner,
        target: NormalizedPoint,
        viewport: CanvasViewport,
        idFactory: () -> String,
    ): List<StrokeRecord> {
        if (strokes.isEmpty()) return emptyList()
        val width = bounds.width.coerceAtLeast(0.000001f)
        val height = bounds.height.coerceAtLeast(0.000001f)
        val anchor = oppositeCorner(bounds, corner)
        val requestedX = kotlin.math.abs(target.x - anchor.x) / width
        val requestedY = kotlin.math.abs(target.y - anchor.y) / height
        val minimumScale = maxOf(MIN_SELECTION_SIZE / width, MIN_SELECTION_SIZE / height)
        val maximumScale = maximumScale(anchor, width, height, corner)
        val scale = minOf(requestedX, requestedY).coerceIn(
            minimumScale.coerceAtMost(maximumScale),
            maximumScale,
        )
        val scaled = strokes.map { stroke ->
            stroke.copy(
                id = idFactory(),
                samples = stroke.samples.map { sample ->
                    sample.copy(
                        x = (anchor.x + (sample.x - anchor.x) * scale).coerceIn(0f, 1f),
                        y = (anchor.y + (sample.y - anchor.y) * scale).coerceIn(0f, 1f),
                    )
                },
            )
        }
        return scaled.clampVisibleGeometry(viewport)
    }

    private fun oppositeCorner(bounds: NormalizedRect, corner: SelectionCorner): NormalizedPoint =
        when (corner) {
            SelectionCorner.TOP_LEFT -> NormalizedPoint(bounds.right, bounds.bottom)
            SelectionCorner.TOP_RIGHT -> NormalizedPoint(bounds.left, bounds.bottom)
            SelectionCorner.BOTTOM_LEFT -> NormalizedPoint(bounds.right, bounds.top)
            SelectionCorner.BOTTOM_RIGHT -> NormalizedPoint(bounds.left, bounds.top)
        }

    private fun maximumScale(
        anchor: NormalizedPoint,
        width: Float,
        height: Float,
        corner: SelectionCorner,
    ): Float = when (corner) {
        SelectionCorner.TOP_LEFT -> minOf(anchor.x / width, anchor.y / height)
        SelectionCorner.TOP_RIGHT -> minOf((1f - anchor.x) / width, anchor.y / height)
        SelectionCorner.BOTTOM_LEFT -> minOf(anchor.x / width, (1f - anchor.y) / height)
        SelectionCorner.BOTTOM_RIGHT -> minOf((1f - anchor.x) / width, (1f - anchor.y) / height)
    }.coerceAtLeast(1f)

    private fun List<StrokeRecord>.clampVisibleGeometry(viewport: CanvasViewport): List<StrokeRecord> {
        val bounds = visibleBounds(viewport) ?: return this
        val shiftX = when {
            bounds.left < 0f -> -bounds.left
            bounds.right > 1f -> 1f - bounds.right
            else -> 0f
        }
        val shiftY = when {
            bounds.top < 0f -> -bounds.top
            bounds.bottom > 1f -> 1f - bounds.bottom
            else -> 0f
        }
        if (shiftX == 0f && shiftY == 0f) return this
        return map { stroke ->
            stroke.copy(
                samples = stroke.samples.map { sample ->
                    sample.copy(
                        x = (sample.x + shiftX).coerceIn(0f, 1f),
                        y = (sample.y + shiftY).coerceIn(0f, 1f),
                    )
                },
            )
        }
    }

    private fun Collection<StrokeRecord>.visibleBounds(viewport: CanvasViewport): RawVisibleBounds? {
        var initialized = false
        var left = 1f
        var top = 1f
        var right = 0f
        var bottom = 0f
        forEach { stroke ->
            val radiusX = stroke.brush.width / (2f * viewport.widthPixels)
            val radiusY = stroke.brush.width / (2f * viewport.heightPixels)
            stroke.samples.forEach { sample ->
                initialized = true
                left = minOf(left, sample.x - radiusX)
                top = minOf(top, sample.y - radiusY)
                right = maxOf(right, sample.x + radiusX)
                bottom = maxOf(bottom, sample.y + radiusY)
            }
        }
        return if (initialized) RawVisibleBounds(left, top, right, bottom) else null
    }

    private data class RawVisibleBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )
}
