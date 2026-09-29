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
