# Firebase setup

Everything server-side runs in one Firebase project: Firestore, two Cloud Functions (2nd gen,
`us-central1`), Secret Manager, Cloud Scheduler and Firebase Cloud Messaging. The apps carry no
Firebase credentials: the phone asks the bridge for its Firebase client configuration when it
pairs.

## 1. Project

1. Create a Firebase project in the [Firebase console](https://console.firebase.google.com). Note
   the **project id** (`<project-id>` below).
2. Upgrade it to the **Blaze** (pay as you go) plan. Cloud Functions, Secret Manager and Cloud
   Scheduler need it, and it needs a payment card. Most of the use stays inside the free
   quotas; see [Running costs](#running-costs) for what it costs and how to cap it. Set a budget
   alert when you add the card (step 7 there).
3. **Firestore:** create a database in Native mode. Region: any; `us-central1`/`nam5` sits next
   to the functions.
4. Install the CLI (it is also a dev dependency of `bridge/`) and select the project:

   ```
   cd bridge
   npm ci
   npx firebase login
   npx firebase use <project-id>     # writes .firebaserc in the repository root (git-ignored)
   ```

   `.firebaserc.example` shows the shape.

## 2. Firestore rules and TTL

`firestore.rules` denies every client read and write; only the functions (Admin SDK) touch data.

```
npx firebase deploy --only firestore:rules
```

Add **TTL policies** (Google Cloud console > Firestore > Time-to-live) on the field `expireAt`
for these collection groups: `oauthCodes`, `oauthTokens`, `integrations`, `events`. They clean
up expired sign-in codes and tokens, short-lived sign-in state, and the tombstones of deleted task
reminders (kept 400 days so a republish does not bring a deleted item back).

## 3. Register the Android app (for FCM)

1. Firebase console > Project settings > Add app > Android. Package name: the app's
   `applicationId` (`com.example.aicalendar` in this repository; change it in
   `app/build.gradle.kts` first if you want your own).
2. You do **not** need `google-services.json` in the app. From the registration, note:
   - **App ID** (`1:...:android:...`)
   - **Web API key** (Project settings > General)
   - **Sender ID** (Project settings > Cloud Messaging)
3. These are public client values, not secrets. The bridge hands them to a phone after it proves
   the pairing code.

## 4. Secrets

Create each secret once; the functions read them at runtime. Use `--data-file` with a file you
then delete, so the value never lands in shell history.

| Secret | Needed by | Value |
|---|---|---|
| `AI_CALENDAR_PAIRING_CODE` | `api` | A code you choose. Pairs the phone and the PC, and is asked for on the AI client sign-in page. Make it long enough not to guess (12+ characters) |
| `AI_CALENDAR_API_TOKEN` | `api` | A long random value (`openssl rand -base64 32`). A static bearer token for `/mcp`, used by local scripts such as `tools/mcp-call.ps1` |
| `CANVAS_BASE_URL` | `api`, `canvasSync` | Your Canvas address, `https://<school>.instructure.com`. Set it to any placeholder if you do not use Canvas |
| `CANVAS_ACCESS_TOKEN` | `api`, `canvasSync` | Canvas > Account > Settings > New access token. Placeholder if unused |
| `MS_CLIENT_ID` | `api` | From [SETUP-AZURE.md](SETUP-AZURE.md). Placeholder if you skip mail |
| `MS_CLIENT_SECRET` | `api` | From [SETUP-AZURE.md](SETUP-AZURE.md). Placeholder if you skip mail |

```
npx firebase functions:secrets:set AI_CALENDAR_PAIRING_CODE --data-file pairing.txt
npx firebase functions:secrets:set AI_CALENDAR_API_TOKEN --data-file token.txt
npx firebase functions:secrets:set CANVAS_BASE_URL --data-file canvas-url.txt
npx firebase functions:secrets:set CANVAS_ACCESS_TOKEN --data-file canvas-token.txt
npx firebase functions:secrets:set MS_CLIENT_ID --data-file ms-id.txt
npx firebase functions:secrets:set MS_CLIENT_SECRET --data-file ms-secret.txt
```

Every secret listed in `bridge/src/firebase-function.ts` must exist, or the deploy fails. Keep a
local copy of the API token in `bridge/.env.api-token` (git-ignored) for `tools/mcp-call.ps1`.

## 5. Function environment

Non-secret configuration goes in `bridge/.env.<project-id>` (git-ignored; Firebase loads it at
deploy). Start from `bridge/.env.example`:

```
AI_CALENDAR_FIREBASE_APP_ID=1:000000000000:android:0000000000000000
AI_CALENDAR_FIREBASE_API_KEY=<web api key>
AI_CALENDAR_FIREBASE_SENDER_ID=000000000000
AI_CALENDAR_PUBLIC_BASE_URL=https://us-central1-<project-id>.cloudfunctions.net/api
```

`AI_CALENDAR_PUBLIC_BASE_URL` must be the exact public address of the `api` function. It is the
OAuth issuer and resource for AI clients and the base of the Outlook redirect URI. The URL above is
the standard one for a 2nd-gen function named `api` in `us-central1`; check it after the first
deploy.

## 6. Deploy

```
cd bridge
npm test
cd ..
node bridge/node_modules/firebase-tools/lib/bin/firebase.js deploy --only functions
```

Or `scripts/deploy-bridge.ps1` (Windows; writes `build/scripts/bridge-deploy.log`, last line
`DEPLOY_OK` or `DEPLOY_FAILED`). The predeploy step in `firebase.json` compiles TypeScript.

The first deploy enables the needed Google APIs (Cloud Functions, Cloud Run, Cloud Build,
Artifact Registry, Eventarc, Pub/Sub, Cloud Scheduler, Secret Manager) and can take several
minutes. It creates:

- `api`: HTTPS, public invoker (all routes do their own authentication), up to 10 instances.
- `canvasSync`: Cloud Scheduler, every 30 minutes, `America/Denver` time base. It tops up
  repeating reminders and imports Canvas. Change `timeZone` in `firebase-function.ts` if you like.

Check it:

```
curl https://us-central1-<project-id>.cloudfunctions.net/api/health
# {"ok":true,"service":"ai-calendar-bridge"}
```

## Connect the apps and an AI client

**Phone.** Build the debug APK (`scripts/build-app.ps1`, or Android Studio), install it, open
Tools > Cloud Connection, enter the bridge URL
(`https://us-central1-<project-id>.cloudfunctions.net/api`) and the pairing code. The phone
fetches the Firebase client configuration, registers for FCM and syncs. Allow notifications and
exact alarms when Android asks.

**Windows.** `scripts/install-desktop.ps1` publishes a self-contained exe to
`%LOCALAPPDATA%\Programs\AI Calendar Desktop` and starts it. Pair under Tools > Cloud Connection with
the same URL and code. (`desktop/pair-desktop.ps1 -Project <project-id>` is different: it
**rotates** the pairing code, redeploys `api` and pairs the PC.)

**AI client.** Add a remote MCP server (in Claude: Settings > Connectors > Add custom connector)
with the URL `https://us-central1-<project-id>.cloudfunctions.net/api/mcp`. The client registers
itself (dynamic client registration), opens the consent page, and you enter the pairing code.
The server's `instructions` tell the client how to publish reminders; see `bridge/src/mcp.ts`.

**Local checks.** With `AI_CALENDAR_BASE_URL` set to the bridge URL and the API token in
`bridge/.env.api-token`:

```
powershell -File tools/mcp-call.ps1 list_series
```

## Canvas (optional)

See [SETUP-CANVAS.md](SETUP-CANVAS.md): the access token, the two `CANVAS_*` secrets, and what
the 30-minute sync imports.

## Running costs

The Blaze plan needs a card, but you pay only for use beyond the free quotas, and a personal
calendar is small. **Expect $0 to about $1 a month with the phone alone, and a few dollars a
month if the Windows app stays open most of the day.** The numbers below let you check that for
your own calendar. Prices are Google's list prices in October 2026, US regions; check the
linked pricing pages before relying on them.

### Free quotas that apply

| Service | What this project uses | Free each month | Beyond it |
|---|---|---|---|
| [Cloud Firestore](https://cloud.google.com/firestore/pricing) | Reminders, devices, sign-in tokens, series | 50,000 reads, 20,000 writes and 20,000 deletes **per day**, 1 GiB stored. Only the `(default)` database gets this | Reads about $0.03 to $0.06 per 100,000, depending on location |
| [Cloud Run functions](https://cloud.google.com/run/pricing) (2nd gen) | `api` and `canvasSync` | 2 million requests, 180,000 vCPU-seconds, 360,000 GiB-seconds, 1 GiB internet egress (North America) | Requests $0.40 per million; egress about $0.12 per GB |
| [Cloud Scheduler](https://cloud.google.com/scheduler/pricing) | 1 job (`canvasSync`) | 3 jobs per billing account | $0.10 per job per month |
| [Secret Manager](https://cloud.google.com/secret-manager/pricing) | 6 secrets | 6 active secret versions, 10,000 accesses per billing account | $0.06 per extra active version per month |
| [Artifact Registry](https://cloud.google.com/artifact-registry/pricing) | Container images of the functions | 0.5 GB | $0.10 per GB per month |
| Cloud Build, Cloud Storage | Building and uploading on deploy | 2,500 build-minutes; 5 GB in US regions | Not reached by occasional deploys |
| Firebase Cloud Messaging | Sync signals to the phone | Free | Free |

### Where the usage comes from

Every sync reads the whole `events` collection: one document read per reminder, including done
ones and the tombstones of deleted task items. With **N** reminders stored:

| Source | Firestore reads | Data sent out |
|---|---|---|
| Scheduled run, every 30 minutes (Canvas import and series top-up) | About 4 x N, plus one per Canvas item. 48 runs a day | None |
| Phone sync (on each change signal, every 6 hours, and when opened) | N per sync; a few dozen syncs a day | About 1.6 KB per reminder per sync |
| Windows app, while it is running | N per minute (it polls every 60 seconds) | About 1.6 KB per reminder per minute |
| AI client calls | N per `list_reminders`; a few per write | Small |

Example: **N = 360 reminders, with Canvas linked.**

| Scenario | Reads a day | Egress a month | Estimated cost a month |
|---|---|---|---|
| Phone only | About 85,000 (35,000 over the free 50,000) | Under 1 GB | **About $0.30 to $0.60** |
| Phone, and the Windows app open 8 hours a day | About 260,000 | About 8.5 GB | **About $2.80 to $4.70** |
| Phone, and the Windows app open around the clock | About 600,000 | About 25 GB | **About $8 to $13** |
| No Canvas, under 150 reminders, Windows app open only now and then | Under 50,000 | Under 1 GB | **$0** |

Everything else (function CPU and requests, Scheduler, Secret Manager, FCM) stays inside its free
quota at this size. If your bill shows $0 while the table says otherwise, check for Google Cloud
free trial credits on the billing account; they hide charges until they run out.

### Keeping it at or near $0

1. **Close the Windows app when you are not using it,** or turn off Tools > Options > keep running
   in the notification area. It is the largest cost by far.
2. **Keep the reminder count down.** Done reminders and tombstones are read on every sync.
   Deleting old archived reminders lowers every number above.
3. **Canvas** accounts for about three of the four full reads in each scheduled run. Without it
   (placeholder secrets), the Canvas step fails, the scheduler retries the run up to twice, and
   each attempt still tops up repeating reminders: about N to 3 x N reads per run instead of
   4 x N.
4. **Secret versions:** every `functions:secrets:set` adds a version, and old versions stay
   active (and billable past six). Remove unused ones with `npx firebase functions:secrets:prune`.
5. **Container images:** when the Firebase CLI asks on deploy how long to keep container images,
   pick a short period (a day or so), or set a cleanup policy on the `gcf-artifacts` repository in
   Artifact Registry. Otherwise old images pile up past the free 0.5 GB.
6. **Function limits:** `api` scales to zero (`minInstances: 0`) and is capped at 10 instances;
   `canvasSync` runs one at a time. An idle project costs nothing.
7. **Budget alert:** Google Cloud console > Billing > Budgets & alerts > Create budget, for
   example $5 a month with alerts at 50%, 90% and 100%. An alert does not stop charges, but it
   tells you early.

### Limits

The design suits hundreds to a few thousand reminders. Because syncs read the whole collection,
costs grow with the reminder count times the sync count; a much larger calendar would want an
incremental sync (only what changed since the device's last revision).
