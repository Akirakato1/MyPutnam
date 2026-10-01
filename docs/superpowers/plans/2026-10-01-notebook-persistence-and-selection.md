# Notebook Persistence and Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ink immediate and lossless, add vector marquee editing, and replace the notebook page strip with compact top-right navigation.

**Architecture:** The UI owns an optimistic notebook revision while a single debounced writer persists ordered immutable edit groups. Stroke files remain immutable; transforms create replacement strokes and grouped activation/deactivation records provide undo and redo. Pure geometry and reducer units stay independent from Compose, while the canvas switches between AndroidX Ink input and a Compose selection overlay.

**Tech Stack:** Kotlin 2.4, Jetpack Compose, AndroidX Ink 1.0, coroutines, immutable app-private files, JUnit 4, Android instrumented Compose tests.

**Spec:** `docs/superpowers/specs/2026-10-01-notebook-editor-and-content-schema-v2-design.md`

## Global Constraints

- Preserve every visible page and active stroke from notebook manifest version 1.
- Keep stroke files immutable and page coordinates normalized to `0f..1f`.
- Use a 350 ms inactivity debounce and explicit flush barriers.
- A persistence result must never replace a newer in-memory revision.
- Selection is page-local and includes every stroke whose rendered geometry partially intersects the marquee.
- Move and corner-handle scaling remain within page bounds; scaling is proportional.
- The app-local clipboard works across pages and problems for the current process only.
- Do not add page reordering, lasso selection, rotation, non-proportional scaling, or a system clipboard format.

## Review Focus

- Multiple AndroidX Ink completion callbacks arriving together or out of order must emit every stroke exactly once (Task 4).
- App backgrounding or Home during the debounce window must flush dirty edits before dependent work proceeds (Tasks 3 and 8).
- A thick one-point stroke or a segment merely touching the marquee boundary must count as intersecting (Task 5).
- Move/scale previews near every page edge must clamp without changing aspect ratio or producing invalid samples (Tasks 5 and 6).
- A corrupt or partially migrated v1 manifest must retain the original file and must not overwrite it with an empty notebook (Task 1).

---

