# Reference UI and Content Schema v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize every Putnam source record under schema v2, remove runtime PDFs, and keep all hint/prerequisite navigation and offline mathematics correctly rendered.

**Architecture:** Desktop tooling validates one schema-v2 JSON record per problem and pre-renders every rich document through the same KaTeX template. The immutable Room content database stores generated asset references and hint stages but no PDF columns. Compose keeps fixed navigation outside a weighted WebView body and normalizes the deprecated persisted Original Paper tab to Problem.

**Tech Stack:** Node.js 22, JSON Schema 2020-12/Ajv, KaTeX 0.18, Playwright, Python 3/SQLite, Room 2.8, Jetpack Compose/WebView, JUnit 4 and Node test runner.

**Spec:** `docs/superpowers/specs/2026-10-01-notebook-editor-and-content-schema-v2-design.md`

## Global Constraints

- Content remains fully offline, script-free, and free of the Android INTERNET permission.
- Every problem has exactly four ordered stages: `nudge`, `strategy`, `knowledge`, `outline`.
- Statements, hints, prerequisites, and solutions use one rich-document schema and one renderer.
- Remote figures, path traversal, active HTML, and unsafe TeX commands remain invalid.
- Source PDFs remain development-only provenance files and are not packaged in assets or the content database.
- The user database and notebooks are not rebuilt with immutable content.
- A stored `ORIGINAL_PAPER` progress value restores to Problem without crashing.

## Review Focus

- TeX inside a later hint, prerequisite caption, or list item must receive the same validation/rendering as statement math (Tasks 1 and 2).
- A document with no math must not be falsely rejected by KaTeX-output checks (Task 2).
- Multiple prerequisites must all remain reachable; the UI must not silently show only the first (Task 5).
- An upgrade whose last tab was `ORIGINAL_PAPER` must open Problem and persist the normalized tab (Task 4).
- Missing CSS/font assets or a WebView renderer restart must not hide the fixed hint/prerequisite controls (Task 5).

---

### Task 1: Canonical Problem Schema v2 and Source Migration

**Files:**
- Modify: `tools/content/schema/problem.schema.json`
- Modify: `tools/content/src/{content-types.mjs,validate-problem.mjs}`
- Create: `tools/content/templates/problem.template.json`
- Create: `tools/content/SCHEMA.md`
- Modify: `tools/content/test/{fixtures/valid-problem.json,validate-problem.test.mjs,2025-content.test.mjs}`
- Modify: `content-src/2025/problems/{A1,A2,A3,A4,A5,A6,B1,B2,B3,B4,B5,B6}.json`

**Interfaces:**
- Produces: `SCHEMA_VERSION = 2` and a problem record without `originalPdf`.
- Produces: `HintStage = nudge|strategy|knowledge|outline`, fixed respectively to orders 1–4.
- Retains: `validateProblem(record): string[]` with exact JSON paths.

- [ ] **Step 1: Write failing validation tests** for schema version 2, missing/duplicate/mismatched hint stages, exactly four hints, absent `originalPdf`, rich math at every nested location, unsafe TeX, remote/escaping figures, and the canonical template.
- [ ] **Step 2: Run** `npm --prefix tools/content test`; expect v1 fixtures and missing stage validation to fail.
- [ ] **Step 3: Implement** schema/types/validation/docs/template, then mechanically migrate all twelve reviewed 2025 records by adding the fixed stages, changing `schemaVersion`, and removing `originalPdf` only.
- [ ] **Step 4: Re-run** `npm --prefix tools/content test`; expect all source records and negative fixtures to pass.
- [ ] **Step 5: Commit** with `git commit -m "content: standardize Putnam problem schema v2"`.

### Task 2: Deterministic Renderer and PDF-Free Year Build

**Files:**
- Modify: `tools/content/src/{build-year.mjs,render-document.mjs,render-review.mjs}`
- Modify: `tools/content/test/{build-year.test.mjs,render-document.test.mjs}`
- Create: `tools/content/test/generated-content.test.mjs`
- Modify generated: `app/src/main/assets/content/2025/manifest.json`
- Delete generated: `app/src/main/assets/content/2025/original/2025-problems.pdf`

