# MyPutnam Library Release Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze the reviewed catalog, embed the exact Putnam 2020–2025 and Core Handbook v1 starter packages, publish the rights-cleared GitHub Pages feed, and produce one verified signed MyPutnam v0.5.0 APK plus SHA-256 sidecar.

**Architecture:** One deterministic publication build is the source for both GitHub Pages and Android starter assets. A bundle-copy tool selects exact catalog/package bytes without rebuilding them. Android removes the obsolete loose HTML/content database only after all package-backed tests pass. Release verification checks signing, manifest/privacy, embedded catalog/package integrity, feed equivalence, upgrade preservation, and offline behavior.

**Tech Stack:** Existing Node publication tools, GitHub Actions/Pages, Android/Gradle, Room/Compose/WebView, PowerShell release verifier, Python APK content auditor, `apksigner`, `aapt2`, ADB/device smoke tests.

**Spec:** `docs/superpowers/specs/2026-10-18-github-content-library-and-math-reference-design.md`

## Global Constraints

- Depends on all preceding roadmap plans and their review fixes.
- Public deployment and release bundling stop if any included item's redistribution status is not legitimately `cleared`.
- Embed the exact catalog/package bytes produced by the audited publication build; do not maintain a second transformed Android content format.
- Bundled Putnam 2020–2025 and all 97 Core Handbook v1 articles remain catalog entries and support logical delete/offline restore.
- Preserve application ID `com.putnamworkbook.offline`, retained signing certificate, `UserDatabase`, notebook files, and Free Notebook files for in-place upgrade.
- Version target is `0.5.0` with default version code `5` unless a higher store/device-installed code is discovered before assembly; never reuse or decrease a version code.
- Never print, commit, or leave signing credentials/properties in the workspace.
- Produce the distributable APK only after source, content, Pages, debug, and review gates are green.

## Review Focus

- GitHub Pages and APK must expose byte-identical catalog/package digests from the same frozen build.
- Removing legacy assets/database must not remove renderer CSS/fonts or user data.
- A clean install must open fully offline; an upgrade must retain progress/notebooks and reconcile starter defaults without reviving deliberately removed bundled items.
- Release verification must require intended network permissions and reject cleartext/unrelated sensitive permissions.
- The checksum sidecar verifies transport integrity; it is not a signing substitute.

---

### Task 1: Freeze release metadata and build the exact starter bundle

**Files:**
- Create: `content-src/releases/myputnam-v0.5.0.json`
- Create: `tools/content/src/build-android-starter-bundle.mjs`
- Create: `tools/content/test/build-android-starter-bundle.test.mjs`
- Modify: `content-src/publications/content-v1.json`
- Modify: `tools/content/package.json`
- Generate in disposable output: `build/content-publication/**`
- Generate in disposable output: `build/android-starter-content/**`

**Interfaces:**
- Release manifest records app version/code, catalog ID/revision/SHA-256, fixed generation time, included sets/categories, expected counts, feed root, and minimum supported schema versions.
- `buildAndroidStarterBundle({ publicationRoot, releaseManifest, outputRoot }): Promise<{ catalogSha256, packageCount, problemCount, referenceCount }>` copies exact bytes and fails on any unlisted/missing/mutated package.

- [ ] **Step 1: Write a failing exact-selection test**

Assert the starter bundle contains all 72 Putnam 2020–2025 problems and all 97 frozen Core Handbook articles, the full catalog metadata needed to display unavailable future items, exact selected ZIP bytes, renderer static assets, and no source/review/original files.

- [ ] **Step 2: Write failing release-manifest consistency tests**

Reject stale counts, wrong catalog hash/revision, mutable package path, incompatible min app code, non-cleared rights, missing package, duplicate key, and a package not referenced by the catalog.

- [ ] **Step 3: Run tests and verify RED**

```powershell
node --test tools/content/test/build-android-starter-bundle.test.mjs
```

- [ ] **Step 4: Freeze publication/release metadata and implement byte-copy bundling**

Set one opaque catalog revision/fixed `generatedAt`, build the complete publication in a clean root, run the independent cleared-rights audit, then copy by verified digest. Never regenerate a ZIP while creating the Android bundle.

- [ ] **Step 5: Run two clean builds and compare tree hashes**

