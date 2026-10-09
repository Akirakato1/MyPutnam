# Notebook Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the approved ink, brush, selection, page-navigation, and reference-pane fixes as one independently verified workstream before changing problem identities.

**Architecture:** Keep the optimistic notebook model and debounced writer already present. Complete a two-phase AndroidX Ink-to-Compose handoff, make brush persistence race-safe, and pass actual canvas dimensions into stroke hit testing so visible stroke edges select correctly. This plan commits the current notebook work before the generic library migration touches the same persistence and navigation files.

**Tech Stack:** Kotlin 2.4, Jetpack Compose, AndroidX Ink 1.0, coroutines, Room-backed settings, JUnit 4, Android instrumented Compose tests.

**Spec:** `docs/superpowers/specs/2026-10-08-library-topic-search-and-travel-release-design.md`

## Global Constraints

- Keep the existing 350 ms ordered save debounce and all Home, Back, export, page-change, and `ON_STOP` flush barriers.
- Finished platform ink remains visible until its immutable stroke ID has entered Compose rendering and one later animation frame has begun.
- The toolbar has one Pen/Select toggle, exactly eight quick colors, and a width range of 1–12.
- Erase last, selection Cancel, brush controls in Settings, numbered pages, page shifting, and palm-guard UI remain absent.
- The original-source/PDF button and any in-workspace original-paper viewer remain absent; the parsed offline statement, hints, prerequisites, and solution are the only reference surfaces.
- Tapping outside a selection clears it; tapping inside it does not.
- A marquee selects every stroke whose rendered centerline or visible brush edge intersects it.
- Do not add notebook-data migration work; the later generic-identity plan intentionally creates a clean-install format.
- The current notebook changes cross task boundaries in `NotebookCanvas`, `NotebookSelectionTest`, navigation, and the acceptance test. Keep Tasks 1–3 as uncommitted checkpoints and create one coherent notebook commit only after Task 4; never use the dirty working tree to conceal a broken staged snapshot.
- Do not add `Co-Authored-By` trailers.

## Review Focus

- Completion callbacks that are batched, reversed, duplicated, cancelled, or untracked must neither lose nor prematurely remove ink (Task 1).
- An immutable stroke entering recomposition is not enough; platform ink removal must wait for the following frame (Task 1).
- A brush changed while open/page/undo repository work is suspended must remain the selected brush and be the value persisted (Task 2).
- Thick strokes intersected only at a rendered edge on a small or rectangular canvas must be selected in full (Task 3).
- Bottom-right input, outside taps, contextual clipboard controls, and fixed reference headers must work without invisible blockers or disappearing controls (Task 4).

---

### Task 1: Complete the two-phase ink handoff

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/input/FinishedStrokeBuffer.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookCanvas.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/input/FinishedStrokeBufferTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/InkCompletionTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/InkStressTest.kt`

**Interfaces:**
- Produces: `FinishedStrokeBuffer<K>.finish(finishedIds: Collection<K>): Map<K, StrokeRecord>`.
- Produces: `FinishedStrokeBuffer<K>.acknowledgeRendered(renderedStrokeIds: Set<String>): List<K>`.
- Produces: `FinishedStrokeBuffer<K>.remove(id: K): StrokeRecord?` across both pending phases.
- Consumes: `NotebookCanvas(strokes)` immutable IDs and `InProgressStrokesView.removeFinishedStrokes`.
- Produces internal test seam `InkRemovalScheduler`, whose production implementation delegates to `postOnAnimation`.
- Produces ordered `InkHandoffEvent` values for model delivery, rendered-ID acknowledgement, removal scheduling, and actual platform removal; these are observation-only and default to a no-op sink.

- [ ] **Step 1: Extend the buffer tests first**

  Add assertions for callbacks containing multiple IDs, truly reversed `finish()` calls, duplicate callback IDs, cancellation before finish, cancellation while awaiting render, unknown IDs, and rejection when `put()` reuses an ID present in either the recording or awaiting-render map.

- [ ] **Step 2: Run the focused unit test and verify RED**

  Run: `./gradlew.bat :app:testDebugUnitTest --tests '*FinishedStrokeBufferTest*'`

  Expected: the awaiting-render ID reuse case fails against the current implementation.

- [ ] **Step 3: Enforce one ID across both buffer phases**

  Update `put(id, stroke)` to require that `id` is absent from both internal maps. Keep `finish()` idempotent for already-finished/unknown callback IDs and keep cancellation isolated to the requested ID.

- [ ] **Step 4: Add a stateful frame-handoff instrumentation test**

  In `InkCompletionTest`, publish each delivered `StrokeRecord` into the `NotebookCanvas.strokes` state only after a controlled delay and inject a manual `InkRemovalScheduler`. Record the four event phases independently; do not derive removal from the buffer's awaiting-render count. Assert delivery occurs while platform ink remains, the immutable rendered-stroke semantics and rendered-ID acknowledgement appear before removal is scheduled, no platform removal occurs until the manual next-frame callback advances, and the actual removal event follows that callback.

- [ ] **Step 5: Run the frame-handoff test and verify RED**

  Run the single `InkCompletionTest` class on an attached target. Expected: it fails because scheduling/removal are not independently controllable or observable.

- [ ] **Step 6: Implement the deterministic scheduler and event seam**

  Thread the scheduler and no-op event sink from `NotebookCanvas` into `InkInputLayer`. Emit events exactly where the model callback is delivered, a model ID is observed in the AndroidView update, removal is scheduled, and `removeFinishedStrokes` is invoked. Production behavior remains `postOnAnimation`; tests control only the scheduling boundary.

- [ ] **Step 7: Extend rapid-input coverage**

  Make the ten-stroke stress test feed delivered records back into Compose, then assert ten distinct immutable IDs and zero platform strokes awaiting removal. Exercise a page/mode recomposition between delivery and acknowledgement.

- [ ] **Step 8: Run focused and compile verification**

  Run: `./gradlew.bat :app:testDebugUnitTest --tests '*FinishedStrokeBufferTest*' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

  If a device/emulator is attached, run: `./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.putnamworkbook.offline.notebook.ui.InkCompletionTest,com.putnamworkbook.offline.notebook.ui.InkStressTest"`

  Expected: all focused checks pass.

