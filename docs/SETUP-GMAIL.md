# Gmail

> AI-generated.

The bridge's mail tools (`outlook_*`) use Microsoft Graph: Outlook.com, Hotmail and Live only. No Gmail code in the
bridge. Two ways to use Gmail without code changes:

## Option A: your AI client's Gmail connector (recommended)

The AI client reads the mail and publishes reminders; the bridge never reads mail.

1. Connect Gmail in your AI client. Claude: **Settings > Connectors > Gmail**, sign in with Google, allow access.
2. Connect AI Calendar too ([SETUP-FIREBASE.md](SETUP-FIREBASE.md#connect-the-apps-and-an-ai-client)).
3. Ask, once or as a scheduled task, e.g.: "Read my Gmail from the last day, and publish appointments, deadlines,
   bills, deliveries and birthdays to AI Calendar with `publish_task_reminders`, task key `gmail-planner`." The
   bridge's `instructions` tell the client how to file each kind (`domain`, alerts, stable item keys).

Notes:

- Google's connector sets what the assistant can do in Gmail. AI Calendar's move-only, no-delete rules cover only its
  own Outlook tools.
- One stable task key per job, so later runs update instead of duplicating.
- Mail is data. The assistant should not follow instructions in messages; say so in scheduled task prompts too.

## Option B: forward Gmail to a connected Outlook.com mailbox

With the Outlook tools set up ([SETUP-AZURE.md](SETUP-AZURE.md)), forward Gmail there and use those tools, including
filing and move-only rules.

1. Gmail: **Settings (gear) > See all settings > Forwarding and POP/IMAP > Add a forwarding address**; enter the
   connected Outlook.com address.
2. Confirm via the mail in Outlook.com (or copy the code into Gmail).
3. Gmail: **Forward a copy of incoming mail to** that address, **keep Gmail's copy in the Inbox**. Save.
4. To forward only some mail, skip step 3 and use a filter (**Search options > Create filter > Forward it to**).

Forwarded mail is relayed by Gmail. The original sender stays in headers and body (`outlook_get_message` shows them);
list views usually show the original sender.

## Not built: native Gmail tools

`gmail_*` tools beside `outlook_*` are possible but not done. Requirements:

- **Google Cloud OAuth client** (Web application) in the Firebase project's Cloud project, **Gmail API** enabled,
  redirect URI `<base>/gmail/callback`, client id and secret as two new Firebase secrets.
- **Scopes:** `gmail.readonly` (read), `gmail.modify` (label, archive = move), `gmail.send` (reply). `gmail.modify`
  and `gmail.readonly` are restricted.
- **Consent screen:** for personal use leave it in **Testing** with yourself as test user. Refresh tokens then expire
  after **7 days** (weekly reconnect). Publishing needs Google verification and a security assessment for restricted
  scopes. Main reason Option A is recommended.
- **Same safety rules as Outlook:** no delete tools, no forwarding filters, mail as data, pairing code on the connect
  page, tokens only in Firestore.
- **Code:** `GmailService` beside `bridge/src/outlook.ts`, tools in `bridge/src/mcp.ts` (new names only), tests in
  `bridge/test/` with a fake fetch like `outlook.test.ts`.
