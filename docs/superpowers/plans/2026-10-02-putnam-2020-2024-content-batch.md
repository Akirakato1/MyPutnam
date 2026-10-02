# Putnam 2020–2024 Content Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Curate, validate, and locally review all sixty Putnam problems from 2020 through 2024 without modifying packaged Android content or building an APK.

**Architecture:** Add reusable archive-catalog, source-lock, year-audit, local-review, and multi-year database tooling first, tested only against fixtures, temporary directories, and the existing 2025 records. Then curate one complete year at a time into schema-v2 source records. Mark 2020–2024 complete in the catalog only after all source, mathematical, hint, prerequisite, and rendering reviews pass.

**Tech Stack:** Node.js 22, JSON Schema/Ajv, KaTeX, Playwright review rendering, Python 3/SQLite, native Android project left untouched during this plan.

**Spec:** `docs/superpowers/specs/2026-10-02-historical-putnam-content-expansion-design.md`

## Global Constraints

- The historical scope is 1962–2025; this plan implements only the first local batch, 2020–2024.
- `content-src/<year>/problems/` contains exactly A1–A6 and B1–B6 with ordinals 1–12.
- Every problem uses schema version 2, exactly four ordered hint stages, at least one prerequisite, a complete explanatory solution, and HTTPS citations.
- Statements are faithful transcriptions; solutions are independently explained and checked against published solutions.
- Official MAA PDFs are primary sources; Kedlaya TeX/solutions are structured transcription and independent cross-check sources.
- Source files are development provenance and never become runtime assets.
- All year review output stays beneath ignored `build/content-preview/` and `build/content-review/` directories.
- Do not modify `app/src/main/assets/content/`, `app/src/main/assets/databases/content.db`, Android version metadata, or `dist/` during this plan.
- Do not invoke Gradle, assemble an APK, install an APK, or perform migration/upgrade testing during this plan.
- No subagent may author or mathematically review problem content; curation stays in the primary high-reasoning session for consistency.
- Do not add `Co-Authored-By` trailers to commits.

## Review Focus

- A source URL can silently change bytes: Tasks 4–8 lock every downloaded file by byte length and SHA-256 and test the lock before curation is accepted.
- A plausible explanation can still contain a mathematical gap: Tasks 4–8 require a recorded source cross-check and an explicit proof-route review for every solution.
- A hint can spoil the solution too early: Tasks 4–8 require each review note to explain the separation between nudge, strategy, knowledge, outline, and full solution.
- Valid TeX can still render poorly on a tablet: Tasks 4–8 generate and visually inspect local review pages for every statement, hint, prerequisite, solution, and figure.
- The postponed 2020 competition was held in 2021: Task 8 pins its archive key and record year to `2020`, matching the competition label rather than the administration date.

---

### Task 1: Add the archive catalog contract

**Files:**
- Create: `content-src/archive.json`
- Create: `tools/content/schema/archive-catalog.schema.json`
- Create: `tools/content/src/archive-catalog.mjs`
- Create: `tools/content/test/archive-catalog.test.mjs`

**Interfaces:**
- Produces: `validateArchiveCatalog(value): string[]`
- Produces: `loadArchiveCatalog(path): Promise<ArchiveCatalog>`
- Produces: `assertFinalArchiveComplete(catalog): void`
- `ArchiveCatalog` contains `schemaVersion`, `contentSchemaVersion`, `contentVersion`, `targetYears`, and `completedYears`.

- [ ] **Step 1: Write failing catalog tests**

Assert that the canonical target is every integer from 1962 through 2025, the initial completed set is `[2025]`, the content version is `archive-1962-2025.1`, years are unique and ascending, completed years are a subset of target years, and final-build validation rejects the incomplete catalog.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `node --test tools/content/test/archive-catalog.test.mjs`

Expected: FAIL because the catalog module and file do not exist.

- [ ] **Step 3: Implement the catalog schema, module, and initial catalog**

Use Ajv strict validation. `assertFinalArchiveComplete` must reject any catalog whose completed-year set is not exactly its target-year set; there is no production `allowIncomplete` escape hatch.

- [ ] **Step 4: Run focused and full Node tests**

Run: `node --test tools/content/test/archive-catalog.test.mjs`

Run: `npm --prefix tools/content test`

Expected: PASS.

- [ ] **Step 5: Commit**

```text
git add content-src/archive.json tools/content/schema/archive-catalog.schema.json tools/content/src/archive-catalog.mjs tools/content/test/archive-catalog.test.mjs
git commit -m "feat: define historical archive catalog"
```

### Task 2: Generalize source locks and year audits

