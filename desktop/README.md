# AI Planner Desktop

> AI-generated.

Windows companion to the Android app. Same cloud reminders, same Windows XP look, reminder popups while running.
Device token encrypted with the Windows Data Protection API.

## Features

- Tabs: Life, School, Deliveries, All, Archived. Groups: Today, Tomorrow, This week, Next week, then months. Weeks run
  Monday to Sunday (groups and month view).
- Deliveries never alert, whatever lead times they carry. Listed through their expected day, then archived (or when
  marked done). The bridge decides what is a delivery (`domain: "delivery"`), never the wording.
- Reminders move to Archived when their time passes. Birthdays (title contains "birthday") stay listed all day and are
  archived the day after, like deliveries; alerts unchanged.
- Archived is history: newest first under Today, Yesterday, Earlier this week, Last week, Earlier this month, then
  months. Items finished before their date go last under Not due yet, soonest first, and join history on their day.
- List view or month view with the selected day's reminders (View menu or toolbar). Quizzes, tests, exams tagged and
  marked on the month.
- Find (Ctrl+F): title, place, notes, class, all dates in the tab.
- Click a reminder for details: full date, class, points, alerts, assignment text; Done, Open in Canvas, Delete. Done
  can be undone from its confirmation bar.
- Repeating reminders: repeat icon; details give the pattern in words ("Every week on Friday at 8:00 AM"), as on the
  phone. Each occurrence is its own reminder. Delete asks: this one, or this and all future ones. Created on the phone
  or by an AI client.
- School tab class filter; each class has its phone color.
- Balloons above the notification area with Open and Mark done. They stay until closed and fire with the phone's
  alerts: advance notices between 9 PM and 8 AM come at 8 PM or 8 AM; an 11:59 PM or midnight deadline gets one
  "Tonight" alert at 8 PM instead of late alerts. Rule: `ReminderSchedule.cs`, a copy of the phone's
  `ReminderSchedule.kt`.
- Kitchen tab (1.4.0+): shopping list, on hand and the week's meal plan, shared with the phones and Claude's Monday
  grocery order. See Kitchen below.
- Tools > Options: dark mode; keep running in the notification area when the window closes (reminders pop up only
  while running).
- Sync every 60 s, or F5. Offline: last synced copy.

## Kitchen

Shows the cloud kitchen (`GET /v1/kitchen`, bridge 0.9.0+) in three parts, chosen on the bar under the tabs:

- This week: tonight's dinner first, then the rest of the week and next week if published. A day shows its recipe
  (ingredients, numbered steps; none on leftovers nights), the day's note, and the outcome: Cooked or Skipped, Loved
  it / It was OK / Not again (re-choosing clears), and a note saved with Save note. Unsaved note text stays with its
  day (1.7.0+) when another day opens. Left open overnight, the tab moves to the new day: tonight, the week, "Got
  today" and the 7 days of "Got it".
- Shopping list: Add box; items to buy and on order (with order), each with a tick box and an x to remove (Undo in the
  confirmation bar); then "Got it" for the last 7 days, where unticking restores an item. Undo restores a line as it
  was: to buy, on order with its order, or bought (bridge 0.11.0; 0.10.0 restores as to buy). Claude adds the whole
  list to Monday's Walmart order.
