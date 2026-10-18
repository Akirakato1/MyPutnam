# MyPutnam

MyPutnam is a native, offline-first Android study app for browsing competition problems, filtering them by topic, revealing progressive hints and prerequisite refreshers, and writing solutions in a page-based notebook.

The current travel release packages all 72 Putnam problems from 2020 through 2025 for Android 10 and newer. It declares no Internet permission: statements, rendered mathematics, four-stage hints, prerequisites, solutions, search data, settings, progress, and notebook pages remain on the device. Locked source PDFs and TeX files are retained only as development provenance and are never packaged.

## Local build

1. Install JDK 17 or newer and Android SDK Platform 37 with current build tools.
2. Add an untracked `local.properties` containing `sdk.dir=<your Android SDK path>`.
3. Run `gradlew.bat :app:assembleDebug` on Windows.

The app targets Android 16/API 36. When an API 36 tablet emulator or device is attached, run `gradlew.bat :app:connectedDebugAndroidTest`.

## Content and release builds

The checked-in release is version `0.4.0` (code `4`) with immutable content release `putnam-2020-2025-v0.4.0`. Its selected sets are declared in `content-src/releases/putnam-2020-2025-v0.4.0.json`.

Build and independently audit a staging bundle before publishing it into the Android assets:

```text
node tools/content/src/build-release.mjs --source content-src --archive-catalog content-src/archive.json --library-catalog content-src/library.json --release content-src/releases/putnam-2020-2025-v0.4.0.json --staging build/release/putnam-2020-2025-v0.4.0/app-assets --review-output build/release/putnam-2020-2025-v0.4.0/review --receipt build/release/putnam-2020-2025-v0.4.0/receipt.json
python tools/content/src/content_bundle_audit.py --asset-root build/release/putnam-2020-2025-v0.4.0/app-assets --expected-release content-src/releases/putnam-2020-2025-v0.4.0.json
node tools/content/src/publish-release-assets.mjs --staging build/release/putnam-2020-2025-v0.4.0/app-assets --receipt build/release/putnam-2020-2025-v0.4.0/receipt.json --destination app/src/main/assets
```

For a signed release, keep the password-protected keystore outside the repository, copy `keystore.properties.example` to the ignored `keystore.properties`, and supply the preserved alias and passwords. Never commit or print those credentials. Build with `gradlew.bat :app:assembleRelease`.

Place the final APK and checksum at:

```text
dist/putnam-workbook-2020-2025-v0.4.0.apk
dist/putnam-workbook-2020-2025-v0.4.0.apk.sha256
```

Verify the exact APK artifact:

```text
powershell -ExecutionPolicy Bypass -File scripts/verify-release.ps1 dist/putnam-workbook-2020-2025-v0.4.0.apk
```

The verifier checks the application ID, v0.4.0/code 4, SDK range, absence of Internet permission, retained signer certificate, adjacent checksum, and every embedded release/catalog/manifest/database/asset byte. It rejects stale metadata, missing or tampered assets, external dependencies, unselected sets, source files, invalid topics, or counts other than six sets, 72 problems, 288 hints, and the manifest-derived prerequisite total.

This development release uses a clean-install workflow; notebook migration is intentionally out of scope. Follow `scripts/device-smoke-test.md` for the SM-X230 airplane-mode checklist.

## Immutable content feed foundation

The repository can compile the 72 curated Putnam 2020–2025 records into a platform-neutral feed: one complete catalog and one immutable ZIP per problem. This feed is tooling-only for now; the current Android app continues to use its bundled offline database until the later download-library integration is completed.

From a clean checkout, build and audit the ignored local publication with:

```text
npm ci --prefix tools/content
npm --prefix tools/content test
npm --prefix tools/content run build:publication
npm --prefix tools/content run audit:publication
```

The intended catalog URL is `https://akirakato1.github.io/MyPutnam/content/v1/catalog.json`. Catalog package paths are relative to that file, while every ZIP filename is its outer SHA-256 digest and every package has a separate canonical content revision. Changing source content creates new immutable URLs rather than overwriting old package bytes.

Normal validation permits complete rights metadata whose `redistributionStatus` is still unresolved so the package system can be reviewed locally. Public deployment uses the stricter command below and is intentionally blocked for the current Putnam corpus until redistribution permission is established:

```text
npm --prefix tools/content run audit:publication -- --require-cleared-rights
```

`.github/workflows/validate-content.yml` validates pull requests and pushes without uploading content. `.github/workflows/publish-content.yml` is manual-only, applies the cleared-rights gate before artifact upload, and is not evidence that a public feed has been deployed. A later Android release will embed the exact audited catalog and ZIP bytes used to seed device storage.

## Historical archive development

The long-term archive target remains Putnam 1962–2025. Older years are curated locally in five-year batches under `content-src/` without rebuilding the APK each time. The complete archive integration must not run until `content-src/archive.json` reports `completedYears` exactly equal to `targetYears`.

Problem records follow `tools/content/SCHEMA.md`: one canonical statement and solution, exactly four progressive hint stages, one or more prerequisite refreshers, and catalog-backed topic identifiers. Start from `tools/content/templates/problem.template.json` and validate with `npm --prefix tools/content test`.

## Pen input

Finger input, a rubber-tip passive stylus, and a universal active-capacitive stylus all work through Android touch events. An active pen can offer a finer physical tip, but the app does not require hover, pressure, Bluetooth, or Samsung S Pen support; pressure is honored only when the tablet and pen report it.
