# Hint List Math Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct every current hint list item whose mathematical notation is stored as plain text, and prevent the same authoring defect from returning.

**Architecture:** Keep problem schema v3 and the existing build-time KaTeX renderer. Add a narrowly scoped source audit for suspicious math syntax inside list-item `text` runs, convert affected 2025 source runs to `inlineMath`, and prove the generated list HTML contains KaTeX roots. No runtime Markdown or delimiter parser is added.

**Tech Stack:** Node.js 22, ECMAScript modules, AJV, KaTeX, existing problem schema v3/content builder, Node test runner, Playwright review renderer.

**Spec:** `docs/superpowers/specs/2026-10-18-github-content-library-and-math-reference-design.md`

## Global Constraints

- Preserve problem schema version 3 and every stable `ProblemKey`.
- Treat mathematical notation as `inlineMath`/`displayMath`; never reinterpret prose at runtime.
- Scope the new heuristic to `list.items[*]` text runs so normal prose elsewhere does not become a false failure.
- Report exact JSON paths and the matched rule for actionable authoring errors.
- Run the audit over all 72 Putnam 2020–2025 records, not only the reported 2025 examples.
- Do not build or replace a distributable APK in this plan.

## Review Focus

- The validator must catch TeX subscripts/superscripts, TeX commands, and symbolic comparisons/equalities while accepting ordinary punctuation and contractions.
- Mixed prose/math list items must preserve spaces and punctuation after being split into runs.
- The regression must inspect rendered list-item output, not merely accept valid source JSON.
- Current full-document output outside the corrected runs must remain unchanged.

---

### Task 1: Add a focused raw-list-math audit

**Files:**
- Create: `tools/content/src/list-math-audit.mjs`
- Create: `tools/content/test/list-math-audit.test.mjs`
- Modify: `tools/content/src/validate-problem.mjs`
- Modify: `tools/content/test/validate-problem.test.mjs`

**Interfaces:**
- Produces:
  - `export function findRawMathInListText(document, basePath = '$'): Array<{ path: string, text: string, rule: string }>`
  - `validateProblem(record, catalog)` errors of the form `<json path>: mathematical notation in list text must use inlineMath (<rule>)`.
- Rules are limited to unescaped TeX commands, `_` or `^` math syntax, and symbolic `=`, `<`, or `>` between identifier/number-like operands. A short allowlist covers ordinary non-math prose only when a concrete false positive is proven by the current corpus.

- [ ] **Step 1: Write failing unit tests for exact matches and safe prose**

Cover `c_{n,k}`, `2^{k+1}`, `A_iA_j=A_jA_i`, `R^3`, `x_2-x_1`, `a+b<3^a5^b`, and `N=N'+r`. Also cover ordinary apostrophes, hyphenated prose, filenames, URLs, and sentences containing the words “equals” or “less than” without symbolic notation.

- [ ] **Step 2: Write a failing validator integration test**

Insert suspicious text into a valid fixture's fourth-hint list and assert `validateProblem` returns the exact list-item/run path. Assert the same expression in an `inlineMath` run is accepted.

- [ ] **Step 3: Run the focused tests and verify RED**

```powershell
node --test tools/content/test/list-math-audit.test.mjs tools/content/test/validate-problem.test.mjs
```

Expected: FAIL because `list-math-audit.mjs` and validator integration do not exist.

- [ ] **Step 4: Implement traversal and deterministic diagnostics**

Traverse only rich-document list blocks, preserve source order, emit one finding per offending text run, and deduplicate identical rule hits at one path. Integrate findings into `validateProblem` after schema validation so malformed structures are not traversed unsafely.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the command from Step 3.

Expected: PASS with zero failures.

- [ ] **Step 6: Commit**

```powershell
git add tools/content/src/list-math-audit.mjs tools/content/src/validate-problem.mjs tools/content/test/list-math-audit.test.mjs tools/content/test/validate-problem.test.mjs
git commit -m "test: reject raw math in hint lists"
```

### Task 2: Correct every affected current hint list item

**Files:**
- Modify: `content-src/2025/problems/A4.json`
- Modify: `content-src/2025/problems/A6.json`
- Modify: `content-src/2025/problems/B1.json`
- Modify: `content-src/2025/problems/B2.json`
- Modify: `content-src/2025/problems/B3.json`
- Modify: `content-src/2025/problems/B4.json`
- Modify: `tools/content/test/putnam-2020-2025-content.test.mjs`
- Modify: `tools/content/test/2025-content.test.mjs`

**Interfaces:**
- Consumes: `findRawMathInListText` from Task 1.
- Produces: mixed `text` and `inlineMath` runs for every symbolic expression currently found in hint list items.

- [ ] **Step 1: Add a failing whole-corpus audit test**

Load all 72 source records and assert every statement, hint, prerequisite, and solution document returns an empty raw-list-math finding list. Add named expectations that the previously defective fourth-hint items contain `inlineMath` runs for their exact formulas.

- [ ] **Step 2: Run the 2025 and whole-corpus tests and verify RED**

```powershell
node --test tools/content/test/2025-content.test.mjs tools/content/test/putnam-2020-2025-content.test.mjs
```

Expected: FAIL on the six listed 2025 records.

- [ ] **Step 3: Split prose and mathematical runs without rewriting explanations**

