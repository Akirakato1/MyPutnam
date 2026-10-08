# Library Topic Search and 2020–2025 Travel Release Design

## Summary

This release turns the current Putnam-only archive into the first version of a
general offline mathematics library while keeping the visible workflow simple.
The user can continue browsing Putnam by year, or select one curated topic and
see every matching problem in the installed library. The release also finishes
the approved notebook fixes, curates the complete 2020–2025 Putnam set, and
produces one clean-install, signed APK for offline travel use.

The reusable data model is generalized now because the current `(year, code)`
identity is embedded in content, progress, notebook storage, navigation, and
backup data. Adding course chapters or non-Putnam problem sets without changing
that identity would require another cross-cutting migration later.

## Goals

- Preserve the Home menu with Continue, Select Problem, and Settings.
- Preserve the normal Putnam year-to-problem browsing flow.
- Add library-wide filtering by one curated topic at a time.
- Support problems that belong to multiple topics.
- Establish stable collection, set, and problem identities for future
  competitions, courses, and focused practice sets.
- Keep all problem content, rendering assets, topics, hints, prerequisites,
  solutions, progress, and notebook work usable without a network connection.
- Finish and verify every previously approved notebook and reference-pane fix.
- Package all 72 Putnam problems from 2020 through 2025 in one signed APK.
- Retain the longer-term 1962–2025 Putnam archive goal without requiring it for
  this release.

## Non-goals

- Text or formula search.
- Multiple simultaneous topic filters or Boolean filter expressions.
- Runtime importing, editing, or downloading of collections.
- User-created topics.
- Recommendations, rankings, adaptive study plans, or automatic classification.
- Packaging years earlier than 2020 in this release.
- Migrating development progress or notebooks from earlier APKs.

## Stable Library Identity

Every problem has a case-sensitive canonical key made of three separately
validated path-safe segments:

```text
collectionId/setId/problemId
```

For example, Putnam 2023 A4 is `putnam/2023/A4`.

- `collectionId` identifies the source or curriculum, such as `putnam`.
- `setId` identifies an ordered subdivision, such as a competition year,
  course chapter, or practice level.
- `problemId` identifies a problem within its set, such as `A4`.

IDs may contain ASCII letters, digits, periods, underscores, and hyphens; they
may not contain slashes, whitespace, traversal segments, or URI syntax. The
canonical key is unique across the packaged library and is the only identity
used by navigation, progress, notebook storage, export, and backup code.

Putnam display metadata remains explicit: the collection title identifies the
William Lowell Putnam Mathematical Competition, each set exposes its four-digit
year, and each problem exposes its familiar A1–B6 label and ordinal. Generic
identity does not make the Putnam UI less recognizable.

## Source Schema Version 3

Schema version 3 keeps the existing safe rich-document, staged-hint,
prerequisite, solution, and source structures. It replaces the Putnam-specific
top-level `year` and restricted `code` identity with:

- `schemaVersion`, fixed at `3`;
- `collectionId`, `setId`, and `problemId`;
- `label`, the concise user-facing identifier, such as `A1`;
- `ordinal`, unique within the collection and set;
- `topics`, a nonempty array of unique controlled topic IDs;
- `statement`;
- exactly four ordered hints with stages `nudge`, `strategy`, `knowledge`, and
  `outline`;
- one or more ordered prerequisite refreshers;
- `solution`;
- one or more HTTPS source citations.

Collection and set metadata live in a library catalog rather than being copied
into every problem. A set includes its title, display order, and optional typed
metadata such as a competition year. This lets future course sets use chapter
or unit labels without pretending that every set has a year.

The content validator rejects malformed IDs, unknown collections or sets,
unknown or duplicate topics, duplicate ordinals, missing prerequisites,
incorrect hint progression, unsafe TeX, unsafe figure paths, and invalid source
metadata. All existing 2025 records are mechanically migrated to version 3
before their topic metadata is curated.

## Controlled Topic Catalog

The first release uses a flat, curated catalog with stable machine IDs,
human-readable labels, and display order. It contains:

- `algebra` — Algebra
- `analysis` — Analysis
- `calculus` — Calculus
- `combinatorics` — Combinatorics
- `functional-equations` — Functional Equations
- `geometry` — Geometry
- `inequalities` — Inequalities
- `integrals` — Integrals
- `linear-algebra` — Linear Algebra
- `number-theory` — Number Theory
- `polynomials` — Polynomials
- `probability` — Probability
- `sequences-series` — Sequences & Series

