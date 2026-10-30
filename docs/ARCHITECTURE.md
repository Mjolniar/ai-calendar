# Architecture

> AI-generated.

One cloud calendar, three programs. Product behaviour: `README.md`, `desktop\README.md`, `bridge\README.md`. This file
maps the code.

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

Base URL: `https://us-central1-<project-id>.cloudfunctions.net/api` (`/mcp`, `/v1/...`, `/oauth/...`,
`/outlook/connect`, `/health`).

## Reminder record

`bridge\src\types.ts` (`ReminderEvent`). Fields that drive behaviour:

| Field | Meaning |
|---|---|
| `startAt`, `timeZone` | UTC instant + intended IANA zone. Date-only items: 09:00 `America/Denver` by convention. |
| `domain` | Feed/tab: `life`, `school`, `delivery`. |
| `category` | Color: `automatic`, `school`, `medical`, `financial`, `housing`, `travel`, `work`, `personal`, `general`, `delivery`. |
| `critical` | Phone: exact alarm, heads-up alert. |
| `reminderOffsetsMinutes` | Alert lead times. Always empty for deliveries. |
| `done` | Completed; listed under Archived. |
| `source` | `chatgpt` = via MCP tools (any AI client; historical name), `canvas` = Canvas sync, `manual` = made on the phone. |
| `sourceTaskKey`, `sourceItemKey`, `sourceRunId` | `publish_task_reminders` identity: same task + item key updates instead of duplicating. |
| `deletedAt` | Tombstone: deleted task items are kept so republishing does not revive them. |
| `course*`, `schoolItemType`, `details`, `pointsPossible`, ... | Canvas details for School reminders. |
| `revision` | Server change counter. `/v1/sync` reports the highest (reminders and series). |
| `seriesId`, `occurrenceDate` | Occurrence of a series: its series and fixed local date (its identity). |
| `detached` | Occurrence edited alone ("this one"); expansion leaves it alone. |

Phone-made reminders (`source: manual`, `remote = false`) stay on the phone; no device endpoint creates a single cloud
reminder, so they never reach the PC or AI clients. Exception: phone-made repeating reminders go to `POST /v1/series`
and live in the cloud.

## Series (repeating reminders)

`ReminderSeries` in `bridge\src\types.ts`, collection `series`: title, notes, location, domain, category, critical,
lead times, `timeZone`, `rule`, `skippedDates`, `revision`, `deletedAt`. `rule` = `freq` (daily, weekly, monthly,
yearly), `interval`, `weekdays` (weekly, ISO 1-7), `startDate` and `time` (local), `until` or `count`. `notBefore` is
set by a "this and all future ones" split so the new part keeps the old first date. The server expands series into
ordinary reminders with deterministic ids (`occurrenceId(seriesId, date)`); apps alarm, file and complete occurrences
like single reminders and hold no pattern logic - they only word the rule (`RepeatText`) and send series changes.
Pattern math: `bridge\src\recurrence.ts`. Behaviour: `CalendarService` (`createSeries`, `updateSeries`,
`deleteSeries`, `expandAllSeries`). Cloud reminders: edit, complete, delete on the phone; complete, delete on the PC.

## bridge\ (TypeScript, Node 22)

| File | Role |
|---|---|
| `src\firebase-function.ts` | Deployed functions `api` (HTTP) and `canvasSync` (every 30 min; first tops up all series). Lists each one's secrets. |
| `src\firebase.ts` | Production app: Firestore store, FCM notifier, config from env and secrets. |
| `src\server.ts` | Local dev server, in-memory store. |
| `src\app.ts` | Express routes, both auth kinds, pairing guard, rate limits. |
| `src\service.ts` | `CalendarService`: create, update, delete, list, `publishTaskReminders`. All server reminder rules (feed inference, deliveries without offsets, tombstones) and series (create, change, split, delete, expand, skipped dates, detached occurrences). |
| `src\recurrence.ts` | Pure pattern math: rule dates, local time to UTC across DST, 60-day horizon. |
| `src\store.ts` | `CalendarStore` interface; `MemoryCalendarStore` (tests, local); `FirestoreCalendarStore`. |
| `src\mcp.ts` | MCP server: zod tool schemas, handlers, `instructions` text for every AI client. |
| `src\oauth.ts` | OAuth 2.1 + PKCE + dynamic client registration. Consent page asks for the pairing code. |
| `src\canvas.ts` | Canvas Planner import: assignments, quizzes, discussions; skips class meetings; quiz/exam lead times. |
| `src\outlook.ts` | Microsoft Graph for one personal mailbox: list, read, move, send, folders, move-only Inbox rules. |
| `src\notifier.ts` | Per-device FCM data message with only a revision signal. |
| `src\security.ts` | Token hashing, constant-time compare, attempt limiter, pairing guard. |

