# Notebook Editor and Putnam Content Schema v2 Design

**Date:** 2026-10-01

## Purpose

Improve the existing offline Putnam Workbook before expanding its historical archive. The release must make handwriting responsive and reliable, add vector selection and transformation tools, simplify workbook navigation, stabilize mathematical reference content, and establish a canonical source schema for mass-porting older Putnam years.

The app remains a native, offline Android application for the Galaxy Tab A11+ (SM-X230). Existing progress and notebook work must survive an upgrade from the current signed APK.

## Scope

This release includes:

- responsive, buffered notebook persistence with no lost rapid strokes;
- Pen and Select editing modes;
- rectangular stroke selection, movement, proportional scaling, cut, copy, paste, deletion, and grouped undo/redo;
- simplified page navigation with previous/next buttons and a page counter;
- removal of page reordering, numbered page buttons, the palm-guard overlay, and the Original Paper view;
- stable hint controls and consistent offline KaTeX rendering for every content section;
- a documented and validated Putnam problem source schema v2;
- migration of existing notebooks and content records;
- a signed upgrade APK after automated and emulator verification.

The release does not include user-importable content packages, Android system clipboard integration, arbitrary rotation of selected strokes, freeform lasso selection, non-proportional stretching, or the 2020–2024 content expansion.

## Confirmed Root Causes

### Ink latency and disappearing strokes

The current view model persists every completed stroke immediately. Each commit writes an immutable stroke file and then durably rewrites the notebook manifest. When the operation finishes, the view model reloads the entire page from disk and publishes that older snapshot to the canvas. This creates visible pauses and permits a completed save to replace strokes that were drawn more recently but have not yet been persisted.

The input layer also stores only one pending completed stroke. AndroidX Ink reports finished strokes asynchronously. If another stroke completes before the first callback is delivered, the newer stroke replaces the pending slot, so the earlier stroke can disappear permanently.

### Gray touch-blocking rectangle

The rectangle is the enabled-by-default palm-guard overlay. It intentionally consumes new pointer events in part of the page, which explains why a stroke cannot begin there but can cross it after beginning elsewhere.

### Hint controls and mathematics

Hint documents are already converted to static KaTeX HTML during the desktop build. The unstable behavior comes from placing a full-height Android WebView beneath the header in a non-weighted Compose column. The embedded view can claim or overdraw the header region. A single constrained rendering component and explicit resource checks are required for statements, hints, prerequisites, and solutions.

## Notebook Interaction Model

### Tool modes

The notebook has two mutually exclusive tools:

- **Pen:** pointer input creates vector strokes using the selected brush.
- **Select:** dragging on an empty area creates a rectangular marquee. Pen input is disabled while this tool is active.

The active tool is visually distinct and accessible by text label and content description. Switching to Pen clears the active selection but does not change notebook content.

### Marquee selection

Selection uses page-normalized coordinates. A stroke is selected in full when any part of its rendered geometry intersects the marquee. This applies to every partially intersecting stroke. Hit testing considers the stroke polyline and half its brush width rather than relying only on a loose bounding-box overlap.

After pointer release, the marquee is replaced by the tight bounds of the selected strokes. An empty marquee clears the prior selection. Selection is limited to the active page.

### Move and scale

Dragging inside the selection moves all selected strokes as one group. Corner handles proportionally scale the group around the opposite corner. Movement and scaling are clamped so the transformed selection remains within the page. A minimum selection size prevents zero or near-zero scale operations.

The preview is applied in memory during the gesture. Releasing the pointer commits one grouped edit. Cancelling the gesture restores the pre-gesture geometry.

### Clipboard and deletion

When a selection exists, contextual controls provide Cut, Copy, Paste, Delete, and Cancel selection.

- Copy stores complete vector strokes and their relative geometry in an app-local in-memory clipboard.
- Cut performs Copy and removes the selected strokes as one grouped edit.
- Paste creates new stroke identifiers on the active page, places the group slightly down and right when space permits, clamps it to the page, and selects the pasted group.
- Delete removes all selected strokes as one grouped edit.

The clipboard works across pages and problems during the current app process. It is not written to Android's system clipboard and need not survive process death.

### Undo and redo

Undo and redo operate on logical edit groups rather than individual persistence calls. One pen stroke is one edit group. A move, scale, cut, paste, or selection deletion affecting many strokes is also one edit group. Performing a new edit after undo discards the redo branch.

## Notebook Persistence

### Optimistic state

Completed strokes and transformations are applied to `NotebookUiState` immediately. The canvas renders this authoritative in-memory state without waiting for storage. A persistence completion updates only save status and durable revision information; it never replaces a newer UI revision with a disk snapshot.

### Ordered batch saving

The view model sends immutable notebook mutations to a single ordered writer. Mutations received within a 350 ms inactivity window are persisted as one batch. New stroke files are written before one atomic manifest replacement records the resulting edit groups and active stroke references.

The writer exposes a flush barrier. Page changes, undo/redo, add/clear/delete page, Home, export, app backgrounding, and orderly view-model closure request a flush before dependent work proceeds. Drawing remains available while ordinary background persistence is running.

### Failure behavior

Pending edits remain visible if persistence fails. The save indicator changes to `Save failed`, retains the dirty mutations, and retries them on the next explicit or scheduled flush. A failed write cannot mark a mutation durable or remove it from memory. Export and navigation actions that require a consistent disk snapshot report the failure instead of silently exporting stale work.

### Notebook format migration

