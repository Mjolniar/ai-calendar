# AI Planner Desktop

Windows companion for the Android app. It shows the same cloud
reminders in the same Windows XP look and pops up reminders while it runs. The
desktop device token is kept encrypted with the Windows Data Protection API.

## Features

- Life, School, Deliveries, All, and Archived tabs. Reminders are grouped by
  Today, Tomorrow, This week, Next week, and month. Weeks run Monday to Sunday,
  in these groups and in the month view.
- Deliveries (packages, orders, shipments) never alert: no balloon pops up for
  one, whatever lead times it carries. A delivery stays on its tab through the
  day it is expected, not just until its time, and is filed under Archived when
  that day ends or it is marked done. The bridge decides what is a delivery
  (`domain: "delivery"`); the wording of a reminder never does.
- A reminder moves to Archived when its time passes. A birthday (a title with
  the word "birthday") stays listed through its whole day and is filed the day
  after, as a delivery is; its alerts are unchanged.
- Archived reads as history: the latest first, under Today, Yesterday, Earlier
  this week, Last week, Earlier this month, and then each month. Reminders
  finished before their date are kept at the end under Not due yet, soonest
  first, and join the history when their day comes.
- List view, or a month view with the reminders of the selected day (View menu
  or toolbar). Quizzes, tests, and exams are tagged and marked on the month.
- Find (Ctrl+F) by title, place, notes, or class, across every date in the tab.
- Click a reminder to open its details: full date, class, points, alerts, the
  assignment text, and Done, Open in Canvas, and Delete. Done can be undone
  from the bar that confirms it.
- Repeating reminders show a repeat icon, and their details say the pattern in
  words ("Every week on Friday at 8:00 AM"), worded as on the phone. Each
  occurrence is its own reminder. Delete asks whether it is for this one or for
  this and all future ones. Repeating reminders are made on the phone or by an
  AI client, as other reminders are.
- Class filter on the School tab; each class has the color it has on the phone.
- Reminder balloons above the notification area, with Open and Mark done. They
  stay until closed and appear at the same moments as the phone's alerts: an
  advance notice that would land between 9 PM and 8 AM comes at 8 PM or 8 AM
  instead, and a deadline at 11:59 PM or midnight gets one "Tonight" alert at
  8 PM in place of its late last-minute alerts. The rule is
  `ReminderSchedule.cs`, a copy of the phone's `ReminderSchedule.kt`.
- Kitchen tab (since 1.4.0): the household's shopping list, what is on hand
  and the week's meal plan, shared with the phones and with Claude's Monday grocery
  order. See the Kitchen section below.
- Tools > Options: dark mode, and keeping the app in the notification area
  when the window is closed (reminders only pop up while it is running).
- Sync every 60 seconds, or F5. Offline, the last synced copy is shown.

## Kitchen

The Kitchen tab shows the cloud calendar's kitchen (`GET /v1/kitchen`, bridge
0.9.0 or later) in three parts, chosen on the bar under the tabs:

- This week: tonight's dinner first, then the rest of the week and next week
  if it is published. Opening a day shows its recipe (ingredients and
  numbered steps; leftovers nights have none), the day's note, and how it
  went: Cooked or Skipped, Loved it, It was OK or Not again (choosing the
  chosen one again clears it), and a note saved with Save note.
- Shopping list: a box with Add, then what is to buy and what is on order
  (with its order), each with a box to tick when bought and an x to remove it
  (Undo in the bar that confirms it), then "Got it" with the last 7 days, where
  unticking puts an item back. Claude adds everything on the list to Monday's
  Walmart order.
- On hand (since 1.6.0; "Pantry" before): what Claude thinks is in the house,
  corrected by people rather than kept by hand. The header says when Claude
  last checked ("Updated Mon 10/26"). The Tell Claude box sends Claude a note
  ("Out of eggs, have 2 cans of beans, tossed the cilantro"); it waits under
  the box as "Waiting for Claude", can be withdrawn (with Undo) until Claude
  answers, and then shows "Claude: <reply>". The newest three corrections show,
  with Show all. Below, Claude's estimate by section, each item with a chip in
  plain words (Have, Running low, Probably out, in green, amber and red) and
  why Claude thinks so in small grey text; running low and probably out come
  first in each section, and "Running low & out only" hides the rest. Clicking
  an item shows We're out (applied at once: it goes on the shopping list and
  the bar shows Claude's reply), We have it, and Something else..., which
  starts a note about that item. There is no Have/Low/Out choice, add form or
  remove button any more: Claude adds and removes items from people's notes.
  An empty pantry says "Claude fills this in after the next grocery order."