- [ ] **Step 9: Inspect the checkpoint without committing it**

  Run `git diff --check` for the Task 1 files and inspect their diff. Do not commit yet: the dirty `NotebookCanvas` also carries selection callback changes that become coherent in Task 3.

### Task 2: Make live brush controls race-safe

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookUiState.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookToolbar.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookPane.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/feature/settings/SettingsScreenTest.kt`

**Interfaces:**
- Produces: `NotebookUiState.brush: BrushSpec`.
- Produces: `NotebookViewModel.setBrush(brush: BrushSpec)` with immediate state and serialized latest-value persistence.
- Produces: test tags `notebook-brush-color-{ink|blue|red|green|purple|orange|teal|pink}` and `notebook-brush-width`.
- Consumes: `SettingsRepository.observe()` and `SettingsRepository.update(transform)`.

- [ ] **Step 1: Write delayed-operation brush tests**

  Add tests that block `loadOrCreate`, `loadExisting`, `loadStrokes`, page selection, and undo in turn; call `setBrush()` while each operation is suspended; release it; assert the newer brush is still in UI state and settings. Add rapid color/width writes, settings write failure followed by retry, `awaitIdle()`, and reopen persistence assertions.

- [ ] **Step 2: Run the focused view-model test and verify RED**

  Run: `./gradlew.bat :app:testDebugUnitTest --tests '*NotebookViewModelTest*'`

  Expected: at least the suspended `publishDocument` case restores the stale captured brush.

- [ ] **Step 3: Separate initial brush loading from document publication**

  Add a monotonic `brushRevision` incremented by `setBrush()`. `open()` captures the revision before reading settings and applies the stored brush only if no newer live change occurred. Remove the `brush` parameter from ordinary `publishDocument`; every document publication retains `current.brush`. Keep serialized settings writes reading the latest state under `brushUpdateMutex`.

- [ ] **Step 4: Pin the toolbar contract in Compose tests**

  Assert one Pen/Select toggle, eight named colors, immediate color switching, integer width positions 1–12, persisted selection after recomposition, contextual selection controls only in Select mode, and absence of Erase last and Cancel.

- [ ] **Step 5: Pin Settings removal**

  Assert general Settings contains paper/style controls but no color or pen-width controls. Keep existing stored brush fields because the notebook toolbar persists through the same repository.

- [ ] **Step 6: Run focused and compile verification**

  Run: `./gradlew.bat :app:testDebugUnitTest --tests '*NotebookViewModelTest*' :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`

  Expected: PASS.

- [ ] **Step 7: Inspect the checkpoint without committing it**

  Inspect the exact Task 2 diff and run `git diff --check`. Do not stage `NotebookSelectionTest` or the shared navigation/canvas files separately from their Task 3 consumers.

### Task 3: Use viewport-aware whole-stroke selection

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/CanvasViewport.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/StrokeGeometry.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/StrokeTransform.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/NotebookSelectionState.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/SelectionOverlay.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookCanvas.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookPane.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookUiState.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/edit/StrokeGeometryTest.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/edit/StrokeTransformTest.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt`