```powershell
npm --prefix tools/content test
node tools/content/src/build-publication.mjs --source content-src --library content-src/library.json --publication content-src/publications/content-v1.json --output build/content-publication
node tools/content/src/audit-publication.mjs --root build/content-publication --require-cleared-rights
node tools/content/src/build-android-starter-bundle.mjs --publication build/content-publication --release content-src/releases/myputnam-v0.5.0.json --output build/android-starter-content
```

Repeat in a second clean temporary root and assert identical file paths and SHA-256 values.

- [ ] **Step 6: Commit metadata/tooling**

```powershell
git add content-src/releases/myputnam-v0.5.0.json content-src/publications/content-v1.json tools/content/src/build-android-starter-bundle.mjs tools/content/test/build-android-starter-bundle.test.mjs tools/content/package.json tools/content/package-lock.json
git commit -m "build: freeze myputnam v0.5 content"
```

### Task 2: Embed starter packages and retire the obsolete loose runtime path

**Files:**
- Generate from Task 1: `app/src/main/assets/content-library/catalog.json`
- Generate from Task 1: `app/src/main/assets/content-library/packages/<sha256>.zip`
- Generate from Task 1: `app/src/main/assets/content-library/renderer/**`
- Remove after replacement tests pass: `app/src/main/assets/content/**`
- Remove after replacement tests pass: `app/src/main/assets/databases/content.db`
- Remove/retire: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentDatabase.kt`
- Remove/retire: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentDao.kt`
- Remove/retire: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentEntities.kt`
- Remove/retire: `app/src/main/java/com/putnamworkbook/offline/data/content/RoomContentRepository.kt`
- Remove/retire: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentBuildKind.kt`
- Remove/retire: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentInitializer.kt`
- Remove/retire: `app/src/main/java/com/putnamworkbook/offline/data/content/ContentIntegrityValidator.kt`
- Remove after all callers are gone: `app/src/test/java/com/putnamworkbook/offline/data/content/ContentBuildKindTest.kt`
- Remove after replacement tests pass: `app/src/androidTest/java/com/putnamworkbook/offline/data/content/ContentDatabaseTest.kt`
- Remove after replacement tests pass: `app/schemas/com.putnamworkbook.offline.data.content.ContentDatabase/`
- Modify: `app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt`
- Create: `app/src/main/java/com/putnamworkbook/offline/data/content/LegacyContentCleanup.kt`
- Modify/replace: `app/src/androidTest/java/com/putnamworkbook/offline/data/content/PackagedReleaseDatabaseTest.kt`
- Modify/replace: `app/src/androidTest/java/com/putnamworkbook/offline/data/content/ContentAssetUpgradeTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/data/content/StarterPackageBundleTest.kt`

**Interfaces:**
- Application startup uses only embedded catalog/starter ZIPs plus the installed index/package store/composite repositories.
- `LegacyContentCleanup` may remove only the obsolete immutable `content.db` and its hash preferences after the package-backed repository is initialized; it never addresses `user.db`, notebooks, free workspaces, or `content-library/`.

- [ ] **Step 1: Add failing embedded-bundle tests before deleting legacy assets**

Assert every bundled descriptor resolves to exact asset ZIP bytes, all packages verify/extract lazily, logical deletion/restoration works offline, 72 problems and 97 references are discoverable, and no loose document path is used.

- [ ] **Step 2: Add failing legacy-cleanup containment tests**

Seed legacy immutable data beside sentinel user/notebook/free-workspace/library data. Assert cleanup deletes only the obsolete database/preferences, is idempotent, and does nothing until new content initialization succeeds.

- [ ] **Step 3: Promote generated starter bytes atomically**

Use the Task 1 tool/output; do not hand-copy or edit catalog/ZIP files. Verify `git diff --stat` matches expected generated removals/additions.

- [ ] **Step 4: Switch dependency injection and remove obsolete runtime files/assets**

Run repository/startup tests before and after removal. Retain only renderer assets required by the fixed shell, under the new content-library renderer path.

- [ ] **Step 5: Run full Android verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL and no test opens `databases/content.db` or a loose problem HTML asset.

- [ ] **Step 6: Commit starter integration**

```powershell
git add -A -- app/src/main/assets app/src/main/java/com/putnamworkbook/offline/data/content app/schemas/com.putnamworkbook.offline.data.content.ContentDatabase
git add app/src/main/java/com/putnamworkbook/offline/di/AppContainer.kt app/src/androidTest/java/com/putnamworkbook/offline/data/content
git commit -m "feat: embed package-backed starter library"
```

### Task 3: Finalize v0.5 app metadata, privacy, upgrade, and release auditing

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/PrivacyManifestTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/PutnamAppStartupTest.kt`
- Modify: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt`
- Create: `app/src/androidTest/java/com/putnamworkbook/offline/acceptance/InPlaceUpgradeAcceptanceTest.kt`
- Modify: `tools/content/src/audit-packaged-content.py`
- Modify: `tools/content/test/test_audit_packaged_content.py`
- Modify: `scripts/verify-release.ps1`
- Modify: `scripts/device-smoke-test.md`

**Interfaces:**
- App label/version are `MyPutnam`, `0.5.0`, default version code `5`.
- `network_security_config.xml` rejects cleartext and does not trust user-added CAs specially.
- `verify-release.ps1` accepts APK plus optional release-manifest path and checks signature, version/SDK, intended permissions, cleartext config, starter catalog/package audit, checksum sidecar, and retained signer.

- [ ] **Step 1: Write failing release-auditor tests**

Create synthetic APK fixtures/ZIP views for missing/mutated/orphan starter packages, loose legacy content, catalog hash mismatch, wrong counts, wrong version, missing checksum, intended permission missing, unrelated sensitive permission present, and cleartext enabled.

- [ ] **Step 2: Add clean-install and upgrade acceptance setup**

Clean install must launch offline, browse/open bundled problem/reference, draw/save ink, and logically restore a deleted bundled item. Upgrade setup installs the current signed v0.4 APK, creates progress/notebook/free-workspace/backup and a logical content state where supported, then uses `adb install -r` for v0.5 and verifies preserved user work.

- [ ] **Step 3: Implement metadata/network security/verifier changes**

Retain application ID and signing identity. Require `INTERNET` and `ACCESS_NETWORK_STATE`; explicitly reject location, camera, microphone, contacts, SMS, storage-wide, advertising, and account permissions.

- [ ] **Step 4: Run Python/Android verification**

```powershell
python -m unittest discover -s tools/content/test -p "test_audit_packaged_content.py"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 5: Run connected clean/upgrade tests when a device/emulator exists**

