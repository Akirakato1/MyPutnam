# Android Problem Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let MyPutnam refresh a GitHub-hosted catalog, manually install/update/delete/restore immutable problem packages, and browse all cataloged problems while preserving offline use and every notebook/progress record.

**Architecture:** Add a last-known-good catalog cache, a separate Room installed-content index, an app-private package store, and a persistent explicit-operation queue. A composite problem repository resolves logical document references through the active verified package. The existing user database, notebook repository, and Free Notebook storage remain independent and unchanged.

**Tech Stack:** Kotlin/Java 17, Android SDK 29+, Jetpack Compose, Room 2.8.5, coroutines/Flow, `HttpURLConnection` behind an injectable transport, Android WebKit asset loading, JUnit 4 and Compose/instrumented tests.

**Spec:** `docs/superpowers/specs/2026-10-18-github-content-library-and-math-reference-design.md`

## Global Constraints

- Depends on the completed content-package/catalog foundation plan and its exact catalog/package fixtures.
- Preserve `UserDatabase`, all `ProblemProgressEntity` rows, `FileNotebookRepository`, and Free Notebook storage formats.
- Store replaceable content only below app-private `files/content-library/` and `library-index.db`.
- Startup refresh is non-blocking and metadata-only; it never installs or updates packages.
- Actual package mutation requires explicit user action. Do not add WorkManager or implicit background downloads in v1.
- A downloaded revision wins over bundled; logical removal suppresses bundled fallback; failed updates keep the old active revision.
- Deleting content never deletes notebooks, progress, hint state, completion state, or recency.
- Backups contain desired library metadata, never ZIP/HTML bytes.
- Use only the compiled HTTPS GitHub Pages origin. Disable redirects, cleartext, WebView networking, JavaScript, file/content access, and unsafe navigation.
- Do not create or replace a distributable APK under `dist/`; final packaging belongs to the release-integration plan.

## Review Focus

- Process death, cancellation, hash failure, and out-of-storage must never turn staging leftovers into installed content.
- Index activation and filesystem activation must have an explicit recoverable ordering.
- Removing a downloaded override of a bundled item must not unexpectedly reveal the old bundled version.
- Long-lived Home/browse screens must update after install/delete without rebuilding the NavHost.
- Missing recent content must route to restoration, not crash the workspace.
- Package/index code must be unable to address notebook or user-database paths.

---

### Task 1: Parse, cache, and refresh the full catalog without blocking Home

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/data/json/JsonValue.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/json/StrictJsonCodec.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/backup/BackupManifest.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/catalog/CatalogModels.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/catalog/CatalogCodec.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/catalog/CatalogStore.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/catalog/FileCatalogStore.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/catalog/CatalogClient.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/catalog/HttpsCatalogClient.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/catalog/CatalogOriginPolicy.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/catalog/CatalogRefreshCoordinator.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/storage/AtomicBytesStore.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/putnamworkbook/offline/PutnamApp.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/data/library/catalog/CatalogCodecTest.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/data/library/catalog/FileCatalogStoreTest.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/data/library/catalog/CatalogRefreshCoordinatorTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/data/library/catalog/HttpsCatalogClientTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/PutnamAppStartupTest.kt`

**Interfaces:**
- `data class CatalogValidators(val etag: String?, val lastModified: String?)`.
- `sealed interface CatalogFetchResult { data class Modified(val bytes: ByteArray, val validators: CatalogValidators); data object NotModified; data class Failed(val message: String) }`.
- `fun interface CatalogTransport { suspend fun execute(request: CatalogHttpRequest): CatalogHttpResponse }`; production uses bounded `HttpURLConnection`, tests use an in-memory fake/local server.
- `interface CatalogClient { suspend fun fetch(validators: CatalogValidators?): CatalogFetchResult }`.
- `interface CatalogStore { val state: StateFlow<CatalogState>; suspend fun initialize(); suspend fun commitRemote(bytes: ByteArray, validators: CatalogValidators, refreshedAt: Long); suspend fun recordFailure(message: String, attemptedAt: Long) }`.
- `CatalogOriginPolicy` accepts only `https://akirakato1.github.io/MyPutnam/` and catalog-relative package paths under `content/v1/`.

- [ ] **Step 1: Write failing strict-codec and catalog-model tests**

