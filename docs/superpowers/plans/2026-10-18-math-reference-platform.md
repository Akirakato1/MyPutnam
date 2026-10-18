# Math Reference Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a package-backed, offline-searchable Math Reference from Home and from the problem split view, with structured tables and navigation that never disturbs notebook state.

**Architecture:** Reference JSON compiles through the shared rich-document/package/catalog foundation. Android exposes a reference repository over the same catalog, installed index, package store, and logical resource resolver as problems. One reusable browser/view model drives a full Home screen or the existing left workspace pane. Workspace reference browsing is ephemeral and does not replace the persisted problem/hint/prerequisite/solution tab.

**Tech Stack:** Node.js 22/AJV/KaTeX/Playwright, Kotlin/Java 17, Jetpack Compose, coroutines/Flow, existing Room package index, Android WebView resolver, JUnit 4 and Compose/instrumented tests.

**Spec:** `docs/superpowers/specs/2026-10-18-github-content-library-and-math-reference-design.md`

## Global Constraints

- Depends on the shared content foundation and Android package/index/resolver tasks.
- `ReferenceKey` is exactly `math-reference/<categoryId>/<articleId>` and remains stable across revisions.
- Reference article schema v1 uses rich-document v2; problem schema v3 remains on rich-document v1.
- Tables are semantic structured data, never images; only the table wrapper may scroll horizontally.
- Search works entirely from local catalog metadata and installed-state data.
- Do not add Math Reference to persisted `ReferenceTab`; opening/closing it must not write a different active problem tab.
- Do not recreate, resize vertically, or reset `NotebookViewModel`, `NotebookPane`, active page, ink, selection, or 40/60 split while browsing references.
- Missing related items show install/restore affordances instead of broken documents.
- Do not curate Core Handbook content in this plan beyond fixtures; the next plan owns the reviewed corpus.
- Do not build a distributable APK in this plan.

## Review Focus

- Search ranking and tie-breaking must be deterministic and locale-independent.
- Table math, headers, accessibility semantics, and overflow must work at phone, split-pane, and full-tablet widths.
- Browser Back must unwind article/results/category history before returning to the prior workspace tab.
- Scroll offsets must be keyed by logical document plus active revision so updates do not inherit stale positions.
- Related keys must never become filesystem paths or unrestricted WebView navigation.

---

### Task 1: Compile validated reference articles into the shared package format

**Files:**
- Create: `tools/content/templates/reference.template.json`
- Create: `tools/content/REFERENCE_SCHEMA.md`
- Create: `tools/content/src/render-reference.mjs`
- Create: `tools/content/src/build-reference-package.mjs`
- Create: `tools/content/test/render-reference.test.mjs`
- Create: `tools/content/test/build-reference-package.test.mjs`
- Modify: `tools/content/src/build-publication.mjs`
- Modify: `tools/content/src/audit-publication.mjs`
- Modify: `tools/content/test/build-publication.test.mjs`
- Use existing foundation files: `tools/content/schema/reference-article.schema.json`, `tools/content/schema/rich-document-v2.schema.json`, `tools/content/src/validate-reference.mjs`

**Interfaces:**
- `renderReferenceArticle(record): string` composes source sections in the approved fixed order and returns one inert fragment.
- `buildReferencePackage({ record, articleAssetRoot, libraryCatalog, minAppVersionCode }): Promise<{ contentKey, archiveBytes, envelope, catalogMetadata }>`.
- Package members are exactly `package.json`, `source/reference.json`, `documents/article.fragment.html`, and declared `assets/`.

- [ ] **Step 1: Write failing fixed-order and table-render tests**

Use the valid foundation fixture to assert title/aliases/notation, definitions, results, conditions, examples, pitfalls, relations, and attribution appear in the approved order. Assert semantic caption/header/cell markup, KaTeX-root counts, table-local overflow, and no page-level overflow.

- [ ] **Step 2: Write failing package tests**

