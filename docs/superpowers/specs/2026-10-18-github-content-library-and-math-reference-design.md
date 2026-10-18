# GitHub Content Library and Math Reference Design

**Date:** 2026-10-18

**Status:** Proposed for written review

**Product name:** MyPutnam

## Purpose

MyPutnam must remain useful without a network connection while growing beyond
the problem data that can reasonably ship in every APK. Users should be able to
see a current catalog hosted from the MyPutnam GitHub repository, install only
the problems they want, update or remove those problems explicitly, and keep
all notebook work independent of content installation.

The same foundation will also provide an offline **Math Reference** covering
commonly used high-school through undergraduate formulas, theorems, methods,
and transform tables. The reference must be available from Home and from the
left pane of an active problem without disturbing the notebook. Its content
contract must also be reusable by a future static web version of MyPutnam.

The first implementation also fixes a current content defect: mathematical
notation embedded in hint list-item text, especially fourth-hint proof
outlines, can appear as unrendered plain text. Every rich-document location,
including list items, must use the same structured math and KaTeX pipeline.

## Existing Baseline

The current application is a native Android app whose replaceable problem
content is compiled into:

- `app/src/main/assets/databases/content.db`;
- generated HTML beneath `app/src/main/assets/content/`; and
- shared offline KaTeX CSS and fonts beneath
  `app/src/main/assets/content/shared/`.

Problem source already uses schema v3 and the stable canonical identity
`collectionId/setId/problemId`. `ProblemKey`, notebook paths, and progress rows
already use that identity. The source format supports structured paragraphs,
headings, display math, inline math, lists, and figures. This is the correct
base to extend rather than replace.

`ContentRepository` currently exposes Room rows containing Android asset paths.
`ContentDatabase` is an immutable database recreated from the APK whenever its
asset hash changes. Downloaded packages require a layer above this asset-only
implementation and a resource abstraction that is not tied to `AssetManager`.

User data is already separate: progress and settings live in `UserDatabase`,
while problem and Free Notebook documents live under the notebook filesystem.
That separation must remain intact.

## Scope

This design includes:

- a versioned static catalog published from the MyPutnam GitHub repository;
- one immutable downloadable package per problem;
- the current Putnam 2020–2025 problems represented in that catalog and also
  bundled as an offline starter collection;
- manual install, update, delete, restore, multi-select, and collection/set
  batch operations;
- a non-blocking startup catalog refresh with a last-known-good cache;
- layered resolution of bundled and downloaded content;
- independent preservation of notebooks and progress;
- a Home-level **Problem Library** for managing content;
- a structured, searchable **Math Reference** that uses the same delivery
  foundation;
- a bundled Core Handbook v1 and support for future downloadable reference
  articles;
- Math Reference access inside the existing split-view workspace;
- platform-neutral schemas and fixtures suitable for a later static web app;
- explicit network, archive, integrity, compatibility, attribution, and
  publishing safeguards;
- backup metadata sufficient to identify content that should be reinstalled;
  and
- correction and regression coverage for math inside hint list items.

## Non-goals

The first version does not include:

- automatic installation or automatic updating of problem packages;
- subscription-style category syncing;
- user-authored imports, arbitrary feed URLs, or third-party repositories;
- accounts, cloud notebook sync, analytics, telemetry, or uploads;
- a static web application implementation;
- runtime execution of downloaded JavaScript or arbitrary HTML;
- catalog signing or certificate pinning;
- equation-shape or handwriting search;
- completion of the entire 1962–2025 Putnam archive as part of this feature;
- an attempt to reproduce every result in an undergraduate mathematics
  curriculum in Core Handbook v1; or
- redistribution of material whose publication rights have not been cleared.

The contracts deliberately leave room for optional automatic sync, signed
catalogs, more competitions, physics collections, and the future web client.
Those are later features rather than hidden requirements of this release.

## Approaches Considered

### Selected: static catalog plus immutable per-item packages

GitHub Pages serves one validated catalog and content-addressed packages. The
app stores a last-known-good catalog and installs packages atomically. This
supports selective downloads, deterministic review, offline operation, safe
rollback on failure, and a future browser client without embedding Git logic.

