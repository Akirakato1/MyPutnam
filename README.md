# Putnam Workbook

Putnam Workbook is a native, offline-first Android study app for browsing Putnam problems, revealing progressive hints, and working solutions in a page-based handwriting notebook.

The first release target is a self-contained 2025 vertical slice for Android 10 and newer. It intentionally declares no internet permission; problem content, rendered mathematics, source PDFs, progress, settings, and notebook pages all remain on the device.

## Local build

1. Install JDK 17 and Android SDK Platform 37 with Build Tools 36.0.0.
2. Add an untracked `local.properties` containing `sdk.dir=<your Android SDK path>`.
3. Run `./gradlew :app:assembleDebug` (`gradlew.bat` on Windows).

The app still targets Android 16/API 36, and instrumented tests use an API 36 tablet emulator:

```text
./gradlew :app:connectedDebugAndroidTest
```

## Signed release build

The checked-in release version is code `2`, name `0.2.0`, with immutable content version `2025.1`. To retain upgradeability, every release must be signed by the same private key.

1. Create a password-protected keystore outside the repository and back it up securely.
2. Copy `keystore.properties.example` to the ignored `keystore.properties`, then enter the keystore path, alias, and passwords.
3. Build with `./gradlew :app:assembleRelease`. For an upgrade rehearsal, use `-PversionCodeOverride=1` only for the baseline APK.
4. Install with `adb install -r app/build/outputs/apk/release/app-release.apk`.

Build the packaged content and its Room database before releasing:

```text
npm --prefix tools/content run build -- --year 2025 --content-version 2025.1
python tools/content/src/build-content-db.py --manifest app/src/main/assets/content/2025/manifest.json --output app/src/main/assets/databases/content.db
```

Place the final APK at `dist/putnam-workbook-2025.apk`, write its SHA-256 to `dist/putnam-workbook-2025.apk.sha256`, and verify it:

```text
powershell -ExecutionPolicy Bypass -File scripts/verify-release.ps1 dist/putnam-workbook-2025.apk
```

The verifier checks the signature, package name, SDK range, absence of the INTERNET permission, and checksum. See `scripts/device-smoke-test.md` for the final SM-X230 airplane-mode checklist.

## Pen input

Finger input, a rubber-tip passive stylus, and a universal active-capacitive stylus all work through Android touch events. An active pen can provide a finer physical tip, but the app does not require hover, pressure, Bluetooth, or Samsung S Pen support; pressure is honored only when the tablet and pen report it.
