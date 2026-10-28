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
- `POST /admin/revoke-tokens`: revokes AI connector grants and/or device tokens; a dry run unless `apply: true`. Static API token only (`tools/revoke-tokens.ps1`).
- `GET /v1/kitchen` and `/v1/kitchen/items`, `/v1/kitchen/pantry`, `/v1/kitchen/corrections`, `/v1/kitchen/notes/:date`: the Kitchen (below).
- `GET /health`: unauthenticated health check.

## Kitchen

The household's shopping list, pantry estimate, weekly meal plan and dinner notes, shown in the apps' Kitchen view and kept up by Claude's weekly grocery run through the `kitchen_*` MCP tools (`kitchen_get`, `kitchen_list_add`, `kitchen_list_update`, `kitchen_pantry_set`, `kitchen_corrections_resolve`, `kitchen_week_publish`). The contract is `docs/kitchen.md`; every rule is in `src/kitchen.ts`:

- Adding something already open on the list (same name, ignoring case, spacing, punctuation and a plural "s"; letters of any script count) reuses that item.
- Setting a pantry item out puts it on the shopping list, even if it was out already; setting it have takes off the list the `need` item the pantry put there (items a person or Claude added, and ordered ones, stay). Ticking a list item as got sets the pantry item with that name back to have.
- People correct the estimate with `POST /v1/kitchen/corrections`: `out` and `have` apply at once, a `note` waits for Claude. A `clientId` makes a resend after a timeout harmless (200, `repeated: true`). A status a person set outranks `kitchen_pantry_set` for a day unless Claude sends `override: true`.
- `POST /v1/kitchen/items` with `restore: true`, `status` and `orderRef` is the apps' Undo after a remove.
- Got items stay in the apps' list for 7 days, and in storage for 30. The list holds 400 open items; dinner notes are kept for about 400 days.
- The current week is the published week that contains `today` (also in the snapshot) in the household time zone: `AI_CALENDAR_TIME_ZONE`, default `America/Denver`. `nextWeek` is the next one published.
- A write that changes nothing does not change the revision.

Storage: key-value documents in the `integrations` collection (`kitchen:list`, `kitchen:pantry`, `kitchen:corrections`, `kitchen:notes`, `kitchen:weeks`, `kitchen:week:<start>`), each changed in a Firestore transaction (`mutateKv`). A change sends a `kitchen_sync` push to the other devices, so an open Kitchen view refreshes.

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
- Moving the time earlier removes a future occurrence that still sits at the old time once the new time has passed (today's 6:00 PM after a change to 8:00 AM at 1:00 PM).
- A series cannot be a delivery. The routine class meeting rule never archives an occurrence of a series.
- The first date must be from 2000-01-01 to 2100-12-31. Lead times are at most 60 days (86,400 minutes), the listing horizon, except for yearly series.
- `create_series` (and `POST /v1/series`) with the idempotency key of a series the user deleted answers `deleted: true` (the phone gets 409) and makes nothing; `create_reminder` does the same for a deleted reminder.

## Limits and safeguards

- Mail: `outlook_send_mail` takes at most 10 recipients (to and cc) and sends at most 20 mails a day (America/Denver), counted in a Firestore transaction (`integrations/outlookSendCount`). Every send, refusal and failure is logged in `integrations/outlookSendLog` (last 300) and Cloud Logging with metadata only: time, mode, recipient count, recipient domains, subject length.
- AI connectors: each sign-in is a grant (`grantId` on every token). A refresh token used again more than 2 minutes after it was exchanged revokes its whole grant (someone has a copy); other connections are untouched. Tokens have no absolute lifetime. A client registers at most 5 redirect URIs of 2,000 characters; one without a name is named after its redirect host, and the consent page shows the full redirect URI.
- Pairing: a code shorter than 32 characters is logged as weak at startup (never the code) and then also gets a global limit of 50 failed attempts an hour, counted in Firestore (`integrations/pairingFailures`). A strong code has only the per-address limit, so nobody can block the owner's pairing.
- Canvas: an open Canvas reminder due inside the fetched window that neither planner list shows any more is marked done (`retiredAt`), never deleted, and reopened if Canvas lists it again; more than 10 such items in one run retire none and log a warning (a partial Canvas answer). Lock and unlock dates and details that Canvas removed are cleared once details are fetched again.
- Request bodies: 16 KB by default (pairing, OAuth token and consent forms), 64 KB for client registration, 128 KB for the device routes, 4 MB for `/mcp`, which is parsed only after its token is checked.
- Planner state: at most 700,000 bytes (UTF-8) per document; appends run in a transaction. A key whose document id (dots become underscores) already belongs to another key is refused.

The server stores only a SHA-256 hash of each device token. Firebase data messages contain a revision signal, not private reminder content; the phone fetches reminder data over authenticated HTTPS.

## Local verification

```powershell
npm install
npm test
npm run build
$env:AI_CALENDAR_API_TOKEN='local-development-token'
$env:AI_CALENDAR_PAIRING_CODE='<at least 32 random characters>'
npm run dev
```

The local server refuses to start without `AI_CALENDAR_PAIRING_CODE`; it has no default. Local mode uses an in-memory store and does not send Firebase messages. Production uses Firestore and Firebase Cloud Messaging through `src/firebase-function.ts`.