Port the private backup JSON parser into the shared `data/json` package without behavior changes, then test exact catalog fields, unknown fields, duplicate keys/orders, unsafe paths, schema incompatibility, and bounded numeric/string/array sizes using foundation fixtures.

- [ ] **Step 2: Write failing last-known-good store tests**

Cover embedded-only startup, valid cached override, invalid/truncated cache fallback, atomic commit, stale temporary cleanup, refresh timestamps/errors, and preservation of prior bytes after a failed replacement.

- [ ] **Step 3: Write failing HTTP/origin/refresh tests**

Cover 200, 304, ETag, Last-Modified, finite connect/read timeouts, cancellation, maximum catalog bytes, HTTP rejection, absolute/cross-origin URL rejection, redirects disabled, and a coordinator assertion that no package installer is invoked.

- [ ] **Step 4: Run focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.data.library.catalog.*" --no-daemon
```

- [ ] **Step 5: Implement local-first initialization and non-blocking refresh**

Render the app after `CatalogStore.initialize()` reads only local bytes. Launch one application-scope refresh afterward; commit only a fully decoded/validated catalog. Add `INTERNET` and `ACCESS_NETWORK_STATE`, explicitly set `android:usesCleartextTraffic="false"`, and do no work on the main dispatcher.

- [ ] **Step 6: Run tests and compile instrumentation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.data.library.catalog.*" :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: tests pass and Home startup coverage proves a blocked/failed refresh cannot delay local UI.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/java/com/putnamworkbook/offline/data/json app/src/main/java/com/putnamworkbook/offline/data/library app/src/main/java/com/putnamworkbook/offline/backup/BackupManifest.kt app/src/main/java/com/putnamworkbook/offline/PutnamApp.kt app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt app/src/test/java/com/putnamworkbook/offline/data/library/catalog app/src/androidTest/java/com/putnamworkbook/offline/data/library/catalog app/src/androidTest/java/com/putnamworkbook/offline/PutnamAppStartupTest.kt
git commit -m "feat: cache and refresh content catalog"
```

### Task 2: Add the separate installed-content index and recovery state

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/index/LibraryIndexEntities.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/index/LibraryIndexDao.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/index/LibraryIndexDatabase.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/index/InstalledContentIndex.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/index/RoomInstalledContentIndex.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/index/InstalledContentModels.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/data/library/index/InstalledContentResolutionTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/data/library/index/LibraryIndexDatabaseTest.kt`
- Generate: `app/schemas/com.putnamworkbook.offline.data.library.index.LibraryIndexDatabase/1.json`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`

**Interfaces:**
- `interface InstalledContentIndex { fun observe(): Flow<InstalledContentSnapshot>; suspend fun reconcileBundled(catalog: PublicationCatalog); suspend fun activateDownloaded(entry: InstalledPackage); suspend fun logicallyRemove(key: String); suspend fun restoreBundled(key: String); suspend fun quarantine(key: String, digest: String, error: InstallError); suspend fun recordFailure(key: String, error: InstallError); suspend fun exportBackupState(): LibraryBackupState; suspend fun applyBackupState(state: LibraryBackupState): ReinstallPlan }`.
- Content rows retain the last-known catalog entry metadata, bundled descriptor, downloaded descriptor/location, logical-removal flag, desired-installed flag, active digest/source, quarantined digest/error, last operation error, and catalog-listed state. This snapshot is sufficient to browse/open an installed item after a later catalog stops listing it.
- Operation rows retain stable ID, action, content key, state, bytes completed/total, attempt count, and safe failure text.

- [ ] **Step 1: Write failing precedence and lifecycle tests**

Cover downloaded-over-bundled precedence, bundled default, logical removal, offline bundled restore, downloaded-only delete, downloaded-override delete suppressing bundled fallback, quarantining an active digest without falling back to another revision, removed catalog entry retention with its last-known label/topics/collection data, and unsupported-min-app state.

- [ ] **Step 2: Write failing operation recovery tests**

Persist operations in `QUEUED`, `DOWNLOADING`, `VERIFYING`, `ACTIVATING`, `FAILED`, and `COMPLETE`; assert interrupted nonterminal work becomes retryable after startup and is never inferred installed from an operation row.

- [ ] **Step 3: Prove database separation**

Add an instrumented test that snapshots `UserDatabase` schema/data and notebook root, mutates the library index, then proves both are byte/logically unchanged.

- [ ] **Step 4: Run tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.data.library.index.*" :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 5: Implement Room schema and transactional resolution**

