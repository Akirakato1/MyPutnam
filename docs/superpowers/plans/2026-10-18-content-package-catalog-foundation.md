# Content Package and Catalog Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish platform-neutral, deterministic source, package, catalog, and publication contracts for downloadable MyPutnam problems and reference articles, then compile Putnam 2020–2025 into immutable per-problem packages.

**Architecture:** Editable JSON remains canonical. Shared validators and renderers compile inert HTML fragments and declared local assets into one immutable ZIP per item. A full snapshot publication catalog points to digest-addressed, same-origin package paths. The existing loose-asset/SQLite Android pipeline remains green until the Android runtime plan intentionally switches to the new packages.

**Tech Stack:** Node.js 22, ECMAScript modules, AJV, KaTeX, pinned `yazl`/`yauzl`, Node test runner, Playwright, JSON Schema, GitHub Actions and GitHub Pages.

**Spec:** `docs/superpowers/specs/2026-10-18-github-content-library-and-math-reference-design.md`

## Global Constraints

- Preserve problem schema v3 and all existing `ProblemKey` values.
- Keep the editable source taxonomy (`library-catalog.schema.json`) distinct from the generated feed (`publication-catalog.schema.json`).
- Keep `package.json` as the sole implicit ZIP member. Its `members` list covers every other member; self-hashing is forbidden.
- Derive `packageRevision` from canonical payload metadata/member hashes. Use the complete ZIP SHA-256 only for the immutable outer filename and descriptor.
- Never read the wall clock during a deterministic build. `catalogRevision` and `generatedAt` come from a tracked publication manifest.
- Emit only relative, same-origin package paths. Never place source PDFs, provenance TeX, locks, review notes, or scripts in packages/publication artifacts.
- Do not mark redistribution rights `cleared` without reviewed evidence. Local development builds may be produced, but the publish audit must reject uncleared entries.
- Do not switch Android runtime storage or build a distributable APK in this plan.

## Review Focus

- ZIP bytes, canonical JSON, catalog bytes, and logical output trees must be repeatable across clean builds and source mtimes.
- All paths must survive Windows case-insensitivity and reject slash/backslash/drive/traversal ambiguities.
- Catalog, envelope, source, and immutable filename must agree on key, revision, schemas, sizes, and digests.
- Rich-document v1 must accept the entire existing problem corpus unchanged; table support belongs only to v2.
- Public publication must be impossible while rights status is incomplete.

---

### Task 1: Extract shared keys, schema registry, and rich-document contracts

**Files:**
- Modify: `tools/content/src/content-types.mjs`
- Modify: `tools/content/src/validate-problem.mjs`
- Modify: `tools/content/src/render-document.mjs`
- Modify: `tools/content/schema/problem.schema.json`
- Create: `tools/content/src/schema-registry.mjs`
- Create: `tools/content/src/rich-document.mjs`
- Create: `tools/content/schema/rich-document-v1.schema.json`
- Create: `tools/content/schema/rich-document-v2.schema.json`
- Create: `tools/content/test/content-contracts.test.mjs`
- Create: `tools/content/test/fixtures/contracts/valid-rich-document-v1.json`
- Create: `tools/content/test/fixtures/contracts/valid-rich-document-v2.json`
- Create: `tools/content/test/fixtures/contracts/invalid-rich-document-v2.json`

**Interfaces:**
- Add to `content-types.mjs`:
  - `referenceKey(categoryId, articleId): string`
  - `parseReferenceKey(value): { namespace: 'math-reference', categoryId: string, articleId: string }`
- Add to `rich-document.mjs`:
  - `validateRichDocument(document, { version, basePath }): string[]`
  - `findDocumentMathRuns(document, basePath = '$'): Array<{ path: string, tex: string, displayMode: boolean }>`
- Rich-document v2 adds `table` with rich-run caption/headers and equal-width rows of non-table rich documents; nested tables are invalid.

- [ ] **Step 1: Write failing key and version-boundary tests**

Assert `ReferenceKey` is exactly `math-reference/<categoryId>/<articleId>`, case-sensitive, and rejects unsafe or extra segments. Assert v1 accepts every current problem document but rejects `table`; assert v2 accepts a valid table and rejects zero rows/columns, unequal cells, nested tables, and invalid math.

- [ ] **Step 2: Run the focused contract test and verify RED**