- On hand (1.6.0+; "Pantry" before): Claude's estimate of what is in the house, corrected by people, not maintained by
  hand. Header shows Claude's last check ("Updated Mon 10/26"). The Tell Claude box sends a note ("Out of eggs, have 2
  cans of beans, tossed the cilantro"); it shows as "Waiting for Claude", can be withdrawn (with Undo) until answered,
  then shows "Claude: <reply>". Newest three corrections shown, with Show all. Below: the estimate by section, each
  item with a chip (Have, Running low, Probably out in green, amber, red) and Claude's reason in small grey text; low
  and out first; "Running low & out only" hides the rest. Clicking an item offers We're out (applied at once: added to
  the shopping list, reply shown in the bar), We have it (with bridge 0.11.0 also removes the pantry's list line), and
  Something else... (starts a note about the item). Each correction has its own id, so a resend after no answer is
  stored once. No Have/Low/Out choice, add form or remove button: Claude adds and removes items from people's notes.
  Empty pantry: "Claude fills this in after the next grocery order."

All kitchen rules are on the server. Every change is sent at once; the answer is shown and the kitchen refetched. A
rejected change leaves the view unchanged and explains in a message box; a change about something already gone
(removed elsewhere, or answered by Claude) refetches and says so in the status bar. A fetch started before a change
never overwrites it. No push on the PC: the tab is fetched when shown, once on window activate/show/restore, with
Refresh or F5, and while on screen every 20 s (window active) or 60 s (inactive). Nothing fetched while another tab
shows. Without pairing or connection: "Can't reach the cloud calendar. Kitchen needs a connection." Only the open tab
and part are stored on the PC (`view.json`); reminder sync, alerts and caches are unaffected.

## Build and install

PowerShell:

```powershell
.\install.ps1
```

Installs the self-contained exe to `%LOCALAPPDATA%\Programs\AI Calendar Desktop` and creates an `AI Planner` desktop
shortcut. An old `AI Calendar` desktop shortcut to this app is removed; one in the Start Menu is replaced by `AI
Planner` in the same place; same-named shortcuts to other targets are left alone. Old icon files in the install folder
are replaced with the current icon, so pins and shortcuts show current artwork. Private settings and the event cache
are in `%USERPROFILE%\.ai-calendar-desktop` (avoids Windows package redirection creating separate pairings per
launcher). First launch: enter the public bridge URL and existing pairing code. The pairing code is never stored. The
address must be https (plain http only to this PC); a hand-edited `settings.json` with any other address gets no
request at all, so the device token never travels in clear. Tools > Cloud Connection shows the connection and re-pairs.

### Name

Since 1.5.1 the app shows as "AI Planner": window title, About, tray icon, balloons, message boxes, shortcuts, exe
product name and description. Deliberately unchanged: exe `AI Calendar.exe` (process name used by `install.ps1` and
build scripts), install folder `%LOCALAPPDATA%\Programs\AI Calendar Desktop`, settings folder
`%USERPROFILE%\.ai-calendar-desktop`, project and assembly names. Renaming them would break the taskbar pin, lose the
pairing and stop the install script finding the running app.

## Code

- `Xp/` = theme. `Luna.xaml`, `Noir.xaml`: light and dark palettes; every control color is a resource there. `XpTheme`
  installs the theme and swaps palettes. `XpControls.xaml` styles controls; `XpWindow` draws title bar and frame;
  `XpBalloon`, `XpMessageBox` are popups. All artwork original; no Microsoft assets.
- `Xp/XpIcons.xaml` is generated by `tools/xp-icons/generate.py` from the phone icon drawings. Do not edit by hand.
- `ReminderText.cs` (grouping, wording, search) and `ReminderSchedule.cs` mirror the phone's `PlannerFormat.kt` and
  `ReminderSchedule.kt`.
- Kitchen: `Kitchen.cs` (records, tolerant reader of kitchen answers, display rules: tonight from local date, list
  parts, On hand order and filter, shown corrections, `**bold**`), `KitchenRows.cs` (row view models),
  `KitchenView.xaml(.cs)` (the tab), `MainWindow.Kitchen.cs` (fetch timing). Calls in `BridgeClient.cs`; corrections
  use `POST /v1/kitchen/corrections` and `DELETE /v1/kitchen/corrections/:id` (bridge 0.10.0); old pantry routes are
  no longer called. Works with bridge 0.10.0 and 0.11.0; 0.11.0 additions (`today`, correction `clientId`,
  `removedListItemId`, Undo `restore`) are optional on both sides.

## Tests

```powershell
dotnet test tests
```

Covers reminder timing and wording, the kitchen reader and rules; drives the main window and Kitchen tab against a fake
calendar service (no network, no tray icon); draws every window in both palettes to `tests\artifacts` for review after
theme changes. Windows are off screen and never take focus. Also draws `tests\artifacts\AppIcon.ico` from the icon
artwork; copy it over `AppIcon.ico` after changing the artwork.
