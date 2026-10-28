# Kitchen

A household's shopping list, pantry and weekly meal plan, shared by the phones, the Windows app and
AI clients. It was designed around a weekly grocery run done by an AI assistant: the assistant reads
the list and the pantry, places the order, marks what it ordered and publishes the week's meal plan.

The pantry is the assistant's **estimate** of what is on hand, with a reason for each item ("bought
10/26", "used in Wed jambalaya"). Nobody keeps it up to date by hand. People use the apps to add
things to buy, tick off what they bought, correct the estimate ("We're out", "We have it", or a
note to the assistant), read tonight's recipe and say how a dinner went.

Every kitchen rule lives on the server, in `bridge/src/kitchen.ts`. The apps are views of
`GET /v1/kitchen` that send small edits; unlike the reminder rules, nothing here is implemented
twice.

Versions: bridge 0.9.0 or later (0.9.1 for the `kitchen_sync` push, 0.10.0 for the pantry estimate
and corrections, 0.11.0 for repeat-safe corrections, Undo that keeps an item's status, `today` and
the time zone setting). Phone 0.17.0 (0.18.0 for offline edits, 0.19.0 for On hand), Windows 1.4.0
(1.6.0 for On hand). An older bridge ignores the fields it does not know.

## Records

**Shopping-list item**

