package com.putnamworkbook.offline.notebook.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.notebook.edit.CanvasViewport
import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import com.putnamworkbook.offline.notebook.edit.NotebookSelectionState
import com.putnamworkbook.offline.notebook.edit.SelectionCorner
import com.putnamworkbook.offline.notebook.edit.corner
import com.putnamworkbook.offline.notebook.edit.rotationHandle
import kotlin.math.roundToInt

const val ROTATION_HANDLE_TEST_TAG = "notebook-rotation-handle"
private const val SELECTION_CORNER_HANDLE_TEST_TAG_PREFIX = "notebook-selection-corner-"

internal data class NotebookCanvasPlacement(
    val topLeftInPane: Offset,
    val size: IntSize,
) {
    init {
        require(size.width > 0 && size.height > 0) { "Notebook canvas must have positive dimensions" }
    }

    val viewport: CanvasViewport
        get() = CanvasViewport(size.width.toFloat(), size.height.toFloat())

    fun toPaneOffset(point: NormalizedPoint): Offset = Offset(
        x = topLeftInPane.x + point.x * size.width,
        y = topLeftInPane.y + point.y * size.height,
    )

    fun toCanvasPoint(pointInPane: Offset): NormalizedPoint = NormalizedPoint(
        x = (pointInPane.x - topLeftInPane.x) / size.width,
        y = (pointInPane.y - topLeftInPane.y) / size.height,
    )
}

internal fun selectionCornerHandleTestTag(corner: SelectionCorner): String =
    SELECTION_CORNER_HANDLE_TEST_TAG_PREFIX + corner.name.lowercase().replace('_', '-')

@Composable
internal fun SelectionHandleOverlay(
    selection: NotebookSelectionState,
    canvasPlacement: NotebookCanvasPlacement,
    onPreviewScale: (SelectionCorner, NormalizedPoint) -> Unit,
    onCommitScale: (SelectionCorner, NormalizedPoint) -> Unit,
    onPreviewRotation: (Float) -> Unit,
    onCommitRotation: () -> Unit,
    onCancelPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rotationStemPixels = with(density) { ROTATION_STEM_LENGTH.toPx() }
    val frame = selection.displayFrame(canvasPlacement.viewport)
    val gestureKey = selection.selectedStrokeIds to selection.frame

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        SelectionCorner.entries.forEach { corner ->
            SelectionDragTarget(
                centerInPane = canvasPlacement.toPaneOffset(frame.corner(corner)),
                canvasPlacement = canvasPlacement,
                gestureKey = gestureKey,
                testTag = selectionCornerHandleTestTag(corner),
                onDrag = { point -> onPreviewScale(corner, point) },
                onDragEnd = { point -> point?.let { onCommitScale(corner, it) } },
                onDragCancel = onCancelPreview,
            )
        }

        var rotationBaseline by remember(gestureKey, canvasPlacement) {
            mutableStateOf<RotationGestureBaseline?>(null)
        }
        SelectionDragTarget(
            centerInPane = canvasPlacement.toPaneOffset(frame.rotationHandle(rotationStemPixels)),
            canvasPlacement = canvasPlacement,
            gestureKey = gestureKey,
            testTag = ROTATION_HANDLE_TEST_TAG,
            semanticsModifier = Modifier.semantics {
                contentDescription = "Rotate selection"
                customActions = listOf(
                    CustomAccessibilityAction("Rotate clockwise 15 degrees") {
                        onPreviewRotation(frame.rotationRadians + ROTATION_ACCESSIBILITY_STEP_RADIANS)
                        onCommitRotation()
                        true
                    },
                    CustomAccessibilityAction("Rotate counterclockwise 15 degrees") {
                        onPreviewRotation(frame.rotationRadians - ROTATION_ACCESSIBILITY_STEP_RADIANS)
                        onCommitRotation()
                        true
                    },
                )
            },
            onDragStart = { point ->
                rotationBaseline = RotationGestureBaseline.start(frame, point)
            },
            onDrag = { point ->
                rotationBaseline?.let { baseline -> onPreviewRotation(baseline.angleFor(point)) }
            },
            onDragEnd = {
                rotationBaseline = null
                onCommitRotation()
            },
            onDragCancel = {
                rotationBaseline = null
                onCancelPreview()
            },
        )
    }
}

@Composable
private fun SelectionDragTarget(
    centerInPane: Offset,
    canvasPlacement: NotebookCanvasPlacement,
    gestureKey: Any,
    testTag: String,
    onDragStart: (NormalizedPoint) -> Unit = {},
    onDrag: (NormalizedPoint) -> Unit,
    onDragEnd: (NormalizedPoint?) -> Unit,
    onDragCancel: () -> Unit,
    semanticsModifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hitRadiusPixels = with(density) { HANDLE_TARGET_RADIUS.toPx() }
    val latestCenter by rememberUpdatedState(centerInPane)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onDragCancel)
    var lockedCenter by remember(gestureKey, canvasPlacement) { mutableStateOf<Offset?>(null) }
    var currentPoint by remember(gestureKey, canvasPlacement) { mutableStateOf<NormalizedPoint?>(null) }
    val displayedCenter = lockedCenter ?: centerInPane

    Box(
        modifier = Modifier
            .absoluteOffset {
                IntOffset(
                    x = (displayedCenter.x - hitRadiusPixels).roundToInt(),
                    y = (displayedCenter.y - hitRadiusPixels).roundToInt(),
                )
            }
            .size(HANDLE_TARGET_SIZE)
            .testTag(testTag)
            .then(semanticsModifier)
            .pointerInput(gestureKey, canvasPlacement) {
                var gestureCenter = Offset.Zero
                detectDragGestures(
                    orientationLock = null,
                    onDragStart = { down, _, _ ->
                        gestureCenter = latestCenter
                        lockedCenter = gestureCenter
                        currentPoint = canvasPlacement.toCanvasPoint(
                            gestureCenter - Offset(hitRadiusPixels, hitRadiusPixels) + down.position,
                        )
                        latestOnDragStart(requireNotNull(currentPoint))
                    },
                    onDragEnd = {
                        latestOnDragEnd(currentPoint)
                        lockedCenter = null
                        currentPoint = null
                    },
                    onDragCancel = {
                        latestOnDragCancel()
                        lockedCenter = null
                        currentPoint = null
                    },
                    shouldAwaitTouchSlop = { true },
                    onDrag = { change, _ ->
                        change.consume()
                        val point = canvasPlacement.toCanvasPoint(
                            gestureCenter - Offset(hitRadiusPixels, hitRadiusPixels) + change.position,
                        )
                        currentPoint = point
                        latestOnDrag(point)
                    },
                )
            },
    )
}

private val HANDLE_TARGET_SIZE = 48.dp
private val HANDLE_TARGET_RADIUS = 24.dp
private val ROTATION_ACCESSIBILITY_STEP_RADIANS = Math.toRadians(15.0).toFloat()
