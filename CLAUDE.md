# Blueprint+

Android takeoff tool for cabinet quotes: open a PDF plan, set the drawing scale
(usually `1/4" = 1'`), tap two points per run, and it totals the linear footage.
Kotlin + Jetpack Compose + Material 3 (dynamic color on Android 12+), single
activity, no network. Opens to a list of saved jobs; each job holds one plan PDF.

## Commands
Gradle needs the JDK bundled with Android Studio:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest            # unit tests (fast, run after every Scale.kt change)
./gradlew assembleDebug                # build APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease              # signed with the upload key via git-ignored keystore.properties → app/build/outputs/apk/release/app-release.apk
```
Release signing uses the `CN=Luke Adair` key shared with sixteenth-plus (`keystore.properties` → `~/.android-keys/lukeadair-upload.jks`). Never commit either. Bump `versionCode` and `versionName` in `app/build.gradle.kts` for each release. A release-signed APK can't install over a debug install (different signature): uninstalling the debug app deletes its saved jobs.

## Layout
- `app/src/main/java/com/scaletape/Scale.kt`: all length/scale parsing and math. Pure Kotlin, no Android imports. Keep it that way so it stays unit-testable.
- `Job.kt`: `Job`/`Run` model and JSON (de)serialization. `JobStore.kt`: files on disk.
- `Rooms.kt`: finding room labels in a page's text, walls on the rendered page, and the room for a run. Pure Kotlin, tested in `RoomsTest` and `WallFinderTest`.
- `RunList.kt`: the sheet's run list, grouped by room (alphabetical, Unassigned last, so sections don't jump when a run moves) with LF totals. Drag a run by its ≡ handle onto another room; near the list's top/bottom edge the list auto-scrolls and the row's shift absorbs the scroll. The carried row is pinned (`LocalPinnableContainer`) so scrolling it off screen doesn't cancel the drag; the handle has `systemGestureExclusion` because it sits in the back-gesture strip. Platform drag-and-drop (`dragAndDropSource`) didn't start inside the bottom sheet, which is why this is hand-rolled.
- `MainActivity.kt`: theme + navigation (`openId` null = home, else that job; no nav library).
- `HomeScreen.kt`: job list, New job FAB, delete with confirm.
- `JobScreen.kt`: top bar (name + scale line, pages, edit dialog), plan canvas with loupe and grabber, and a `BottomSheetScaffold` sheet: collapsed header (pill, total, Undo, Clear, Calibrate), pulled up shows the run list with per-run delete.
- `app/src/test/.../ScaleTest.kt`, `JobTest.kt`: unit tests (org.json is a test-only dep because Android's is a stub on the JVM).

## Saving
- `filesDir/jobs/<id>.json` + `<id>.pdf`. New job copies the picked PDF in (picker URIs don't survive), and checks it opens before creating the job.
- Every change in `JobScreen` goes through `update { }`, which saves immediately with `AtomicFile`. No save button, nothing lost on back or kill.
- Adding a field to `Job`: read it with `opt*` and a default in `jobFromJson` so old saves still load.

## How measuring works
- PDF pages are rendered with the platform `PdfRenderer` (no PDF library). PDF units are points (1/72"), so a page rendered at scale `s` has `72*s` pixels per paper inch; that `ppi` is stored on each `Run`.
- A `Run` keeps its two points in page-bitmap pixels, not feet, so changing the scale text recomputes every run.
- Scale is a ratio of paper inches per real inch (`parseScale("1/4\" = 1'")` = 1/48). Real inches = `px / ppi / ratio`.
- Placing a point: tap opens a magnifier (loupe) centered on the point with crosshairs, plus a three-bar grabber on its rim. Drag the grabber to move the point 1:1; tap it to set the point (start, then the grabber turns green for the end). Tapping outside the loupe closes it; back cancels the measurement. Loupe/grabber sizes live in `Geometry`.
- Runs: when not measuring, tapping near a run (24dp) selects it; the sheet header offers Delete. Tapping a run in the sheet list selects it too, switching page or centering and zooming in on it. `Run.distanceTo` does the hit test.
- Scale is shown under the job name; the top-bar pencil edits name and scale together and only saves a scale that parses.
- Sheet peek height is the measured header (incl. the nav-bar inset), so the collapsed sheet hides the list. The pill is hand-drawn because `BottomSheetDefaults.DragHandle` has fixed 22dp padding. Back collapses an expanded sheet first. The run list's max height keeps the expanded sheet below the title bar.
- Calibrate: draw a line over a known dimension, enter its real length; `calibratedScale` writes an equivalent scale back into the scale field (one source of truth).
- Bitmaps are capped at 4096 px on the long side to bound memory. All `PdfRenderer` calls go through the single-thread `pdfThread` dispatcher because it isn't thread-safe.

## Rooms
- `PdfRenderer.Page.textContents` gives the page text with no positions; `searchText(word)` gives each match's start index and box in PDF points (top-left origin). Needs API 35, or SDK extension 13 on 11+; older devices and scanned plans get no rooms (everything is Unassigned).
- `findRoomLabels` searches a fixed word list (`ROOM_WORDS`, longest first, plus plan abbreviations), keeps whole words only, and keeps only matches at the page's most common label height (±12%), which drops notes that mention rooms. A multi-word name on two lines comes back as one tall box, so it's sized by its first word's box. A number after a name is a room number ("BEDRM 2") unless it's a size ("KITCHEN 10'x26'1"). The word right before a room word on its line is kept as part of the name ("GIRLS BATH", "M. BATH") unless it's another room word ("HALL BATH" stays two) or a filler word. A hit is skipped as already taken only when another covers half its box ("BATH" in "MASTER BATH"): tiny tags that clip a label ("PANTRY 1", a rotated "MASTER CLOSET 1") must not hide it. Debugging labels: dump the page text and every `searchText` result from the phone and replay `findRoomLabels` in a JVM test; the bitmap as raw RGB the same way for `WallFinder`/`seedRect`. Add new abbreviations there with a test. After the height filter, porches, patios, garages, carports, storage, halls and plain closets are dropped by name (`notACabinetRoom`); master/M./his/her/primary and walk-in closets stay ("MASTER CL" reads as Master Closet).
- Picking: `pickRoom` takes the nearest label whose sightline from the run's midpoint doesn't cross a wall (else the nearest). `WallFinder` reads the rendered page: a wall is two long (48"+) parallel lines 3"-12" apart center to center, or one long solid band 3"+ thick. Every dark stretch on a sightline is checked every 2px (a wall face can touch a faucet), and samples within one stroke are merged. Sizes are real inches via the job's scale, so a wrong scale breaks wall finding (and the totals). Thresholds were tuned on two real plans (test plans A and B): countertop edge + cabinet face (~2.5") and a 36" cooktop outline beside the counter edge must not count as walls. When debugging, dump the phone's own page bitmap and labels: its render and label boxes differ from PDFBox. Tests in `WallFinderTest`.
- Room borders (`Boundaries.kt`, pure Kotlin, `BoundariesTest`): `Job.boundaries` are polygons in PDF points, 3 to `MAX_CORNERS` (6) corners, never overlapping (sharing a wall is fine) and inside the page. The first time a page is shown with a valid scale (and labels read), labels inside a table (`inTable`: 4+ long ruled lines evenly spaced 6-30pt apart within 105pt, with the label among them or one row past, i.e. a schedule) are dropped, repeated names are numbered (`numberRepeats`: "Storage", "Storage 2", counting other pages' rooms), and each label gets a rectangle from `seedRect`: per side, three parallel rays (2' apart) stop at the first wall (`WallFinder.crosses`, bisected, pulled back 4"), the shortest wins (a door in one or two rays mustn't let it leak), capped at 30' and clamped to the page. The side rays are pulled in to 80% of the middle ray's reach across, so in a narrow hall they don't run outside it. Then `separate` places them smallest first and cuts each later one back off those placed (`clipAway`), dropping one whose label is inside another room, unless the two labels are 4'+ apart: then the placed room is split halfway between the labels first (open plans: hall into kitchen, kitchen into dining). Borders already on the page (added by hand before seeding) are kept and seeds are cut back off them. The page goes in `Job.seededPages` and isn't re-seeded unless it has an old >6-corner border, the user taps Redetect (`unseed`: wipes the page's borders, edited or not), or the scale's ratio changes and the page isn't in `Job.editedPages` (any border move/add/delete on the page; `reseedIfScaleChanged`, run in `update`). New jobs start at the 1/4" default, so a first seed is often at the wrong scale. `reassignRooms` puts every non-manual run on a seeded page in the smallest border holding its midpoint (none = Unassigned); unseeded pages keep the label-based `pickRoom`. The top-bar pin toggles edit mode: tap a room, drag a corner (a spot that would overlap, self-cross or leave the page is skipped), tap the dot between corners to add one (under 6), long-press to remove (3 minimum). Add room makes a 10' square at the view center cut off the bounding boxes of rooms there. A run crossing a border belongs to the room holding its midpoint.
- `Job.rooms`: rooms the user added, plus any room a run was dragged out of (so a room doesn't vanish when its last run moves; Unassigned excepted). Shown even when empty, so runs can be dragged in. Deleting a room (✕ on its heading) moves its runs to Unassigned as manual, so re-picking can't put them back; it never deletes runs. Renaming (✎) moves its runs to the new name as manual; renaming onto an existing room merges them.
- `Run.room`: null = not picked yet; "" = Unassigned; otherwise the room name. `Run.roomManual` = the user dragged it there; never changed by the app. All other runs are re-picked when the job opens and when the scale changes (`refreshRooms`); new runs right after they're made.
- Room labels for every page are read once when a job opens, on `pdfThread`; re-picking renders each page that has runs (the shown page reuses its bitmap).

## Rules
- Any change to parsing or math gets a test in `ScaleTest`.
- Accuracy is the product: a wrong total means a wrong quote. Check edge cases (fractions, feet-inches, `1'-0"`, locale decimals) when touching `Scale.kt`.
- Follow Material 3 components and color roles (`MaterialTheme.colorScheme`); marks drawn on the plan use fixed colors because the paper is always white.
- Keep it lean: no new dependencies, DI, ViewModel or navigation library unless a feature truly needs it.
- App id is `com.lukeadair.blueprintplus` (changed from `com.scaletape`; the Kotlin package and `namespace` stay `com.scaletape`). Never change `applicationId` as a side effect.

