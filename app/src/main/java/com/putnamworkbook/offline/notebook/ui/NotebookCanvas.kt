package com.putnamworkbook.offline.notebook.ui

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.notebook.input.MotionEventAdapter
import com.putnamworkbook.offline.notebook.input.PointerAction
import com.putnamworkbook.offline.notebook.input.TouchStrokeRecorder
import com.putnamworkbook.offline.notebook.input.FinishedStrokeBuffer
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.edit.NotebookSelectionState
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import com.putnamworkbook.offline.notebook.edit.NormalizedPoint
import com.putnamworkbook.offline.notebook.edit.NormalizedRect
import com.putnamworkbook.offline.notebook.edit.SelectionCorner

const val NOTEBOOK_CANVAS_TEST_TAG = "notebook-canvas"
val RenderedStrokeCountKey = SemanticsPropertyKey<Int>("RenderedStrokeCount")
private var SemanticsPropertyReceiver.renderedStrokeCount by RenderedStrokeCountKey

@Composable
fun NotebookCanvas(
    page: NotebookPage,
    strokes: List<StrokeRecord>,
    brush: BrushSpec,
    palmGuard: PalmGuardState?,
    onStrokeFinished: (StrokeRecord) -> Unit,
    toolMode: NotebookToolMode = NotebookToolMode.PEN,
    selection: NotebookSelectionState? = null,
    onSelect: (NormalizedRect) -> Unit = {},
    onPreviewMove: (Float, Float) -> Unit = { _, _ -> },
    onCommitMove: (Float, Float) -> Unit = { _, _ -> },
    onPreviewScale: (SelectionCorner, NormalizedPoint) -> Unit = { _, _ -> },
    onCommitScale: (SelectionCorner, NormalizedPoint) -> Unit = { _, _ -> },
    onCancelPreview: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    val renderedStrokes = selection?.previewStrokes?.let { preview ->
        strokes.filterNot { it.id in selection.selectedStrokeIds } + preview
    } ?: strokes
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag(NOTEBOOK_CANVAS_TEST_TAG)
            .semantics { renderedStrokeCount = renderedStrokes.size },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.White)
            if (page.paperStyle == PaperStyle.SQUARE_GRID) {
                val spacing = 32.dp.toPx()
                var x = spacing
                while (x < size.width) {
                    drawLine(Color(0x1F64748B), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height))
                    x += spacing
                }
                var y = spacing
                while (y < size.height) {
                    drawLine(Color(0x1F64748B), start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y))
                    y += spacing
                }
            }
            val matrix = Matrix()
            renderedStrokes.forEach { record ->
                renderer.draw(drawContext.canvas.nativeCanvas, record.toInkStroke(size.width, size.height), matrix)
            }
        }
        if (toolMode == NotebookToolMode.PEN) {
            AndroidView(
                factory = { context -> InkInputLayer(context) },
                modifier = Modifier.fillMaxSize(),
                update = { layer ->
                    layer.brush = brush
                    layer.onStrokeFinished = onStrokeFinished
                },
            )
        } else {
            SelectionOverlay(
                selection = selection,
                onSelect = onSelect,
                onPreviewMove = onPreviewMove,
                onCommitMove = onCommitMove,
                onPreviewScale = onPreviewScale,
                onCommitScale = onCommitScale,
                onCancelPreview = onCancelPreview,
                modifier = Modifier.fillMaxSize(),
            )
        }
        palmGuard?.let { PalmGuard(it, maxHeight) }
    }
}

private class InkInputLayer(context: Context) : FrameLayout(context) {
    private val inkView = InProgressStrokesView(context)
    private var recorder = recorderFor(BrushSpec())
    private var recorderBrush = BrushSpec()
    private var primaryPointerId: Int? = null
    private var inProgressStrokeId: InProgressStrokeId? = null
    private val finishedStrokeBuffer = FinishedStrokeBuffer<InProgressStrokeId>()

    var brush: BrushSpec = BrushSpec()
        set(value) {
            field = value
            if (primaryPointerId == null && value != recorderBrush) {
                recorderBrush = value
                recorder = recorderFor(value)
            }
        }
    var onStrokeFinished: (StrokeRecord) -> Unit = {}

    init {
        addView(
            inkView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        inkView.eagerInit()
        inkView.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    finishedStrokeBuffer.drain(strokes.keys).forEach(onStrokeFinished)
                    inkView.removeFinishedStrokes(strokes.keys)
                }
            },
        )
        inkView.setOnTouchListener { view, event ->
            handleTouch(view, event)
            true
        }
    }

    private fun handleTouch(view: android.view.View, event: MotionEvent) {
        MotionEventAdapter.toEvents(event).forEach { pointerEvent ->
            val result = recorder.onEvent(pointerEvent, width.toFloat(), height.toFloat())
            result.finishedStroke?.let { finished ->
                inProgressStrokeId?.let { inkId -> finishedStrokeBuffer.put(inkId, finished) }
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pointerId = event.getPointerId(event.actionIndex)
                primaryPointerId = pointerId
                view.requestUnbufferedDispatch(event)
                inProgressStrokeId = inkView.startStroke(event, pointerId, brush.toInkBrush())
            }

            MotionEvent.ACTION_MOVE -> primaryPointerId?.let { pointerId ->
                inProgressStrokeId?.let { strokeId -> inkView.addToStroke(event, pointerId, strokeId) }
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                if (liftedId == primaryPointerId) {
                    inProgressStrokeId?.let { inkView.finishStroke(event, liftedId, it) }
                    primaryPointerId = null
                    inProgressStrokeId = null
                    recorderBrush = brush
                    recorder = recorderFor(brush)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                inProgressStrokeId?.let { inkView.cancelStroke(it, event) }
                primaryPointerId = null
                inProgressStrokeId = null
                finishedStrokeBuffer.clear()
                recorderBrush = brush
                recorder = recorderFor(brush)
            }
        }
    }

    private fun recorderFor(spec: BrushSpec) = TouchStrokeRecorder(brushProvider = { spec })
}

private fun BrushSpec.toInkBrush(): Brush = Brush.createWithColorIntArgb(
    family = StockBrushes.marker(StockBrushes.MarkerVersion.V1),
    colorIntArgb = colorArgb.toInt(),
    size = width,
    epsilon = minOf(0.1f, width / 2f),
)

private fun StrokeRecord.toInkStroke(width: Float, height: Float): Stroke {
    val includePressure = samples.all { it.pressure != null }
    val batch = MutableStrokeInputBatch()
    samples.forEach { sample ->
        batch.add(
            StrokeInput.create(
                x = sample.x * width,
                y = sample.y * height,
                elapsedTimeMillis = sample.elapsedMillis,
                toolType = InputToolType.TOUCH,
                pressure = if (includePressure) requireNotNull(sample.pressure) else StrokeInput.NO_PRESSURE,
            ),
        )
    }
    return Stroke(brush.toInkBrush(), batch)
}
