# Putnam Workbook device smoke test

Release candidate: `putnam-workbook-2025-v0.3.0.apk` (content `2025.2`, version code 3)

## Automated and emulator evidence

- [x] API 36 tablet emulator: app installs and launches offline.
- [x] Twelve 2025 problems appear in A1–A6, B1–B6 order.
- [x] A1 opens in the landscape 40/60 reference/notebook workspace.
- [x] Problem, Help, Prerequisites, and Solution render offline with bundled KaTeX; four hint controls remain visible.
- [x] Pen/Select switching, partial-stroke marquee selection, move, proportional scale, copy/paste, and grouped undo/redo work.
- [x] Previous/next controls and `Page X of Y` navigate pages; numbered navigation, page shifting, palm guard, and Original Paper are absent.
- [x] Notebook pages, ink strokes, completion state, Home, and Continue persist.
- [x] Backup/restore round-trip succeeds and exported PDF opens.
- [x] Activity recreation preserves data; same-signature version-code-2 to version-code-3 upgrade preserves data and normalizes a legacy Original Paper tab to Problem.
- [x] Release manifest has no `android.permission.INTERNET` permission.

## Physical Galaxy Tab A11+ SM-X230

Status: pending installation and hands-on validation by the tablet owner. Keep the device in airplane mode for this checklist.

- [ ] Sideload the APK and confirm the package opens without a network connection.
- [ ] In Pen mode, write ten quick strokes with a finger; confirm every stroke appears immediately and remains after one second.
- [ ] Write for five minutes with a rubber-tip passive stylus; confirm lines remain responsive and no previous stroke vanishes.
- [ ] If available, write with a universal active-capacitive stylus. Confirm lines appear without gaps; pressure support is optional.
- [ ] Switch to Select. Drag a rectangle across only part of several strokes and confirm each intersected stroke is selected in full.
- [ ] Move the selection, scale it proportionally from each corner, then exercise Copy, Paste, Cut, Delete, Undo, and Redo.
- [ ] Confirm transformed and pasted strokes remain within the page and survive Home, force-stop/relaunch, and Continue.
- [ ] Continue writing for 20 minutes; add, clear, and delete notebook pages.
- [ ] Use only the top-right previous/next buttons to navigate pages and verify the `Page X of Y` index and disabled boundary buttons.
- [ ] Background the app, reopen it, then restart the tablet and use Continue.
- [ ] Exercise Home and Android Back from the workspace and archive.
- [ ] Open Problem, each revealed Help stage, every Prerequisite, and Solution; confirm mathematics is typeset and header controls never disappear.
- [ ] Confirm there is no Original Paper tab, palm-guard rectangle, page-shift control, or numbered page strip.
- [ ] Create a backup, change progress, restore the backup, and verify the change is reversed.
- [ ] Export a workbook PDF and open it in an installed PDF viewer.

The SM-X230 does not advertise Samsung S Pen digitizer support. Finger and passive rubber-tip stylus input are equivalent capacitive touch. A universal active-capacitive pen normally behaves as a more precise touch point; pressure and hover are used only if Android reports them, so neither is required by this app.