**Interfaces:**
- Produces: `CanvasViewport(widthPixels: Float, heightPixels: Float)` with finite positive invariants.
- Produces: `StrokeRecord.intersects(rect: NormalizedRect, viewport: CanvasViewport): Boolean`.
- Produces: `Collection<StrokeRecord>.selectionBounds(viewport: CanvasViewport): NormalizedRect?`.
- Changes: selection callback to `onSelect(rect: NormalizedRect, viewport: CanvasViewport)`.
- Produces: `NotebookViewModel.updateViewport(viewport)` and `NotebookUiState.canvasViewport`; `NotebookCanvas` reports size changes, selection bounds are recomputed after resize, and page changes retain the current pane viewport while clearing page-specific selection.
- Changes: `StrokeTransform.move(..., viewport)` and `scaleFromCorner(..., viewport)` clamp transformed visible brush geometry, not only sample centers.

- [ ] **Step 1: Write geometry tests at multiple aspect ratios**

  Cover a 12-pixel stroke whose centerline misses but rendered edge touches the marquee on 320×640 and 1200×600 canvases; crossing, touching, enclosing, outside, single-point, zero-area marquee, page-edge clamping, and preview bounds. Add a diagonal corner near-miss that lies inside a rectangle expanded on both axes but outside the round brush radius. Assert exact pixel-space segment-to-rectangle distance selects all and only strokes whose rendered capsule intersects the marquee, then selects each matching stroke in full.

- [ ] **Step 2: Run focused tests and verify RED**

  Run: `./gradlew.bat :app:testDebugUnitTest --tests '*StrokeGeometryTest*'`

  Expected: viewport-specific rendered-edge cases fail against the fixed 1,000-pixel assumption.

- [ ] **Step 3: Test viewport ownership and transform clamping**

  In view-model/transform tests, cover select, move, scale, preview, commit, copy/paste on a different page, returning to a page, and viewport resize. Paste is disabled until a viewport has been reported. Assert transformed visible brush edges stay within the page on rectangular viewports; brush width remains fixed while sample coordinates scale.

- [ ] **Step 4: Implement exact viewport-aware geometry**

  Convert stroke centerline segments and the marquee to pixel coordinates and test whether their minimum distance is at most `brush.width / 2`, giving rounded-corner capsule behavior rather than a conservative expanded rectangle. Compute brush-inclusive normalized bounds from the actual viewport. Thread the current viewport through selection, move, scale, paste, preview, and committed replacement paths; recompute selection bounds when it changes.

- [ ] **Step 5: Complete outside-tap behavior tests**

  Assert a tap strictly outside the selection clears it, a tap inside preserves it, handle drags do not clear it, and a new outside drag replaces it with the new marquee selection.

- [ ] **Step 6: Run focused tests and compile instrumentation sources**

  Run: `./gradlew.bat :app:testDebugUnitTest --tests '*StrokeGeometryTest*' --tests '*StrokeTransformTest*' --tests '*NotebookViewModelTest*' :app:compileDebugAndroidTestKotlin`

  Expected: PASS.

- [ ] **Step 7: Inspect the coherent selection checkpoint**

  Run `git diff --check` and inspect every Task 3 consumer. Do not use directory-wide staging or commit until the reference/acceptance gate in Task 4 passes.

### Task 4: Close notebook and reference regressions

**Files:**
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookPaneTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreenTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/feature/workspace/ReferenceRendererFixture.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt`
- Modify if a regression is exposed: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferencePane.kt`
- Modify if a regression is exposed: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/HelpPane.kt`
- Modify if a regression is exposed: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferenceDocumentBody.kt`
- Modify if a regression is exposed: `app/src/main/java/com/putnamworkbook/offline/content/ui/MathContentWebView.kt`
- Modify if a regression is exposed: `app/src/main/java/com/putnamworkbook/offline/content/ui/LocalContentClient.kt`
- Verify: all files touched by Tasks 1–3 and the existing save/clipboard/page-navigation suites.

**Interfaces:**
- Consumes: Tasks 1–3 plus existing `NotebookSaveCoordinator`, previous/next page controls, reference selectors, and offline renderer.
- Produces: one coherent regression gate for every notebook/reference requirement in the spec.

- [ ] **Step 1: Correct contextual-control acceptance assertions**

  After switching to Pen, assert Copy/Cut/Paste/Delete-selection nodes are absent. Toggle back to Select before asserting Paste is enabled from the retained process clipboard.

  Also assert no original-source/PDF control, numbered-page navigator, or page-shifting action is present while the parsed reference pane remains available.

- [ ] **Step 2: Add canvas-corner input coverage**

  Draw at top-left, top-right, bottom-left, and bottom-right inside the actual canvas bounds and assert all four strokes arrive. This replaces reliance on merely asserting that a `palm-guard` tag is absent.

