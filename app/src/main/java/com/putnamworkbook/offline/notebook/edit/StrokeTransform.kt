package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

enum class SelectionFlipAxis {
    HORIZONTAL,
    VERTICAL,
}

data class SelectionTransformResult(
    val strokes: List<StrokeRecord>,
    val frame: OrientedSelectionFrame,
)

fun snapRotationRadians(angleRadians: Float, incrementDegrees: Int = 15): Float {
    require(angleRadians.isFinite()) { "Rotation angle must be finite" }
    require(incrementDegrees > 0) { "Rotation increment must be positive" }
    val increment = (incrementDegrees * PI / 180.0).toFloat()
    return round(angleRadians / increment) * increment
}

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

    fun rotate(
        strokes: List<StrokeRecord>,
        frame: OrientedSelectionFrame,
        requestedAngleRadians: Float,
        viewport: CanvasViewport,
        idFactory: () -> String,
    ): SelectionTransformResult? {
        require(requestedAngleRadians.isFinite()) { "Rotation angle must be finite" }
        requireMatchingViewport(frame, viewport)
        if (strokes.isEmpty()) return SelectionTransformResult(emptyList(), frame)
        val delta = requestedAngleRadians - frame.rotationRadians
        val cosine = cos(delta)
        val sine = sin(delta)
        val center = frame.center.toPixels(viewport)
        val transformed = strokes.toPixelStrokes(viewport).mapPoints { point ->
            val dx = point.x - center.x
            val dy = point.y - center.y
            PixelPoint(
                x = center.x + dx * cosine - dy * sine,
                y = center.y + dx * sine + dy * cosine,
            )
        }
        return transformed.fitToPage(viewport, frame.copy(rotationRadians = requestedAngleRadians), idFactory)
    }

    fun flip(
        strokes: List<StrokeRecord>,
        frame: OrientedSelectionFrame,
        axis: SelectionFlipAxis,
        viewport: CanvasViewport,
        idFactory: () -> String,
    ): SelectionTransformResult? {
        requireMatchingViewport(frame, viewport)
        if (strokes.isEmpty()) return SelectionTransformResult(emptyList(), frame)
        val transformed = strokes.toPixelStrokes(viewport).mapPoints { point ->
            val local = frame.pixelToLocal(point)
            frame.localToPixel(
                when (axis) {
                    SelectionFlipAxis.HORIZONTAL -> PixelPoint(-local.x, local.y)
                    SelectionFlipAxis.VERTICAL -> PixelPoint(local.x, -local.y)
                },
            )
        }
        return transformed.fitToPage(viewport, frame, idFactory)
    }

    fun scaleFromCorner(
        strokes: List<StrokeRecord>,
        frame: OrientedSelectionFrame,
        corner: SelectionCorner,
        target: NormalizedPoint,
        viewport: CanvasViewport,
        idFactory: () -> String,
    ): SelectionTransformResult? {
        requireMatchingViewport(frame, viewport)
        if (strokes.isEmpty()) return SelectionTransformResult(emptyList(), frame)
        val anchor = frame.localCorner(opposite(corner))
        val dragged = frame.localCorner(corner)
        val targetLocal = frame.pixelToLocal(target.toPixels(viewport))
        val requestedScale = minOf(
            abs(targetLocal.x - anchor.x) / abs(dragged.x - anchor.x),
            abs(targetLocal.y - anchor.y) / abs(dragged.y - anchor.y),
        )
        val minimumScale = maxOf(
            MIN_SELECTION_SIZE * viewport.widthPixels / frame.widthPixels,
            MIN_SELECTION_SIZE * viewport.heightPixels / frame.heightPixels,
        )
        val desiredScale = requestedScale.coerceAtLeast(minimumScale)
        val source = strokes.toPixelStrokes(viewport)

        fun scaled(scale: Float): ScaledSelection {
            val transformed = source.mapPoints { point ->
                val local = frame.pixelToLocal(point)
                frame.localToPixel(
                    PixelPoint(
                        x = anchor.x + (local.x - anchor.x) * scale,
                        y = anchor.y + (local.y - anchor.y) * scale,
                    ),
                )
            }
            val centerPixels = frame.localToPixel(
                PixelPoint(
                    x = anchor.x * (1f - scale),
                    y = anchor.y * (1f - scale),
                ),
            )
            return ScaledSelection(
                strokes = transformed,
                centerPixels = centerPixels,
                widthPixels = frame.widthPixels * scale,
                heightPixels = frame.heightPixels * scale,
            )
        }

        var chosen = scaled(desiredScale)
        if (!chosen.strokes.fitsPage(viewport) && desiredScale > 1f) {
            var lower = 1f
            var upper = desiredScale
            var best = scaled(lower)
            if (!best.strokes.fitsPage(viewport)) {
                return best.strokes.fitToPage(
                    viewport = viewport,
                    frameCenterPixels = best.centerPixels,
                    frameWidthPixels = best.widthPixels,
                    frameHeightPixels = best.heightPixels,
                    frameRotationRadians = frame.rotationRadians,
                    idFactory = idFactory,
                )
            }
            repeat(SCALE_SEARCH_STEPS) {
                val candidateScale = (lower + upper) / 2f
                val candidate = scaled(candidateScale)
                if (candidate.strokes.fitsPage(viewport)) {
                    lower = candidateScale
                    best = candidate
                } else {
                    upper = candidateScale
                }
            }
            chosen = best
        }
        return chosen.strokes.fitToPage(
            viewport = viewport,
            frameCenterPixels = chosen.centerPixels,
            frameWidthPixels = chosen.widthPixels,
            frameHeightPixels = chosen.heightPixels,
            frameRotationRadians = frame.rotationRadians,
            idFactory = idFactory,
        )
    }

    fun scaleFromCorner(
        strokes: List<StrokeRecord>,
        bounds: NormalizedRect,
        corner: SelectionCorner,
        target: NormalizedPoint,
        viewport: CanvasViewport,
        idFactory: () -> String,
    ): List<StrokeRecord> = scaleFromCorner(
        strokes = strokes,
        frame = bounds.toOrientedFrame(viewport),
        corner = corner,
        target = target,
        viewport = viewport,
        idFactory = idFactory,
    )?.strokes ?: strokes

    private fun requireMatchingViewport(frame: OrientedSelectionFrame, viewport: CanvasViewport) {
        require(frame.viewport == viewport) { "Selection frame viewport does not match the canvas" }
    }

    private fun opposite(corner: SelectionCorner): SelectionCorner = when (corner) {
        SelectionCorner.TOP_LEFT -> SelectionCorner.BOTTOM_RIGHT
        SelectionCorner.TOP_RIGHT -> SelectionCorner.BOTTOM_LEFT
        SelectionCorner.BOTTOM_LEFT -> SelectionCorner.TOP_RIGHT
        SelectionCorner.BOTTOM_RIGHT -> SelectionCorner.TOP_LEFT
    }

    private fun OrientedSelectionFrame.localCorner(corner: SelectionCorner): PixelPoint = PixelPoint(
        x = when (corner) {
            SelectionCorner.TOP_LEFT, SelectionCorner.BOTTOM_LEFT -> -widthPixels / 2f
            SelectionCorner.TOP_RIGHT, SelectionCorner.BOTTOM_RIGHT -> widthPixels / 2f
        },
        y = when (corner) {
            SelectionCorner.TOP_LEFT, SelectionCorner.TOP_RIGHT -> -heightPixels / 2f
            SelectionCorner.BOTTOM_LEFT, SelectionCorner.BOTTOM_RIGHT -> heightPixels / 2f
        },
    )

    private fun OrientedSelectionFrame.pixelToLocal(point: PixelPoint): PixelPoint {
        val centerPixels = center.toPixels(viewport)
        val dx = point.x - centerPixels.x
        val dy = point.y - centerPixels.y
        val cosine = cos(rotationRadians)
        val sine = sin(rotationRadians)
        return PixelPoint(dx * cosine + dy * sine, -dx * sine + dy * cosine)
    }

    private fun OrientedSelectionFrame.localToPixel(point: PixelPoint): PixelPoint {
        val centerPixels = center.toPixels(viewport)
        val cosine = cos(rotationRadians)
        val sine = sin(rotationRadians)
        return PixelPoint(
            x = centerPixels.x + point.x * cosine - point.y * sine,
            y = centerPixels.y + point.x * sine + point.y * cosine,
        )
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

    private fun List<StrokeRecord>.toPixelStrokes(viewport: CanvasViewport): List<PixelStroke> =
        map { stroke ->
            PixelStroke(
                source = stroke,
                points = stroke.samples.map { sample ->
                    PixelSample(
                        point = PixelPoint(
                            x = sample.x * viewport.widthPixels,
                            y = sample.y * viewport.heightPixels,
                        ),
                        source = sample,
                    )
                },
            )
        }

    private fun List<PixelStroke>.mapPoints(transform: (PixelPoint) -> PixelPoint): List<PixelStroke> =
        map { stroke ->
            stroke.copy(points = stroke.points.map { it.copy(point = transform(it.point)) })
        }

    private fun List<PixelStroke>.fitToPage(
        viewport: CanvasViewport,
        frame: OrientedSelectionFrame,
        idFactory: () -> String,
    ): SelectionTransformResult? = fitToPage(
        viewport = viewport,
        frameCenterPixels = frame.center.toPixels(viewport),
        frameWidthPixels = frame.widthPixels,
        frameHeightPixels = frame.heightPixels,
        frameRotationRadians = frame.rotationRadians,
        idFactory = idFactory,
    )

    private fun List<PixelStroke>.fitToPage(
        viewport: CanvasViewport,
        frameCenterPixels: PixelPoint,
        frameWidthPixels: Float,
        frameHeightPixels: Float,
        frameRotationRadians: Float,
        idFactory: () -> String,
    ): SelectionTransformResult? {
        val bounds = pixelBounds() ?: return SelectionTransformResult(
            emptyList(),
            OrientedSelectionFrame(
                center = frameCenterPixels.toNormalizedOrNull(viewport) ?: return null,
                widthPixels = frameWidthPixels,
                heightPixels = frameHeightPixels,
                rotationRadians = frameRotationRadians,
                viewport = viewport,
            ),
        )
        if (bounds.width > viewport.widthPixels + PIXEL_EPSILON ||
            bounds.height > viewport.heightPixels + PIXEL_EPSILON
        ) {
            return null
        }
        val shiftX = when {
            bounds.left < 0f -> -bounds.left
            bounds.right > viewport.widthPixels -> viewport.widthPixels - bounds.right
            else -> 0f
        }
        val shiftY = when {
            bounds.top < 0f -> -bounds.top
            bounds.bottom > viewport.heightPixels -> viewport.heightPixels - bounds.bottom
            else -> 0f
        }
        val shifted = if (shiftX == 0f && shiftY == 0f) this else mapPoints { point ->
            PixelPoint(point.x + shiftX, point.y + shiftY)
        }
        val shiftedCenter = PixelPoint(
            frameCenterPixels.x + shiftX,
            frameCenterPixels.y + shiftY,
        ).toNormalizedOrNull(viewport) ?: return null
        return SelectionTransformResult(
            strokes = shifted.materialize(viewport, idFactory),
            frame = OrientedSelectionFrame(
                center = shiftedCenter,
                widthPixels = frameWidthPixels,
                heightPixels = frameHeightPixels,
                rotationRadians = frameRotationRadians,
                viewport = viewport,
            ),
        )
    }

    private fun List<PixelStroke>.fitsPage(viewport: CanvasViewport): Boolean {
        val bounds = pixelBounds() ?: return true
        return bounds.left >= -PIXEL_EPSILON &&
            bounds.top >= -PIXEL_EPSILON &&
            bounds.right <= viewport.widthPixels + PIXEL_EPSILON &&
            bounds.bottom <= viewport.heightPixels + PIXEL_EPSILON
    }

    private fun List<PixelStroke>.pixelBounds(): PixelBounds? {
        if (isEmpty()) return null
        var initialized = false
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        forEach { stroke ->
            val radius = stroke.source.brush.width / 2f
            stroke.points.forEach { sample ->
                initialized = true
                left = minOf(left, sample.point.x - radius)
                top = minOf(top, sample.point.y - radius)
                right = maxOf(right, sample.point.x + radius)
                bottom = maxOf(bottom, sample.point.y + radius)
            }
        }
        return if (initialized) PixelBounds(left, top, right, bottom) else null
    }

    private fun List<PixelStroke>.materialize(
        viewport: CanvasViewport,
        idFactory: () -> String,
    ): List<StrokeRecord> = map { transformed ->
        transformed.source.copy(
            id = idFactory(),
            samples = transformed.points.map { pixelSample ->
                pixelSample.source.copy(
                    x = normalizedCoordinate(pixelSample.point.x, viewport.widthPixels),
                    y = normalizedCoordinate(pixelSample.point.y, viewport.heightPixels),
                )
            },
        )
    }

    private fun normalizedCoordinate(value: Float, extent: Float): Float {
        val normalized = value / extent
        return when {
            normalized in 0f..1f -> normalized
            normalized > -NORMALIZED_EPSILON && normalized < 0f -> 0f
            normalized > 1f && normalized < 1f + NORMALIZED_EPSILON -> 1f
            else -> error("Transformed sample lies outside the page")
        }
    }

    private fun NormalizedPoint.toPixels(viewport: CanvasViewport): PixelPoint = PixelPoint(
        x = x * viewport.widthPixels,
        y = y * viewport.heightPixels,
    )

    private fun PixelPoint.toNormalizedOrNull(viewport: CanvasViewport): NormalizedPoint? {
        val normalizedX = x / viewport.widthPixels
        val normalizedY = y / viewport.heightPixels
        if (normalizedX !in -NORMALIZED_EPSILON..1f + NORMALIZED_EPSILON ||
            normalizedY !in -NORMALIZED_EPSILON..1f + NORMALIZED_EPSILON
        ) {
            return null
        }
        return NormalizedPoint(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
    }

    private data class RawVisibleBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private data class PixelPoint(val x: Float, val y: Float)

    private data class PixelSample(
        val point: PixelPoint,
        val source: StrokeSample,
    )

    private data class PixelStroke(
        val source: StrokeRecord,
        val points: List<PixelSample>,
    )

    private data class PixelBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    private data class ScaledSelection(
        val strokes: List<PixelStroke>,
        val centerPixels: PixelPoint,
        val widthPixels: Float,
        val heightPixels: Float,
    )

    private const val SCALE_SEARCH_STEPS = 32
    private const val PIXEL_EPSILON = 0.001f
    private const val NORMALIZED_EPSILON = 0.00001f
}