### Rejected: mutable loose JSON loaded directly from `main`

Loose files are easy to author, but a multi-file update can be observed halfway
through publication. It also makes individual install integrity, atomicity,
asset completeness, and reliable caching harder. Editable JSON remains the
source format; it is compiled into immutable publication artifacts.

### Rejected: cloning or pulling the Git repository on-device

Shipping a Git client would add substantial storage, credentials, repository
state, and recovery complexity without improving the user experience. GitHub
is a static publication source, not an application database.

### Rejected for now: shared Kotlin Multiplatform runtime

The future web client needs the same content contract, identifiers, and golden
fixtures, but it does not need Android Room or filesystem code. JSON Schema and
conformance fixtures provide the useful boundary now. A shared runtime can be
considered only after a web client exists and demonstrates genuine shared-code
value.

## Identity and Content Types

### Problems

Problem identity remains the case-sensitive, path-safe three-part
`ProblemKey`:

```text
<collectionId>/<setId>/<problemId>
```

For example, `putnam/2025/A1` always identifies the same mathematical problem.
A corrected explanation or renderer output changes the package revision, not
the problem key. Notebook and progress records continue to depend only on the
problem key.

### Reference articles

A new `ReferenceKey` uses:

```text
math-reference/<categoryId>/<articleId>
```

For example, `math-reference/number-theory/euler-totient` identifies one
article across revisions. Category and article IDs use the same safe-ID rules
as `ProblemKey` segments.

### Revisions

Each catalog entry has an opaque `packageRevision` and a SHA-256 digest. Clients
compare the digest for equality; they do not infer ordering from a revision
string. A new revision is published at a new immutable path. Existing package
bytes are never replaced in place.

## Rich-document Contracts

Problem schema v3 remains valid. Existing problem JSON is not rewritten into a
less structured format.

The current v3 rich-document contract becomes an explicitly versioned shared
contract for problem content. It retains:

- headings;
- paragraphs;
- display math;
- ordered and unordered lists whose items are arrays of inline runs;
- figures with local assets; and
- `text` and `inlineMath` runs.

Reference articles use a second compatible rich-document version that adds a
structured `table` block. Table headers and cells contain rich documents or
inline runs, never rasterized formula sheets. This makes Laplace/Fourier tables
readable, searchable by metadata, accessible, and correctly rendered at tablet
and browser widths. Problem schema v3 stays immutable; a future problem schema
may opt into the newer rich-document version separately.

Raw HTML, Markdown math delimiters, remote images, and TeX embedded in `text`
runs are not authoring formats. Mathematical notation belongs in
`inlineMath` or `displayMath`. The renderer pre-renders KaTeX with strict errors
and `trust: false`.

## Problem Source and Package Contract

The canonical authoring record remains one validated problem JSON document
with:

- stable problem identity and display metadata;
- topic IDs;
- statement;
- four staged hints (`nudge`, `strategy`, `knowledge`, `outline`);
- ordered prerequisite refreshers;
- complete solution; and
- source attribution.

The publishing tool compiles each problem into one ZIP package. A problem ZIP
contains only declared files:

```text
package.json
source/problem.json
documents/statement.fragment.html
documents/hint-1.fragment.html
documents/hint-2.fragment.html
documents/hint-3.fragment.html
documents/hint-4.fragment.html
documents/prerequisite-1.fragment.html
documents/prerequisite-<n>.fragment.html
documents/solution.fragment.html
assets/<optional problem-local files>
```

HTML files are generated body fragments, not standalone executable pages. The
Android and future web hosts provide their own fixed document shell, CSP,
styles, and bundled KaTeX fonts. This avoids platform-specific asset URLs while
preserving deterministic build-time math rendering.

`package.json` records:

- package schema version and content type;
- canonical content key and package revision;
- source schema and rich-document versions;
- minimum compatible app version code;
- every member path, media type, byte length, and SHA-256 digest; and
- total declared uncompressed size.

The package contains no source PDFs, locked provenance files, review notes,
scripts, or undeclared archive members.

## Math Reference Source and Package Contract

