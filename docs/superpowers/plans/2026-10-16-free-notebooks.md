# Free Notebooks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a named, autosaving Free Notebook library and full-width editor with atomic create, rename, delete, Save, and Save As workflows.

**Architecture:** A new `FreeWorkspaceRepository` stores strict UUID/name/date metadata under the existing notebook backup root and delegates page/stroke editing to the existing notebook repository through reserved internal keys. New library and editor destinations reuse `NotebookPane`; a retryable durable-save notifier advances workspace modified dates only after notebook data is safely written.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Navigation and lifecycle, coroutines/StateFlow, NIO atomic file operations, existing binary notebook storage, JUnit 4, Compose UI testing.

**Spec:** `docs/superpowers/specs/2026-10-16-free-notebooks-and-selection-transforms-design.md`

## Global Constraints

- Work fully offline and add no network permission.
- Reuse the current notebook editor, autosave, undo/redo, page, brush, selection, and backup systems.
- Identify workspace directories only by UUID; never use display names in paths.
- Store workspaces below `notebooks/__local__/free-workspaces/` so existing backup/restore includes them.
- Map workspace UUIDs only through `ProblemKey("__local__", "free-workspaces", workspaceId.toString())`.
- A missing catalog means an empty library; a corrupt catalog is preserved and surfaced, never overwritten silently.
- Names are trimmed, reject ISO control characters, contain 1-80 Unicode code points, and are unique after NFKC plus `Locale.ROOT` lowercase normalization.
- Sort by modified time descending, then normalized name, then UUID.
- Creation prompts for a valid name before files become visible.
- Autosave starts immediately after creation; Save flushes; Save As duplicates and switches to the new copy.
- Modified time advances only after a durable notebook save or successful rename.
- Delete requires confirmation and has no in-app undo.
- Add no migration for existing problem notebooks or user data.

## Review Focus

- Visually different Unicode names that normalize identically must conflict; Task 2 pins this with `nfkcAndCaseVariantsShareOneUniqueName`.
- A crash between directory and catalog publication must not expose a partial workspace; Task 3 pins this with `startupReconcilesStagingOrphansAndTombstones`.
- Save As must preserve inactive strokes and the undo cursor, not only visible ink; Task 1 pins this with `cloneCopiesCompleteHistoryAndEveryReferencedStroke`.
- A metadata-notification failure after a durable stroke save must remain retryable without replaying the stroke edit; Task 6 pins this with `failedModifiedTimeNotificationRetriesWithoutDuplicatingNotebookEdits`.
- Backup restore must recover both catalog metadata and workspace pages; Task 7 pins this with `backupRestoreRoundTripsFreeWorkspaceCatalogAndInk`.

---

### Task 1: Atomic notebook creation and complete clone primitives

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/NotebookRepository.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepository.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepositoryTest.kt`

**Interfaces:**
- Consumes: existing notebook manifests, immutable stroke files, `DurableAtomicFileWriter`, and `ProblemKey`.
- Produces:
  - `suspend fun NotebookRepository.createNotebook(key: ProblemKey, defaultPaper: PaperStyle): NotebookDocument`
  - `suspend fun NotebookRepository.cloneNotebook(sourceKey: ProblemKey, targetKey: ProblemKey): NotebookDocument`

- [ ] **Step 1: Write failing atomic-create and full-clone tests**

Add `createPublishesOnePageNotebookOnlyAfterManifestIsDurable`, `createRejectsAnExistingTarget`, `cloneCopiesCompleteHistoryAndEveryReferencedStroke`, `cloneRewritesOnlyTheDocumentKey`, and `cloneRejectsAnExistingTarget`. Build a source with active and inactive strokes plus undone edit groups; assert the target loads identical pages/history/cursor/stroke bytes under `targetKey` while the source remains unchanged.

- [ ] **Step 2: Write failing containment and failure-rollback tests**

Add `cloneRejectsUnsafeKeys` and `failedCloneLeavesNoTargetManifest`. Use a sibling problem notebook to prove directory containment and that failed cleanup never touches another notebook.

- [ ] **Step 3: Run repository tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.notebook.storage.FileNotebookRepositoryTest" --no-daemon
```