Use `library-index.db`, its own Room class/schema export, foreign-key-safe operation records, and one transaction for every state transition. Expose a monotonically increasing snapshot revision so repositories/view models can reload.

- [ ] **Step 6: Run tests and verify GREEN**

Run the command from Step 4.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/data/library/index app/src/test/java/com/putnamworkbook/offline/data/library/index app/src/androidTest/java/com/putnamworkbook/offline/data/library/index app/schemas app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt
git commit -m "feat: track installed content separately"
```

### Task 3: Verify, stage, activate, and queue immutable packages

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/packages/PackageStore.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/packages/FilePackageStore.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/packages/PackageDownloader.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/packages/PackageEnvelopeCodec.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/packages/PackageArchiveValidator.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/packages/ZipCentralDirectoryValidator.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/packages/HtmlFragmentAuditor.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/packages/PackageInstaller.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/packages/QueuedPackageInstaller.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/data/library/packages/PackageArchiveValidatorTest.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/data/library/packages/HtmlFragmentAuditorTest.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/data/library/packages/QueuedPackageInstallerTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/data/library/packages/FilePackageStoreTest.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`

**Interfaces:**
- `interface PackageStore { suspend fun recoverInterruptedState(); suspend fun stage(descriptor: PackageDescriptor, input: InputStream): StagedPackage; suspend fun activate(staged: StagedPackage): PackageLocation; suspend fun ensureBundledExtracted(descriptor: PackageDescriptor): PackageLocation; suspend fun delete(location: PackageLocation) }`.
- `interface PackageInstaller { fun observeOperations(): Flow<List<PackageOperation>>; suspend fun enqueue(keys: Set<String>, action: PackageAction); suspend fun retry(operationId: String); suspend fun cancel(operationId: String) }`.
- Immutable active roots are `files/content-library/packages/<sha256>/`; unique staging/tombstone siblings are never addressable by the resolver.

- [ ] **Step 1: Port foundation package fixtures and write failing archive tests**

Cover outer size/hash, key/revision/schema/min-app agreement, traversal, absolute/backslash/drive/empty/dot paths, case collisions, duplicate/undeclared members, symlink/special central-directory modes, all size/count limits, inner digest/size mismatch, malformed ZIP, and unsafe fragment markup/URLs.

- [ ] **Step 2: Write failing atomic store tests**

Cover successful downloaded and bundled lazy extraction, same-digest idempotence, interrupted stage cleanup, failed atomic activation, out-of-space cleanup, tombstone recovery, and containment beneath the content root.

- [ ] **Step 3: Write failing queue/batch tests**

Cover install/update/delete/restore/retry/cancel, explicit-only download, repeated idempotent operations, persisted progress, failed update retaining old active content, verified reinstall clearing only the matching quarantine, storage-specific failure reporting, and one failed batch item not rolling back successful siblings.

- [ ] **Step 4: Run focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.data.library.packages.*" --no-daemon
```

- [ ] **Step 5: Implement verification before filesystem/index activation**

Stream downloads to bounded staging, compute the outer digest, inspect the central directory before extraction, validate `package.json`, verify every extracted member, audit fragments/assets, then atomically rename. Commit the active index only after the final immutable directory is durable; reconcile an unindexed final directory safely on restart. For deletion, first transactionally deactivate or logically remove the item, then tombstone/delete only package directories no index row still references. For update, retain the old directory until the new index activation commits, then garbage-collect it only when unreferenced.

- [ ] **Step 6: Implement serialized explicit operations**

Use an application coroutine scope plus persisted Room rows, not WorkManager. Limit parallel downloads conservatively, support cancellation between safe checkpoints, and convert process-interrupted work to retryable state.

- [ ] **Step 7: Run unit and instrumented compilation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.data.library.packages.*" :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/data/library/packages app/src/test/java/com/putnamworkbook/offline/data/library/packages app/src/androidTest/java/com/putnamworkbook/offline/data/library/packages app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt
git commit -m "feat: install verified content packages"
```

