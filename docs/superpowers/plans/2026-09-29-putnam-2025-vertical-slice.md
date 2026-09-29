# Putnam 2025 Offline Workbook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify a production-quality, self-contained Android APK that provides the complete offline workbook experience for all twelve 2025 Putnam problems.

**Architecture:** A native Kotlin/Jetpack Compose app reads immutable content from a prepackaged Room database and local static assets, while progress and settings live in a separate Room database and vector notebooks live in app-private files. A desktop Node/Python pipeline validates structured source records, pre-renders mathematics to script-free local HTML, packages official PDFs, and produces the content database consumed by the app.

**Tech Stack:** JDK 17; Gradle 9.6.0; Android Gradle Plugin 9.4.0 with built-in Kotlin; KSP 2.3.12; compile SDK 37; target SDK 36; min SDK 29; Jetpack Compose BOM 2026.09.00; Activity Compose 1.13.0; Navigation Compose 2.10.2; Room 2.8.5; AndroidX Ink 1.0.0; AndroidX WebKit 1.17.1; Node.js 22; Python 3.9+ standard library; KaTeX and Playwright Chromium with versions pinned in `package-lock.json`.

**Spec:** `docs/superpowers/specs/2026-09-29-putnam-offline-workbook-design.md`

## Global Constraints

- Application ID: `com.putnamworkbook.offline`; retain it unchanged for every sideloaded upgrade.
- Minimum Android version: Android 10, API 29.
- Target SDK: Android 16, API 36. Compile SDK: API 37, as required by Compose 1.12.x from BOM 2026.09.00.
- Primary device: Galaxy Tab A11+ SM-X230, 1920 × 1200 in landscape.
- The app must not declare `android.permission.INTERNET` or load remote resources.
- The APK must contain every required content record, font, diagram, rendering asset, and original 2025 PDF.
- Touch input must work with a finger, rubber-tip stylus, or universal active-capacitive stylus without pressure, hover, or stylus-button assumptions.
- The app stores immutable content separately from mutable progress/settings and notebook files.
- Visible content must contain no raw LaTeX, missing glyphs, clipped equations, or broken assets.
- Content is for personal, noncommercial study; source attribution must remain visible.
- Use only stable Android/AndroidX dependencies listed above; do not substitute alpha APIs.
- Use manual constructor injection through `AppContainer`; do not add Hilt or another DI framework.
- All destructive notebook operations and restore actions require confirmation.
- Do not commit signing passwords, local SDK paths, or backup archives.

## Review Focus

- A second finger or palm contact during a stroke must not create a second stroke or navigate pages; Task 6 adds multi-pointer and guarded-region tests.
- Process death or an I/O failure during autosave must preserve every previously finalized stroke; Task 5 adds injected-failure and recovery tests.
- Missing or corrupt generated HTML/PDF assets must show a content-integrity error and preserve the alternate view; Task 8 adds both failure-path tests.
- A backup containing path traversal, mismatched hashes, or a newer schema must leave current data untouched; Task 12 adds adversarial restore tests.
- Installing a newly built APK with the same application ID/signing key must preserve progress and notebook files while replacing immutable content; Task 19 adds an upgrade rehearsal.

---

## Planned File Structure

```text
app/src/main/java/com/putnamworkbook/offline/
  PutnamApplication.kt                 # process-level dependency container
  MainActivity.kt                      # single activity and edge-to-edge host
  PutnamApp.kt                         # theme and navigation root
  di/AppContainer.kt                   # manual dependency graph
  domain/model/                        # shared IDs, status, paper, brush models
  data/content/                        # immutable Room entities, DAO, repository
  data/user/                           # progress/settings Room entities and repositories
  notebook/model/                      # vector notebook and stroke models
  notebook/storage/                    # versioned, atomic notebook-file persistence
  notebook/input/                      # touch-to-stroke normalization
  notebook/ui/                         # canvas, palm guard, pages, and toolbar
  content/ui/                          # secure local HTML and PDF viewers
  feature/home/                        # main menu
  feature/archive/                     # year/problem browser
  feature/workspace/                   # split workspace and help workflow
  feature/settings/                    # preferences, backup, attribution
  backup/                              # validated backup archive creation/restore
  export/                              # workbook PDF export
tools/content/                         # structured-content validation/build pipeline
content-src/2025/                      # reviewed 2025 records and official source files
app/src/main/assets/                   # generated content database/HTML/PDF assets
```

## Task 1: Bootstrap the Android Project and Privacy Baseline

**Files:**
- Create: `.gitignore`, `README.md`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- Create: `gradle/libs.versions.toml`, `gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}`, `gradlew`, `gradlew.bat`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/putnamworkbook/offline/{PutnamApplication.kt,MainActivity.kt,PutnamApp.kt}`
- Create: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/{AppLaunchTest.kt,PrivacyManifestTest.kt}`