Expected: FAIL because the atomic-create and clone APIs do not exist.

- [ ] **Step 4: Implement staged creation and cloning**

For creation, write a one-page manifest with the final key into a unique sibling staging directory, then atomically move the complete directory to the target. For cloning, copy every stroke referenced by every page, including inactive references, write the cloned manifest with the target key into a sibling staging directory, then atomically move it to the target. Reject source equals target, unsafe keys, missing sources, and existing targets; remove abandoned staging directories on caught failures.

- [ ] **Step 5: Run repository tests and verify GREEN**

Run the command from Step 3.

Expected: PASS with zero failures.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/notebook/storage app/src/test/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepositoryTest.kt
git commit -m "feat: create and clone complete notebooks"
```

### Task 2: Free-workspace model, name policy, and strict catalog codec

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/freeworkspace/FreeWorkspaceRecord.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/freeworkspace/FreeWorkspaceName.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/freeworkspace/FreeWorkspaceCatalogCodec.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/freeworkspace/FreeWorkspaceCatalogCodecTest.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/freeworkspace/FreeWorkspaceNameTest.kt`

**Interfaces:**
- Consumes: the project's strict JSON codec helpers and `java.text.Normalizer`.
- Produces:
  - `data class FreeWorkspaceRecord(val id: UUID, val name: String, val createdAtEpochMillis: Long, val modifiedAtEpochMillis: Long)`
  - `enum class FreeWorkspaceStorageState { READY, MISSING }`
  - `data class FreeWorkspaceEntry(val record: FreeWorkspaceRecord, val storageState: FreeWorkspaceStorageState)`; this is derived at listing time and is not serialized.
  - `enum class FreeWorkspaceNameError { BLANK, TOO_LONG, CONTROL_CHARACTER, DUPLICATE }`
  - `data class ValidatedWorkspaceName(val display: String, val uniquenessKey: String)`
  - `fun validateWorkspaceName(candidate: String, existing: Collection<FreeWorkspaceRecord>, selfId: UUID? = null): Result<ValidatedWorkspaceName>` with a typed `FreeWorkspaceNameException(error)` failure.
  - `FreeWorkspaceCatalogCodec.encode(records: List<FreeWorkspaceRecord>): ByteArray`
  - `FreeWorkspaceCatalogCodec.decode(bytes: ByteArray): List<FreeWorkspaceRecord>`

- [ ] **Step 1: Write failing name-policy tests**

Add `trimsAndPreservesDisplayCapitalization`, `rejectsBlankControlAndOverEightyCodePoints`, `nfkcAndCaseVariantsShareOneUniqueName`, and `renameMayKeepItsOwnNormalizedName`.

- [ ] **Step 2: Write failing strict-codec tests**

Cover deterministic encoding, unknown fields, unsupported version, duplicate IDs, duplicate normalized names, invalid UUIDs, negative or reversed timestamps, trailing content, and the empty catalog.

- [ ] **Step 3: Run free-workspace model tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.freeworkspace.*" --no-daemon
```

Expected: FAIL because the package and APIs do not exist.

- [ ] **Step 4: Implement name validation and immutable records**

Count Unicode code points after trimming. Build uniqueness keys with `Normalizer.Form.NFKC` followed by `lowercase(Locale.ROOT)`. Do not mutate display capitalization.

- [ ] **Step 5: Implement schema-version-1 catalog encoding**

Use exact fields `schemaVersion`, `workspaces`, `id`, `name`, `createdAtEpochMillis`, and `modifiedAtEpochMillis`. Sort records by UUID during encoding for deterministic bytes; listing order belongs to the repository.

- [ ] **Step 6: Run tests and verify GREEN**

Run the command from Step 3.

Expected: PASS with zero failures.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/freeworkspace app/src/test/java/com/putnamworkbook/offline/freeworkspace
git commit -m "feat: define free workspace catalog"
```

