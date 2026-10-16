package com.putnamworkbook.offline.notebook.edit

import kotlin.math.cos
import kotlin.math.sin

data class OrientedSelectionFrame(
    val center: NormalizedPoint,
    val widthPixels: Float,
    val heightPixels: Float,
    val rotationRadians: Float,
    val viewport: CanvasViewport,
) {
    init {
        require(center.x.isFinite() && center.y.isFinite()) {
            "Selection center must be finite"
        }
        require(center.x in 0f..1f && center.y in 0f..1f) {
            "Selection center must be page-normalized"
        }
        require(widthPixels.isFinite() && widthPixels > 0f) {
            "Selection width must be finite and positive"
        }
        require(heightPixels.isFinite() && heightPixels > 0f) {
            "Selection height must be finite and positive"
        }
        require(rotationRadians.isFinite()) { "Selection rotation must be finite" }
    }
}

fun NormalizedRect.toOrientedFrame(viewport: CanvasViewport): OrientedSelectionFrame =
    OrientedSelectionFrame(
        center = NormalizedPoint(
            x = (left + right) / 2f,
            y = (top + bottom) / 2f,
        ),
        widthPixels = width * viewport.widthPixels,
        heightPixels = height * viewport.heightPixels,
        rotationRadians = 0f,
        viewport = viewport,
    )

fun OrientedSelectionFrame.contains(point: NormalizedPoint): Boolean {
    val local = toLocalPixels(point)
    return kotlin.math.abs(local.x) <= widthPixels / 2f + GEOMETRY_EPSILON &&
        kotlin.math.abs(local.y) <= heightPixels / 2f + GEOMETRY_EPSILON
}

fun OrientedSelectionFrame.corner(corner: SelectionCorner): NormalizedPoint {
    val halfWidth = widthPixels / 2f
    val halfHeight = heightPixels / 2f
    val localX = when (corner) {
        SelectionCorner.TOP_LEFT, SelectionCorner.BOTTOM_LEFT -> -halfWidth
        SelectionCorner.TOP_RIGHT, SelectionCorner.BOTTOM_RIGHT -> halfWidth
    }
    val localY = when (corner) {
        SelectionCorner.TOP_LEFT, SelectionCorner.TOP_RIGHT -> -halfHeight
        SelectionCorner.BOTTOM_LEFT, SelectionCorner.BOTTOM_RIGHT -> halfHeight
    }
    return localPixelsToNormalized(localX, localY)
}

fun OrientedSelectionFrame.hitCorner(
    point: NormalizedPoint,
    radiusPixels: Float,
): SelectionCorner? {
    require(radiusPixels.isFinite() && radiusPixels >= 0f) {
        "Corner hit radius must be finite and non-negative"
    }
    val pointX = point.x * viewport.widthPixels
    val pointY = point.y * viewport.heightPixels
    val radiusSquared = radiusPixels * radiusPixels
    return SelectionCorner.entries.firstOrNull { candidate ->
        val corner = corner(candidate)
        val dx = pointX - corner.x * viewport.widthPixels
        val dy = pointY - corner.y * viewport.heightPixels
        dx * dx + dy * dy <= radiusSquared + GEOMETRY_EPSILON
    }
}

fun OrientedSelectionFrame.rotationHandle(stemPixels: Float): NormalizedPoint {
    require(stemPixels.isFinite() && stemPixels >= 0f) {
        "Rotation handle stem must be finite and non-negative"
    }
    return localPixelsToNormalized(0f, -heightPixels / 2f - stemPixels)
}

fun OrientedSelectionFrame.hitRotationHandle(
    point: NormalizedPoint,
    radiusPixels: Float,
    stemPixels: Float,
): Boolean {
    require(radiusPixels.isFinite() && radiusPixels >= 0f) {
        "Rotation handle hit radius must be finite and non-negative"
    }
    val handle = rotationHandle(stemPixels)
    val dx = (point.x - handle.x) * viewport.widthPixels
    val dy = (point.y - handle.y) * viewport.heightPixels
    return dx * dx + dy * dy <= radiusPixels * radiusPixels + GEOMETRY_EPSILON
}

private fun OrientedSelectionFrame.toLocalPixels(point: NormalizedPoint): LocalPoint {
    val dx = (point.x - center.x) * viewport.widthPixels
    val dy = (point.y - center.y) * viewport.heightPixels
    val cosine = cos(rotationRadians)
    val sine = sin(rotationRadians)
    return LocalPoint(
        x = dx * cosine + dy * sine,
        y = -dx * sine + dy * cosine,
    )
}

private fun OrientedSelectionFrame.localPixelsToNormalized(
    localX: Float,
    localY: Float,
): NormalizedPoint {
    val cosine = cos(rotationRadians)
    val sine = sin(rotationRadians)
    val rotatedX = localX * cosine - localY * sine
    val rotatedY = localX * sine + localY * cosine
    return NormalizedPoint(
        x = center.x + rotatedX / viewport.widthPixels,
        y = center.y + rotatedY / viewport.heightPixels,
    )
}

private data class LocalPoint(val x: Float, val y: Float)

private const val GEOMETRY_EPSILON = 0.0001f
