# Putnam problem source schema v2

Each problem is authored as one JSON record validated by
`schema/problem.schema.json`. Start from `templates/problem.template.json` and
run `npm --prefix tools/content test` before building a year.

## Top-level fields

- `schemaVersion`: always `2`.
- `year`: competition year, from 1938 through the schema's supported upper
  bound.
- `code`: `A1`–`A6` or `B1`–`B6`.
- `ordinal`: display order from 1 through 12 within the year.
- `statement`: the complete problem as a nonempty rich document.
- `hints`: exactly four hint objects in the fixed progression below.
- `prerequisites`: zero or more prerequisite refreshers, ordered from 1.
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