Assert deterministic ZIP bytes, stable key/revision, canonical source JSON, exact members, declared local assets, no review/provenance files, and descriptor/envelope/catalog agreement.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
node --test tools/content/test/render-reference.test.mjs tools/content/test/build-reference-package.test.mjs
```

- [ ] **Step 4: Implement the renderer/compiler through shared primitives**

Do not fork the rich-document renderer, asset auditor, ZIP writer, or package envelope. The article renderer owns section headings/order; section bodies remain structured documents.

- [ ] **Step 5: Extend publication builder/auditor for reference entries**

Add zero-or-more reference categories/articles to the complete catalog and audit relation keys, category/order uniqueness, rights, package closure, and descriptor agreement. Keep a zero-reference catalog valid until curation lands.

- [ ] **Step 6: Run full content-tool tests**

```powershell
npm --prefix tools/content test
```

- [ ] **Step 7: Commit**

```powershell
git add tools/content/templates/reference.template.json tools/content/REFERENCE_SCHEMA.md tools/content/src/render-reference.mjs tools/content/src/build-reference-package.mjs tools/content/src/build-publication.mjs tools/content/src/audit-publication.mjs tools/content/test
git commit -m "feat: build math reference packages"
```

### Task 2: Add reference identity, repository, and deterministic offline search

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/domain/model/ReferenceKey.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/content/ReferenceRepository.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/content/CompositeReferenceRepository.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/reference/ReferenceSearch.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/domain/model/ReferenceKeyTest.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/data/content/CompositeReferenceRepositoryTest.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/feature/reference/ReferenceSearchTest.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`

**Interfaces:**
- `data class ReferenceKey(val categoryId: String, val articleId: String) { val canonical: String; companion object { fun parse(canonical: String): ReferenceKey } }` reuses the safe-ID rule from `ProblemKey`.
- `data class ReferenceCategorySummary(val id: String, val title: String, val displayOrder: Int)`.
- `data class ReferenceArticleSummary(val key: ReferenceKey, val title: String, val aliases: List<String>, val keywords: List<String>, val categoryTitle: String, val categoryDisplayOrder: Int, val displayOrder: Int)`.
- `data class ReferenceArticleContent(val summary: ReferenceArticleSummary, val document: ContentResourceRef, val relatedReferences: List<ReferenceKey>, val relatedProblems: List<ProblemKey>, val attribution: List<Attribution>)`.
- `interface ReferenceRepository { fun observeCategories(): Flow<List<ReferenceCategorySummary>>; fun observeInstalledArticles(): Flow<List<ReferenceArticleSummary>>; suspend fun article(key: ReferenceKey): ReferenceArticleContent? }`.
- `searchReferences(query, articles): List<ReferenceSearchResult>` ranks exact title, exact alias, title prefix, alias prefix, then keyword/category match.

- [ ] **Step 1: Write failing identity tests**

Cover canonical round trip, case sensitivity, fixed namespace, unsafe/blank/dot/extra segments, and equality/hash behavior.

- [ ] **Step 2: Write failing search tests**

Normalize trimmed/collapsed whitespace with `lowercase(Locale.ROOT)`. Cover exact/prefix/category/keyword ranks, aliases such as `phi`, deterministic ties by category order/article order/title/key, empty query behavior, and Turkish-locale independence.

- [ ] **Step 3: Write failing composite-repository tests**

Cover active installed items only, downloaded-over-bundled precedence, logical removal, catalog revision updates, absent related targets, source/envelope key mismatch rejection, and reference/problem relation decoding.

- [ ] **Step 4: Run focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.domain.model.ReferenceKeyTest" --tests "com.putnamworkbook.offline.data.content.CompositeReferenceRepositoryTest" --tests "com.putnamworkbook.offline.feature.reference.ReferenceSearchTest" --no-daemon
```

- [ ] **Step 5: Implement repository/search without Android paths**

Expose only `ContentResourceRef`; read article metadata from catalog and active source JSON through verified package members. Missing relations remain typed unresolved keys and do not invalidate the current article.

- [ ] **Step 6: Run tests and verify GREEN**

Run the command from Step 4.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/domain/model/ReferenceKey.kt app/src/main/java/com/putnamworkbook/offline/data/content/ReferenceRepository.kt app/src/main/java/com/putnamworkbook/offline/data/content/CompositeReferenceRepository.kt app/src/main/java/com/putnamworkbook/offline/feature/reference/ReferenceSearch.kt app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt app/src/test/java/com/putnamworkbook/offline/domain/model/ReferenceKeyTest.kt app/src/test/java/com/putnamworkbook/offline/data/content/CompositeReferenceRepositoryTest.kt app/src/test/java/com/putnamworkbook/offline/feature/reference/ReferenceSearchTest.kt
git commit -m "feat: search installed math references"
```

### Task 3: Build the reusable category/search/article browser

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/reference/MathReferenceUiState.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/reference/MathReferenceViewModel.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/reference/MathReferenceBrowser.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/reference/ReferenceArticlePane.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/feature/reference/MathReferenceViewModelTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/feature/reference/MathReferenceBrowserTest.kt`

**Interfaces:**
- `sealed interface ReferenceLocation { data object Categories; data class Category(val id: String); data class Results(val query: String); data class Article(val key: ReferenceKey) }`.
- Public view-model methods: `setQuery`, `openCategory`, `openArticle`, `openRelatedReference`, `openRelatedProblem`, `back(): Boolean`, and `retry`.
- Browser state includes categories, query/results, current article, back stack, loading/error, installed/available relation state, and a focused library request.

- [ ] **Step 1: Write failing navigation-state tests**

Cover Categories → Category → Article, query → Results → Article, related-article chains, consumed Back order, root Back returning false, query clearing, repository revision refresh, unavailable article, and recoverable load error.

- [ ] **Step 2: Run view-model tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.reference.MathReferenceViewModelTest" --no-daemon
```

