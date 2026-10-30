# AI Planner

> [!WARNING]
> **Fully AI-coded.** All code, tests and docs written by AI coding agents. No human line-by-line review. No
> independent security audit. Read the code before trusting it with your data, mailbox or payment card. Use at your
> own risk.

Planner that AI assistants write to over MCP. Android and Windows apps share one cloud planner on a Firebase backend.
AI clients (Claude or any MCP client) connect through a remote MCP server with OAuth 2.1. Windows XP look, original
artwork.

Formerly AI Calendar. The old name stays in technical IDs (Android package `com.example.aicalendar`, Windows exe and
folders, `AI_CALENDAR_*` settings) so updates and pairings keep working.

![Phone, light theme](docs/screenshots/phone-light.png)
![Phone, dark theme](docs/screenshots/phone-dark.png)
![Windows app](docs/screenshots/windows-luna-life.png)

Screenshots: rendered by the UI tests from sample data. More in [docs/screenshots](docs/screenshots). Kitchen not
pictured.

This export: phone 0.20.0, Windows 1.7.0, bridge 0.11.0.

## Features

- **AI publishing:** `publish_task_reminders` with stable task and item keys. Updates instead of duplicating. Deleted
  stays deleted.
- **Phone alerts:** exact alarms for critical reminders, day-before notices, Done/Snooze/Ignore, re-armed after
  reboot. Night rule: advance notices between 9 PM and 8 AM move to 8 PM or 8 AM.
- **Feeds:** Life, School, Deliveries, All, Archived. Deliveries never alert. Birthdays and deliveries stay listed all
  day.
- **Repeats:** daily, weekly on chosen days, monthly, yearly; every N; ending never, on a date or after N times. The
  server expands each series 60 days ahead. Edit/delete asks "This one" or "This and all future ones".
- **Canvas LMS:** assignments, quizzes, discussions every 30 minutes. Course colors, points, submission types,
  quiz/exam warnings.
- **Kitchen:** shopping list, weekly meal plan with recipes, On hand. Shared by phones, PC and AI clients
  (`kitchen_*` tools). On hand is the assistant's pantry estimate; people correct it:
  - **We're out:** puts the item on the shopping list.
  - **We have it:** removes the item the pantry put on the list.
  - **Tell Claude:** note the assistant applies and answers.

  Bought items return to Have. Undo restores a removed line. Dinners: Cooked/Skipped, rating. Live refresh across
  devices. Phone works offline. [docs/kitchen.md](docs/kitchen.md).
- **Phone updates:** `scripts/publish-app.ps1` publishes to the bridge. Paired phones offer Install, check SHA-256,
  Android asks to confirm.
- **Gmail:** through your AI client's Gmail connector, or forwarded to Outlook.com.
  [docs/SETUP-GMAIL.md](docs/SETUP-GMAIL.md).
- **Outlook.com (optional):** list, read, move, file into folders, move-only Inbox rules via Microsoft Graph. Never
  deletes mail.
- **Windows app:** list and month views, Find, class filter, Kitchen tab, balloons, dark mode.

## Layout

| Folder | Contents |
|---|---|
| `app/` | Android app. Kotlin, Jetpack Compose, `minSdk 26` |
| `desktop/` | Windows app. .NET 8, WPF, windowless xUnit tests |
| `bridge/` | Firebase Functions backend (TypeScript): sync API, MCP server, OAuth, Canvas, Outlook, Kitchen, phone updates |
| `ui-harness/` | Headless Robolectric + Roborazzi harness for phone screens and UI flows |
| `tools/xp-icons/` | Icon generator (phone, Windows, launcher) from one Python file |
| `tools/` | `mcp-call.ps1` (call an MCP tool with the API token), `deploy-functions.cmd` |
| `scripts/` | PowerShell: build/test phone, publish phone update, test/install Windows app, deploy bridge, emulator tests |
| `docs/` | Setup guides, architecture, Kitchen contract, screenshots |

## Setup

Needs your own Firebase project on the Blaze plan (Cloud Functions, Cloud Scheduler; requires a payment card).
Optional: Microsoft Entra app registration for mail tools.

Cost: $0 to about $1/month with the phone only. More with the Windows app open all day; its Kitchen tab refreshes
every 20 s when active, 60 s otherwise. [Running costs](docs/SETUP-FIREBASE.md#running-costs).

Time zone: `America/Denver` by default. Set `AI_CALENDAR_TIME_ZONE` (IANA name, e.g. `Europe/Berlin`) in
`bridge/.env.<project-id>`. Used for date-only reminders (09:00) and the Kitchen's today and week.

1. [Firebase](docs/SETUP-FIREBASE.md): project, Firestore, Functions, secrets, Android app, deploy.
2. [Canvas](docs/SETUP-CANVAS.md) (optional).
3. [Azure, Outlook.com mail](docs/SETUP-AZURE.md) (optional).
4. [Gmail](docs/SETUP-GMAIL.md) (optional; the bridge has no Gmail tools).
5. [Install apps, pair, connect an AI client](docs/SETUP-FIREBASE.md#connect-the-apps-and-an-ai-client).

Coding agents (Claude Code, Codex): use [AGENTS.md](AGENTS.md).

## Build and deploy

PowerShell, repository root, path without `&`. Bridge URL:
`https://us-central1-<project-id>.cloudfunctions.net/api`.

```powershell
# Bridge: install, typecheck, test, deploy (after `firebase use <project-id>`, see .firebaserc.example)
cd bridge; npm ci; npx tsc -p tsconfig.json --noEmit; npm test; cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/deploy-bridge.ps1

# Phone: unit tests, debug APK, install over USB. Never uninstall: it erases local reminders,
# unsent changes and pairing.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-app.ps1 :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Phone update over the cloud. Bump versionCode in app/build.gradle.kts first.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/publish-app.ps1

# Windows: test, install, start
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-desktop.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-desktop.ps1
```

- `scripts/build-app.ps1` expects the JDK in `C:\Program Files\Android\Android Studio\jbr` and the SDK in
  `%LOCALAPPDATA%\Android\Sdk`. Edit its first two lines otherwise.
- Android only installs updates signed with the same key. Publish phone updates from the PC that built the installed
  app.

## Architecture

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

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/kitchen.md](docs/kitchen.md).

## Tests

| Suite | Command (repository root) |
|---|---|
| Bridge | `cd bridge`, `npm ci`, `npm test` |
| Bridge on the Firestore emulator (every MCP tool over HTTP) | `scripts/test-emulator.ps1` |
| Phone unit tests, lint, APK | `scripts/build-app.ps1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` |
| Phone screens (output in `ui-harness/build/shots`) | `ui-harness/run.ps1` |
| Windows | `scripts/test-desktop.ps1` |

## Security

Model, reporting and deployment checklist: [SECURITY.md](SECURITY.md).

- No secrets in the repository. Secrets in Firebase Secret Manager; local copies in ignored `secrets/` and
  `bridge/.env.*`.
- Phone and PC pair with a code and get a device token. The server stores only its SHA-256 hash. FCM messages carry a
  revision number, never content.
- AI clients use OAuth 2.1 (dynamic client registration, PKCE). The consent page asks for the pairing code.
- Firestore rules deny all client access. Only the functions read and write.
- Phone updates: offered to paired devices only; installed after a SHA-256 match and Android's confirmation.
