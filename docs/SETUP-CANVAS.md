# Linking Canvas

> AI-generated.

Optional. Fills the School tab with assignments, quizzes, discussions and peer reviews from every active course: due
dates kept current, course colors, points, submission types, assignment text. Submitted work is archived
automatically. Read-only: nothing is written to Canvas.

## 1. Canvas address

The address you sign in at, e.g. `https://<school>.instructure.com` or `https://canvas.<school>.edu`. Origin only
(`https://` + host, no path). HTTPS required.

## 2. Access token

1. Canvas: **Account > Settings**.
2. **Approved Integrations > + New Access Token**.
3. Purpose `AI Calendar`. Expiry: blank (none), or a date with a renewal reminder.
4. Copy it now; Canvas shows it once.

The token acts as you in every course. Treat it as a password: never commit it, paste it in chat, or store it outside
the Firebase secret. If the button is missing, your school disabled personal tokens; ask the Canvas admin.

## 3. Store in Firebase

One value per file (one line, no trailing spaces):

```
cd bridge
npx firebase functions:secrets:set CANVAS_BASE_URL --data-file canvas-url.txt
npx firebase functions:secrets:set CANVAS_ACCESS_TOKEN --data-file canvas-token.txt
cd ..
node bridge/node_modules/firebase-tools/lib/bin/firebase.js deploy --only functions
```

Delete both files. Both functions need the values: `api` (Sync Canvas in the apps, `sync_canvas_assignments`) and
`canvasSync` (30-minute schedule). Secrets load at instance start: redeploy after changing one.

## 4. Check

- Phone: **Tools > Cloud Connection > Sync Canvas**. School tab fills within a minute; the Windows app follows at its
  next sync (it cannot start a Canvas sync).
- AI client or command line: `sync_canvas_assignments` returns `fetched`, `active`, `created`, `updated`, `archived`.

```
powershell -File tools/mcp-call.ps1 sync_canvas_assignments
```

Errors: `Canvas returned HTTP 401` = token wrong or expired; `Canvas base URL must use HTTPS` = bad address; no result
at all = usually a path after the host.

## What the sync does

Every 30 minutes (`canvasSync`) and on request:

| Step | Detail |
|---|---|
| Window | Planner items due from 7 days ago to 6 months ahead (`/api/v1/planner/items`), plus active courses (`/api/v1/courses`) for names and codes |
| Imported | Assignments, quizzes, discussions, sub-assignments, peer reviews. Not class meetings, announcements, calendar events |
| Identity | Task key `canvas` with a stable item key per item; due date changes update in place |
| Alerts | Quizzes: 3 days, 1 day, 2 h, 1 h, at deadline. Exams (title has exam, test, midterm, final): 1 week, 1 day, 2 h, 1 h, at deadline. Both critical (exact alarms). Others: 1 day before. Night rule applies (11:59 PM deadline alerts at 8 PM) |
| Details | Course, points, submission types, attempts, open/lock dates, assignment text (plain). **Open in Canvas** links to it |
| Completion | Items Canvas marks complete (submitted or done in planner) are marked done and archived |
| Deleting | A Canvas reminder deleted in the apps stays deleted |

The School tab's **Class** filter has one entry per course, with a stable color on phone and PC.

## Notes

- Canvas reminders are labelled with time zone `America/Denver`. The due instant is exact regardless; change
  `timeZone` in `toReminder` in `bridge/src/canvas.ts` to show your own zone to AI clients.
- AI clients must not publish under task key `canvas`.
- Unlink: set both secrets to placeholders and redeploy, or revoke the token in Canvas. Imported reminders stay until
  deleted or archived.
