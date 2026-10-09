# Offline mathematics problem source schema v3

Each problem is authored as one JSON record validated by
`schema/problem.schema.json`. Start from `templates/problem.template.json` and
run `npm --prefix tools/content test` before building a set.

## Top-level fields

- `schemaVersion`: always `3`.
- `collectionId`, `setId`, and `problemId`: exact case-sensitive path-safe ID
  segments. Their canonical identity is `collectionId/setId/problemId`.
- `label`: concise display label, independent of the problem ID.
- `ordinal`: positive display order within the collection and set.
- `topics`: one or more unique IDs from `content-src/library.json`.
- `statement`: the complete problem as a nonempty rich document.
- `hints`: exactly four hint objects in the fixed progression below.
- `prerequisites`: one or more prerequisite refreshers, ordered from 1.
- `solution`: a complete explanatory solution as a nonempty rich document.
- `sources`: one or more HTTPS citations with `kind`, `label`, and `url`.

`originalPdf` is intentionally absent. Locked official PDFs remain
development-only provenance under `content-src`; they are not app content.

## Hint progression

Every hint has `order`, `stage`, `title`, and `document`. Both order and stage
are fixed:

1. `nudge`: a non-spoiling observation or question.
2. `strategy`: the principal technique or useful reduction.
3. `knowledge`: a theorem, identity, or explanation that bridges a knowledge
   gap.
4. `outline`: an ordered proof plan that stops short of duplicating the full
   solution.

Stages may not be omitted, duplicated, or rearranged.

## Rich documents

A rich document contains a nonempty `blocks` array. Supported blocks are:

- `heading`: `level` 1–4 and inline `runs`;
- `paragraph`: inline `runs`;
- `displayMath`: a `tex` string;
- `list`: an `ordered` flag and one or more items, each expressed as inline
  `runs`;
- `figure`: a normalized problem-local `path`, nonempty `alt`, and optional
  inline `caption` runs.

An inline run is either `{ "type": "text", "text": "..." }` or
`{ "type": "inlineMath", "tex": "..." }`. Math is validated everywhere,
including list items and figure captions.

Figure paths must be relative, normalized, use `/`, and remain beneath the
problem directory. Remote URLs, absolute paths, and `..` traversal are
rejected. Raw or active HTML is not part of the schema. TeX commands that can
load, link, identify, class, or style external content are rejected; KaTeX is
run with `trust: false` and strict errors.

## Prerequisites and sources

Each prerequisite has sequential `order`, a nonempty `title`, and a rich
`document`. Sources have a `kind` of `problem`, `solution`, or `reference`, a
human-readable `label`, and an HTTPS `url`.

## Year source and review contract

Each competition year lives beneath `content-src/<year>/` and contains exactly
the twelve records `A1.json` through `A6.json` and `B1.json` through `B6.json`.
Their ordinals are 1 through 12 in that order. `sources.json` byte-locks every
provenance PDF or TeX file with its HTTPS URL, retrieval date, byte length,
SHA-256 digest, and optional PDF page count. These locked originals remain
development-only and are never runtime assets.

Each `review/<code>.md` records statement fidelity, mathematical review, render
review, topic review, the exact local problem and solution sources checked, and explicit
separation between the four hint stages. A referenced local figure must exist
beneath that problem's directory and have its own completed figure-review line.

Run a reusable year audit with:

```text
node tools/content/src/audit-year.mjs --year <year>
```

Generate disposable local preview HTML and screenshots with:

```text
node tools/content/src/review-year.mjs --year <year>
```

The CLI writes only beneath ignored `build/content-preview/<year>/` and
`build/content-review/<year>/` defaults. It refuses Android `app/` output.

## Archive catalog and final integration

`content-src/archive.json` is the tracked archive catalog. `targetYears`
contains every year from 1962 through 2025, while `completedYears` contains
only years that passed the source, schema, mathematical, hint, prerequisite,
and rendering gates. The final archive builder rejects the catalog unless
those arrays are identical.

The deterministic asset layout contains `content/shared/` for KaTeX
fonts/styles and `content/<collection>/<set>/manifest.json` plus each problem
tree. Manifest asset paths are already relative to the Android asset root. Archive database
metadata includes the global content version, sorted included years, range,
year and problem counts, a catalog SHA-256, and a composite SHA-256 over the
catalog plus sorted manifests.

The archive CLI requires explicit source, output, review, catalog, and database
paths. It has no Android-output default. Its one-time invocation is reserved
until every target year is complete.
