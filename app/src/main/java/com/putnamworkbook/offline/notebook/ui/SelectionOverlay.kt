package com.putnamworkbook.offline.notebook.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.notebook.edit.CanvasViewport
import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.edit.NotebookSelectionState
import com.putnamworkbook.offline.notebook.edit.OrientedSelectionFrame
import com.putnamworkbook.offline.notebook.edit.SelectionCorner
import com.putnamworkbook.offline.notebook.edit.contains
import com.putnamworkbook.offline.notebook.edit.corner
import com.putnamworkbook.offline.notebook.edit.hitCorner
import com.putnamworkbook.offline.notebook.edit.hitRotationHandle
import com.putnamworkbook.offline.notebook.edit.rotationHandle
import com.putnamworkbook.offline.notebook.edit.toOrientedFrame
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

const val SELECTION_OVERLAY_TEST_TAG = "notebook-selection-overlay"

@Composable
fun SelectionOverlay(
    selection: NotebookSelectionState?,
    onSelect: (NormalizedRect, CanvasViewport) -> Unit,
    onPreviewMove: (Float, Float) -> Unit,
    onCommitMove: (Float, Float) -> Unit,
    onPreviewScale: (SelectionCorner, NormalizedPoint) -> Unit,
    onCommitScale: (SelectionCorner, NormalizedPoint) -> Unit,
    onPreviewRotation: (Float) -> Unit,
    onCommitRotation: () -> Unit,
    onCancelPreview: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rotationStemPixels = with(density) { ROTATION_STEM_LENGTH.toPx() }
    val handleHitRadiusPixels = with(density) { HANDLE_HIT_RADIUS.toPx() }
    val handleRadiusPixels = with(density) { HANDLE_VISIBLE_RADIUS.toPx() }
    val handleStrokePixels = with(density) { HANDLE_STROKE_WIDTH.toPx() }
    val frameStrokePixels = with(density) { FRAME_STROKE_WIDTH.toPx() }
    var gesture by remember { mutableStateOf<SelectionGesture?>(null) }
    var currentPoint by remember { mutableStateOf<NormalizedPoint?>(null) }
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SELECTION_OVERLAY_TEST_TAG)
                .pointerInput(selection?.frame) {
                    detectTapGestures { tap ->
                        val viewport = CanvasViewport(size.width.toFloat(), size.height.toFloat())
                        val point = tap.normalized(size.width, size.height)
                        val frame = selection?.displayFrame(viewport)
                        if (
                            frame != null &&
                            !frame.contains(point) &&
                            frame.hitCorner(point, handleHitRadiusPixels) == null &&
                            !frame.hitRotationHandle(point, handleHitRadiusPixels, rotationStemPixels)
                        ) {
                            onClearSelection()
                        }
                    }
                }
                .pointerInput(selection?.selectedStrokeIds, selection?.frame) {
                    detectDragGestures(
                        orientationLock = null,
                        onDragStart = { down, _, _ ->
                            val viewport = CanvasViewport(size.width.toFloat(), size.height.toFloat())
                            val point = down.position.normalized(size.width, size.height)
                            val frame = selection?.displayFrame(viewport)
                            val isRotationHandle = frame?.hitRotationHandle(
                                point,
                                handleHitRadiusPixels,
                                rotationStemPixels,
                            ) == true
                            val corner = frame?.hitCorner(point, handleHitRadiusPixels)
                            gesture = when (
                                resolveSelectionDragIntent(
                                    isRotationHandle = isRotationHandle,
                                    isCorner = corner != null,
                                    isInsideSelection = frame?.contains(point) == true,
                                )
                            ) {
                                SelectionDragIntent.MARQUEE -> SelectionGesture.Marquee(point)
                                SelectionDragIntent.MOVE -> SelectionGesture.Move(point)
                                SelectionDragIntent.SCALE -> SelectionGesture.Scale(requireNotNull(corner), point)
                                SelectionDragIntent.ROTATE -> SelectionGesture.Rotate(
                                    initialPointerAngle = requireNotNull(frame).pointerAngle(point),
                                    initialFrameAngle = frame.rotationRadians,
                                )
                            }
                            currentPoint = point
                        },
                        onDragEnd = {
                            val end = currentPoint
                            when (val active = gesture) {
                                is SelectionGesture.Marquee -> if (end != null) {
                                    onSelect(
                                        NormalizedRect.fromPoints(active.start, end),
                                        CanvasViewport(size.width.toFloat(), size.height.toFloat()),
                                    )
                                }
                                is SelectionGesture.Move -> if (end != null) {
                                    onCommitMove(end.x - active.start.x, end.y - active.start.y)
                                }
                                is SelectionGesture.Scale -> if (end != null) onCommitScale(active.corner, end)
                                is SelectionGesture.Rotate -> onCommitRotation()
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
                        shouldAwaitTouchSlop = { true },
                        onDrag = { change, _ ->
                            change.consume()
                            val point = change.position.normalized(size.width, size.height)
                            currentPoint = point
                            when (val active = gesture) {
                                is SelectionGesture.Move -> onPreviewMove(point.x - active.start.x, point.y - active.start.y)
                                is SelectionGesture.Scale -> onPreviewScale(active.corner, point)
                                is SelectionGesture.Rotate -> {
                                    val viewport = CanvasViewport(size.width.toFloat(), size.height.toFloat())
                                    val frame = selection?.displayFrame(viewport) ?: return@detectDragGestures
                                    onPreviewRotation(
                                        resolveRotationAngle(
                                            initialFrameAngle = active.initialFrameAngle,
                                            initialPointerAngle = active.initialPointerAngle,
                                            currentPointerAngle = frame.pointerAngle(point),
                                        ),
                                    )
                                }
                                else -> Unit
                            }
                        },
                    )
                },
        ) {
            val viewport = CanvasViewport(size.width, size.height)
            selection?.displayFrame(viewport)?.let { frame ->
                val corners = listOf(
                    frame.corner(SelectionCorner.TOP_LEFT),
                    frame.corner(SelectionCorner.TOP_RIGHT),
                    frame.corner(SelectionCorner.BOTTOM_RIGHT),
                    frame.corner(SelectionCorner.BOTTOM_LEFT),
                ).map { it.toOffset(size.width, size.height) }
                corners.indices.forEach { index ->
                    drawLine(
                        color = FRAME_COLOR,
                        start = corners[index],
                        end = corners[(index + 1) % corners.size],
                        strokeWidth = frameStrokePixels,
                    )
                }
                val topCenter = Offset(
                    x = (corners[0].x + corners[1].x) / 2f,
                    y = (corners[0].y + corners[1].y) / 2f,
                )
                val rotationHandle = frame.rotationHandle(rotationStemPixels).toOffset(size.width, size.height)
                drawLine(FRAME_COLOR, topCenter, rotationHandle, strokeWidth = frameStrokePixels)
                corners.forEach { handle ->
                    drawCircle(Color.White, handleRadiusPixels, handle)
                    drawCircle(FRAME_COLOR, handleRadiusPixels, handle, style = Stroke(handleStrokePixels))
                }
                drawCircle(Color.White, handleRadiusPixels, rotationHandle)
                drawCircle(FRAME_COLOR, handleRadiusPixels, rotationHandle, style = Stroke(handleStrokePixels))
            }
            val marquee = (gesture as? SelectionGesture.Marquee)?.let { active ->
                currentPoint?.let { NormalizedRect.fromPoints(active.start, it) }
            }
            marquee?.let {
                drawRect(
                    FRAME_COLOR,
                    Offset(it.left * size.width, it.top * size.height),
                    androidx.compose.ui.geometry.Size(it.width * size.width, it.height * size.height),
                    style = Stroke(width = frameStrokePixels),
                )
            }
        }
    }
}

