# Kitchen

A household's shopping list, pantry and weekly meal plan, shared by the phones, the Windows app and
AI clients. It was designed around a weekly grocery run done by an AI assistant: the assistant reads
the list and the pantry, places the order, marks what it ordered and publishes the week's meal plan.
People use the apps to add what ran out, tick off what they bought, mark pantry items low or out,
read tonight's recipe and say how a dinner went.

Every kitchen rule lives on the server, in `bridge/src/kitchen.ts`. The apps are views of
`GET /v1/kitchen` that send small edits; unlike the reminder rules, nothing here is implemented
twice. Requires bridge 0.9.0 or later (0.9.1 for the `kitchen_sync` push), phone 0.17.0 (0.18.0
for offline edits and pantry add and remove) and Windows 1.4.0 (1.5.0 for pantry add and remove).

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

**Pantry item:** `id` (`p` and 12 base36 characters), `name`, `section` (for example "Canned &
jarred" or "Freezer"; "Other" when unset), `status` (`have`, `low` or `out`), `note`, `updatedAt`.

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
2. Setting a pantry item to `out`, from any client, puts an item with the same name on the list
   (`source: pantry`) unless an open one exists.
3. When a list item becomes `got`, the pantry item with the same name becomes `have`.
4. `got` items stay in the device snapshot for 7 days (newest first), so a mistaken tick can be
   undone, and in storage for 30 days.
5. The current week is the published week whose `start` to `end` contains today in the bridge's
   time zone (`America/Denver` in the code; change it for your own deployment). `nextWeek` is the
   earliest published week that starts after today.

Storage is key-value documents in the `integrations` collection (`kitchen:list`, `kitchen:pantry`,
`kitchen:notes`, `kitchen:weeks`, `kitchen:week:<start>`), each changed in a Firestore transaction.

## Device API

Device token, as for `/v1/sync`. Without a valid token every route answers `401`; a bad body gets
`400 {"error": "..."}`. Every success carries `revision`, a number that only grows.

| Route | Body | Answer |
|---|---|---|
| `GET /v1/kitchen` | | `{ revision, list, pantry, week, nextWeek, notes }`. The list is `need` (oldest first), `ordered`, then `got` (newest first); the pantry is sorted by section, then name. |
| `POST /v1/kitchen/items` | `{ name, qty?, note? }` | `201` with `created: true`, or `200` with the open item (rule 1) |
| `PATCH /v1/kitchen/items/:id` | any of `name`, `qty`, `note`, `status` (`need` or `got`) | `200 { revision, item }`, `404` if unknown |
| `DELETE /v1/kitchen/items/:id` | | `200 { revision, deleted: true }`, `404` if unknown |
| `POST /v1/kitchen/pantry` | `{ name, section?, status? }` | `201`, or `200` with the existing item of that name; `listItem` when rule 2 added one |
| `PATCH /v1/kitchen/pantry/:id` | any of `status`, `note`, `section` | `200 { revision, item, listItem? }`, `404` if unknown |
| `DELETE /v1/kitchen/pantry/:id` | | `200 { revision, deleted: true }`, `404` if unknown |
| `PUT /v1/kitchen/notes/:date` | any of `cooked`, `rating`, `note` | `200 { revision, date, note }` |

Ids in paths match `^[A-Za-z0-9_-]{1,64}$`, dates `^\d{4}-\d{2}-\d{2}$`.

## MCP tools

| Tool | Does |
|---|---|
| `kitchen_get` | The snapshot, plus `got` items of the last 30 days and the list of published weeks. |
| `kitchen_list_add` | Adds 1 to 50 items (`source: ai`). Rule 1 applies. |
| `kitchen_list_update` | Changes 1 to 50 items by id: `status` (`need`, `ordered` with `orderRef`, or `got`), `name`, `qty`, `note`, or `remove: true`. |
| `kitchen_pantry_set` | Upserts 1 to 200 pantry items by name, or removes them. Rules 2 and 3 apply. |
| `kitchen_week_publish` | Stores one week; it replaces a stored week with the same `week.start`. |

## Keeping the apps current

After any kitchen change, from a device or an AI client, the bridge sends an FCM data message
`{ "type": "kitchen_sync", "revision": "<ms timestamp>" }` to every device except the one that made
the change (normal priority, 10-minute time to live). It carries no kitchen content.

- **Phone:** if the Kitchen view is on screen and the app is in the foreground, it fetches the
  kitchen again (at most one fetch in flight and one queued). Otherwise it does nothing. The view
  also fetches when it opens, when the app comes back, and on Refresh.
- **Windows:** no FCM on the PC. While the Kitchen tab is showing, it refreshes every 20 seconds
  when the window is active and every 60 seconds otherwise, and once when the window is activated.

## What the apps show

One Kitchen view with three parts, in the same XP controls and palettes as the rest of each app:

1. **This week:** tonight's dinner first (name, side, the day's note), then the other days. A day
   opens to its recipe (ingredients, numbered steps, note) and two rows of choices, Cooked or
   Skipped and Loved it, It was OK or Not again, plus a note.
2. **Shopping list:** a box with Add; the open items (`need`, then `ordered` with its order), each
   with a box to tick when bought and a way to remove it, with Undo; then "Got it" with the last 7
   days, where unticking sets `need` again.
3. **Pantry:** items by section, each Have, Low or Out. Marking Out says "Added to the shopping
   list" (rule 2). A form adds an item (name, section, status; a name already there is shown
   instead of added twice), and the x removes one, with Undo that adds it back as it was.

## Offline edits (phone)

When the bridge cannot be reached, the phone keeps the last good snapshot and shows it with a bar
("Offline: showing 9:41 AM. 2 changes will be sent when you're back online."). Edits made meanwhile
show at once and wait in their own queue, separate from the reminders' queue:

- An item added offline gets a temporary id `local-<uuid>`; later queued edits that name it use the
  real id once the add is sent. Removing it before then just drops the queued add.
- The queue is sent oldest first after the next successful fetch: opening the view, Refresh, a
  `kitchen_sync` signal, or the background reminder sync (after the reminders' own queue; a kitchen
  failure never affects reminder sync).
- A `404` drops the edit silently (the item is gone), a `400` drops it with one balloon naming it,
  and a network error or `5xx` keeps it and stops until the next try.

Online edits go straight to the server; only a failed connection queues.