A problem may have several topics. For example, an integral inequality may be
tagged `integrals`, `analysis`, and `inequalities`. Tags describe substantial
content, not incidental notation. Topic assignment is part of mathematical
review and is never generated at runtime. The catalog may gain new stable IDs
in later releases without changing existing problem keys.

## Runtime Content Database

The immutable prepackaged content database is rebuilt with normalized tables:

- `collection`: ID, title, kind, and display order;
- `problem_set`: collection ID, set ID, title, display order, and optional
  metadata;
- `topic`: topic ID, label, and display order;
- `problem`: canonical key, collection/set/problem IDs, label, ordinal,
  rendered statement and solution assets, hashes, and attribution;
- `problem_topic`: canonical problem key and topic ID;
- `hint` and `prerequisite`: canonical problem key plus their existing ordered
  content fields;
- `content_meta`: schema, content, release, count, and integrity metadata.

Foreign keys enforce collection, set, problem, and topic membership. A unique
constraint protects `(collection_id, set_id, ordinal)`, and a second protects
`(collection_id, set_id, problem_id)`. Repository queries provide ordered
collections and sets, problems within one set, all topics, all problems for one
topic, and one complete problem by canonical key.

The domain `ProblemKey` changes to the three stable IDs. User progress rows,
Continue state, notebook directories, PDF export names, and backup manifests
use the canonical key. Because this is a clean-install development release,
there is no user-database or notebook migration. Delivery instructions will
state that the previous development build must be uninstalled before
installation.

## Library Browser Interaction

Home remains unchanged. Select Problem opens a library browser with a
horizontally scrollable single-choice topic row above its content.

The row begins with All, followed by the controlled topics in catalog order.
Only one choice can be active. Tapping a topic selects it; tapping the active
topic again selects All. No keyboard or text field is shown.

With All selected, the screen groups installed sets under their collection.
For this release that is the Putnam collection followed by the years 2025 down
through 2020. Selecting a year opens its ordered twelve-problem list, preserving
the existing browse workflow.

With a topic selected, the screen shows every matching installed problem,
grouped first by collection and then by set. A row shows collection/set context,
the problem label, and current progress. Selecting a row opens the same split
reference/notebook workspace. A filter with no installed matches shows `No
installed problems match this topic` and leaves the topic row usable.

Filter state belongs to the library view model and survives navigation between
the library root and a set's problem list during that app session. It is not a
general setting and is not backed up.

## Notebook and Reference Requirements

The release includes every already approved fix and feature:

- completed strokes are saved through the debounced save coordinator rather
  than triggering blocking storage work for every pointer event;
- rapid strokes remain present and ordered;
- finished live ink remains visible until the committed Compose stroke has
  rendered, preventing the one-frame disappearance between strokes;
- one Pen mode / Select mode toggle replaces separate mode buttons;
- Erase last and selection Cancel controls are absent;
- tapping outside a selection clears it;
- a marquee includes every stroke it intersects, even partially;
- selected strokes can move, scale, cut, copy, paste, and delete;
- eight quick colors and a 1–12 width control live in the notebook toolbar and
  the last brush is remembered;
- brush controls are absent from general Settings;
- page shifting and numbered-page navigation are absent;
- previous/next page buttons and a top-right page index are present;
- the bottom-right input-blocking gray artifact is absent;
- hint, prerequisite, statement, and solution mathematics use the same offline
  rendered pipeline;
- reference navigation controls remain visible while content loads, scrolls,
  and changes.

The input handoff uses two phases: a finished platform ink stroke enters a
pending-render set when converted to the immutable notebook model, and the
platform stroke is removed only on the next animation frame after its model ID
appears in the rendered Compose stroke set. Cancellation and untracked platform
ink remain independently removable.

## 2020–2025 Content Curation

The release contains twelve reviewed problems for each year 2020, 2021, 2022,
2023, 2024, and 2025. The 60 new 2020–2024 records receive the same treatment as
the existing 2025 set; all 72 are normalized to schema version 3.

Each record must pass:

1. locked-source verification with URL, retrieval date, byte length, SHA-256,
   and page count where applicable;
