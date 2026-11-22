# Blueprint+

Android takeoff tool for cabinet quotes. Open a PDF floor plan, set the drawing scale
(usually 1/4" = 1'), and measure cabinet runs by tapping two points. Blueprint+ totals the
linear footage per room and for the whole job.

- Magnifier with crosshairs and a drag handle for placing points precisely
- Calibrate the scale from any known dimension (for plans printed "fit to page")
- Rooms come from the plan's own labels: each run goes to the nearest room label that isn't
  behind a wall (Android 15+, PDFs with real text)
- Run list grouped by room with footage totals; drag runs between rooms, add, rename and delete rooms
- Jobs save automatically on the phone; nothing leaves the device

## Install

Download the APK from the [latest release](../../releases/latest) and open it on an Android
8.0+ phone (allow installs from your browser or file manager when asked).

## Build

Needs the JDK bundled with Android Studio:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

`./gradlew assembleRelease` signs the APK when a git-ignored `keystore.properties` points to a key
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it the release APK is unsigned.

## License

MIT
