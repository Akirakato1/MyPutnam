# MyPutnam device smoke test

Release candidate: `putnam-workbook-2020-2025-v0.4.0.apk` (content `putnam-2020-2025.1`, version code 4)

This candidate intentionally has no notebook migration contract. Uninstalling the development build erases its local notebooks and progress.

## Clean installation on Galaxy Tab A11+ SM-X230

- [ ] Enable airplane mode and uninstall the old development build: `adb uninstall com.putnamworkbook.offline`.
- [ ] Install with `adb install <absolute-apk-path>` and launch while still offline.
- [ ] Confirm Home offers Continue Most Recent, Browse Problems, Free Notebook, Problem Library, and Settings; Continue is initially empty.
- [ ] Open Browse Problems and confirm All shows exactly 72 installed problems across 2020–2025 in A1–A6, B1–B6 order.
- [ ] Open Problem Library and confirm the last-known catalog loads offline, bundled entries are marked Installed, and problem/reference filters do not require a network connection.
- [ ] While offline, logically delete one bundled problem, confirm Browse Problems hides it while its notebook remains, then restore it from Problem Library and reopen it.
- [ ] With networking enabled, refresh the catalog and install/update one test entry; disable networking and confirm it still opens with all hints and solution content.
- [ ] Interrupt a download, relaunch, and confirm it is shown as failed/retryable rather than installed.
- [ ] Create a backup, delete a mix of bundled/downloaded content, restore the backup, and confirm bundled choices restore locally while remote choices appear as a selectable review list and do not download until confirmed.
- [ ] Search by topic and confirm a topic such as Integrals shows only matching problems; clear the topic to restore All.
- [ ] Open a problem and confirm the landscape split workspace keeps the reference pane and notebook visible together.
- [ ] Open Problem, every progressive Hint, each Prerequisite, and Solution; confirm all mathematics is typeset offline and header controls remain visible.
- [ ] Confirm previous/next page arrows and the top-right `Page X of Y` index work, with no numbered page strip or page-shift control.
- [ ] In Pen mode, write ten rapid strokes with a finger and confirm each stays continuously visible without flicker or loss.
- [ ] Draw into the bottom-right of the page and confirm there is no blocked gray rectangle.
- [ ] Repeat writing with a rubber-tip passive stylus and, if available, a universal active-capacitive stylus.
- [ ] Change among all eight pen colors and several widths directly in the notebook toolbar.
- [ ] Toggle to Select, marquee across part of several strokes, and confirm every intersected stroke is selected in full.
- [ ] Move and proportionally resize the selection; exercise Copy, Paste, Cut, Delete, Undo, and Redo.
- [ ] Tap outside the marquee and confirm it deselects without a Cancel button; confirm there is no Erase Last button.
- [ ] Add, clear, and delete notebook pages, then return Home and use Continue Most Recent.
- [ ] Force-stop and relaunch the app; confirm notebook pages, ink, page position, progress, and recent-problem state persist.
- [ ] Exercise Android Back from the workspace and library, then restart the tablet and use Continue again.
- [ ] Confirm there is no Original Paper tab and that the app remains fully usable throughout the test in airplane mode.
- [ ] Confirm Android reports only Internet and network-state permissions and that cleartext traffic is disabled.

The SM-X230 does not advertise Samsung S Pen digitizer support. Finger and passive rubber-tip stylus input are equivalent capacitive touch. A universal active-capacitive pen normally behaves as a more precise touch point; pressure and hover are optional and are used only if Android reports them.
