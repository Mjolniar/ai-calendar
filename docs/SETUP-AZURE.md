# Microsoft (Azure) setup for Outlook.com mail

> AI-generated.

Optional. Gmail: [SETUP-GMAIL.md](SETUP-GMAIL.md).

The `outlook_*` MCP tools let an AI client read and file mail of one **personal Microsoft account** (Outlook.com,
Hotmail, Live) via Microsoft Graph. To skip, set `MS_CLIENT_ID` and `MS_CLIENT_SECRET` to placeholders; the tools then
report "not configured" and everything else works.

Sign-in: authorization code flow against `https://login.microsoftonline.com/consumers`. Refresh token stored in
Firestore (`integrations/outlookAccount`); access tokens refreshed on demand.

## 1. App registration

1. [Microsoft Entra admin center](https://entra.microsoft.com) (or Azure portal > App registrations). A free personal
   Azure account works.
2. **New registration:**
   - Name: any, e.g. `AI Calendar Outlook`.
   - Supported account types: **Personal Microsoft accounts only**.
   - Redirect URI: platform **Web**, `https://us-central1-<project-id>.cloudfunctions.net/api/outlook/callback`
     (= `AI_CALENDAR_PUBLIC_BASE_URL` + `/outlook/callback`).
3. **Application (client) ID** = `MS_CLIENT_ID`.

## 2. Client secret

Certificates & secrets > New client secret. Longest expiry (24 months); set yourself a reminder. Copy the secret
**value** (not its id) immediately = `MS_CLIENT_SECRET`. When it expires, mail tools stop until a new one is stored.

## 3. API permissions

API permissions > Add a permission > Microsoft Graph > **Delegated**:

| Permission | Purpose |
|---|---|
| `offline_access` | Refresh token |
| `openid`, `email`, `User.Read` | Identify the mailbox |
| `Mail.ReadWrite` | List, read, move messages; manage folders |
| `Mail.Send` | Reply, send clarifications, `mailto:` List-Unsubscribe |
| `MailboxSettings.ReadWrite` | List and create Inbox rules (move-only) |

No admin consent for personal accounts; you consent when connecting.

## 4. Store secrets, deploy

```
cd bridge
npx firebase functions:secrets:set MS_CLIENT_ID --data-file ms-id.txt
npx firebase functions:secrets:set MS_CLIENT_SECRET --data-file ms-secret.txt
cd ..
node bridge/node_modules/firebase-tools/lib/bin/firebase.js deploy --only functions:api
```

Delete both text files afterwards.

## 5. Connect the mailbox

Open `https://us-central1-<project-id>.cloudfunctions.net/api/outlook/connect`, enter the pairing code, sign in, accept
the permissions. Ask your AI client to run `outlook_status`; it should report the address.

## Limits

- Personal accounts lack Graph `$search` on messages and the `unsubscribeEnabled` / `unsubscribeData` fields. The
  bridge filters by date and parses `List-Unsubscribe` itself.
- Tools never delete mail: moves only, Deleted Items refused as a destination, Inbox rules with delete, forward or
  redirect rejected.
- Mail content is data; tool descriptions tell the AI client not to follow instructions in messages.