**Interfaces:**
- Consumes: Task 1 schema-v2 records.
- Produces: schema-v2 year manifest containing statement, four staged hints, prerequisites, solution, citations, and referenced local assets only.
- Produces: generated-content audit mapping each source math run to KaTeX markup while permitting documents with zero math runs.

- [ ] **Step 1: Write failing build tests** asserting no original PDF is copied or referenced, every hint stage survives the manifest, all local figures are hashed, all source math becomes `.katex` markup, zero-math documents pass, and referenced CSS/fonts return existing files.
- [ ] **Step 2: Run** `npm --prefix tools/content test`; expect current PDF-copy and stage-dropping behavior to fail.
- [ ] **Step 3: Remove** PDF preparation from `buildProblem`, carry hint stages, centralize rich-document audit helpers, and keep Playwright overflow/missing-glyph review for every section.
- [ ] **Step 4: Run** `npm --prefix tools/content test` and `npm --prefix tools/content run build -- --year 2025 --content-version 2025.2`; expect 12 problems, 48 staged hints, no packaged PDF, and zero render warnings.
- [ ] **Step 5: Commit** with `git commit -m "content: build script-free schema v2 assets"`.

### Task 3: Content Database v2 and Runtime Models

**Files:**
- Modify: `tools/content/sql/content-schema.sql`
- Modify: `tools/content/src/build-content-db.py`
- Modify: `tools/content/test/{fixtures/content-manifest.json,test_build_content_db.py}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/content/{ContentDatabase.kt,ContentEntities.kt,ContentRepository.kt,RoomContentRepository.kt}`
- Create: `app/src/main/java/com/putnamworkbook/offline/domain/model/HintStage.kt`
- Replace generated: `app/src/main/assets/databases/content.db`
- Create generated: `app/schemas/com.putnamworkbook.offline.data.content.ContentDatabase/2.json`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/data/content/{ContentDatabaseTest.kt,ContentAssetUpgradeTest.kt}`
- Modify: `app/src/test/java/com/putnamworkbook/offline/{export/WorkbookPdfLayoutTest.kt,feature/workspace/WorkspaceViewModelTest.kt}`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/{navigation/ArchiveNavigationTest.kt,export/WorkbookPdfExporterTest.kt}`

**Interfaces:**
- Produces: `HintContent(order, stage: HintStage, title, assetPath, sha256)`.
- Produces: `ProblemContent` without `OriginalPdf` and with `hints: List<HintContent>`.
- Produces: Room content schema version 2 without `original_pdf_*` columns and with non-null `hint.stage`.

- [ ] **Step 1: Write failing Python and Android tests** for staged hints, absent PDF columns, schema/user version 2, deterministic rows, foreign-key enforcement, packaged-hash database replacement, and 2025.2 metadata.
- [ ] **Step 2: Run** `python tools/content/test/test_build_content_db.py` and the targeted Android content tests; expect schema/model failures.
- [ ] **Step 3: Implement** the SQL/builder/domain/Room changes, rebuild `content.db`, and export Room schema 2; do not modify `user.db`.
- [ ] **Step 4: Re-run** Python tests plus `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.putnamworkbook.offline.data.content`; expect all to pass.
- [ ] **Step 5: Commit** with `git commit -m "content: package schema v2 database"`.

### Task 4: Remove Original Paper Runtime with Progress Compatibility