```powershell
node --test tools/content/test/content-contracts.test.mjs
```

Expected: FAIL because the shared schemas, registry, and reference-key functions do not exist.

- [ ] **Step 3: Implement the registry and reusable validation**

Compile schemas once, resolve external `$ref` values predictably, and preserve exact JSON error paths. Refactor problem validation to call the shared v1 validator after the problem envelope passes.

- [ ] **Step 4: Prove all current records remain valid**

```powershell
node --test tools/content/test/content-contracts.test.mjs tools/content/test/validate-problem.test.mjs tools/content/test/putnam-2020-2025-content.test.mjs
```

Expected: PASS for all 72 existing problems with no source rewrite.

- [ ] **Step 5: Commit**

```powershell
git add tools/content/src/content-types.mjs tools/content/src/schema-registry.mjs tools/content/src/rich-document.mjs tools/content/src/validate-problem.mjs tools/content/schema tools/content/test/content-contracts.test.mjs tools/content/test/fixtures/contracts
git commit -m "feat: define shared content contracts"
```

### Task 2: Define reference, rights, package-envelope, and feed schemas

**Files:**
- Modify: `tools/content/schema/library-catalog.schema.json`
- Modify: `tools/content/src/library-catalog.mjs`
- Modify: `content-src/library.json`
- Create: `tools/content/schema/reference-categories.schema.json`
- Create: `tools/content/schema/reference-article.schema.json`
- Create: `tools/content/schema/package-envelope.schema.json`
- Create: `tools/content/schema/publication-catalog.schema.json`
- Create: `tools/content/schema/publication-manifest.schema.json`
- Create: `tools/content/src/validate-reference.mjs`
- Create: `tools/content/src/package-contract.mjs`
- Create: `tools/content/src/publication-catalog.mjs`
- Create: `tools/content/test/reference-contract.test.mjs`
- Create: `tools/content/test/package-contract.test.mjs`
- Create: `tools/content/test/publication-catalog.test.mjs`
- Create: `tools/content/test/fixtures/contracts/valid-reference.json`
- Create: `tools/content/test/fixtures/contracts/valid-package-envelope.json`
- Create: `tools/content/test/fixtures/contracts/valid-publication-catalog.json`

**Interfaces:**
- `PACKAGE_SCHEMA_VERSION = 1`.
- `PACKAGE_LIMITS = { maxCompressedBytes: 16 * 1024 * 1024, maxUncompressedBytes: 64 * 1024 * 1024, maxEntries: 256, maxEntryBytes: 16 * 1024 * 1024 }`.
- `validatePackageEnvelope(value): string[]`.
- `validatePackageMemberPath(path): boolean`.
- `validatePublicationCatalog(value): string[]`.
- `buildPublicationCatalog({ publicationManifest, libraryCatalog, problemPackages, referencePackages }): object`.
- Every source/catalog item resolves rights fields: source URL, attribution, copyright holder when known, license/permission basis, and `redistributionStatus`.

- [ ] **Step 1: Write failing schema fixtures**

Cover required reference sections/examples, safe relation keys, category membership, aliases/keywords, complete rights metadata, package member declarations, relative package paths, schema/app compatibility, and catalog uniqueness. Add invalid fixtures for unknown topic/category, duplicate order/key, absolute/escaping URL, missing rights, envelope/descriptor mismatch, and unsupported schema combinations.

- [ ] **Step 2: Run contract tests and verify RED**

```powershell
node --test tools/content/test/reference-contract.test.mjs tools/content/test/package-contract.test.mjs tools/content/test/publication-catalog.test.mjs
```

Expected: FAIL because the schemas and validators do not exist.

- [ ] **Step 3: Implement strict schemas and canonical descriptors**

Define `PackageDescriptor` fields `contentType`, `packageRevision`, `packageSchemaVersion`, `contentSchemaVersion`, `richDocumentVersion`, `path`, `compressedBytes`, `uncompressedBytes`, `sha256`, and `minAppVersionCode`. Reject unknown fields in security-sensitive envelopes/descriptors.

- [ ] **Step 4: Add honest rights records to source taxonomy**

Record known attribution/source metadata for current collections without changing an unresolved status to `cleared`. Make development validation distinguish “metadata complete” from “publicly redistributable.”

- [ ] **Step 5: Run tests and verify GREEN**

Run the command from Step 2 plus `npm --prefix tools/content test`.

