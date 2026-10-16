package com.putnamworkbook.offline.notebook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.edit.NotebookToolMode
import kotlin.math.roundToInt

const val UNDO_TEST_TAG = "notebook-undo"
const val REDO_TEST_TAG = "notebook-redo"
const val TOOL_MODE_TOGGLE_TEST_TAG = "notebook-tool-mode-toggle"
const val BRUSH_WIDTH_TEST_TAG = "notebook-brush-width"
const val BRUSH_COLOR_PALETTE_TEST_TAG = "notebook-brush-color-palette"
const val CUT_SELECTION_TEST_TAG = "notebook-cut-selection"
const val COPY_SELECTION_TEST_TAG = "notebook-copy-selection"
const val PASTE_SELECTION_TEST_TAG = "notebook-paste-selection"
const val DELETE_SELECTION_TEST_TAG = "notebook-delete-selection"

@Composable
fun NotebookToolbar(
    saveState: NotebookSaveState,
    toolMode: NotebookToolMode,
    brush: BrushSpec,
    hasSelection: Boolean,
    clipboardHasContent: Boolean,
    onSetToolMode: (NotebookToolMode) -> Unit,
    onBrushChanged: (BrushSpec) -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDeleteSelection: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPage: () -> Unit,
    onClearPage: () -> Unit,
    onDeletePage: () -> Unit,
    activePageIndex: Int,
    pageCount: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarIconButton(
                description = if (toolMode == NotebookToolMode.PEN) "Pen mode" else "Select mode",
                glyph = if (toolMode == NotebookToolMode.PEN) ToolbarGlyph.PEN else ToolbarGlyph.SELECT,
                onClick = {
                    onSetToolMode(
                        if (toolMode == NotebookToolMode.PEN) NotebookToolMode.SELECT else NotebookToolMode.PEN,
                    )
                },
                modifier = Modifier.testTag(TOOL_MODE_TOGGLE_TEST_TAG),
                highlighted = true,
            )
            ToolbarIconButton("Undo", ToolbarGlyph.UNDO, onUndo, Modifier.testTag(UNDO_TEST_TAG))
            ToolbarIconButton("Redo", ToolbarGlyph.REDO, onRedo, Modifier.testTag(REDO_TEST_TAG))
            ToolbarIconButton("Add page", ToolbarGlyph.ADD_PAGE, onAddPage)
            ToolbarIconButton("Clear page", ToolbarGlyph.CLEAR_PAGE, onClearPage)
            ToolbarIconButton("Delete page", ToolbarGlyph.DELETE_PAGE, onDeletePage)
            Text(
                when (saveState) {
                    NotebookSaveState.SAVING -> "Saving…"
                    NotebookSaveState.SAVED -> "Saved"
                    NotebookSaveState.ERROR -> "Save failed"
                    NotebookSaveState.LOADING -> "Loading…"
                    NotebookSaveState.IDLE -> ""
                },
            )
            Spacer(Modifier.weight(1f))
            ToolbarIconButton(
                description = "Previous page",
                glyph = ToolbarGlyph.PREVIOUS_PAGE,
                onClick = { onSelectPage(activePageIndex - 1) },
                modifier = Modifier.testTag(PREVIOUS_PAGE_TEST_TAG),
                enabled = activePageIndex > 0,
            )
            Text(
                "Page ${activePageIndex + 1} of $pageCount",
                modifier = Modifier.testTag(PAGE_INDEX_TEST_TAG),
            )
            ToolbarIconButton(
                description = "Next page",
                glyph = ToolbarGlyph.NEXT_PAGE,
                onClick = { onSelectPage(activePageIndex + 1) },
                modifier = Modifier.testTag(NEXT_PAGE_TEST_TAG),
                enabled = activePageIndex < pageCount - 1,
            )
        }
        if (toolMode == NotebookToolMode.PEN) {
            Row(
                modifier = Modifier.fillMaxWidth().height(CONTEXT_TOOLBAR_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .testTag(BRUSH_COLOR_PALETTE_TEST_TAG)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                        .selectableGroup()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NOTEBOOK_BRUSH_COLORS.forEach { choice ->
                        BrushColorCircle(
                            choice = choice,
                            selected = brush.colorArgb == choice.argb,
                            onClick = { onBrushChanged(brush.copy(colorArgb = choice.argb)) },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("Width ${brush.width.roundToInt()}")
                Slider(
                    value = brush.width,
                    onValueChange = { width -> onBrushChanged(brush.copy(width = width)) },
                    valueRange = 1f..12f,
                    steps = 10,
                    modifier = Modifier.weight(1f).testTag(BRUSH_WIDTH_TEST_TAG),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(CONTEXT_TOOLBAR_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarIconButton(
                    "Cut",
                    ToolbarGlyph.CUT,
                    onCut,
                    Modifier.testTag(CUT_SELECTION_TEST_TAG),
                    enabled = hasSelection,
                )
                ToolbarIconButton(
                    "Copy",
                    ToolbarGlyph.COPY,
                    onCopy,
                    Modifier.testTag(COPY_SELECTION_TEST_TAG),
                    enabled = hasSelection,
                )
                ToolbarIconButton(
                    "Paste",
                    ToolbarGlyph.PASTE,
                    onPaste,
                    Modifier.testTag(PASTE_SELECTION_TEST_TAG),
                    enabled = clipboardHasContent,
                )
                ToolbarIconButton(
                    "Delete selection",
                    ToolbarGlyph.DELETE_SELECTION,
                    onDeleteSelection,
                    Modifier.testTag(DELETE_SELECTION_TEST_TAG),
                    enabled = hasSelection,
                )
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    description: String,
    glyph: ToolbarGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val container = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val enabledContent = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val content = enabledContent.copy(alpha = if (enabled) 1f else 0.38f)
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(CircleShape)
            .background(container),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .size(48.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            ToolbarGlyphCanvas(glyph, content)
        }
    }
}

@Composable
private fun BrushColorCircle(
    choice: NotebookBrushColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ring = Color(0xFFFFD54F)
    val contrastOutline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    Box(
        modifier = Modifier
            .size(48.dp)
            .testTag("notebook-brush-color-${choice.tag}")
            .semantics { contentDescription = choice.label }
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .drawBehind {
                val center = Offset(size.width / 2f, size.height / 2f)
                if (selected) {
                    drawCircle(ring.copy(alpha = 0.32f), radius = 17.dp.toPx(), center = center)
                    drawCircle(
                        contrastOutline,
                        radius = 14.dp.toPx(),
                        center = center,
                        style = Stroke(3.dp.toPx()),
                    )
                    drawCircle(ring, radius = 13.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                }
                drawCircle(Color(choice.argb), radius = 10.dp.toPx(), center = center)
                drawCircle(
                    contrastOutline,
                    radius = 10.dp.toPx(),
                    center = center,
                    style = Stroke(1.dp.toPx()),
                )
            },
    )
}

private enum class ToolbarGlyph {
    PEN,
    SELECT,
    UNDO,
    REDO,
    ADD_PAGE,
    CLEAR_PAGE,
    DELETE_PAGE,
    PREVIOUS_PAGE,
    NEXT_PAGE,
    CUT,
    COPY,
    PASTE,
    DELETE_SELECTION,
}

@Composable
private fun ToolbarGlyphCanvas(glyph: ToolbarGlyph, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val strokeWidth = 2.dp.toPx()
        val line = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)

        when (glyph) {
            ToolbarGlyph.PEN -> {
                val nib = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.82f)
                    lineTo(size.width * 0.62f, size.height * 0.18f)
                    lineTo(size.width * 0.84f, size.height * 0.40f)
                    lineTo(size.width * 0.38f, size.height * 0.84f)
                    close()
                }
                drawPath(nib, color, style = line)
                drawLine(color, point(0.32f, 0.70f), point(0.68f, 0.34f), strokeWidth, StrokeCap.Round)
                drawCircle(color, strokeWidth * 0.7f, point(0.32f, 0.70f))
            }
            ToolbarGlyph.SELECT -> {
                val segments = listOf(
                    0.16f to 0.16f, 0.42f to 0.16f, 0.58f to 0.16f, 0.84f to 0.16f,
                    0.84f to 0.16f, 0.84f to 0.42f, 0.84f to 0.58f, 0.84f to 0.84f,
                    0.84f to 0.84f, 0.58f to 0.84f, 0.42f to 0.84f, 0.16f to 0.84f,
                    0.16f to 0.84f, 0.16f to 0.58f, 0.16f to 0.42f, 0.16f to 0.16f,
                )
                segments.chunked(2).forEach { (start, end) ->
                    drawLine(color, point(start.first, start.second), point(end.first, end.second), strokeWidth, StrokeCap.Round)
                }
            }
            ToolbarGlyph.UNDO, ToolbarGlyph.REDO -> {
                val redo = glyph == ToolbarGlyph.REDO
                drawArc(
                    color = color,
                    startAngle = if (redo) 210f else -30f,
                    sweepAngle = if (redo) -230f else 230f,
                    useCenter = false,
                    topLeft = point(0.20f, 0.20f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.62f, size.height * 0.62f),
                    style = line,
                )
                if (redo) {
                    drawLine(color, point(0.80f, 0.30f), point(0.82f, 0.58f), strokeWidth, StrokeCap.Round)
                    drawLine(color, point(0.80f, 0.30f), point(0.56f, 0.34f), strokeWidth, StrokeCap.Round)
                } else {
                    drawLine(color, point(0.20f, 0.30f), point(0.18f, 0.58f), strokeWidth, StrokeCap.Round)
                    drawLine(color, point(0.20f, 0.30f), point(0.44f, 0.34f), strokeWidth, StrokeCap.Round)
                }
            }
            ToolbarGlyph.ADD_PAGE -> {
                val page = Path().apply {
                    moveTo(size.width * 0.20f, size.height * 0.10f)
                    lineTo(size.width * 0.64f, size.height * 0.10f)
                    lineTo(size.width * 0.82f, size.height * 0.28f)
                    lineTo(size.width * 0.82f, size.height * 0.90f)
                    lineTo(size.width * 0.20f, size.height * 0.90f)
                    close()
                    moveTo(size.width * 0.64f, size.height * 0.10f)
                    lineTo(size.width * 0.64f, size.height * 0.28f)
                    lineTo(size.width * 0.82f, size.height * 0.28f)
                }
                drawPath(page, color, style = line)
                drawLine(color, point(0.36f, 0.60f), point(0.66f, 0.60f), strokeWidth, StrokeCap.Round)
                drawLine(color, point(0.51f, 0.45f), point(0.51f, 0.75f), strokeWidth, StrokeCap.Round)
            }
            ToolbarGlyph.CLEAR_PAGE -> {
                val eraser = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.66f)
                    lineTo(size.width * 0.56f, size.height * 0.18f)
                    lineTo(size.width * 0.84f, size.height * 0.42f)
                    lineTo(size.width * 0.46f, size.height * 0.86f)
                    lineTo(size.width * 0.26f, size.height * 0.86f)
                    close()
                }
                drawPath(eraser, color, style = line)
                drawLine(color, point(0.37f, 0.48f), point(0.66f, 0.72f), strokeWidth, StrokeCap.Round)
                drawLine(color, point(0.46f, 0.86f), point(0.88f, 0.86f), strokeWidth, StrokeCap.Round)
            }
            ToolbarGlyph.DELETE_PAGE -> {
                drawLine(color, point(0.24f, 0.28f), point(0.78f, 0.28f), strokeWidth, StrokeCap.Round)
                drawLine(color, point(0.40f, 0.16f), point(0.62f, 0.16f), strokeWidth, StrokeCap.Round)
                drawRoundRect(
                    color = color,
                    topLeft = point(0.30f, 0.34f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.42f, size.height * 0.52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(strokeWidth, strokeWidth),
                    style = line,
                )
                drawLine(color, point(0.44f, 0.46f), point(0.44f, 0.74f), strokeWidth, StrokeCap.Round)
                drawLine(color, point(0.58f, 0.46f), point(0.58f, 0.74f), strokeWidth, StrokeCap.Round)
            }
            ToolbarGlyph.PREVIOUS_PAGE, ToolbarGlyph.NEXT_PAGE -> {
                val next = glyph == ToolbarGlyph.NEXT_PAGE
                val tip = if (next) point(0.72f, 0.50f) else point(0.28f, 0.50f)
                val upper = if (next) point(0.44f, 0.22f) else point(0.56f, 0.22f)
                val lower = if (next) point(0.44f, 0.78f) else point(0.56f, 0.78f)
                drawLine(color, upper, tip, strokeWidth, StrokeCap.Round)
                drawLine(color, tip, lower, strokeWidth, StrokeCap.Round)
            }
            ToolbarGlyph.CUT -> {
                drawCircle(color, size.width * 0.13f, point(0.25f, 0.25f), style = line)
                drawCircle(color, size.width * 0.13f, point(0.25f, 0.75f), style = line)
                drawLine(color, point(0.35f, 0.34f), point(0.82f, 0.74f), strokeWidth, StrokeCap.Round)
                drawLine(color, point(0.35f, 0.66f), point(0.82f, 0.26f), strokeWidth, StrokeCap.Round)
            }
            ToolbarGlyph.COPY -> {
                drawRoundRect(
                    color = color,
                    topLeft = point(0.18f, 0.28f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.50f, size.height * 0.58f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(strokeWidth, strokeWidth),
                    style = line,
                )
                drawRoundRect(
                    color = color,
                    topLeft = point(0.34f, 0.14f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.50f, size.height * 0.58f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(strokeWidth, strokeWidth),
                    style = line,
                )
            }
            ToolbarGlyph.PASTE -> {
                drawRoundRect(
                    color = color,
                    topLeft = point(0.22f, 0.20f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.58f, size.height * 0.68f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(strokeWidth, strokeWidth),
                    style = line,
                )
                drawRoundRect(
                    color = color,
                    topLeft = point(0.36f, 0.10f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.30f, size.height * 0.20f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(strokeWidth, strokeWidth),
                    style = line,
                )
                drawLine(color, point(0.34f, 0.50f), point(0.68f, 0.50f), strokeWidth, StrokeCap.Round)
                drawLine(color, point(0.34f, 0.68f), point(0.60f, 0.68f), strokeWidth, StrokeCap.Round)
            }
            ToolbarGlyph.DELETE_SELECTION -> {
                val segments = listOf(
                    0.14f to 0.34f, 0.14f to 0.14f, 0.14f to 0.14f, 0.34f to 0.14f,
                    0.66f to 0.14f, 0.86f to 0.14f, 0.86f to 0.14f, 0.86f to 0.34f,
                    0.86f to 0.66f, 0.86f to 0.86f, 0.86f to 0.86f, 0.66f to 0.86f,
                    0.34f to 0.86f, 0.14f to 0.86f, 0.14f to 0.86f, 0.14f to 0.66f,
                )
                segments.chunked(2).forEach { (start, end) ->
                    drawLine(color, point(start.first, start.second), point(end.first, end.second), strokeWidth, StrokeCap.Round)
                }
                drawLine(color, point(0.38f, 0.38f), point(0.62f, 0.62f), strokeWidth, StrokeCap.Round)
                drawLine(color, point(0.62f, 0.38f), point(0.38f, 0.62f), strokeWidth, StrokeCap.Round)
            }
        }
    }
}

private val CONTEXT_TOOLBAR_HEIGHT = 52.dp

private data class NotebookBrushColor(
    val label: String,
    val tag: String,
    val argb: Long,
)

private val NOTEBOOK_BRUSH_COLORS = listOf(
    NotebookBrushColor("Ink", "ink", 0xFF1F2937L),
    NotebookBrushColor("Blue", "blue", 0xFF1D4ED8L),
    NotebookBrushColor("Red", "red", 0xFFB91C1CL),
    NotebookBrushColor("Green", "green", 0xFF15803DL),
    NotebookBrushColor("Purple", "purple", 0xFF7E22CEL),
    NotebookBrushColor("Orange", "orange", 0xFFC2410CL),
    NotebookBrushColor("Teal", "teal", 0xFF0F766EL),
    NotebookBrushColor("Pink", "pink", 0xFFBE185DL),
)