**Files:**
- Delete: `app/src/main/java/com/putnamworkbook/offline/content/ui/{OriginalPdfPane.kt,OriginalPdfRepository.kt,OriginalPdfViewModel.kt}`
- Delete: `app/src/androidTest/java/com/putnamworkbook/offline/content/ui/OriginalPdfRepositoryTest.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/domain/model/ReferenceTab.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/{PutnamApp.kt,di/AppContainer.kt,navigation/PutnamNavHost.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/{ReferencePane.kt,WorkspaceViewModel.kt}`
- Modify: `app/src/test/java/com/putnamworkbook/offline/{feature/workspace/WorkspaceViewModelTest.kt,export/WorkbookPdfLayoutTest.kt}`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/{data/user/ProgressRepositoryTest.kt,navigation/ArchiveNavigationTest.kt,export/WorkbookPdfExporterTest.kt,acceptance/OfflineWorkbookAcceptanceTest.kt}`

**Interfaces:**
- Retains: deprecated storage enum value `ReferenceTab.ORIGINAL_PAPER` solely for user-database/backup decoding.
- Produces: `ReferenceTab.visibleEntries = listOf(PROBLEM, HELP, PREREQUISITES, SOLUTION)` and normalization of deprecated state to `PROBLEM` on open.

- [ ] **Step 1: Write failing tests** asserting only four visible tabs, no PDF dependencies/assets, legacy `ORIGINAL_PAPER` progress opens Problem, normalized progress is persisted, and backup restore still accepts the legacy string.
- [ ] **Step 2: Run** targeted workspace/progress/backup/navigation tests; expect current Original Paper UI and dependency assertions to fail.
- [ ] **Step 3: Delete** the PDF runtime and DI path, render only `visibleEntries`, normalize legacy state in `WorkspaceViewModel.open`, and update fakes/models without changing the user database schema.
- [ ] **Step 4: Re-run** the targeted tests and `./gradlew.bat :app:testDebugUnitTest`; expect all to pass.
- [ ] **Step 5: Commit** with `git commit -m "feat: remove original paper runtime"`.

### Task 5: Stable Hint and Prerequisite Navigation

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferenceDocumentBody.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/{ReferencePane.kt,HelpPane.kt,WorkspaceUiState.kt,WorkspaceViewModel.kt}`
- Modify: `app/src/main/java/com/putnamworkbook/offline/content/ui/MathContentWebView.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/{content/ui/LocalContentClientTest.kt,feature/workspace/WorkspaceScreenTest.kt,acceptance/OfflineWorkbookAcceptanceTest.kt}`

**Interfaces:**
- Produces: fixed test-tagged headers and a `ReferenceDocumentBody(assetPath, onIntegrityError, modifier)` constrained with `weight(1f)`.
- Produces: `activePrerequisiteIndex` and ordered prerequisite selector actions parallel to revealed hint selection.

- [ ] **Step 1: Write failing Compose/WebView tests** proving the header bottom never exceeds body top, all revealed hint buttons remain visible through asset switches/renderer recreation, each prerequisite is selectable, actual hint KaTeX CSS/font assets resolve, and integrity failures leave navigation visible.
- [ ] **Step 2: Run** the targeted instrumented tests; expect current full-height WebView and first-prerequisite-only behavior to fail.
- [ ] **Step 3: Implement** weighted body layout, stable keyed asset loading, fixed hint/prerequisite headers, and prerequisite selection state without enabling JavaScript or network access.
- [ ] **Step 4: Re-run** targeted tests and `./gradlew.bat :app:connectedDebugAndroidTest`; expect all reference-content tests to pass.
- [ ] **Step 5: Commit** with `git commit -m "fix: stabilize offline math reference panes"`.

### Task 6: Content Pipeline Regression Gate

**Files:**
- Modify: `README.md`
- Modify: `content-src/2025/README.md`
- Test/Generated review: `build/content-review/2025/`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: documented schema-v2 authoring/build commands and a clean 2025 review report.

- [ ] **Step 1: Run** `rg -n "schemaVersion.?1|2025\.1|originalPdf|assets/content/.*/original" README.md content-src/2025/README.md app/src/main/assets/content/2025/manifest.json`; expect stale current-workflow references before the documentation update.
- [ ] **Step 2: Update** current authoring/build documentation to schema v2 and content version `2025.2`; state that locked source PDFs are development-only while preserving their copyright/provenance notes.
- [ ] **Step 3: Run** `npm --prefix tools/content test`, `python tools/content/test/test_build_content_db.py`, the 2025 build/review, `./gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest`; expect all to pass with no PDF runtime asset.
- [ ] **Step 4: Commit** with `git commit -m "docs: document schema v2 content workflow"`.
