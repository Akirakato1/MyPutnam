# 2018 Putnam source set

This directory locks the official 2018 William Lowell Putnam Mathematical Competition problem paper and problem-and-solution document published by the Mathematical Association of America (MAA). It also locks Kiran Kedlaya's TeX transcriptions as secondary cross-checks. `sources.json` records the exact request and final URLs, retrieval date, byte length, media type, parsed PDF page count, and SHA-256 digest.

The locked copies are development-only provenance inputs. They are not packaged as runtime assets in MyPutnam. Copyright remains with the MAA and the respective authors. This repository does not grant permission to redistribute or commercially reuse the source documents.

Do not silently replace a locked file. If a publisher updates one, inspect the change and update its metadata in the same reviewed change. The standalone MAA problem paper controls statement fidelity. The MAA problem-and-solution document controls official solution fidelity. The TeX files are cross-checks, not silent substitutes.

The authored problem records will use schema version 3 with canonical keys `putnam/2018/{A1..B6}` and reviewed controlled topics. The canonical schema, field semantics, fixed four-stage hint progression, and reusable starter file are documented in `../../tools/content/SCHEMA.md` and `../../tools/content/templates/problem.template.json`.

The current checkpoint locks the complete source set and contains reviewed records for A1 through A6. Validate the checkpoint from the repository root with:

```text
node --test tools/content/test/2018-sources.test.mjs tools/content/test/2018-content.test.mjs
```

To reproduce the A1–A6 render review, run:

```text
node tools/content/src/review-year.mjs --year 2018 --source content-src/2018 --preview-output build/content-preview/2018-A1-A6 --review-output build/content-review/2018-A1-A6 --catalog content-src/library.json --problems A1,A2,A3,A4,A5,A6 --widths 480,720 --preview
```

B1 through B6 remain to be curated. The archive must not record 2018 as complete until all twelve records pass the strict whole-year audit.
