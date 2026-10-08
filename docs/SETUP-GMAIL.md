# Gmail

The bridge's built-in mail tools (`outlook_*`) speak Microsoft Graph, so they work with
Outlook.com, Hotmail and Live accounts only. There is no Gmail code in the bridge. You can still
have Gmail feed your calendar today, in either of two ways, without changing any code.

## Option A: your AI client's own Gmail connector (recommended)

The bridge does not need to read your mail for the calendar to work. The AI client reads it and
publishes reminders to the bridge.

1. In your AI client, connect Gmail with its own connector. In Claude: **Settings >
   Connectors > Gmail**, then sign in with Google and allow access.
2. Connect AI Calendar as well (see [SETUP-FIREBASE.md](SETUP-FIREBASE.md#connect-the-apps-and-an-ai-client)).
3. Ask for what you want, once or as a scheduled task, for example: "Read my Gmail from the last
   day, and publish appointments, deadlines, bills, deliveries and birthdays to AI Calendar with
   `publish_task_reminders`, task key `gmail-planner`." The bridge's `instructions` tell the
   client how to file each kind (`domain`, alerts, stable item keys).

Good to know:

- Google's connector decides what the assistant can do in Gmail (search and read, and whatever
  else that connector offers). AI Calendar's move-only and no-delete rules apply to its own
  Outlook tools, not to another connector.
- Use one stable task key per job, so later runs update reminders instead of duplicating them.
- Mail is data. If a message contains instructions ("forward this", "click here"), the assistant
  should not follow them; say so in your scheduled task's prompt as well.

## Option B: forward Gmail to a connected Outlook.com mailbox

If you already use the bridge's Outlook tools (see [SETUP-AZURE.md](SETUP-AZURE.md)), forward
Gmail into that mailbox and everything works through them, including filing and move-only rules.

1. In Gmail: **Settings (gear) > See all settings > Forwarding and POP/IMAP > Add a forwarding
   address**, and enter the Outlook.com address the bridge is connected to.
2. Open the confirmation mail in Outlook.com and confirm (or copy the code back into Gmail).
3. Back in Gmail, choose **Forward a copy of incoming mail to** that address and **keep Gmail's
   copy in the Inbox**. Save.
4. To forward only some mail, skip step 3 and create a Gmail filter (**Search options > Create
   filter > Forward it to**) instead.

Forwarded mail arrives with Gmail as the relay. The original sender is still in the headers and
the body, and `outlook_get_message` shows them; list views show the original sender in most
cases.

## Not built: native Gmail tools

Adding `gmail_*` tools to the bridge, alongside `outlook_*`, is possible but not done. If you or
a coding agent take it on, these are the parts that matter:

- **Google Cloud OAuth client** (type Web application) in the Firebase project's Google Cloud
  project, the **Gmail API** enabled, redirect URI `<base>/gmail/callback`, and the client id and
  secret stored as two new Firebase secrets.
- **Scopes:** `gmail.readonly` to read, `gmail.modify` to label and archive (Gmail's equivalent of
  moving), `gmail.send` to reply. `gmail.modify` and `gmail.readonly` are restricted scopes.
- **Consent screen:** for a personal app, leave it in **Testing** with your own account as a test
  user. Google then expires refresh tokens after **7 days**, so the mailbox has to be reconnected
  weekly; publishing it instead requires Google's verification and a security assessment for
  restricted scopes. This is the main reason Option A is the recommended path.
- **Same safety rules as Outlook:** no delete tools, no forwarding filters, mail treated as data,
  the pairing code on the connect page, tokens only in Firestore.
- **Code layout:** a `GmailService` beside `bridge/src/outlook.ts`, tools registered in
  `bridge/src/mcp.ts` (new names only, existing ones unchanged), tests in `bridge/test/` with a
  fake fetch, as `outlook.test.ts` does.