Each Math Reference article is a validated JSON record with:

- `schemaVersion`;
- stable `ReferenceKey`;
- title and display order;
- aliases, category, and search keywords;
- definitions and notation;
- formulas, identities, theorem statements, or methods;
- conditions and conventions under which the results apply;
- at least one short worked example;
- common mistakes or limitations;
- related reference keys;
- optional related problem keys; and
- sources, attribution, and rights metadata.

All explanatory fields are structured rich documents. A reference package uses
the same envelope and safety rules as a problem package and contains:

```text
package.json
source/reference.json
documents/article.fragment.html
assets/<optional article-local files>
```

The article renderer creates a consistent page from the named source sections.
Relations are links by stable key, never filesystem paths. A missing or removed
related item does not make the current article invalid.

## Catalog Contract

The published catalog is a complete snapshot, not a sequence of patches. Its
schema includes:

- catalog schema version, catalog ID, opaque catalog revision, and generation
  time;
- minimum client compatibility information;
- collections and their sets;
- shared problem topics;
- Math Reference categories;
- resolved attribution and rights information; and
- one entry for every published problem and reference article.

Each problem entry includes its stable key, label, ordinal, collection/set
metadata, topic IDs, and package descriptor. Each reference entry includes its
stable key, title, aliases, category, keywords, display order, and package
descriptor. Search and list metadata therefore remain usable without
downloading item packages.

Every package descriptor contains:

- content type;
- opaque package revision;
- package schema/content schema versions;
- same-origin relative ZIP path;
- compressed byte size and declared uncompressed byte size;
- SHA-256 digest; and
- minimum app version code.

Catalog validation rejects duplicate keys, duplicate display order within a
scope, unknown topics/categories, unsafe paths, absolute package URLs, missing
rights data, incompatible schema combinations, and packages whose envelope or
digest does not match the catalog.

The bundled catalog contains the same entries and descriptors used by the
published feed for Putnam 2020–2025 and Core Handbook v1 at that APK's build
revision. A later remote catalog may advertise newer immutable revisions.
Bundling does not create a second metadata format.

## Repository and Publication Layout

Editable source remains reviewable in Git:

```text
content-src/
  library.json
  <problem collections and sets>/
  reference/
    categories.json
    articles/<categoryId>/<articleId>.json
    review/<categoryId>/<articleId>.md
tools/content/
  schema/
  src/
  test/
```

A deterministic publisher writes a disposable staging directory such as:

```text
build/content-publication/
  content/v1/catalog.json
  content/v1/packages/problem/<collection>/<set>/<problem>/<digest>.zip
  content/v1/packages/reference/<category>/<article>/<digest>.zip
```

Generated publication output is not hand-edited. A GitHub Actions workflow
runs schema, mathematical-review, render, package, digest, and catalog tests,
then deploys one complete artifact to GitHub Pages. Pages publication is
atomic at the artifact level: the new catalog is never exposed without every
package it references.

The production feed root is compiled into the app and uses the MyPutnam GitHub
Pages HTTPS origin. Package paths resolve relative to that origin. A later
static web client can be deployed beside `/content/v1/` without changing the
catalog contract.

## Android Runtime Architecture

The content subsystem is divided into focused components:

### `CatalogClient`

Fetches the catalog with bounded connect/read timeouts and conditional HTTP
headers. It knows the one configured HTTPS origin but does not install content.

### `CatalogStore`

Maintains the embedded catalog, the last-known-good remote catalog, its cache
metadata, and the time/result of the most recent refresh. A remote catalog is
made visible only after complete validation and atomic replacement.

### `PackageStore`

Owns app-private staging and installed-package directories. It performs safe
archive inspection, extraction, member verification, and atomic activation.
Temporary data is recoverable and disposable. The APK embeds the exact starter
ZIP bytes named by its embedded catalog. The store treats those ZIPs as a
read-only source and verifies/extracts them lazily, allowing offline restore
without maintaining a second loose-asset representation.

### `InstalledContentIndex`

A new replaceable-content database tracks active revisions, physical package
locations, bundled availability, downloaded overrides, logical removal of
bundled items, install errors, and desired-install metadata used by backup.
It is separate from `UserDatabase` and notebook files.