- [ ] **Step 3: Extend reference-header tests**

  Use a deterministic local test fixture whose body exceeds the reference pane height and contains representative inline/display KaTeX. Add a controllable local-client response gate for delayed loads and recreate the keyed WebView by switching assets and disposing/recomposing the host. Keep hint and prerequisite selectors visible during delayed loads, long document scrolling, integrity failure, tab switches, and renderer recreation. Assert problem, hint, prerequisite, and solution documents all reach the same `math-content-ready` semantics/event path.

- [ ] **Step 4: Run the new regression classes and verify RED where behavior is missing**

  Compile and run `NotebookPaneTest`, `WorkspaceScreenTest`, and `OfflineWorkbookAcceptanceTest` on an attached target. Any new failing case must be repaired only in the named notebook/reference files, followed by a rerun of the failing class. If no target is attached, compilation is required now and execution remains an explicit final-release gate.

- [ ] **Step 5: Run the complete local regression gate**

  Run: `./gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug`

  Expected: PASS with only previously accepted deprecation warnings.

- [ ] **Step 6: Run available device tests**

  If `adb devices` reports an attached target, run:

  `./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.putnamworkbook.offline.notebook.ui.InkCompletionTest,com.putnamworkbook.offline.notebook.ui.InkStressTest,com.putnamworkbook.offline.notebook.ui.NotebookPaneTest,com.putnamworkbook.offline.notebook.ui.NotebookSelectionTest,com.putnamworkbook.offline.feature.settings.SettingsScreenTest,com.putnamworkbook.offline.feature.workspace.WorkspaceScreenTest,com.putnamworkbook.offline.acceptance.OfflineWorkbookAcceptanceTest"`

  Expected: PASS. If no target is attached, record that instrumentation remains for the final release emulator/tablet gate.

- [ ] **Step 7: Stage and inspect one coherent notebook snapshot**

  Stage only the explicit notebook/settings/navigation/reference/test files named in Tasks 1–4, including any new `CanvasViewport` and handoff test-seam files. Use explicit file paths:

  ```text
  git add -- app/src/main/java/com/putnamworkbook/offline/notebook/input/FinishedStrokeBuffer.kt app/src/main/java/com/putnamworkbook/offline/notebook/edit/CanvasViewport.kt app/src/main/java/com/putnamworkbook/offline/notebook/edit/StrokeGeometry.kt app/src/main/java/com/putnamworkbook/offline/notebook/edit/StrokeTransform.kt app/src/main/java/com/putnamworkbook/offline/notebook/edit/NotebookSelectionState.kt app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookCanvas.kt app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookPane.kt app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookToolbar.kt app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookUiState.kt app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModel.kt app/src/main/java/com/putnamworkbook/offline/notebook/ui/SelectionOverlay.kt
  git add -- app/src/main/java/com/putnamworkbook/offline/feature/settings/SettingsScreen.kt app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferencePane.kt app/src/main/java/com/putnamworkbook/offline/feature/workspace/HelpPane.kt app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferenceDocumentBody.kt app/src/main/java/com/putnamworkbook/offline/content/ui/MathContentWebView.kt app/src/main/java/com/putnamworkbook/offline/content/ui/LocalContentClient.kt
  git add -- app/src/test/java/com/putnamworkbook/offline/notebook/input/FinishedStrokeBufferTest.kt app/src/test/java/com/putnamworkbook/offline/notebook/edit/StrokeGeometryTest.kt app/src/test/java/com/putnamworkbook/offline/notebook/edit/StrokeTransformTest.kt app/src/test/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModelTest.kt
  git add -- app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/InkCompletionTest.kt app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/InkStressTest.kt app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookPaneTest.kt app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt app/src/androidTest/java/com/putnamworkbook/offline/feature/settings/SettingsScreenTest.kt app/src/androidTest/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreenTest.kt app/src/androidTest/java/com/putnamworkbook/offline/feature/workspace/ReferenceRendererFixture.kt app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt
  ```

  Omit any listed optional reference file whose contents did not change. Run `git diff --cached --check`, inspect `git diff --cached --stat`, and read `git diff --cached`; confirm no content schema/catalog/source, generated asset, or release file is present. Compile/test against the staged snapshot by exporting it to a clean verification worktree, so unstaged files cannot conceal a missing dependency.

- [ ] **Step 8: Commit the coherent notebook workstream**

  ```text
  git commit -m "feat: finish offline notebook workspace"
  ```

- [ ] **Step 9: Verify the commit boundary**

  Run the complete local gate again, confirm `git status --short` contains no notebook/settings/navigation/reference change, and only then begin the generic-library plan.
