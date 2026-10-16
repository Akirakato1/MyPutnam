# Free Notebooks and Oriented Selection Transforms

**Status:** Approved in conversation; awaiting written-spec review  
**Date:** 2026-10-16

## Purpose

Extend the offline workbook with two related capabilities:

1. Selection editing that can rotate and mirror selected handwriting while preserving the existing move, scale, clipboard, undo, autosave, and page behavior.
2. A Free Notebook area for named, offline notebooks that are not attached to a problem and that reuse the mature notebook editor.

The feature is for tablet users working with a finger, passive stylus, or universal active stylus. It must remain fully offline and preserve the current 48dp touch targets and fixed-height notebook toolbars.

## Scope

### Included

- Free rotation through a handle above an oriented selection box.
- Optional 15-degree rotation snapping, off by default.
- Horizontal and vertical selection flips.
- One-step undo and redo for each committed rotate or flip operation.
- A Home-screen entry for Free Notebook.
- A named free-workspace library with create, open, rename, delete, creation date, and modified date.
- Autosave after a workspace has been named and created.
- Explicit Save to flush pending autosave work.
- Save As to duplicate the current workspace and switch editing to the new copy.
- Duplicate-name validation with a red error treatment and shake animation.
- Backup and restore coverage for free workspaces.

### Not included

- Importing external notebook files.
- PDF export for free workspaces.
- Folders, tags, thumbnails, or cloud synchronization for free workspaces.
- Persisting an active selection or an in-progress transform gesture.
- Converting existing problem notebooks to a new schema.
- Treating selected strokes as a permanently grouped object after deselection.

## Design principles

- Reuse the existing notebook engine rather than maintain a second editor.
- Keep problem content and free-workspace metadata separate.
- Persist transformed stroke coordinates, not a stack of display transforms.
- Make every committed transform an ordinary replacement edit so existing undo, redo, autosave, and backup behavior continues to apply.
- Use immutable UUIDs for storage identity; user-facing names never become paths.
- Do not clip or distort handwriting to satisfy page boundaries.
- Fail atomically: interrupted create, rename, delete, or Save As operations must not damage an existing workspace.

## Architecture decision

The selected approach adds a `FreeWorkspaceRepository` and reuses the existing `NotebookRepository` and `NotebookViewModel` for page and stroke editing.

Each free workspace receives a UUID and an internal reserved notebook key:

```text
ProblemKey("__local__", "free-workspaces", workspaceUuid)
```

This key is an implementation detail. It is never inserted into or returned by the problem content database, so free workspaces cannot appear in the problem library or topic search.

This approach was selected over two alternatives:

- Generalizing every problem-facing API from `ProblemKey` to a universal notebook identity would be conceptually pure but would create unnecessary migration and exporter risk.
- A separate free-notebook editing engine would duplicate autosave, undo, rendering, and selection logic and allow the two editors to drift.

## Oriented selection model

`NotebookSelectionState` will retain the selected stroke IDs and preview strokes and gain an oriented frame. The frame contains:

- Center point.
- Width and height in the current canvas's pixel coordinate system.
- Clockwise rotation in radians.
- The viewport dimensions against which the frame was calculated.

The existing normalized axis-aligned bounds remain available for broad intersection checks and compatibility with current selection code. The oriented frame is authoritative for drawing handles, hit testing, move, scale, flip, and rotation while the selection is active.

Selection orientation is transient:

- It remains stable through committed move, scale, flip, and rotate operations while the selection stays active.
- Tapping outside clears it.
- Reselecting the same strokes creates a fresh axis-aligned frame with zero rotation.
- A material viewport-size change cancels any preview and rebuilds the active selection as an axis-aligned frame. This prevents stale pixel geometry after window or device resizing.

Persisted `StrokeRecord` and `StrokeSample` formats do not change.

## Transform math

All rotate and flip calculations occur in pixel coordinates so a non-square canvas does not distort angles.

For a stroke sample:

1. Convert its normalized coordinates to pixels.
2. Translate it relative to the oriented frame center.
3. Rotate it into the frame's local coordinate system when needed.
4. Apply the requested transform.
5. Rotate it back to canvas coordinates.
6. Translate it back around the selection center.
7. Convert it to normalized coordinates only after page-boundary handling succeeds.

### Rotation

The gesture records the initial pointer angle and selection angle. Each drag update computes the pointer's current `atan2` angle around the frame center. The difference produces a preview based on the original selected strokes, avoiding cumulative floating-point drift.

