# Linking Canvas

Optional. With Canvas linked, the School tab becomes your coursework feed: assignments, quizzes,
discussions and peer reviews from every active course, with due dates kept current, course colors,
points, submission types and the assignment text. Work you submit is archived on its own.

The bridge reads Canvas with your personal access token. Nothing is written back to Canvas.

## 1. Find your Canvas address

It is the address you sign in at, for example `https://<school>.instructure.com` or your
school's own domain (`https://canvas.<school>.edu`). Use the origin only: `https://` and the host,
no path. It must be HTTPS.

## 2. Create an access token

1. In Canvas, open **Account > Settings**.
2. Under **Approved Integrations**, choose **+ New Access Token**.
3. Purpose: `AI Calendar`. Expiry: leave blank for no expiry, or pick a date and put a reminder in
   your calendar to renew it.
4. Copy the token right away; Canvas shows it once.

The token acts as you, with your access, in every course. Treat it like a password: never commit
it, paste it into chat, or store it anywhere but the Firebase secret. Some schools disable
personal tokens; if the button is missing, ask your Canvas administrator.

## 3. Store it in Firebase

Put each value in a file (one line, no trailing spaces), then:

```
cd bridge
npx firebase functions:secrets:set CANVAS_BASE_URL --data-file canvas-url.txt
npx firebase functions:secrets:set CANVAS_ACCESS_TOKEN --data-file canvas-token.txt
cd ..
node bridge/node_modules/firebase-tools/lib/bin/firebase.js deploy --only functions
```

Delete both files afterwards. Both functions need the new values: `api` (for Sync Canvas from
the apps and the `sync_canvas_assignments` MCP tool) and `canvasSync` (the 30-minute schedule).
Secrets are read when an instance starts, so redeploy after changing one.

## 4. Check it

- On the phone: **Tools > Cloud Connection > Sync Canvas**. The School tab fills within a
  minute, on the phone and, at its next sync, in the Windows app (which cannot start a Canvas
  sync itself).
- From an AI client or the command line: `sync_canvas_assignments` returns the counts (`fetched`,
  `active`, `created`, `updated`, `archived`).

```
powershell -File tools/mcp-call.ps1 sync_canvas_assignments
```

If it fails: `Canvas returned HTTP 401` means the token is wrong or expired;
`Canvas base URL must use HTTPS` means the address is off; nothing at all usually means the
address has a path after the host.

## What the sync does

Every 30 minutes (`canvasSync`), and whenever you ask:

| Step | Detail |
|---|---|
| Window | Planner items due from 7 days ago to 6 months ahead (`/api/v1/planner/items`), plus your active courses (`/api/v1/courses`) for names and codes |
| What is imported | Assignments, quizzes, discussions, sub-assignments and peer reviews. Class meetings, announcements and calendar events are left out |
| Identity | Each item is a reminder under task key `canvas` with a stable item key, so a changed due date updates it instead of adding another |
| Alerts | Quizzes: 3 days, 1 day, 2 hours, 1 hour and at the deadline. Exams (titles with exam, test, midterm, final): 1 week, 1 day, 2 hours, 1 hour and at the deadline. Both are critical (exact alarms on the phone). Everything else: 1 day before. The night rule still applies, so an 11:59 PM deadline alerts at 8 PM |
| Details | Course, points, submission types, attempts, open and lock dates, and the assignment text as plain text. **Open in Canvas** links to the item |
| Completion | Items Canvas lists as complete (submitted, or marked done in the planner) are marked done and move to Archived |
| Deleting | Delete a Canvas reminder in the apps and it stays deleted on later syncs |

The School tab's **Class** filter lists one entry per course, each with a stable color shared by
the phone and the PC.

## Notes

- Canvas reminders are stored with the time zone `America/Denver` as their label. The due instant
  is exact whatever the zone; change the `timeZone` value in `toReminder` in
  `bridge/src/canvas.ts` if you want your own zone shown to AI clients.
- AI clients should not publish under the task key `canvas`; it belongs to this sync.
- To unlink Canvas, set both secrets to placeholders and redeploy, or revoke the token in Canvas.
  Imported reminders stay until you delete or archive them.