- [ ] **Step 3: Implement one reusable state machine**

Use an internal `ArrayDeque<ReferenceLocation>` and supplied coroutine scope. Do not use Android navigation inside the browser; its host decides what root Back means.

- [ ] **Step 4: Write failing Compose browser tests**

Cover category order/counts, offline search field/results/ranking, empty/no-result states, article section rendering, related reference/problem actions, missing target install/restore affordance, attribution display, error/retry, and accessibility labels.

- [ ] **Step 5: Implement responsive category/results/article composition**

Use the shared logical document renderer for article body. Render related links and attribution as typed Compose controls from article metadata, so stable keys never become arbitrary WebView URLs.

- [ ] **Step 6: Run unit tests and compile instrumentation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.reference.*" :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/feature/reference app/src/test/java/com/putnamworkbook/offline/feature/reference app/src/androidTest/java/com/putnamworkbook/offline/feature/reference
git commit -m "feat: browse math references offline"
```

### Task 4: Add the Home Math Reference destination and MyPutnam branding

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/feature/reference/MathReferenceScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/AppRoute.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/PutnamApp.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/navigation/MathReferenceNavigationTest.kt`

**Interfaces:**
- `AppRoute.MathReference` path `math-reference`.
- Home callback `onMathReference` and test tag `home-math-reference`.
- Visible application title becomes `MyPutnam`; package/application ID remains unchanged.

- [ ] **Step 1: Write failing Home/navigation tests**

Assert Home order includes Continue Recent, Browse Problems, Free Notebook, Problem Library, Math Reference, Settings; clicking opens the full browser; root Back returns Home; internal browser Back is consumed first; state survives configuration/navigation recomposition as intended.

- [ ] **Step 2: Compile instrumentation and verify RED**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 3: Implement route and scoped view model**

Create/close the browser view model with its `NavBackStackEntry`. Route missing related problems to Browse/Problem Library using stable keys without closing user work unexpectedly.

- [ ] **Step 4: Re-run compile and focused navigation tests when a device exists**

Compile always; run the single connected class when available.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/feature/reference/MathReferenceScreen.kt app/src/main/java/com/putnamworkbook/offline/feature/home/HomeScreen.kt app/src/main/java/com/putnamworkbook/offline/navigation app/src/main/java/com/putnamworkbook/offline/PutnamApp.kt app/src/main/res/values/strings.xml app/src/androidTest/java/com/putnamworkbook/offline/navigation/MathReferenceNavigationTest.kt
git commit -m "feat: open math reference from home"
```

### Task 5: Preserve document scroll positions by logical revision

**Files:**
- Create: `app/src/main/java/com/putnamworkbook/offline/content/ui/WebDocumentPositionStore.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/content/ui/MathContentWebView.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferenceDocumentBody.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/reference/ReferenceArticlePane.kt`
- Create: `app/src/test/java/com/putnamworkbook/offline/content/ui/WebDocumentPositionStoreTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/content/ui/LocalContentClientTest.kt`

**Interfaces:**
- `class WebDocumentPositionStore { fun position(documentId: String): Int; fun update(documentId: String, scrollY: Int); fun clear(documentId: String) }`.
- `MathContentWebView(resource, initialScrollY, onScrollYChanged, onIntegrityError, modifier)`.
- `documentId` includes content key, member path, and active package digest.

- [ ] **Step 1: Write failing store tests**

Cover independent documents/revisions, nonnegative clamping, update/clear, and bounded least-recently-used retention so a long session cannot grow without limit.

- [ ] **Step 2: Write failing WebView lifecycle tests**

Scroll a long local article, switch documents, return, and assert restored Y after page-ready. Assert a new revision starts at zero, callbacks come from native scroll changes with JavaScript still disabled, and disposed views save their latest position.

- [ ] **Step 3: Run tests/compile and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.content.ui.WebDocumentPositionStoreTest" :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 4: Implement native scroll capture/restore**

Use WebView scroll callbacks and restore only after the verified local document finishes loading. Keep separate position stores for the Home browser and each workspace browser host.

- [ ] **Step 5: Run tests and commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/content/ui app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferenceDocumentBody.kt app/src/main/java/com/putnamworkbook/offline/feature/reference/ReferenceArticlePane.kt app/src/test/java/com/putnamworkbook/offline/content/ui/WebDocumentPositionStoreTest.kt app/src/androidTest/java/com/putnamworkbook/offline/content/ui/LocalContentClientTest.kt
git commit -m "feat: preserve reference scroll positions"
```

