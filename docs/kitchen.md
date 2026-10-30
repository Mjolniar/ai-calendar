# Kitchen

> AI-generated.

Shared shopping list, pantry and weekly meal plan for phones, the Windows app and AI clients. Built around a weekly
grocery run by an AI assistant: it reads the list and pantry, places the order, marks what it ordered, publishes the
meal plan.

The pantry is the assistant's **estimate**, with a reason per item ("bought 10/26", "used in Wed jambalaya"). Nobody
maintains it by hand. People use the apps to add things to buy, tick off purchases, correct the estimate ("We're
out", "We have it", or a note to the assistant), read tonight's recipe and rate dinners.

All rules live on the server (`bridge/src/kitchen.ts`). The apps are views of `GET /v1/kitchen` that send small edits;
nothing is implemented twice.

Versions: bridge 0.9.0+ (0.9.1 `kitchen_sync` push; 0.10.0 pantry estimate and corrections; 0.11.0 repeat-safe
corrections, status-keeping Undo, `today`, time zone setting). Phone 0.17.0 (0.18.0 offline edits, 0.19.0 On hand),
Windows 1.4.0 (1.6.0 On hand). Older bridges ignore unknown fields.

## Records

**Shopping-list item**

| Field | Meaning |
|---|---|
| `id` | `k` + 12 base36 characters. Stable. |
| `name` | 1-120 characters, e.g. "Aluminum foil". |
| `qty`, `note` | Optional text (max 40 / 300), `""` when unset. |
| `status` | `need` (to buy), `ordered` (on a placed order), `got` (bought or arrived). |
| `orderRef` | Short reference while ordered, e.g. "Walmart #200000000001, Tue 11/3". |
| `source`, `addedBy` | `device` (device name), `ai` ("Claude") or `pantry` ("Pantry"). |
| `addedAt`, `updatedAt`, `doneAt` | ISO timestamps; `doneAt` once `got`. |

**Pantry item** (the estimate)

| Field | Meaning |
|---|---|
| `id` | `p` + 12 base36 characters. |
| `name`, `section` | e.g. "Black beans" in "Canned & jarred"; section "Other" when unset. |
| `status` | `have`, `low`, `out`: the AI client's estimate or a person's correction. |
| `basis` | Short reason shown under the item: "bought 10/26", "Pixel said it's out". |
| `checkedAt` | Last AI assessment. Apps show the newest as "Updated ...". |
| `correctedAt` | Last status set by a person's correction. |
| `note`, `updatedAt` | Free text; last change. |

**Correction** (a person's fix to the estimate)

| Field | Meaning |
|---|---|
| `id` | `c` + 12 base36 characters. |
| `kind` | `out` ("We're out"), `have` ("We have it"), `note` (free text for the AI client). |
| `text` | Note, max 300 characters (`""` for out/have). |
| `itemId`, `itemName` | Related pantry item, if any. |
| `status` | `open` (unhandled note), `applied`, `dismissed`. |
| `reply` | Shown under it: server answer for out/have, AI client answer for notes. |
| `addedBy`, `addedAt`, `resolvedAt` | Device name, timestamps. |
| `clientId` | Sending app's own id, if sent. |

**Meal-plan week** (JSON published by the AI client; apps read these fields, ignore the rest):

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

Strings may contain `**bold**`. `days[].kind`: `cook`, `leftovers`, `special`. `days[].recipe`: a recipe `id`, absent
on leftovers nights.

**Dinner note** (keyed by date): `{ "cooked": "yes" | "skipped" | "", "rating": "loved" | "ok" | "no" | "", "note": "",
"updatedAt": "..." }`.

## Server rules

1. Adding a name that matches an open item (`need`/`ordered`; ignoring case, spacing, punctuation, plural "s") returns
   that item. A sent `qty`/`note` replaces the stored one.
2. **Out means buy it.** Setting a pantry item `out` (correction, `kitchen_pantry_set`, or an older app's pantry edit)
   always leaves an open list item of that name; one is added (`source: pantry`) if none is open, even if it was
   already out and its list item was removed or bought since.
3. **Have takes it off.** Setting `have` removes the open `need` item the pantry added (`source: pantry`). Items added
   by a person or the AI client stay; the reply says it is still listed.
4. A list item becoming `got` sets the same-named pantry item to `have`.
5. A person's correction outranks the estimate for a day. `kitchen_pantry_set` keeps it and answers
   `keptPersonStatus: true`, unless sent with `override: true`.
6. `got` items stay in the device snapshot 7 days (newest first, for undoing mistakes) and in storage 30 days.
7. **Today** = date in the household zone: `AI_CALENDAR_TIME_ZONE` (IANA), `America/Denver` if unset or invalid. The
   snapshot carries it as `today`. Current week = published week whose `start`-`end` contains it; `nextWeek` = earliest
   published week starting after it. The same zone gives date-only reminders 09:00.

Storage: key-value documents in `integrations` (`kitchen:list`, `kitchen:pantry`, `kitchen:corrections`,
`kitchen:notes`, `kitchen:weeks`, `kitchen:week:<start>`), each changed in a Firestore transaction.

Time zone: put e.g. `AI_CALENDAR_TIME_ZONE=Europe/Berlin` in `bridge/.env.<project-id>` and deploy.

## Device API

Device token as for `/v1/sync`. No valid token: `401`. Bad body: `400 {"error": "..."}`. Every success carries
`revision` (only increases).

| Route | Body | Answer |
|---|---|---|
| `GET /v1/kitchen` | | `{ revision, today, list, pantry, week, nextWeek, notes, corrections }`. List: `need` (oldest first), `ordered`, then `got` (newest first). Pantry by section, then name. Corrections: open oldest first, then those handled in the last 7 days, newest first. |
| `POST /v1/kitchen/items` | `{ name, qty?, note? }` | `201` with `created: true`, or `200` with the open item (rule 1). |
| `POST /v1/kitchen/items` (Undo) | `{ name, qty?, note?, restore: true, status, orderRef? }` | Restores a removed line with its `status` (`need`, `ordered`, `got`) and order reference. An open item of that name is returned instead (`200`); a `got` line is always restored. |
| `PATCH /v1/kitchen/items/:id` | any of `name`, `qty`, `note`, `status` (`need`/`got`) | `200 { revision, item }`, `404` if unknown |
| `DELETE /v1/kitchen/items/:id` | | `200 { revision, deleted: true }`, `404` if unknown |
| `POST /v1/kitchen/corrections` | `{ kind, text?, itemId?, clientId? }` | `201 { revision, correction, item?, listItem?, removedListItemId? }`. See below. |
| `DELETE /v1/kitchen/corrections/:id` | | Withdraws an **open** note: `200 { revision, deleted: true }`, else `404`. |
| `PUT /v1/kitchen/notes/:date` | any of `cooked`, `rating`, `note` | `200 { revision, date, note }` |

Corrections:
- `out` / `have` need `itemId` and apply at once: status changes, basis becomes "<device> said it's out" / "<device>
  said you have it", rules 2 and 3 run. Stored as `applied` with a reply:
  - "Marked out and added to the shopping list."
  - "Marked as on hand and taken off the shopping list."
  - "Marked as on hand; it's still on the shopping list."

  Unknown item: `404`. `listItem` = item added by rule 2; `removedListItemId` = item removed by rule 3.
- `note` needs `text` (1-300) and may name an `itemId`. A note about a since-removed item is stored without it. Stays
  `open` until the AI client answers.
- `clientId` (1-64 letters, digits, `_`, `-`) makes resends safe: the same `clientId` again changes nothing and answers
  `200` with the stored correction and `repeated: true`. Apps send one with every correction.

Path ids match `^[A-Za-z0-9_-]{1,64}$`, dates `^\d{4}-\d{2}-\d{2}$`.

Bridge 0.9.x pantry routes (`POST /v1/kitchen/pantry`, `PATCH` / `DELETE /v1/kitchen/pantry/:id`) remain for phone
0.18 and Windows 1.5. A `PATCH` status change counts as a person's correction (rules 2, 3, 5). Current apps do not use
them.

## MCP tools

| Tool | Function |
|---|---|
| `kitchen_get` | Snapshot plus `got` items of the last 30 days and the list of published weeks. Open corrections are the AI client's to apply. |
| `kitchen_list_add` | Adds 1-50 items (`source: ai`); rule 1 applies. For things the pantry does not track; for "we ran out of X", set X out with `kitchen_pantry_set` (which lists it). |
| `kitchen_list_update` | Changes 1-50 items by id: `status` (`need`, `ordered` with `orderRef`, `got`), `name`, `qty`, `note`, or `remove: true`. |
| `kitchen_pantry_set` | Upserts 1-200 pantry items by name (`section`, `status`, `basis`, `note`) or removes them (`remove: true`). Rules 2, 3, 5 apply; `override: true` replaces a recent person status. Results may carry `listItem`, `removedListItemId`, `keptPersonStatus`. |
| `kitchen_corrections_resolve` | Answers 1-50 corrections after acting: `{ updates: [{ id, status: "applied" \| "dismissed", reply? }] }`. Reply (max 300) is shown in the apps. |
| `kitchen_week_publish` | Stores one week; replaces a stored week with the same `week.start`. |

The AI client applies open notes (via `kitchen_pantry_set` or list tools), then answers each with
`kitchen_corrections_resolve`.

## Keeping apps current

After any kitchen change the bridge sends FCM data `{ "type": "kitchen_sync", "revision": "<ms timestamp>" }` to every
other device (normal priority, 10-minute TTL). No content. A repeated correction sends nothing.

- **Phone:** refetches if the Kitchen view is on screen and the app is in the foreground (max one fetch running, one
  queued). Also fetches on open, on return to the app, and on Refresh.
- **Windows:** no FCM. While the Kitchen tab shows: every 20 s when the window is active, 60 s otherwise, and once on
  activation.

## App view

One Kitchen view, three parts, same XP controls and palettes as the rest of each app:

1. **This week:** tonight's dinner first (name, side, note), then the other days. "Tonight" = snapshot `today`. A day
   opens to its recipe (ingredients, numbered steps, note), Cooked/Skipped, Loved it/It was OK/Not again, and a note.
2. **Shopping list:** Add box; open items (`need`, then `ordered` with order), each with a tick box and remove. Undo
   after remove restores the line as it was, ordered lines with their order (`restore`). Then "Got it" (last 7 days);
   unticking sets `need`.
3. **On hand:** the AI client's estimate.
   - Header: "Updated <newest `checkedAt`>".
   - **Tell Claude** box; Send posts a `note`.
   - Corrections: open notes as "Waiting for Claude" (withdrawable); handled ones from the last 7 days with replies.
   - Estimate by section. Each item shows **Have**, **Running low** or **Probably out** and its basis. Low and out
     first; a filter shows only those.
   - Item actions:
     - **We're out:** sends `out`; always lists the item (rule 2); the reply says so.
     - **We have it:** sends `have`; removes the pantry's own list item (rule 3).
     - **Something else...:** opens Tell Claude with the item named.
   - No Have/Low/Out controls, add form or remove button. The AI client adds and removes items from people's notes.

## Offline edits (phone)

Without the bridge the phone shows the last good snapshot with a bar ("Offline: showing 9:41 AM. 2 changes will be sent
when you're back online."). Edits show at once and wait in their own queue, separate from reminders:

- Items added offline get temporary id `local-<uuid>`; later queued edits use the real id once the add is sent.
  Removing it first just drops the queued add.
- Corrections and notes are queued too, keeping their `clientId` across tries (no duplicates).
- The queue is sent oldest first after the next successful fetch: opening the view, Refresh, a `kitchen_sync` signal,
  or background reminder sync (after the reminder queue; kitchen failures never affect reminder sync).
- `404` drops the edit (item gone); `400` drops it with one balloon naming it; network error or `5xx` keeps it and stops
  until the next try.

Online edits go straight to the server; only a failed connection queues.