### Task 4: Resolve logical documents and replace the direct asset-path repository

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentResourceRef.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/content/CatalogProblemRepository.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/content/CompositeContentRepository.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentRepository.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/content/ui/ContentResourceResolver.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/content/ui/RendererShell.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/content/ui/LocalContentClient.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/content/ui/MathContentWebView.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferenceDocumentBody.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferencePane.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/HelpPane.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/export/WorkbookPdfExporter.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/export/WorkbookExportOptions.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/data/content/CompositeContentRepositoryTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/content/ui/ContentResourceResolverTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/content/ui/LocalContentClientTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/export/WorkbookPdfExporterTest.kt`

**Interfaces:**
- `data class ContentResourceRef(val contentKey: String, val packageSha256: String, val memberPath: String, val memberSha256: String)`.
- Replace `assetPath` fields with `resource` fields in `ContentDocument`, `HintContent`, and `ProblemContent` (`statement`, `solution`).
- Add `fun observeInstalledRevision(): Flow<Long>` to `ContentRepository`.
- `interface ContentResourceResolver { fun urlFor(resource: ContentResourceRef): Uri; fun intercept(uri: Uri): WebResourceResponse?; fun readDocument(resource: ContentResourceRef): String }`.

- [ ] **Step 1: Write failing composite-repository tests**

Use catalog/index/package fakes to assert installed-only collections/sets/topics/problems, downloaded/bundled precedence, logical removal, removed-but-installed entries, unavailable documents, attribution decoding, and revision flow after mutation.

- [ ] **Step 2: Write failing resolver/WebView/PDF tests**

Cover bundled and downloaded fragments/assets, fixed renderer shell/CSP, bundled KaTeX CSS/fonts, member hash recheck, inactive-revision/undeclared/traversal URL rejection, remote-resource rejection, JavaScript/file/content access disabled, navigation blocking, and PDF export from a downloaded resource. A runtime member mismatch must show the local integrity state, quarantine the exact active digest, and leave the item unavailable without falling back to another revision until explicit restore/reinstall succeeds.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.data.content.CompositeContentRepositoryTest" :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 4: Implement catalog-backed problem decoding**

Read metadata from the active catalog or the index's retained last-known catalog entry and full problem structure from verified `source/problem.json`; construct only `ContentResourceRef` values whose member declarations match the active envelope. Keep legacy `RoomContentRepository` available to old tests until Plan 6 removes the loose runtime path.

- [ ] **Step 5: Implement fixed local resource hosting**

Serve only app-owned renderer-static and active package-member prefixes through `WebViewAssetLoader`. Wrap fragments with a fixed shell/CSP; never load package-supplied shell/style/script. Key WebViews by logical reference plus active digest. On an integrity mismatch, atomically quarantine that digest through the index, show the existing local integrity error, and do not select a bundled or older downloaded fallback; only an explicit verified restore/reinstall may clear the quarantine.

- [ ] **Step 6: Run content, WebView, and export tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/data/content app/src/main/java/com/putnamworkbook/offline/content/ui app/src/main/java/com/putnamworkbook/offline/feature/workspace app/src/main/java/com/putnamworkbook/offline/export app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt app/src/test/java/com/putnamworkbook/offline/data/content/CompositeContentRepositoryTest.kt app/src/androidTest/java/com/putnamworkbook/offline/content/ui app/src/androidTest/java/com/putnamworkbook/offline/export/WorkbookPdfExporterTest.kt
git commit -m "feat: resolve content from active packages"
```

### Task 5: Make browsing installed-only and recover missing recent problems

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/archive/ArchiveViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/archive/ArchiveScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceUiState.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/AppRoute.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/feature/archive/ArchiveViewModelTest.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModelTest.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/feature/home/HomeViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/feature/archive/LibraryBrowserTest.kt`

**Interfaces:**
- `sealed interface RecentProblemState { data object None; data class Available(val problem: ProblemSummary); data class Unavailable(val key: ProblemKey) }`.
- `AppRoute.ProblemLibrary(focusKey: ProblemKey?)` supports restoration from Continue Recent.
- Visible label changes from “Select problem” to “Browse Problems.”

- [ ] **Step 1: Write failing live-refresh and missing-content tests**

Assert browse/topic lists include active local items only and refresh after an installed-revision emission. Assert available recent opens workspace; unavailable recent routes to focused Problem Library; no recent remains disabled.

- [ ] **Step 2: Write failing stable workspace-unavailable tests**

Opening missing content yields an explicit unavailable state with restore/reinstall action and Back, not a generic exception or partially initialized notebook.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.archive.*" --tests "com.putnamworkbook.offline.feature.home.*" --tests "com.putnamworkbook.offline.feature.workspace.*" --no-daemon
```