- [ ] **Step 6: Commit**

```powershell
git add content-src/library.json tools/content/schema tools/content/src/validate-reference.mjs tools/content/src/package-contract.mjs tools/content/src/publication-catalog.mjs tools/content/src/library-catalog.mjs tools/content/test
git commit -m "feat: define package and feed schemas"
```

### Task 3: Render safe platform-neutral fragments and local assets

**Files:**
- Modify: `tools/content/src/render-document.mjs`
- Modify: `tools/content/templates/content.css`
- Modify: `tools/content/test/render-document.test.mjs`
- Create: `tools/content/src/safe-package-asset.mjs`
- Create: `tools/content/test/safe-package-asset.test.mjs`

**Interfaces:**
- `renderDocumentFragment(document, { richDocumentVersion }): string` returns body markup only.
- Existing `renderDocument(document, options)` remains available and wraps the fragment for legacy review/build callers.
- `auditPackageAsset({ path, mediaType, bytes }): string[]` validates supported raster/SVG assets without executing or fetching content.

- [ ] **Step 1: Write failing fragment tests**

Assert fragments have no `<html>`, `<head>`, `<script>`, `<style>`, `<base>`, inline event handler, or remote URL. Assert inline/display/list/table-cell math produces matching KaTeX roots and v2 tables emit `<caption>`, `<th scope="col">`, and `.table-scroll`.

- [ ] **Step 2: Write failing asset safety tests**

Cover media/extension mismatches, oversized images, SVG scripts, event handlers, `foreignObject`, external references, data URLs, unsafe IDs/URLs, and a minimal safe SVG/raster fixture.

- [ ] **Step 3: Run rendering/asset tests and verify RED**

```powershell
node --test tools/content/test/render-document.test.mjs tools/content/test/safe-package-asset.test.mjs
```

- [ ] **Step 4: Implement fragments while preserving legacy full pages**

Reuse one recursive block/run renderer. Permit horizontal overflow only inside `.table-scroll`; keep the document/body width constrained. Run the existing render audit against both full pages and fragments.

- [ ] **Step 5: Run focused and full tool tests**

```powershell
npm --prefix tools/content test
```

Expected: existing full-document snapshots/behavior stay green and new fragment safety checks pass.

- [ ] **Step 6: Commit**

```powershell
git add tools/content/src/render-document.mjs tools/content/src/safe-package-asset.mjs tools/content/templates/content.css tools/content/test/render-document.test.mjs tools/content/test/safe-package-asset.test.mjs
git commit -m "feat: render safe content fragments"
```

### Task 4: Build and verify deterministic generic ZIP packages

**Files:**
- Modify: `tools/content/package.json`
- Modify: `tools/content/package-lock.json`
- Create: `tools/content/src/package-archive.mjs`
- Create: `tools/content/test/package-archive.test.mjs`

**Interfaces:**
- `createPackageArchive({ contentType, contentKey, sourceSchemaVersion, richDocumentVersion, minAppVersionCode, members }): Promise<{ bytes: Uint8Array, envelope: object }>`.
- `verifyPackageArchive(bytes, { expectedDescriptor, limits = PACKAGE_LIMITS }): Promise<{ envelope: object, members: Map<string, Uint8Array> }>`.
- `PackageMemberInput = { path: string, mediaType: string, bytes: Uint8Array }`.

- [ ] **Step 1: Add pinned ZIP dependencies**

Pin exact `yazl` and `yauzl` versions and refresh the lockfile. Do not use a native dependency or a library that silently preserves source timestamps/modes.

- [ ] **Step 2: Write failing deterministic/safety tests**

Build twice with different input mtimes/order and require byte equality. Cover fixed ZIP timestamps/modes; sorted paths; duplicate/case-colliding paths; directory/symlink/special entries; absolute/backslash/drive/dot/empty paths; undeclared members; wrong inner/outer sizes and hashes; entry/member/total limits; malformed central directory; and descriptor mismatch.

- [ ] **Step 3: Run package tests and verify RED**

```powershell
node --test tools/content/test/package-archive.test.mjs
```

- [ ] **Step 4: Implement archive creation and independent verification**

Canonicalize JSON recursively, hash every payload member, compute `declaredUncompressedBytes` excluding `package.json`, derive `packageRevision` from the canonical envelope payload, and verify the complete ZIP before returning members. Never trust entry metadata alone.