Auth:

- `/mcp`: bearer token = static API token (`AI_CALENDAR_API_TOKEN`, for `tools\mcp-call.ps1`) or an OAuth access token.
- `/v1/*`: device token from `/v1/devices/register` with the pairing code. Server stores only the SHA-256 hash.
- MCP server rebuilt per request (stateless transport); no session state across deploys.

Firestore: `events`, `series`, `devices`, `oauthClients`, `oauthCodes`, `oauthTokens`, `integrations` (key-value:
Outlook tokens, planner state `planner:<key>` + `planner-index`). `firestore.rules` denies all client access.

MCP tools (27): `create_reminder`, `update_reminder`, `delete_reminder`, `list_reminders`, `publish_task_reminders`,
`list_canvas_assignments`, `sync_canvas_assignments`, `outlook_status`, `outlook_list_messages`,
`outlook_get_message`, `outlook_move_message`, `outlook_move_messages`, `outlook_send_mail`, `outlook_unsubscribe`,
`outlook_list_folders`, `outlook_create_folder`, `outlook_delete_folder`, `outlook_list_rules`, `outlook_create_rule`,
`outlook_update_rule`, `planner_state_get`, `planner_state_put`, `planner_state_list`, `list_series`, `create_series`,
`update_series`, `delete_series`. Usage: tool descriptions and `instructions` in `bridge/src/mcp.ts`.

Tests: `bridge\test\*.test.ts` (vitest, in-memory store, `supertest` on the Express app).

## app\ (Android, package `com.example.aicalendar`)

One module, `minSdk 26`, `targetSdk 36`, Compose with Material 3 under the XP controls. No `google-services.json`: the
app gets its Firebase config from the bridge on pairing (`FirebaseBootstrap`).

| File | Role |
|---|---|
| `PlannerEvent.kt` | Reminder record and JSON form. `defaultReminderOffsets`, `alarmSignature`. |
| `EventRepository.kt` | Local store: one JSON document in SharedPreferences `planner_events`, read-modify-write under `LocalStoreLock`. Merges server snapshots. |
| `PendingChanges.kt` | Outbox for cloud reminder changes (`pending_changes`), one coalesced entry per reminder, overlaid on each sync so an offline Done is not undone. Series changes queue under their own idempotency key (`series:<key>`), never merged; a queued series delete hides its occurrences until delivered. |
| `RepeatRule.kt` | `RepeatRule`, `ReminderSeries` (as `/v1/sync` sends it), `SeriesStore` (`planner_series`), `RepeatText`, editor `RepeatChoice`, request bodies (`SeriesRequests`). |
| `BridgeApi.kt` | All HTTPS to the bridge: pair, sync, save, done, delete, Canvas sync. HTTPS only, no redirects. |
| `RemoteEventParser.kt` | Parses `/v1/sync`. A malformed record keeps the local copy; a body without an events array is an error, never "zero events". |
| `BridgeSyncWorker.kt` | WorkManager: sync every 6 h, on FCM signal, outbox delivery, FCM installation id updates. |
| `CalendarMessagingService.kt` | FCM callback; hands off to `BridgeSyncWorker`. |
| `BridgeSettings.kt`, `SecureTokenStore.kt` | Server URL; device token encrypted with an Android Keystore key (`bridge_secure`). |
| `ReminderSchedule.kt` | Pure: start time + offsets to alarm times (night rule). |
| `AlarmScheduler.kt` | Arms/cancels AlarmManager alarms. Full reconcile on boot, update, permission change; incremental after sync. State in `scheduled_alarm_kinds`, `snoozed_until`, `alarm_meta`. |
| `ReminderReceiver.kt`, `NotificationHelper.kt` | Alarm fires; notification with Done, Snooze 10m, Ignore. Two channels (one critical). |
| `BootReceiver.kt` | Re-arms after reboot, app update, exact-alarm permission change. |
| `EventDomain.kt`, `EventCategory.kt`, `AssessmentKind.kt` | Feed, color category, quiz/test/exam badge. |
| `EventBuckets.kt` | Current vs Archived, "listed all day", Archived order. |
| `PlannerFormat.kt`, `PlannerCalendar.kt` | Wording, list groups, month grid. |
| `MainActivity.kt` | Activity and root composable `PlannerApp`: tabs, menus, find, balloon tips, taskbar. |
| `PlannerList.kt`, `PlannerDialogs.kt` | Rows and details; editor with Repeat box; `SeriesScopeDialog`; pickers; connection and options dialogs. |
| `EventImportParser.kt` | Import from JSON and basic `.ics`. |
| `xp\XpTheme.kt`, `XpChrome.kt`, `XpControls.kt`, `XpIcons.kt` | XP theme: palettes, chrome, controls, generated icons. |

