# Offline Putnam Workbook — Product and Technical Design

**Date:** 2026-09-29

**Status:** Approved in conversation; awaiting written-spec review

**Target device:** Samsung Galaxy Tab A11+ (SM-X230), 1920 × 1200, Android 16

**Distribution:** Personally sideloaded, self-contained APK

## 1. Purpose

Build a personal, completely offline Android workbook for practicing historical William Lowell Putnam Mathematical Competition problems. The learner browses by competition year and problem, opens one problem, and works on it in a persistent split-screen notebook. When stuck, the learner may progressively reveal hints, prerequisite knowledge, a solution outline, and a fully explained solution.

Success means the learner can install one APK, disconnect the tablet from the internet, browse the complete archive, read correctly rendered mathematics, write multi-page work with a finger or generic capacitive stylus, close and reopen the app without losing work, and export or back up their data.

## 2. Scope

### Included

- A year-first historical archive browser.
- A problem list containing the standard A1–A6 and B1–B6 entries where applicable.
- A split problem/workbook workspace optimized for landscape use.
- Progressive, pre-generated hints and prerequisite refreshers.
- A clear, pre-generated explanation of a complete solution.
- Persistent, page-based vector-ink notebooks linked to individual problems.
- Local progress markers and revealed-hint counts.
- Original source PDFs as an authoritative rendering fallback.
- Local backup, restore, and workbook PDF export.
- One self-contained sideloadable APK with no runtime downloads.

### Excluded

- Adaptive recommendations, daily practice, or a guided curriculum.
- Online accounts, synchronization, cloud backup, or collaborative notebooks.
- On-device generative AI or live hint generation.
- Automated proof grading or handwriting recognition.
- Public app-store or content redistribution in the initial project.

## 3. Product Principles

1. **Offline means complete.** Problems, hints, prerequisite notes, explanations, fonts, diagrams, source PDFs, and rendering assets are packaged in the APK.
2. **The problem remains visible while working.** The default workspace is a persistent landscape split view.
3. **Help is progressive.** The app reveals only the next requested level of help and records how much assistance was used.
4. **Ink is durable.** A completed stroke is saved without requiring an explicit Save action.
5. **Source fidelity wins.** The app never exposes raw LaTeX and always provides the original document when normalized content cannot be trusted.
6. **The target hardware is touch-first.** The SM-X230 cannot distinguish a generic capacitive pen from a finger, so core notebook behavior must not require active-stylus events.

## 4. Primary User Flow

### Main menu

The app opens directly to a main menu with three primary actions:

- **Continue:** Resume the most recently opened problem on its last active notebook page.
- **Select problem:** Browse the archive by year, then select a problem.
- **Settings:** Configure notebook behavior, appearance, backup, and attribution.

If no problem has been opened, Continue is disabled and explains why.

### Archive browser

The first screen lists competition years in descending order. Selecting a year shows its problems in competition order. Each problem displays:

- identifier, such as A1 or B4;
- status: untouched, in progress, or completed;
- number of revealed hints, from zero through four;
- notebook page count, when nonzero.

There is no ranking, recommendation, or forced sequence.

### Problem workspace

The workspace uses a landscape split:

- **Left panel:** Problem statement, Help, Prerequisites, Solution, and Original Paper views.
- **Right panel:** The page-based notebook and drawing controls.

A persistent Home control returns directly to the main menu after autosaving. Android Back follows the same behavior from the workspace. Back from the archive problem list returns to the year list, and Back from the year list returns to the main menu.

## 5. Help and Solution Model

Each problem provides four hints followed by the final solution in this fixed sequence:

1. a small directional nudge;
2. a relevant technique or key observation;
3. a theorem, formula, or prerequisite idea with a concise refresher;
4. a solution outline;
5. a fully explained solution, tracked separately from the hint count.

Only the next unrevealed stage is offered. Revealing a stage requires a deliberate tap and records the highest stage used. Previously revealed material remains available offline. The complete solution is visually separated from hints, and opening it is recorded independently, to reduce accidental spoilers.

The prerequisite view may also be opened directly. It contains only material relevant to that problem and avoids revealing the actual chain of solution steps unless the learner advances to the corresponding hint level.

## 6. Notebook Experience

### Input assumptions

On the SM-X230, finger input, a rubber-tip stylus, and a universal active-capacitive stylus all arrive as ordinary touch input. The notebook therefore:

- accepts single-pointer touch strokes without requiring stylus detection;
- does not depend on pressure, tilt, hover, pen buttons, or Bluetooth;
- includes a movable, resizable palm-guard region that consumes touches;
- supports right- and left-handed guard presets;
- exposes large undo, redo, and eraser controls;
- avoids one-finger swipe navigation while drawing.

The input layer still records Android tool-type and pressure information when available. On a future device with a compatible active pen, the app may automatically enable true stylus-only writing and richer stroke data without changing the notebook file format.