**Interfaces:**
- Produces: `class AppContainer(context: Context)`; `@Composable fun PutnamApp(container: AppContainer)`.

- [ ] **Step 1: Provision the build prerequisites**

Install or select JDK 17, Android SDK Platform 37, Build Tools 36.0.0, platform-tools, and an API 36 emulator. Record only the SDK path in untracked `local.properties`; do not change the machine's existing Java 8 installation globally.

- [ ] **Step 2: Write failing launch and permission tests**

`AppLaunchTest.app_root_is_visible()` must assert a node tagged `app-root`; `PrivacyManifestTest.internet_permission_is_absent()` must assert that the installed package's requested permissions exclude `android.permission.INTERNET`.

- [ ] **Step 3: Run the tests and confirm the scaffold is missing**

Run: `./gradlew :app:connectedDebugAndroidTest`

Expected: FAIL because the Android project/application does not yet exist.

- [ ] **Step 4: Create the minimal buildable application**

Use the exact versions in the plan header, application ID `com.putnamworkbook.offline`, `minSdk = 29`, `targetSdk = 36`, `compileSdk = 37`, edge-to-edge layout, Material 3, and no permissions. `PutnamApp` renders a tagged root with a temporary “Putnam Workbook” label.

- [ ] **Step 5: Verify the build and tests**

Run: `./gradlew help :app:assembleDebug :app:connectedDebugAndroidTest`

Expected: all commands succeed and both instrumented tests pass.

- [ ] **Step 6: Commit**

```bash
git add .gitignore README.md settings.gradle.kts build.gradle.kts gradle.properties gradle app
git commit -m "build: bootstrap offline Android workbook"
```

## Task 2: Define the Structured Content Contract and Renderer

**Files:**
- Create: `tools/content/package.json`, `tools/content/package-lock.json`
- Create: `tools/content/schema/problem.schema.json`
- Create: `tools/content/src/{content-types.mjs,validate-problem.mjs,render-document.mjs,render-review.mjs,build-year.mjs}`
- Create: `tools/content/templates/{document.html,content.css}`
- Create: `tools/content/test/{validate-problem.test.mjs,render-document.test.mjs,fixtures/valid-problem.json}`

**Interfaces:**
- Produces: `validateProblem(record: unknown): string[]`; `renderDocument(document: RichDocument): string`; `buildYear(sourceDir: string, outputDir: string): Promise<BuildManifest>`.
- Produces record contract: `{ schemaVersion: 1, year, code, ordinal, statement, hints[4], prerequisites, solution, sources, originalPdf }`.
- `RichDocument.blocks` accepts only `paragraph`, `displayMath`, `heading`, `list`, and `figure`; prose uses escaped text plus explicit inline-math runs, never raw HTML.

- [ ] **Step 1: Write failing contract tests**

Test that a valid record passes; three or five hints fail; duplicate hint order fails; remote figure URLs fail; `<script>` in prose is escaped; invalid TeX reports the JSON path that contains it; and output HTML contains a restrictive CSP with no script tag.

- [ ] **Step 2: Run the Node tests and observe failure**

Run: `node --test tools/content/test/*.test.mjs`

Expected: FAIL because validators and renderers are absent.

- [ ] **Step 3: Implement schema validation and build-time KaTeX rendering**

Escape prose before layout, render inline/display math at build time, resolve figures only beneath the problem directory, copy required KaTeX CSS/fonts into the generated local asset tree, emit static script-free HTML, and return all validation errors in one run. `render-review.mjs` uses pinned Playwright Chromium to flag console errors, horizontal overflow, blank output, and broken local assets and to write review screenshots.

- [ ] **Step 4: Verify tests and deterministic output**

Run: `npm --prefix tools/content ci && npm --prefix tools/content test`

Run the fixture build twice and compare SHA-256 hashes of every output file; expected hashes are identical.

- [ ] **Step 5: Commit**

```bash
git add tools/content
git commit -m "feat: add validated math content pipeline"
```

## Task 3: Package Immutable Content and Expose the Content Repository

**Files:**
- Create: `tools/content/sql/content-schema.sql`
- Create: `tools/content/src/build-content-db.py`
- Create: `tools/content/test/test_build_content_db.py`
- Create, generated: `app/src/main/assets/databases/content.db`
- Create: `app/src/main/java/com/putnamworkbook/offline/domain/model/ProblemKey.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/content/{ContentEntities.kt,ContentDao.kt,ContentDatabase.kt,ContentRepository.kt,RoomContentRepository.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/data/content/ContentDatabaseTest.kt`
- Test asset: `app/src/androidTest/assets/databases/content-fixture.db`