### Task 3: Atomic free-workspace repository lifecycle

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/freeworkspace/FreeWorkspaceRepository.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/freeworkspace/FileFreeWorkspaceRepository.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/freeworkspace/FileFreeWorkspaceRepositoryTest.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`

**Interfaces:**
- Consumes: Task 1 atomic-create/clone APIs, Task 2 catalog/name types, `AtomicFileWriter`, default `PaperStyle`, reserved keys, and the validated notebook root.
- Produces:
  - `fun interface EpochMillisClock { fun now(): Long }`, injected into the file repository for deterministic timestamps.
  - `sealed interface FreeWorkspaceResult<out T>` with `Success<T>(value)`, `NameRejected(error)`, and `Failure(message)`.
  - `interface FreeWorkspaceRepository` methods:
    - `suspend fun list(): FreeWorkspaceResult<List<FreeWorkspaceEntry>>`
    - `suspend fun create(name: String, defaultPaper: PaperStyle): FreeWorkspaceResult<FreeWorkspaceRecord>`
    - `suspend fun rename(id: UUID, name: String): FreeWorkspaceResult<FreeWorkspaceRecord>`
    - `suspend fun duplicate(sourceId: UUID, name: String): FreeWorkspaceResult<FreeWorkspaceRecord>`
    - `suspend fun delete(id: UUID): FreeWorkspaceResult<Unit>`
    - `suspend fun markModified(id: UUID): FreeWorkspaceResult<FreeWorkspaceRecord>`
    - `fun notebookKey(id: UUID): ProblemKey`
  - `AppContainer.freeWorkspaceRepository`.

- [ ] **Step 1: Write failing happy-path lifecycle tests**

Add `createListsAndOpensOnePageWorkspace`, `listSortsByModifiedNameThenUuid`, `missingDirectoryListsAsDamaged`, `renameChangesOnlyMetadata`, `duplicateClonesAndUsesNewIdentity`, `deleteRemovesCatalogAndNotebook`, and `markModifiedUsesInjectedClock`.

- [ ] **Step 2: Write failing rollback and recovery tests**

Add `catalogFailureRollsBackCreateAndDuplicate`, `deleteCatalogFailureRestoresTombstoneBeforeDeletion`, `tombstoneCleanupFailureLeavesRecoverableTombstone`, `startupReconcilesStagingOrphansAndTombstones`, `missingCatalogMeansEmptyLibrary`, and `corruptCatalogIsPreservedAndReported`.

- [ ] **Step 3: Run repository tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.freeworkspace.FileFreeWorkspaceRepositoryTest" --no-daemon
```

Expected: FAIL because repository interfaces and implementation do not exist.

- [ ] **Step 4: Implement serialized catalog operations**

Use one coroutine mutex. Store `catalog.json` at `__local__/free-workspaces/`, map IDs with `ProblemKey("__local__", "free-workspaces", id.toString())`, keep UUID notebook directories beside the catalog, use `.stage-` prefixes for stages, and use `.delete-` prefixes for tombstones. Never interpret an unknown ordinary directory as a workspace.

- [ ] **Step 5: Implement create, rename, duplicate, atomic delete, and touch**

Translate `FreeWorkspaceNameException` into `NameRejected`; translate safe storage failures into `Failure` messages limited to 240 characters. Create through `NotebookRepository.createNotebook`; duplicate through `cloneNotebook`. If catalog publication then fails, move the unreferenced UUID directory to a `.stage-rollback-` name before best-effort cleanup so it cannot appear as an ordinary orphan. For delete, validate and move the UUID directory to a unique sibling tombstone, atomically publish the catalog without the record, restore the untouched tombstone only if catalog publication fails, then remove the tombstone. If post-publication cleanup fails, keep the tombstone for startup reconciliation and still report the workspace as deleted; never attempt to restore a tombstone after recursive deletion has begun. Deleting a damaged record with no UUID directory removes only its catalog entry. Use time only through the injected `EpochMillisClock`, and advance it only after an operation is durable.

- [ ] **Step 6: Implement startup reconciliation and corruption preservation**

Restore referenced tombstones, delete unreferenced tombstones and abandoned stages, and preserve unknown ordinary directories. Move an undecodable catalog to a unique `.corrupt` name before returning `Failure`; while no valid `catalog.json` exists but a preserved corrupt catalog does, continue returning the corruption error instead of treating the library as newly empty. A later valid restored catalog takes precedence over preserved diagnostics.

