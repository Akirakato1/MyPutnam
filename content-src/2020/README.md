# 2020 Putnam source set

This directory locks two distinct official 2020 William Lowell Putnam Mathematical Competition artifacts published by the Mathematical Association of America (MAA): the standalone two-page problem paper and the twenty-page problem-and-solution document. It also locks Kiran Kedlaya's TeX transcriptions as secondary cross-checks. `sources.json` records the exact request and final URLs, retrieval date, byte length, media type, parsed PDF page count, and SHA-256 digest.

The 2020 competition was postponed and administered on February 20, 2021. Its competition label, set ID, display title, `competitionYear`, and canonical problem keys remain 2020; the catalog records the actual sitting separately as `administrationYear: 2021`. This distinction must not create a `putnam/2021/...` alias or duplicate.

The locked copies are development-only provenance inputs. They are not packaged as runtime assets in the Putnam Workbook app. Copyright remains with the MAA and the respective authors. This repository does not grant permission to redistribute or commercially reuse the source documents.

Do not silently replace a locked file. If a publisher updates one, inspect the change and update its metadata in the same reviewed change. The standalone MAA problem paper controls statement fidelity. The MAA problem-and-solution document controls official solution fidelity. The TeX files are cross-checks, not silent substitutes.

The authored problem records in `problems/` use schema version 3 with canonical keys `putnam/2020/{A1..B6}` and reviewed controlled topics. The canonical schema, field semantics, fixed four-stage hint progression, and reusable starter file are documented in `../../tools/content/SCHEMA.md` and `../../tools/content/templates/problem.template.json`.

From the repository root, validate this year with:

```text
node tools/content/src/audit-year.mjs --year 2020 --catalog content-src/library.json
node tools/content/src/review-year.mjs --year 2020 --catalog content-src/library.json
```

Inspect every generated page at both 480 and 720 CSS pixels before accepting the content for packaging.