private sealed interface SelectionGesture {
    data class Marquee(val start: NormalizedPoint) : SelectionGesture
    data class Move(val start: NormalizedPoint) : SelectionGesture
    data class Scale(val corner: SelectionCorner, val start: NormalizedPoint) : SelectionGesture
    data class Rotate(
        val initialPointerAngle: Float,
        val initialFrameAngle: Float,
    ) : SelectionGesture
}

internal enum class SelectionDragIntent {
    MARQUEE,
    MOVE,
    SCALE,
    ROTATE,
}

internal fun resolveSelectionDragIntent(
    isRotationHandle: Boolean,
    isCorner: Boolean,
    isInsideSelection: Boolean,
): SelectionDragIntent = when {
    isRotationHandle -> SelectionDragIntent.ROTATE
    isCorner -> SelectionDragIntent.SCALE
    isInsideSelection -> SelectionDragIntent.MOVE
    else -> SelectionDragIntent.MARQUEE
}

internal fun resolveRotationAngle(
    initialFrameAngle: Float,
    initialPointerAngle: Float,
    currentPointerAngle: Float,
): Float = initialFrameAngle + shortestAngle(currentPointerAngle - initialPointerAngle)

internal class RotationGestureBaseline private constructor(
    private val frame: OrientedSelectionFrame,
    private val initialPointerAngle: Float,
) {
    fun angleFor(pointer: NormalizedPoint): Float = resolveRotationAngle(
        initialFrameAngle = frame.rotationRadians,
        initialPointerAngle = initialPointerAngle,
        currentPointerAngle = frame.pointerAngle(pointer),
    )

    companion object {
        fun start(frame: OrientedSelectionFrame, pointer: NormalizedPoint): RotationGestureBaseline =
            RotationGestureBaseline(frame, frame.pointerAngle(pointer))
    }
}

private fun Offset.normalized(width: Int, height: Int): NormalizedPoint = NormalizedPoint(
    x = x / width.coerceAtLeast(1),
    y = y / height.coerceAtLeast(1),
)

private fun NormalizedPoint.toOffset(width: Float, height: Float): Offset = Offset(
    x = x * width,
    y = y * height,
)

internal fun NotebookSelectionState.displayFrame(viewport: CanvasViewport): OrientedSelectionFrame {
    val candidate = previewFrame ?: frame
    return if (candidate.viewport == viewport) candidate else bounds.toOrientedFrame(viewport)
}

internal fun OrientedSelectionFrame.pointerAngle(point: NormalizedPoint): Float {
    val dx = (point.x - center.x) * viewport.widthPixels
    val dy = (point.y - center.y) * viewport.heightPixels
    return atan2(dy, dx)
}

private fun shortestAngle(angle: Float): Float = atan2(sin(angle), cos(angle))

private val FRAME_COLOR = Color(0xFF2563EB)
internal val ROTATION_STEM_LENGTH = 32.dp
private val HANDLE_HIT_RADIUS = 24.dp
private val HANDLE_VISIBLE_RADIUS = 8.dp
private val HANDLE_STROKE_WIDTH = 2.dp
private val FRAME_STROKE_WIDTH = 2.dp
