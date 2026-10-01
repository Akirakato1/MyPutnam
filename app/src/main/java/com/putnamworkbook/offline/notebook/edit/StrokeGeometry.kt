package com.putnamworkbook.offline.notebook.edit

import com.putnamworkbook.offline.notebook.model.StrokeRecord

private const val REFERENCE_PAGE_SIZE = 1_000f

fun StrokeRecord.intersects(rect: NormalizedRect): Boolean {
    val radius = brush.width / (REFERENCE_PAGE_SIZE * 2f)
    val expanded = RawRect(
        rect.left - radius,
        rect.top - radius,
        rect.right + radius,
        rect.bottom + radius,
    )
    if (samples.size == 1) return expanded.contains(samples.single().x, samples.single().y)
    return samples.zipWithNext().any { (start, end) ->
        segmentIntersects(start.x, start.y, end.x, end.y, expanded)
    }
}

fun Collection<StrokeRecord>.selectionBounds(): NormalizedRect? {
    if (isEmpty()) return null
    var left = 1f
    var top = 1f
    var right = 0f
    var bottom = 0f
    forEach { stroke ->
        val radius = stroke.brush.width / (REFERENCE_PAGE_SIZE * 2f)
        stroke.samples.forEach { sample ->
            left = minOf(left, sample.x - radius)
            top = minOf(top, sample.y - radius)
            right = maxOf(right, sample.x + radius)
            bottom = maxOf(bottom, sample.y + radius)
        }
    }
    return NormalizedRect(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f),
    )
}

private data class RawRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

private fun segmentIntersects(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    rect: RawRect,
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