### Pages and tools

Each problem owns an independent notebook with one initial page. The user may:

- add, delete, and reorder pages;
- choose blank or square-grid paper per page;
- select pen color and width;
- erase whole strokes;
- undo and redo recent notebook actions;
- clear a page after confirmation;
- navigate with explicit previous/next buttons or a page strip;
- view a lightweight horizontal page-turn animation.

Explicit page controls avoid conflicts between writing and swipe gestures. Deleting a page, clearing a page, and restoring a backup require confirmation because they can remove work.

### Ink representation

Ink is stored as resolution-independent vector strokes in page coordinates. Each stroke contains ordered input points, timestamps, tool metadata when available, and brush attributes. Finalized strokes are immutable; undo/redo changes which strokes are active. This representation supports sharp screen rendering and faithful PDF export.

## 7. Architecture

### Application shell

The app is a native Kotlin Android application using Jetpack Compose for screens and navigation. It targets the SM-X230's Android 16 environment and uses Android 10 (API 29) as the minimum supported version. API 29 aligns with the lower bound for Android's low-latency graphics support while allowing future reuse on older tablets.

### Major components

1. **Navigation shell** — Main menu, archive browser, workspace, settings, and file export/import flows.
2. **Content repository** — Read-only access to problems, enrichment, attribution, and original documents.
3. **Progress repository** — Problem status, reveal history, recent-problem state, and settings.
4. **Notebook engine** — Touch capture, vector-stroke rendering, page operations, palm guard, and undo/redo.
5. **Math content viewer** — Offline structured text and vector mathematics rendering, with local assets only.
6. **PDF viewer/exporter** — Original-document fallback and generated workbook export.
7. **Content build pipeline** — Source acquisition, normalization, enrichment validation, asset generation, and package assembly.

Each component exposes a narrow interface and does not read another component's storage directly.

### Rendering strategy

The reference panel uses native controls around a local, read-only mathematical-content renderer. Normal prose is stored as structured text. Equations and unsupported diagrams are converted during the desktop build pipeline into scalable vector assets. A bundled local HTML renderer may lay out prose and inline vector mathematics, but it never fetches remote content or executes source-provided scripts.

The notebook remains fully native. It does not run inside the mathematical-content renderer.

## 8. Content Sources and Pipeline

### Sources

The initial source set is:

- the Mathematical Association of America Putnam archive for official recent problems and solutions;
- Kiran Kedlaya's Putnam archive for historical indexing and references;
- the `rpxgit/The-Putnam-Archive` mirror for available TeX and PDF source files.

Every content record stores source attribution and a source checksum. Source precedence is explicit: an official MAA document overrides an unofficial mirror when they disagree.

The mirror states that MAA problem text is copyrighted. The completed artifact is therefore a personal, noncommercial study build. Public redistribution of the populated content package requires a separate permission and licensing review.

### Normalization

The build pipeline performs these steps:

1. acquire the approved source documents;
2. verify file hashes and record provenance;
3. parse TeX where source is available;
4. extract or transcribe older PDF-only material into the normalized schema;
5. preserve the source PDF unchanged;
6. convert prose, formulas, tables, and figures into offline display assets;
7. attach generated hints, prerequisite notes, solution outlines, and explanations;
8. validate the structured record;
9. render review screenshots and compare them with the source document;
10. package the validated content database and assets into the APK.

Content is generated and validated on the development computer. No TeX compiler, OCR engine, or language model is required on the tablet.

### Problem record

At minimum, each record contains:

- competition year and canonical problem identifier;
- normalized problem statement;
- topic metadata for maintenance, not recommendation;
- four ordered hints;
- prerequisite entries;
- complete explanatory solution and a separately tracked reveal state;
- source names, URLs, and checksums;
- original PDF asset and page location;
- rendering status and review status;
- content schema version.

### Rendering verification

The pipeline rejects content with:

- raw or unexpanded TeX commands in visible output;
- missing fonts or glyphs;
- unsupported formula constructs without a vector fallback;
- clipped or overflowing equations;
- missing figures, tables, or diagrams;
- empty rendered pages;
- broken internal asset references.

The original paper is always available from the workspace. Android's local PDF renderer displays it without a network connection.

## 9. Storage and Data Integrity

### Separation of data

The app maintains three storage domains:

1. **Immutable content:** A prepopulated Room/SQLite database and bundled assets shipped with the APK.
2. **Mutable user state:** A separate Room database for status, reveal history, most-recent problem, and settings.
3. **Notebook files:** Versioned vector-ink documents stored in app-private storage and referenced from user state.

This separation prevents a content update from overwriting user notes.

### Autosave

A stroke is committed when it ends. Page-level metadata operations use transactions. The app flushes pending state when it moves to the background and restores the exact problem, panel, and notebook page after restart. A crash may lose the stroke currently in progress but must not corrupt earlier strokes.

