# Spec 14: The new UI (draft)

Owner: Gareth (PM). Build: a Claude build agent. QA: Mark, and a Claude test agent. Status: **draft by Claude, 28 Nov 2026 (AWST).** Builds on the whole stack, branch `do/spec-13-android-client` (Specs 9 to 13 integrated).
Sources: `docs/design/DESIGN-PASS-1.md` (all 14 screens, look, structure); `docs/strategy/DUE-DIFFERENTIATION-REVIEW.md` (every Due-derived name, string and layout to replace); strategy section 7; the screens Specs 11 to 13 already built in the new look (`ui/DoLook.kt`, `ui/SysdScreens.kt`, `ui/AssistantsScreen.kt`, the Do Unlimited paywall).

## 1. Goal
Bring every remaining screen to the design pass, and remove everything that copies Due. After this spec the app has Do's own structure, look and words throughout. No behaviour of the nag engine changes.

## 2. Scope

### A. Structure
- Four tabs: **Today**, **All**, **Catalogue**, **Settings**; a floating Add button on Today and All (design screens 5, 10, 7, 12). Spec 11's two-tab bar grows to four.
- **Today** (screen 5): Overdue, Later today, Coming up; source chip on every row (You, Catalogue, Assistant); nagging indicator with its interval; one-tap Done; the Stuff You Should Do card below Overdue (Spec 11).
- **All** (screen 10): search; filters Active, Repeating, Catalogue, Assistant, Done; "x of 5 free" indicator for free users that opens the paywall; repeat icon.
- **Edit** (screen 11): title; Next, Repeats, Nag until done, How it gets through; the "From Stuff You Should Do" box with the item's why for catalogue reminders; **History** from the Spec 9 event log (newest first, plain words: "Added from Stuff You Should Do", "Nagged 3 times", "Snoozed 10 min", "Done"); Mark done; Delete with an Undo snackbar for 30 seconds (Spec 9's undo window).
- **Settings** (screen 12): subscription card first (state and price line from Spec 10, never the intro price without the renewal price); Nagging (default nag interval, quiet hours, Do Not Disturb follow, snooze options and time shortcuts); Helpers (Assistants, Stuff You Should Do suggestions switch, Health check with its badge); Your data (Backup, About).
- First run keeps Specs 11's steps; step 1 is the permissions screen (screen 1) and step 2 the first reminder (screen 2) if they are not already in this form.

### B. Do's own words and layouts (replaces Due-derived ones)
- Add field label **"What needs doing?"** everywhere (add, edit, share sheet, widget, first reminder). No "Remind me to".
- Alert levels **Normal, Loud, Alarm** in all UI copy, with Do's own one-line explanations (design screen 6). Stored enum names stay as they are (STANDARD, CRITICAL, PROMINENT) so no migration is needed; only display text changes.
- **Natural-language preview**: replace "Set to / Keep / Set & Remove" with Do's interaction: the parse is applied as you type, the title is tidied automatically, and a single **Undo** puts the words back (screen 6). Keep Spec 5's behaviour underneath.
- **Time shortcuts** replace the 4 x 3 Quick Times grid and its twelve defaults: one wrapping row of chips with Do's defaults **In an hour, Tonight 7 pm, Tomorrow 9 am, Saturday morning, Next week, Choose...**. Users can still customise them (Spec 3's customisation, reworded as "Time shortcuts").
- **Notification actions**: Done, **+10 min**, **Later...** (replaces "Snooze to...").
- **Full-screen alarm** (screen 9): big Done; Snooze 10 min; **Stop sound**, which stops ringing but keeps nagging (confirm against Spec 5's Stop and make it behave this way).
- **Repeat menu**: Do's own wording and order (Never, Every day, Weekdays, Every week on ..., Every month on ..., Every year on ..., Custom...), keeping every Spec 4 capability.
- **Settings and other labels**: remove Due-style wording ("Postpone behaviour", "Quick Times", "Defaults for new reminders", "Parse dates", "Interval on time picker"); use plain Do wording. List every changed string in Build notes.
- A unit test fails if any known Due-derived string returns (`DueWordingTest`, a list of banned phrases checked against `strings.xml` and Kotlin sources).

### C. Look
- One theme from `ui/DoLook.kt` across all screens (ground, ink, secondary, alarm red only for overdue, nagging and buy; catalogue amber and assistant blue chips). Dark theme with matching tokens (new; check contrast 4.5:1 for text).
- 48dp touch targets, real buttons and labels, content descriptions on icon buttons, TalkBack order checked by Robolectric semantics tests.
- Fonts: system sans for now (adding Space Grotesk and Inter is a separate call).

## 3. Out of scope
Any change to alarms, nag maths, quiet hours, repeats logic, parsing logic (beyond the preview interaction), billing logic, cloud behaviour, catalogue content. Tablet layouts. iPhone.

## 4. Acceptance tests
Host (unit and Robolectric, CI):
1. **Due wording gone.** `DueWordingTest` passes; it fails if "Remind me to", "Set & Remove", "Quick Times", "Prominent", "Postpone behaviour" or the twelve old default time labels appear in strings or UI code.
2. **Tabs and navigation.** Robolectric: each tab renders; Add opens from Today and All; back behaviour is sensible.
3. **Today grouping** and chips for each source; nagging indicator only on nagging rows.
4. **All filters** each return the right rows; the free indicator shows only for free users.
5. **Edit history** renders the event log in plain words, newest first; Delete then Undo within 30 s restores.
6. **Preview interaction**: typing a date applies it and tidies the title; Undo restores the words; behaviour matches Spec 5's parse results.
7. **Time shortcuts** produce the right times (including Tonight after 7 pm and Saturday morning on a Saturday).
8. **Stop sound** stops the ringing and keeps the next nag scheduled.
9. **Dark theme** contrast checks for text tokens.
10. **Existing tests pass**; CI green (debug, SCHEDULE, release).

Phone (Mark with Tim): every screen on the S24+, light and dark, large font; the nag notification buttons; the full-screen alarm.

## 5. Calls (Claude's defaults for Gareth)
1. Display names change; stored enum names don't.
2. Undo snackbar uses Spec 9's 30-second window.
3. Dark theme included now.
4. System font until a font decision.