- [ ] **Step 7: Run free-workspace and notebook-storage tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.freeworkspace.*" --tests "com.putnamworkbook.offline.notebook.storage.*" --no-daemon
```

Expected: PASS with zero failures.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/freeworkspace app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt app/src/test/java/com/putnamworkbook/offline/freeworkspace
git commit -m "feat: persist free workspace lifecycle"
```

### Task 4: Free Notebook library state, naming dialog, and list UI

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/freenotebook/FreeWorkspaceListViewModel.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/freenotebook/FreeWorkspaceLibraryScreen.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/freenotebook/FreeWorkspaceNameDialog.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/feature/freenotebook/FreeWorkspaceListViewModelTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/feature/freenotebook/FreeWorkspaceLibraryScreenTest.kt`

**Interfaces:**
- Consumes: `FreeWorkspaceRepository` and result types from Task 3.
- Produces:
  - `FreeWorkspaceListUiState` with `FreeWorkspaceEntry` values, loading/error state, active name-dialog state, delete confirmation, and a monotonic `nameErrorAnimationKey`.
  - `FreeWorkspaceListViewModel.create`, `requestRename`, `rename`, `requestDelete`, `confirmDelete`, and dismiss methods.
  - `FreeWorkspaceLibraryScreen(state, onBack, onNew, onOpen, onRename, onDelete, ...)`.
  - Reusable `FreeWorkspaceNameDialog` for Create, Rename, and later Save As.

- [ ] **Step 1: Write failing view-model tests**

Cover initial sorted load, create success returning the new UUID, rename without moving data, delete confirmation, repository failure retention, and duplicate errors incrementing `nameErrorAnimationKey` while keeping the dialog open.

- [ ] **Step 2: Run view-model tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.freenotebook.FreeWorkspaceListViewModelTest" --no-daemon
```

Expected: FAIL because the feature classes do not exist.

- [ ] **Step 3: Implement list state and operations**

Keep repository calls in a supplied/testable scope. Refresh from repository results after each mutation. Do not dismiss naming dialogs on `NameRejected` or `Failure`.

- [ ] **Step 4: Write failing Compose library tests**

Test empty state, row content and locale-formatted dates, whole-row open, separately clickable Rename/Delete icons, named delete confirmation, blank-name inline error, duplicate-name error semantics, and a changed shake-animation key.

- [ ] **Step 5: Implement the library and shared naming dialog**

Use a top app bar with Back, title, and New workspace. Give each row stable UUID-based test tags. Implement the red outline/glow through Material error colors and a short `Animatable` horizontal translation keyed by `nameErrorAnimationKey`; clear the visual error when text changes.

- [ ] **Step 6: Compile Android tests and run view-model tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.freenotebook.*" :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/feature/freenotebook app/src/test/java/com/putnamworkbook/offline/feature/freenotebook app/src/androidTest/java/com/putnamworkbook/offline/feature/freenotebook
git commit -m "feat: add free notebook library UI"
```

### Task 5: Home and navigation routes

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/AppRoute.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/PutnamApp.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/navigation/ArchiveNavigationTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/navigation/FreeNotebookNavigationTest.kt`

**Interfaces:**
- Consumes: library screen/view-model from Task 4 and `AppContainer.freeWorkspaceRepository` from Task 3.
- Produces:
  - `AppRoute.FreeWorkspaces` path `free-notebooks`.
  - `AppRoute.FreeWorkspace(id: UUID)` path `free-notebooks/{workspaceId}`.
  - Home callback `onFreeNotebook` and test tag `home-free-notebook`.

- [ ] **Step 1: Write failing Home and route tests**

Assert Home shows Free Notebook between Select problem and Settings, clicking it opens the library, Back returns Home, successful Create navigates directly to the new UUID route, and malformed UUID routes display a stable error rather than crash.

- [ ] **Step 2: Compile instrumentation tests and verify missing APIs**

Run:

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: compilation fails on absent route/callback APIs, or the pre-change runtime lacks `home-free-notebook`.

- [ ] **Step 3: Implement routes and library destination**

Use `Uri.encode`/decode for route values and strict `UUID.fromString` handling. Add `freeWorkspaceRepository` to `WorkspaceDependencies` and supply it from `PutnamApp`. Scope the library view model to its navigation entry and close it on disposal. Pass the created UUID to navigation only after repository success.