### `PackageInstaller`

Coordinates explicit install/update/delete/restore actions. Downloads first go
to staging. The installer verifies the outer ZIP digest and size, archive
limits, `package.json`, every member digest, schema compatibility, and content
key before atomically switching the active revision.

### Content repositories

The existing UI-facing problem repository becomes a composite over installed
content rather than a direct Room/asset-path reader. A parallel reference
repository exposes installed reference metadata, category browsing, offline
search, and article documents. Screens receive logical document references;
they do not know whether bytes came from the APK or app-private storage.

### `ContentResourceResolver`

Resolves a logical document reference through the active package and serves a
fixed local WebView shell plus the verified HTML fragment and local assets. It
also serves the app-bundled KaTeX CSS/fonts. Existing WebView protections remain:
JavaScript disabled, network loads blocked, unsafe navigation blocked, and a
restrictive CSP supplied by the host shell.

## Layering and Resolution Rules

For a stable content key, resolution is deterministic:

1. An active verified downloaded revision wins.
2. Otherwise an active bundled starter revision is used.
3. A logical-removal record suppresses the bundled fallback.
4. Otherwise the item is unavailable locally even if present in the catalog.

Updating verifies and stages the replacement before switching. The old active
revision remains usable until that switch succeeds and is removed only after
the index commit. The app does not automatically downgrade or replace content.

If a catalog stops listing an installed package, the package remains installed
and usable, marked as no longer listed. Catalog changes never delete content or
user work.

## Startup and Catalog Refresh

Home renders from local state immediately. If networking is available, the app
starts one non-blocking catalog refresh. It is acceptable to attempt the fetch
and handle failure rather than gate on Android connectivity heuristics.

The refresh uses `ETag` and/or `Last-Modified` when supplied. A `304` preserves
the cached catalog. A timeout, HTTP failure, invalid schema, incompatible
catalog, or interrupted write preserves the previous valid catalog. If no
remote catalog has ever succeeded, the embedded catalog remains the usable
baseline.

Startup refresh only changes catalog metadata. It never installs or updates a
problem/reference package. Problem Library exposes the last refresh time,
non-disruptive error state, and a manual retry.

## Manual Install and Batch Operations

Actual package changes require an explicit user action. Individual, selected,
set/year, category, and whole-collection actions enqueue the same per-entry
operations. The queue persists enough state to recover cleanly from process
death; an interrupted item returns to a retryable state rather than appearing
installed.

Group progress reports completed, remaining, failed, and cancelled items.
Failure of one package does not roll back successfully installed independent
items. Repeating an operation is idempotent.

An item whose `minAppVersionCode` is newer than the installed app is visible
but cannot be installed and is labelled **App update required**.

## Delete and Restore Semantics

Deleting a downloaded-only item removes its active package after the index
transaction succeeds. Deleting a bundled item records a logical removal and
clears any extracted cache. Its unavoidable compressed APK bytes do not shrink,
and the UI explains this when storage details are shown.

Deleting a downloaded override for a bundled item also records logical removal;
it must not unexpectedly reveal the older bundled version. Restoring a bundled
item clears that record and works offline. When online, the user may instead
install the catalog revision.

Problem notebooks, progress, completion status, revealed hints, and recency
records are never deleted with content. Browse Problems hides uninstalled
problems, while Problem Library can indicate that preserved notebook work
exists. Continue Recent must not crash or open an unavailable workspace: it
routes the user to restore the missing problem.

Reference articles contain no notebook data. Removing them only changes local
reference availability; related links show a restore/install affordance when
the target is absent.

## Backup and Restore

Backups continue to contain irreplaceable user data rather than package bytes.
The next backup schema adds a small validated library-state member containing:

- desired installed problem/reference keys;
- logically removed bundled keys; and
- the package digests active when the backup was made, for information only.

Restoring never trusts or restores downloaded HTML/ZIP bytes. Bundled desired
items can be restored locally. Missing remote items are shown as a reviewed
reinstall set; the user explicitly starts the downloads when online. Older
backup archives without library state remain valid and use starter defaults.

