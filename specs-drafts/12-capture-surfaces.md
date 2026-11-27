# Spec 12: Capture surfaces (draft)

Owner: Gareth (PM). Build: a Claude build agent. QA: Mark, and a Claude test agent (host). Status: **draft by Claude, 27 Nov 2026 (AWST), not yet approved.** Builds on Spec 9. Independent of Specs 10 and 11.
Sources: `docs/strategy/DO-PRODUCT-STRATEGY.md` (pillar 2; section 10: AppFunctions decision); `docs/design/DESIGN-PASS-1.md` (screens 13 widget and Quick Settings tile, 14 share sheet); Android developer documentation for App Widgets (Jetpack Glance), `TileService`, share targets (`ACTION_SEND`), App Shortcuts and AppFunctions (Jetpack `androidx.appfunctions`). The builder confirms current API details against those docs; anything not confirmed is marked assumed.

## 1. Goal
Let people get a reminder into Do in under two seconds from anywhere on the phone: sharing text from another app, a home-screen widget, a Quick Settings tile, a long-press app shortcut, a deep link, and (when Google opens it to other apps) Gemini through AppFunctions. Everything goes through Spec 9's `ReminderCommands`, with the right `source`. No network.

## 2. Starts from Spec 9
`ReminderCommands` with `Origin` and `ReminderSource` (`SHARE`, `WIDGET` exist; this spec adds nothing to the enum unless needed). Natural-language parsing (`DateParser`, Spec 5) turns text into a due time and repeat. The free cap of 5 applies to every surface.

## 3. Scope

### A. Share to Do
- An activity that accepts `ACTION_SEND` with `text/plain` (and `text/*`), shown as "Add to Do" in the share sheet.
- A bottom sheet over the sending app (design screen 14): "What needs doing?" prefilled with the shared text, trimmed to the first 200 characters and the first line; the parsed date shown with the words it came from ("from "Friday after work""); four quick choices (Tonight, Tomorrow, Weekend, Choose...); "Save and go back".
- No date found: due defaults to tomorrow 9 am (the user's default if Spec 14 adds one), shown before Save.
- Saves with `Origin(Actor.USER, ReminderSource.SHARE, sourceId = <calling package if Android provides it, else null>)`.
- Over the cap: the paywall, then back to the sending app.
- Works with the app not running; finishes and returns to the sender after Save.

### B. Home-screen widgets (Jetpack Glance, assumed the current recommended toolkit)
- **Large (4 x 3):** "Do", the count overdue, the next three active reminders with tick-to-done (Done through `ReminderCommands.markDone` with `Actor.USER`), and a "What needs doing?" row that opens Add.
- **Small (2 x 2):** the number overdue, or "All clear", and tap opens Add.
- Updated whenever reminders change (hook the Spec 9 command layer's commit point) and on boot and time change; never polls.
- Respects the system's dark theme. No personal data beyond titles, which the user already sees in notifications.

### C. Quick Settings tile
- A `TileService` "Do: Add a reminder" that opens Add (collapsing the shade). Label and icon only; no state.

### D. App shortcuts and deep link
- Static shortcuts on the launcher icon: "Add a reminder", "Today".
- Deep link `do://add?text=<urlencoded>` (and the same as an `https://` app link later, out of scope) opens Add prefilled. Used by automation apps and, later, assistants.

### E. AppFunctions (built now, switched on later)
- Expose functions through Jetpack AppFunctions: `createReminder(text, dueAt?, repeat?)`, `completeReminder(id)`, `listReminders(limit)`. Each is a thin call into `ReminderCommands` with `Origin(Actor.AGENT, ReminderSource.AGENT, sourceId = <caller>)`; agent reminders count towards the 5.
- Alert level from an agent is stored as `requestedAlertLevel`, and the reminder is created at Normal (`STANDARD`) until the user approves (`approval = PENDING`); approval UI comes with Spec 13 or 14.
- Built behind a build flag, `appFunctionsEnabled`, default on in debug and **off in release** until Google opens Gemini access to other apps (Tim applies for early access). Assumed: AppFunctions needs Android 16 or later; on older phones the functions simply aren't registered.

## 4. Out of scope
The cloud agent API (Spec 13). Approval screens for agents (Spec 13 or 14). Voice input. Lock-screen widgets. Wear OS. iPhone.

## 5. Acceptance tests
Host (unit, CI):
1. **Share text handling.** First line, 200-character trim, parse result and its source words; empty text refused.
2. **Every surface uses the commands** with the right `Origin` (unit over the small adapters).
3. **Cap.** A free user at 5 is sent to the paywall from Share, the widget's add row and AppFunctions (which returns a clear "limit reached" error to the caller instead).
4. **Agent level held.** `createReminder` with an Alarm request stores `requestedAlertLevel=PROMINENT`, `alertLevel=STANDARD`, `approval=PENDING`.
5. **Deep link parsing** with spaces, emoji and an empty `text`.
6. **CI green**, both exact-alarm variants; the release manifest has AppFunctions off when the flag is off.

Phone (Mark with Tim):
7. Share a chat message "Grab the dry cleaning Friday after work": the sheet shows Fri at 5:30 pm (or the parser's after-work time) and saves.
8. Add both widgets; tick one done from the widget; the app and the notification agree.
9. Add the tile; it opens Add from the lock screen after unlock (never test by removing the lock).
10. Long-press shortcuts work.
11. AppFunctions on the S24+: only if Google has granted early access; otherwise NOT RUN.

## 6. Risks
- **Widget staleness.** Updating from the command layer's commit point keeps it current; boot and time-change refreshes cover the rest.
- **AppFunctions API changes.** It is a preview; keeping it behind a flag and thin limits rework.
- **Share sheet clutter.** Accept only text, so Do doesn't appear for images.

## 7. Calls (Claude's defaults for Gareth)
1. Share sheet saves without opening the full app.
2. No date found means tomorrow 9 am, shown before Save.
3. Widgets in Glance.
4. AppFunctions built now, off in release until Gemini access opens.
5. Agent alert requests are held at Normal until approved.