- [ ] **Step 4: Implement observable reloads and routes**

Collect installed revision with cancellation-safe reloads; retain filters where possible. Keep progress rows even when summaries are unavailable. Do not create a notebook until content availability is confirmed.

- [ ] **Step 5: Run tests and compile instrumentation**

Run the command from Step 3 plus `:app:compileDebugAndroidTestKotlin`.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/feature/archive app/src/main/java/com/putnamworkbook/offline/feature/home app/src/main/java/com/putnamworkbook/offline/feature/workspace app/src/main/java/com/putnamworkbook/offline/navigation app/src/test/java/com/putnamworkbook/offline/feature/archive/ArchiveViewModelTest.kt app/src/test/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModelTest.kt app/src/test/java/com/putnamworkbook/offline/feature/home/HomeViewModelTest.kt app/src/androidTest/java/com/putnamworkbook/offline/feature/archive/LibraryBrowserTest.kt
git commit -m "feat: browse only installed problems"
```

### Task 6: Add the Problem Library management UI and batch actions

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/data/library/network/NetworkStatusMonitor.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/library/ProblemLibraryModels.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/library/ProblemLibraryViewModel.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/library/ProblemLibraryScreen.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/library/ProblemLibraryFilters.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/library/ProblemLibraryRow.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/library/LibraryActionDialog.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/AppRoute.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/com/putnamworkbook/offline/feature/library/ProblemLibraryViewModelTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/feature/library/ProblemLibraryScreenTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/navigation/ProblemLibraryNavigationTest.kt`

**Interfaces:**
- State combines last-known catalog, installed snapshot, operation queue, problem-progress presence, network availability, refresh metadata, filters, selection, and confirmation.
- Use Problems/Math Reference content-type segments. Problems filter by collection, set/year, topic, installation state, and text; reference entries filter by category, installation state, and text.
- All row/group actions map to `PackageInstaller.enqueue(keys, action)`.

- [ ] **Step 1: Write failing view-model/filter tests**

Cover both content-type segments, problem and reference filter intersections, deterministic text match, Installed/Available/Update available/App update required states, focused missing key, multi-selection, set/year/collection/reference-category batches, refresh retry, partial batch counts, network-only action disabling, and bundled offline restore.

- [ ] **Step 2: Run view-model tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.library.*" --no-daemon
```

- [ ] **Step 3: Implement state/action mapping**

Do not duplicate install logic in UI. Keep completed/remaining/failed/cancelled per-item results visible. Show “App update required” without an enabled install action.

- [ ] **Step 4: Write failing Compose/navigation tests**

Cover Home destination; problem row metadata/size/topics/status; reference row category/aliases/size/status; accessibility labels; offline explanations; retry; selection mode; group progress; problem deletion confirmation explicitly saying notebooks are retained; reference deletion wording; delete/restore; and Back.

- [ ] **Step 5: Implement tablet-first UI**

Use stable canonical-key tags, responsive list/detail layout where appropriate, and no horizontal control scrolling at the Galaxy Tab target width. Keep Home locally usable while refresh errors appear only as a non-disruptive library diagnostic.

- [ ] **Step 6: Run tests and compile instrumentation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.library.*" :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/data/library/network app/src/main/java/com/putnamworkbook/offline/feature/library app/src/main/java/com/putnamworkbook/offline/feature/home/HomeScreen.kt app/src/main/java/com/putnamworkbook/offline/navigation app/src/main/res/values/strings.xml app/src/test/java/com/putnamworkbook/offline/feature/library app/src/androidTest/java/com/putnamworkbook/offline/feature/library app/src/androidTest/java/com/putnamworkbook/offline/navigation/ProblemLibraryNavigationTest.kt
git commit -m "feat: manage downloadable problem library"
```

