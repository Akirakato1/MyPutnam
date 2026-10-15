# Putnam Workbook

Putnam Workbook is a native, offline-first Android study app for browsing competition problems, filtering them by topic, revealing progressive hints and prerequisite refreshers, and writing solutions in a page-based notebook.

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
node tools/content/src/build-release.mjs --release content-src/releases/putnam-2020-2025-v0.4.0.json --catalog content-src/library.json --source content-src --output build/release/putnam-2020-2025-v0.4.0/app-assets --receipt build/release/putnam-2020-2025-v0.4.0/receipt.json
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

## Historical archive development

The long-term archive target remains Putnam 1962–2025. Older years are curated locally in five-year batches under `content-src/` without rebuilding the APK each time. The complete archive integration must not run until `content-src/archive.json` reports `completedYears` exactly equal to `targetYears`.

Problem records follow `tools/content/SCHEMA.md`: one canonical statement and solution, exactly four progressive hint stages, one or more prerequisite refreshers, and catalog-backed topic identifiers. Start from `tools/content/templates/problem.template.json` and validate with `npm --prefix tools/content test`.

## Pen input

Finger input, a rubber-tip passive stylus, and a universal active-capacitive stylus all work through Android touch events. An active pen can offer a finer physical tip, but the app does not require hover, pressure, Bluetooth, or Samsung S Pen support; pressure is honored only when the tablet and pen report it.