The manifest gains an explicit notebook format version and ordered edit-group history. Existing immutable stroke files remain valid. On first load, a version-1 manifest is mapped to version 2 by treating its active strokes as one initial history state. Inactive legacy references are not reconstructed as a redo branch, but their immutable files are left untouched. Before the atomic replacement, the successfully decoded version-1 manifest is preserved once as `manifest.bin.v1.bak`; a failed migration continues using the original manifest.

Transformed strokes receive new immutable identifiers. Their source strokes are retained and marked inactive through the edit group, enabling lossless undo without rewriting stroke files.

## Workbook User Interface

The notebook toolbar contains, from left to right:

1. Pen and Select mode controls;
2. Undo, Redo, and Erase last;
3. Add page, Clear page, and Delete page;
4. a flexible spacer;
5. previous-page control, `Page X of Y`, and next-page control aligned at the top right.

The old bottom page strip, numbered page buttons, and page reordering controls are removed. Page changes remain explicit; horizontal drawing gestures never turn pages. Previous and next controls are disabled at the first and last page respectively.

When strokes are selected, a contextual action row exposes Cut, Copy, Paste, Delete, and Cancel selection without hiding the page controls. Paste is enabled whenever the app-local clipboard contains strokes.

The palm-guard overlay and its settings controls are removed. Existing stored palm-guard fields remain readable for backup compatibility but no longer affect the UI.

## Reference Content Interface

The available tabs are Problem, Help, Prerequisites, and Solution. The Original Paper tab, PDF renderer, PDF navigation, and packaged PDF assets are removed from the runtime application.

Each tab places fixed navigation controls above a content body constrained with `weight(1f)` and full available width. An embedded WebView cannot measure into or overdraw the header. Revealed hint buttons remain present while content loads, scrolls, or switches.

All rich documents use the same script-free offline renderer and bundled KaTeX CSS/fonts. The build verifies that every generated page resolves its stylesheet and fonts and contains pre-rendered KaTeX markup for each source math run. Runtime WebViews remain network-blocked and JavaScript-disabled.

## Putnam Problem Source Schema v2

The source of truth remains one JSON record per problem. The schema is intentionally Putnam-specific for the historical archive and does not introduce runtime import or generic user-defined collections.

Each record contains:

- `schemaVersion`, fixed at `2`;
- `year`, integer from 1938 through the supported upper bound;
- `code`, one of A1–A6 or B1–B6;
- `ordinal`, unique integer 1–12 within the year;
- `statement`, a nonempty rich document;
- `hints`, exactly four ordered hint objects;
- `prerequisites`, an ordered list of zero or more prerequisite objects;
- `solution`, a nonempty rich document;
- `sources`, one or more HTTPS source citations.

Each hint requires `order`, `stage`, `title`, and `document`. Orders and stages are fixed as:

1. `nudge` — a non-spoiling observation or question;
2. `strategy` — the principal technique or useful reduction;
3. `knowledge` — the relevant theorem, identity, or knowledge-gap bridge;
4. `outline` — an ordered proof plan without copying the complete solution.

Rich documents continue to support headings, paragraphs, ordered or unordered lists, display mathematics, inline text/math runs, and local figures with alternative text and optional captions. TeX is data, never executable content. Remote figures, path traversal, active HTML, and unsafe TeX commands are rejected.

`originalPdf` is removed from the problem record. Locked source PDFs and their hashes remain in the development-only source directory for transcription and review, but the year builder does not copy them into application assets. Citation metadata preserves attribution without presenting the PDF in the app.

The repository includes:

- the authoritative JSON Schema;
- one complete canonical problem template;
- schema documentation explaining every field and hint stage;
- validator tests for structural, ordering, path, and TeX failures;
- a migration of all existing 2025 source records from schema v1 to v2.

## Build Pipeline

The year builder validates every problem before generating output. It renders statement, all four hints, every prerequisite, and the solution through the same renderer. It copies only referenced local figures plus shared static rendering assets. The output manifest and content database no longer contain original-PDF fields.

The immutable bundled content database is rebuilt because it contains no user state. The separate user database and notebook directory retain progress and work across the signed APK upgrade.

## Testing Strategy

Implementation follows test-driven development. Required regression coverage includes:

- two or more strokes completing before AndroidX Ink callbacks are delivered, with every stroke emitted once;
- ten rapid strokes appearing immediately and remaining ordered while repository writes are delayed;
- batching multiple strokes into one manifest commit;
- a completed older save never replacing newer UI state;
- flush barriers for page navigation, Home, export, backgrounding, and closure;
- persistence failure retaining visible dirty edits and succeeding on retry;
- version-1 notebook migration preserving existing active strokes and pages;
- segment/marquee intersection, including partial intersections and brush width;
- empty selection, page-bound clamping, proportional scale limits, and transform previews;
- grouped undo/redo for move, scale, cut, paste, and delete;
- cross-page and cross-problem app-local clipboard use;
- removal of the palm guard, Original Paper tab, page numbers, and reorder buttons;
- top-right page index and correctly disabled previous/next controls;
- hint buttons remaining visible above the renderer;
- pre-rendered KaTeX and resolvable local CSS/fonts for statements, hints, prerequisites, and solutions;
- schema-v2 validation and deterministic year builds;
- signed upgrade installation retaining progress and notebook data.

Release verification runs all Node, Python, JVM, and Android instrumented tests, Android lint, release assembly, APK signature/package/SDK inspection, and a same-signature upgrade rehearsal. The deliverable is a newly versioned signed APK and checksum. Physical SM-X230 smoke testing remains the final user-side confirmation.
