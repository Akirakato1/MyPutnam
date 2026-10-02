# Historical Putnam Content Expansion Design

## Purpose

Expand Putnam Workbook from its 2025 vertical slice into a complete offline
practice archive covering every competition year from 1962 through 2025. The
archive must retain the existing problem-first workflow: choose a year, choose
a problem, read properly rendered mathematics, reveal progressive help, and
work in the integrated notebook.

Development builds are disposable. Each release in this expansion is a fresh
APK with a freshly generated immutable content database. No work will be spent
converting notebooks or preserving app data from earlier development APKs.

## Scope and batch sequence

Content is added cumulatively, newest first, in five-calendar-year batches:

1. 2020–2024, added to the existing 2025 content
2. 2015–2019
3. 2010–2014
4. 2005–2009
5. 2000–2004
6. 1995–1999
7. 1990–1994
8. 1985–1989
9. 1980–1984
10. 1975–1979
11. 1970–1974
12. 1965–1969
13. 1962–1964

The completed archive contains 64 competition years and 768 problems. A batch
is not added to the app catalog until every year and problem in that batch has
passed the content and rendering gates below. The first deliverable therefore
contains 2020–2025: six years and 72 problems.

Years before 1962 are out of scope because they predate the modern twelve
problem A1–A6/B1–B6 format.

## Source policy

Use the Mathematical Association of America archive as the preferred official
source for problem papers and published solutions:

- <https://maa.org/maa-putnam-archive/>

Use Kiran Kedlaya's Putnam Archive as the preferred structured TeX source and
as an independent solution cross-check:

- <https://kskedlaya.org/putnam-archive/>

The rpxgit mirror may be used only as a reproducible fallback for source files
that match the Kedlaya archive:

- <https://github.com/rpxgit/The-Putnam-Archive>

Each `content-src/<year>/sources.json` records the exact download URL,
retrieval date, byte length, page count when applicable, and SHA-256 digest.
Locked PDFs and TeX files are development provenance only and are never
packaged in the APK.

Problem statements must be faithful transcriptions. Solutions are explanatory
rewrites checked against at least one published solution rather than
unreviewed verbatim copies. Every problem record cites its problem and solution
sources.

## Canonical problem record

Schema v2 remains the canonical authoring format. Each year has twelve JSON
records named `A1.json` through `A6.json` and `B1.json` through `B6.json`.
Every record contains:

- the complete statement as safe rich-document blocks;
- exactly four hints in the existing `nudge`, `strategy`, `knowledge`, and
  `outline` progression;
- at least one prerequisite refresher explaining a formula, theorem,
  definition, or technique relevant to the problem;
- a complete explanatory solution;
- HTTPS source citations;
- any required local figures beneath that problem's source directory.

The schema continues to prohibit remote runtime assets, raw HTML, active
scripts, unsafe TeX, absolute paths, and path traversal. KaTeX renders all math
at build time using the bundled local fonts and stylesheet.

Hints must provide genuinely increasing help. The nudge must not reveal the
main method; the strategy names the useful reduction; the knowledge stage
bridges the most likely technical gap; and the outline supplies a proof plan
without duplicating the final solution.

## Multi-year content pipeline

Per-year authoring and rendering remain isolated:

- `content-src/<year>/` contains provenance, records, figures, and review
  evidence.
- `app/src/main/assets/content/<year>/` contains that year's generated,
  script-free HTML, KaTeX assets, figures, and manifest.
- A tracked archive catalog lists the included years and the global content
  version.

The existing year builder continues to build and review one year at a time. A
new archive-level build command reads the catalog, renders its years in sorted
order, and rejects missing, duplicate, or out-of-catalog years.

The content database builder accepts all catalog manifests and writes one
deterministic Room database. It inserts years in ascending order and problems
by ordinal. Composite `(year, code)` keys remain unchanged, so no Room schema
change is required. Global metadata records:

- content schema version;
- archive content version;
- sorted included-year list;
- minimum and maximum year;
- total year and problem counts;
- a digest of the canonical catalog and all included manifests.

The archive content version uses
`archive-<minimum-year>-2025.<revision>`, beginning with
`archive-2020-2025.1`. Rebuilding identical inputs must produce identical
manifests, rendered assets, logical database contents, and catalog digest.

## Application behavior

The existing archive screens already query distinct years and then the twelve
problems for a selected year. They remain the user-facing model and require no
layout redesign. The first batch must display years in descending order from
2025 through 2020 and show A1–A6 followed by B1–B6 for each year.

All statements, prerequisites, hints, and solutions load from packaged local
assets while the device is offline. The app continues to declare no internet
permission. Progress and notebook storage remain keyed by `(year, code)` so
different years cannot collide.

Settings displays the global archive content version. Release verification
checks the expected year range, exact year count, exact problem count, schema
version, absence of original PDFs, and absence of internet permission.

## Fresh-install development policy

Every content batch produces a new APK and is tested as a clean installation.
The prior development APK may be uninstalled or its data cleared before
sideloading. There is no upgrade rehearsal and no requirement to retain
notebooks, progress, settings, or backups across development versions.

No new notebook or user-database migration code will be written for this
expansion. Existing defensive migration code may remain untouched, but the
content work must not depend on it. The packaged immutable content database is
always rebuilt in full from the catalog.

## Review evidence and quality gates

Each problem has a review record that confirms:

1. statement text and notation match the locked source;
2. the final answer and proof route agree with a published solution;
3. every TeX expression renders successfully;
4. the four hints increase in specificity without prematurely copying the
   solution;
5. prerequisite material is correct and directly useful;
6. figures, lists, and displayed equations remain legible on the tablet-sized
   reference pane;
7. source citations are complete.

Automated gates cover:

- schema and unsafe-content validation for every source record;
- exactly twelve unique A/B codes and ordinals per included year;
- exactly four ordered hint stages and at least one prerequisite per problem;
- source-lock hashes and complete review evidence;
- deterministic per-year and archive builds;
- cross-year duplicate and manifest/catalog mismatch rejection;
- SQLite foreign-key and integrity checks;
- database year/problem counts and ordered queries;
- local resolution of every generated HTML, stylesheet, font, and figure;
- Android archive navigation and representative offline rendering from the
  newest and oldest included years;
- release APK privacy, version, content range, and asset-exclusion checks.

## Batch completion and releases

A batch is complete only when all records are authored, review evidence is
present, automated gates pass, generated pages have been visually inspected,
the clean-install Android smoke test passes, and a signed APK has been
verified. Partially authored years remain outside the tracked archive catalog
and therefore cannot leak into release builds.

The first batch produces a fresh v0.4.0 APK named
`putnam-workbook-2020-2025-v0.4.0.apk`. Later batches follow the same process
with a new app version and a filename describing the cumulative minimum and
maximum year.

## Explicit non-goals

- Problems before 1962
- Importing arbitrary user-authored problem packs
- Runtime downloads or online synchronization
- Notebook or user-data migration between development APKs
- Redesigning the established year/problem/workbook navigation
- Packaging original source PDFs or TeX files
