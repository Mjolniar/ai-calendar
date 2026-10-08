# UI harness for the phone app

Runs the phone app's real screens on the JVM, without a phone or emulator, and saves pictures of
them. It is how the XP look and the UI behavior were checked while the phone was locked or
unplugged.

It runs on Linux and on Windows (JDK 17+). On Windows see "If the first run on Windows fails".

## What it is

A separate Gradle build. It is not part of the app's build and changes nothing in `app\`.

- Sources: the app's own `..\app\src\main\java` and `res`, read in place. No copies.
- Left out: eight files that need Firebase, WorkManager, the network or the Keystore:
  `BridgeApi.kt`, `BridgeSyncWorker.kt`, `CalendarMessagingService.kt`, `EventImportParser.kt`,
  `FirebaseBootstrap.kt`, `PendingChanges.kt`, `RemoteEventParser.kt`, `SecureTokenStore.kt`.
- In their place: `src\stubs\java\com\example\aicalendar\Stubs.kt`, stand-ins with the same names
  that keep everything local.
- Manifest: `src\harness\AndroidManifest.xml`, the app's manifest without the Firebase service and
  the xml configs.
- Robolectric 4.17 runs the Compose UI with native graphics; Roborazzi 1.50.0 writes the pictures.
  Tests render at 443 x 986 dp, 420 dpi, API 35: the size of the reference phone (Pixel 10 Pro XL).

The application id is `com.example.aicalendar.uiharness`, not the app's. Nothing from here is meant
to be installed; if an APK from this build ever reached the phone, it would sit beside the real
app instead of replacing it with one that cannot sync.

## Tests

| File | Covers |
|---|---|
| `XpShots.kt` | `XpShots` and `XpShotsDark`: every screen, dialog, menu and state as a picture, in Luna and in Noir. 31 tests each |
| `XpFlows.kt` | Behavior behind the screens: dark mode toggle, new reminder, title validation, done and undo, archive and undo, delete confirmation, editing time and alerts, find, opening from a notification, picking a day in the month view. 14 tests |
| `RepeatFlows.kt` | Repeating reminders: the rule in words in details, "this one" and "this and all future ones" for delete and edit and what each sends to the bridge, a new weekly reminder, the cloud connection it needs, and a delivery that cannot repeat. 8 tests |
| `DeliveryFlows.kt` | The Deliveries tab and its counts; a delivery arms no alarm and posts no notification, on save, on conversion and after a sync; moving it back restores the alert; deliveries and birthdays stay listed through their day. 9 tests |
| `XpActivityTest.kt` | Starts the real `MainActivity` and opens a reminder from its notification; an end-of-day deadline arms the evening alarm and says "Tonight"; the notification uses the balloon layout. 3 tests |
| `XpInsetsShot.kt` | The window chrome under fake status and navigation bars. 1 test |

The app's own unit tests stay in `app\src\test` and run with the app's build.

## Run

```
powershell -NoProfile -ExecutionPolicy Bypass -File ui-harness/run.ps1
```

It prints the exit code, the notable lines, the test totals and the picture counts. Arguments
pass through to Gradle, for example `testDebugUnitTest --tests "*DeliveryFlows*"`.

- Pictures: `build\shots\` (light) and `build\shots-dark\`, half scale, named in screen order
  (`01-life.png`, `06-archived.png`, `26-deliveries.png`, ...). Open them to check a UI change.
- Test report: `build\reports\tests\testDebugUnitTest\index.html`. Full Gradle output: `run.log`.
- A clean run took one to three minutes in the cloud. Expect the first run here to take longer.

The first run needs the internet. Gradle downloads Robolectric, Roborazzi and the Compose test
libraries (Maven Central and Google's Maven), and Robolectric then downloads its Android 15
runtime jar from Maven Central into `%USERPROFILE%\.m2`. After that it runs offline.

## When the app changes

- A new or changed screen: add a case to `XpShotsBase` in `XpShots.kt` and look at both pictures.
- A change to the public functions or classes of one of the eight left-out files: the harness
  stops compiling until `Stubs.kt` matches again.
- A new app file that imports Firebase, WorkManager or `java.net`: add it to `stubbedOut` in
  `build.gradle.kts` and give `Stubs.kt` a stand-in for whatever the UI calls.
- A library or plugin version bump in the app: repeat it in this folder's `build.gradle.kts`.
- Pictures are recorded, not compared. A test fails on a crash or a failed assertion, never on a
  visual difference. Looking at the pictures is the check.

## If the first run on Windows fails

Things that already bit this project on this PC, in the order to suspect them:

0. Every test fails with `UnsatisfiedLinkError` at `RenderNode`, after a `NoSuchFileException`
   naming `%20` in a path: Robolectric could not read its runtime jar from a folder with a space.
   `run.ps1` copies the jar to `build\robolectric-deps` and `build.gradle.kts`
   uses it; a run before the jar was ever downloaded fails this way once, then the next run works.
   In an agent shell that sets `NoDefaultCurrentDirectoryInExePath`, clear it (`AGENTS.md`).
1. Temp folder: `run.ps1` sets `TEMP` and `TMP` to `build\tmp`. Robolectric
   unpacks native libraries there. If it was started some other way, the default temp path (with
   an apostrophe) is the first suspect.
2. Path: start it through `C:\ai-calendar`, never through the real folder name with `&` in it.
3. JDK: Robolectric with API 35 needs JDK 17 or newer. `run.ps1` uses Android Studio's JDK 21.
4. Offline: a `No cached version` line in `run.log` means a download was blocked.