Record exact commands/results. If no device exists, leave the physical/runtime gate pending rather than claiming it.

- [ ] **Step 6: Commit release integration code**

```powershell
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/res/xml/network_security_config.xml app/src/androidTest/java/com/putnamworkbook/offline/PrivacyManifestTest.kt app/src/androidTest/java/com/putnamworkbook/offline/PutnamAppStartupTest.kt app/src/androidTest/java/com/putnamworkbook/offline/acceptance/OfflineWorkbookAcceptanceTest.kt app/src/androidTest/java/com/putnamworkbook/offline/acceptance/InPlaceUpgradeAcceptanceTest.kt tools/content/src/audit-packaged-content.py tools/content/test/test_audit_packaged_content.py scripts/verify-release.ps1 scripts/device-smoke-test.md
git commit -m "chore: prepare myputnam v0.5 release"
```

### Task 4: Publish and verify the GitHub Pages content feed

**Files:**
- Modify: `.github/workflows/publish-content.yml`
- Modify: `README.md`
- Modify only if audit finds defects: publication/content source files from prior plans.

**Interfaces:**
- After the cleared-rights gate is green, publish workflow runs for `main` pushes affecting content/tool/workflow inputs and by manual dispatch.
- Production catalog URL is `https://akirakato1.github.io/MyPutnam/content/v1/catalog.json`.

- [ ] **Step 1: Enable automatic main deployment only after rights evidence is reviewed**

Keep least-privilege permissions, Pages environment, concurrency cancellation, clean build, full tests, and independent `--require-cleared-rights` audit before artifact upload.

- [ ] **Step 2: Push the fully reviewed source commit to `main`**

Confirm the workflow ran from that exact commit. Do not deploy a locally modified/uncommitted artifact.

- [ ] **Step 3: Verify the deployed catalog and representative packages**

Fetch over HTTPS, confirm correct content type/status, no redirect off origin, catalog SHA/revision match the release manifest, and representative problem/reference package bytes match local SHA-256. Verify every catalog path is reachable through an automated bounded audit rather than manually checking only two files.

- [ ] **Step 4: Test Android against the production feed without relying on it for startup**

With network enabled, refresh metadata and install a remote-only or newer test entry if the catalog contains one. Then disable network and open it. Also block the feed and confirm Home/installed content remain usable.

- [ ] **Step 5: Commit workflow/docs if changed after verification**

```powershell
git add .github/workflows/publish-content.yml README.md
git commit -m "ci: publish myputnam content feed"
git push origin main
```

