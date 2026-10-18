# MyPutnam GitHub Library and Math Reference Roadmap

**Spec:** `docs/superpowers/specs/2026-10-18-github-content-library-and-math-reference-design.md`

This roadmap orders six independently reviewable implementation plans. Execute them in order because each later plan consumes contracts or runtime behavior established by the earlier plans.

1. [`2026-10-18-hint-list-math-repair.md`](2026-10-18-hint-list-math-repair.md)
   - Correct raw mathematical notation in current hint list items.
   - Add a validator and KaTeX regression before any package conversion.
2. [`2026-10-18-content-package-catalog-foundation.md`](2026-10-18-content-package-catalog-foundation.md)
   - Define the shared problem/reference/package/catalog contracts.
   - Build deterministic immutable per-item ZIPs and the GitHub Pages artifact.
   - Keep the legacy Android content path working until the runtime switches.
3. [`2026-10-18-android-problem-library.md`](2026-10-18-android-problem-library.md)
   - Add catalog refresh, installed-content index, secure package storage, composite repositories, Problem Library, and backup library state.
   - Preserve `UserDatabase`, notebook files, and Free Notebooks.
4. [`2026-10-18-math-reference-platform.md`](2026-10-18-math-reference-platform.md)
   - Add reference packaging, offline search, Home browsing, article display, and split-pane access that does not disturb notebook state.
5. [`2026-10-18-core-handbook-v1-curation.md`](2026-10-18-core-handbook-v1-curation.md)
   - Curate and independently review the approved twelve-category Core Handbook v1 in category batches.
6. [`2026-10-18-myputnam-library-release-integration.md`](2026-10-18-myputnam-library-release-integration.md)
   - Freeze the bundled catalog/packages, finish rights and security gates, publish the feed, and create one signed MyPutnam APK plus checksum.

## Dependency Graph

```text
Hint repair
    |
    v
Package/catalog foundation
    |
    v
Android Problem Library
    |
    v
Math Reference platform
    |
    v
Core Handbook v1
    |
    v
Release integration
```

The Math Reference platform uses Android package/index/resolver primitives, so execute its Android work only after the Android Problem Library plan is complete. Core Handbook curation can begin as soon as the reference source validator, renderer, and review workflow are green; it does not require rebuilding an APK per category.

## Cross-plan release rules

- Do not create or replace a distributable APK under `dist/` before Plan 6. Debug compilation/assembly remains allowed as implementation verification.
- Do not migrate or delete notebook files. Problem identity remains the stable `ProblemKey`; article identity remains the stable `ReferenceKey`.
- Do not copy package bytes into backups. Backups store only user data and small desired-library metadata.
- Do not publish Putnam or reference material whose tracked redistribution status is not legitimately `cleared`.
- Do not make network access a prerequisite for Home, installed content, bundled restore, notebooks, or Math Reference items already on device.
- Do not introduce a runtime Markdown/TeX parser. All downloaded mathematical documents are validated, pre-rendered inert fragments.
- Keep schemas, fixtures, and generated publication bytes platform-neutral so a later static web client can consume them without corpus conversion.

## Completion gate

The roadmap is complete only when all six plans have fresh verification evidence, GitHub Pages exposes a self-consistent catalog and immutable packages, the bundled Putnam 2020–2025 and Core Handbook v1 work offline, user work survives delete/update/reinstall and backup/restore, and the signed APK passes the physical Galaxy Tab smoke test when the tablet is available.
