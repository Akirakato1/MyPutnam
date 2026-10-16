# Oriented Selection Transforms Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add oriented selection boxes, free or 15-degree-snapped rotation, horizontal and vertical flips, and rotated scaling to the existing notebook editor.

**Architecture:** Keep selection orientation as transient editor state and persist only replacement stroke coordinates. Pure pixel-space geometry lives in the notebook edit package; `NotebookViewModel` owns previews and undoable commits; Compose owns hit testing, drawing, and pointer gestures.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX lifecycle, JUnit 4, Compose UI testing, existing file-backed notebook repository.

**Spec:** `docs/superpowers/specs/2026-10-16-free-notebooks-and-selection-transforms-design.md`

## Global Constraints

- Work fully offline; add no network permission or network dependency.
- Preserve the existing `StrokeRecord` and notebook manifest formats; no migration.
- Perform rotation and flipping in viewport pixel coordinates, never directly in normalized space.
- Persist only committed stroke replacements; selection frames, snap state, and previews remain transient.
- Free rotation is the default; snapping rounds the absolute result to the nearest 15 degrees when enabled.
- Never clip or individually clamp transformed samples.
- Keep every toolbar action at least 48dp and keep the fixed toolbar rows free of horizontal scrolling.
- Each rotate or flip commit is exactly one undoable edit group.
- When the viewport changes materially, cancel previews and rebuild the selection axis-aligned.

## Review Focus

- Rectangular viewport geometry must not skew angles or flips; Task 2 pins this with `rotationUsesPixelSpaceOnRectangularViewport`.
- Edge-adjacent selections must translate as a whole rather than clip; Task 2 pins this with `rotationFitsTheWholeGroupWithoutClampingSamples`.
- A selection too large for a requested angle must retain the last valid preview; Task 3 pins this with `invalidRotationPreviewKeepsTheLastValidPreview`.
- The rotation handle must win over outside-tap and move detection; Task 4 pins this with `rotationHandleHasHighestDragPriorityAndDoesNotClearSelection`.
- Rapid preview updates must commit only the final geometry as one undoable edit; Task 3 pins this with `rotationCommitUsesLatestPreviewAsOneEditGroup`.

---

### Task 1: Oriented selection frame and pure hit geometry

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/OrientedSelectionFrame.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/notebook/edit/OrientedSelectionFrameTest.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/NotebookSelectionState.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/StrokeGeometry.kt`

**Interfaces:**
- Consumes: `NormalizedPoint`, `NormalizedRect`, `CanvasViewport`, `SelectionCorner`, and `selectionBounds(viewport)`.
- Produces:
  - `data class OrientedSelectionFrame(val center: NormalizedPoint, val widthPixels: Float, val heightPixels: Float, val rotationRadians: Float, val viewport: CanvasViewport)`
  - `fun OrientedSelectionFrame.contains(point: NormalizedPoint): Boolean`
  - `fun OrientedSelectionFrame.corner(corner: SelectionCorner): NormalizedPoint`
  - `fun OrientedSelectionFrame.hitCorner(point: NormalizedPoint, radiusPixels: Float): SelectionCorner?`
  - `fun OrientedSelectionFrame.rotationHandle(stemPixels: Float): NormalizedPoint`
  - `fun OrientedSelectionFrame.hitRotationHandle(point: NormalizedPoint, radiusPixels: Float, stemPixels: Float): Boolean`
  - `fun NormalizedRect.toOrientedFrame(viewport: CanvasViewport): OrientedSelectionFrame`
  - `NotebookSelectionState.frame` and nullable `previewFrame` properties.

- [ ] **Step 1: Write failing frame-geometry tests**

Add tests named `cornersRotateAroundCenterInPixelSpace`, `containsInverseRotatesThePoint`, `cornerHitUsesPixelRadius`, and `rotationHandleExtendsFromLocalTopCenter`. Assert exact 90-degree corner locations on both `CanvasViewport(1_000f, 1_000f)` and `CanvasViewport(320f, 640f)`, inside/outside classification, and a 48dp-equivalent pixel hit radius without enlarging the visible handle.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.notebook.edit.OrientedSelectionFrameTest" --no-daemon
```

Expected: FAIL because `OrientedSelectionFrame` and its geometry functions do not exist.

- [ ] **Step 3: Implement the frame data type and coordinate helpers**

