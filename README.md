# AI Planner

> [!WARNING]
> **This project is fully AI-coded.** Every line of code, every test and every document here was
> written by AI coding agents. No human wrote the code or reviewed it line by line. It runs one
> person's real calendar, and its tests pass, but it has had no independent security review or
> audit. Read the code before you trust it with your data, your mailbox or a payment card, and use
> it at your own risk.

A personal planner that AI assistants can write to: reminders, deliveries, school work from
Canvas, and a household Kitchen with a shared shopping list, pantry and weekly meal plan. An Android
app and a Windows app share one cloud planner; a Firebase backend stores it and exposes it to AI
clients (Claude, or any MCP client) through a remote MCP server secured with OAuth 2.1. Every
screen is styled like Windows XP, with original artwork.

AI Planner is formerly AI Calendar. The old name stays in technical identifiers (the Android package
`com.example.aicalendar`, the Windows exe and its folders, the `AI_CALENDAR_*` settings), so
updates and pairings keep working across the rename.

![Phone, light theme](docs/screenshots/phone-light.png)
![Phone, dark theme](docs/screenshots/phone-dark.png)
![Windows app](docs/screenshots/windows-luna-life.png)

The screenshots are rendered by the projects' own UI tests from made-up sample reminders. More in
[docs/screenshots](docs/screenshots): the month view, a repeating reminder, the dark School tab and
the icon set. They show the reminder views; the Kitchen view is not pictured yet.

This export: phone 0.19.0, Windows 1.6.0, bridge 0.10.0.

## What it does

- **AI clients publish reminders.** An assistant reading your mail or a task result calls
  `publish_task_reminders`; stable task and item keys update instead of duplicating, and a
  reminder you deleted stays deleted.
- **Phone alerts you never miss.** Exact alarms for critical reminders, day-before notices, Done /
  Snooze / Ignore actions, re-armed after reboot. A night rule moves advance notices out of 9 PM to
  8 AM.
- **Feeds:** Life, School and Deliveries tabs, plus All and Archived. Deliveries are only listed
  and never alert. Birthdays and deliveries stay listed all day.
- **Repeating reminders:** daily, weekly on chosen weekdays, monthly or yearly, every N, ending
  never, on a date or after N times. The server turns each series into ordinary reminders for the
  next 60 days; editing or deleting one asks "This one" or "This and all future ones".
- **Canvas LMS:** assignments, quizzes and discussions imported every 30 minutes, with course
  colors, points, submission types and quiz/exam warnings.
- **Kitchen:** a shopping list, a pantry and the week's meal plan with recipes, shared by the
  phones, the PC and AI clients (`kitchen_*` MCP tools). Marking a pantry item Out puts it on the
  list; ticking it off as bought puts the pantry item back to Have. Each dinner can be marked
  Cooked or Skipped and rated. Open Kitchen views refresh when another device changes something,
  and the phone keeps working offline and sends its changes later. See
  [docs/kitchen.md](docs/kitchen.md).
- **Updates over the cloud:** publish a phone build to the bridge (`scripts/publish-app.ps1`) and
  paired phones offer Install, check the download's SHA-256 and hand it to Android, which asks you
  to confirm.
- **Gmail:** through your AI client's own Gmail connector, or forwarded into Outlook.com; see
  [docs/SETUP-GMAIL.md](docs/SETUP-GMAIL.md).
- **Outlook.com mail tools (optional):** list, read, move, file into folders and create move-only
  Inbox rules through Microsoft Graph, so an assistant can triage mail. Nothing ever deletes mail.
- **Windows companion:** list and month views, Find, class filter, the Kitchen tab, balloons while
  it runs, dark mode.

### Since the first public release

- A security and sync audit's fixes: a device's stale copy can no longer overwrite a newer change
  (including Done), deleted reminders and occurrences stay deleted, "this and all future ones" no
  longer empties or duplicates a series, Canvas quizzes and exams keep their early warnings, the
  Outlook tools refuse every route toward deleting mail, and dependency advisories are fixed.
- Reminders made on a phone are shared with the cloud planner, also when they were made offline
  or before pairing.
- Cloud updates for the phone app, and the Kitchen.

## Repository

| Folder | What |
|---|---|
| `app/` | Android app. Kotlin, Jetpack Compose, `minSdk 26` |
| `desktop/` | Windows companion. .NET 8, WPF, xUnit tests that never show a window |
| `bridge/` | Firebase Functions backend in TypeScript: device sync API, MCP server, OAuth, Canvas, Outlook, Kitchen, phone updates |
| `ui-harness/` | Headless Robolectric + Roborazzi harness that renders every phone screen and drives UI flows |
| `tools/xp-icons/` | Generator for all icons (phone, Windows, launcher) from one Python definition file |
| `tools/` | `mcp-call.ps1` (call an MCP tool with the API token), `deploy-functions.cmd` |
| `scripts/` | PowerShell helpers: build and test the phone app, publish a phone update, test and install the Windows app, deploy the bridge, run the emulator tests |
| `docs/` | Setup guides, architecture, the Kitchen contract, screenshots |

