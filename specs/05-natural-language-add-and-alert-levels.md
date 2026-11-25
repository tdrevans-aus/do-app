# Spec 5: Natural-language add and alert levels

Owner: Gareth (PM). Build: Jim, via a cloud coding agent, one PR to `tdrevans-aus/do-app`. QA: Mark. Status: ready to build, 25 Nov 2026.
Sources: `DO-RESEARCH.md` s. 1.3, 4, 5.1, 5.2 and 5.11; Due help "Type the date as part of the reminder (natural date parsing)" (article 203530719, in `src/due-kb.txt` 671-715 and the full body in `src/zd1.json`); Due help "Get a reminder before an event" (`due-kb.txt` 654-665); Due help "Standard, Critical & Prominent Alerts: What's the Difference?" (article 58240771866009, updated 19 Oct 2026); Due help "Use Critical Alerts" (article 15781461032473) and "Use Prominent Alerts" (article 52208554848793); Play full-screen intent policy in `src/play-policy-sensitive.txt` and `src/answer_13392821.txt`. Menu, editor and Settings labels were checked against Due 26.6.1 (16468) iPad screenshots in `/workspace/due-screens/batch2/` (`email1-image1.jpg`, `email1-image5.jpg`, `email1-image8.jpg`, `email2-image3.jpg`, `email3-image10.jpg`) and the notes in `specs/due-screens-batch2-notes.md`, 25 Nov 2026. Rule: if in doubt, copy Due. Never invent a Due fact: cite a source, or mark it assumed.

## 1. Goal
The add field accepts typed English the way Due's does, and shows the parsed due time, and any repeat, before Save. A reminder also has one of Due's three alert levels, Standard, Critical or Prominent, mapped onto what Android actually allows, with a clear fallback when a permission is missing.

## 2. Starts from Spec 4
Build on PR #4 (head `8241170`, branch `do/spec-4-repeats`), which will merge onto main (`ef930ae`, Specs 1 to 3). Package `com.evansappstudio.doapp`, minSdk 26, target and compile 36, Kotlin, Compose, Room via KSP at version 2, `setAlarmClock()`. Reuse `Scheduler`, `AlarmReceiver`, `DoneReceiver`, `SnoozeReceiver`, `ReminderNotifier`, `ReminderMirror`, `SettingsStore`, the add and edit form in `RemindersUi`, `NagSchedule`, `ScheduleResolver`, `RepeatRule` / `RepeatMath` / `RepeatText` / `RepeatPresets`, `QuickTimes` and the debug receiver.

The `reminder` row after Spec 4 is `id`, `title`, `dueAt`, `nagMinutes`, `isDone`, `doneAt`, `createdAt`, `repeatInterval`, `repeatUnit`, `repeatFrom`, `repeatWeekDays`, `repeatMonthOn`, `patternDueAt`. This PR adds a Room v3 migration for a per-reminder alert level, teaches the editor a Due-style date parser, and teaches `ReminderNotifier` the three alert levels. It leaves exact-alarm mode, the alarm-clock path, the three snooze options, the Quick Times factory slots, and the Spec 4 repeat maths alone.

The `setAlarmClock()` status-bar icon is still open for Tim, as in Spec 4. This spec does not switch scheduler APIs.

## 3. Scope

### A. Natural-language add