Convert all expressions reported by the audit, including the matrix/vector notation in A4, coefficient/congruence notation in A6, the numeric comparison in B1, indexed variables in B2, exponent/divisibility inequalities in B3, and recurrence/count notation in B4. Keep surrounding wording, list order, and hint staging unchanged.

- [ ] **Step 4: Re-run the entire source audit**

```powershell
npm --prefix tools/content test
```

Expected: all content-tool tests pass and the corpus audit reports zero suspicious list text runs.

- [ ] **Step 5: Commit**

```powershell
git add content-src/2025/problems tools/content/test/2025-content.test.mjs tools/content/test/putnam-2020-2025-content.test.mjs
git commit -m "fix: render math in hint list items"
```

### Task 3: Pin KaTeX rendering inside list items

**Files:**
- Modify: `tools/content/test/render-document.test.mjs`
- Modify: `tools/content/test/generated-content.test.mjs`
- Modify only if the regression exposes a renderer defect: `tools/content/src/render-document.mjs`
- Modify only if list layout needs correction: `tools/content/templates/content.css`

**Interfaces:**
- Consumes: existing `renderDocument`, `findDocumentMathRuns`, and `auditRenderedDocument`.
- Produces: a regression that counts expected `.katex` roots inside `<li>` elements and rejects literal raw source formulas in generated list text.

- [ ] **Step 1: Add a failing mixed-run list rendering regression**

Render ordered and unordered list items containing multiple inline math runs. Assert semantic `<ol>/<ul>/<li>` structure, exact KaTeX-root count within list items, escaped prose, and no literal `c_{n,k}` or `A_iA_j=A_jA_i` outside KaTeX annotation markup.

- [ ] **Step 2: Add generated 2025 hint assertions**

Build the affected 2025 records into a temporary directory and assert hint-4 fragments contain the expected list items and KaTeX roots. Keep the assertion independent of Android assets.

- [ ] **Step 3: Run rendering tests**

```powershell
node --test tools/content/test/render-document.test.mjs tools/content/test/generated-content.test.mjs
```

Expected before source regeneration: the new generated-content assertion fails; after correction it passes. Change renderer code only if the correctly structured source still fails.

- [ ] **Step 4: Run Playwright review for the affected year**

```powershell
npm --prefix tools/content run review-year -- --source content-src/2025 --preview build/review/2025-preview --review build/review/2025
```

Inspect A4, A6, B1, B2, B3, and B4 hint 4 at the configured tablet widths. Confirm bullets, baselines, wrapping, and KaTeX have no clipping or horizontal page overflow.

- [ ] **Step 5: Commit**

```powershell
git add tools/content/test/render-document.test.mjs tools/content/test/generated-content.test.mjs tools/content/src/render-document.mjs tools/content/templates/content.css
git commit -m "test: cover katex in hint lists"
```

### Task 4: Refresh local generated content without packaging an APK

**Files:**
- Regenerate: `app/src/main/assets/content/putnam/2025/problems/*/hint-4.html`
- Regenerate as required by the existing deterministic release pipeline: `app/src/main/assets/content/library.json`
- Regenerate as required by the existing deterministic release pipeline: `app/src/main/assets/content/release.json`
- Regenerate: `app/src/main/assets/databases/content.db`
- Modify only for discovered regressions: current content source/tool/test files from Tasks 1–3.

**Interfaces:**
- Consumes: the existing `build-release.mjs`/`publish-release-assets.mjs` flow and `content-src/releases/putnam-2020-2025-v0.4.0.json`.
- Produces: deterministic local assets whose digests and database references match the corrected source. It does not produce a new distributable APK.

- [ ] **Step 1: Run the established release-content build into a disposable directory**

Use the exact commands documented by the existing v0.4 release plan. Build outside `app/src/main/assets` first and run the packaged-content audit against that disposable output.

- [ ] **Step 2: Compare and promote only deterministic generated changes**

Confirm the diff is limited to affected HTML, expected hashes/manifests, and the content database. Promote through the existing publication command; do not hand-edit generated HTML or SQLite.

- [ ] **Step 3: Run full content and Android content tests**

```powershell
npm --prefix tools/content test
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL; all generated hashes resolve and existing problem rendering tests remain green.

- [ ] **Step 4: Commit generated content**

```powershell
git add app/src/main/assets/content app/src/main/assets/databases/content.db
git commit -m "build: refresh corrected hint content"
```

### Task 5: Review the repair as an independent boundary

**Files:**
- Modify only for review findings: files changed in Tasks 1–4.

**Interfaces:**
- Consumes: the complete source-audit, corrected source, render regressions, and regenerated local content from Tasks 1–4.
- Produces: reviewed commits with a focused regression for every accepted Critical or Important finding.

- [ ] **Step 1: Request focused code/content review**

Review from the pre-plan commit through HEAD. Require explicit attention to heuristic false positives/negatives, exact JSON paths, preservation of mathematical meaning, list semantics, KaTeX-root assertions, and generated-asset determinism.

- [ ] **Step 2: Fix every Critical or Important finding with a regression**

Use focused commits. Do not loosen the rule globally to suppress one false positive; narrow it with a proven safe case.

- [ ] **Step 3: Run fresh verification**

```powershell
npm --prefix tools/content test
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: all commands pass. Record if Playwright or Android instrumentation cannot run, and do not claim those checks without output.