All kitchen rules live on the server. Every change is sent at once; the answer
is shown and the kitchen fetched again. A change the server does not take
leaves the view as it was and says why in a message box. There is no push on
the PC, so the tab is fetched when it is shown, once when the window is
activated, shown or restored, with Refresh or F5, and while it is on screen
every 20 seconds when the window is active and every 60 seconds otherwise.
Nothing is fetched while another tab shows. Without a pairing or a connection it says
"Can't reach the cloud calendar. Kitchen needs a connection." Nothing about
the kitchen is stored on the PC except which tab and part were open
(`view.json`); reminder sync, alerts and the reminder caches are unchanged.

## Build and install

Run from PowerShell:

```powershell
.\install.ps1
```

The self-contained executable is installed under
`%LOCALAPPDATA%\Programs\AI Calendar Desktop` and an `AI Planner` shortcut is
created on the current user's desktop. An `AI Calendar` shortcut left there by
an earlier install is removed, and one in the Start Menu is replaced by an
`AI Planner` shortcut in the same place. Icon files left in that folder by
earlier installs are replaced with the app's icon, so a taskbar pin or shortcut
that points at one shows the current artwork. Private settings and the event cache are
stored in `%USERPROFILE%\.ai-calendar-desktop`, which prevents Windows package
redirection from creating separate pairings for different launchers. On first
launch, enter the public bridge URL and existing pairing code. The
pairing code is never stored. Tools > Cloud Connection shows the connection and
pairs again.

### The name

Since 1.5.1 the app is shown as "AI Planner": the window title, the About
window, the notification-area icon, balloons, message boxes, shortcuts, and
the exe's product name and description. On purpose, everything else keeps the
old name: the exe `AI Calendar.exe` (and so the process name `install.ps1` and
the build scripts look for), the install folder
`%LOCALAPPDATA%\Programs\AI Calendar Desktop`, the settings folder
`%USERPROFILE%\.ai-calendar-desktop`, and the project and assembly names.
Renaming them would break the taskbar pin, lose the pairing and stop the
install script from finding the running app.

## Code

- `Xp/` is the theme. `Luna.xaml` and `Noir.xaml` are the light and dark
  palettes; every color the controls use is a resource in them. `XpTheme`
  installs the theme into the application and swaps one palette for the other. `XpControls.xaml` styles the
  controls, `XpWindow` draws the title bar and frame, `XpBalloon` and
  `XpMessageBox` are the popups. All artwork is original; no Microsoft assets
  are used.
- `Xp/XpIcons.xaml` is generated by `tools/xp-icons/generate.py` from the same
  drawings as the phone's icons. Do not edit it by hand.
- `ReminderText.cs` (grouping, wording, search) and `ReminderSchedule.cs`
  mirror `PlannerFormat.kt` and `ReminderSchedule.kt` in the phone app.
- Kitchen: `Kitchen.cs` (records, the tolerant reader of the kitchen answers,
  and the display rules: tonight from the local date, the list's parts,
  the On hand order and filter, the corrections shown, `**bold**`),
  `KitchenRows.cs` (row view models), `KitchenView.xaml(.cs)` (the tab),
  `MainWindow.Kitchen.cs` (when it is fetched). The calls are in
  `BridgeClient.cs`; corrections use `POST /v1/kitchen/corrections` and
  `DELETE /v1/kitchen/corrections/:id` (bridge 0.10.0), and the old pantry
  routes are no longer called.

## Tests

```powershell
dotnet test tests
```

The tests cover the reminder timing and wording and the kitchen's reader and
rules, drive the main window and its Kitchen tab against a stand-in for the
calendar service (no network, no tray icon), and draw every window in both
palettes into
`tests\artifacts` for a look after a theme change. Windows are shown off screen
and never take the focus. The run also draws `tests\artifacts\AppIcon.ico` from
the icon artwork; copy it over `AppIcon.ico` after changing that artwork.