**Files:**
- Create: `tools/content/src/audit-source-locks.mjs`
- Create: `tools/content/src/audit-year.mjs`
- Create: `tools/content/src/review-year.mjs`
- Create: `tools/content/test/audit-source-locks.test.mjs`
- Create: `tools/content/test/audit-year.test.mjs`
- Create: `tools/content/test/review-year.test.mjs`
- Modify: `tools/content/test/2025-sources.test.mjs`
- Modify: `tools/content/test/2025-content.test.mjs`
- Modify: `tools/content/package.json`

**Interfaces:**
- Produces: `auditSourceLocks(sourceRoot): Promise<string[]>`
- Produces: `auditYear(sourceRoot, expectedYear): Promise<string[]>`
- Produces: `reviewYear({ year, sourceRoot, previewRoot, reviewRoot }): Promise<void>`
- Produces CLI: `node tools/content/src/audit-year.mjs --year <year>`
- Produces CLI: `node tools/content/src/review-year.mjs --year <year>` with outputs fixed beneath ignored `build/content-preview/` and `build/content-review/`.
- Consumes: schema-v2 `validateProblem(record)` and canonical A1–B6 code/ordinal mapping.

- [ ] **Step 1: Write failing source-lock tests**

Cover correct PDF and TeX locks, changed bytes, wrong byte length, wrong SHA-256, missing file, duplicate source ID, unsafe file path, optional PDF page count, and rejection of `http://` citations.

- [ ] **Step 2: Write failing year-audit tests**

Cover a valid twelve-problem year and failures for a missing code, extra JSON file, wrong year, wrong ordinal, fewer than four hints, missing prerequisite, thin solution, absent review note, incomplete review check, missing source citation, and an unverified local figure.

- [ ] **Step 3: Write failing local-review safety tests**

Assert that review output is created only under caller-supplied temporary roots, and that the CLI refuses any preview or review path beneath `app/`.

- [ ] **Step 4: Run the focused tests and verify RED**

Run: `node --test tools/content/test/audit-source-locks.test.mjs tools/content/test/audit-year.test.mjs tools/content/test/review-year.test.mjs`

Expected: FAIL because the audit modules do not exist.

- [ ] **Step 5: Implement the reusable audits, safe local-review wrapper, and replace duplicated 2025 assertions**

Return sorted, path-qualified error strings rather than stopping at the first error. Keep year-specific expected source URLs and page mappings in `content-src/<year>/sources.json` and review notes, not in JavaScript test code.

- [ ] **Step 6: Add `audit-year` and `review-year` package scripts and run all content tests**

Run: `npm --prefix tools/content test`

Expected: the current 2025 corpus passes through the generalized audit.

- [ ] **Step 7: Commit**

```text
git add tools/content/src/audit-source-locks.mjs tools/content/src/audit-year.mjs tools/content/src/review-year.mjs tools/content/test tools/content/package.json
git commit -m "feat: add reusable Putnam year audits"
```

### Task 3: Prove final packaging is mechanical without packaging the app

**Files:**
- Create: `tools/content/src/build-archive.mjs`
- Create: `tools/content/test/build-archive.test.mjs`
- Modify: `tools/content/src/build-content-db.py`
- Modify: `tools/content/test/test_build_content_db.py`
- Modify: `tools/content/SCHEMA.md`
- Modify: `README.md`

**Interfaces:**
- Produces: `buildArchive(catalog, sourceRoot, outputRoot, reviewRoot): Promise<ArchiveBuild>`
- Produces: `build_archive_database(manifests, catalog, output_path)`
- Consumes: `assertFinalArchiveComplete`, `auditYear`, and `buildYear`.
- The production CLI requires explicit source, output, review, catalog, and database paths; it has no default pointing at Android assets.

- [ ] **Step 1: Write failing archive-build tests**

Using two complete temporary fixture years, assert sorted manifests, shared-asset deduplication, rejection of incomplete catalogs, rejection of missing/extra years, rejection of repeated `(year, code)` keys, deterministic logical output, and no writes outside the supplied temporary roots.

- [ ] **Step 2: Write failing multi-year database tests**

Assert two years coexist, asset paths retain their year prefix, metadata contains the sorted year list/minimum/maximum/year count/problem count/catalog digest, row insertion is stable, and the existing foreign-key/integrity tests still pass.

- [ ] **Step 3: Run focused tests and verify RED**

Run: `node --test tools/content/test/build-archive.test.mjs`

Run: `python -m unittest discover -s tools/content/test -p 'test_build_content_db.py'`

Expected: FAIL because the archive interfaces do not exist.

- [ ] **Step 4: Implement the smallest archive and database builders that satisfy the tests**

Do not invoke the CLI against real `app/` paths. Use only test fixtures and temporary directories.

- [ ] **Step 5: Document the final one-time command without running it**