When snapping is disabled, the requested angle is used directly. When snapping is enabled, the absolute resulting angle is rounded to the nearest 15 degrees.

Rotation preserves the selection's center whenever the transformed visible bounds fit. If the transformed group crosses an edge but can fit on the page, the complete group and frame receive the smallest whole-group translation needed to bring it back on-page. If an unusually large selection cannot fit at the requested angle, the preview remains at the last valid angle from the current gesture. Samples are never individually clamped.

### Flips

- **Flip horizontal** mirrors local X coordinates, producing a left-to-right mirror across the frame's local vertical centerline.
- **Flip vertical** mirrors local Y coordinates, producing a top-to-bottom mirror across the frame's local horizontal centerline.

The frame angle and dimensions remain unchanged after a flip.

### Scaling after rotation

Corner dragging continues to scale uniformly. The opposite oriented corner is the anchor. Pointer movement is projected onto the frame's local axes, and the smaller valid scale factor is used. Existing minimum-selection-size behavior remains. Scaling stops at the largest value that keeps the transformed group on-page.

### Commit and preview behavior

- Move, scale, rotate, and flip previews use replacement stroke IDs prefixed for preview use.
- Gesture cancellation removes the preview and restores the committed strokes.
- Releasing the rotation handle commits one replacement edit.
- Each flip-button press commits one replacement edit immediately.
- Existing undo and redo treat each commit as one edit group.

## Selection overlay and gestures

The overlay draws the oriented rectangle, four corner handles, and a rotation handle. The rotation handle is a small circle connected by a short stem to the frame's local top-edge center. Its visible size remains compact, but its hit target is at least 48dp.

Hit-test priority is:

1. Rotation handle.
2. Corner scale handles.
3. Interior of the oriented rectangle for moving.
4. A replacement marquee outside the current selection.

Point-in-selection tests inverse-rotate the pointer into the frame's local coordinate system before comparing it with half-width and half-height. Taps on the rotation handle or its hit area do not clear the selection. A tap outside all selection affordances clears it.

The snap toggle is session state. It defaults off whenever an editor destination is opened and is not written to settings or notebook storage.

## Selection toolbar

Select mode's fixed second row contains seven 48dp icon controls:

1. Cut.
2. Copy.
3. Paste.
4. Delete selection.
5. Flip horizontal.
6. Flip vertical.
7. Toggle 15-degree rotation snapping.

The row requires 336dp and fits the established 504dp narrow split-pane target without horizontal scrolling. Cut, Copy, Delete, and both Flip controls are disabled without a selection. Paste depends on clipboard content. The snap toggle is always enabled in Select mode and uses selected-state semantics. Every icon has a TalkBack content description; visual labels are not added.

## Free-workspace catalog

The catalog is a versioned, strictly validated file stored under the notebook backup root:

```text
notebooks/__local__/free-workspaces/catalog.json
```

Each record contains:

```text
id: UUID
name: display string
createdAtEpochMillis: non-negative long
modifiedAtEpochMillis: non-negative long, not earlier than createdAt
```

Workspace notebook data remains in the existing repository layout below:

```text
notebooks/__local__/free-workspaces/<workspace UUID>/
```

The catalog codec rejects unknown fields, unsupported versions, duplicate IDs, duplicate normalized names, invalid UUIDs, invalid timestamps, and unsafe or invalid names.

### Name rules

- Trim leading and trailing whitespace before storage.
- Reject empty names.
- Reject ISO control characters.
- Limit names to 80 Unicode code points.
- Compare uniqueness using NFKC normalization and `Locale.ROOT` lowercase conversion.
- Preserve the user's trimmed spelling and capitalization for display.

Rename permits retaining the same workspace's normalized name. Create and Save As require a name not used by any existing workspace.

### Ordering and dates

The library sorts by `modifiedAt` descending, then normalized name, then UUID for deterministic ties. Dates are displayed using the device locale and timezone.

`modifiedAt` advances only after a durable notebook save succeeds or a rename succeeds. Opening or merely selecting a workspace does not alter it.

## Atomic repository operations

`FreeWorkspaceRepository` serializes catalog and lifecycle operations through a mutex and uses durable atomic file writes.

### Create

1. Validate uniqueness.
2. Create a staged one-page notebook using the configured default paper style.
3. Atomically publish the UUID directory.
4. Atomically add the catalog record.
5. Roll back the published directory if catalog publication fails.

