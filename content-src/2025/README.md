# 2025 Putnam source set

This directory locks the official 2025 William Lowell Putnam Mathematical Competition problem paper and solution set published by the Mathematical Association of America (MAA). `sources.json` records the exact origin, retrieval date, byte length, page count, and SHA-256 digest of each file.

The locked copies are development-only provenance inputs. They are not packaged as runtime assets in the Putnam Workbook app. Copyright remains with the MAA and the respective authors. This repository does not grant permission to redistribute or commercially reuse the source PDFs.

Do not silently replace either PDF. If the MAA updates a file, inspect the change and update its locked metadata in the same reviewed change.

The authored problem records in `problems/` use schema version 3 with canonical keys `putnam/2025/{A1..B6}` and reviewed controlled topics. The canonical schema, field semantics, fixed four-stage hint progression, and reusable starter file are documented in `../../tools/content/SCHEMA.md` and `../../tools/content/templates/problem.template.json`.

From the repository root, validate and build this year with:

```text
npm --prefix tools/content test
node tools/content/src/build-set.mjs --source content-src/2025 --asset-root build/schema-v3-2025/app-assets --catalog content-src/library.json --collection putnam --set 2025 --content-version putnam-2025.dev1
```

Inspect every generated page and the review summary in `build/content-review/2025/` before accepting the packaged HTML and database.