## Home and Problem Browsing

Home contains:

- Continue Recent;
- Browse Problems;
- Free Notebook;
- Problem Library;
- Math Reference; and
- Settings.

**Browse Problems** replaces the current installed-library meaning of “Select
Problem.” It shows only active local problems and retains collection/year and
topic browsing. Existing progress state remains visible.

## Problem Library UI

Problem Library displays the complete last-known catalog. It includes the
bundled Putnam 2020–2025 entries exactly like future cataloged problems, with a
starter/bundled detail rather than a separate section.

Users can filter by:

- collection;
- set or year;
- topic;
- installation state (`Installed`, `Available`, `Update available`, or
  `App update required`); and
- a text query over catalog labels and metadata.

Each row displays its collection/set/problem identity, topics, download size,
installed state, and available update state. Row actions support install,
update, delete, retry, or restore as appropriate. Selection mode supports
multi-select plus group install/delete by year, set, reference category, or
collection. Destructive group actions require confirmation and explicitly say
that notebooks are retained.

Offline, catalog browsing and local deletion/restoration of bundled items still
work. Network-required buttons are disabled with an explanatory state. A
partial or failed download is never shown as installed.

## Math Reference UI

Home → Math Reference opens a category browser with an offline search field.
Search is case-insensitive and covers titles, aliases, category labels, and
keywords. Ranking favors exact title, exact alias, title prefix, alias prefix,
then keyword/category matches. Formula glyph recognition and full-body search
are outside v1; aliases such as `phi`, `totient`, `integration by parts`, and
`Weierstrass substitution` make named results easy to find.

An article page presents, in a consistent order:

1. title, aliases, and notation;
2. definitions;
3. formula/result or method;
4. conditions and convention notes;
5. worked example;
6. pitfalls;
7. related reference/problem links; and
8. attribution.

Long tables remain vertically readable and allow horizontal scrolling within
the table only; the whole pane must not acquire horizontal overflow.

Inside a problem workspace, a **Reference** tab/button is added to the existing
left reference pane. It opens the same search/category/article experience in
that pane. The right notebook never closes, resizes vertically, or resets.
Reference-pane Back returns article → results/category → the previously active
problem/hint/prerequisite/solution tab while preserving its selection and
scroll state.

## Core Handbook v1

Core Handbook v1 is bundled, cataloged, and logically removable/restorable in
the same way as bundled problems. It is curated in category batches, with a
review record for each article. Its initial categories are:

1. Algebra and inequalities
2. Sequences, series, and recurrences
3. Trigonometry
4. Geometry and vectors
5. Complex numbers
6. Number theory
7. Combinatorics and generating functions
8. Differential and integral calculus
9. Linear algebra
10. Probability
11. Differential equations
12. Laplace and Fourier transforms

The initial content must include the high-value families requested for lookup:
standard algebra and complex-number rules; common inequality tools;
trigonometric identities; combinatorial formulas; Euler's totient function and
core modular-number-theory results; arithmetic/geometric series and standard
linear-recurrence solution methods; derivative and integral tables;
substitution, integration by parts, partial fractions, Weierstrass
substitution, and representative reduction formulas; first- and second-order
ODE methods; and coherent Laplace/Fourier definitions, properties, convention
notes, inverse methods, and common transform tables.

Transform tables explicitly state conventions, domains, convergence/region
conditions, and distributional caveats where relevant. “Common table” means a
curated practical table, not a claim of mathematical exhaustiveness.

Each article must pass:

- schema validation;
- mathematical correctness review;
- notation and convention consistency review;
- independent source/attribution review;
- KaTeX/table rendering review at phone, tablet split-pane, and full-tablet
  widths; and
- search-alias review.

Advanced real analysis, abstract algebra, topology, special functions, and
additional physics-oriented references are later catalog expansions.

## Hint List-item Math Defect

The renderer already supports `inlineMath` inside list-item runs. The observed
failure is present in source records where mathematical expressions were
authored as ordinary `text` runs. For example, several 2025 fourth-hint outline
items contain strings such as `c_{n,k}`, `R^3`, or `A_iA_j=A_jA_i` entirely in
text. The generated HTML faithfully leaves those strings unrendered.

