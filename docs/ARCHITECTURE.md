# Architecture

One cloud calendar, three programs. What the product does is in `README.md`, `desktop\README.md`
and `bridge\README.md`; this file is the map of the code.

```
 AI clients (MCP connectors, scheduled tasks)             Canvas LMS      Outlook.com
            |  MCP over HTTPS, POST /mcp                      |  every 30 min      |  Microsoft Graph
            v                                                 v                    v
 +------------------------------------------------------------------------------------------+
 |  bridge\  Firebase Functions, project <project-id>, region us-central1                    |
 |  `api` (Express app + MCP server)          `canvasSync` (scheduled)                       |
 |  Firestore: events, series, devices, oauthClients, oauthCodes, oauthTokens, integrations  |
 +------------------------------------------------------------------------------------------+
            |  FCM "revision changed" signal (no content)          ^
            |  GET /v1/sync, PATCH/DELETE /v1/events/:id            |  same /v1 API, polled every 60 s
            v                                                      |
 app\  Android phone (Kotlin, Compose)                   desktop\  Windows PC (.NET 8, WPF)
 alarms and notifications                                balloons while it runs
```

Base URL: `https://us-central1-<project-id>.cloudfunctions.net/api`
(`/mcp`, `/v1/...`, `/oauth/...`, `/outlook/connect`, `/health`).

## The reminder

`bridge\src\types.ts` (`ReminderEvent`) is the record. The fields that drive behavior:

| Field | Meaning |
|---|---|
| `startAt`, `timeZone` | UTC instant plus the IANA zone it was meant in. Date-only items are published at 09:00 `America/Denver` by convention. |
| `domain` | The feed: `life`, `school` or `delivery`. Decides the tab. |
| `category` | `automatic`, `school`, `medical`, `financial`, `housing`, `travel`, `work`, `personal`, `general`, `delivery`. Decides the color. |
| `critical` | Phone: exact alarm and heads-up alert. |
| `reminderOffsetsMinutes` | Minutes before `startAt` to alert. Always empty for a delivery. |
| `done` | Completed. Listed under Archived. |
| `source` | `chatgpt` = created through the MCP tools (by any AI client; the name is historical), `canvas` = Canvas sync, `manual` = made on the phone. |
| `sourceTaskKey`, `sourceItemKey`, `sourceRunId` | Identity for `publish_task_reminders`: the same task and item key update the reminder instead of duplicating it. |
| `deletedAt` | Tombstone: a deleted task item is kept so republishing does not bring it back. |
| `course*`, `schoolItemType`, `details`, `pointsPossible`, ... | Canvas detail shown when a School reminder is opened. |
| `revision` | Server-side change counter. `/v1/sync` reports the highest one (of reminders and series). |
| `seriesId`, `occurrenceDate` | Set on an occurrence of a repeating reminder: its series, and its local date in that series (its identity; it never moves). |
| `detached` | An occurrence edited on its own ("this one"). Expanding the series leaves it alone. |

Reminders made on the phone (`source: manual`, `remote = false`) stay on the phone. There is no
device endpoint that creates a single cloud reminder, so they never reach the PC or the AI clients.
A repeating reminder made on the phone is the exception: it goes to `POST /v1/series` and lives in
the cloud.

## The series (repeating reminders)

`ReminderSeries` in `bridge\src\types.ts`, Firestore collection `series`: title, notes, location,
domain, category, critical, lead times, `timeZone`, `rule`, `skippedDates`, `revision`, `deletedAt`.
`rule` is `freq` (daily, weekly, monthly, yearly), `interval`, `weekdays` (weekly, ISO 1 to 7),
`startDate` and `time` (local), and `until` or `count`. `notBefore` is set by a "this and all future
ones" split, so the new part keeps the old first date. The server expands a series into ordinary
reminders with deterministic ids (`occurrenceId(seriesId, date)`), so the apps alarm, file and
complete occurrences exactly like single reminders and hold no copy of the pattern logic. They only
word the rule (`RepeatText`) and send series changes. Pattern math: `bridge\src\recurrence.ts`.
Behavior: `CalendarService` (`createSeries`, `updateSeries`, `deleteSeries`, `expandAllSeries`).
Cloud reminders can be edited, completed and deleted from the phone, and completed and deleted
from the PC.

## bridge\ (TypeScript, Node 22 runtime)