### Task 7: Back up desired library state without package bytes

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/backup/LibraryBackupState.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/backup/LibraryBackupStateCodec.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/backup/LibraryStateBackupPort.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/backup/BackupArchive.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/backup/BackupCoordinator.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/settings/SettingsScreen.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/backup/BackupArchiveTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/backup/BackupRestoreTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/feature/settings/SettingsScreenTest.kt`

**Interfaces:**
- Backup archive schema becomes 3 while readers accept 2 and 3.
- Optional `library-state.json` contains desired problem/reference keys, logically removed bundled keys, and informational active digests.
- Restore returns a validated `ReinstallPlan`; it restores bundled desired items locally but never starts remote downloads.
- Settings presents that `ReinstallPlan` as a dismissible, selectable review list; only an explicit online confirmation enqueues the selected remote items through `PackageInstaller`.

- [ ] **Step 1: Write failing v3 and backward-compatibility tests**

Cover deterministic v3 round trip, valid v2 archive without library state, malformed/duplicate/unsafe keys, malformed digest, unknown fields/version, and validation-before-mutation.

- [ ] **Step 2: Write failing restore semantics tests**

Assert bundled desired items restore offline; remote desired items become reviewed candidates; logical removals are restored; package bytes/HTML are absent; and user progress/settings/notebook trees round-trip unchanged. Add a Settings flow test proving candidates are visible and selectable, dismiss queues nothing, offline confirm remains disabled with an explanation, and explicit online confirm enqueues only the selected keys.

- [ ] **Step 3: Run backup tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.backup.*" :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 4: Implement optional library-state member and atomic restore ordering**

Decode and validate every member before changing notebooks, `UserDatabase`, or library index. Apply user/notebook restore with existing rollback guarantees, then apply library desired state; if library-state application fails, report a safe recovery result without trusting package bytes. Surface the returned reinstall candidates in Settings and reuse the package installer's explicit-operation path rather than downloading during restore.

- [ ] **Step 5: Run backup and index tests**

Run the command from Step 3 plus the installed-index unit tests and `SettingsScreenTest` instrumentation compilation.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/backup app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt app/src/main/java/com/putnamworkbook/offline/feature/settings app/src/test/java/com/putnamworkbook/offline/backup app/src/androidTest/java/com/putnamworkbook/offline/backup app/src/androidTest/java/com/putnamworkbook/offline/feature/settings/SettingsScreenTest.kt
git commit -m "feat: preserve desired library state in backups"
```

### Task 8: End-to-end privacy, recovery, and feature review

**Files:**
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/PrivacyManifestTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/ProblemLibraryAcceptanceTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/DownloadedContentAcceptanceTest.kt`
- Modify: `scripts/device-smoke-test.md`
- Modify only for review findings: files changed in Tasks 1–7.

**Interfaces:**
- Consumes: the catalog, index, package installer, composite repository, Problem Library UI, and backup port from Tasks 1–7.
- Produces: reviewed end-to-end behavior and evidence that network/content mutations cannot compromise offline startup or user work.

- [ ] **Step 1: Add privacy and startup assertions**

Require only intended `INTERNET`/`ACCESS_NETWORK_STATE` network permissions, `usesCleartextTraffic=false`, no unrelated sensitive permissions, JavaScript/network-blocked WebViews, and Home visible before a deliberately blocked refresh completes.

- [ ] **Step 2: Add acceptance workflows with a fake server**

Exercise catalog refresh, install/open offline, explicit update, corrupt-update rollback, runtime integrity quarantine with no fallback, delete with retained notebook/progress, bundled logical delete/offline restore, missing recent restore routing, interrupted queue recovery, batch partial failure, and backup reinstall plan. Never depend on live GitHub.

- [ ] **Step 3: Request whole-plan code/security review**

Require explicit attention to origin/redirect policy, archive parser, HTML audit, atomic filesystem/index ordering, cancellation, Room separation, missing-content routing, backup ordering, accessibility, and user-work preservation.

- [ ] **Step 4: Fix every Critical or Important finding with a regression**

Use focused commits and do not weaken archive/catalog/HTML validation.

- [ ] **Step 5: Run fresh full verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL. This debug assembly is verification only; do not copy an APK to `dist/`.

- [ ] **Step 6: Run connected tests when a device/emulator is attached**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

Record the absence of a device rather than claiming runtime execution.

- [ ] **Step 7: Commit acceptance coverage/review fixes**

```powershell
git add app/src/androidTest/java/com/putnamworkbook/offline/PrivacyManifestTest.kt app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt app/src/androidTest/java/com/putnamworkbook/offline/acceptance/ProblemLibraryAcceptanceTest.kt app/src/androidTest/java/com/putnamworkbook/offline/acceptance/DownloadedContentAcceptanceTest.kt scripts/device-smoke-test.md
git add -u -- app/src/main/java/com/putnamworkbook/offline app/src/test/java/com/putnamworkbook/offline app/src/androidTest/java/com/putnamworkbook/offline
git commit -m "test: cover downloadable problem library"
```
