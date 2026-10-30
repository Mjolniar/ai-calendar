# UI harness for the phone app

> AI-generated.

Runs the phone app's real screens on the JVM, without phone or emulator, and saves pictures. Used to check the XP look
and UI behaviour while the phone was unavailable.

It runs on Linux and on Windows (JDK 17+). On Windows see "If the first run on Windows fails".

## Contents

Separate Gradle build; not part of the app build; changes nothing in `app\`.

- Sources: the app's `..\app\src\main\java` and `res`, read in place. No copies.
- Excluded: eight files needing Firebase, WorkManager, network or Keystore: `BridgeApi.kt`, `BridgeSyncWorker.kt`,
  `CalendarMessagingService.kt`, `EventImportParser.kt`, `FirebaseBootstrap.kt`, `PendingChanges.kt`,
  `RemoteEventParser.kt`, `SecureTokenStore.kt`.
- Replacements: `src\stubs\java\com\example\aicalendar\Stubs.kt`, same-named local stand-ins.
- Manifest: `src\harness\AndroidManifest.xml` = the app's manifest minus the Firebase service and xml configs.
- Robolectric 4.17 runs Compose with native graphics; Roborazzi 1.50.0 writes pictures. Render size 443 x 986 dp,
  420 dpi, API 35: the size of the reference phone (Pixel 10 Pro XL).

Application id `com.example.aicalendar.uiharness`, not the app's. Not for installing; an APK from here would sit beside
the real app instead of replacing it with one that cannot sync.

## Tests

| File | Covers |
|---|---|
| `XpShots.kt` | `XpShots` and `XpShotsDark`: every screen, dialog, menu and state as a picture, in Luna and Noir. 47 tests each |
| `XpFlows.kt` | Dark mode toggle, new reminder, title validation, done/undo, archive/undo, delete confirmation, editing time and alerts, find, opening from a notification, picking a day in month view. 14 tests |
| `RepeatFlows.kt` | Rule in words in details; "this one" vs "this and all future ones" for delete and edit and what each sends; new weekly reminder; required cloud connection; deliveries cannot repeat. 8 tests |
| `DeliveryFlows.kt` | Deliveries tab and counts; no alarm or notification on save, conversion, or after sync; moving back restores the alert; deliveries and birthdays listed all day. 9 tests |
| `XpActivityTest.kt` | Real `MainActivity`; opening a reminder from its notification; end-of-day deadline arms the evening "Tonight" alarm; balloon notification layout; cold start with Kitchen open fetches once. 4 tests |
| `KitchenFlows.kt` | Kitchen against `FakeKitchen.kt` (fake bridge answering like 0.11.0, or 0.10.0 with `bridge011 = false`; `before`/`after` hooks to fail requests or lose answers): what each edit sends and shows; offline queue (retry after 30 s, at once on reconnect, background job requested on first queued edit, sent edit kept when the follow-up fetch fails); corrections (clientId on resend, note about a removed item, Withdraw for notes only, We have it removing the pantry line); Undo of an ordered line; Kitchen never touches reminders or alarms. 36 tests |
| `UpdateFlows.kt` | Update prompts persist until used; a passing balloon waits behind one; "Allow from this source" continues the install unless the waiting build is no longer newer or the phone is unpaired. 6 tests |
| `XpInsetsShot.kt` | Window chrome under fake status and navigation bars. 1 test |

App unit tests stay in `app\src\test` and run with the app build.

## Run

```
powershell -NoProfile -ExecutionPolicy Bypass -File ui-harness/run.ps1
```

Prints exit code, notable lines, test totals, picture counts. Extra arguments go to Gradle, e.g.
`testDebugUnitTest --tests "*DeliveryFlows*"`.

- Pictures: `build\shots\` (light), `build\shots-dark\`; half scale, named in screen order (`01-life.png`,
  `06-archived.png`, `26-deliveries.png`, ...). Check them after UI changes.
- Report: `build\reports\tests\testDebugUnitTest\index.html`. Full Gradle output: `run.log`.
- A clean run takes 1-3 minutes; the first run longer.

First run needs internet: Gradle fetches Robolectric, Roborazzi and Compose test libraries (Maven Central, Google
Maven); Robolectric then fetches its Android 15 runtime jar into `%USERPROFILE%\.m2`. Offline afterwards.

## When the app changes

- New or changed screen: add a case to `XpShotsBase` in `XpShots.kt`; check both pictures.
- Changed public API of one of the eight excluded files: the harness fails to compile until `Stubs.kt` matches.
- New app file importing Firebase, WorkManager or `java.net`: add it to `stubbedOut` in `build.gradle.kts` and stub what
  the UI calls in `Stubs.kt`.
- Library or plugin version bump in the app: repeat it in this folder's `build.gradle.kts`.
- Pictures are recorded, not compared. Tests fail on crashes or assertions only. Looking at the pictures is the check.

## If the first run on Windows fails

Known causes, most likely first:

0. Every test fails with `UnsatisfiedLinkError` at `RenderNode` after a `NoSuchFileException` with `%20` in a path:
   Robolectric could not read its runtime jar from a folder with a space. `run.ps1` copies the jar to
   `build\robolectric-deps` and `build.gradle.kts` uses it; a run before the jar was ever downloaded
   fails once this way, the next works.
   In an agent shell that sets `NoDefaultCurrentDirectoryInExePath`, clear it (`AGENTS.md`).
1. Temp folder: `run.ps1` sets `TEMP` and `TMP` to `build\tmp` (Robolectric unpacks native libraries
   there). Started another way, the default temp path (with an apostrophe) is the first suspect.
2. Path: keep the repository in a folder whose path has no `&` in it.
3. JDK: Robolectric with API 35 needs JDK 17+. `run.ps1` uses Android Studio's JDK 21.
4. Offline: `No cached version` in `run.log` means a download was blocked.