The documentation must state that the command is reserved until `completedYears` equals `targetYears` and that this batch plan produces no APK.

- [ ] **Step 6: Run the complete non-Android tooling suite**

Run: `npm --prefix tools/content test`

Run: `python -m unittest discover -s tools/content/test -p 'test_*.py'`

Expected: PASS with no tracked changes beneath `app/src/main/assets/`.

- [ ] **Step 7: Commit**

```text
git add tools/content README.md
git commit -m "feat: add deterministic final archive builder"
```

### Task 4: Curate the 2024 competition

**Files:**
- Create: `content-src/2024/sources.json`
- Create: `content-src/2024/original/2024-problems.pdf`
- Create: `content-src/2024/original/2024-solutions.pdf`
- Create: `content-src/2024/original/2024.tex`
- Create: `content-src/2024/original/2024s.tex`
- Create: `content-src/2024/problems/A1.json` through `B6.json`
- Create: `content-src/2024/review/A1.md` through `B6.md`
- Create: `content-src/2024/README.md`

**Interfaces:**
- Consumes official problems: `https://maa.org/wp-content/uploads/2026/02/2024-Putnam-Problems.pdf`
- Consumes official solutions: `https://maa.org/wp-content/uploads/2026/02/2024-Putnam-Solutions.pdf`
- Cross-checks: `https://kskedlaya.org/putnam-archive/2024.tex` and `2024s.tex`
- Produces one fully audited local schema-v2 year.

- [ ] **Step 1: Lock all four source files and write `sources.json`**

Record exact URLs, retrieval date, byte lengths, SHA-256 hashes, and PDF page counts. Run the source-lock audit.

- [ ] **Step 2: Run the year audit and verify RED**

Run: `node tools/content/src/audit-year.mjs --year 2024`

Expected: FAIL listing the twelve missing problem and review records.

- [ ] **Step 3: Curate A1–A3 and their review notes**

For each problem: transcribe from the official PDF/TeX, independently reconstruct and cross-check the proof, write four progressively stronger hints, write targeted prerequisites, cite sources, validate, locally render, and document the exact source pages and hint separation.

- [ ] **Step 4: Validate A1–A3 and commit**

```text
git add content-src/2024
git commit -m "content: add 2024 Putnam A1 through A3"
```

- [ ] **Step 5: Repeat the curation loop for A4–A6 and commit**

Commit: `content: add 2024 Putnam A4 through A6`

- [ ] **Step 6: Repeat the curation loop for B1–B3 and commit**

Commit: `content: add 2024 Putnam B1 through B3`

- [ ] **Step 7: Repeat the curation loop for B4–B6 and commit**

Commit: `content: add 2024 Putnam B4 through B6`

- [ ] **Step 8: Run the complete year audit and local visual review**

Run: `node tools/content/src/audit-year.mjs --year 2024`

Run: `node tools/content/src/review-year.mjs --year 2024`

Inspect all generated documents beneath `build/content-review/2024/` at tablet reference-pane width and record `Render review: complete` only after inspection.

- [ ] **Step 9: Run all content tests and commit final 2024 review evidence**

Run: `npm --prefix tools/content test`

Commit: `content: complete 2024 Putnam review`

### Task 5: Curate the 2023 competition

**Files:**
- Create the same source, problem, review, and README layout beneath `content-src/2023/`.

**Interfaces:**
- Official problems: `https://maa.org/wp-content/uploads/2025/02/2023-Putnam-Problems.pdf`
- Official solutions: `https://maa.org/wp-content/uploads/2025/02/2023-Putnam-Problems-and-Solutions.pdf`
- Cross-checks: `https://kskedlaya.org/putnam-archive/2023.tex` and `2023s.tex`

- [ ] **Step 1: Lock sources and verify the year audit fails for missing content**
- [ ] **Step 2: Curate, validate, render, review, and commit A1–A3**
- [ ] **Step 3: Curate, validate, render, review, and commit A4–A6**
- [ ] **Step 4: Curate, validate, render, review, and commit B1–B3**
- [ ] **Step 5: Curate, validate, render, review, and commit B4–B6**
- [ ] **Step 6: Run the full 2023 audit, inspect every local render, and commit completed review evidence**
- [ ] **Step 7: Run `npm --prefix tools/content test` and require PASS**

Use the 2024 task's exact curation loop and commit-message pattern with year `2023`.

### Task 6: Curate the 2022 competition

**Files:**
- Create the same layout beneath `content-src/2022/`.

**Interfaces:**
- Official problems: `https://maa.org/wp-content/uploads/2024/10/2022_Putnam_Competitions.pdf`
- Official solutions: `https://maa.org/wp-content/uploads/2024/10/2022-Putnam-solutions.pdf`
- Cross-checks: `https://kskedlaya.org/putnam-archive/2022.tex` and `2022s.tex`