**Interfaces:**
- Produces: `data class ProblemKey(val year: Int, val code: String)`.
- Produces: `data class ProblemSummary(val key: ProblemKey, val ordinal: Int)` and `ProblemContent(summary, statementAssetPath, hints, prerequisites, solutionAssetPath, originalPdf, attribution)`.
- Produces: `interface ContentRepository { suspend fun years(): List<Int>; suspend fun problems(year: Int): List<ProblemSummary>; suspend fun problem(key: ProblemKey): ProblemContent? }`.
- Consumes: Task 2 `BuildManifest` and generated HTML paths.

- [ ] **Step 1: Write failing Python and Room tests**

The Python test asserts deterministic row ordering and foreign-key rejection. The Android test opens the prepackaged fixture through Room and asserts year, problem code, four ordered hints, prerequisite order, solution asset, attribution, PDF path, page index, and SHA-256 fields.

- [ ] **Step 2: Run tests and observe missing database code**

Run: `python -m unittest discover -s tools/content/test -p "test_*.py"`

Run: `./gradlew :app:connectedDebugAndroidTest`

Expected: FAIL because the builder and Room schema do not exist.

- [ ] **Step 3: Implement matching SQLite and Room schemas**

Use tables `problem`, `hint`, `prerequisite`, and `content_meta`; enable foreign keys; export the Room schema; generate an empty production database until 2025 content is added; open it with `createFromAsset("databases/content.db")`; never use a destructive migration.

- [ ] **Step 4: Build and verify the fixture database**

Run both commands from Step 2, then run `./gradlew :app:assembleDebug`.

Expected: all pass and Room accepts the Python-created prepackaged database without a schema mismatch.

- [ ] **Step 5: Commit**

```bash
git add tools/content app/src
git commit -m "feat: add immutable problem repository"
```

## Task 4: Persist Progress, Recent Work, and Settings Separately

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/domain/model/{ProblemStatus.kt,PaperStyle.kt,BrushSpec.kt,AppSettings.kt,ReferenceTab.kt}`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/user/{UserEntities.kt,UserDao.kt,UserDatabase.kt,ProgressRepository.kt,RoomProgressRepository.kt,SettingsRepository.kt,RoomSettingsRepository.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/data/user/{ProgressRepositoryTest.kt,UserDatabaseSchemaTest.kt}`

**Interfaces:**
- Produces: `enum class ProblemStatus { UNTOUCHED, IN_PROGRESS, COMPLETED }`.
- Produces: `enum class ReferenceTab { PROBLEM, HELP, PREREQUISITES, SOLUTION, ORIGINAL_PAPER }`.
- Produces: `data class ProblemProgress(key, status, revealedHintCount, solutionRevealed, notebookPageCount, activePageIndex, activeReferenceTab, lastOpenedAt)`.
- Produces: `ProgressRepository.observe(key): Flow<ProblemProgress>`, `observeAll(): Flow<Map<ProblemKey, ProblemProgress>>`, `observeRecent(): Flow<ProblemProgress?>`, `suspend markOpened(key): Unit`, `suspend revealNextHint(key): Int`, `suspend revealSolution(key): Unit`, `suspend markCompleted(key, completed): Unit`, and `suspend setNotebookState(key, pageCount, pageIndex, referenceTab): Unit`.
- Produces: `SettingsRepository.observe(): Flow<AppSettings>` and `suspend update(transform: (AppSettings) -> AppSettings): Unit` with handedness, palm guard, default paper, brush, theme, and keep-awake values.

- [ ] **Step 1: Write failing repository tests**

Assert untouched → in-progress on open; hint count is monotonic and capped at four; solution reveal is separate; completion is reversible; recent problem/page/reference-tab updates atomically; and changing default paper does not mutate an existing-page fixture.

- [ ] **Step 2: Run the focused tests**

Run: `./gradlew :app:connectedDebugAndroidTest`

Expected: FAIL because the user database is absent.

- [ ] **Step 3: Implement version-1 user storage**

Create a separate `user.db`, export its schema, use transactions for reveal/recent updates, and omit every destructive fallback API.

- [ ] **Step 4: Verify repository and schema tests**