- [ ] **Step 4: Compile Android tests**

Run the command from Step 2.

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/feature/home/HomeScreen.kt app/src/main/java/com/putnamworkbook/offline/navigation app/src/main/java/com/putnamworkbook/offline/PutnamApp.kt app/src/androidTest/java/com/putnamworkbook/offline/navigation
git commit -m "feat: navigate to free notebooks"
```

### Task 6: Full-width editor, durable modified dates, Save, and Save As

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/freenotebook/DurableSaveNotifier.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/freenotebook/FreeNotebookEditorScreen.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/freenotebook/FreeNotebookEditorCoordinator.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/feature/freenotebook/DurableSaveNotifierTest.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/feature/freenotebook/FreeNotebookEditorCoordinatorTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/feature/freenotebook/FreeNotebookEditorScreenTest.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`

**Interfaces:**
- Consumes: `NotebookViewModel`, Task 3 repository operations, Task 4 naming dialog, Task 5 editor route, and the completed oriented selection toolbar plan.
- Produces:
  - `DurableSaveNotifier(scope, notify: suspend (revision: Long) -> Unit, onFailure: (Throwable) -> Unit)`, `fun enqueue(revision: Long)`, and `suspend fun awaitIdle(): Result<Unit>` with coalesced, retryable metadata notifications.
  - Optional `NotebookViewModel` constructor hook `onDurableSave: (revision: Long) -> Unit = {}`; problem notebooks use the default no-op, while Free Notebooks inject `DurableSaveNotifier::enqueue`.
  - `FreeNotebookEditorCoordinator.save()`, `saveAs(name)`, and `back()` operations.
  - Full-width `FreeNotebookEditorScreen` with Back, title, Save, Save As, and shared `NotebookPane`.

- [ ] **Step 1: Write failing notifier tests**

Add `coalescesSuccessfulDurableRevisions`, `failedModifiedTimeNotificationRetriesWithoutDuplicatingNotebookEdits`, and `awaitIdleWaitsForTheLatestMetadataWrite`. Assert a failed metadata update remains pending while the already-durable stroke edit is not re-enqueued.

- [ ] **Step 2: Write failing editor-coordinator tests**

Cover open by UUID, Save flushing pending edits, Back remaining in editor after flush/metadata failure, Save As prefilled with the current exact name, duplicate rejection retaining the dialog, successful duplication switching IDs, and source/duplicate independence.

- [ ] **Step 3: Run editor JVM tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.freenotebook.DurableSaveNotifierTest" --tests "com.putnamworkbook.offline.feature.freenotebook.FreeNotebookEditorCoordinatorTest" --no-daemon
```

Expected: FAIL because notifier/coordinator APIs do not exist.

- [ ] **Step 4: Implement retryable durable-save notification**

Have `NotebookViewModel.handleSaved` invoke the non-suspending durable-save hook only after its file save succeeds. For a Free Notebook, that hook is `DurableSaveNotifier::enqueue`; the notifier's suspending `notify` callback invokes `FreeWorkspaceRepository.markModified(workspaceId)` and converts any non-success result into a retryable failure. Include notifier completion in the coordinator's idle wait after `NotebookViewModel.awaitIdle()`. Metadata failure calls `onFailure`, retains the newest pending revision, and never replays `NotebookEdit` objects; Save and Back retry the pending metadata notification.

- [ ] **Step 5: Implement coordinator Save, Back, and Save As**

Save waits for both `NotebookViewModel.awaitIdle()` and `DurableSaveNotifier.awaitIdle()`. Back performs the same two waits and navigates only when both succeed. Save As flushes both layers first, opens the exact-name-prefilled dialog, duplicates through the repository, and replaces the current editor route with the new UUID route.

- [ ] **Step 6: Write failing Compose editor tests**

Assert there is no reference pane, the notebook fills remaining width, the workspace name is shown, Save and Save As invoke their callbacks, Save As uses the shared error/shake dialog, and the complete two-row notebook toolbar remains present.

- [ ] **Step 7: Implement and wire the full-width editor**

Reuse `NotebookPane` directly below a compact header. Reuse the same `NotebookViewModel` callback wiring as the problem workspace, including rotate, snap, flip, page, selection, clipboard, and confirmation controls. Flush on lifecycle `ON_STOP`.

- [ ] **Step 8: Run editor tests and compile instrumentation**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.freenotebook.*" :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/feature/freenotebook app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModel.kt app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt app/src/test/java/com/putnamworkbook/offline/feature/freenotebook app/src/androidTest/java/com/putnamworkbook/offline/feature/freenotebook
git commit -m "feat: edit and duplicate free notebooks"
```

