# Putnam Workbook device smoke test

Release candidate: `putnam-workbook-2025.apk` (content `2025.1`, version code 2)

## Automated and emulator evidence

- [x] API 36 tablet emulator: app installs and launches offline.
- [x] Twelve 2025 problems appear in A1–A6, B1–B6 order.
- [x] A1 opens in the landscape 40/60 reference/notebook workspace.
- [x] Four hints, solution, and original two-page paper open from packaged assets.
- [x] Notebook page, ink stroke, completion state, Home, and Continue persist.
- [x] Backup/restore round-trip succeeds and exported PDF opens.
- [x] Activity recreation preserves data; same-signature version-code-1 to version-code-2 upgrade preserves data.
- [x] Release manifest has no `android.permission.INTERNET` permission.

## Physical Galaxy Tab A11+ SM-X230

Status: pending installation and hands-on validation by the tablet owner. Keep the device in airplane mode for this checklist.

- [ ] Sideload the APK and confirm the package opens without a network connection.
- [ ] Write for five minutes each with a finger and rubber-tip passive stylus.
- [ ] If available, write with a universal active-capacitive stylus. Confirm lines appear without gaps.
- [ ] Enable palm guard and confirm an accidental resting palm does not add ink.
- [ ] Continue writing for 20 minutes; add, reorder, and delete notebook pages.
- [ ] Background the app, reopen it, then restart the tablet and use Continue.
- [ ] Exercise Home and Android Back from the workspace and archive.
- [ ] Open Original Paper and move between both pages.
- [ ] Create a backup, change progress, restore the backup, and verify the change is reversed.
- [ ] Export a workbook PDF and open it in an installed PDF viewer.

The SM-X230 does not advertise Samsung S Pen digitizer support. Finger and passive rubber-tip stylus input are equivalent capacitive touch. A universal active-capacitive pen normally behaves as a more precise touch point; pressure and hover are used only if Android reports them, so neither is required by this app.
