# 2025 Putnam source set

This directory locks the official 2025 William Lowell Putnam Mathematical Competition problem paper and solution set published by the Mathematical Association of America (MAA). `sources.json` records the exact origin, retrieval date, byte length, page count, and SHA-256 digest of each file.

The locked copies are development-only provenance inputs. They are not packaged as runtime assets in the Putnam Workbook app. Copyright remains with the MAA and the respective authors. This repository does not grant permission to redistribute or commercially reuse the source PDFs.

Do not silently replace either PDF. If the MAA updates a file, inspect the change and update its locked metadata in the same reviewed change.

The authored problem records in `problems/` use schema version 2 and build to immutable content version `2025.2`. The canonical schema, field semantics, fixed four-stage hint progression, and reusable starter file are documented in `../../tools/content/SCHEMA.md` and `../../tools/content/templates/problem.template.json`.

From the repository root, validate and build this year with:

```text
npm --prefix tools/content test
node tools/content/src/build-year.mjs --year 2025 --content-version 2025.2
python tools/content/src/build-content-db.py --manifest app/src/main/assets/content/2025/manifest.json --output app/src/main/assets/databases/content.db
```

Inspect every generated page and the review summary in `build/content-review/2025/` before accepting the packaged HTML and database.