- [ ] **Step 1: Lock sources and verify RED for missing records**
- [ ] **Step 2: Curate/review/commit A1–A3**
- [ ] **Step 3: Curate/review/commit A4–A6**
- [ ] **Step 4: Curate/review/commit B1–B3**
- [ ] **Step 5: Curate/review/commit B4–B6**
- [ ] **Step 6: Pass the complete year audit and visual review**
- [ ] **Step 7: Run `npm --prefix tools/content test` and require PASS**

Use the exact per-problem quality and review requirements from Task 4.

### Task 7: Curate the 2021 competition

**Files:**
- Create the same layout beneath `content-src/2021/`.

**Interfaces:**
- Official problems: `https://maa.org/wp-content/uploads/2024/10/Putnam-2021-problems.pdf`
- Official solutions: `https://maa.org/wp-content/uploads/2024/10/Putnam-2021-problems-and-solutions.pdf`
- Cross-checks: `https://kskedlaya.org/putnam-archive/2021.tex` and `2021s.tex`

- [ ] **Step 1: Lock sources and verify RED for missing records**
- [ ] **Step 2: Curate/review/commit A1–A3**
- [ ] **Step 3: Curate/review/commit A4–A6**
- [ ] **Step 4: Curate/review/commit B1–B3**
- [ ] **Step 5: Curate/review/commit B4–B6**
- [ ] **Step 6: Pass the complete year audit and visual review**
- [ ] **Step 7: Run `npm --prefix tools/content test` and require PASS**

Use the exact per-problem quality and review requirements from Task 4.

### Task 8: Curate the 2020 competition

**Files:**
- Create the same layout beneath `content-src/2020/`.

**Interfaces:**
- Official problems: `https://maa.org/wp-content/uploads/2024/10/2020Putnam_final.pdf`
- Official solutions: `https://maa.org/wp-content/uploads/2024/10/2020-Putnam-Solutions.pdf`
- Cross-checks: `https://kskedlaya.org/putnam-archive/2020.tex` and `2020s.tex`
- Every record and route key uses year `2020`, even though the postponed competition was administered on February 20, 2021.

- [ ] **Step 1: Lock sources and verify RED for missing records**
- [ ] **Step 2: Add a focused audit assertion that every record is keyed to 2020**
- [ ] **Step 3: Curate/review/commit A1–A3**
- [ ] **Step 4: Curate/review/commit A4–A6**
- [ ] **Step 5: Curate/review/commit B1–B3**
- [ ] **Step 6: Curate/review/commit B4–B6**
- [ ] **Step 7: Pass the complete year audit and visual review**
- [ ] **Step 8: Run `npm --prefix tools/content test` and require PASS**

Use the exact per-problem quality and review requirements from Task 4.

### Task 9: Close the local 2020–2024 batch without packaging

**Files:**
- Modify: `content-src/archive.json`
- Create: `content-src/batches/2020-2024-review.md`
- Test: `tools/content/test/archive-catalog.test.mjs`

**Interfaces:**
- Consumes five passing `auditYear` results plus the existing 2025 year.
- Produces `completedYears: [2020, 2021, 2022, 2023, 2024, 2025]` while leaving the 1962–2019 target years incomplete.

- [ ] **Step 1: Write the batch review ledger**

Record every included year, its twelve reviewed codes, source-lock status, mathematical-review status, render-review status, and the exact passing audit command.

- [ ] **Step 2: Update the catalog test first**

Change the expected completed-year list to `[2020, 2021, 2022, 2023, 2024, 2025]`; verify RED before editing the catalog.

- [ ] **Step 3: Update `content-src/archive.json` and verify GREEN**

Run: `node --test tools/content/test/archive-catalog.test.mjs`

- [ ] **Step 4: Run every year audit**

Run the audit CLI separately for 2020, 2021, 2022, 2023, 2024, and 2025. All must pass.

- [ ] **Step 5: Run the complete non-Android suite**

Run: `npm --prefix tools/content test`

Run: `python -m unittest discover -s tools/content/test -p 'test_*.py'`

Expected: PASS. Confirm `git status --short` shows no changes beneath `app/src/main/assets/`, no APK, and no `dist/` artifact.

- [ ] **Step 6: Commit the completed local batch**

```text
git add content-src/archive.json content-src/batches/2020-2024-review.md
git commit -m "content: complete 2020 through 2024 Putnam batch"
```

- [ ] **Step 7: Stop before packaging**

Report the locally completed 2020–2024 batch and begin a new reviewed plan for 2015–2019. Do not run the final archive builder against Android paths until every target year through 1962 is complete.