| File | Role |
|---|---|
| `src\firebase-function.ts` | The two deployed functions: `api` (HTTP) and `canvasSync` (every 30 minutes; it first tops up every series' occurrences). Lists the secrets each one gets. |
| `src\firebase.ts` | Builds the production app: Firestore store, FCM notifier, config from environment and secrets. |
| `src\server.ts` | Local development server with the in-memory store. |
| `src\app.ts` | Express routes, the two kinds of auth, pairing guard and rate limits. |
| `src\service.ts` | `CalendarService`: create, update, delete, list, `publishTaskReminders`. All reminder rules of the server live here (feed inference, deliveries stored without offsets, tombstones), and the series: create, change, split, delete, expand, skipped dates, detached occurrences. |
| `src\recurrence.ts` | Pure pattern math: the dates a rule produces, local time to UTC across daylight saving, the 60-day horizon. |
| `src\store.ts` | `CalendarStore` interface, `MemoryCalendarStore` (tests, local), `FirestoreCalendarStore`. |
| `src\mcp.ts` | The MCP server: tool schemas (zod), tool handlers, the `instructions` text sent to every AI client. |
| `src\oauth.ts` | OAuth 2.1 with PKCE and dynamic client registration for AI clients. The consent page asks for the pairing code. |
| `src\canvas.ts` | Canvas Planner import: assignments, quizzes, discussions; routine class meetings are left out; quiz and exam lead times. |
| `src\outlook.ts` | Microsoft Graph for one personal mailbox: list, read, move, send, folders, Inbox rules (move only). |
| `src\notifier.ts` | FCM data message per device carrying only a revision signal. |
| `src\security.ts` | Token hashing, constant-time compare, attempt limiter, pairing guard. |

Auth:

- `/mcp` accepts a bearer token that is either the static API token (`AI_CALENDAR_API_TOKEN`,
  used by `tools\mcp-call.ps1`) or an OAuth access token issued to an AI client.
- `/v1/*` accepts a device token. A device gets one from `/v1/devices/register` with the pairing
  code. The server stores only the SHA-256 hash.
- The MCP server is rebuilt for every request (stateless transport). There is no session state to
  migrate on deploy.

Firestore collections: `events`, `series`, `devices`, `oauthClients`, `oauthCodes`, `oauthTokens`, and
`integrations`, a key-value store that holds the Outlook account tokens and the planner state
documents (`planner:<key>` plus `planner-index`). `firestore.rules` denies all client access; only
the functions read and write.

MCP tools (27): `create_reminder`, `update_reminder`, `delete_reminder`, `list_reminders`,
`publish_task_reminders`, `list_canvas_assignments`, `sync_canvas_assignments`, `outlook_status`,
`outlook_list_messages`, `outlook_get_message`, `outlook_move_message`, `outlook_move_messages`,
`outlook_send_mail`, `outlook_unsubscribe`, `outlook_list_folders`, `outlook_create_folder`,
`outlook_delete_folder`, `outlook_list_rules`, `outlook_create_rule`, `outlook_update_rule`,
`planner_state_get`, `planner_state_put`, `planner_state_list`, `list_series`, `create_series`,
`update_series`, `delete_series`. Who uses them and how is in
`bridge/src/mcp.ts` (the tool descriptions and the `instructions` text).

Tests: `bridge\test\*.test.ts` (vitest, in-memory store, `supertest` against the Express app).

## app\ (Android, package `com.example.aicalendar`)

Single module, `minSdk 26`, `targetSdk 36`, Compose with Material 3 underneath the XP controls.
No `google-services.json`: the app asks the bridge for the Firebase client configuration when it
is paired (`FirebaseBootstrap`).

| File | Role |
|---|---|
| `PlannerEvent.kt` | The phone's reminder record and its JSON form. `defaultReminderOffsets`, `alarmSignature`. |
| `EventRepository.kt` | Local store: one JSON document in SharedPreferences `planner_events`. Every read-modify-write runs under `LocalStoreLock`. Merges server snapshots. |
| `PendingChanges.kt` | Outbox of phone changes to cloud reminders (`pending_changes`), one coalesced entry per reminder, overlaid on every sync so an offline Done is not undone. Series changes are queued under their own idempotency key (`series:<key>`) and never merged; a queued series delete hides its occurrences until it is delivered. |
| `RepeatRule.kt` | Repeating reminders on the phone: `RepeatRule`, `ReminderSeries` (as `/v1/sync` sends it), `SeriesStore` (`planner_series`), `RepeatText` (the rule in words), the editor's `RepeatChoice` and the request bodies (`SeriesRequests`). |
| `BridgeApi.kt` | All HTTPS to the bridge: pair, sync, save, done, delete, Canvas sync. HTTPS only, no redirects. |
| `RemoteEventParser.kt` | Parses `/v1/sync`. A malformed record keeps the local copy; a body without an events array is an error, never "zero events". |
| `BridgeSyncWorker.kt` | WorkManager: periodic sync every 6 hours, sync on FCM signal, outbox delivery, FCM installation id updates. |
| `CalendarMessagingService.kt` | FCM callback. Hands the work to `BridgeSyncWorker`. |
| `BridgeSettings.kt`, `SecureTokenStore.kt` | Server URL and the device token, encrypted with an Android Keystore key (`bridge_secure`). |
| `ReminderSchedule.kt` | Pure function from start time and offsets to alarm times: the night rule. |
| `AlarmScheduler.kt` | Arms and cancels AlarmManager alarms. Full reconcile on boot, update and permission change; incremental after a sync. Records in `scheduled_alarm_kinds`, `snoozed_until`, `alarm_meta`. |
| `ReminderReceiver.kt`, `NotificationHelper.kt` | Alarm fires, notification is posted with Done, Snooze 10m and Ignore. Two channels, one for critical reminders. |
| `BootReceiver.kt` | Re-arms after reboot, app update and exact-alarm permission change. |
| `EventDomain.kt`, `EventCategory.kt`, `AssessmentKind.kt` | Feed, color category, quiz/test/exam badge. |
| `EventBuckets.kt` | Current versus Archived, "listed all day", Archived order. |
| `PlannerFormat.kt`, `PlannerCalendar.kt` | Wording, list groups, month grid. |
| `MainActivity.kt` | The activity and `PlannerApp`, the root composable: tabs, menus, find, balloon tips, taskbar. |
| `PlannerList.kt`, `PlannerDialogs.kt` | Reminder rows and details; editor with its Repeat box, the "this one or all future ones" dialog (`SeriesScopeDialog`), pickers, connection and options dialogs. |
| `EventImportParser.kt` | Import from JSON and basic `.ics`. |
| `xp\XpTheme.kt`, `XpChrome.kt`, `XpControls.kt`, `XpIcons.kt` | The XP theme: palettes, window chrome, controls, generated icons. |

Tests: `app\src\test\...` are plain JVM unit tests (JUnit 4, real `org.json`). They cover the pure
logic. Screens are covered by the headless harness in `ui-harness\`.

## desktop\ (Windows, .NET 8, WPF)

A viewer with alerts. It shows cloud reminders only, marks them done, deletes them (an occurrence of
a repeating reminder: this one, or this and all future ones), opens Canvas.

| File | Role |
|---|---|
| `App.xaml.cs` | Single instance per session (mutex), crash log, starts the main window. |
| `MainWindow.xaml(.cs)` | Everything on screen: tabs, list and month views, find, details, class filter, tray icon, balloons. Sync timer 60 s, reminder timer 30 s. |
| `BridgeClient.cs` | The `/v1` API, including `DeleteSeriesAsync`. Methods are virtual so tests replace it (`tests\FakeBridge.cs`). |
| `DesktopSettings.cs` | Files in `%USERPROFILE%\.ai-calendar-desktop`: `settings.json` (pairing, DPAPI), `view.json` (window and tab), `events.json` (cache), `series.json` (repeating reminders, cache), `connection.log`. |
| `CalendarEvent.cs` | The record plus the rules that hang on it: `ResolvedDomain`, `IsDelivery`, `IsBirthday`, `IsListedAllDay`, `CurrentUntil`, `IsArchivedAt`, `AlertOffsets`, assessment badge. |
| `ReminderText.cs` | Grouping, wording, search, month grid offsets, Archived groups and order. Mirrors `PlannerFormat.kt` and `EventBuckets.kt`. `RepeatText` words a repeating rule as `RepeatRule.kt` does. |
| `ReminderSchedule.cs` | A copy of `ReminderSchedule.kt`. |
| `ReminderRows.cs` | View models for rows, groups, month cells. |
| `EventColors.cs` | Category and class colors, matching the phone's. |
| `Xp\` | Theme: `Luna.xaml`, `Noir.xaml`, `XpControls.xaml`, `XpWindow`, `XpBalloon`, `XpMessageBox`, `XpSeriesScopeBox` (this one or all future ones), generated `XpIcons.xaml`. `XpControls.xaml` styles the option button (`RadioButton`). |
| `install.ps1` | Publish the self-contained exe and install it for the current user. |
| `pair-desktop.ps1` | Not routine: generates a NEW pairing code, stores it as the Firebase secret, redeploys `api`, then pairs this PC. |

Tests: `desktop\tests\` (xUnit). Pure rule tests, window tests driven through `UiFixture` with a
fake bridge and a throwaway settings folder, and render tests that draw every window in both
palettes into `tests\artifacts\`.

## Rules implemented twice

Change both sides and both test files together.

| Rule | Phone | Windows | Tests |
|---|---|---|---|
| Night rule: advance notices that would land 9 PM to 8 AM move to 8 PM or 8 AM; an end-of-day deadline (11:59 PM, midnight) gets one "Tonight" alert at 8 PM | `ReminderSchedule.kt` | `ReminderSchedule.cs` | `ReminderScheduleTest.kt`, `ReminderScheduleTests.cs` |
| What is a delivery: explicit `domain delivery`, or delivery category with no domain | `EventDomain.kt` (`resolvedDomain`, `isDelivery`) | `CalendarEvent.cs` (`ResolvedDomain`, `IsDelivery`) | `EventDomainTest.kt`, `DeliveryRulesTests.cs` |
| Deliveries never alert | `EventDomain.kt` (`alertOffsets`), `AlarmScheduler.kt` (`neverAlerts`), `ReminderReceiver.kt` | `CalendarEvent.cs` (`AlertOffsets`), `MainWindow.xaml.cs` (`TakeDueReminders`) | `DeliveryFlows.kt` (harness), `DeliveryTests.cs` |
| Listed all day: deliveries and birthdays stay current until their local day ends | `EventBuckets.kt` (`isListedAllDay`, `currentUntilMillis`, `isFiledAt`) | `CalendarEvent.cs` (`IsListedAllDay`, `CurrentUntil`, `IsArchivedAt`) | `EventBucketsTest.kt`, `DeliveryRulesTests.cs` |
| Birthday: the word birthday, birthdays, bday or b-day in the title, case-insensitive, whole word | `EventBuckets.kt` (`isBirthdayTitle`) | `CalendarEvent.cs` (`IsBirthday`) | same two |
| Weeks run Monday to Sunday, in list groups and the month grid | `PlannerFormat.kt` (`dueGroupFor`), `PlannerCalendar.kt` (`leadingBlankDays`) | `ReminderText.cs` (`GroupFor`, `LeadingBlankDays`) | `PlannerFormatTest.kt`, `ReminderTextTests.cs` |
| Archived: past items newest first under Today, Yesterday, Earlier this week, Last week, Earlier this month, then months; items finished before their date last, under Not due yet, soonest first; ties by title then id | `EventBuckets.kt` (`archiveOrder`), `PlannerFormat.kt` (`archiveGroupFor`) | `ReminderText.cs` (`ArchiveOrder`, `ArchiveGroupFor`) | `EventBucketsTest.kt`, `PlannerFormatTest.kt`, `ReminderTextTests.cs`, `MainWindowTests.cs` |
| Quiz, test, exam badges | `AssessmentKind.kt` | `CalendarEvent.cs` (`AssessmentClassifier`) | `AssessmentKindTest.kt` |
| Category colors and per-class colors | `EventCategory.kt`, `PlannerList.kt` (`courseColorForKey`) | `EventColors.cs` | `EventCategoryTest.kt` |
| Palettes Luna and Noir | `xp\XpTheme.kt` | `Xp\Luna.xaml`, `Xp\Noir.xaml` | `XpPaletteTest.kt`, `ThemeTests.cs` |
| A repeating rule in words: "Every 2 weeks on Monday and Friday at 8:00 AM, until Dec 4, 2026"; monthly "on the 31st", yearly "on October 9", a count "10 times" | `RepeatRule.kt` (`RepeatText`) | `ReminderText.cs` (`RepeatText`) | `RepeatRuleTest.kt`, `RepeatTests.cs` (the same cases) |
| Deleting an occurrence asks "This one" or "This and all future ones"; the choice is the confirmation | `PlannerDialogs.kt` (`SeriesScopeDialog`), `MainActivity.kt` | `Xp\XpSeriesScopeBox.cs`, `MainWindow.xaml.cs` (`RequestDeleteAsync`) | `RepeatFlows.kt` (harness), `RepeatTests.cs` |

The server enforces its own half of the delivery rule (`service.ts`: a delivery is stored with
empty offsets on create, publish and update; `bridge\test\service.test.ts`,
`delivery-migration.test.ts`). The apps still treat any stored offsets on a delivery as silent.

## Generated and derived files

- `app\...\xp\XpIcons.kt`, the launcher and notification drawables in `app\src\main\res\drawable`,
  and `desktop\Xp\XpIcons.xaml` come from `tools\xp-icons\generate.py` (stdlib Python, no packages).
- `desktop\AppIcon.ico` is drawn by the desktop tests into `tests\artifacts\AppIcon.ico`; copy it
  over `desktop\AppIcon.ico` after changing the artwork.
- `bridge\dist\` is the TypeScript build output. Never edit it.