**Where it lives.** Parsing runs on the title field of the add and edit screen (Due's "Remind me to..." field, `email1-image1.jpg`). It is on-device, offline, English only, and has no ML and no network. A deterministic parser, not a third-party cloud API. Settings > Reminder Editor already exists in Due as a Parse Dates toggle, on in `email1-image5.jpg`; Due's help says to turn it off there (`due-kb.txt` 714-715). Store the same toggle in `SettingsStore` (`commit`, same as the other debug-configured settings). Factory is on, assumed: the screenshot has it on, and Due's factory default is not independently shown.

**The preview, copied from Due.** Due does not apply the parse just because the user typed it. It underlines the date it found, and the first Quick Access Times button becomes Set to "…", with the resolved date under it (`due-kb.txt` 678-680). Do the same:

- Underline the matched span in the title as the user types.
- Replace slot 0 of the Quick Times grid with a Set to "{matched text}" button. Under the label, show what the parse produced: the local date and time (device 12/24-hour setting, device locale) and, if a repeat was parsed, the rule in Spec 4 words (for example "Weekdays" or "Every 2 weeks on Wednesday").
- Tap Set to: write the parsed `dueAt` and any `RepeatRule` into the draft, strip the matched words from the title, and show a small Keep button under the title. Due: "The due date changes, and the date words leave the title" (`due-kb.txt` 680).
- Tap Keep, which appears only right after Set to (`due-kb.txt` 682-684): put the words back in the title, leave the due date and rule set, and hide Keep.
- If the user never taps Set to, the words stay underlined. Once the keyboard is down, tapping the underlined span offers Set (apply and keep the words) or Set & Remove (apply and strip them) (`due-kb.txt` 685-688).
- Save without Set, Set to, or Set & Remove does not apply the parse. The stored `dueAt` and rule stay whatever the draft already was (Due: the words stay underlined so the date can be set later, `due-kb.txt` 685-688 and 665). If the stripped title would be empty, Save is rejected until the user types a title (assumed: every reminder still needs a title, Spec 1).
- After the parse is accepted, slot 0 returns to its ordinary Quick Times button (assumed: Due's help only describes the Set to state).
- Tapping any other Quick Times button, or the date banner, changes the draft due as in Spec 3 and does not by itself accept or discard the pending underline.
- Opening an existing reminder re-runs the parser on its title. Due: "If you open the reminder again, the date in the title is still underlined" (`due-kb.txt` 665). No extra Room column is required for the underline.
- Parse Dates off: no underline, no Set to, slot 0 stays a normal Quick Times button, and a mid-edit toggle-off clears the preview without changing the draft.

**What the parser returns.** One match or none. Fields: the matched span (start, end) in the original text, the leftover title after stripping that span and collapsing leftover whitespace, a `dueAt` (epoch millis in the device zone), and an optional Spec 4 `RepeatRule`. There is no second due time and no list of alternatives.

**Times** (Due: "4pm", "4:00 PM", "16:00", `due-kb.txt` 697-700). Also accept 24-hour times with minutes, such as 18:30, and the short form "3p" ("3p Thu" is a Due short form, `due-kb.txt` 711). am/pm is case-insensitive and may have a space or a full stop (4pm, 4 p.m., 4PM). A time that is not after now on the chosen date rolls to the next occurrence of that time of day, the same idea as Quick Times At Time (assumed for typed times: Due's help does not spell out the roll, but "jumps to the next one" is how Due describes a set time). "noon" and "midnight" are not in Due's list; include them as assumed: noon is 12:00, midnight is 00:00 and means the upcoming midnight.

**Dates** (Due: "Aug 22", "22 August", "8/22", "next Monday", "Fri", "tomorrow", "May 30 2030", `due-kb.txt` 679, 691-704). Also accept "today" (assumed), "the 25th" / "the 25th of December" (assumed), and "3 dec" / "3 December". Weekday names accept three-letter and full forms, singular or plural (fri, Fri, Friday). "next Tuesday" is the coming Tuesday, and if today is already that weekday it is seven days on (assumed). A date with no year is this year if the resolved instant is after now, otherwise next year (assumed). "the 25th" with no month is this month if that instant is after now, otherwise next month (assumed). A date with no time keeps the draft's time of day, then applies the roll-forward rule (assumed: Due's help never states the default clock time). The draft time on a new reminder is still now plus 5 minutes (Spec 3).

**Australian date order.** Due's help gives "8/22" as a numeric date, which is US month/day. Do is for Tim in Australia, so numeric dates are day/month: `22/8` and `22/8/2030` are 22 August. `8/22` does not parse (month 22 is invalid). This is a Do difference from Due's documented example, not a guess about Due. Also accept `22-8`, `22.8`, and an unambiguous ISO `2030-05-30` (assumed).

**Offsets from now** (Due: "in 20 mins", "in 6 months", "in 1h 20m", "2.5 hours", `due-kb.txt` 705-711). "in 20 min", "in 20 minutes" and "in 2 hours" are the same family. Minute and hour offsets are elapsed duration from now. Day, week and month offsets are calendar steps from now that keep the current local clock time (assumed, matching Spec 3's day/week rule). A bare duration such as "2.5 hours" is treated as "in 2.5 hours" (Due lists it as a short form).

**Connectors.** Words such as "at", "on" and "in" that belong to the matched phrase are part of the span and are stripped with it. "Make dinner reservation at 14:00 tomorrow" becomes "Make dinner reservation" (Due's own example). "Sit in the meeting tomorrow" only matches "tomorrow", so "in" stays. A bare "may", "march" or "august" in ordinary English ("May I call her") does not parse; those month names parse only with a day number or an unambiguous date phrase (assumed). A bare number such as "25" does not parse as "the 25th".

**Repeats, a Do addition.** Due's natural-date article only documents dates and times. It does not say that phrases such as "every day" set a repeat. StillDue advertises this ("Bins out every Tuesday at 7am", `DO-RESEARCH.md` s. 2). Do still parses a repeat into a Spec 4 `RepeatRule`, because that is in this PR's brief, and marks it clearly as a Do addition, not a copied Due fact. Highlight and Set to cover the repeat span the same way as a date. Supported phrases, case-insensitive, mapped to `RepeatRule.of(...)`:

| Phrase family | Rule |
|---|---|
| every day, daily, every 1 day | interval 1, `DAY`, from due date |
| weekdays, every weekday | Spec 4 weekdays preset (Mon-Fri) |
| every Wednesday, Wednesdays, weekly on wed | interval 1, `WEEK`, On that day |
| every 2 weeks on wed | interval 2, `WEEK`, On WED |
| every hour, hourly, every 2 hours, every 15 minutes, every 30 min | interval N, `HOUR` or `MINUTE`, from due date (Do's Spec 4 units) |
| monthly, every month, 25th each month, every 25th, monthly on the 25th | interval 1, `MONTH`, `DOM:n` |
| monthly on the last fri, last Friday of the month | interval 1, `MONTH`, `NTH:LAST,FRIDAY` |
| 2nd Tuesday each month, monthly on the 2nd tue | interval 1, `MONTH`, `NTH:2,TUESDAY` |
| first day of the month, last day of the month | `NTH:1,DAY` or `NTH:LAST,DAY` |
| every year, yearly, every 25 Nov | interval 1, `YEAR` (a month-and-day, if present, sets the first `dueAt`) |
| every 3 months after completion, from completion every 3 months | interval 3, `MONTH`, from `COMPLETION` (assumed wording) |

A repeat with no time or date keeps the draft `dueAt`. A repeat plus a time or weekday sets the first `dueAt` to the next matching instant, then attaches the rule. Interval must be 1 to 99 or the repeat does not parse (Spec 4 range). Hourly or minute repeats with chosen weekdays are out of Spec 4, so a phrase such as "every hour on weekdays" does not parse a rule (the time, if any, may still parse). End dates and "end after N" stay out, as in Spec 4.

**Off, and no match.** If nothing in the title is a known time, date, offset or repeat, there is no underline and no Set to. The title is left alone. Parsing never invents a due time from ordinary words.

**Unit tests.** Ship a table-driven test of the parser with a frozen clock of Wednesday 25 November 2026 at 18:03:00 in `Australia/Perth` (UTC+8), and a draft due of now plus 5 minutes (18:08). Zone and clock are arguments, so the test does not read the device clock. All 50 cases in the table at the end of this section must pass. Jim may add more, and must not delete these.

### B. Alert levels

**Three mutually exclusive types**, named as Due names them, not as the older "Loud / Alarm" labels in `DO-RESEARCH.md` s. 4. Due: "Each reminder and timer in Due can use one of three alert types: Standard, Critical, or Prominent. They are mutually exclusive" (alert-types article). Do ships this for reminders only (timers are out).

Store `alertLevel` on the `reminder` row (`STANDARD`, `CRITICAL`, or `PROMINENT`). Room version 3, with a migration from version 2 that sets existing rows to `STANDARD`. New reminders take the default alert type from `SettingsStore`. Factory default is Standard, assumed: Due's overflow on a new reminder shows Standard Alert checked (`email2-image3.jpg`), and Due does not document a "default alert type for new reminders" row. Changing the default does not rewrite existing rows (same idea as Due's "Only new reminders will be created with these settings", `email1-image4.jpg`).

**Where the user sets it.**

- List overflow, copied from `email2-image3.jpg`, in this order under Auto Snooze: Standard Alert (check the current one), Critical Alert with the !!! mark, Prominent Alert with the alarm-clock mark. A tap writes the level immediately, does not open the editor, and turns off the other two. Due's help places the same three items on the list context menu.
- Editor: the bell already sits in Due's composer top bar, between the repeat button and Save (`email1-image1.jpg`). Tapping it opens an Alert Type picker with the same three names. The picker edits the draft; Save persists it, as with the repeat rule.
- List indicators, copied from Due's help: a !!! mark only when that reminder is specifically Critical, and an alarm-clock mark only when it is specifically Prominent. Standard has no extra mark. Due hides the mark when a device-level "All Reminders" override is on; Do does not ship that override in this PR (see Out of scope), so the mark always follows the stored level.
- Settings, test-harness section: Default alert type (Standard / Critical / Prominent), plus a short permission status for full-screen intent and Do Not Disturb access, with a button that opens the matching system screen. Due's Sounds & Alerts page (`email1-image8.jpg`) has Critical Alerts and Prominent Alerts as Off / All / Specific disclosures. That fuller device-level override is deferred; this PR still has to ask for the Android permissions, which is what the status row is for.

**Android mapping.**

- **Standard.** The existing `reminders` channel (`IMPORTANCE_HIGH`, `USAGE_NOTIFICATION`, no DND bypass, `CATEGORY_REMINDER`). Sound plays once per fire, respects silent mode, vibration and Do Not Disturb. This is today's notification, and it stays the default path. Do not change that channel's existing sound attributes, because a user may already have tweaked it.
- **Critical.** A new `reminders_critical` channel, `IMPORTANCE_HIGH`, `AudioAttributes.USAGE_ALARM`, `setBypassDnd(true)`, `CATEGORY_ALARM`. Sound plays once per fire (Due: "Plays once", "You do not need to stop the alert sound manually"). Show a visible Critical label on the notification (Due: "a red 'Critical' label"). Requires `ACCESS_NOTIFICATION_POLICY` (Do Not Disturb access) for the channel to override DND. The first time the user chooses Critical on a reminder or as the default, explain why, then send them to `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`. Do not call `NotificationManager.setInterruptionFilter` or otherwise change the user's DND mode. The stored level stays Critical even if they refuse; at fire time, fall back (below).
- **Prominent.** A new `reminders_prominent` channel, `IMPORTANCE_HIGH`, `USAGE_ALARM`, `setBypassDnd(true)`, `CATEGORY_ALARM`, with a full-screen intent to a Do activity. Due: "an alarm, like the ones from Apple's stock Clock app. It takes over the screen and keeps sounding until you act on it." On a locked or off screen, show the full-screen activity (`showWhenLocked`, `turnScreenOn`). When the device is unlocked and in use, Due shows a banner, not the full-screen alarm (Prominent Alerts article). On Android the full-screen intent already behaves that way, so use it, and keep the heads-up notification sounding until the user acts. The activity offers Mark Done, the three Spec 2 snooze actions, and Stop. Stop silences this fire without marking the reminder done (Due: "dismiss the alert without marking the reminder done"). If nag is on, the next nag is scheduled from the moment the user stops or otherwise attends, not while the alarm is still ringing (Due: "a Prominent alert keeps sounding until you attend to it, so its Auto Snooze interval only starts counting once you do"). If nag is off, Stop is the last fire. Side or volume buttons should Stop if the activity can see them; OEM behaviour varies, so the on-screen Stop is the guaranteed path (assumed). Requires `USE_FULL_SCREEN_INTENT`, and DND access for silent and DND breakthrough. The first time the user chooses Prominent, explain both, then open `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` when `NotificationManager.canUseFullScreenIntent()` is false (Android 14+; this app targets 36), and the DND-access screen if that is also missing.

**Fallbacks,** required, not optional. A fire must still notify. Log `DoAlarm alert-fallback id=<id> requested=PROMINENT|CRITICAL effective=CRITICAL|STANDARD reason=NO_FSI|NO_DND` (no title).

- Prominent without full-screen permission: post as Critical (heads-up, alarm stream, DND bypass if granted, but no full-screen activity). If a looping heads-up sound is still possible, keep it until the user acts; otherwise play once and rely on nag.
- Critical (or a Prominent fallback) without DND access: still post on the Critical channel, but `setBypassDnd` will not take effect, so Do Not Disturb may silence it. That is the fallback, not a failure.
- If even the Critical channel cannot be created, use Standard.
- Never crash, and never skip the notification, because a permission is missing.
- Settings and the editor should show that a chosen level is currently falling back, with a tappable grant action.

**Nag and grouping.** Standard and Critical keep the Spec 1 cadence: each nag is one sound, one notification updated in place. Prominent holds the cadence until the user attends, as above. Grouping and the silent summary (Spec 2) still apply to Standard and Critical. A Prominent fire should not be folded into a quiet group summary; it must alert on its own (assumed: Due's Prominent path is an alarm, not a grouped banner). Snooze all does not dismiss a ringing Prominent full-screen activity until that reminder is itself snoozed or stopped (assumed).

**Direct boot.** Extend the device-protected mirror with `alertLevel`, so a pre-unlock fire can use Critical or Prominent on the lock screen. After unlock, Room remains the source of truth, as today.

**Permissions in the manifest.** Add `USE_FULL_SCREEN_INTENT` and `ACCESS_NOTIFICATION_POLICY`. Do not add a foreground service unless looping Prominent sound cannot be done from the full-screen activity plus the notification. Prefer no FGS in this PR. The Play Console declaration for full-screen intent is not part of this PR (nothing is submitted to Play without Tim). The in-app prompt and the fallback must work even if Play later refuses the pre-grant.

**Debug hooks** (debug build only, same exported receiver). Keep every Spec 1 to 4 action. Add:

- `.debug.PARSE` with `text` (required) and optional `now` (epoch millis) and `draftDue` (epoch millis). Missing `now` is the device clock; missing `draftDue` is `now + 5 minutes`. Logs `DoAlarm parse match="..." title="..." dueAt=<ms> rule=OFF|... span=<start>,<end>` or `DoAlarm parse none text="..."`. No title of a saved reminder is required, and nothing is written to Room.
- `.debug.ADD` gains optional `parse` (boolean, default false) and optional `alert` (`STANDARD`, `CRITICAL`, `PROMINENT`, default the Settings default, or Standard if unset). When `parse` is true, run the parser on `title` at the device now, apply it as Set & Remove (strip the span, use the parsed due and rule), then add. If there is no match, add the original title at the usual `now + inSeconds`. Log the existing `debug-add` line, plus `parse` and `alert` lines when they did work.
- `.debug.SET_ALERT_LEVEL` with `id` and `level`. Writes through, unlike the editor. Log `DoAlarm alert id=<id> level=...`.
- `.debug.CONFIG_PARSE` with `value` `ON` or `OFF`. `commit`. Log `DoAlarm config-parse value=ON|OFF`.
- `.debug.CONFIG_ALERT_DEFAULT` with `level`. `commit`. Log `DoAlarm config-alert-default level=...`.
- `.debug.DUMP_ALERT` with optional `id`. With an id, logs `DoAlarm alert id=<id> level=... default=... fsi=<true|false> dnd=<true|false> effective=...`. Without an id, logs the default and the two permission flags.

The release build still must not contain the receiver (Spec 1 AT-17).

**UI.** Still a test harness. Add the underline, Set to / Keep / Set & Remove, the bell picker, the list overflow items and indicators, and the Settings rows. No design polish.

**Parser test table** (frozen now = 2026-11-25T18:03:00+08:00, zone `Australia/Perth`, draft due = 2026-11-25T18:08:00+08:00). `due` values are local clock times in that zone. `title` is the leftover after Set & Remove. `rule` uses Spec 4 debug tokens (`1 DAY`, `1 WEEK MON,TUE,WED,THU,FRI`, `2 WEEK WED`, `1 HOUR`, `1 MONTH NTH:LAST,FRIDAY`, and `OFF` for no repeat). Cases marked assumed are Do calls, not Due quotes.

| # | Input | title | due | rule | Source |
|---|---|---|---|---|---|
| 1 | Collect passport next Monday 4pm | Collect passport | 2026-11-30 16:00 | OFF | Due help |
| 2 | Make dinner reservation at 14:00 tomorrow | Make dinner reservation | 2026-11-26 14:00 | OFF | Due help |
| 3 | Wish Sally happy birthday on Aug 22 at 8 AM | Wish Sally happy birthday | 2027-08-22 08:00 | OFF | Due help (22 Aug 2026 is already past) |
| 4 | Phone contract expires May 30 2030 | Phone contract expires | 2030-05-30 18:08 | OFF | Due help, date only keeps draft time (assumed) |
| 5 | Kids' dentist Fri 4:15 PM | Kids' dentist | 2026-11-27 16:15 | OFF | Due help |
| 6 | 4pm | (empty) | 2026-11-26 16:00 | OFF | Due help, rolls forward |
| 7 | 4:00 PM | (empty) | 2026-11-26 16:00 | OFF | Due help |
| 8 | 16:00 | (empty) | 2026-11-26 16:00 | OFF | Due help |
| 9 | Aug 22 | (empty) | 2027-08-22 18:08 | OFF | Due help, year rolls (assumed) |
| 10 | 22 August | (empty) | 2027-08-22 18:08 | OFF | Due help |
| 11 | in 20 mins | (empty) | 2026-11-25 18:23 | OFF | Due help |
| 12 | in 6 months | (empty) | 2027-05-25 18:03 | OFF | Due help, calendar months from now (assumed) |
| 13 | in 1h 20m | (empty) | 2026-11-25 19:23 | OFF | Due help |
| 14 | 2.5 hours | (empty) | 2026-11-25 20:33 | OFF | Due help short form |
| 15 | 3p Thu | (empty) | 2026-11-26 15:00 | OFF | Due help short form |
| 16 | Dentist 3p Thu | Dentist | 2026-11-26 15:00 | OFF | Due help / research examples |
| 17 | Meeting in 1h 20m | Meeting | 2026-11-25 19:23 | OFF | Due help / research examples |
| 18 | Laundry 2.5 hours | Laundry | 2026-11-25 20:33 | OFF | Due help / research examples |
| 19 | 6pm | (empty) | 2026-11-26 18:00 | OFF | Brief; 18:03 is after 18:00 |
| 20 | 18:30 | (empty) | 2026-11-25 18:30 | OFF | Brief |
| 21 | noon | (empty) | 2026-11-26 12:00 | OFF | Brief, assumed |
| 22 | midnight | (empty) | 2026-11-26 00:00 | OFF | Assumed, upcoming midnight |
| 23 | in 20 min | (empty) | 2026-11-25 18:23 | OFF | Brief |
| 24 | in 2 hours | (empty) | 2026-11-25 20:03 | OFF | Brief |
| 25 | tomorrow | (empty) | 2026-11-26 18:08 | OFF | Brief, draft time (assumed) |
| 26 | today | (empty) | 2026-11-25 18:08 | OFF | Assumed |
| 27 | fri | (empty) | 2026-11-27 18:08 | OFF | Brief |
| 28 | next tue | (empty) | 2026-12-01 18:08 | OFF | Brief |
| 29 | next Monday | (empty) | 2026-11-30 18:08 | OFF | Due "next Monday" |
| 30 | 3 dec | (empty) | 2026-12-03 18:08 | OFF | Brief |
| 31 | the 25th | (empty) | 2026-11-25 18:08 | OFF | Brief; draft time still ahead (assumed) |
| 32 | 22/8 | (empty) | 2027-08-22 18:08 | OFF | AU d/m, year rolls |
| 33 | 8/22 | no match | | | AU d/m, month 22 is invalid |
| 34 | 22/8/2030 | (empty) | 2030-08-22 18:08 | OFF | AU d/m with year |
| 35 | 12/25 | no match | | | AU d/m, month 25 is invalid |
| 36 | 25/12 | (empty) | 2026-12-25 18:08 | OFF | AU d/m |
| 37 | 2030-05-30 | (empty) | 2030-05-30 18:08 | OFF | ISO, assumed |
| 38 | Take bins out every day | Take bins out | 2026-11-25 18:08 | 1 DAY | Do addition |
| 39 | weekdays | (empty) | 2026-11-25 18:08 | 1 WEEK MON,TUE,WED,THU,FRI | Do addition |
| 40 | every 2 weeks on wed | (empty) | 2026-11-25 18:08 | 2 WEEK WED | Do addition; today is Wed and draft time is ahead |
| 41 | every hour | (empty) | 2026-11-25 18:08 | 1 HOUR | Do addition |
| 42 | monthly on the last fri | (empty) | 2026-11-27 18:08 | 1 MONTH NTH:LAST,FRIDAY | Do addition; last Friday of Nov 2026 is the 27th |
| 43 | Bins out every Tuesday at 7am | Bins out | 2026-12-01 07:00 | 1 WEEK TUE | Do addition; StillDue wording, not Due help |
| 44 | every 3 months after completion | (empty) | 2026-11-25 18:08 | 3 MONTH from COMPLETION | Do addition, assumed wording |
| 45 | Buy milk | no match | | | ordinary title |
| 46 | May I call her | no match | | | bare month name, assumed |
| 47 | Sit in the meeting tomorrow | Sit in the meeting | 2026-11-26 18:08 | OFF | "in" is not a time connector here |
| 48 | tomorrow noon | (empty) | 2026-11-26 12:00 | OFF | Assumed combination |
| 49 | every hour on weekdays | no match | | | Spec 4 cannot attach On days to HOUR |
| 50 | NEXT MONDAY 4PM | (empty) | 2026-11-30 16:00 | OFF | case-insensitive |

Empty leftover titles are allowed as a parse result; the editor still refuses to Save them without a typed title.

## 4. Out of scope
Due's device-level Critical Alerts and Prominent Alerts Off / All Reminders / Specific Reminders setting (help articles, and Off in `email1-image8.jpg` and `email3-image10.jpg`). Do ships a default for new reminders plus a per-reminder level, and the permission prompts. The All-Reminders override can come back later once we know whether we want it. Custom Critical alert volume (Due's help). Per-reminder alert sounds and Due's sound catalogue. Timers, and timer-only Prominent snooze duration. Languages other than English. ML or any network parse. Typing a time into the notification with `RemoteInput` (a Could in `DO-RESEARCH.md` s. 5.3). Share-sheet add, a Quick Settings tile, Siri or Assist, and app shortcuts. The notification Quick Times grid, Custom Snooze and "Snooze to..." (still deferred from Specs 2 and 3). Hourly or minute repeats on chosen days (still deferred from Spec 4). End date and end after N. Quiet hours, the health check (a later spec can reuse the permission status row), paywall, backup, Open Link, widgets, Wear OS, and any design polish. Submitting a Play Console declaration, publishing, or changing `setAlarmClock()` / the exact-alarm permission plan. Changing the three notification snooze options or the Quick Times factory slots. Adding a foreground service unless looping Prominent sound proves impossible without one, in which case call it out in the PR rather than silently adding it.

## 5. Acceptance tests (Mark)
Setup, package, tolerance and `.debug.ADD` / `.debug.DONE` / `.debug.SNOOZE` are as in Specs 1 to 4. New: `adb shell am broadcast -a com.evansappstudio.doapp.debug.PARSE -n com.evansappstudio.doapp/.debug.DebugReceiver --es text "Collect passport next Monday 4pm"`, and the same pattern for `SET_ALERT_LEVEL`, `CONFIG_PARSE`, `CONFIG_ALERT_DEFAULT` and `DUMP_ALERT`. Capture `adb logcat -s DoAlarm`. The PR includes unit tests for the parser table and a PowerShell 5.1 phone runner at `qa/run-spec-05.ps1`, in the style of `qa/tests/run-spec-04.ps1`: UTF-8 BOM, `[CmdletBinding()]` on the helper functions, named parameters only, and an `-Only AT-02,AT-05` filter. Mark reviews it, he does not have to write it. Tests marked **human** need a person on the add/edit screen, the shade or the lock screen; the rest should be scriptable over adb. Restore Parse Dates on, Default alert type Standard, automatic time zone, and leftover reminders (Done or delete) before handing the phone back. Do not add a PIN on Tim's phone without his ok. Do not leave Do Not Disturb, silent mode or full-screen permission in a surprising state; the runner should restore them when it can, and the human checks should say when they cannot.

1. **Parser unit table.** CI runs the 50-row table in section 3 against a frozen clock. All documented Due examples match. `8/22` and `12/25` do not parse. `22/8` is 22 August. "May I call her" and "Buy milk" do not parse. "every hour on weekdays" does not invent an On-days hour rule.

2. **PARSE debug.** On the phone, `.debug.PARSE` with `text` "Collect passport next Monday 4pm" and a `now` of 2026-11-25T18:03:00+08:00 (or the frozen extras) logs `match="next Monday 4pm"`, leftover title "Collect passport", `dueAt` equal to Monday 30 Nov 2026 16:00 in the device zone if the zone is Perth, `rule=OFF`. A second PARSE of "Buy milk" logs `parse none`. A third, "Bins out every Tuesday at 7am", logs title "Bins out", rule `1 WEEK TUE`, and a Tuesday 07:00 `dueAt`.

3. **ADD with parse.** `.debug.ADD` `parse true` `title` "Dentist 3p Thu" `inSeconds` 600 `nagMinutes` 0. The stored title is "Dentist", `dueAt` is the coming Thursday at 15:00 local, rule off, and the list shows that title and time. A copy with "Take bins out every day": title "Take bins out", daily rule, `DUMP_REPEAT` shows the Spec 4 daily sequence from the stored due.

4. **Set to, Keep, Set & Remove. Human.** Parse Dates on. Open add, type "Collect passport next Monday 4pm". The span is underlined, and slot 0 reads Set to "next Monday 4pm" with the resolved date under it. The other eleven Quick Times buttons are unchanged. Tap Set to: title becomes "Collect passport", the date banner shows Monday 16:00, Keep appears, and slot 0 is a normal Quick Times button again. Tap Keep: the words come back, the banner stays on Monday 16:00. Leave without Save: nothing is stored. Repeat, use Set & Remove (keyboard down, tap the underline): words leave, date is set, Save stores the stripped title. Type "Buy milk", Save with the default draft due: no parse is applied.

5. **Save does not silently apply. Human.** Type "Meeting in 1h 20m" and Save without Set to. The stored title still contains those words, and `dueAt` is the ordinary new-reminder draft (now plus 5 minutes), not now plus 1 hour 20 minutes. Re-open: the span is underlined again, and Set / Set & Remove still work.

6. **Parse Dates off.** `CONFIG_PARSE` `OFF`, then PARSE "tomorrow" still logs a match from the debug hook (the parser itself stays available for tests), but **human:** the editor shows no underline and no Set to, and slot 0 is 9:30 am (or the custom slot 0). `CONFIG_PARSE` `ON` restores the preview. Process death: set OFF, `adb shell am kill com.evansappstudio.doapp`, relaunch: it is still off. Restore ON afterwards.

7. **Default alert type and per-reminder override.** Fresh settings: `DUMP_ALERT` shows `default=STANDARD`. `.debug.ADD` a reminder with no `alert` extra: `DUMP_ALERT` `level=STANDARD` `effective=STANDARD`. `SET_ALERT_LEVEL` `CRITICAL`: `level=CRITICAL`. `CONFIG_ALERT_DEFAULT` `PROMINENT`, add another with no extra: `level=PROMINENT`. The first reminder is still `STANDARD` (or `CRITICAL` if that was the last write). The default persists across `am kill`. Restore the default to Standard afterwards.

8. **List overflow and editor. Human.** Overflow on a list row offers Standard Alert, Critical Alert and Prominent Alert, in that order, with the Due marks (!!! and the alarm clock) and a check on the current one (`email2-image3.jpg`). Choosing Critical writes immediately; the row shows the !!! mark; `DUMP_ALERT` matches. The editor bell opens the same three choices as a draft; Save persists, leaving without Save does not. A Standard row has no extra mark.

9. **Standard fire.** Add one due in 15 seconds, nag off, `alert` STANDARD. It fires within tolerance on the existing `reminders` channel, `IMPORTANCE_HIGH`, once, with Mark Done and the three snoozes. `dumpsys notification` does not show a full-screen intent. **Human:** with the ringer on and DND off, it sounds like today's reminders.

10. **Critical fire and DND. Human,** with a debug add. Grant Do Not Disturb access to Do (the runner may open the settings screen; a person must tap Allow if adb cannot). Add one due in 15 seconds, nag 1, `alert` CRITICAL. It fires on `reminders_critical`, sound once, a visible Critical label, Mark Done and the three snoozes still work, and a +1 minute nag sounds once more. Turn on Do Not Disturb (total silence if the phone offers it), add another Critical due in 15 seconds: it still sounds. Turn DND off afterwards. If DND access cannot be granted in this session, mark the DND half NOT RUN and still capture the channel and label with DND off.

11. **Critical fallback.** Revoke DND access (or run before it is granted). Add a Critical reminder due in 15 seconds. `DUMP_ALERT` shows `dnd=false` and `effective` not bypassing DND. The notification still appears (Standard behaviour under DND is acceptable). Log contains `alert-fallback` `requested=CRITICAL` `reason=NO_DND`. Restore access afterwards if the test revoked it.

12. **Prominent fire, lock screen and Stop. Human.** Grant full-screen intent and DND access. Add one due in 20 seconds, nag 1, `alert` PROMINENT. **Lock the phone (no new PIN).** When it fires, a full-screen alarm-style screen appears, the sound keeps going, and Mark Done, the three snoozes and Stop are available. Tap Stop: the screen and sound go, the reminder is not done, and the next nag arrives one interval after Stop, not while it was ringing. A second copy, Mark Done: sound stops, and a one-shot reminder is done (or a repeating one advances, if that copy was given a rule). A third copy, snooze option 0: sound stops, `dueAt` is now plus 1 hour, as in Spec 2. Unlock afterwards.

13. **Prominent when unlocked, and FSI fallback.** With FSI granted, add a Prominent reminder due in 15 seconds and leave the phone unlocked. **Human:** it should arrive as a heads-up or banner that keeps sounding, not a full-screen takeover (Due's unlocked behaviour). Then revoke full-screen intent (`cmd appops set com.evansappstudio.doapp USE_FULL_SCREEN_INTENT deny` or the system screen), add another Prominent due in 15 seconds. No full-screen activity. `DUMP_ALERT` `fsi=false`. Log `alert-fallback` `requested=PROMINENT`. A heads-up or ordinary high-importance notification still appears. Restore FSI afterwards.

14. **Direct boot and process death.** Add a Critical (or Prominent, if FSI is granted) reminder due in 5 minutes, nag off. `adb shell am kill com.evansappstudio.doapp`, wait for the fire: it still posts at the right level. **Reboot, do not unlock:** a Standard or Critical reminder due shortly still appears on the lock screen; a Prominent one should take over the lock screen if FSI is granted. If this cannot be run without a PIN, mark the unlock-path checks that need one NOT RUN; the process-death half is still required.

15. **CI green.** Unit tests cover the parser table, date-order rejection of US numeric dates, year and month roll-forward, leftover-title stripping, each mapped repeat phrase, the Room v3 migration of a version-2 row (level becomes STANDARD, repeat columns intact), and alert fallback selection (no FSI, no DND, both missing). The release build contains no debug receiver, and still contains no keystore or API keys (Spec 1 AT-18).

## 6. Risks
- **Play policy for `USE_FULL_SCREEN_INTENT`.** From Android 14, this is a special-access permission and is auto-granted only when the app's core function is "setting an alarm" or "receiving phone or video calls" (`src/play-policy-sensitive.txt`, [Play: Full-Screen Intent Permission](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en), [Play: FGS and full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821)). Apps that do not meet that test are not pre-granted and "must request permission from the user". Play will also review a declaration at publish time. Do has a fair case, the same one already noted for `USE_EXACT_ALARM` in Spec 1: it is an alarm-style reminder app, and Prominent is core, not a gimmick. It is still a risk, because a reminder app is not named in the policy. This PR must therefore ship the user prompt and a working fallback, and must not assume the pre-grant. Nothing in this PR is submitted to Play. If Play later refuses the declaration, Prominent degrades to the fallback and the app remains publishable.
- **DND bypass is not a Play-restricted permission, but it is special access.** `ACCESS_NOTIFICATION_POLICY` only works after the user grants Do Not Disturb access. OEM settings screens differ. Critical and Prominent cannot honestly promise to break through DND until that grant exists. The fallback and the Settings status row are the mitigation. Never change the user's interruption filter.
- **Android 15+ notification cooldown** can soften repeated Standard nags for up to two minutes (`DO-RESEARCH.md` s. 5.1). Critical-style notifications that need audio and haptics are documented as exempt. Mark should note whether Critical and Prominent nags are softened on the S24+ (Android 17). This PR does not invent a workaround beyond using the alarm stream and alarm category.
- **OEM shade and lock-screen behaviour** for full-screen intents and custom notifications is messy, as in Spec 2. Samsung may still keep a process alive or show FSI differently. The human lock-screen test matters more than the emulator.
- **Due does not document repeat parsing.** The phrase list is a Do addition. Over-matching ("May I", "weekly shop" as a weekly rule) will make people distrust the preview. The no-match cases and the Set to confirmation are the safety net. If a phrase is doubtful, do not parse it.
- **Numeric date order will surprise anyone who expects Due's "8/22".** The preview must show the resolved date, so a wrong guess is visible before Save. Document day/month in the eventual in-app help.
- **Looping Prominent sound** without a foreground service may be clipped when the screen is off or after a few seconds on some OEMs. If Mark sees that, say so in the PR rather than quietly adding an FGS. An FGS would bring its own Play declaration (`DO-RESEARCH.md` s. 5.11).
- **`SET_ALERT_LEVEL` and `ADD` with `parse true` write through**, unlike the editor. Restore leftover reminders after a run.

## 7. Gareth calls
Defaults Jim should build to, unless Tim walks them back:

1. **Copy Due's Set to / Keep / Set & Remove preview**, and do not apply a parse on Save alone. Showing the result before Save means the Set to button and the underline, not a silent rewrite at Save.
2. **English only, day/month numeric dates, no ML and no network.** `8/22` does not parse. This is a deliberate Do difference from Due's "8/22" example.
3. **A date or weekday with no time keeps the draft clock time**, then rolls forward if that instant is not after now. A new reminder's draft is still now plus 5 minutes.
4. **noon and midnight, today, and "the 25th" ship**, marked assumed, because the brief asks for them and they are ordinary English. "tonight" and "yesterday" do not parse in this PR.
5. **Repeat phrases ship in this PR as a Do addition**, mapped only to Spec 4 rules, and only the families in the table. They are highlighted and accepted through the same Set to path. Due's help is not the source for this.
6. **From-completion parses only the explicit "after completion" / "from completion" wording** in the table. Casual wording such as "every 3 months after I do it" is left unparsed rather than guessed.
7. **Default alert type is Standard**, applied only to new reminders. Per-reminder override is on the overflow menu and the editor bell. Due's Off / All / Specific device-level override is out of this PR.
8. **Prominent without `USE_FULL_SCREEN_INTENT` falls back to Critical-style.** Critical without DND access still posts, but may be silent under DND. Never skip the fire.
9. **Prominent holds nag until the user attends**, then the Spec 1 cadence starts from that moment, copying Due. Standard and Critical nag as they do today.
10. **No foreground service in this PR** unless Mark or Jim can show that looping Prominent sound cannot work without one. Call that out; do not add it quietly.
11. **Play declarations and publishing stay with Tim.** This PR only adds the permissions, the prompts and the fallbacks.
12. **The parser table's frozen clock is 18:03 AWST on Wednesday 25 Nov 2026**, the time on Tim's Due screenshots, so examples such as "Fri" and "next Monday" have one agreed answer.