| Field | Meaning |
|---|---|
| `id` | `k` and 12 base36 characters. Stable. |
| `name` | 1 to 120 characters, for example "Aluminum foil". |
| `qty`, `note` | Optional free text (at most 40 and 300 characters), `""` when unset. |
| `status` | `need` (still to buy), `ordered` (on a placed order) or `got` (bought or arrived). |
| `orderRef` | A short reference while ordered, for example "Walmart #200000000001, Tue 11/3". |
| `source`, `addedBy` | Who added it: `device` (the device's name), `ai` ("Claude") or `pantry` ("Pantry"). |
| `addedAt`, `updatedAt`, `doneAt` | ISO timestamps; `doneAt` only once it is `got`. |

**Pantry item** (the estimate)

| Field | Meaning |
|---|---|
| `id` | `p` and 12 base36 characters. |
| `name`, `section` | For example "Black beans" in "Canned & jarred"; the section is "Other" when unset. |
| `status` | `have`, `low` or `out`: what the AI client thinks, or what a person said. |
| `basis` | Why, in a few words, shown under the item: "bought 10/26", "Pixel said it's out". |
| `checkedAt` | When the AI client last assessed it. The apps show the newest one as "Updated ...". |
| `correctedAt` | When a person last set its status with a correction. |
| `note`, `updatedAt` | Free text, and the last change. |

**Correction:** a person's fix to the estimate.

| Field | Meaning |
|---|---|
| `id` | `c` and 12 base36 characters. |
| `kind` | `out` ("We're out"), `have` ("We have it") or `note` (free text for the AI client). |
| `text` | The note, up to 300 characters (`""` for out and have). |
| `itemId`, `itemName` | The pantry item it is about, when there is one. |
| `status` | `open` (a note the AI client has not handled yet), `applied` or `dismissed`. |
| `reply` | What the apps show under it: the server's answer for out and have, the AI client's for a note. |
| `addedBy`, `addedAt`, `resolvedAt` | The device's name and the timestamps. |
| `clientId` | The sending app's own id for it, when it sent one. |

**Meal-plan week:** a JSON object published by the AI client. The apps read these fields and ignore
the rest:

```json
{
  "week": { "start": "2026-11-03", "end": "2026-11-09", "title": "Meal plan, Nov 3-9" },
  "tagline": "For 2",
  "meta": ["Ordered, delivery **Tue 10am-noon**"],
  "days": [
    { "date": "2026-11-03", "label": "Tue 11/3", "dinner": "Baked salmon", "base": "rice",
      "note": "Thaw the chicken tonight", "kind": "cook", "recipe": "salmon" }
  ],
  "recipes": [
    { "id": "salmon", "title": "Baked salmon", "sub": "Tue, 30 min, serves 2",
      "ingredients": "Salmon fillets (about 1 lb), ...", "steps": ["...", "..."], "note": "optional" }
  ]
}
```

Strings may contain `**bold**`. `days[].kind` is `cook`, `leftovers` or `special`; `days[].recipe`
is the `id` of a recipe, or absent on a leftovers night.

**Dinner note:** keyed by the dinner's date, `{ "cooked": "yes" | "skipped" | "", "rating": "loved"
| "ok" | "no" | "", "note": "", "updatedAt": "..." }`.

## Server rules

1. Adding an item whose name matches an open one (`need` or `ordered`; case, spacing, punctuation
   and a plural "s" are ignored) returns the open item instead of a duplicate. A `qty` or `note`
   sent with it replaces the stored one.
2. **Out means buy it.** Setting a pantry item `out` (a "We're out" correction, `kitchen_pantry_set`,
   or an older app's pantry edit) always leaves an open item with that name on the list. If none
   is open, one is added (`source: pantry`). This also happens when the item was out already and
   its list item was removed or bought since.
3. **Have takes it off.** Setting a pantry item `have` removes the open `need` item the pantry put
   on the list for it (`source: pantry`). Items a person or the AI client added stay, and the reply
   says it is still on the list.
4. When a list item becomes `got`, the pantry item with the same name becomes `have`.
5. A status a person set with a correction outranks the estimate for a day. `kitchen_pantry_set`
   keeps it and answers `keptPersonStatus: true`, unless it sends `override: true`.
6. `got` items stay in the device snapshot for 7 days (newest first), so a mistaken tick can be
   undone, and in storage for 30 days.
7. **Today** is the date in the household's time zone: `AI_CALENDAR_TIME_ZONE` on the bridge, an
   IANA name, `America/Denver` when unset or invalid. The snapshot carries it as `today`. The
   current week is the published week whose `start` to `end` contains it, and `nextWeek` is the
   earliest published week that starts after it. The same zone gives date-only reminders their
   09:00.

Storage is key-value documents in the `integrations` collection (`kitchen:list`, `kitchen:pantry`,
`kitchen:corrections`, `kitchen:notes`, `kitchen:weeks`, `kitchen:week:<start>`), each changed in a
Firestore transaction.

To set the time zone, put `AI_CALENDAR_TIME_ZONE=Europe/Berlin` (for example) in
`bridge/.env.<project-id>` and deploy.

## Device API

Device token, as for `/v1/sync`. Without a valid token every route answers `401`; a bad body gets
`400 {"error": "..."}`. Every success carries `revision`, a number that only grows.

| Route | Body | Answer |
|---|---|---|
| `GET /v1/kitchen` | | `{ revision, today, list, pantry, week, nextWeek, notes, corrections }`. The list is `need` (oldest first), `ordered`, then `got` (newest first). The pantry is sorted by section, then name. Corrections: open ones oldest first, then the ones handled in the last 7 days, newest first. |
| `POST /v1/kitchen/items` | `{ name, qty?, note? }` | `201` with `created: true`, or `200` with the open item (rule 1). |
| `POST /v1/kitchen/items` (Undo) | `{ name, qty?, note?, restore: true, status, orderRef? }` | Puts a removed line back with its `status` (`need`, `ordered` or `got`) and order reference, instead of as `need`. An open item of that name is returned instead (`200`); a `got` line is always put back. |
| `PATCH /v1/kitchen/items/:id` | any of `name`, `qty`, `note`, `status` (`need` or `got`) | `200 { revision, item }`, `404` if unknown |
| `DELETE /v1/kitchen/items/:id` | | `200 { revision, deleted: true }`, `404` if unknown |
| `POST /v1/kitchen/corrections` | `{ kind, text?, itemId?, clientId? }` | `201 { revision, correction, item?, listItem?, removedListItemId? }`. See below. |
| `DELETE /v1/kitchen/corrections/:id` | | Withdraws an **open** note: `200 { revision, deleted: true }`, `404` otherwise. |
| `PUT /v1/kitchen/notes/:date` | any of `cooked`, `rating`, `note` | `200 { revision, date, note }` |

A correction:
- `out` and `have` need the pantry item's `itemId` and apply at once. The status changes, the basis
  becomes "<device> said it's out" or "<device> said you have it", and rules 2 and 3 run. The
  correction is stored as `applied`, with a reply:
  - "Marked out and added to the shopping list."
  - "Marked as on hand and taken off the shopping list."
  - "Marked as on hand; it's still on the shopping list."

  An unknown item is a `404`. `listItem` is the item rule 2 added, and `removedListItemId` the one
  rule 3 removed.
- `note` needs `text` (1 to 300 characters) and may name an `itemId`. A note about an item removed
  since is still stored, without the item. It stays `open` until the AI client answers it.
- `clientId` (1 to 64 letters, digits, `_` or `-`) makes a resend safe. Sending the same `clientId`
  again changes nothing and answers `200` with the stored correction and `repeated: true`. The apps
  send one with every correction, so a send that timed out after the server stored it is not
  stored twice.

Ids in paths match `^[A-Za-z0-9_-]{1,64}$`, dates `^\d{4}-\d{2}-\d{2}$`.

The pantry routes of bridge 0.9.x (`POST /v1/kitchen/pantry`, `PATCH` and `DELETE
/v1/kitchen/pantry/:id`) remain for phone 0.18 and Windows 1.5. A status changed with the `PATCH`
counts as a person's correction (rules 2, 3 and 5). The current apps do not use these routes.

## MCP tools

| Tool | Does |
|---|---|
| `kitchen_get` | The snapshot, plus `got` items of the last 30 days and the list of published weeks. Open corrections are the AI client's to apply. |
| `kitchen_list_add` | Adds 1 to 50 items (`source: ai`). Rule 1 applies. For things to buy that the pantry doesn't keep; for "we ran out of X", set X out with `kitchen_pantry_set`, which lists it too. |
| `kitchen_list_update` | Changes 1 to 50 items by id: `status` (`need`, `ordered` with `orderRef`, or `got`), `name`, `qty`, `note`, or `remove: true`. |
| `kitchen_pantry_set` | Upserts 1 to 200 pantry items by name (`section`, `status`, `basis`, `note`), or removes them (`remove: true`). Rules 2, 3 and 5 apply, and `override: true` replaces a person's recent status. Each result may carry `listItem`, `removedListItemId` or `keptPersonStatus`. |
| `kitchen_corrections_resolve` | Answers 1 to 50 corrections after acting on them: `{ updates: [{ id, status: "applied" \| "dismissed", reply? }] }`. The reply (up to 300 characters) is what the apps show. |
| `kitchen_week_publish` | Stores one week; it replaces a stored week with the same `week.start`. |

The AI client applies open notes (with `kitchen_pantry_set` or the list tools), then answers each
one with `kitchen_corrections_resolve`.

## Keeping the apps current

After any kitchen change, from a device or an AI client, the bridge sends an FCM data message
`{ "type": "kitchen_sync", "revision": "<ms timestamp>" }` to every device except the one that made
the change (normal priority, 10-minute time to live). It carries no kitchen content. A repeated
correction changes nothing and sends no message.

- **Phone:** if the Kitchen view is on screen and the app is in the foreground, it fetches the
  kitchen again (at most one fetch in flight and one queued). Otherwise it does nothing. The view
  also fetches when it opens, when the app comes back, and on Refresh.
- **Windows:** no FCM on the PC. While the Kitchen tab is showing, it refreshes every 20 seconds
  when the window is active and every 60 seconds otherwise, and once when the window is activated.

## What the apps show

One Kitchen view with three parts, in the same XP controls and palettes as the rest of each app:

1. **This week:** tonight's dinner first (name, side, the day's note), then the other days. "Tonight"
   is the snapshot's `today`. A day opens to its recipe (ingredients, numbered steps, note) and two
   rows of choices, Cooked or Skipped and Loved it, It was OK or Not again, plus a note.
2. **Shopping list:** a box with Add; the open items (`need`, then `ordered` with its order), each
   with a box to tick when bought and a way to remove it. Undo after a remove puts the line back as
   it was, ordered lines with their order (`restore`). Then "Got it" with the last 7 days, where
   unticking sets `need` again.
3. **On hand:** what the AI client thinks the household has.
   - The header reads "Updated <newest `checkedAt`>".
   - Below it is a **Tell Claude** box. Send posts a `note`.
   - Then the corrections. Open notes show as "Waiting for Claude", and one can still be
     withdrawn. Handled ones from the last 7 days show with their reply.
   - The estimate is grouped by section. Each item shows **Have**, **Running low** or **Probably
     out** and its basis. Low and out sort first, and a filter shows only those.
   - Each item has three actions:
     - **We're out:** sends `out`. It always lists the item (rule 2), and the reply says so.
     - **We have it:** sends `have`. It takes the pantry's own list item off (rule 3).
     - **Something else...:** opens the Tell Claude box with the item named.
   - There are no Have/Low/Out controls, no add form and no remove button. The AI client adds and
     removes items from what people tell it.

## Offline edits (phone)

When the bridge cannot be reached, the phone keeps the last good snapshot and shows it with a bar
("Offline: showing 9:41 AM. 2 changes will be sent when you're back online."). Edits made meanwhile
show at once and wait in their own queue, separate from the reminders' queue:

- An item added offline gets a temporary id `local-<uuid>`; later queued edits that name it use the
  real id once the add is sent. Removing it before then just drops the queued add.
- Corrections and notes to Claude are queued too. Each keeps its `clientId` across tries, so a
  resend never stores it twice.
- The queue is sent oldest first after the next successful fetch: opening the view, Refresh, a
  `kitchen_sync` signal, or the background reminder sync (after the reminders' own queue; a kitchen
  failure never affects reminder sync).
- A `404` drops the edit (the item is gone), a `400` drops it with one balloon naming it, and a
  network error or `5xx` keeps it and stops until the next try.

Online edits go straight to the server; only a failed connection queues.
