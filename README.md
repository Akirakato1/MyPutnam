# MyPutnam

MyPutnam is a native, offline-first Android study app for browsing competition problems, filtering them by topic, revealing progressive hints and prerequisite refreshers, consulting a math-reference handbook, and writing solutions in a page-based notebook.

The current private development build packages all 72 Putnam problems from 2020 through 2025 and 97 math-reference articles as 169 immutable packages for Android 10 and newer. Installed content, rendered mathematics, settings, progress, notebook pages, and Free Notebooks remain usable offline. The app declares `INTERNET` and `ACCESS_NETWORK_STATE` only for catalog refreshes and explicit package downloads; cleartext traffic is disabled, WebView content cannot load the network, and locked source PDFs or TeX files are never packaged.

## Local build

1. Install JDK 17 or newer and Android SDK Platform 37 with current build tools.
2. Add an untracked `local.properties` containing `sdk.dir=<your Android SDK path>`.
3. Run `gradlew.bat :app:assembleDebug` on Windows.

The app targets Android 16/API 36. When an API 36 tablet emulator or device is attached, run `gradlew.bat :app:connectedDebugAndroidTest`.

## Content and release builds

The checked-in application metadata is version `0.5.0` (code `5`). The exact private starter-library selection and catalog hash are frozen in `content-src/releases/myputnam-v0.5.0-development.json`. Its `local-development` scope is deliberate because the current catalog records unresolved Putnam redistribution rights; do not present this build or its Putnam packages as a public content release.

From a clean checkout, install the pinned tools, run the full suite, and reproduce the publication and starter bundle:

```text
npm ci --prefix tools/content
npm --prefix tools/content test
npm --prefix tools/content run build:publication
npm --prefix tools/content run audit:publication
npm --prefix tools/content run build:android-starter
```

The app embeds the byte-exact starter output under `app/src/main/assets/content-library/`: one catalog, one release manifest, 169 digest-named immutable packages, and the fixed offline renderer. Runtime initialization indexes those packages and lazily verifies/extracts them; the obsolete immutable `content.db` path is no longer used. User progress remains in `user.db`, while notebook and Free Notebook files remain separate from content packages.

For a signed release, keep the password-protected keystore outside the repository, copy `keystore.properties.example` to the ignored `keystore.properties`, and supply the preserved alias and passwords. Never commit or print those credentials. Build with `gradlew.bat :app:assembleRelease`.

Place the final APK and checksum at:

```text
dist/myputnam-v0.5.0.apk
dist/myputnam-v0.5.0.apk.sha256
```

Verify the exact APK artifact:

```text
powershell -ExecutionPolicy Bypass -File scripts/verify-release.ps1 dist/myputnam-v0.5.0.apk content-src/releases/myputnam-v0.5.0-development.json
```

The verifier checks the application ID, v0.5.0/code 5, SDK range, exact permission allowlist, disabled cleartext traffic, system-only trust roots, retained signer certificate, adjacent checksum, catalog/release metadata, all 169 package hashes and inner members, the offline renderer, and absence of legacy loose content or forbidden source/signing files.

An in-place update signed with the retained key preserves progress, notebooks, and Free Notebooks while removing only the obsolete immutable content database. Uninstalling still removes Android app data, so make a backup first if a clean reinstall is necessary. Follow `scripts/device-smoke-test.md` for the SM-X230 clean-install and upgrade checklist.

## Downloadable content feed

The Android app includes the Download Problems library UI, catalog refresh, verified package installation/update/deletion, backup of desired library state, and offline reopening. The same catalog format supports Putnam, other competitions, subject-focused problem sets, and reference collections without changing the notebook format.

The intended catalog URL is `https://akirakato1.github.io/MyPutnam/content/v1/catalog.json`. Catalog package paths are relative to that file, while every ZIP filename is its outer SHA-256 digest and every package has a separate canonical content revision. Changing source content creates new immutable URLs rather than overwriting old package bytes.

That production feed is not deployed yet. Normal validation permits explicit unresolved rights so the package system can be reviewed privately. The default deployment audit remains blocked by unresolved Putnam redistribution rights:

```text
npm --prefix tools/content run audit:deployment
```

`.github/workflows/validate-content.yml` validates pull requests and pushes without uploading content. `.github/workflows/publish-content.yml` remains manual-only; its presence is not evidence that a public feed exists. Its normal `rights-cleared` mode applies the cleared-rights gate before artifact upload. For the owner's temporary personal feed only, the browser workflow also offers `temporary-personal-public`, which still runs the complete tests and integrity/security audit but requires the exact acknowledgement `I understand this temporary feed is public`. This mode does not make the feed private: while GitHub Pages is published from a public repository, anyone with the URL can access it.

Personal-device browser workflow:

1. Make the repository public on GitHub.
2. Open **Actions → Publish content feed → Run workflow** in any browser.
3. Choose `temporary-personal-public`, enter the exact acknowledgement above, and run it.
4. After the workflow succeeds, refresh Download Problems in MyPutnam and download the desired content.
5. Make the repository private again when finished; already downloaded packages continue to work offline.

Important: making the repository private unpublishes GitHub Pages. Every time it is later made public again, rerun **Publish content feed** from the browser before expecting the app endpoint to work. No push, visibility change, or other event deploys content automatically.

## Historical archive development

The long-term archive target remains Putnam 1962–2025. Older years are curated locally in five-year batches under `content-src/` without rebuilding the APK each time. The complete archive integration must not run until `content-src/archive.json` reports `completedYears` exactly equal to `targetYears`.

Problem records follow `tools/content/SCHEMA.md`: one canonical statement and solution, exactly four progressive hint stages, one or more prerequisite refreshers, and catalog-backed topic identifiers. Start from `tools/content/templates/problem.template.json` and validate with `npm --prefix tools/content test`.

## Pen input

Finger input, a rubber-tip passive stylus, and a universal active-capacitive stylus all work through Android touch events. An active pen can offer a finer physical tip, but the app does not require hover, pressure, Bluetooth, or Samsung S Pen support; pressure is honored only when the tablet and pen report it.