### Task 6: Embed the browser in the problem split view without persisting a new tab

**Files:**
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceUiState.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModel.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/ReferencePane.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreen.kt`
- Modify: `app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt`
- Modify: `app/src/test/java/com/putnamworkbook/offline/feature/workspace/WorkspaceViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/feature/workspace/WorkspaceScreenTest.kt`

**Interfaces:**
- Add ephemeral `mathReferenceOpen: Boolean` to `WorkspaceUiState` or an equivalent non-persisted presentation model.
- Add `WorkspaceViewModel.openMathReference()` and `closeMathReference()`; neither calls `ProgressRepository.setNotebookState`.
- Keep `ReferenceTab.visibleEntries` as Problem/Help/Prerequisites/Solution. Render a separate “Reference” control beside them.

- [ ] **Step 1: Write failing persistence-boundary tests**

Open a problem on each existing tab, enter/search/open/back through Math Reference, close it, and assert `activeReferenceTab`, active hint/prerequisite, reveal state, and progress-write count are unchanged.

- [ ] **Step 2: Write failing notebook-stability Compose tests**

Capture notebook composition identity/bounds, active page, strokes, selection, and toolbar state. Browse multiple reference locations and assert the right pane remains the same instance with identical 40/60 geometry and no vertical shift/reset.

- [ ] **Step 3: Run tests/compile and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.putnamworkbook.offline.feature.workspace.WorkspaceViewModelTest" :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 4: Implement an in-place left-pane browser**

Scope `MathReferenceViewModel` inside the existing workspace `NavBackStackEntry`; do not navigate away from the workspace. Browser Back unwinds internally; root Back closes it and restores the prior tab and its scroll/selection.

- [ ] **Step 5: Run workspace tests and commit**

```powershell
git add app/src/main/java/com/putnamworkbook/offline/feature/workspace app/src/main/java/com/putnamworkbook/offline/navigation/PutnamNavHost.kt app/src/test/java/com/putnamworkbook/offline/feature/workspace app/src/androidTest/java/com/putnamworkbook/offline/feature/workspace
git commit -m "feat: browse references beside the notebook"
```

### Task 7: Validate responsive tables and end-to-end reference behavior

**Files:**
- Modify: `tools/content/src/render-review.mjs`
- Modify: `tools/content/test/render-review.test.mjs`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/MathReferenceAcceptanceTest.kt`
- Modify only for findings: files changed in Tasks 1–6.

**Interfaces:**
- Consumes: the complete reference source/runtime/UI stack from Tasks 1–6.
- Produces: responsive render receipts, end-to-end notebook-preservation evidence, and reviewed fixes with regressions.

- [ ] **Step 1: Add 336/512/1280-width render assertions**

Capture representative text/math/table reference fixtures. Fail on body-level horizontal overflow, clipped KaTeX, inaccessible headers, or a table wrapper that cannot scroll independently.

- [ ] **Step 2: Add end-to-end acceptance coverage**

From Home, search/open/back through an installed article offline. In a problem, draw ink, switch notebook page, select a hint, browse/search/open a reference, return, and assert identical notebook page, stroke data, geometry, selection, toolbar state, prior reference tab, and scroll offsets.

- [ ] **Step 3: Request whole-plan review**

Require explicit attention to schema/table semantics, search ranking, lifecycle cancellation, related-key handling, scroll restoration, WebView restrictions, Back behavior, and notebook composition/state preservation.

- [ ] **Step 4: Fix every Critical or Important finding with regression coverage**

- [ ] **Step 5: Run fresh verification**

```powershell
npm --prefix tools/content test
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: all commands pass. Debug assembly is verification only; do not publish an APK.

- [ ] **Step 6: Run connected acceptance when a device/emulator exists**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

Record any unavailable device limitation.

- [ ] **Step 7: Commit**

```powershell
git add tools/content/src/render-review.mjs tools/content/test/render-review.test.mjs app/src/androidTest/java/com/putnamworkbook/offline/acceptance/MathReferenceAcceptanceTest.kt
git add -u -- app/src/main/java/com/putnamworkbook/offline app/src/test/java/com/putnamworkbook/offline app/src/androidTest/java/com/putnamworkbook/offline
git commit -m "test: cover math reference platform"
```