Tests: `app\src\test\...`, plain JVM (JUnit 4, real `org.json`), pure logic. Screens: headless harness in
`ui-harness\`.

## desktop\ (Windows, .NET 8, WPF)

Viewer with alerts: cloud reminders only; complete, delete (occurrences: this one, or this and all future ones), open
Canvas.

| File | Role |
|---|---|
| `App.xaml.cs` | Single instance per session (mutex), crash log, starts the main window. |
| `MainWindow.xaml(.cs)` | Tabs, list and month views, find, details, class filter, tray icon, balloons. Sync timer 60 s, reminder timer 30 s. |
| `BridgeClient.cs` | `/v1` API incl. `DeleteSeriesAsync`. Virtual methods for test fakes (`tests\FakeBridge.cs`). |
| `DesktopSettings.cs` | `%USERPROFILE%\.ai-calendar-desktop`: `settings.json` (pairing, DPAPI), `view.json` (window, tab), `events.json` (cache), `series.json` (cache), `connection.log`. |
| `CalendarEvent.cs` | Record + rules: `ResolvedDomain`, `IsDelivery`, `IsBirthday`, `IsListedAllDay`, `CurrentUntil`, `IsArchivedAt`, `AlertOffsets`, assessment badge. |
| `ReminderText.cs` | Grouping, wording, search, month offsets, Archived groups and order. Mirrors `PlannerFormat.kt`, `EventBuckets.kt`. `RepeatText` as in `RepeatRule.kt`. |
| `ReminderSchedule.cs` | Copy of `ReminderSchedule.kt`. |
| `ReminderRows.cs` | View models for rows, groups, month cells. |
| `EventColors.cs` | Category and class colors, matching the phone. |
| `Xp\` | Theme: `Luna.xaml`, `Noir.xaml`, `XpControls.xaml` (incl. `RadioButton`), `XpWindow`, `XpBalloon`, `XpMessageBox`, `XpSeriesScopeBox`, generated `XpIcons.xaml`. |
| `install.ps1` | Publish the self-contained exe and install for the current user. |
| `pair-desktop.ps1` | Not routine: new pairing code, stored as Firebase secret, redeploys `api`, pairs this PC. |

Tests: `desktop\tests\` (xUnit): pure rules, window tests via `UiFixture` with a fake bridge and throwaway settings
folder, render tests drawing every window in both palettes to `tests\artifacts\`.

## Rules implemented twice

Change both sides and both test files together.

| Rule | Phone | Windows | Tests |
|---|---|---|---|
| Night rule: advance notices that would land 9 PM to 8 AM move to 8 PM or 8 AM; an end-of-day deadline (11:59 PM, midnight) gets one "Tonight" alert at 8 PM | `ReminderSchedule.kt` | `ReminderSchedule.cs` | `ReminderScheduleTest.kt`, `ReminderScheduleTests.cs` |
| Delivery = explicit `domain delivery`, or delivery category with no domain | `EventDomain.kt` (`resolvedDomain`, `isDelivery`) | `CalendarEvent.cs` (`ResolvedDomain`, `IsDelivery`) | `EventDomainTest.kt`, `DeliveryRulesTests.cs` |
| Deliveries never alert | `EventDomain.kt` (`alertOffsets`), `AlarmScheduler.kt` (`neverAlerts`), `ReminderReceiver.kt` | `CalendarEvent.cs` (`AlertOffsets`), `MainWindow.xaml.cs` (`TakeDueReminders`) | `DeliveryFlows.kt` (harness), `DeliveryTests.cs` |
| Listed all day: deliveries and birthdays stay current until their local day ends | `EventBuckets.kt` (`isListedAllDay`, `currentUntilMillis`, `isFiledAt`) | `CalendarEvent.cs` (`IsListedAllDay`, `CurrentUntil`, `IsArchivedAt`) | `EventBucketsTest.kt`, `DeliveryRulesTests.cs` |
| Birthday: birthday, birthdays, bday or b-day in the title, case-insensitive, whole word | `EventBuckets.kt` (`isBirthdayTitle`) | `CalendarEvent.cs` (`IsBirthday`) | same two |
| Weeks run Monday to Sunday (list groups, month grid) | `PlannerFormat.kt` (`dueGroupFor`), `PlannerCalendar.kt` (`leadingBlankDays`) | `ReminderText.cs` (`GroupFor`, `LeadingBlankDays`) | `PlannerFormatTest.kt`, `ReminderTextTests.cs` |
| Archived: past items newest first under Today, Yesterday, Earlier this week, Last week, Earlier this month, then months; items finished early last under Not due yet, soonest first; ties by title then id | `EventBuckets.kt` (`archiveOrder`), `PlannerFormat.kt` (`archiveGroupFor`) | `ReminderText.cs` (`ArchiveOrder`, `ArchiveGroupFor`) | `EventBucketsTest.kt`, `PlannerFormatTest.kt`, `ReminderTextTests.cs`, `MainWindowTests.cs` |
| Quiz, test, exam badges | `AssessmentKind.kt` | `CalendarEvent.cs` (`AssessmentClassifier`) | `AssessmentKindTest.kt` |
| Category and per-class colors | `EventCategory.kt`, `PlannerList.kt` (`courseColorForKey`) | `EventColors.cs` | `EventCategoryTest.kt` |
| Palettes Luna and Noir | `xp\XpTheme.kt` | `Xp\Luna.xaml`, `Xp\Noir.xaml` | `XpPaletteTest.kt`, `ThemeTests.cs` |
| Rule in words: "Every 2 weeks on Monday and Friday at 8:00 AM, until Dec 4, 2026"; monthly "on the 31st", yearly "on October 9", count "10 times" | `RepeatRule.kt` (`RepeatText`) | `ReminderText.cs` (`RepeatText`) | `RepeatRuleTest.kt`, `RepeatTests.cs` (same cases) |
| Deleting an occurrence asks "This one" or "This and all future ones"; the choice is the confirmation | `PlannerDialogs.kt` (`SeriesScopeDialog`), `MainActivity.kt` | `Xp\XpSeriesScopeBox.cs`, `MainWindow.xaml.cs` (`RequestDeleteAsync`) | `RepeatFlows.kt` (harness), `RepeatTests.cs` |

The server enforces its half of the delivery rule (`service.ts`: empty offsets on create, publish, update;
`bridge\test\service.test.ts`, `delivery-migration.test.ts`). Apps still treat stored offsets on a delivery as silent.

## Generated files

- `app\...\xp\XpIcons.kt`, launcher and notification drawables in `app\src\main\res\drawable`, and
  `desktop\Xp\XpIcons.xaml`: from `tools\xp-icons\generate.py` (stdlib Python).
- `desktop\AppIcon.ico`: drawn by desktop tests to `tests\artifacts\AppIcon.ico`; copy over after artwork changes.
- `bridge\dist\`: TypeScript build output. Never edit.