Use pixel-space local axes derived from `cos(rotationRadians)` and `sin(rotationRadians)`. Validate finite positive dimensions, finite angle, and a matching positive viewport. Normalize angles only for display consistency; do not quantize them here.

- [ ] **Step 4: Extend `NotebookSelectionState` without changing persistence**

Add:

```kotlin
val frame: OrientedSelectionFrame
val previewFrame: OrientedSelectionFrame? = null
```

Keep `bounds` as the axis-aligned visible bounds used by legacy intersection code. Update all construction sites to derive a zero-angle frame from `bounds` and the current viewport.

- [ ] **Step 5: Run the frame and existing geometry tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.notebook.edit.*" --no-daemon
```

Expected: PASS with zero failures.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/notebook/edit app/src/test/java/com/putnamworkbook/offline/notebook/edit
git commit -m "feat: model oriented notebook selections"
```

### Task 2: Pixel-space rotate, flip, and oriented scale transforms

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/StrokeTransform.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/edit/StrokeTransformTest.kt`

**Interfaces:**
- Consumes: `OrientedSelectionFrame` from Task 1 and existing `StrokeRecord`/`CanvasViewport` types.
- Produces:
  - `enum class SelectionFlipAxis { HORIZONTAL, VERTICAL }`
  - `data class SelectionTransformResult(val strokes: List<StrokeRecord>, val frame: OrientedSelectionFrame)`
  - `fun snapRotationRadians(angleRadians: Float, incrementDegrees: Int = 15): Float`
  - `fun StrokeTransform.rotate(strokes: List<StrokeRecord>, frame: OrientedSelectionFrame, requestedAngleRadians: Float, viewport: CanvasViewport, idFactory: () -> String): SelectionTransformResult?`
  - `fun StrokeTransform.flip(strokes: List<StrokeRecord>, frame: OrientedSelectionFrame, axis: SelectionFlipAxis, viewport: CanvasViewport, idFactory: () -> String): SelectionTransformResult`
  - `fun StrokeTransform.scaleFromCorner(strokes: List<StrokeRecord>, frame: OrientedSelectionFrame, corner: SelectionCorner, target: NormalizedPoint, viewport: CanvasViewport, idFactory: () -> String): SelectionTransformResult`

- [ ] **Step 1: Write failing rotation and snapping tests**

Add `rotationUsesPixelSpaceOnRectangularViewport`, `snapRoundsAbsoluteRotationToNearestFifteenDegrees`, and `rotationPreservesBrushPressureAndTiming`. Assert a 90-degree rotation on a 320x640 viewport in pixels, and assert 7 degrees snaps to 0 while 8 degrees snaps to 15.

- [ ] **Step 2: Write failing boundary tests**

Add `rotationFitsTheWholeGroupWithoutClampingSamples` and `rotationReturnsNullWhenRequestedAngleCannotFit`. Assert that every sample receives the same whole-group translation and that an impossible requested angle returns `null` without producing partially clamped samples.

- [ ] **Step 3: Write failing local-axis flip tests**

Add `horizontalFlipMirrorsLocalXAtNinetyDegrees` and `verticalFlipMirrorsLocalYAtNinetyDegrees`. Use asymmetric strokes so the two operations cannot accidentally pass with canvas-axis mirroring.

- [ ] **Step 4: Write failing oriented-scale tests**

Add `rotatedCornerScalingUsesTheOppositeLocalCorner` and `orientedScaleStopsAtPageBoundary`. Assert uniform scale, unchanged angle, and in-range visible bounds.

- [ ] **Step 5: Run transform tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.notebook.edit.StrokeTransformTest" --no-daemon
```

Expected: FAIL on missing rotate, flip, snap, and oriented-scale APIs.

- [ ] **Step 6: Implement a shared pixel-space transform pipeline**

Convert every sample to pixels, apply the local transform around the frame center, compute brush-aware visible bounds, and either translate the entire group into the page or reject the transform. Generate exactly one new ID per output stroke and preserve brush, pressure, and elapsed time.

- [ ] **Step 7: Retain an axis-aligned compatibility overload for scale**

Keep the current bounds-based `scaleFromCorner` signature as a wrapper that builds a zero-angle frame. This avoids unrelated churn in callers while Task 3 migrates the editor path.

- [ ] **Step 8: Run transform tests and verify GREEN**

