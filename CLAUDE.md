# Scale Tape

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
```

## Layout
- `app/src/main/java/com/scaletape/Scale.kt`: all length/scale parsing and math. Pure Kotlin, no Android imports. Keep it that way so it stays unit-testable.
- `Job.kt`: `Job`/`Run` model and JSON (de)serialization. `JobStore.kt`: files on disk.
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
- Runs: when not measuring, tapping near a run (24dp) selects it; the sheet header offers Delete. `Run.distanceTo` does the hit test.
- Scale is shown under the job name; the top-bar pencil edits name and scale together and only saves a scale that parses.
- Sheet peek height is the measured header (incl. the nav-bar inset), so the collapsed sheet hides the list. The pill is hand-drawn because `BottomSheetDefaults.DragHandle` has fixed 22dp padding. Back collapses an expanded sheet first.
- Calibrate: draw a line over a known dimension, enter its real length; `calibratedScale` writes an equivalent scale back into the scale field (one source of truth).
- Bitmaps are capped at 4096 px on the long side to bound memory. All `PdfRenderer` calls go through the single-thread `pdfThread` dispatcher because it isn't thread-safe.

## Rules
- Any change to parsing or math gets a test in `ScaleTest`.
- Accuracy is the product: a wrong total means a wrong quote. Check edge cases (fractions, feet-inches, `1'-0"`, locale decimals) when touching `Scale.kt`.
- Follow Material 3 components and color roles (`MaterialTheme.colorScheme`); marks drawn on the plan use fixed colors because the paper is always white.
- Keep it lean: no new dependencies, DI, ViewModel or navigation library unless a feature truly needs it.
- Package/app id is `com.scaletape`. Never change `applicationId` as a side effect.
