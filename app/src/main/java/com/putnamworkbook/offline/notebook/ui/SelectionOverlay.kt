package com.putnamworkbook.offline.notebook.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.putnamworkbook.offline.notebook.edit.NotebookSelectionState
import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.edit.SelectionCorner
import com.putnamworkbook.offline.notebook.edit.selectionBounds

const val SELECTION_OVERLAY_TEST_TAG = "notebook-selection-overlay"

@Composable
fun SelectionOverlay(
    selection: NotebookSelectionState?,
    onSelect: (NormalizedRect) -> Unit,
    onPreviewMove: (Float, Float) -> Unit,
    onCommitMove: (Float, Float) -> Unit,
    onPreviewScale: (SelectionCorner, NormalizedPoint) -> Unit,
    onCommitScale: (SelectionCorner, NormalizedPoint) -> Unit,
    onCancelPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var gesture by remember { mutableStateOf<SelectionGesture?>(null) }
    var currentPoint by remember { mutableStateOf<NormalizedPoint?>(null) }
    Canvas(
        modifier = modifier
            .testTag(SELECTION_OVERLAY_TEST_TAG)
            .pointerInput(selection?.selectedStrokeIds) {
                detectDragGestures(
                    onDragStart = { start ->
                        val point = start.normalized(size.width, size.height)
                        gesture = selection?.let { existing ->
                            existing.hitCorner(start, size.width.toFloat(), size.height.toFloat())?.let {
                                SelectionGesture.Scale(it, point)
                            } ?: if (existing.bounds.contains(point)) {
                                SelectionGesture.Move(point)
                            } else null
                        } ?: SelectionGesture.Marquee(point)
                        currentPoint = point
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val point = change.position.normalized(size.width, size.height)
                        currentPoint = point
                        when (val active = gesture) {
                            is SelectionGesture.Move -> onPreviewMove(point.x - active.start.x, point.y - active.start.y)
                            is SelectionGesture.Scale -> onPreviewScale(active.corner, point)
                            else -> Unit
                        }
                    },
                    onDragEnd = {
                        val end = currentPoint
                        when (val active = gesture) {
                            is SelectionGesture.Marquee -> if (end != null) onSelect(NormalizedRect.fromPoints(active.start, end))
                            is SelectionGesture.Move -> if (end != null) onCommitMove(end.x - active.start.x, end.y - active.start.y)
                            is SelectionGesture.Scale -> if (end != null) onCommitScale(active.corner, end)
                            null -> Unit
                        }
                        gesture = null
                        currentPoint = null
                    },
                    onDragCancel = {
                        onCancelPreview()
                        gesture = null
                        currentPoint = null
                    },
                )
            },
    ) {
        val previewBounds = selection?.previewStrokes?.selectionBounds()
        val bounds = previewBounds ?: selection?.bounds
        bounds?.let {
            val topLeft = Offset(it.left * size.width, it.top * size.height)
            val rectSize = Size(it.width * size.width, it.height * size.height)
            drawRect(Color(0xFF2563EB), topLeft, rectSize, style = Stroke(width = 3f))
            it.corners().forEach { point ->
                drawCircle(Color.White, 10f, Offset(point.x * size.width, point.y * size.height))
                drawCircle(Color(0xFF2563EB), 10f, Offset(point.x * size.width, point.y * size.height), style = Stroke(3f))
            }
        }
        val marquee = (gesture as? SelectionGesture.Marquee)?.let { active ->
            currentPoint?.let { NormalizedRect.fromPoints(active.start, it) }
        }
        marquee?.let {
            drawRect(
                Color(0xFF2563EB),
                Offset(it.left * size.width, it.top * size.height),
                Size(it.width * size.width, it.height * size.height),
                style = Stroke(width = 2f),
            )
        }
    }
}

private sealed interface SelectionGesture {
    data class Marquee(val start: NormalizedPoint) : SelectionGesture
    data class Move(val start: NormalizedPoint) : SelectionGesture
    data class Scale(val corner: SelectionCorner, val start: NormalizedPoint) : SelectionGesture
}

private fun Offset.normalized(width: Int, height: Int): NormalizedPoint = NormalizedPoint(
    (x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
    (y / height.coerceAtLeast(1)).coerceIn(0f, 1f),
)

private fun NormalizedRect.contains(point: NormalizedPoint): Boolean =
    point.x in left..right && point.y in top..bottom

private fun NormalizedRect.corners(): List<NormalizedPoint> = listOf(
    NormalizedPoint(left, top),
    NormalizedPoint(right, top),
    NormalizedPoint(left, bottom),
    NormalizedPoint(right, bottom),
)

private fun NotebookSelectionState.hitCorner(
    point: Offset,
    width: Float,
    height: Float,
): SelectionCorner? {
    val threshold = 28f
    val corners = listOf(
        SelectionCorner.TOP_LEFT to Offset(bounds.left * width, bounds.top * height),
        SelectionCorner.TOP_RIGHT to Offset(bounds.right * width, bounds.top * height),
        SelectionCorner.BOTTOM_LEFT to Offset(bounds.left * width, bounds.bottom * height),
        SelectionCorner.BOTTOM_RIGHT to Offset(bounds.right * width, bounds.bottom * height),
    )
    return corners.firstOrNull { (_, handle) -> (handle - point).getDistance() <= threshold }?.first
}
