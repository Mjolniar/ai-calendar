# Firebase setup

> AI-generated.

All server parts run in one Firebase project: Firestore, two Cloud Functions (2nd gen, `us-central1`), Secret
Manager, Cloud Scheduler, Firebase Cloud Messaging. The apps carry no Firebase credentials; the phone gets its client
config from the bridge when it pairs.

## 1. Project

1. Create a project in the [Firebase console](https://console.firebase.google.com). Note the **project id**
   (`<project-id>` below).
2. Upgrade to **Blaze** (pay as you go). Required for Cloud Functions, Secret Manager, Cloud Scheduler; needs a payment
   card. Most use stays in free quotas; see [Running costs](#running-costs). Set a budget alert when adding the card
   (step 7 there).
3. **Firestore:** create a database in Native mode. Any region; `us-central1`/`nam5` is next to the functions.
4. CLI (also a dev dependency of `bridge/`):

   ```
   cd bridge
   npm ci
   npx firebase login
   npx firebase use <project-id>     # writes .firebaserc in the repository root (git-ignored)
   ```

   Shape: `.firebaserc.example`.

## 2. Firestore rules and TTL

`firestore.rules` denies all client reads and writes; only the functions (Admin SDK) touch data.

```
npx firebase deploy --only firestore:rules
```

Add **TTL policies** (Google Cloud console > Firestore > Time-to-live) on field `expireAt` for collection groups
`oauthCodes`, `oauthTokens`, `integrations`, `events`. They remove expired sign-in codes and tokens, short-lived
sign-in state, and tombstones of deleted task reminders (kept 400 days so a republish does not revive them).

## 3. Register the Android app (FCM)

1. Firebase console > Project settings > Add app > Android. Package name = `applicationId`
   (`com.example.aicalendar` here; change it in `app/build.gradle.kts` first for your own).
2. No `google-services.json` needed. Note:
   - **App ID** (`1:...:android:...`)
   - **Web API key** (Project settings > General)
   - **Sender ID** (Project settings > Cloud Messaging)
3. These are public client values. The bridge gives them to a phone after it proves the pairing code.

## 4. Secrets

Create each once. Use `--data-file` with a file you delete afterwards (keeps values out of shell history).

| Secret | Used by | Value |
|---|---|---|
| `AI_CALENDAR_PAIRING_CODE` | `api` | Your code. Pairs phone and PC; asked on the AI client sign-in page. 12+ characters, hard to guess |
| `AI_CALENDAR_API_TOKEN` | `api` | Long random value (`openssl rand -base64 32`). Static bearer for `/mcp`, for local scripts like `tools/mcp-call.ps1` |
| `CANVAS_BASE_URL` | `api`, `canvasSync` | `https://<school>.instructure.com`. Placeholder if no Canvas |
| `CANVAS_ACCESS_TOKEN` | `api`, `canvasSync` | Canvas > Account > Settings > New access token. Placeholder if unused |
| `MS_CLIENT_ID` | `api` | From [SETUP-AZURE.md](SETUP-AZURE.md). Placeholder if no mail |
| `MS_CLIENT_SECRET` | `api` | From [SETUP-AZURE.md](SETUP-AZURE.md). Placeholder if no mail |

```
npx firebase functions:secrets:set AI_CALENDAR_PAIRING_CODE --data-file pairing.txt
npx firebase functions:secrets:set AI_CALENDAR_API_TOKEN --data-file token.txt
npx firebase functions:secrets:set CANVAS_BASE_URL --data-file canvas-url.txt
npx firebase functions:secrets:set CANVAS_ACCESS_TOKEN --data-file canvas-token.txt
npx firebase functions:secrets:set MS_CLIENT_ID --data-file ms-id.txt
npx firebase functions:secrets:set MS_CLIENT_SECRET --data-file ms-secret.txt
```

Every secret listed in `bridge/src/firebase-function.ts` must exist or the deploy fails. Keep a local copy of the API
token in `bridge/.env.api-token` (git-ignored) for `tools/mcp-call.ps1`.

## 5. Function environment

Non-secret config in `bridge/.env.<project-id>` (git-ignored; loaded at deploy). Start from `bridge/.env.example`:

```
AI_CALENDAR_FIREBASE_APP_ID=1:000000000000:android:0000000000000000
AI_CALENDAR_FIREBASE_API_KEY=<web api key>
AI_CALENDAR_FIREBASE_SENDER_ID=000000000000
AI_CALENDAR_PUBLIC_BASE_URL=https://us-central1-<project-id>.cloudfunctions.net/api
```

`AI_CALENDAR_PUBLIC_BASE_URL` must be the exact public address of `api`: OAuth issuer and resource for AI clients and
base of the Outlook redirect URI. The URL above is the standard one for a 2nd-gen `api` in `us-central1`; verify after
the first deploy.

## 6. Deploy

```
cd bridge
npm test
cd ..
node bridge/node_modules/firebase-tools/lib/bin/firebase.js deploy --only functions
```

Or `scripts/deploy-bridge.ps1` (Windows; log `build/scripts/bridge-deploy.log`, last line `DEPLOY_OK` or
`DEPLOY_FAILED`). The predeploy step in `firebase.json` compiles TypeScript.

The first deploy enables the needed APIs (Cloud Functions, Cloud Run, Cloud Build, Artifact Registry, Eventarc,
Pub/Sub, Cloud Scheduler, Secret Manager) and can take minutes. It creates:

- `api`: HTTPS, public invoker (routes authenticate themselves), max 10 instances.
- `canvasSync`: Cloud Scheduler, every 30 minutes, `America/Denver` time base. Tops up repeating reminders and imports
  Canvas. Change `timeZone` in `firebase-function.ts` if wanted.

Check:

```
curl https://us-central1-<project-id>.cloudfunctions.net/api/health
# {"ok":true,"service":"ai-calendar-bridge"}
```

## Connect the apps and an AI client

**Phone.** Build the debug APK (`scripts/build-app.ps1` or Android Studio), install, open Tools > Cloud Connection,
enter the bridge URL (`https://us-central1-<project-id>.cloudfunctions.net/api`) and the pairing code. The phone
fetches its Firebase config, registers for FCM and syncs. Allow notifications and exact alarms.

**Windows.** `scripts/install-desktop.ps1` publishes a self-contained exe to
`%LOCALAPPDATA%\Programs\AI Calendar Desktop` and starts it. Pair under Tools > Cloud Connection with the same URL and
code. (`desktop/pair-desktop.ps1 -Project <project-id>` is different: it **rotates** the pairing code, redeploys `api`
and pairs the PC.)

**AI client.** Add a remote MCP server (Claude: Settings > Connectors > Add custom connector) with URL
`https://us-central1-<project-id>.cloudfunctions.net/api/mcp`. The client registers itself, opens the consent page,
and you enter the pairing code. The server's `instructions` tell it how to publish reminders (`bridge/src/mcp.ts`).

**Local checks.** With `AI_CALENDAR_BASE_URL` set to the bridge URL and the API token in `bridge/.env.api-token`:

```
powershell -File tools/mcp-call.ps1 list_series
```

## Canvas (optional)

[SETUP-CANVAS.md](SETUP-CANVAS.md): access token, the two `CANVAS_*` secrets, what the sync imports.

## Running costs

Blaze needs a card, but you pay only beyond the free quotas. **Expect $0 to ~$1/month with the phone only; a few
dollars/month if the Windows app stays open most of the day.** Prices: Google list prices, October 2026, US regions;
check the linked pages.

### Free quotas

| Service | Used for | Free per month | Beyond |
|---|---|---|---|
| [Cloud Firestore](https://cloud.google.com/firestore/pricing) | Reminders, devices, sign-in tokens, series | 50,000 reads, 20,000 writes, 20,000 deletes **per day**; 1 GiB stored. `(default)` database only | Reads ~$0.03-0.06 per 100,000 by location |
| [Cloud Run functions](https://cloud.google.com/run/pricing) (2nd gen) | `api`, `canvasSync` | 2M requests, 180,000 vCPU-s, 360,000 GiB-s, 1 GiB egress (North America) | $0.40 per million requests; egress ~$0.12/GB |
| [Cloud Scheduler](https://cloud.google.com/scheduler/pricing) | 1 job (`canvasSync`) | 3 jobs per billing account | $0.10 per job/month |
| [Secret Manager](https://cloud.google.com/secret-manager/pricing) | 6 secrets | 6 active versions, 10,000 accesses per billing account | $0.06 per extra active version/month |
| [Artifact Registry](https://cloud.google.com/artifact-registry/pricing) | Function container images | 0.5 GB | $0.10/GB/month |
| Cloud Build, Cloud Storage | Deploy build and upload | 2,500 build-minutes; 5 GB in US | Not reached by occasional deploys |
| Firebase Cloud Messaging | Phone sync signals | Free | Free |

### Usage sources

Every sync reads the whole `events` collection: one read per reminder, incl. done ones and tombstones. With **N**
reminders:

| Source | Firestore reads | Egress |
|---|---|---|
| Scheduled run every 30 min (Canvas, series top-up) | ~4 x N + 1 per Canvas item; 48 runs/day | None |
| Phone sync (each change signal, every 6 h, on open) | N per sync; a few dozen/day | ~1.6 KB per reminder per sync |
| Windows app while running | N per minute (60 s poll) | ~1.6 KB per reminder per minute |
| AI client calls | N per `list_reminders`; a few per write | Small |

Example: **N = 360, Canvas linked.**

| Scenario | Reads/day | Egress/month | Cost/month |
|---|---|---|---|
| Phone only | ~85,000 (35,000 over free) | < 1 GB | **~$0.30-0.60** |
| Phone + Windows app 8 h/day | ~260,000 | ~8.5 GB | **~$2.80-4.70** |
| Phone + Windows app 24 h | ~600,000 | ~25 GB | **~$8-13** |
| No Canvas, < 150 reminders, Windows app rarely open | < 50,000 | < 1 GB | **$0** |

Everything else (function CPU and requests, Scheduler, Secret Manager, FCM) stays free at this size. A $0 bill despite
the table usually means free-trial credits on the billing account.

### Staying near $0

1. **Close the Windows app when unused**, or turn off Tools > Options > keep running in the notification area. Largest
   cost by far.
2. **Fewer reminders.** Done reminders and tombstones are read every sync; deleting old archived ones lowers every
   number.
3. **Canvas** is ~3 of the 4 full reads per scheduled run. Without it (placeholder secrets) the Canvas step fails, the
   scheduler retries up to twice, each attempt still tops up series: N to 3 x N reads per run instead of 4 x N.
4. **Secret versions:** each `functions:secrets:set` adds a version; old ones stay active (billable past six). Prune:
   `npx firebase functions:secrets:prune`.
5. **Container images:** when the CLI asks on deploy, keep images briefly (~1 day), or set a cleanup policy on
   `gcf-artifacts` in Artifact Registry. Otherwise they exceed the free 0.5 GB.
6. **Function limits:** `api` scales to zero (`minInstances: 0`), max 10 instances; `canvasSync` runs one at a time.
   Idle = free.
7. **Budget alert:** Google Cloud console > Billing > Budgets & alerts > Create budget, e.g. $5/month with alerts at
   50 %, 90 %, 100 %. Alerts do not stop charges.

### Scale limit

Suits hundreds to a few thousand reminders. Cost grows with reminders x syncs; a much larger calendar needs
incremental sync (changes since the device's last revision).