The fix has two parts:

1. audit all current hint list items and split every mathematical expression
   into `inlineMath` runs; and
2. add a source-quality rule that rejects likely raw math syntax/operators in
   list-item text and a render regression asserting that expected list-item
   math produces KaTeX roots.

The quality rule is deliberately scoped to structured list items so ordinary
prose punctuation is not broadly reinterpreted. The full 2020–2025 content
suite and screenshot review must run after correction. No runtime Markdown or
delimiter parser is introduced.

## Network and Privacy Rules

The Android manifest adds `android.permission.INTERNET` for catalog/package
downloads. The application adds no analytics, tracking, account, advertising,
or upload behavior. Cleartext traffic remains disabled.

Only the compiled HTTPS catalog origin is trusted. Catalog package paths must
remain relative and resolve to the same origin. Redirects to another origin,
HTTP URLs, remote document assets, and WebView network requests are rejected.
Network calls run off the main thread with finite timeouts and cancellation.

Release verification changes from “no Internet permission” to asserting the
intended permission and the absence of unrelated sensitive permissions and
cleartext configuration.

## Package and Rendering Security

Before extraction or activation, the app enforces:

- configured compressed and uncompressed byte limits;
- an entry-count limit;
- no absolute paths, backslashes, drive prefixes, empty segments, `.`/`..`,
  case-colliding duplicates, or undeclared members;
- no symlinks or special files;
- an allowlist of expected media types/extensions;
- outer ZIP size and SHA-256 agreement with the catalog;
- inner member size and SHA-256 agreement with `package.json`;
- content-key and revision agreement across catalog, envelope, and source;
- supported schema/app-version combinations; and
- atomic extraction beneath the owned content root.

Generated fragments also pass a runtime structural audit. Script, style,
frame, form, object, base, embedded remote content, inline event handlers, and
nonlocal URLs are rejected; only the fixed renderer's inert markup and local
asset references are accepted.

Downloaded content is data, not executable code. Generated fragments are
wrapped by an app-owned HTML shell. JavaScript stays disabled. The shell CSP
permits only the minimum local styles, fonts, and images required by the
renderer, and existing navigation/resource interception remains active.

HTTPS, strict package validation, and non-executable rendering are sufficient
for the controlled first-party feed. Catalog signing is a compatible future
hardening measure, not a v1 dependency.

## Attribution and Publication Rights

Every collection and reference article resolves to explicit source,
attribution, copyright-holder when known, license/permission status, and source
URL metadata. Mathematical facts are not treated as permission to copy source
prose or diagrams. Reference explanations and examples should be written
originally and cite reliable sources.

The public publishing workflow refuses entries lacking a completed rights
status. Material may remain in development review until redistribution is
cleared. Original PDFs and TeX provenance files remain development-only and
are never placed in packages or the Pages artifact.

## Failure and Recovery Behavior

- **Catalog offline/timeout:** use the last valid or embedded catalog; Home and
  installed content remain available.
- **Invalid new catalog:** retain the previous catalog and show a retryable
  diagnostic only in Problem Library.
- **Hash/schema/archive failure:** discard staging data, retain the old active
  revision, and identify the failed item.
- **Interrupted process:** clean or resume from persisted queue/index state;
  never infer installation from leftover files.
- **Out of storage:** stop before activation where possible, remove staging,
  retain existing content, and show a storage-specific error.
- **Unsupported package:** show it in the catalog as requiring an app update.
- **Removed catalog entry:** keep any installed copy usable; never auto-delete.
- **Missing content with saved notebook:** retain the notebook and offer
  restore/reinstall instead of opening a broken workspace.
- **Bundled restore while offline:** clear the logical-removal record and use
  the embedded package.
- **Renderer integrity failure:** show the existing local integrity error,
  quarantine a downloaded package when appropriate, and do not fall back to a
  different revision unless the user restores/reinstalls it.

## Testing Strategy

### Content tooling

- JSON Schema tests for problem, reference, package-envelope, and catalog
  contracts;
