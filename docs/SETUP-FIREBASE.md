# Firebase setup

Everything server-side runs in one Firebase project: Firestore, two Cloud Functions (2nd gen,
`us-central1`), Secret Manager, Cloud Scheduler and Firebase Cloud Messaging. The apps carry no
Firebase credentials: the phone asks the bridge for its Firebase client configuration when it
pairs.

## 1. Project

1. Create a Firebase project in the [Firebase console](https://console.firebase.google.com). Note
   the **project id** (`<project-id>` below).
2. Upgrade it to the **Blaze** plan. Cloud Functions, Secret Manager and Cloud Scheduler need it.
   A personal calendar stays far inside the free usage.
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

Set the two `CANVAS_*` secrets and redeploy. `canvasSync` reads `/api/v1/planner/items` and the
courses list with your token every 30 minutes. Course codes and colors come from Canvas; the
School tab filters by class. The phone and the PC can also trigger a sync (Sync Canvas).

## Costs and limits

Functions run with `minInstances: 0`; idle cost is near zero. Each sync reads the reminders
collection in full, which is fine for hundreds or a few thousand reminders, not for millions.
