# AI Calendar bridge

> AI-generated.

MCP endpoint for AI clients (Claude sessions, scheduled tasks) and device sync API for the phone and PC.

## Endpoints

- `POST /mcp`: MCP streamable HTTP. Requires `Authorization: Bearer <AI_CALENDAR_API_TOKEN>`.
- `POST /v1/bootstrap`: non-secret Firebase client config, after the pairing code is validated.
- `POST /v1/devices/register`: pairs an Android installation with `AI_CALENDAR_PAIRING_CODE`.
- `GET /v1/sync`: authoritative reminder list and repeating reminders (`series`) for a paired device.
- `PATCH /v1/events/:id`, `POST /v1/events/:id/done`, `DELETE /v1/events/:id`: change, complete, delete one reminder.
- `POST /v1/series`, `PATCH /v1/series/:id`, `DELETE /v1/series/:id`: create, change, delete a series. `PATCH` takes `from`, `DELETE` takes `?from=` (local date) for "this and all future ones".
- `POST /v1/canvas/sync`: refresh Canvas assignments and course/detail metadata.
- `POST /admin/revoke-tokens`: revoke AI connector grants and/or device tokens; dry run unless `apply: true`. Static API token only (`tools/revoke-tokens.ps1`).
- `GET /v1/kitchen`, `/v1/kitchen/items`, `/v1/kitchen/pantry`, `/v1/kitchen/corrections`, `/v1/kitchen/notes/:date`: Kitchen (below).
- `GET /health`: unauthenticated health check.

## Kitchen

Shopping list, pantry estimate, weekly meal plan and dinner notes, shown in the apps' Kitchen view and maintained by Claude's weekly grocery run via `kitchen_*` MCP tools (`kitchen_get`, `kitchen_list_add`, `kitchen_list_update`, `kitchen_pantry_set`, `kitchen_corrections_resolve`, `kitchen_week_publish`). Contract: `docs/kitchen.md`. Rules: `src/kitchen.ts`.

- Adding a name already open on the list (ignoring case, spacing, punctuation, plural "s"; any script) reuses that item.
- Pantry `out` puts the item on the list, even if already out. Pantry `have` removes the `need` item the pantry added (items added by a person or Claude, and ordered ones, stay). Ticking a list item `got` sets the same-named pantry item to `have`.
- People correct the estimate with `POST /v1/kitchen/corrections`: `out`/`have` apply at once; `note` waits for Claude. `clientId` makes a resend after timeout harmless (200, `repeated: true`). A person's status outranks `kitchen_pantry_set` for a day unless Claude sends `override: true`.
- `POST /v1/kitchen/items` with `restore: true`, `status`, `orderRef` = the apps' Undo after a remove.
- Got items: 7 days in the apps' list, 30 in storage. Max 400 open list items. Dinner notes kept ~400 days.
- Current week = published week containing `today` (also in the snapshot) in the household zone `AI_CALENDAR_TIME_ZONE` (default `America/Denver`). `nextWeek` = next published.
- A write that changes nothing keeps the revision.

Storage: key-value documents in `integrations` (`kitchen:list`, `kitchen:pantry`, `kitchen:corrections`, `kitchen:notes`, `kitchen:weeks`, `kitchen:week:<start>`), each changed in a Firestore transaction (`mutateKv`). Changes send a `kitchen_sync` push to other devices so open Kitchen views refresh.

## Feeds

`domain`: `life`, `school` or `delivery`. Academic work = `school`; a package, order or shipment in transit = `delivery`; else `life`. Without `domain` the server infers `school` from the wording, else `life`. Never infers `delivery`: only explicit `domain: "delivery"` (or `category: "delivery"` without `domain`), because deliveries do not alert.

Deliveries are stored with empty `reminderOffsetsMinutes`, whatever lead times or `critical` the caller sends, on create, publish and every update while a delivery. Moving to another feed restores default lead times unless the update supplies its own. Apps list deliveries on their own tab with no alarms.

## Repeating reminders