- [ ] **Step 5: Run tests twice from clean temporary directories**

Run the command from Step 3 twice and compare the test fixture output hashes reported by the test.

- [ ] **Step 6: Commit**

```powershell
git add tools/content/package.json tools/content/package-lock.json tools/content/src/package-archive.mjs tools/content/test/package-archive.test.mjs
git commit -m "feat: build deterministic content packages"
```

### Task 5: Compile all Putnam 2020–2025 problems into per-item packages

**Files:**
- Create: `tools/content/src/build-problem-package.mjs`
- Create: `tools/content/test/build-problem-package.test.mjs`
- Modify: `tools/content/src/content-types.mjs`
- Modify only if source metadata needs package projection: `content-src/library.json`

**Interfaces:**
- `buildProblemPackage({ record, problemAssetRoot, libraryCatalog, minAppVersionCode }): Promise<{ contentKey, archiveBytes, envelope, catalogMetadata }>`.
- Output members follow the approved names: `source/problem.json`, statement, four hints, all prerequisites, solution, and declared `assets/` only.

- [ ] **Step 1: Write a failing package compiler test for a fixture and real `putnam/2025/A1`**

Assert exact member names, canonical source JSON, fragment rather than standalone HTML, four staged hints, all prerequisites, solution, optional local figures, stable metadata, and no original PDF/TeX/lock/review file.

- [ ] **Step 2: Run the compiler test and verify RED**

```powershell
node --test tools/content/test/build-problem-package.test.mjs
```

- [ ] **Step 3: Implement problem projection through existing validated models**

Do not reparse math or duplicate problem validation. Build documents with Task 3, assets with its auditor, and the archive with Task 4.

- [ ] **Step 4: Add a 72-problem deterministic corpus test**

Build every Putnam 2020–2025 problem twice in separate temporary roots. Assert 72 unique stable keys, no missing A1–A6/B1–B6 per year, byte-identical packages, and no cross-problem assets.

- [ ] **Step 5: Run the package and full corpus suites**

```powershell
node --test tools/content/test/build-problem-package.test.mjs tools/content/test/putnam-2020-2025-content.test.mjs
```

- [ ] **Step 6: Commit**

```powershell
git add tools/content/src/build-problem-package.mjs tools/content/src/content-types.mjs tools/content/test/build-problem-package.test.mjs content-src/library.json
git commit -m "feat: package putnam problems individually"
```

### Task 6: Build and independently audit the complete publication tree

**Files:**
- Create: `content-src/publications/content-v1.json`
- Create: `tools/content/src/build-publication.mjs`
- Create: `tools/content/src/audit-publication.mjs`
- Create: `tools/content/test/build-publication.test.mjs`
- Create: `tools/content/test/audit-publication.test.mjs`
- Modify: `tools/content/package.json`
- Generate only in ignored/disposable output: `build/content-publication/content/v1/catalog.json`
- Generate only in ignored/disposable output: `build/content-publication/content/v1/packages/problem/**/<sha256>.zip`

**Interfaces:**
- `buildPublication({ sourceRoot, libraryCatalog, publicationManifest, outputRoot }): Promise<{ catalogPath, problemCount, referenceCount, packageCount }>`.
- `auditPublication(root, { requireClearedRights = false }): Promise<string[]>`.
- Tracked manifest fields: `schemaVersion`, `catalogId`, `catalogRevision`, `generatedAt`, `minAppVersionCode`, `includedSets`, and `includedReferenceCategories`.

- [ ] **Step 1: Write failing clean-root and logical-tree tests**

Assert the builder refuses a nonempty output root before mutation; emits exactly one catalog plus referenced digest-addressed packages; produces 72 problem entries; uses paths relative to `content/v1/catalog.json`; and produces identical bytes/logical trees in two clean roots.

- [ ] **Step 2: Write failing independent audit tests**

Mutate catalog, ZIP, envelope, source key, revision, member, digest, size, and immutable filename one at a time. Add orphan-package and missing-package cases. Assert originals/review/scripts/TeX/PDF are rejected anywhere in the artifact.

- [ ] **Step 3: Run publication tests and verify RED**

```powershell
node --test tools/content/test/build-publication.test.mjs tools/content/test/audit-publication.test.mjs
```

- [ ] **Step 4: Implement builder and an auditor that does not trust builder internals**