No workspace exists before the naming dialog succeeds.

### Rename

Validate the name and atomically replace the catalog. Notebook files do not move because paths use UUIDs.

### Save As

1. Wait for the source notebook's autosave coordinator to become idle.
2. Validate the new name.
3. Clone the complete notebook manifest, pages, immutable stroke files, applied edit cursor, and undo/redo history into a staged UUID directory.
4. Rewrite the cloned manifest's internal problem key to the new reserved key.
5. Atomically publish the directory and catalog record.
6. Navigate to the new workspace with the original editor destination replaced, so subsequent autosaves target the new copy.

The source remains unchanged if any step fails.

### Delete

After user confirmation, move the UUID directory to an internal tombstone, remove the catalog record atomically, and then delete the tombstone. If catalog replacement fails, restore the directory. Delete has no in-app undo after the confirmation is accepted.

### Crash recovery

Catalog loading removes abandoned staging directories. Tombstones are reconciled against the catalog: a still-referenced workspace is restored, while an unreferenced tombstone is deleted. Unknown ordinary directories are not surfaced as workspaces and are preserved for diagnosis rather than silently deleted.

If the catalog itself cannot be decoded, preserve it with a `.corrupt` suffix and surface a library error instead of overwriting it with an empty catalog.

## Autosave and modified timestamps

Newly created workspaces immediately use the existing notebook autosave coordinator. No unnamed draft exists.

The notebook editor reports successful durable revisions to the free-workspace coordinator. The coordinator touches `modifiedAt` only after the corresponding revision is durable. A failed save leaves the current in-memory editing state visible, retains the prior modified time, and shows the existing Save failed state.

The Free Notebook header exposes:

- **Save:** request an immediate flush and wait for pending edits. It is safe when nothing is pending.
- **Save As:** open the prefilled naming dialog, then follow the atomic duplication workflow.

Save As uses the current workspace name exactly as the initial field value. Confirming without changing it therefore triggers the duplicate-name feedback rather than silently inventing a suffix.

Back navigation waits for pending autosave work. If flushing fails, the editor remains open and presents the failure instead of navigating away.

## Navigation and screens

### Home

Add a full-width **Free Notebook** button between **Select problem** and **Settings**.

### Free Notebook library

The library has a top app bar with Back, title, and New workspace. Its list is sorted by most recently modified. Each row contains:

- Workspace name.
- Created date.
- Last modified date.
- Rename icon.
- Delete icon.

The entire remaining row area opens the workspace. Rename and delete icons consume their taps and do not open it. An empty state explains that no free workspaces exist and repeats the New workspace action.

Delete displays a confirmation dialog naming the workspace. Rename displays the shared name dialog prefilled with the current name.

### Naming dialog

Create, Rename, and Save As share one dialog component with context-appropriate title and confirmation text.

- Blank or otherwise invalid names show an inline message.
- A duplicate-name response leaves the dialog open.
- Duplicate feedback applies an error-colored outline/glow and a short horizontal shake animation to the field.
- The error treatment clears after the user changes the text.
- Dialog state survives ordinary recomposition and configuration recreation.

### Free Notebook editor

The editor has no problem statement, hint, or solution pane. The notebook consumes the available workspace beneath a compact header containing:

- Back to the Free Notebook library.
- Current workspace name.
- Save.
- Save As.

The two existing fixed notebook toolbar rows appear below the header unchanged except for the new Select-mode transform icons. Page navigation, pen palette, width slider, selection controls, and canvas behavior are shared with problem notebooks.

Navigating after Save As replaces the original editor route with the new UUID route. Pressing Back then returns to the library rather than the source workspace.

## Component boundaries

### `FreeWorkspaceRepository`

Owns the catalog and workspace lifecycle. It depends on the notebook root, atomic file writer, notebook repository cloning support, clock, and IO dispatcher. It does not know about Compose or navigation.

### `FreeWorkspaceListViewModel`

Loads sorted metadata and coordinates create, rename, and delete requests. It exposes structured name-conflict and storage errors to the UI.

### Free editor coordinator

Combines a `NotebookViewModel` for stroke/page editing with free-workspace metadata and lifecycle actions. It flushes on Save, Back, app stop, and before Save As. It updates modified time only from durable-save callbacks.

### Transform engine

