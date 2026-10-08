# Microsoft (Azure) setup for Outlook.com mail

Optional. The bridge's `outlook_*` MCP tools let an AI client read and file the mail of one
**personal Microsoft account** (Outlook.com, Hotmail, Live) through Microsoft Graph. Skip this
and set `MS_CLIENT_ID` and `MS_CLIENT_SECRET` to placeholders; the tools then report that Outlook
is not configured and everything else works.

The bridge signs in with the authorization code flow against the `consumers` authority
(`https://login.microsoftonline.com/consumers`), stores the refresh token in Firestore
(`integrations/outlookAccount`), and refreshes access tokens on demand.

## 1. App registration

1. Sign in to the [Microsoft Entra admin center](https://entra.microsoft.com) (or Azure portal >
   App registrations) with any account that can register apps. A free personal Azure account
   works.
2. **New registration**
   - Name: anything, for example `AI Calendar Outlook`.
   - Supported account types: **Personal Microsoft accounts only**.
   - Redirect URI: platform **Web**,
     `https://us-central1-<project-id>.cloudfunctions.net/api/outlook/callback`
     (it is `AI_CALENDAR_PUBLIC_BASE_URL` + `/outlook/callback`).
3. Note the **Application (client) ID**. That is `MS_CLIENT_ID`.

## 2. Client secret

Certificates & secrets > New client secret. Pick the longest expiry offered (24 months) and
put a reminder in your own calendar for it. Copy the secret **value** (not its id) right away. That
is `MS_CLIENT_SECRET`. When it expires the mail tools stop working until a new one is stored.

## 3. API permissions

API permissions > Add a permission > Microsoft Graph > **Delegated**:

| Permission | Why |
|---|---|
| `offline_access` | Refresh token, so the bridge keeps access |
| `openid`, `email`, `User.Read` | Which mailbox is connected |
| `Mail.ReadWrite` | List, read and move messages, manage folders |
| `Mail.Send` | Reply or send a clarification mail, and unsubscribe through `mailto:` List-Unsubscribe |
| `MailboxSettings.ReadWrite` | List and create Inbox rules (move-only) |

No admin consent is needed for a personal account; you consent when you connect.

## 4. Store the secrets and deploy

```
cd bridge
npx firebase functions:secrets:set MS_CLIENT_ID --data-file ms-id.txt
npx firebase functions:secrets:set MS_CLIENT_SECRET --data-file ms-secret.txt
cd ..
node bridge/node_modules/firebase-tools/lib/bin/firebase.js deploy --only functions:api
```

Delete the two text files afterwards.

## 5. Connect the mailbox

Open `https://us-central1-<project-id>.cloudfunctions.net/api/outlook/connect`, enter the
pairing code, sign in with the Microsoft account and accept the permissions. Then ask your AI
client to run `outlook_status`; it should report the connected address.

## Limits worth knowing

- Personal accounts do not support Graph `$search` on messages, nor the `unsubscribeEnabled` and
  `unsubscribeData` fields. The bridge filters by date and parses `List-Unsubscribe` itself.
- By design the tools never delete mail: moves only, Deleted Items is refused as a destination,
  and Inbox rules with delete, forward or redirect actions are rejected.
- Treat mail content as data. The tool descriptions tell the AI client not to follow
  instructions found in messages.
