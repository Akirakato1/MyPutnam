package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.notebook.model.StrokeRecord

fun StrokeRecord.intersects(rect: NormalizedRect, viewport: CanvasViewport): Boolean {
    val pixelRect = PixelRect(
        rect.left * viewport.widthPixels,
        rect.top * viewport.heightPixels,
        rect.right * viewport.widthPixels,
        rect.bottom * viewport.heightPixels,
    )
    val radiusSquared = brush.width * brush.width / 4f
    if (samples.size == 1) {
        val sample = samples.single()
        return pointToRectDistanceSquared(
            sample.x * viewport.widthPixels,
            sample.y * viewport.heightPixels,
            pixelRect,
        ) <= radiusSquared + DISTANCE_EPSILON
    }
    return samples.zipWithNext().any { (start, end) ->
        segmentToRectDistanceSquared(
            start.x * viewport.widthPixels,
            start.y * viewport.heightPixels,
            end.x * viewport.widthPixels,
            end.y * viewport.heightPixels,
            pixelRect,
        ) <= radiusSquared + DISTANCE_EPSILON
    }
}

fun Collection<StrokeRecord>.selectionBounds(viewport: CanvasViewport): NormalizedRect? {
    var initialized = false
    var leftPixels = viewport.widthPixels
    var topPixels = viewport.heightPixels
    var rightPixels = 0f
    var bottomPixels = 0f
    forEach { stroke ->
        val radius = stroke.brush.width / 2f
        stroke.samples.forEach { sample ->
            initialized = true
            val x = sample.x * viewport.widthPixels
            val y = sample.y * viewport.heightPixels
            leftPixels = minOf(leftPixels, x - radius)
            topPixels = minOf(topPixels, y - radius)
            rightPixels = maxOf(rightPixels, x + radius)
            bottomPixels = maxOf(bottomPixels, y + radius)
        }
    }
    if (!initialized) return null
    return NormalizedRect(
        (leftPixels / viewport.widthPixels).coerceIn(0f, 1f),
        (topPixels / viewport.heightPixels).coerceIn(0f, 1f),
        (rightPixels / viewport.widthPixels).coerceIn(0f, 1f),
        (bottomPixels / viewport.heightPixels).coerceIn(0f, 1f),
    )
}

private data class PixelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

private fun pointToRectDistanceSquared(x: Float, y: Float, rect: PixelRect): Float {
    val dx = when {
        x < rect.left -> rect.left - x
        x > rect.right -> x - rect.right
        else -> 0f
    }
    val dy = when {
        y < rect.top -> rect.top - y
        y > rect.bottom -> y - rect.bottom
        else -> 0f
    }
    return dx * dx + dy * dy
}

private fun segmentToRectDistanceSquared(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    rect: PixelRect,
): Float {
    if (segmentIntersects(x1, y1, x2, y2, rect)) return 0f
    return minOf(
        pointToRectDistanceSquared(x1, y1, rect),
        pointToRectDistanceSquared(x2, y2, rect),
        pointToSegmentDistanceSquared(rect.left, rect.top, x1, y1, x2, y2),
        pointToSegmentDistanceSquared(rect.right, rect.top, x1, y1, x2, y2),
        pointToSegmentDistanceSquared(rect.right, rect.bottom, x1, y1, x2, y2),
        pointToSegmentDistanceSquared(rect.left, rect.bottom, x1, y1, x2, y2),
    )
}

private fun pointToSegmentDistanceSquared(
    px: Float,
    py: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) {
        val pointDx = px - x1
        val pointDy = py - y1
        return pointDx * pointDx + pointDy * pointDy
    }
    val position = (((px - x1) * dx + (py - y1) * dy) / lengthSquared).coerceIn(0f, 1f)
    val nearestX = x1 + position * dx
    val nearestY = y1 + position * dy
    val pointDx = px - nearestX
    val pointDy = py - nearestY
    return pointDx * pointDx + pointDy * pointDy
}

private fun segmentIntersects(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    rect: PixelRect,
): Boolean {
    if (rect.contains(x1, y1) || rect.contains(x2, y2)) return true
    return segmentsIntersect(x1, y1, x2, y2, rect.left, rect.top, rect.right, rect.top) ||
        segmentsIntersect(x1, y1, x2, y2, rect.right, rect.top, rect.right, rect.bottom) ||
        segmentsIntersect(x1, y1, x2, y2, rect.right, rect.bottom, rect.left, rect.bottom) ||
        segmentsIntersect(x1, y1, x2, y2, rect.left, rect.bottom, rect.left, rect.top)
}

private fun segmentsIntersect(
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    cx: Float,
    cy: Float,
    dx: Float,
    dy: Float,
): Boolean {
    val o1 = orientation(ax, ay, bx, by, cx, cy)
    val o2 = orientation(ax, ay, bx, by, dx, dy)
    val o3 = orientation(cx, cy, dx, dy, ax, ay)
    val o4 = orientation(cx, cy, dx, dy, bx, by)
    if (o1 == 0f && onSegment(ax, ay, bx, by, cx, cy)) return true
    if (o2 == 0f && onSegment(ax, ay, bx, by, dx, dy)) return true
    if (o3 == 0f && onSegment(cx, cy, dx, dy, ax, ay)) return true
    if (o4 == 0f && onSegment(cx, cy, dx, dy, bx, by)) return true
    return (o1 > 0f) != (o2 > 0f) && (o3 > 0f) != (o4 > 0f)
}

private fun orientation(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
    (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

private fun onSegment(ax: Float, ay: Float, bx: Float, by: Float, x: Float, y: Float): Boolean =
    x in minOf(ax, bx)..maxOf(ax, bx) && y in minOf(ay, by)..maxOf(ay, by)

private const val DISTANCE_EPSILON = 0.0001f