### Task 7: Backup, acceptance workflow, and corruption regressions

**Files:**
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/backup/BackupRestoreTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/FreeNotebookAcceptanceTest.kt`
- Modify only if failures require it: `app/src/main/java/com/putnamworkbook/offline/backup/BackupCoordinator.kt`
- Modify only if failures require it: Free Notebook files from Tasks 1-6.

**Interfaces:**
- Consumes: complete Free Notebook feature from Tasks 1-6.
- Produces: backup and end-to-end acceptance coverage.

- [ ] **Step 1: Add backup round-trip coverage**

Add `backupRestoreRoundTripsFreeWorkspaceCatalogAndInk`. Create two named workspaces with multiple pages and strokes, back up, remove local state, restore, then assert names, dates, page styles, stroke samples, and list order.

- [ ] **Step 2: Add end-to-end UI acceptance coverage**

Test Home → Free Notebook → New → name → draw rapidly → Save → library → reopen → Rename → Save As conflict/shake → valid Save As → edit duplicate → confirm source unchanged → Delete confirmation.

- [ ] **Step 3: Add damaged-workspace UI coverage**

Create a catalog record without its UUID directory and assert the row shows a stable damaged-state error, cannot open or duplicate, but can still rename and delete.

- [ ] **Step 4: Run all JVM tests, lint, debug assembly, and Android-test compilation**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL with zero test or lint failures.

- [ ] **Step 5: Run instrumentation tests when a device is attached**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

Expected: all connected tests pass. If no device/emulator exists, record the limitation and do not claim runtime execution.

- [ ] **Step 6: Commit**

```powershell
git add app/src/androidTest app/src/main
git commit -m "test: cover free notebook lifecycle"
```

### Task 8: Whole-feature review and signed APK verification

**Files:**
- Modify only for review findings: files changed by Tasks 1-7 and the oriented-selection plan.
- Generate: `dist/putnam-workbook-2020-2025-v0.4.0.apk`
- Generate: `dist/putnam-workbook-2020-2025-v0.4.0.apk.sha256`

**Interfaces:**
- Consumes: both completed implementation plans and the existing private release-signing workflow.
- Produces: reviewed commits and a verified installable APK signed by the retained certificate.

- [ ] **Step 1: Request whole-branch review**

Review from the pre-feature commit through HEAD against the approved spec. Require explicit attention to atomic lifecycle operations, clone completeness, autosave/metadata failure ordering, navigation replacement after Save As, Unicode name collisions, backup coverage, rotated hit testing, accessibility, and 504dp toolbar fit.

- [ ] **Step 2: Fix every Critical or Important finding with focused regression tests**

Commit each coherent fix without rewriting or discarding unrelated user changes.

- [ ] **Step 3: Run fresh full verification**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build the signed release using transient signing properties**

Use the established external private signing note and keystore. Create only a temporary properties file/link, run `:app:assembleRelease`, and remove every temporary credential file in a `finally` block. Never print signing values.

- [ ] **Step 5: Replace the distribution APK and checksum sidecar**

Copy the signed release APK to the existing `dist` filename and regenerate its SHA-256 sidecar.

- [ ] **Step 6: Verify signature, manifest, offline permission state, checksum, and packaged content**

Run:

```powershell
.\scripts\verify-release.ps1 .\dist\putnam-workbook-2020-2025-v0.4.0.apk
```

Expected: `Release verification passed`, retained signer digest, no INTERNET permission, matching checksum, and a successful packaged-content audit.

- [ ] **Step 7: Confirm a clean committed source tree and no transient signing files**

Run `git status --short`, check that workspace `keystore.properties` is absent, and check that no `putnam-signing-*.properties` temporary files remain.