`StrokeTransform` owns pure pixel-space move, scale, rotate, flip, and page-fit calculations. It has no Compose dependency.

### Selection overlay

Owns drawing and pointer gesture interpretation. It emits preview/commit requests but does not mutate strokes or persist edits.

## Backup and restore

The existing backup coordinator archives every regular file below the notebook root. The catalog and UUID workspace directories therefore enter backups without a second archive format.

Restore continues replacing the whole notebook root atomically. After restore, the Free Workspace repository reloads and validates its catalog. Problem progress remains independent of free-workspace metadata.

## Error handling

- Name conflicts are expected validation results and use the dialog's red glow, shake, and inline message.
- Storage or cloning failures use a stable error message and leave the dialog/editor state intact for retry.
- Autosave failures retain unsaved in-memory content and keep the user in the editor.
- A missing free-workspace catalog represents an empty library.
- A corrupt catalog is preserved and reported; it is not replaced automatically.
- A catalog record whose UUID directory is missing is reported as damaged and cannot be opened or duplicated. Rename and delete remain available so the user can correct or remove the entry.
- Rotation and scaling never create non-finite or out-of-range stroke samples.

## Accessibility

- All actionable icons keep at least 48dp touch targets.
- Transform icons have the descriptions `Flip horizontal`, `Flip vertical`, and `Snap rotation to 15 degrees`.
- The snap icon exposes selected/unselected toggle semantics.
- The rotation handle exposes a semantics label and a 48dp hit region, even though its visible circle is smaller.
- Library rows announce name, creation date, and modified date.
- Rename and Delete remain separately focusable.
- Naming errors are exposed as error semantics and announced when they appear; shake and color are supplementary cues.

## Verification strategy

### Pure unit tests

- Pixel-space free rotation on square and rectangular viewports.
- Absolute 15-degree snapping in both rotation directions.
- Horizontal and vertical flip behavior in rotated local coordinates.
- Oriented corner scaling and minimum size.
- Whole-group edge translation and rejection of angles that cannot fit.
- Oriented point containment and handle hit priority.
- One replacement edit per rotate or flip commit.
- Undo and redo restore exact stroke samples.

### Repository tests

- Create, list ordering, rename, delete, and empty-catalog behavior.
- NFKC/case-insensitive duplicate detection and name validation.
- Created and modified timestamp rules with an injected clock.
- Complete Save As cloning, including inactive strokes and undo/redo history.
- Source preservation on clone failure.
- Atomic rollback and staging/tombstone recovery.
- Corrupt-catalog preservation.

### Compose instrumentation tests

- Rotation handle drawing and drag callbacks.
- Free rotation versus snapped preview.
- Flip and snap icons, enabled states, semantics, and callbacks.
- Stable canvas position and no toolbar horizontal scrolling.
- Home-to-library navigation.
- Create, rename, delete-confirmation, and Save As dialogs.
- Duplicate-name glow, shake, inline error, and retained dialog.
- Full-width Free Notebook editor without a reference pane.

### Integration and acceptance tests

- Create a named workspace, draw rapidly, leave after flush, reopen, and verify strokes.
- Save As, switch to the duplicate, edit it, and confirm the source is unchanged.
- Rotate and flip, close, reopen, and verify committed geometry.
- Back navigation remains in the editor on save failure.
- Backup, delete local data, restore, and reopen free workspaces with metadata and pages intact.
- Existing problem browsing, problem workspaces, selection transforms, backup, and packaged offline content remain functional.

Android instrumentation tests must at minimum compile in environments without an emulator. Before release, the interaction tests should also be run on the target Galaxy Tab or a compatible Android device because pointer hit regions, rotation gestures, and the shake animation are tactile UI behavior.

## Success criteria

- A user can create a named free workspace from Home and immediately draw with autosave protection.
- The free-workspace library accurately lists, opens, renames, and deletes workspaces with correct dates.
- Save flushes pending work, and Save As creates and switches to an independent duplicate.
- Duplicate names cannot be committed and produce the specified red-and-shake feedback.
- Selected handwriting rotates freely, optionally snaps to 15-degree increments, flips across either local axis, and remains editable with the oriented frame.
- Rotate and flip are single-step undoable operations and survive reopen after autosave.
- No toolbar row scrolls or shifts the canvas when modes change.
- Free workspaces are included in backup and do not appear in the problem catalog.
- Existing problem notebooks continue to work without data migration.