Run the command from Step 5.

Expected: PASS with zero failures.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/notebook/edit/StrokeTransform.kt app/src/test/java/com/putnamworkbook/offline/notebook/edit/StrokeTransformTest.kt
git commit -m "feat: add oriented stroke transforms"
```

### Task 3: View-model previews, commits, snapping, and undo integration

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookUiState.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModel.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModelTest.kt`

**Interfaces:**
- Consumes: `SelectionTransformResult`, `SelectionFlipAxis`, and `snapRotationRadians` from Task 2.
- Produces:
  - `NotebookUiState.rotationSnapEnabled: Boolean = false`
  - `fun NotebookViewModel.setRotationSnapEnabled(enabled: Boolean)`
  - `fun NotebookViewModel.previewRotation(requestedAngleRadians: Float)`
  - `fun NotebookViewModel.commitRotationPreview()`
  - `fun NotebookViewModel.flipSelection(axis: SelectionFlipAxis)`
  - Frame-preserving move and scale preview/commit behavior.

- [ ] **Step 1: Write failing snap-state and viewport-reset tests**

Add `rotationSnapDefaultsOffAndCanBeToggled` and `viewportResizeCancelsPreviewAndRebuildsAxisAlignedFrame`. Assert that `open()` resets snapping to false and that a changed viewport clears preview strokes/frame and resets frame rotation to zero.

- [ ] **Step 2: Write failing rotation-preview tests**

Add `invalidRotationPreviewKeepsTheLastValidPreview` and `rotationCommitUsesLatestPreviewAsOneEditGroup`. Send several preview angles, including an invalid final request, then commit and assert the latest valid geometry is active, old strokes are inactive, and one undo restores the original samples.

- [ ] **Step 3: Write failing flip and oriented-scale tests**

Add `eachFlipIsOneUndoableReplacement` and `moveAndScalePreserveSelectionAngle`. Assert distinct horizontal/vertical geometry and an unchanged oriented angle after move and scale.

- [ ] **Step 4: Run the view-model tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.notebook.ui.NotebookViewModelTest" --no-daemon
```

Expected: FAIL on missing state and methods.

- [ ] **Step 5: Implement preview state and frame-preserving commits**

Extend `updatePreview` to accept both replacement strokes and a preview frame. Add a `commitPreviewedReplacement()` helper that commits the current preview rather than recomputing from the last pointer coordinate. Extend `commitReplacement` with an explicit `selectionAfter` frame so a committed transform does not reset orientation.

- [ ] **Step 6: Implement snap and flip commands**

Apply snapping in `previewRotation`, not in the pure free-rotation function. `flipSelection` commits immediately through the existing optimistic edit path. Both commands no-op safely without a selection or viewport.

- [ ] **Step 7: Run view-model and save-coordinator tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.notebook.ui.*" --no-daemon
```

Expected: PASS with zero failures.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookUiState.kt app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModel.kt app/src/test/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModelTest.kt
git commit -m "feat: commit rotation and flip edits"
```

### Task 4: Oriented overlay, rotation handle, and gesture routing

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/SelectionOverlay.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookCanvas.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookPane.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/ui/SelectionGestureResolverTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt`

**Interfaces:**
- Consumes: oriented-frame hit geometry from Task 1 and view-model callbacks from Task 3.
- Produces:
  - `SelectionDragIntent.ROTATE`
  - `onPreviewRotation: (Float) -> Unit`
  - `onCommitRotation: () -> Unit`
  - An oriented selection overlay with a 48dp rotation-handle hit region.

- [ ] **Step 1: Write failing pure gesture-priority tests**

Add `rotationHandleHasHighestDragPriorityAndDoesNotClearSelection`, `rotatedInteriorStartsMove`, and `outsideRotatedFrameStartsMarquee`. Resolve intent from explicit booleans in the order rotate, corner, interior, outside.