A series (collection `series`) holds `freq` (`daily`, `weekly`, `monthly`, `yearly`), `interval`, `weekdays` (weekly, ISO 1 = Monday to 7 = Sunday), `startDate` and `time` (local to `timeZone`), optional `until` or `count`. The server expands it into ordinary reminders, one per date, with `seriesId` and `occurrenceDate`; apps hold no pattern logic. Rules: `src/recurrence.ts`; behaviour: `CalendarService` in `src/service.ts`.

- Occurrences of the next 60 days are listed (at least the next two). Written on create/change; topped up by `canvasSync` every 30 minutes.
- Occurrences keep local time in the series zone (8:00 AM stays 8:00 AM across DST). Monthly 29th-31st falls on the last day of shorter months; yearly Feb 29 on Feb 28.
- Occurrence id = f(series id, date): re-expanding never duplicates.
- Deleting an occurrence adds its date to `skippedDates`; it never returns. Completing one marks only that one.
- Editing one occurrence sets `detached: true`; expansion leaves it alone. Editing from a date ("this and all future ones", `from`) ends the series the day before and continues as a new series keeping the old first date (week parity, day of month, count carry over). Future occurrences not done and not detached are rewritten; past and done stay.
- Deleting a series removes future unfinished occurrences; past and done stay.
- Moving the time earlier removes a future occurrence still at the old time once the new time has passed (today's 6:00 PM after a change to 8:00 AM at 1:00 PM).
- A series cannot be a delivery. The routine class meeting rule never archives a series occurrence.
- First date 2000-01-01 to 2100-12-31. Lead times max 60 days (86,400 minutes, the listing horizon), except yearly.
- `create_series` (and `POST /v1/series`) with the idempotency key of a user-deleted series answers `deleted: true` (phone: 409) and creates nothing; `create_reminder` likewise for deleted reminders.

## Limits and safeguards

- Mail: `outlook_send_mail` max 10 recipients (to + cc), max 20 mails/day (America/Denver), counted in a Firestore transaction (`integrations/outlookSendCount`). Every send, refusal and failure logged in `integrations/outlookSendLog` (last 300) and Cloud Logging, metadata only: time, mode, recipient count, recipient domains, subject length.
- AI connectors: each sign-in is a grant (`grantId` on every token). A refresh token reused more than 2 minutes after exchange revokes its whole grant (copied token); other connections unaffected. No absolute token lifetime. Max 5 redirect URIs of 2,000 characters per client; unnamed clients are named after the redirect host; the consent page shows the full redirect URI.
- Pairing: a code under 32 characters is logged as weak at startup (never the code) and gets a global limit of 50 failed attempts/hour (Firestore `integrations/pairingFailures`). Strong codes have only the per-address limit, so nobody can lock out the owner.
- Canvas: an open Canvas reminder due inside the fetched window that no planner list shows any more is marked done (`retiredAt`), never deleted, and reopened if Canvas lists it again. More than 10 in one run: none retired, warning logged (partial answer). Lock/unlock dates and details removed in Canvas are cleared on the next details fetch.
- Request bodies: 16 KB default (pairing, OAuth token and consent forms), 64 KB client registration, 128 KB device routes, 4 MB `/mcp` (parsed only after the token check).
- Planner state: max 700,000 bytes (UTF-8) per document; appends in a transaction. A key whose document id (dots become underscores) belongs to another key is refused.

Only SHA-256 hashes of device tokens are stored. Firebase data messages carry a revision signal, no reminder content; the phone fetches data over authenticated HTTPS.

## Local check

```powershell
npm install
npm test
npm run build
$env:AI_CALENDAR_API_TOKEN='local-development-token'
$env:AI_CALENDAR_PAIRING_CODE='<at least 32 random characters>'
npm run dev
```

The local server refuses to start without `AI_CALENDAR_PAIRING_CODE` (no default). Local mode: in-memory store, no Firebase messages. Production: Firestore and FCM via `src/firebase-function.ts`.