### Task 5: Request final review and run every fresh gate

**Files:**
- Modify only for review findings: all roadmap implementation files.

**Interfaces:**
- Consumes: the complete roadmap implementation and frozen release/content metadata.
- Produces: a clean, reviewed, fully verified source commit that is the sole input to signing.

- [ ] **Step 1: Request independent final code, security, content, and UX reviews**

Review from the pre-roadmap commit through HEAD. Require explicit attention to package/feed determinism, rights, origin/redirect policy, archive/HTML safety, index/filesystem activation, backup compatibility, deletion semantics, notebook preservation, reference convention accuracy, accessibility, and Galaxy Tab layout.

- [ ] **Step 2: Fix every Critical or Important finding with a focused regression**

Do not waive a rights, integrity, data-loss, or rendering issue to meet a release target.

- [ ] **Step 3: Run fresh content/publication gates**

```powershell
npm ci --prefix tools/content
npm --prefix tools/content test
node tools/content/src/build-publication.mjs --source content-src --library content-src/library.json --publication content-src/publications/content-v1.json --output build/content-publication
node tools/content/src/audit-publication.mjs --root build/content-publication --require-cleared-rights
```

- [ ] **Step 4: Run fresh Android/Python gates**

```powershell
python -m unittest discover -s tools/content/test -p "test_*.py"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon
```

- [ ] **Step 5: Confirm a clean committed tree before signing**

`git status --short` may show ignored build outputs only. Confirm no graphify outputs, temporary signing files, review screenshots, or local publication builds are staged.

### Task 6: Build one signed APK, checksum it, and smoke-test the tablet

**Files:**
- Generate locally (not committed): `dist/myputnam-v0.5.0.apk`
- Generate locally (not committed): `dist/myputnam-v0.5.0.apk.sha256`
- Never persist: workspace `keystore.properties` or transient signing-property files.

**Interfaces:**
- Consumes: the clean reviewed source commit, external private signing configuration, release manifest, and published feed from Tasks 1–5.
- Produces: `dist/myputnam-v0.5.0.apk`, its SHA-256 sidecar, release-verifier evidence, and the physical-device smoke-test record.

- [ ] **Step 1: Assemble the signed release with transient signing configuration**

Use the established external private keystore note. Create only a temporary properties file/link, run `:app:assembleRelease` with version code 5 (or the pre-approved higher code), and remove all temporary credential material in a `finally` block. Never print secret values.

- [ ] **Step 2: Copy exactly one final APK and create the SHA-256 sidecar**

Use `dist/myputnam-v0.5.0.apk`; write the uppercase/lowercase conventional digest plus filename to its `.sha256` sidecar. Do not leave earlier candidate APKs presented as current.

- [ ] **Step 3: Run the release verifier against the final file**

```powershell
.\scripts\verify-release.ps1 .\dist\myputnam-v0.5.0.apk .\content-src\releases\myputnam-v0.5.0.json
```

Expected: retained signer, correct version/SDK, intended permissions only, cleartext disabled, matching sidecar, complete starter-package/catalog audit, and no legacy loose runtime content.

- [ ] **Step 4: Install on the Galaxy Tab A11+ SM-X230 and run the physical checklist**

Test clean install and, if the prior app is present, signed in-place update. With Wi-Fi off: launch, Browse Problems, open 2020/2025 problems, reveal all hint stages (including corrected bullet math), open solution, draw/select/transform/save/reopen, use Free Notebook, search/open representative handbook articles/tables, logically delete/restore bundled problem/reference, and restore backup. With Wi-Fi on: refresh catalog, install/update/delete a package, then reopen it offline.

- [ ] **Step 5: Verify layout/input on the target hardware**

Use finger, rubber-tip stylus, and any available universal active stylus. Confirm toolbar fit, no pane shift, stable notebook during reference browsing, table-local scroll, selection handles above the canvas UI, rotation/flip, page deletion guard, and no stroke flicker/loss.

- [ ] **Step 6: Record final artifact details**

Report absolute APK/sidecar paths, SHA-256, version/code, signer digest, catalog revision/hash, counts, Pages URL, verification commands, and any connected/physical test limitations. Do not claim a test that was not run.

- [ ] **Step 7: Confirm no transient secrets or duplicate current APKs remain**

Check `git status --short`, workspace signing files, and `dist/`. Preserve only the one final APK/sidecar requested for sideloading plus any intentionally archived older release outside the current-delivery path.