### Backup and restore

Settings exposes a user-initiated backup through Android's document picker. A backup contains:

- the user database;
- all notebook documents;
- a manifest with app, schema, and content versions;
- integrity hashes.

Restore validates the manifest and hashes before replacing any local state. Existing state is retained if validation or migration fails.

### PDF export

The user may export a single problem workbook containing the problem identifier and statement followed by notebook pages. An export option controls whether revealed hints and the full solution are included. Vector ink remains sharp in the resulting PDF.

## 10. Settings

Settings includes:

- handedness and palm-guard preset;
- default page background: blank or square grid;
- default pen color and width;
- light, dark, or system theme;
- keep screen awake while a workspace is open;
- backup and restore;
- attribution, content sources, app version, and content version.

Changing defaults does not retroactively alter existing notebook pages or strokes.

## 11. Offline, Privacy, and Security

- The manifest requests no internet permission.
- The app contains no account system, telemetry, advertising, or analytics SDK.
- All user work remains in app-private storage unless the user explicitly exports it.
- Source PDFs are treated as untrusted build inputs and normalized outside the tablet app.
- Local HTML content has remote loading, arbitrary navigation, and source-provided scripting disabled.
- Restore accepts only the documented backup format and validates versions and checksums before applying it.

## 12. Error Handling

- If structured problem content fails its integrity check, the app identifies the affected entry and offers the original PDF rather than showing malformed mathematics.
- If an original PDF is unexpectedly missing or corrupt at runtime, the normalized problem remains usable and the app reports a content-integrity error. A build with this condition fails release acceptance.
- If notebook loading fails, the app preserves the unreadable file, opens a new recovery page only after informing the user, and never silently deletes data.
- If export fails, local notebook data is unaffected and the app reports a concise corrective action.
- If a backup is incompatible or corrupt, restore stops before replacing current data.
- Content migrations and user-data migrations are separate; user-data migrations are never destructive fallbacks.

## 13. Verification Strategy

### Automated tests

- Unit tests for content schema validation, ordering, hint progression, statuses, and settings.
- Unit tests for vector-stroke serialization, undo/redo, page operations, and backup manifests.
- Database migration tests that preserve user progress and notebooks.
- Compose tests for main-menu, year, problem-list, workspace, Home, and Android Back navigation.
- Instrumented tests for process death, cold restart, rotation, and APK content updates.
- Rendering checks for raw TeX, missing glyphs/assets, overflow, and blank output.
- Stress tests for long drawing sessions and notebooks with many pages.

### Device-oriented checks

The 2025 vertical slice is tested in landscape at 1920 × 1200 and exercised with:

- a finger;
- a rubber-tip capacitive stylus;
- a universal active-capacitive stylus when available.

Checks cover stroke latency, accidental palm marks, palm-guard movement, page navigation, panel scrolling, background/resume behavior, and readability at normal and enlarged text sizes.

### Acceptance criteria for the 2025 vertical slice

- All twelve problems are accessible from the 2025 problem list.
- Every problem has four progressive hints, prerequisite material, and a full explanation.
- No visible raw LaTeX, missing glyphs, clipped equations, or missing diagrams remain.
- Every entry provides an original-paper fallback.
- All notebook operations work without network access.
- Completed strokes survive app backgrounding, force-stop, restart, and a test APK upgrade.
- Continue resumes the exact most-recent problem and notebook page.
- Problem statuses and hint counts update correctly.
- Backup/restore and PDF export reproduce the expected content.
- The app requests no internet permission.

## 14. Delivery Phases

### Phase 1: 2025 vertical slice

Build the complete application flow and content pipeline around the twelve 2025 problems. This is not a throwaway prototype; it establishes the production schema, storage, rendering, enrichment, and test harness.

### Phase 2: archive expansion

Run the validated pipeline across the historical archive in batches. Each batch must pass structural and rendering checks before packaging. PDF-only and older documents receive focused transcription and visual review.

### Phase 3: complete personal APK

Package the validated archive, enrichment, source PDFs, fonts, and app into one APK. Run an offline installation and upgrade rehearsal on the target tablet, then produce the final APK and local backup instructions.

All releases use the same application identifier and a retained signing key so Android treats a sideloaded APK as an in-place upgrade and preserves app-private user data. Upgrade rehearsals still begin with a successful user-data backup.

## 15. Assumptions

- The app is initially for one learner and one primary tablet.
- English is the only content and interface language.
- Landscape is the primary workspace orientation; other screens remain usable in portrait.
- The complete archive means all recoverable official competition problems beginning with the modern Putnam competition in 1938 through the latest validated year included in the build.
- The first validated content year is 2025.
- Public distribution is out of scope unless content permissions are resolved separately.
