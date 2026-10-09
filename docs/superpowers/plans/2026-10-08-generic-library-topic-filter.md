# Generic Library and Topic Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Putnam-only identity and archive model with a generic offline library, migrate the reviewed 2025 set, and provide one-topic library-wide filtering without adding text search or imports.

**Architecture:** Schema-v3 source records point into a controlled library/topic catalog and compile into normalized immutable SQLite tables. A validated `collection/set/problem` key becomes the single identity across content, progress, notebooks, backup, navigation, and export. The existing archive view-model lifetime is retained, but its state is split into independent catalog, set, and topic queries so a single selected topic survives root/set navigation without stale responses.

**Tech Stack:** Node.js 22, JSON Schema/Ajv, KaTeX, Python 3/SQLite, Kotlin 2.4, Room 2.8, Jetpack Compose, coroutines, JUnit 4, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-10-08-library-topic-search-and-travel-release-design.md`

## Global Constraints

- This plan runs after `2026-10-08-notebook-polish.md` and preserves its commits.
- Canonical problem identity is exactly `collectionId/setId/problemId`; each validated segment matches `^[A-Za-z0-9._-]+$` and is neither `.` nor `..`.
- IDs are case-sensitive and may not be trimmed, normalized, inferred from titles, or silently lowercased.
- Schema v3 requires a nonempty unique topic list and at least one prerequisite.
- The first controlled topic catalog contains exactly the thirteen IDs and labels approved in the spec.
- Generic schema validation does not impose Putnam's A1–B6 or twelve-problem rules; `auditYear` owns those Putnam-specific checks.
- Topic filtering is single-choice and session-only. There is no text search, import, user taxonomy, or recommendation logic.
- The application remains offline and contains no Internet permission or runtime content fallback.
- Development data is disposable: user DB v1, notebook formats v1/v2, and backup v1 are not migrated.
- Tasks 1–2 are one atomic content-tool transition because the strict archive adapter consumes the changed set-builder contract; do not claim the full Node/Python suite is GREEN or commit between them.
- Tasks 3–5 are one atomic Android transition. Generic keys, repository contracts, persistence, routes, and archive UI deliberately change together; do not claim a GREEN whole-module compile or commit an intermediate Task 3/4 snapshot.
- Do not add `Co-Authored-By` trailers.

## Review Focus

- A leftover `(year, code)` in a route, binary header, progress row, backup snapshot, filename, or UI key would split one logical problem into incompatible identities (Tasks 1 and 4).
- Case-distinct valid keys such as `course/unit/P1` and `course/unit/p1` must not collide in backup duplicate detection (Task 4).
- Topic queries must return each problem once, keep collection/set context, and order by collection, set, ordinal—not global ordinal (Tasks 2, 3, and 5).
- Late set/topic responses and progress emissions must not overwrite a newer filter or list (Task 5).
- A malformed packaged DB or wrong shared-asset depth must produce a visible startup/integrity failure rather than crashing before the UI can report it (Tasks 2, 3, and 6).

---

### Task 1: Add generic IDs, library catalog, and source schema v3

**Files:**
- Create: `content-src/library.json`
- Create: `tools/content/schema/library-catalog.schema.json`
- Create: `tools/content/src/library-catalog.mjs`
- Create: `tools/content/test/library-catalog.test.mjs`
- Create: `tools/content/src/build-set.mjs`
- Create: `tools/content/test/build-set.test.mjs`
- Modify: `tools/content/schema/problem.schema.json`
- Modify: `tools/content/src/content-types.mjs`
- Modify: `tools/content/src/validate-problem.mjs`
- Modify: `tools/content/src/render-document.mjs`
- Modify: `tools/content/src/build-year.mjs`
- Modify: `tools/content/src/audit-year.mjs`
- Modify: `tools/content/src/review-year.mjs`
- Modify: `tools/content/templates/problem.template.json`
- Modify: `tools/content/templates/document.html`
- Modify: `tools/content/SCHEMA.md`
- Modify: `tools/content/test/validate-problem.test.mjs`
- Modify: `tools/content/test/build-year.test.mjs`
- Modify: `tools/content/test/audit-year.test.mjs`
- Modify: `tools/content/test/review-year.test.mjs`
- Modify: `tools/content/test/render-document.test.mjs`
- Modify: `tools/content/test/generated-content.test.mjs`
- Modify: `tools/content/test/2025-content.test.mjs`
- Modify: `tools/content/test/helpers/year-fixture.mjs`
- Modify: `tools/content/test/fixtures/valid-problem.json`
- Modify: `tools/content/test/fixtures/content-manifest.json`
- Modify: `content-src/2025/problems/A1.json` through `content-src/2025/problems/B6.json`
- Modify: `content-src/2025/review/A1.md` through `content-src/2025/review/B6.md`
- Modify: `content-src/2025/README.md`

**Interfaces:**
- Produces: `isSafeId(value): boolean`, `setKey(value): string`, and `problemKey(value): string` in content tooling.
- Produces: `validateLibraryCatalog(value): string[]` and `loadLibraryCatalog(path): Promise<LibraryCatalog>`.
- Changes: `validateProblem(record, catalog): string[]`; catalog context is mandatory.
- Produces: `buildSet(sourceDir, assetRoot, {catalog, collectionId, setId, contentVersion, problems?}): Promise<BuildManifestV3>`. `assetRoot` is a complete staging asset root; output is written at `content/{collectionId}/{setId}/...` and `content/shared/...`, and every manifest asset path is relative to the Android asset root.
- Produces `build-set.mjs` CLI flags `--source`, `--asset-root`, `--catalog`, `--collection`, `--set`, `--content-version`, and optional `--problems`; the manifest path is deterministically `content/{collection}/{set}/manifest.json`. A `--database` flag invokes the Python one-set builder only after render/hash validation.
- Keeps: `buildYear(sourceDir, assetRoot, {catalog, year, contentVersion, problems?})` as a thin Putnam wrapper around `buildSet`.
- Changes: `auditYear(sourceRoot, expectedYear, {catalog}): Promise<string[]>` and `reviewYear({year, sourceRoot, previewRoot, reviewRoot, catalog, problems}): Promise<{manifest, review}>`; their CLIs accept `--catalog` and default to the repository's `content-src/library.json`.
- Pins `content-src/library.json` to `{schemaVersion: 1, contentSchemaVersion: 3, collections, topics}`. Collection IDs and collection display orders are globally unique; set IDs and set display orders are unique within their collection; topic IDs and topic display orders are globally unique. A set has `id`, `title`, `displayOrder`, and optional typed `metadata.competitionYear`.
- Pins `BuildManifestV3` to schema/content version, collection/set IDs, a canonical-key problem array, an asset-root-relative asset array, catalog hash, and manifest hash. Document references use final asset-root-relative paths such as `content/putnam/2025/problems/A1/statement.html`.
- Changes `renderDocument(document, {sharedAssetPrefix, title})` so the template contains escaped placeholders rather than a Putnam title and fixed `../../shared` link.

- [ ] **Step 1: Write ID and catalog tests first**

  Accept canonical examples such as `putnam/2025/A1` and `course/chapter-1/P1`. Reject empty segments, `.`, `..`, whitespace, `/`, `\\`, `%`, URI punctuation, extra/fewer segments, trimming, duplicate collection/set/topic IDs, duplicate display orders, and the exact case-fold assumption. Assert parse/serialize round trips preserve case.

- [ ] **Step 2: Write schema-v3 RED tests**

  Require `collectionId`, `setId`, `problemId`, `label`, positive `ordinal`, nonempty unique `topics`, statement, four fixed hints, at least one prerequisite, solution, and sources. Reject v2, unknown catalog membership/topics, duplicate topics, zero prerequisites, and unsafe IDs. Assert generic IDs and ordinals are not restricted to Putnam formats.

- [ ] **Step 3: Run focused tests and verify RED**

  Run: `node --test tools/content/test/library-catalog.test.mjs tools/content/test/validate-problem.test.mjs tools/content/test/build-set.test.mjs`

  Expected: missing schema-v3/catalog/build interfaces fail.

- [ ] **Step 4: Implement content-tool keys and catalog loading**

  JS key helpers validate every segment and preserve case. Put the thirteen approved topics and Putnam sets 2020–2025 in the pinned catalog structure, with set display order newest-first and `metadata.competitionYear`.

- [ ] **Step 5: Implement schema v3 and generic set building**

  Keep rich-document and safe-TeX behavior unchanged. Putnam `buildYear` supplies `collectionId = "putnam"` and `setId = String(year)` and retains exact A1–B6 completeness auditing. Render final paths directly: HTML at `content/putnam/2025/problems/A1/*.html` links to shared assets with `../../../../shared/...`; do not apply brittle post-render string replacements. Calculate hashes after final placement.

- [ ] **Step 6: Migrate 2025 without changing mathematical content**

  Convert all twelve records to `putnam/2025/{A1..B6}`, add labels and reviewed topics, preserve every statement/hint/prerequisite/solution/source document byte-for-structure, and add topic-review evidence to each review note. Assert totals remain 12 problems, 48 hints, and 36 prerequisites.

- [ ] **Step 7: Run the focused schema/set gate**

  Run:

  `node --test tools/content/test/library-catalog.test.mjs tools/content/test/validate-problem.test.mjs tools/content/test/build-set.test.mjs tools/content/test/build-year.test.mjs tools/content/test/audit-year.test.mjs tools/content/test/review-year.test.mjs tools/content/test/render-document.test.mjs tools/content/test/generated-content.test.mjs tools/content/test/2025-content.test.mjs`

  Exclude the strict archive/DB suites until Task 2 migrates their consumers.

  Run: `node tools/content/src/audit-year.mjs --year 2025`

  Expected: focused PASS. The full content suite is intentionally deferred to Task 2.

- [ ] **Step 8: Inspect the tooling checkpoint without committing it**

  Run `git diff --check` across the exact Task 1 paths and continue directly to Task 2; `build-archive` is not yet migrated to the new contract.

### Task 2: Build normalized schema-v3 content and assets

**Files:**
- Modify: `tools/content/sql/content-schema.sql`
- Modify: `tools/content/src/build-content-db.py`
- Modify: `tools/content/src/build-set.mjs`
- Modify: `tools/content/src/build-year.mjs`
- Modify: `tools/content/src/build-archive.mjs`
- Modify: `tools/content/src/archive-catalog.mjs`
- Modify: `tools/content/schema/archive-catalog.schema.json`
- Modify: `content-src/archive.json`
- Modify: `tools/content/test/test_build_content_db.py`
- Modify: `tools/content/test/build-set.test.mjs`
- Modify: `tools/content/test/build-year.test.mjs`
- Modify: `tools/content/test/build-archive.test.mjs`
- Modify: `tools/content/test/archive-catalog.test.mjs`
- Modify: `tools/content/test/fixtures/content-manifest.json`

**Interfaces:**
- Produces SQLite tables `collection`, `problem_set`, `topic`, `problem`, `problem_topic`, `hint`, `prerequisite`, and `content_meta`.
- Produces: `build_content_database(manifest, catalog, output_path)`.
- Produces: `build_archive_database(manifests, catalog, archive_catalog, output_path)` for the strict future all-target archive path.
- Produces: `rooted_asset_path(collection_id, set_id, asset_path)` returning `content/{collectionId}/{setId}/{assetPath}`.
- Changes the retained full-archive adapter to `buildArchive({archiveCatalog, libraryCatalog, sourceRoot, outputRoot, reviewRoot})`; it still calls `assertFinalArchiveComplete` and is not a progressive release API.
- Pins `content_meta` keys `schema_version`, `content_version`, `build_kind`, `release_id`, `catalog_sha256`, `manifest_sha256`, `collection_count`, `set_count`, `topic_count`, `problem_count`, `problem_topic_count`, `hint_count`, and `prerequisite_count`. `release_id` is empty for set/archive builds.
- Defers: multi-set progressive `build_release_database` to the release plan.

- [ ] **Step 1: Write Python schema and builder tests first**

  Assert exact primary/foreign keys and named indices; collection/set/topic insertion; canonical key/segment agreement; one row per problem-topic pair; required hint stages/prerequisites; deterministic insertion; atomic failure; foreign-key/integrity checks; and rejection when a direct Python caller supplies unknown topics, malformed keys, mismatched sets, or duplicate ordinals.

- [ ] **Step 2: Write asset-layout tests first**

  Pin problem assets to `content/putnam/2025/problems/A1/...`, shared assets to `content/shared/...`, and generated HTML links at the exact `../../../../shared/...` depth. Assert missing fonts/styles/figures and stale final-placement hashes fail. Assert all thirteen controlled topics are inserted even if one has zero problem joins.

- [ ] **Step 3: Run focused tests and verify RED**

  Run: `python -m unittest discover -s tools/content/test -p 'test_build_content_db.py'`

  Run: `node --test tools/content/test/build-set.test.mjs tools/content/test/build-year.test.mjs tools/content/test/build-archive.test.mjs`

  Expected: existing v2 tables and year-only paths fail.

- [ ] **Step 4: Implement the normalized schema and builders**

  Use canonical string `problem.key` as the problem primary key and as every child-table FK. Parse `competitionYear` only from catalog metadata; never infer it from `setId`. Insert only installed collections/sets from the supplied manifest, but insert the entire thirteen-topic controlled catalog so zero-match filters remain visible. Require each problem's join set to equal its manifest topics exactly.

- [ ] **Step 5: Migrate the retained historical archive adapter to schema v3**

  Change `content-src/archive.json.contentSchemaVersion` and its schema/tests from 2 to 3 while preserving `contentVersion: "archive-1962-2025.1"`, target years 1962–2025, and `completedYears: [2025]`. Make the strict archive builder consume the library catalog and generic manifests/paths; keep its all-target completeness semantics intact. Do not add a release-selection escape hatch here.

- [ ] **Step 6: Run all content/Python tests**

  Run: `npm --prefix tools/content test`

  Run: `python -m unittest discover -s tools/content/test -p 'test_*.py'`

  Expected: PASS with deterministic logical database comparisons.

- [ ] **Step 7: Stage explicitly and commit**

  ```text
  git add content-src/library.json content-src/2025 tools/content/schema/problem.schema.json tools/content/schema/library-catalog.schema.json tools/content/src/library-catalog.mjs tools/content/src/content-types.mjs tools/content/src/validate-problem.mjs tools/content/src/render-document.mjs tools/content/templates/problem.template.json tools/content/templates/document.html tools/content/SCHEMA.md tools/content/test/library-catalog.test.mjs tools/content/test/validate-problem.test.mjs tools/content/test/audit-year.test.mjs tools/content/test/review-year.test.mjs tools/content/test/render-document.test.mjs tools/content/test/generated-content.test.mjs tools/content/test/2025-content.test.mjs tools/content/test/helpers/year-fixture.mjs tools/content/test/fixtures/valid-problem.json
  git add content-src/archive.json tools/content/sql/content-schema.sql tools/content/src/build-content-db.py tools/content/src/build-set.mjs tools/content/src/build-year.mjs tools/content/src/build-archive.mjs tools/content/src/archive-catalog.mjs tools/content/schema/archive-catalog.schema.json tools/content/test/test_build_content_db.py tools/content/test/build-set.test.mjs tools/content/test/build-year.test.mjs tools/content/test/build-archive.test.mjs tools/content/test/archive-catalog.test.mjs tools/content/test/fixtures/content-manifest.json
  git diff --cached --check
  git commit -m "feat: define generic math content library"
  ```

### Task 3: Expose the generic library through Room and safe startup

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/domain/model/ProblemSetKey.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/domain/model/ProblemKey.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/domain/model/ProblemKeyTest.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentIntegrityValidator.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentInitializer.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentEntities.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentDao.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentDatabase.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentRepository.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/content/RoomContentRepository.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/PutnamApp.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/data/content/ContentDatabaseTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/data/content/ContentAssetUpgradeTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/PutnamAppStartupTest.kt`
- Generate: `app/schemas/com.putnamworkbook.offline.data.content.ContentDatabase/3.json`
- Generate: `app/src/androidTest/assets/databases/content-fixture.db`

**Interfaces:**
- Produces: validated `ProblemSetKey(collectionId, setId)` and `ProblemKey(collectionId, setId, problemId)` with canonical serialization/parsing; every segment preserves case and matches the Global Constraints.
- Produces: `LibraryCollection(id, title, kind, displayOrder)`.
- Produces: `ProblemSetSummary(key, title, displayOrder, competitionYear)`.
- Produces: `TopicSummary(id, label, displayOrder)`.
- Changes: `ProblemSummary(key, label, ordinal, collectionTitle, setTitle, competitionYear)`.
- Produces repository methods `collections()`, `sets(collectionId)`, `topics()`, `problems(setKey)`, `problemsByTopic(topicId)`, and `problem(key)`.
- Produces: `suspend fun AppContainer.initializeContent(): ContentRepository`.
- Produces injectable `fun interface ContentInitializer { suspend fun initialize(): ContentRepository }` and test-tagged `content-startup-loading` / `content-startup-error` surfaces.

- [ ] **Step 1: Write Room/repository tests first**

  First write canonical key constructor/parser tests. Then use at least two collections, a nonnumeric set, repeated `A1` labels, a multi-topic problem, all thirteen topics, and an unreferenced topic. Assert exact ordering by collection display order/ID, set display order/ID, problem ordinal/ID; no duplicate topic rows; exact manifest join equality; complete content loading; pinned `content_meta` count/hash validation; and semantic corruption rejection even if SQLite foreign keys were disabled during fixture creation.

- [ ] **Step 2: Write startup failure tests first**

  In `PutnamAppStartupTest`, inject blocking, successful, malformed-DB, missing-DB, and throwing initializers. Assert the loading/error/ready surfaces, a concise error instead of composition failure, and DB open/integrity work on `Dispatchers.IO`. Make the ready path pass the already initialized repository into navigation and export coordinator construction; neither may touch a lazy `container.contentRepository` and reopen the database.

- [ ] **Step 3: Run key tests and record the expected atomic-transition RED state**

  Run: `./gradlew.bat :app:testDebugUnitTest --tests '*ProblemKeyTest*'`

  Expected: missing generic key types fail. After the repository contract changes, whole-module compilation is intentionally deferred until Tasks 4–5 migrate every consumer and fake; document this as an atomic transition, not a passing checkpoint.

- [ ] **Step 4: Implement Room schema 3 and projections**

  First implement validated `ProblemSetKey`/`ProblemKey` constructors and exact canonical parsing. Then keep SQL and Room PKs, indices, FKs, names, and nullability identical. Use an `EXISTS` topic predicate or protected one-topic join so each problem appears once. Keep immutable packaged-hash replacement behavior.

- [ ] **Step 5: Initialize content before dependent UI**

  `PutnamApp` represents Loading, Ready, and Error states. Only Ready creates `PutnamNavHost` and export services. Error keeps Settings-independent recovery text visible and never performs a network fallback.

- [ ] **Step 6: Generate Room schema/fixtures without claiming the module is GREEN**

  Use the Python schema-v3 builder to generate `content-fixture.db` and compare its SQL schema to the Room entity/index/FK contract. Full KSP/compile/instrumentation verification occurs in Task 5 after every repository caller and fake is migrated.

- [ ] **Step 7: Inspect but do not commit the Android foundation**

  Run `git diff --check` over the exact Task 3 paths. Continue directly to Task 4; the staged snapshot is not yet buildable by design.

### Task 4: Replace persisted year/code identities

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/user/UserEntities.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/user/UserDao.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/user/UserDatabase.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/user/ProgressRepository.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/user/RoomProgressRepository.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/model/NotebookDocument.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/NotebookRepository.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/NotebookManifestCodec.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepository.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/backup/BackupCoordinator.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/backup/BackupArchive.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/backup/BackupManifest.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/AppRoute.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceUiState.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/export/ExportCoordinator.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/export/WorkbookPdfExporter.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/storage/NotebookManifestCodecTest.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepositoryTest.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/backup/BackupArchiveTest.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModelTest.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/export/WorkbookPdfLayoutTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/backup/BackupRestoreTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/data/user/ProgressRepositoryTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/data/user/UserDatabaseSchemaTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/export/WorkbookPdfExporterTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreenTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookPaneTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt`
- Generate: `app/schemas/com.putnamworkbook.offline.data.user.UserDatabase/2.json`

**Interfaces:**
- Changes progress PK to `problem_key: String`; DAO queries take canonical strings.
- Changes `NotebookDocument.SCHEMA_VERSION` and notebook codec format to version 3 with one `writeUTF(key.canonical)` field; removes all legacy decode branches and `FileNotebookRepository.migrateIfNeeded`, and rejects versions 1/2.
- Changes notebook path to `notebooks/{collectionId}/{setId}/{problemId}/` after normalized containment checks.
- Changes user snapshot and outer backup archive to schema version 2 using `problemKey`.
- Produces routes `SetProblems(ProblemSetKey)` and `Workspace(ProblemKey)` with individually URI-encoded string segments.
- Produces injective export filename `{collection}%2F{set}%2F{problem}-workbook.pdf`.

- [ ] **Step 1: Migrate tests to generic keys before production code**

  Replace test keys with `ProblemKey("putnam", "2025", "A1")`, then add a non-Putnam `ProblemKey("course", "chapter-1", "P1")`. Assert progress, recent work, notebook documents, workspace, export headings, ViewModel store keys, remember keys, and routes preserve all three segments. Give metadata titles/labels values different from their IDs so tests detect ID-derived display text.

- [ ] **Step 2: Add clean-break persistence tests**

  Assert fresh user DB schema 2, notebook v3 round trip, explicit rejection of old notebook/user/backup formats, exact case-sensitive duplicate keys, path containment, and backup round-trip containing both `course/unit/P1` and `course/unit/p1` without case folding.

- [ ] **Step 3: Run focused tests and verify RED**

  Run: `./gradlew.bat :app:testDebugUnitTest --tests '*NotebookManifestCodecTest*' --tests '*FileNotebookRepositoryTest*' --tests '*BackupArchiveTest*' --tests '*WorkspaceViewModelTest*' --tests '*WorkbookPdfLayoutTest*' :app:compileDebugAndroidTestKotlin`

  Expected: year/code interfaces fail.

- [ ] **Step 4: Implement canonical persistence and routes**

  Remove every persisted year/code field. Do not add a Room migration or notebook/backup converter. Delivery remains clean-install. Keep traversal/ZIP-bomb protections while making duplicate-member comparison case-sensitive.

- [ ] **Step 5: Remove hardcoded Putnam/year display assumptions**

  Workspace and PDF titles use `ProblemSummary` collection/set/label metadata. `HomeViewModel` resolves the recent canonical key through the already initialized `ContentRepository`; Continue uses returned set/problem labels rather than parsing IDs. Export filenames use the injective `%2F` separator because `%` is invalid inside an ID.

- [ ] **Step 6: Inspect the identity checkpoint**

  Run `git diff --check` over the exact Task 4 files and inspect for any persisted `year`/`code`, v1/v2 notebook codec branch, case-folded archive member, or Putnam-derived heading. Do not compile or commit until Task 5 migrates archive/navigation fakes and consumers.

### Task 5: Add the single-topic library browser

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/archive/TopicFilterRow.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/feature/archive/ArchiveViewModelTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/feature/archive/LibraryBrowserTest.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/archive/ArchiveViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/archive/ArchiveScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/archive/ProblemListScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/navigation/ArchiveNavigationTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt`

**Interfaces:**
- Produces `ArchiveUiState(collections, setsByCollection, topics, selectedTopicId, topicProblems, selectedSet, setProblems, isLoadingCatalog, isLoadingTopicProblems, isLoadingSetProblems, catalogError, topicError, setError, progressError)` with independent source lists and errors.
- Produces: `selectTopic(topicId: String?)`, `selectSet(key: ProblemSetKey)`, `retryCatalog()`, `retryTopic()`, `retrySet()`, `retryProgress()`, and `close()`.
- Produces test tags `topic-all`, `topic-{id}`, `library-collection-{id}`, `library-set-{canonical}`, and `library-problem-{canonical}`.

- [ ] **Step 1: Write ViewModel race/error tests first**

  Cover initial catalog/topics, All, selecting one topic, tapping it again to return to All, unknown topic fallback, no matches, two collections, repeated labels, multi-topic membership, progress updates to both cached lists, progress-flow failure/retry, rapid topic changes, out-of-order repositories that ignore cancellation, independent set loads, and retry after each failure class. After switching to All, assert a late successful or failed topic request cannot repopulate data or errors.

- [ ] **Step 2: Write Compose/navigation tests first**

  Assert the chip row remains usable during body loading/error/empty states; All shows collections and sets; a topic shows grouped global results; nonnumeric sets navigate; set Back returns with the selected chip intact; full canonical test keys prevent repeated `A1` collisions; and a row opens the correct workspace.

- [ ] **Step 3: Run focused tests and verify RED**

  Run: `./gradlew.bat :app:testDebugUnitTest --tests '*ArchiveViewModelTest*' :app:compileDebugAndroidTestKotlin`

  Expected: missing state/actions/UI fail.

- [ ] **Step 4: Implement independent query state**

  Retain one `ArchiveViewModel` at NavHost scope. Use distinct jobs/generation counters and separate source summaries for catalog, selected-topic results, selected-set results, and progress. Switching to All, catalog fallback, retry, and `close()` each cancel and invalidate the appropriate generation before clearing state. Rethrow `CancellationException`, reject late successes and errors even when fakes ignore cancellation, catch progress-flow failures as visible `progressError`, and republish both result lists from successful progress emissions.

- [ ] **Step 5: Implement the simple topic UI**

  Put a horizontally scrollable single-choice row above the body. `null` means All. Group topic results by ordered collection/set metadata. Show exactly `No installed problems match this topic` for a valid empty topic. Do not add a keyboard or text field.

- [ ] **Step 6: Run focused and full Android local gates**

  Run: `./gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug`

  Expected: PASS.

- [ ] **Step 7: Run startup/database instrumentation when available**

  Run `PutnamAppStartupTest`, `ContentDatabaseTest`, `ContentAssetUpgradeTest`, `LibraryBrowserTest`, and `ArchiveNavigationTest` on an attached target. Expected: PASS. If no target is attached, require Android-test compilation now and carry execution as an explicit travel-release gate.

- [ ] **Step 8: Stage the atomic Android transition explicitly**

  Stage only the exact Task 3–5 production/test/schema files; do not use `git add app/src/main`, `app/src/test`, or `app/src/androidTest`. Use explicit commands such as:

  ```text
  git add -- app/src/main/java/com/putnamworkbook/offline/domain/model/ProblemKey.kt app/src/main/java/com/putnamworkbook/offline/domain/model/ProblemSetKey.kt app/src/main/java/com/putnamworkbook/offline/data/content/ContentEntities.kt app/src/main/java/com/putnamworkbook/offline/data/content/ContentDao.kt app/src/main/java/com/putnamworkbook/offline/data/content/ContentDatabase.kt app/src/main/java/com/putnamworkbook/offline/data/content/ContentRepository.kt app/src/main/java/com/putnamworkbook/offline/data/content/RoomContentRepository.kt app/src/main/java/com/putnamworkbook/offline/data/content/ContentIntegrityValidator.kt app/src/main/java/com/putnamworkbook/offline/data/content/ContentInitializer.kt app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt app/src/main/java/com/putnamworkbook/offline/PutnamApp.kt
  git add -- app/src/main/java/com/putnamworkbook/offline/data/user/UserEntities.kt app/src/main/java/com/putnamworkbook/offline/data/user/UserDao.kt app/src/main/java/com/putnamworkbook/offline/data/user/UserDatabase.kt app/src/main/java/com/putnamworkbook/offline/data/user/ProgressRepository.kt app/src/main/java/com/putnamworkbook/offline/data/user/RoomProgressRepository.kt app/src/main/java/com/putnamworkbook/offline/notebook/model/NotebookDocument.kt app/src/main/java/com/putnamworkbook/offline/notebook/storage/NotebookRepository.kt app/src/main/java/com/putnamworkbook/offline/notebook/storage/NotebookManifestCodec.kt app/src/main/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepository.kt app/src/main/java/com/putnamworkbook/offline/notebook/ui/NotebookViewModel.kt
  git add -- app/src/main/java/com/putnamworkbook/offline/backup/BackupCoordinator.kt app/src/main/java/com/putnamworkbook/offline/backup/BackupArchive.kt app/src/main/java/com/putnamworkbook/offline/backup/BackupManifest.kt app/src/main/java/com/putnamworkbook/offline/navigation/AppRoute.kt app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt app/src/main/java/com/putnamworkbook/offline/feature/home/HomeViewModel.kt app/src/main/java/com/putnamworkbook/offline/feature/home/HomeScreen.kt app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModel.kt app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceUiState.kt app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreen.kt app/src/main/java/com/putnamworkbook/offline/export/ExportCoordinator.kt app/src/main/java/com/putnamworkbook/offline/export/WorkbookPdfExporter.kt
  git add -- app/src/main/java/com/putnamworkbook/offline/feature/archive/ArchiveViewModel.kt app/src/main/java/com/putnamworkbook/offline/feature/archive/ArchiveScreen.kt app/src/main/java/com/putnamworkbook/offline/feature/archive/ProblemListScreen.kt app/src/main/java/com/putnamworkbook/offline/feature/archive/TopicFilterRow.kt
  git add -- app/src/test/java/com/putnamworkbook/offline/domain/model/ProblemKeyTest.kt app/src/test/java/com/putnamworkbook/offline/notebook/storage/NotebookManifestCodecTest.kt app/src/test/java/com/putnamworkbook/offline/notebook/storage/FileNotebookRepositoryTest.kt app/src/test/java/com/putnamworkbook/offline/backup/BackupArchiveTest.kt app/src/test/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModelTest.kt app/src/test/java/com/putnamworkbook/offline/export/WorkbookPdfLayoutTest.kt app/src/test/java/com/putnamworkbook/offline/feature/archive/ArchiveViewModelTest.kt
  git add -- app/src/androidTest/java/com/putnamworkbook/offline/PutnamAppStartupTest.kt app/src/androidTest/java/com/putnamworkbook/offline/data/content/ContentDatabaseTest.kt app/src/androidTest/java/com/putnamworkbook/offline/data/content/ContentAssetUpgradeTest.kt app/src/androidTest/java/com/putnamworkbook/offline/backup/BackupRestoreTest.kt app/src/androidTest/java/com/putnamworkbook/offline/data/user/ProgressRepositoryTest.kt app/src/androidTest/java/com/putnamworkbook/offline/data/user/UserDatabaseSchemaTest.kt app/src/androidTest/java/com/putnamworkbook/offline/export/WorkbookPdfExporterTest.kt app/src/androidTest/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreenTest.kt app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookPaneTest.kt app/src/androidTest/java/com/putnamworkbook/offline/notebook/ui/NotebookSelectionTest.kt app/src/androidTest/java/com/putnamworkbook/offline/feature/archive/LibraryBrowserTest.kt app/src/androidTest/java/com/putnamworkbook/offline/navigation/ArchiveNavigationTest.kt app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt
  git add -- app/src/androidTest/assets/databases/content-fixture.db app/schemas/com.putnamworkbook.offline.data.content.ContentDatabase/3.json app/schemas/com.putnamworkbook.offline.data.user.UserDatabase/2.json
  ```

  Run `git diff --cached --check`, inspect the complete cached diff and stat, and verify no Task 1/2 content-source/tooling file or production asset is included.

- [ ] **Step 9: Commit**

  ```text
  git commit -m "feat: add generic offline math library"
  ```

- [ ] **Step 10: Verify the committed Android boundary**

  Run the full Task 5 local gate from the committed snapshot and confirm there are no remaining identity/repository/archive Android changes before Task 6.

### Task 6: Prove a staged schema-v3 2025 vertical slice

**Files:**
- Modify: `tools/content/test/generated-content.test.mjs`
- Modify: `tools/content/test/2025-content.test.mjs`
- Generate ignored staging output: `build/schema-v3-2025/app-assets/content/putnam/2025/**`
- Generate ignored staging output: `build/schema-v3-2025/app-assets/content/shared/**`
- Generate ignored staging output: `build/schema-v3-2025/app-assets/databases/content.db`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: a disposable one-set schema-v3 bundle for source-to-database validation. Production `app/src/main/assets` remains untouched until the final six-set release plan publishes it once.

- [ ] **Step 1: Build 2025 into a temporary staging root**

  Run:

  `node tools/content/src/build-set.mjs --source content-src/2025 --asset-root build/schema-v3-2025/app-assets --catalog content-src/library.json --collection putnam --set 2025 --content-version putnam-2025.dev1 --database build/schema-v3-2025/app-assets/databases/content.db`

  The CLI calls `build_content_database` after rendering and writes no production asset.

- [ ] **Step 2: Audit the staged bundle**

  Verify schema 3, one collection, one set, all thirteen topic rows, 12 problems, 48 hints, 36 prerequisites, exact problem-topic joins, manifest/DB/hash agreement, valid local dependencies, and no PDF/TeX originals. Confirm final shared links use `../../../../shared/...` from a problem document.

- [ ] **Step 3: Run all tooling and Android local checks**

  Run: `npm --prefix tools/content test`

  Run: `python -m unittest discover -s tools/content/test -p 'test_*.py'`

  Run: `./gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug`

  Expected: PASS; staged content metadata reports the exact Task 2 counts. The old production main asset is allowed only to trigger the new visible startup error until the final release publication; tests use injected repositories/schema-v3 fixtures and must not claim it is final content.

- [ ] **Step 4: Run available instrumentation**

  If a target is attached, run content-database tests against the schema-v3 fixture and UI tests with injected repositories. Do not run or claim a packaged-production-content smoke test until the final release plan. Record unavailable device coverage rather than claiming it passed.

- [ ] **Step 5: Commit only durable staging tests**

  ```text
  git add tools/content/test/generated-content.test.mjs tools/content/test/2025-content.test.mjs
  git commit -m "test: prove schema v3 Putnam 2025 slice"
  ```

- [ ] **Step 6: Stop before release packaging**

  Do not copy staging into `app/src/main/assets`, delete/replace the old production baseline, bump the app version, assemble an APK, copy anything to `dist/`, or run signing until the 2020–2025 curation and travel-release plans are complete.