The auditor reopens every ZIP, recomputes every hash/size, revalidates every schema/key/right/path relation, and checks catalog closure. `requireClearedRights = true` fails every non-cleared entry; false permits a local review artifact but retains the status visibly.

- [ ] **Step 5: Run a complete local review build**

```powershell
node tools/content/src/build-publication.mjs --source content-src --library content-src/library.json --publication content-src/publications/content-v1.json --output build/content-publication
node tools/content/src/audit-publication.mjs --root build/content-publication
```

Expected: 72 problem packages and a self-consistent local catalog. If rights are not cleared, the normal local audit passes with review statuses while `--require-cleared-rights` fails intentionally.

- [ ] **Step 6: Commit**

```powershell
git add content-src/publications/content-v1.json tools/content/package.json tools/content/src/build-publication.mjs tools/content/src/audit-publication.mjs tools/content/test/build-publication.test.mjs tools/content/test/audit-publication.test.mjs
git commit -m "feat: build complete content publication"
```

### Task 7: Add continuous validation and a rights-gated Pages workflow

**Files:**
- Create: `.github/workflows/validate-content.yml`
- Create: `.github/workflows/publish-content.yml`
- Modify: `README.md`
- Modify: `tools/content/SCHEMA.md`
- Modify: `tools/content/package.json`

**Interfaces:**
- Validation workflow runs on pull requests and pushes to `main`.
- Publication workflow is initially `workflow_dispatch` only and uses `audit-publication --require-cleared-rights`; Plan 6 may add automatic `main` deployment only after the tracked rights gate is truly green.
- Intended feed URL: `https://akirakato1.github.io/MyPutnam/content/v1/catalog.json`.

- [ ] **Step 1: Add package scripts used identically locally and in CI**

Add `test`, `build:publication`, and `audit:publication` scripts with no machine-specific paths.

- [ ] **Step 2: Implement continuous validation**

Use checkout, Node 22, lockfile cache, `npm ci --prefix tools/content`, the complete test suite, a clean publication build, and the independent non-public audit. While any included rights status is uncleared, do not upload package artifacts from validation; retain only ordinary test/audit logs that do not reproduce content.

- [ ] **Step 3: Implement manual rights-gated Pages publication**

Grant only `contents: read`, `pages: write`, and `id-token: write`; use GitHub Pages artifact upload/deploy actions and concurrency cancellation. Run the cleared-rights audit before upload. Never deploy on pull requests.

- [ ] **Step 4: Document contracts and local commands**

Document immutable URLs, revisions, source versus feed schemas, development versus cleared publication audits, and the fact that Android later embeds the exact generated catalog/ZIP bytes.

- [ ] **Step 5: Run local equivalents**

```powershell
npm ci --prefix tools/content
npm --prefix tools/content test
npm --prefix tools/content run build:publication
npm --prefix tools/content run audit:publication
```

Expected: all validation passes. Do not claim public publication until the cleared-rights command and GitHub Pages deployment both succeed.

- [ ] **Step 6: Commit**

```powershell
git add .github/workflows README.md tools/content/SCHEMA.md tools/content/package.json tools/content/package-lock.json
git commit -m "ci: validate immutable content feed"
```

### Task 8: Review the foundation without switching Android runtime

**Files:**
- Modify only for review findings: files changed in Tasks 1–7.

**Interfaces:**
- Consumes: all shared schemas, renderers, package builders, publication tools, fixtures, and workflows created by this plan.
- Produces: a reviewed foundation whose public APIs and security limits are frozen for the Android and Math Reference plans.

- [ ] **Step 1: Request whole-plan code and security review**

Require explicit review of schema compatibility, deterministic ZIPs, canonical JSON, path normalization, archive limits, fragment/SVG safety, catalog closure, rights enforcement, and Pages permissions.

- [ ] **Step 2: Fix every Critical or Important finding with focused tests**

Do not weaken validation or add permissive fallbacks to make malformed fixtures pass.

- [ ] **Step 3: Run fresh final verification**

```powershell
npm --prefix tools/content test
node tools/content/src/build-publication.mjs --source content-src --library content-src/library.json --publication content-src/publications/content-v1.json --output build/content-publication
node tools/content/src/audit-publication.mjs --root build/content-publication
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: all commands pass, the old Android content flow remains operational, and no distributable APK or committed generated publication tree is produced.
