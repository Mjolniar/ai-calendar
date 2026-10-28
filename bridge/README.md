# AI Calendar bridge

This service is both the private MCP endpoint used by AI clients (Claude sessions and scheduled tasks) and the device synchronization API for the phone and the PC.

## Endpoints

- `POST /mcp`: MCP streamable HTTP endpoint. Requires `Authorization: Bearer <AI_CALENDAR_API_TOKEN>`.
- `POST /v1/bootstrap`: returns non-secret Firebase client configuration after validating the pairing code.
- `POST /v1/devices/register`: pairs an Android installation using `AI_CALENDAR_PAIRING_CODE`.
- `GET /v1/sync`: returns the authoritative reminder list to a paired device, and the repeating reminders (`series`).
- `PATCH /v1/events/:id`, `POST /v1/events/:id/done`, `DELETE /v1/events/:id`: change, complete or delete one reminder.
- `POST /v1/series`, `PATCH /v1/series/:id`, `DELETE /v1/series/:id`: create, change or delete a repeating reminder. `PATCH` takes `from` and `DELETE` takes `?from=` (a local date) for "this and all future ones".
- `POST /v1/canvas/sync`: refreshes Canvas assignments and their course/detail metadata for a paired device.
- `GET /v1/kitchen` and `/v1/kitchen/items`, `/v1/kitchen/pantry`, `/v1/kitchen/notes/:date`: the Kitchen (below).
- `GET /health`: unauthenticated health check.

## Kitchen

The household's shopping list, pantry, weekly meal plan and dinner notes, shown in the apps' Kitchen view and kept up by Claude's weekly grocery run through the `kitchen_*` MCP tools (`kitchen_get`, `kitchen_list_add`, `kitchen_list_update`, `kitchen_pantry_set`, `kitchen_week_publish`). The contract is `docs/kitchen.md`; every rule is in `src/kitchen.ts`:

- Adding something already open on the list (same name, ignoring case, spacing, punctuation and a plural "s") reuses that item.
- Marking a pantry item Out puts it on the shopping list; ticking a list item as got sets the pantry item with that name back to Have.
- Got items stay in the apps' list for 7 days, and in storage for 30.
- The current week is the published week that contains today in `America/Denver`; `nextWeek` is the next one published.

Storage: key-value documents in the `integrations` collection (`kitchen:list`, `kitchen:pantry`, `kitchen:notes`, `kitchen:weeks`, `kitchen:week:<start>`), each changed in a Firestore transaction (`mutateKv`). Kitchen changes send no push signal; the apps fetch the snapshot when the Kitchen view is shown.

## Feeds

Every reminder has a `domain`: `life`, `school`, or `delivery`. Academic work is `school`; a package, order, or shipment on its way is `delivery`; everything else is `life`. When a caller gives no `domain`, the server infers `school` from the wording and otherwise uses `life`. It never infers `delivery`: only an explicit `domain: "delivery"` (or `category: "delivery"` with no `domain`) files a reminder there, because deliveries do not alert.

A delivery is stored with an empty `reminderOffsetsMinutes`, whatever lead times or `critical` flag the caller sends, on create, on publish, and on every update while it stays a delivery. Moving it to another feed restores the default lead times unless the same update supplies its own. The apps list deliveries on their own tab and arm no alarms for them.

## Repeating reminders

A series (Firestore collection `series`) holds the pattern: `freq` (`daily`, `weekly`, `monthly`, `yearly`), `interval`, `weekdays` (weekly, ISO 1 = Monday to 7 = Sunday), `startDate` and `time` (local to the series' `timeZone`), and an optional `until` or `count`. The server turns it into ordinary reminders, one per date, with `seriesId` and `occurrenceDate`, so the apps need no copy of the pattern logic. Rules: `src/recurrence.ts`; behavior: `CalendarService` in `src/service.ts`.

- The occurrences of the next 60 days are kept listed, and at least the next two. They are written when a series is created or changed and topped up by the scheduled `canvasSync` run every 30 minutes.
- Each occurrence happens at the series' local time in its zone, so 8:00 AM stays 8:00 AM across daylight saving changes. Monthly on the 29th to 31st falls on the last day of a shorter month; yearly on February 29 falls on February 28.
- An occurrence id is derived from the series id and the date, so expanding again never duplicates.
- Deleting an occurrence adds its date to `skippedDates`; it never comes back. Completing one marks only that one done.
- Changing an occurrence on its own sets `detached: true`; expanding leaves it alone. Changing the series from a date ("this and all future ones", `from`) ends it the day before and continues as a new series that keeps the old first date, so week parity, the day of the month and the count carry over. Future occurrences that are not done and not detached are rewritten; past and done ones stay.
- Deleting a series removes its future unfinished occurrences and keeps past and done ones.
- A series cannot be a delivery. The routine class meeting rule never archives an occurrence of a series.

The server stores only a SHA-256 hash of each device token. Firebase data messages contain a revision signal, not private reminder content; the phone fetches reminder data over authenticated HTTPS.

## Local verification

```powershell
npm install
npm test
npm run build
$env:AI_CALENDAR_API_TOKEN='local-development-token'
$env:AI_CALENDAR_PAIRING_CODE='123456'
npm run dev
```

Local mode uses an in-memory store and does not send Firebase messages. Production uses Firestore and Firebase Cloud Messaging through `src/firebase-function.ts`.
