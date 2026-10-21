# 2019 Putnam source set

This directory locks the official 2019 William Lowell Putnam Mathematical Competition problem paper and problem-and-solution document published by the Mathematical Association of America (MAA). It also locks Kiran Kedlaya's TeX transcriptions as secondary cross-checks. `sources.json` records the exact request and final URLs, retrieval date, byte length, media type, parsed PDF page count, and SHA-256 digest.

The locked copies are development-only provenance inputs. They are not packaged as runtime assets in MyPutnam. Copyright remains with the MAA and the respective authors. This repository does not grant permission to redistribute or commercially reuse the source documents.

Do not silently replace a locked file. If a publisher updates one, inspect the change and update its metadata in the same reviewed change. The standalone MAA problem paper controls statement fidelity. The MAA problem-and-solution document controls official solution fidelity. The TeX files are cross-checks, not silent substitutes.

The authored problem records in `problems/` use schema version 3 with canonical keys `putnam/2019/{A1..B6}` and reviewed controlled topics. The canonical schema, field semantics, fixed four-stage hint progression, and reusable starter file are documented in `../../tools/content/SCHEMA.md` and `../../tools/content/templates/problem.template.json`.

The current checked-in curation checkpoint covers A1–A3. Validate it from the repository root with:

```text
node --test tools/content/test/2019-content.test.mjs tools/content/test/2019-sources.test.mjs
node tools/content/src/review-year.mjs --year 2019 --source content-src/2019 --catalog content-src/library.json --problems A1,A2,A3 --widths 480,720 --preview
```

Inspect every generated page at both 480 and 720 CSS pixels before accepting new problems into the checkpoint. Once all twelve records are complete, replace the subset audit with the strict whole-year audit.