2. statement transcription comparison against the cited source;
3. mathematical review of the full solution;
4. review that the four hints form a genuine nudge, strategy, knowledge bridge,
   and proof outline without merely repeating the solution;
5. prerequisite usefulness and correctness review;
6. topic assignment review;
7. schema and KaTeX validation;
8. generated-asset hash and offline dependency validation;
9. screenshot review for overflow, missing glyphs, and malformed formulas;
10. reusable year audit with all twelve expected IDs and ordinals.

Official MAA materials are preferred. When an official historical artifact is
not available, an established Putnam archive or mirror may be used with its
exact provenance recorded. Development-only source PDFs are not packaged in
the APK.

## Progressive Release Packaging

The long-term archive catalog continues to track the 1962–2025 objective and
which years have completed curation. A separate explicit release manifest lists
only the collection/set keys included in a particular APK. The travel release
manifest contains exactly `putnam/2020` through `putnam/2025`.

The archive builder accepts a release manifest only when every listed set is
complete and audited. It rejects missing or extra manifests, partial sets,
duplicate keys, unreviewed problems, and asset/database count mismatches. This
allows safe progressive APKs without falsely declaring the long-term archive
complete.

The travel release advances the app to version name `0.4.0` and version code
`4`. It is built once after all application and content gates pass. The release
uses the existing local signing configuration; signing secrets remain outside
version control.

## Error Handling

- Schema, topic, source, render, review, or package errors fail the desktop
  build with an exact record path and reason.
- The application never silently drops an unknown topic or broken problem.
- A content database open or query failure produces an in-app error state rather
  than an empty-success state.
- If the in-memory selected topic is absent from the loaded catalog, the
  library browser falls back to All.
- A missing or hash-mismatched content asset shows the existing integrity error
  and does not attempt a network fallback.
- Notebook save failures remain visible and never report success prematurely.

## Testing and Verification

### Deterministic content tooling

- JSON-schema and semantic-validator tests cover version 3, generic IDs,
  controlled topics, collection/set membership, and all existing rich-content
  safety rules.
- Builder tests cover normalized topic rows, generic keys, foreign keys,
  deterministic output, progressive release subsets, and exact metadata counts.
- Every year audit and the composite 2020–2025 release audit must pass.
- Generated HTML is checked for local styles/fonts, pre-rendered KaTeX, blocked
  network dependencies, overflow, and missing glyphs.

### Android data and UI

- Repository/database tests cover collection and set ordering, problems by set,
  all topics, library-wide problems by topic, complete problem loading, and
  malformed database rejection.
- View-model tests cover All, one selected topic, toggling back to All, progress
  joins, empty results, stale-load cancellation, and query errors.
- Compose tests cover topic chips, year browsing, grouped topic results, empty
  states, correct problem navigation, and progress labels.
- Existing workspace, content-integrity, backup, export, and no-Internet tests
  are migrated to generic keys.
- Notebook unit and Compose regression tests cover save coalescing, rapid-stroke
  retention, two-phase ink handoff, mode toggling, outside-tap deselection,
  partial-intersection selection, transforms, clipboard actions, brush controls,
  page controls, and input across the complete canvas.

### Release artifact

- Run the complete Node, Python, Android unit, Android compile, lint, and
  available instrumentation suites from a clean build.
- Inspect the packaged manifest to confirm that `android.permission.INTERNET`
  is absent.
- Inspect the packaged content database for exactly six sets, 72 problems, 288
  hints, at least 72 prerequisites, valid topic joins, and years 2020–2025 only.
- Inspect the APK contents to confirm all referenced local assets are present and
  development PDFs are absent.
- Verify the APK signature and publish its SHA-256 digest beside the artifact.
- If an Android device or emulator is attached, perform install/launch and a
  smoke path through topic selection, workspace ink, hints, page navigation, and
  restart persistence. Otherwise the user performs the final tablet smoke test
  after sideloading.

## Acceptance Criteria

The work is complete when a clean install of the signed 0.4.0 APK operates with
airplane-mode-equivalent network isolation, exposes exactly the six requested
Putnam years, filters all installed problems by one selected curated topic,
opens the correct split-view workbook, renders all mathematical material
correctly, passes the notebook regression gates, and ships with a verified
SHA-256 checksum. The architecture must admit a future collection or course set
by adding catalog metadata and schema-v3 records, without changing problem
identity, progress, notebook, navigation, or search interfaces.