- [ ] **Step 2: Run gesture tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.notebook.ui.SelectionGestureResolverTest" --no-daemon
```

Expected: FAIL because rotation intent and oriented hit routing are absent.

- [ ] **Step 3: Implement oriented drawing and hit testing**

Draw four rotated edges, four corner handles, the top-center stem, and the visible circular rotation handle. Convert 48dp hit sizes to pixels through Compose density while keeping the visible circle compact. Use preview frame/strokes when present.

- [ ] **Step 4: Implement rotation dragging**

Record the initial pointer angle and frame angle in `SelectionGesture.Rotate`. Emit the absolute requested angle during drag and `onCommitRotation()` on release. On cancellation, call the existing preview-cancel callback.

- [ ] **Step 5: Write Compose tests for the handle and callbacks**

Add `draggingRotationHandlePreviewsAndCommitsWithoutClearingSelection` and update existing move/scale tests to use the oriented frame. Assert one preview sequence, one commit, zero marquee selections, and zero clear-selection calls.

- [ ] **Step 6: Compile instrumentation tests**

Run:

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/notebook/ui app/src/test/java/com/putnamworkbook/offline/notebook/ui/SelectionGestureResolverTest.kt app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt
git commit -m "feat: add selection rotation gesture"
```

### Task 5: Flip and snap toolbar controls with end-to-end wiring

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookToolbar.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookPane.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookPaneTest.kt`

**Interfaces:**
- Consumes: `NotebookUiState.rotationSnapEnabled`, `NotebookViewModel.setRotationSnapEnabled`, and `NotebookViewModel.flipSelection` from Task 3.
- Produces:
  - Test tags `notebook-flip-horizontal`, `notebook-flip-vertical`, and `notebook-snap-rotation`.
  - Icon glyphs and accessible descriptions `Flip horizontal`, `Flip vertical`, and `Snap rotation to 15 degrees`.

- [ ] **Step 1: Write failing toolbar visibility and state tests**

Extend `selectModeAlwaysShowsIconActionsAndDisablesUnavailableOperations` to require all seven Select controls. Assert both flips are disabled without a selection, enabled with a selection, and the snap control is always enabled with selected/unselected semantics.

- [ ] **Step 2: Write failing toolbar-width and callback tests**

At `Modifier.width(504.dp)`, assert every Select action retains a 48dp target and remains inside the toolbar. Click both flips and toggle snap twice; assert exact callback order and state descriptions.

- [ ] **Step 3: Compile instrumentation tests and verify RED behavior is represented**

Run:

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: compilation fails until the new callback parameters/tags exist, or runtime assertions fail on a connected device against the pre-change toolbar.

- [ ] **Step 4: Add compact custom glyphs and controls**

Add mirror-axis glyphs that visibly distinguish horizontal from vertical and a `15°`/magnet-style snap glyph. Use `ToolbarIconButton`; do not add text labels or horizontal scrolling.

- [ ] **Step 5: Wire callbacks through pane, canvas, and problem workspace**

Pass rotation preview/commit, both flip axes, and snap state from `PutnamNavHost` through `NotebookPane` and `NotebookCanvas` to their owners. Preserve default no-op callbacks for focused Compose tests.

- [ ] **Step 6: Compile all Android tests**

Run the command from Step 3.

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/notebook/ui app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui
git commit -m "feat: expose flip and rotation snap controls"
```

### Task 6: Selection-transform regression and branch verification

**Files:**
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt`
- Modify only if failures require it: selection-transform production files from Tasks 1-5.

**Interfaces:**
- Consumes: the complete transform feature from Tasks 1-5.
- Produces: acceptance coverage proving rotation/flip survive autosave and coexist with existing selection editing.

- [ ] **Step 1: Extend acceptance coverage**

Add a selected asymmetric two-stroke fixture, rotate it through the handle, toggle snap and rotate again, flip both axes, undo/redo, navigate Home, reopen, and assert committed stroke geometry remains. Keep existing move, scale, copy, paste, and outside-tap assertions.

- [ ] **Step 2: Run all JVM tests, lint, debug assembly, and Android-test compilation**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL with zero test or lint failures.

- [ ] **Step 3: Run instrumentation tests when a device is attached**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

Expected: all connected tests pass. If no device/emulator exists, record that limitation and do not claim runtime execution.

- [ ] **Step 4: Request focused code review and fix all Critical or Important findings**

Review the complete transform commit range against the approved spec, with special attention to non-square geometry, preview/commit identity, page boundaries, touch priority, accessibility, and toolbar width.

- [ ] **Step 5: Re-run the full verification command after review fixes**

Run the command from Step 2.

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit acceptance or review fixes**

```powershell
git add app/src/androidTest app/src/main app/src/test
git commit -m "test: cover oriented selection workflows"
```