Run the command from Step 2; expected all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: persist progress and workbook settings"
```

## Task 5: Implement Versioned Atomic Notebook Storage

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/model/{NotebookDocument.kt,NotebookPage.kt,StrokeRecord.kt}`
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/{NotebookRepository.kt,FileNotebookRepository.kt,NotebookManifestCodec.kt,StrokeCodec.kt,AtomicFileWriter.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/storage/{StrokeCodecTest.kt,FileNotebookRepositoryTest.kt}`

**Interfaces:**
- Produces normalized `StrokeSample(x: Float, y: Float, elapsedMillis: Long, pressure: Float?)` and immutable `StrokeRecord(id, brush, samples)`.
- Produces: `NotebookRepository.loadOrCreate(key, defaultPaper): NotebookDocument`, `loadStrokes(key, pageId): List<StrokeRecord>`, `commitStroke(key, pageId, stroke): NotebookDocument`, `setStrokeActive(key, pageId, strokeId, active): NotebookDocument`, `addPage(key, afterPageId, paperStyle): NotebookDocument`, `deletePage(key, pageId): NotebookDocument`, `clearPage(key, pageId): NotebookDocument`, and `reorderPage(key, pageId, newIndex): NotebookDocument`; every method is suspending.
- Storage root: `files/notebooks/<year>-<code>/`; manifests use schema version `1`; each finalized stroke is a separate binary file.

- [ ] **Step 1: Write failing round-trip, history, and recovery tests**

Assert sample/brush round-trip; coordinates remain normalized; undo/redo active flags persist; committing after undo discards the redo tail; an injected manifest-write failure preserves the preceding manifest and strokes; and a corrupt manifest is retained as `.corrupt` rather than deleted.

- [ ] **Step 2: Run JVM tests and observe failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*StrokeCodecTest*' --tests '*FileNotebookRepositoryTest*'`

Expected: FAIL because notebook persistence is absent.

- [ ] **Step 3: Implement immutable stroke files and atomic manifests**

Write new files to a sibling temporary path, flush, then replace atomically where supported with a checked fallback. Ignore unreferenced orphan stroke files during load and preserve them until an explicit maintenance pass.

- [ ] **Step 4: Verify focused and full JVM tests**

Run: `./gradlew :app:testDebugUnitTest`; expected all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: add durable vector notebook storage"
```

## Task 6: Capture Touch Input and Render Low-Latency Ink

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/input/{PointerSampleEvent.kt,TouchStrokeRecorder.kt,MotionEventAdapter.kt}`
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/{NotebookCanvas.kt,PalmGuard.kt}`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/input/TouchStrokeRecorderTest.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/{PalmGuardTest.kt,InkStressTest.kt}`

**Interfaces:**
- Produces: `TouchStrokeRecorder.onEvent(event, canvasWidth, canvasHeight): RecorderResult`.
- Produces: `@Composable fun NotebookCanvas(page, strokes, brush, palmGuard, onStrokeFinished)`.
- Produces: `data class PalmGuardState(side, widthFraction, heightFraction, verticalOffsetFraction)`.
- Consumes: Task 5 `StrokeRecord` and `StrokeSample`.

- [ ] **Step 1: Write failing input tests**

Assert down/move/up emits one normalized stroke; cancel emits none; a second pointer is ignored; lifting the primary pointer finishes exactly once; pressure is nullable; and touches consumed inside the palm-guard test tag never reach the recorder.

- [ ] **Step 2: Run focused tests and observe failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*TouchStrokeRecorderTest*' :app:connectedDebugAndroidTest`

Expected: FAIL because input and canvas code are absent.

- [ ] **Step 3: Implement the recorder and AndroidX Ink adapter**

Use AndroidX Ink 1.0.0 for in-progress/final rendering, store the app's normalized samples as the durable format, and keep page navigation outside the canvas gesture detector.

- [ ] **Step 4: Verify touch classes and a 2,000-stroke render fixture**

Run the command from Step 2; `InkStressTest` must render 2,000 strokes without duplicate output, a crash, or an out-of-memory result.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: add touch-first low-latency ink canvas"
```

## Task 7: Build Notebook Page Controls and Autosaving Session

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/{NotebookViewModel.kt,NotebookUiState.kt,NotebookPane.kt,NotebookToolbar.kt,PageStrip.kt}`
- Test: `app/src/test/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModelTest.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookPaneTest.kt`

**Interfaces:**
- Produces: `NotebookViewModel.open(key: ProblemKey)`, `commitStroke(stroke: StrokeRecord)`, `undo()`, `redo()`, `addPage()`, `requestDeletePage(pageId)`, `confirmDeletePage()`, `requestClearPage(pageId)`, `confirmClearPage()`, `reorderPage(pageId, newIndex)`, and `selectPage(index)`.
- Emits: `NotebookUiState(document, activePageIndex, strokes, pendingConfirmation, saveState)`.
- Consumes: Task 4 defaults and Task 5 repository.

- [ ] **Step 1: Write failing state and UI tests**

Assert first open creates one page; each finished stroke autosaves; undo/redo survives reload; new pages use current defaults; existing pages retain their style; delete/clear require confirmation; explicit previous/next controls change pages; and a one-finger horizontal drag on the canvas does not change pages.

- [ ] **Step 2: Run focused tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*NotebookViewModelTest*' :app:connectedDebugAndroidTest`

Expected: FAIL because the notebook session UI is absent.

- [ ] **Step 3: Implement page state, tools, and page-turn animation**

Use whole-stroke erasing, large touch targets, explicit page buttons, blank/square-grid backgrounds, and a short horizontal transition after a confirmed page change.

- [ ] **Step 4: Verify focused tests**

Run the command from Step 2; expected all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: add autosaving workbook pages and tools"
```

## Task 8: Add Secure Mathematical Content and Original PDF Viewing

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/content/ui/{MathContentWebView.kt,LocalContentClient.kt,OriginalPdfRepository.kt,OriginalPdfViewModel.kt,OriginalPdfPane.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/content/ui/{LocalContentClientTest.kt,OriginalPdfRepositoryTest.kt}`
- Test asset: `app/src/androidTest/assets/pdf/fixture.pdf`

**Interfaces:**
- Produces: `@Composable fun MathContentWebView(assetPath: String, onIntegrityError: (String) -> Unit)`.
- Produces: `OriginalPdfRepository.materialize(assetPath: String, sha256: String): Result<File>`.
- Loads only `https://appassets.androidplatform.net/assets/...` through `WebViewAssetLoader`.

- [ ] **Step 1: Write failing security and corruption tests**

Assert JavaScript, file access, content access, mixed content, popups, and remote navigation are disabled; local CSS/fonts/images load; a missing HTML asset reports an integrity error while leaving Original Paper selectable; and a corrupt/missing PDF reports an error while leaving normalized content readable.

- [ ] **Step 2: Run focused instrumented tests**

Run: `./gradlew :app:connectedDebugAndroidTest`

Expected: FAIL because the viewers do not exist.

- [ ] **Step 3: Implement local WebView and tiled PDF rendering**

Copy a selected PDF asset into a checksum-keyed cache file before opening `PdfRenderer`; render pages off the main thread and retain only visible page bitmaps.

- [ ] **Step 4: Verify tests and inspect the manifest**

Run the command from Step 2 and `./gradlew :app:processDebugMainManifest`; assert the merged manifest contains no INTERNET permission.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: add secure offline math and PDF viewers"
```

## Task 9: Implement Main Menu and Archive Navigation

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/navigation/{AppRoute.kt,PutnamNavHost.kt}`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/home/{HomeScreen.kt,HomeViewModel.kt}`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/archive/{ArchiveScreen.kt,ArchiveViewModel.kt,ProblemListScreen.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/PutnamApp.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/navigation/ArchiveNavigationTest.kt`

**Interfaces:**
- Produces typed routes: `Home`, `ArchiveYears`, `YearProblems(year)`, `Workspace(year, code)`, and `Settings`.
- Consumes: Task 3 summaries and Task 4 progress/recent state.

- [ ] **Step 1: Write the failing navigation test**

Assert Home has Continue, Select problem, and Settings; Continue is disabled without recents; Select problem opens descending years; 2025 opens A1–A6 then B1–B6; rows show untouched/in-progress/completed, hint count, and notebook page count; system Back follows the approved hierarchy.

- [ ] **Step 2: Run the navigation test**

Run: `./gradlew :app:connectedDebugAndroidTest`

Expected: FAIL because routes and screens are absent.

- [ ] **Step 3: Implement navigation with fake-friendly view models**

Use repository interfaces in constructors, stable test tags, and no recommendation, ranking, or guided-practice UI.

- [ ] **Step 4: Verify navigation tests**

Run the command from Step 2; expected all assertions pass.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: add year-first archive navigation"
```

## Task 10: Integrate the Split Workspace and Progressive Help

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/{WorkspaceScreen.kt,WorkspaceViewModel.kt,WorkspaceUiState.kt,ReferencePane.kt,HelpPane.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModelTest.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreenTest.kt`

**Interfaces:**
- Produces: `WorkspaceViewModel.open(key: ProblemKey)`, `revealNextStage()`, `setCompleted(completed: Boolean)`, and `selectReferenceTab(tab: ReferenceTab)`.
- Emits tabs: Problem, Help, Prerequisites, Solution, Original Paper.
- Consumes: Tasks 3, 4, 7, and 8.

- [ ] **Step 1: Write failing help and layout tests**

Assert hints reveal in order 1–4; Solution remains a deliberate fifth reveal and is tracked separately; previously revealed stages persist; opening marks in progress; completion updates the archive; width ≥ 840dp uses a 40/60 split; smaller width shows a Reference/Notebook toggle; Home autosaves and returns to Home; Continue restores the exact problem/page/tab.

- [ ] **Step 2: Run focused workspace tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*WorkspaceViewModelTest*' :app:connectedDebugAndroidTest`

Expected: FAIL because workspace coordination is absent.

- [ ] **Step 3: Implement the coordinated workspace**

Keep the notebook alive when switching reference tabs, use a fixed 40/60 landscape split, and update recent state after each problem/page/tab change.

- [ ] **Step 4: Verify focused and navigation tests**

Run the Step 2 command plus Task 9's navigation test; expected all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: integrate problem workspace and progressive help"
```

## Task 11: Complete Settings and Device-Ergonomic Behavior

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/settings/{SettingsScreen.kt,SettingsViewModel.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/MainActivity.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/{NotebookPane.kt,PalmGuard.kt}`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/feature/settings/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: Task 4 `SettingsRepository`.
- Produces Settings UI for handedness/palm guard, default paper, brush, theme, keep-awake, backup/restore entry, attribution, app version, and content version.

- [ ] **Step 1: Write the failing settings test**

Assert settings survive restart; handedness moves the default guard; theme applies app-wide; keep-awake sets/clears the window flag only in a workspace; changed defaults affect only new pages; attribution lists MAA, Kedlaya, and the archive mirror.

- [ ] **Step 2: Run the settings test**

Run: `./gradlew :app:connectedDebugAndroidTest`

Expected: FAIL because the settings screen is incomplete.

- [ ] **Step 3: Implement settings and ergonomics**

Use accessible labels, minimum 48dp targets, and an adjustable palm guard that cannot cover the navigation toolbar.

- [ ] **Step 4: Verify settings and workspace tests**

Run Task 10 and Task 11 instrumented tests; expected all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: add workbook settings and palm guard controls"
```

## Task 12: Add Validated Local Backup and Restore

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/backup/{BackupManifest.kt,BackupArchive.kt,BackupCoordinator.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/settings/{SettingsScreen.kt,SettingsViewModel.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/backup/BackupArchiveTest.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/backup/BackupRestoreTest.kt`

**Interfaces:**
- Produces: `suspend BackupCoordinator.create(destination: OutputStream): BackupResult` and `suspend validateAndRestore(source: InputStream): RestoreResult`.
- Archive contains manifest version `1`, a logical JSON snapshot of the user database, notebook files, and SHA-256 for every member.
- Consumes: Task 4 repositories and Task 5 notebook root.

- [ ] **Step 1: Write failing round-trip and hostile-input tests**

Assert full state round-trip; tampered hashes fail; `../`/absolute ZIP members fail; duplicate members fail; uncompressed-size limit is enforced; schema version 2 fails cleanly; and every failure leaves the existing progress and notebook directory byte-for-byte unchanged.

- [ ] **Step 2: Run focused backup tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*BackupArchiveTest*' :app:connectedDebugAndroidTest`

Expected: FAIL because backup code is absent.

- [ ] **Step 3: Implement staged restore and Storage Access Framework flows**

Extract only into a temporary app-private directory, validate all files, import user rows transactionally, atomically swap notebook directories, and roll back both domains if any step fails.

- [ ] **Step 4: Verify focused tests**

Run the command from Step 2; expected all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: add verified local backup and restore"
```

## Task 13: Export a Problem Workbook to PDF

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/export/{WorkbookExportOptions.kt,WorkbookPdfExporter.kt,ExportCoordinator.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Test: `app/src/test/java/com/putnamworkbook/offline/export/WorkbookPdfLayoutTest.kt`
- Test: `app/src/androidTest/java/com/putnamworkbook/offline/export/WorkbookPdfExporterTest.kt`

**Interfaces:**
- Produces: `suspend WorkbookPdfExporter.export(key: ProblemKey, options: WorkbookExportOptions, outputStream: OutputStream): ExportResult`.
- `WorkbookExportOptions` includes `includeRevealedHints` and `includeRevealedSolution`; unrevealed help is never exported implicitly.
- Consumes: Task 3 content and Task 5 notebooks.

- [ ] **Step 1: Write failing PDF tests**

Assert the output opens with `PdfRenderer`; the first page identifies year/code and statement; notebook pages follow in order; blank/grid backgrounds match; ink coordinates scale without clipping; optional sections include only already revealed material; and a failed output stream leaves app data unchanged.

- [ ] **Step 2: Run focused export tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*WorkbookPdfLayoutTest*' :app:connectedDebugAndroidTest`

Expected: FAIL because export code is absent.

- [ ] **Step 3: Implement PDF export and document-picker destination**

Lay out the generated statement content at print width and draw notebook strokes directly onto `PdfDocument` canvas paths so ink stays vector-sharp.

- [ ] **Step 4: Verify PDF tests**

Run the command from Step 2; expected all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: export offline problem workbooks to PDF"
```

## Task 14: Acquire and Lock the Official 2025 Sources

**Files:**
- Create: `content-src/2025/sources.json`
- Create: `content-src/2025/original/{2025-problems.pdf,2025-solutions.pdf}`
- Create: `content-src/2025/README.md`
- Test: `tools/content/test/2025-sources.test.mjs`

**Interfaces:**
- Source URLs: `https://maa.org/wp-content/uploads/2026/02/2025-Putnam-Problems-for-Sessions-A-and-B.pdf` and `https://maa.org/wp-content/uploads/2026/02/2025OfficialSolutions.pdf`.
- Produces source metadata containing title, publisher, URL, retrieval date, byte length, page count, and SHA-256.

- [ ] **Step 1: Write the failing source-manifest test**

Assert both source IDs exist, files match byte length/SHA-256, the problem PDF has 2 pages, the solution PDF has 19 pages, and attribution names the Mathematical Association of America.

- [ ] **Step 2: Run the test before sources exist**

Run: `node --test tools/content/test/2025-sources.test.mjs`

Expected: FAIL because the manifest/PDFs are absent.

- [ ] **Step 3: Download, hash, inspect, and record the official files**

Use only the two exact MAA URLs above. Record the observed hashes rather than hard-coding unverified values in advance; document the personal/noncommercial restriction in the year README.

- [ ] **Step 4: Verify source integrity**

Run the test from Step 2; expected all assertions pass.

- [ ] **Step 5: Commit the locked personal-use source set**

```bash
git add content-src/2025 tools/content/test/2025-sources.test.mjs
git commit -m "content: lock official 2025 Putnam sources"
```

## Task 15: Author and Review 2025 Problems A1–A3

**Files:**
- Create: `content-src/2025/problems/{A1.json,A2.json,A3.json}`
- Create: `content-src/2025/review/{A1.md,A2.md,A3.md}`
- Create: `tools/content/test/2025-content.test.mjs`

**Interfaces:**
- Each record conforms to Task 2 and contains exactly four progressive hints, relevant prerequisite refreshers, a solution outline as hint 4, and a fully explained solution.

- [ ] **Step 1: Add failing presence and completeness assertions for A1–A3**

Assert codes/ordinals A1/1, A2/2, A3/3; four hints; nonempty prerequisites; all source page references; and completed statement, mathematical, and render review fields.

- [ ] **Step 2: Run the 2025 content test**

Run: `node --test tools/content/test/2025-content.test.mjs`

Expected: FAIL for missing A1–A3 records.

- [ ] **Step 3: Author records from the locked official PDFs**

Keep hint 1 non-spoiling, place necessary formulas/theorems in hint 3 and Prerequisites, make hint 4 an outline rather than a copied proof, and explain the motivation behind every major solution step.

- [ ] **Step 4: Validate and visually review**

Run: `npm --prefix tools/content run build -- --year 2025 --problems A1,A2,A3`.

Compare every statement/formula with the official source, inspect generated screenshots at the reference-pane width, and record checks in each review file. Expected: zero validation/render warnings.

- [ ] **Step 5: Commit**

```bash
git add content-src/2025 tools/content/test/2025-content.test.mjs
git commit -m "content: add reviewed 2025 A1 through A3"
```

## Task 16: Author and Review 2025 Problems A4–A6

**Files:**
- Create: `content-src/2025/problems/{A4.json,A5.json,A6.json}`
- Create: `content-src/2025/review/{A4.md,A5.md,A6.md}`
- Modify: `tools/content/test/2025-content.test.mjs`

**Interfaces:** Same record and review contract as Task 15.

- [ ] **Step 1: Add failing A4–A6 assertions**

Assert codes/ordinals 4–6, complete help fields, source pages, and review fields.

- [ ] **Step 2: Run the test and observe only A4–A6 failures**

Run: `node --test tools/content/test/2025-content.test.mjs`

- [ ] **Step 3: Author A4–A6 and their review records**

Follow Task 15's hint separation, prerequisite, motivation, and source-fidelity rules.

- [ ] **Step 4: Build and visually verify A4–A6**

Run: `npm --prefix tools/content run build -- --year 2025 --problems A4,A5,A6`; expected zero warnings and screenshots matching the source mathematics.

- [ ] **Step 5: Commit**

```bash
git add content-src/2025 tools/content/test/2025-content.test.mjs
git commit -m "content: add reviewed 2025 A4 through A6"
```

## Task 17: Author and Review 2025 Problems B1–B3

**Files:**
- Create: `content-src/2025/problems/{B1.json,B2.json,B3.json}`
- Create: `content-src/2025/review/{B1.md,B2.md,B3.md}`
- Modify: `tools/content/test/2025-content.test.mjs`

**Interfaces:** Same record and review contract as Task 15.

- [ ] **Step 1: Add failing B1–B3 assertions**

Assert codes B1–B3, ordinals 7–9, complete help fields, source pages, and review fields.

- [ ] **Step 2: Run the test and observe only B1–B3 failures**

Run: `node --test tools/content/test/2025-content.test.mjs`

- [ ] **Step 3: Author B1–B3 and their review records**

Follow Task 15's hint separation, prerequisite, motivation, and source-fidelity rules.

- [ ] **Step 4: Build and visually verify B1–B3**

Run: `npm --prefix tools/content run build -- --year 2025 --problems B1,B2,B3`; expected zero warnings and screenshots matching the source mathematics.

- [ ] **Step 5: Commit**

```bash
git add content-src/2025 tools/content/test/2025-content.test.mjs
git commit -m "content: add reviewed 2025 B1 through B3"
```

## Task 18: Author and Review 2025 Problems B4–B6

**Files:**
- Create: `content-src/2025/problems/{B4.json,B5.json,B6.json}`
- Create: `content-src/2025/review/{B4.md,B5.md,B6.md}`
- Modify: `tools/content/test/2025-content.test.mjs`

**Interfaces:** Same record and review contract as Task 15.

- [ ] **Step 1: Add failing B4–B6 and complete-year assertions**

Assert codes B4–B6, ordinals 10–12, and that the year contains exactly A1–A6 then B1–B6 with twelve original-PDF fallbacks.

- [ ] **Step 2: Run the test and observe only B4–B6 failures**

Run: `node --test tools/content/test/2025-content.test.mjs`

- [ ] **Step 3: Author B4–B6 and their review records**

Follow Task 15's hint separation, prerequisite, motivation, and source-fidelity rules.

- [ ] **Step 4: Build and visually verify the entire 2025 set**

Run: `npm --prefix tools/content run build -- --year 2025`.

Expected: 12 records, 48 hints, 12 explanations, 12 PDF fallbacks, zero raw-TeX/missing-glyph/overflow/broken-asset warnings, and a screenshot review index for all rendered sections.

- [ ] **Step 5: Commit**

```bash
git add content-src/2025 tools/content/test/2025-content.test.mjs app/src/main/assets
git commit -m "content: complete reviewed 2025 Putnam set"
```

## Task 19: Run Offline, Upgrade, and Release Acceptance

**Files:**
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt`
- Create: `scripts/{verify-release.ps1,device-smoke-test.md}`
- Create: `keystore.properties.example`
- Modify: `.gitignore`, `README.md`, `app/build.gradle.kts`
- Output, not committed: `dist/putnam-workbook-2025.apk`, `dist/putnam-workbook-2025.apk.sha256`

**Interfaces:**
- Produces a release APK signed with a retained private key and installable with `adb install -r`.
- Consumes every prior task.

- [ ] **Step 1: Write the failing end-to-end acceptance test**

Assert the 2025 browser has twelve ordered entries; A1 opens in a 40/60 landscape workspace; four hints and solution reveal in order; a stroke/page/status survives force-stop; Continue restores the same page; Original Paper opens; backup/restore round-trips; and exported PDF opens.

- [ ] **Step 2: Run the complete automated suite**

Run: `npm --prefix tools/content test && npm --prefix tools/content run build -- --year 2025 && ./gradlew test connectedDebugAndroidTest lint assembleDebug`

Expected: all Node, Python, JVM, instrumented, lint, and content-render checks pass with no missing assets or INTERNET permission. If the acceptance test exposes a gap, return to the owning task, add the smallest focused regression test there, and repeat this step.

- [ ] **Step 3: Configure stable release signing and version overrides**

Create one password-protected signing key outside Git tracking, populate untracked `keystore.properties`, and document where its backup is stored. Make `app/build.gradle.kts` accept `versionCodeOverride` while defaulting the checked-in release version to `2`.

- [ ] **Step 4: Build and install the signed upgrade baseline**

Run the content build with content version `2025-upgrade-baseline`, assemble with `-PversionCodeOverride=1`, install it, then create progress and at least two notebook pages on the target/emulated device.

- [ ] **Step 5: Build the final APK and rehearse a same-signature upgrade**

Rebuild content with version `2025.1`, assemble the default version-code-2 release, and install it with `adb install -r`. Assert progress/notebooks remain while immutable content reports `2025.1`; copy this APK to `dist/putnam-workbook-2025.apk` and generate `dist/putnam-workbook-2025.apk.sha256`.

- [ ] **Step 6: Perform target-device offline smoke testing**

On the SM-X230 in airplane mode, exercise finger, rubber-tip, and universal active-capacitive input when available; palm guard; 20 minutes of writing; background/restart; Home/Back; PDF fallback; backup/restore; and PDF export. Record results in `scripts/device-smoke-test.md`.

- [ ] **Step 7: Verify release artifact and commit release configuration**

Run: `powershell -ExecutionPolicy Bypass -File scripts/verify-release.ps1 dist/putnam-workbook-2025.apk`.

Expected: valid signature, application ID `com.putnamworkbook.offline`, min SDK 29, target SDK 36, no INTERNET permission, and SHA-256 match.

```bash
git add .gitignore README.md app scripts keystore.properties.example
git commit -m "release: verify 2025 offline workbook vertical slice"
```

## Deferred Follow-Up Plan

After this vertical slice passes Task 19 on the target tablet, write a separate archive-expansion plan for 1938–2024. That plan will reuse the proven content contract and app interfaces, add batch ingestion and PDF-only transcription workflows, handle historical numbering/layout exceptions, and produce the complete single-file personal APK required by the approved specification.