- golden valid/invalid fixtures for keys, tables, relationships, attribution,
  compatibility, and unsafe paths;
- deterministic package and catalog builds;
- outer and inner digest/size verification;
- ZIP traversal, collision, undeclared-member, oversized-entry, and malformed
  archive cases;
- catalog/package consistency and immutable-path checks;
- source audit that rejects raw math in hint list-item text;
- KaTeX root-count and screenshot regression for list-item math;
- Core Handbook category inventory, links, aliases, conventions, and render
  audits; and
- publication audit proving that no originals/review files enter artifacts.

### Android unit and integration tests

- catalog parsing, validation, cache replacement, `304`, timeout, and fallback;
- URL origin and redirect policy;
- layered resolution and downloaded-over-bundled precedence;
- bundled logical delete, offline restore, downloaded update, and failed update
  rollback;
- persistent queue recovery and idempotent batch operations;
- notebook/progress preservation across delete/update/reinstall;
- missing-recent-problem routing;
- reference category/search ranking and relationship resolution;
- safe package extraction and content-resource resolution;
- backup library-state round-trip plus backward-compatible old backups; and
- fake-server tests rather than live GitHub dependencies.

### Compose/instrumented and release tests

- Home destinations for Browse Problems, Problem Library, and Math Reference;
- installed-only problem browsing;
- Problem Library filters, multi-select, confirmations, progress, offline
  states, and accessibility labels;
- reference search/article navigation from Home and inside split view;
- preservation of notebook geometry and state while browsing references;
- generated content in WebView with networking and JavaScript blocked;
- expected Internet permission, no cleartext traffic, and no unrelated
  sensitive permissions;
- clean install and in-place upgrade preserving user data;
- signed APK assembly, packaged-content audit, and SHA-256 sidecar; and
- a physical Galaxy Tab smoke test when a device is available.

## Delivery Sequence

Work proceeds in dependency order:

1. Correct hint list-item source math and add its regression tests.
2. Define the reference, package-envelope, catalog, and rich-table schemas plus
   fixtures.
3. Build deterministic per-item packaging and GitHub Pages publication tools.
4. Convert Putnam 2020–2025 into cataloged starter packages without changing
   stable problem keys.
5. Implement the installed-content index, package store/installer, catalog
   cache/client, composite repositories, and logical resource resolver.
6. Add Problem Library, Home routing, filters, manual/batch actions, and robust
   missing-content flows.
7. Add Math Reference source tooling, Home UI, offline search, article view,
   and in-workbook reference navigation.
8. Curate and review Core Handbook v1 in category batches.
9. Add backup library state, complete security/privacy/release verification,
   deploy the Pages feed, and build one verified APK.

Packaging and APK rebuilding are deferred until the integrated feature and the
currently selected content batches are ready; content curation does not require
rebuilding an APK after every article.

## Acceptance Criteria

The design is complete when the implementation can demonstrate all of the
following:

- MyPutnam launches and opens its bundled content with no network.
- Putnam 2020–2025 appears both in the catalog and installed problem browser.
- A bundled problem can be logically deleted and restored offline.
- A remote-only problem can be installed, opened offline, updated explicitly,
  and deleted without affecting its notebook/progress.
- Invalid catalogs and corrupt/interrupted packages never replace usable local
  state.
- Startup catalog refresh never blocks Home and never downloads packages.
- Problem Library supports individual, selection, and group management with
  clear offline/compatibility states.
- Every current hint list item renders mathematical runs through KaTeX, with a
  regression covering the previously raw 2025 fourth-hint expressions.
- Math Reference is searchable and browsable offline from Home and within a
  problem split view without disturbing notebook state.
- Core Handbook v1 covers all twelve approved categories and the specifically
  requested high-value formula/method families with reviewed conventions,
  examples, pitfalls, and attribution.
- Backup/restore preserves user work and can identify packages offered for
  manual reinstall without embedding package bytes.
- The publication pipeline deterministically deploys a self-consistent Pages
  artifact containing no provenance originals or executable downloaded code.
- The Android architecture exposes platform-neutral schemas/fixtures that a
  later static web client can consume without converting the content corpus.