### Task 1: Versioned Edit History and Manifest Migration

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/model/NotebookEditGroup.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/model/{NotebookDocument.kt,NotebookPage.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/NotebookManifestCodec.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepository.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/storage/NotebookManifestCodecTest.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepositoryTest.kt`

**Interfaces:**
- Produces: `NotebookEditGroup(id: String, activatedStrokeIds: List<String>, deactivatedStrokeIds: List<String>)`.
- Produces: `NotebookPage.editHistory: List<NotebookEditGroup>` and `NotebookPage.appliedEditCount: Int`.
- Produces: `NotebookManifestCodec.decode(bytes): DecodedNotebookManifest`, where metadata includes `sourceVersion` and a v2 `NotebookDocument`.

- [ ] **Step 1: Write failing codec/repository tests** for v2 round-trip, v1 active-stroke migration, corrupt-v1 preservation, one-time `manifest.bin.v1.bak`, invalid cursors, overlapping activation/deactivation IDs, and trailing data.
- [ ] **Step 2: Run** `./gradlew.bat :app:testDebugUnitTest --tests '*NotebookManifestCodecTest*' --tests '*FileNotebookRepositoryTest*'`; expect the new migration/history assertions to fail.
- [ ] **Step 3: Implement** schema version 2, strict edit-group invariants, dual-version decoding, and atomic one-time v1 backup without changing stroke-file encoding.
- [ ] **Step 4: Re-run the Task 1 tests**; expect all to pass and the original manifest to remain untouched on injected migration failure.
- [ ] **Step 5: Commit** with `git commit -m "feat: add versioned notebook edit history"`.

### Task 2: Pure Reducer and Batched Repository Edits

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/model/NotebookEdit.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/model/NotebookReducer.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/NotebookRepository.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepository.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/model/NotebookReducerTest.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 edit groups and page history cursor.
- Produces: `NotebookEdit(group: NotebookEditGroup, newStrokes: List<StrokeRecord>)`.
- Produces: `NotebookReducer.applyEdits(page, edits)`, `undo(page)`, and `redo(page)`.
- Produces: `NotebookRepository.applyEdits(key, pageId, edits)`, `undo(key, pageId)`, and `redo(key, pageId)`.

- [ ] **Step 1: Write failing reducer/repository tests** proving ten edits share one manifest write, transformed replacements deactivate originals, undo/redo acts on whole groups, new edits truncate redo history, retries accept identical orphan stroke files, and a manifest-write failure leaves the prior document durable.
- [ ] **Step 2: Run** `./gradlew.bat :app:testDebugUnitTest --tests '*NotebookReducerTest*' --tests '*FileNotebookRepositoryTest*'`; expect missing-interface failures.
- [ ] **Step 3: Implement** the pure reducer and one-lock batch repository methods; write every new immutable stroke before one atomic manifest replacement.
- [ ] **Step 4: Re-run the Task 2 tests**; expect all to pass, including the injected writer failure and retry.
- [ ] **Step 5: Commit** with `git commit -m "feat: persist grouped notebook edits"`.

### Task 3: Optimistic State and Debounced Save Coordinator

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookSaveCoordinator.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/{NotebookUiState.kt,NotebookViewModel.kt}`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/ui/NotebookSaveCoordinatorTest.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 `NotebookEdit` and `NotebookRepository.applyEdits`.
- Produces: `NotebookSaveCoordinator.enqueue(pageId, edit)`, `flush()`, `awaitIdle()`, and `cancel()`.
- Produces: `NotebookViewModel.flush()` and immediate optimistic `commitStroke(stroke)`.

- [ ] **Step 1: Write failing controlled-wait tests** proving rapid strokes appear immediately, remain ordered during a blocked repository call, batch after the injected debounce gate, flush on demand, retain dirty edits after failure, retry successfully, and ignore stale durable revisions.
- [ ] **Step 2: Run** `./gradlew.bat :app:testDebugUnitTest --tests '*NotebookSaveCoordinatorTest*' --tests '*NotebookViewModelTest*'`; expect missing coordinator and optimistic-state failures.
- [ ] **Step 3: Implement** a single ordered writer with default `350L` debounce, injectable suspend wait for deterministic tests, monotonic UI/durable revisions, and failure-preserving pending edits.
- [ ] **Step 4: Re-run Task 3 tests**, then `./gradlew.bat :app:testDebugUnitTest`; expect the full JVM suite to pass.
- [ ] **Step 5: Commit** with `git commit -m "fix: buffer notebook saves without stale redraws"`.

### Task 4: Lossless AndroidX Ink Completion Buffer

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/input/FinishedStrokeBuffer.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookCanvas.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/input/FinishedStrokeBufferTest.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/InkCompletionTest.kt`

**Interfaces:**
- Produces: `FinishedStrokeBuffer<K>.put(id, stroke)`, `drain(finishedIds): List<StrokeRecord>`, and `clear()`.
- Consumes: Task 3 immediate `onStrokeFinished` handling.

- [ ] **Step 1: Write failing tests** for two strokes completing before one callback, reverse callback order, duplicate callback IDs, cancellation, and a callback containing multiple IDs.
- [ ] **Step 2: Run** `./gradlew.bat :app:testDebugUnitTest --tests '*FinishedStrokeBufferTest*'`; expect the missing buffer failure.
- [ ] **Step 3: Replace** the single `pendingStroke` slot with an ID-keyed buffer associated with each `InProgressStrokeId`; emit and remove every finished entry exactly once.
- [ ] **Step 4: Run** the unit test and `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.putnamworkbook.offline.notebook.ui.InkCompletionTest`; expect all assertions to pass.
- [ ] **Step 5: Commit** with `git commit -m "fix: preserve overlapping ink completions"`.

### Task 5: Selection Geometry and Page-Bound Transforms

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/{NormalizedRect.kt,StrokeGeometry.kt,StrokeTransform.kt}`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/edit/{StrokeGeometryTest.kt,StrokeTransformTest.kt}`

**Interfaces:**
- Produces: `StrokeRecord.intersects(rect: NormalizedRect): Boolean` and `Collection<StrokeRecord>.selectionBounds()`.
- Produces: `StrokeTransform.move(strokes, dx, dy, idFactory)` and `scaleFromCorner(strokes, bounds, corner, target, idFactory)` returning clamped replacement strokes.

- [ ] **Step 1: Write failing geometry tests** for crossing, touching, enclosing, outside, one-point, thick-brush, degenerate marquee, all four corners, minimum scale, page-edge clamping, preserved pressure/timing/brush, and proportional aspect ratio.
- [ ] **Step 2: Run** `./gradlew.bat :app:testDebugUnitTest --tests '*StrokeGeometryTest*' --tests '*StrokeTransformTest*'`; expect missing-type failures.
- [ ] **Step 3: Implement** segment/expanded-rectangle intersection and normalized move/scale transforms without Android UI dependencies.
- [ ] **Step 4: Re-run Task 5 tests**; expect exact normalized bounds and no sample outside `0f..1f`.
- [ ] **Step 5: Commit** with `git commit -m "feat: add vector stroke selection geometry"`.

### Task 6: Selection State, Clipboard, and Grouped Editing

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/edit/{NotebookClipboard.kt,NotebookSelectionState.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/{NotebookUiState.kt,NotebookViewModel.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/{di/AppContainer.kt,navigation/PutnamNavHost.kt}`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/edit/NotebookClipboardTest.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModelTest.kt`

**Interfaces:**
- Produces: `NotebookToolMode.PEN|SELECT` and a process-singleton `NotebookClipboard` with `copy(strokes)`, `hasContent`, and `createPaste(pageStrokes, idFactory)`, injected into each problem's view model.
- Produces view-model actions: `select(rect)`, `previewMove`, `commitMove`, `previewScale`, `commitScale`, `copySelection`, `cutSelection`, `paste`, `deleteSelection`, and `cancelSelection`.

- [ ] **Step 1: Write failing tests** for partial-intersection selection, empty selection, cross-page/problem clipboard reuse, unique pasted IDs, offset-and-clamp placement, transform preview cancellation, grouped cut/paste/delete, selection after paste, Pen-mode clearing, and redo invalidation.
- [ ] **Step 2: Run** `./gradlew.bat :app:testDebugUnitTest --tests '*NotebookClipboardTest*' --tests '*NotebookViewModelTest*'`; expect missing APIs.
- [ ] **Step 3: Implement** one `AppContainer` clipboard, inject it through `WorkspaceDependencies`, and construct view-model edits using Tasks 2, 3, and 5; never persist clipboard content.
- [ ] **Step 4: Re-run Task 6 tests** and the full JVM suite; expect all to pass.
- [ ] **Step 5: Commit** with `git commit -m "feat: add grouped selection editing"`.

### Task 7: Pen/Select Canvas and Contextual Controls

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/SelectionOverlay.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/{NotebookCanvas.kt,NotebookPane.kt,NotebookToolbar.kt}`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookPaneTest.kt`

**Interfaces:**
- Consumes: Task 6 tool/selection state and actions.
- Produces test-tagged Pen, Select, Cut, Copy, Paste, Delete selection, Cancel, marquee, and four corner-handle controls.

- [ ] **Step 1: Write failing Compose tests** proving Select suppresses ink, a drag draws a marquee, partial strokes become selected, inside drag moves, corner drag previews proportional scale, actions call once, Paste enables from clipboard, and Pen clears the marquee.
- [ ] **Step 2: Run** the two instrumented test classes; expect missing nodes/actions.
- [ ] **Step 3: Implement** a Compose overlay above the persistent canvas, render transformed previews without writing, and mount AndroidX Ink input only in Pen mode.
- [ ] **Step 4: Re-run Task 7 instrumented tests**; expect every control and gesture assertion to pass.
- [ ] **Step 5: Commit** with `git commit -m "feat: add notebook pen and select modes"`.

### Task 8: Page Navigation, Flush Boundaries, and Palm-Guard Removal

**Files:**
- Delete: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/{PageStrip.kt,PalmGuard.kt}`
- Delete: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/PalmGuardTest.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/{NotebookPane.kt,NotebookToolbar.kt,NotebookViewModel.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/{NotebookRepository.kt,FileNotebookRepository.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/{notebook/ui/NotebookPaneTest.kt,feature/settings/SettingsScreenTest.kt,acceptance/OfflineWorkbookAcceptanceTest.kt}`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 3 `flush()` and Task 7 toolbar.
- Produces: top-right `previous-page`, `notebook-page-index`, and `next-page` controls; lifecycle `ON_STOP` flush.

- [ ] **Step 1: Write failing tests** asserting `Page 1 of 2` at the top right, disabled boundary arrows, no numbered buttons/reorder/palm guard controls, no drag page turn, Home/page/export/`ON_STOP` flush, and retained legacy palm settings remaining harmless.
- [ ] **Step 2: Run** relevant JVM and instrumented tests; expect old page-strip and palm-guard behavior to conflict.
- [ ] **Step 3: Implement** compact arrow navigation, remove rendering/settings entry points for the guard, delete reorder APIs, and wire flush barriers before state-dependent actions.
- [ ] **Step 4: Run** `./gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest`; expect all notebook, settings, backup, export, and acceptance tests to pass.
- [ ] **Step 5: Commit** with `git commit -m "feat: simplify notebook navigation and flush lifecycle"`.
