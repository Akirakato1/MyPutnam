# 2023 Putnam source set

This directory locks the official 2023 William Lowell Putnam Mathematical Competition problem paper and official problem-and-solution set published by the Mathematical Association of America (MAA). It also locks Kiran Kedlaya's TeX transcriptions as secondary cross-checks. `sources.json` records the exact request and final URLs, retrieval date, byte length, media type, parsed PDF page count, and SHA-256 digest.

The locked copies are development-only provenance inputs. They are not packaged as runtime assets in the Putnam Workbook app. Copyright remains with the MAA and the respective authors. This repository does not grant permission to redistribute or commercially reuse the source documents.

Do not silently replace a locked file. If a publisher updates one, inspect the change and update its metadata in the same reviewed change. The official MAA PDFs control statement and solution fidelity; the TeX files are cross-checks, not silent substitutes.

The authored problem records in `problems/` use schema version 3 with canonical keys `putnam/2023/{A1..B6}` and reviewed controlled topics. The canonical schema, field semantics, fixed four-stage hint progression, and reusable starter file are documented in `../../tools/content/SCHEMA.md` and `../../tools/content/templates/problem.template.json`.

From the repository root, validate this year with:

```text
node tools/content/src/audit-year.mjs --year 2023 --catalog content-src/library.json
node tools/content/src/review-year.mjs --year 2023 --catalog content-src/library.json
```

Inspect every generated page at both 480 and 720 CSS pixels before accepting the content for packaging.