## Set it up

You need your own Firebase project (Blaze plan, for Cloud Functions and Cloud Scheduler) and,
for the optional mail tools, a Microsoft Entra app registration. Blaze needs a payment card; a personal calendar
runs at $0 to about $1 a month with the phone alone, more if the Windows app stays open all day
(details and ways to keep it at $0: [Running costs](docs/SETUP-FIREBASE.md#running-costs)).
The Windows app's Kitchen tab adds to that while it is on screen: it refreshes every 20 seconds
while the window is active and every 60 seconds otherwise.

1. [docs/SETUP-FIREBASE.md](docs/SETUP-FIREBASE.md): project, Firestore, Functions, secrets, the
   Android app registration, deploy.
2. [docs/SETUP-CANVAS.md](docs/SETUP-CANVAS.md): link Canvas LMS for the School feed (optional).
3. [docs/SETUP-AZURE.md](docs/SETUP-AZURE.md): the Microsoft app for Outlook.com mail (optional).
4. [docs/SETUP-GMAIL.md](docs/SETUP-GMAIL.md): use Gmail through your AI client's Gmail connector,
   or by forwarding to Outlook.com (optional; the bridge has no Gmail tools of its own).
5. Build and install the phone and Windows apps, pair them, and connect an AI client:
   [docs/SETUP-FIREBASE.md#connect-the-apps-and-an-ai-client](docs/SETUP-FIREBASE.md#connect-the-apps-and-an-ai-client).

Handing the work to a coding agent (Claude Code, Codex and others)? Point it at
[AGENTS.md](AGENTS.md). It has the setup playbook, the commands, and the rules that keep the two
apps and the server consistent.

## Build and deploy

The basics, from the repository root in PowerShell. Keep the repository in a path without `&`.
`<project-id>` is your Firebase project id; the bridge's base URL is then
`https://us-central1-<project-id>.cloudfunctions.net/api`.

```powershell
# Bridge: install, typecheck, test, deploy (after `firebase use <project-id>`, see .firebaserc.example)
cd bridge; npm ci; npx tsc -p tsconfig.json --noEmit; npm test; cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/deploy-bridge.ps1

# Phone: unit tests and a debug APK, then install it over USB (never uninstall: that erases the
# app's local reminders, its queue of unsent changes and its pairing)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-app.ps1 :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Phone update over the cloud: bump versionCode in app/build.gradle.kts first
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/publish-app.ps1

# Windows: tests, then install and start
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-desktop.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-desktop.ps1
```

`scripts/build-app.ps1` expects Android Studio's JDK in `C:\Program Files\Android\Android Studio\jbr`
and the SDK in `%LOCALAPPDATA%\Android\Sdk`; edit the two lines at its top if yours are elsewhere.
Android installs an update only when it is signed with the same key as the installed app, so
publish phone updates from the computer that built the installed one.

## How it fits together

```
 AI clients (MCP over HTTPS, OAuth 2.1 + PKCE)      Canvas LMS          Outlook.com
                    |                                    | every 30 min      | Microsoft Graph
                    v                                    v                   v
 +----------------------------------------------------------------------------------+
 |  bridge/  Firebase Functions: `api` (Express + MCP server), `canvasSync` (cron)  |
 |  Firestore: events, series, devices, oauth*, integrations (incl. the kitchen)    |
 +----------------------------------------------------------------------------------+
        | FCM `calendar_sync` / `kitchen_sync` signals (no content)   ^
        | GET /v1/sync, /v1/kitchen; PATCH/DELETE /v1/events, ...      | same API, polled
        v                                                              |
 app/  Android: alarms, notifications, Kitchen       desktop/  Windows: list, balloons, Kitchen
```

More in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/kitchen.md](docs/kitchen.md).

## Tests

| Suite | Command (from the repository root) |
|---|---|
| Bridge | `cd bridge`, `npm ci`, `npm test` |
| Bridge against the Firestore emulator (every MCP tool over HTTP) | `scripts/test-emulator.ps1` |
| Phone unit tests, lint, APK | `scripts/build-app.ps1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` |
| Phone screens (pictures in `ui-harness/build/shots`) | `ui-harness/run.ps1` |
| Windows | `scripts/test-desktop.ps1` |

## Security notes

See [SECURITY.md](SECURITY.md) for the security model, how to report a problem, and a
checklist for your own deployment. In short:

- Nothing secret is in the repository. Secrets live in Firebase Secret Manager; local copies go in
  ignored files (`secrets/`, `bridge/.env.*`).
- The phone and the PC pair with a pairing code and get a device token; the server keeps only its
  SHA-256 hash. FCM messages carry a revision number, never reminder or kitchen content.
- AI clients sign in with OAuth 2.1 (dynamic client registration, PKCE); the consent page asks for
  the pairing code.
- Firestore rules deny all client access; only the functions read and write.
- A phone update is offered only to paired devices, and the phone installs it only after its
  SHA-256 matches and you confirm Android's prompt.
