# Spec 4: Repeats

Owner: Gareth (PM). Build: Jim, via a cloud coding agent, one PR to `tdrevans-aus/do-app`. QA: Mark. Status: ready to build, 25 Nov 2026.
Sources: `DO-RESEARCH.md` s. 1.3, 4 and 6; Due help "Repeat a reminder" (article 203530769, updated 27 Oct 2026). The workspace scrape `src/due-kb.txt` 367-418 is truncated mid-article; the full body is in `src/zd1.json`. Due help "Modifying due dates for repeating reminders" (`due-kb.txt` 629-653). Rule: if in doubt, copy Due.

## 1. Goal
A reminder can repeat the way Due's does: daily, weekdays, weekly, monthly or yearly in one tap, or a custom every-N rule, including chosen weekdays, a day of the month, the nth or last weekday, the first or last day, and repeat from completion. Mark Done advances to the next occurrence and the Spec 1 nag loop starts again from that new due. Snooze and Quick Times move only this occurrence unless the user says otherwise. Do also fills Due's documented gap with every-N-minutes and every-N-hours repeats.

## 2. Starts from Spec 3
Main is at `ef930ae` (Spec 1 alarm engine, Spec 2 notification actions, Spec 3 Quick Times). Package `com.evansappstudio.doapp`, minSdk 26, target and compile 36, Kotlin, Compose, Room via KSP at version 1, `setAlarmClock()`. Reuse `Scheduler`, `AlarmReceiver`, `DoneReceiver`, `SnoozeReceiver`, `ReminderNotifier`, `ReminderMirror`, `SettingsStore`, `ReminderFormScreen`, `NagSchedule`, `ScheduleResolver` and the debug receiver.

The current `reminder` row is `id`, `title`, `dueAt`, `nagMinutes`, `isDone`, `doneAt`, `createdAt`. `Scheduler.markDone` sets `isDone` and `doneAt`, cancels the alarm, dismisses the notification and removes the mirror. `updateDetails` and `Scheduler.add` / `update` know nothing about a repeat rule. This PR adds a Room v2 migration, teaches Done and the editor about repeats, and leaves exact-alarm mode, the alarm-clock path, the three snooze options and the Quick Times grid alone.

The `setAlarmClock()` status-bar icon is still open for Tim, as in Spec 3. This spec does not switch scheduler APIs.

Spec 1 treated `dueAt` as a fixed instant and left "how repeats keep local wall time across zones" for later. Spec 3 deferred it. This is that work.

## 3. Scope

**One row, one series.** A repeating reminder is still a single `reminder` row. There is no row per occurrence and no completed-occurrence log in this PR. Mark Done does not set `isDone` while a repeat rule is on. It writes the next `dueAt`, keeps `isDone` false and `doneAt` null, dismisses this notification, cancels the current alarm, updates the device-protected mirror, and arms the next fire at the new `dueAt` through `Scheduler` (same `setAlarmClock()` path as a Spec 2 snooze). Log `DoAlarm repeat-advance id=<id> newDue=<ms> patternDue=<ms> from=DUE_DATE|COMPLETION` (no title). If the computed next `dueAt` is already past, treat it as a Spec 1 missed fire: fire "now", then nag from that new anchor. After Repeat Off, Mark Done is the existing one-shot path (`isDone` true, mirror removed). List checkbox, notification Mark Done, `.debug.DONE` and a pre-unlock `pendingDone` all go through this same `Scheduler.markDone`. Delete still removes the row and the whole series.

**Repeat rule.** Persist a `RepeatRule` on the row (Room version 2, with a migration that leaves existing rows as Repeat Off). Jim may use a single TEXT column plus `patternDueAt`, or typed columns; the fields are:

- `interval`: Int, 1 to 99 (assumed; Due's wheel max is not documented).
- `unit`: `MINUTE`, `HOUR`, `DAY`, `WEEK`, `MONTH`, or `YEAR`.
- `from`: `DUE_DATE` (follow the calendar) or `COMPLETION` (count from when you complete it) (`due-kb.txt` 394).
- `weekDays`: for `WEEK`, the On days. Also used by the Weekdays preset. Empty or null is invalid for `WEEK`.
- `monthOn`: for `MONTH` only. One of: day-of-month 1-31; nth weekday (`1st`, `2nd`, `3rd`, `4th`, or `Last` plus Monday-Sunday); `1st`/`Last` plus `day` (first or last calendar day); or `1st`/`Last` plus `weekday` (first or last Monday-to-Friday day). Due's custom wheels (`due-kb.txt` 396-397; full article in `zd1.json`).
- `patternDueAt`: Long?, the current occurrence as the calendar pattern would have it, before any postpone. Null when Repeat Off or `from` is `COMPLETION`.

Null / Repeat Off means no rule, and `patternDueAt` is null. Reject invalid combinations (interval out of range, `WEEK` with no days, `MONTH` with no `monthOn`, `On` set on a `DAY` or `YEAR` rule). Minute and hour units are a Do addition (below); they do not use `On`.

**Presets, names follow the due date.** Set the due date first, then pick a repeat (`due-kb.txt` 382). The one-tap choices map to:

| Menu choice | Rule |
|---|---|
| Daily | every 1 day, from due date |
| Weekdays | every 1 week, from due date, On Monday-Friday |
| Weekly | every 1 week, from due date, On the due date's weekday |
| Monthly | every 1 month, from due date, On that day of the month (the quick label is like "25th each month at 5:30 pm") |
| Yearly | every 1 year, from due date, same month and day |

**Next occurrence, from due date.** The first fire is the reminder's current `dueAt`, even if that instant would not be produced by `On` (the user already chose the first time). Every later occurrence keeps the local time of day (`due-kb.txt` 402: "Every repeat keeps this time of day"). Computations use `java.time` in the device zone.

- `DAY`: add `interval` calendar days to `patternDueAt` (`plusDays`).
- `WEEK`: the selected `On` days in a cycle of `interval` weeks. Week 0 is the ISO week that contains `patternDueAt`'s date (Monday start, assumed; Due does not say). After the last On-day in that week, skip to the same On-days in week `interval`, `2 * interval`, and so on. Time of day is unchanged. If `interval` is 1 and On is Wednesday only, the next date after this Wednesday is next Wednesday. If On is Monday, Wednesday and Friday, the next after Wednesday is Friday, then Monday.
- `MONTH` day-of-month: add `interval` months, then land on that day of the month. If the month is short (31 January plus one month, or 29 February in a non-leap year), clamp to the last day of that month (`ZonedDateTime.plusMonths` / `plusYears`, assumed; Due does not document short months).
- `MONTH` nth / last weekday or day: in the month that is `interval` months after the pattern month, pick the 1st/2nd/3rd/4th/Last matching day. "Last weekday" is the last Monday-Friday in that month (Due: "the first or last Monday-to-Friday day").
- `YEAR`: add `interval` years, same month and day, with the same clamp as above.
- `MINUTE` / `HOUR`: add the elapsed duration to `patternDueAt` (`plusMinutes` / `plusHours`). These are not calendar units.

Mark Done, when `from` is `DUE_DATE`, sets `patternDueAt` and `dueAt` to the next pattern instant after the current `patternDueAt` (not after the possibly postponed `dueAt`, and not after now). That is how Due's postpone-this-occurrence example works: every 2 months on the 27th at 5:30 pm, postponed from 27 October to 28 October, comes back on 27 December at 5:30 pm, not 28 December (`due-kb.txt` 635-645).

**From completion.** Use this when the next date should be a number of days, weeks or months after the user actually did the work, not a fixed calendar date. Due's example: change the water filter 3 months after the last change; if it was two weeks late, the next change is still 3 months after the day it was done (full article in `zd1.json`). In the custom screen, the On row and the Next date go away, because the next date depends on when it is completed. The editor shows a phrase like "Repeat 3 months after completion".

When Mark Done runs on a from-completion reminder, the next date is the local calendar date of the completion instant, plus the interval (days, weeks, months or years, and for the Do addition, minutes or hours). The time of day comes from the reminder's own `dueAt` at the moment of Done, not from the clock time of the tap. Due is explicit: postponing before completing changes the due time, so the next occurrence uses that new time of day; to keep the original time, complete it without postponing (full article, "What time the next reminder uses").

**Missed occurrences.** If the phone is off, the clock jumps, or a fire is missed, the current occurrence stays due. It does not skip ahead to a future pattern date (assumed; Due does not document missed repeats). Spec 1 already covers a late fire: alert "now", then nag from the current `dueAt` anchor. Reboot, `TIME_SET`, `MY_PACKAGE_REPLACED` and app start still go through `rescheduleAll()`. Mark Done then advances one occurrence only. If that next instant is also in the past, it becomes the new due and fires immediately, so catching up may take several Dones. Do not auto-skip to the next future date.

**Time zone and DST.** Day, week, month and year repeats are local wall time. On `TIMEZONE_CHANGED`, rewrite `dueAt` and `patternDueAt` so the same local date and clock time hold in the new device zone, then reschedule (assumed; this is the Spec 1 forward reference, and Due only says the time of day is kept). Minute and hour repeats keep the same instant, because they are elapsed durations. `TIME_SET` does not rewrite wall time; if the new clock is already past `dueAt`, it is a missed fire of the current occurrence.

DST uses the same resolver as Spec 3: +1 day and the calendar units keep wall time across a transition; minute and hour are elapsed; a local time that falls in a spring-forward gap uses java.time's default resolver (the next valid local time); an overlap uses the earlier offset. Perth has no DST, so unit tests must cover this, and any phone check temporarily uses a zone that does (Australia/Sydney), then restores automatic time zone.

**Snooze.** A one-tap notification snooze, and Snooze all, is "postpone just this occurrence": write a new `dueAt`, leave the rule and `patternDueAt` alone, leave `isDone` false, re-anchor nag to the new `dueAt` (Spec 2). There is no this/all-future prompt on the notification. After Mark Done, a from-due-date rule comes back on the original pattern; a from-completion rule uses the snoozed time of day, as above. A snooze whose target is already past is still a Spec 1 missed fire of this occurrence.

**Editor date changes and Quick Times.** Changing the draft due of a repeating reminder (picker, banner or Quick Times) does not save and does not re-arm, same as Spec 3, until Save. On Save, if `dueAt` changed and `from` is `DUE_DATE`, prompt with Due's three choices (`due-kb.txt` 639-648):

- **Only this occurrence:** set `dueAt` to the new instant, keep the rule and `patternDueAt`. Next after Done follows the original pattern.
- **All future occurrences:** set `dueAt` and `patternDueAt` to the new instant, and retarget the rule so later dates follow the new pattern. Time of day always updates. A day-of-month rule takes the new date's day of month (Due's 27th to 28th example, still every 2 months). A nth-weekday rule takes the nth (or Last) that the new date is, and that weekday. A weekly rule with a single On day takes the new date's weekday; with several On days, keep the set and treat the date move as this occurrence only, but still update the time of day (assumed; Due's example is a single day-of-month). Daily and yearly take the new date and time (yearly also takes the new month and day). Interval does not change here; the user edits that in Custom.
- **Always only this occurrence:** same as Only this occurrence, and store that preference so later due-time saves do not prompt. "Restore warnings and prompts" in the test-harness settings (Due: Settings, Restore Warnings & Prompts) returns it to prompt each time. Persist in `SettingsStore` with `commit`, like the Spec 3 slots.

A title or nag-only save does not prompt. From-completion does not prompt either: a due change is just a new `dueAt`, and Done already uses that time of day. If the current `dueAt` no longer matches the pattern (a this-occurrence postpone), show a warning icon beside the due date. Tapping it offers to apply All future occurrences (`due-kb.txt` 652-653).

**Repeat Off.** Repeat button, choose Repeat Off, then Save (`due-kb.txt`, "Stop a reminder from repeating"). The current `dueAt` stays, the rule and `patternDueAt` clear, and the reminder is a one-shot from then on.

**Hourly and minute repeats (Do addition).** Due's shortest repeat is every day; there is no every-N-minutes or every-N-hours repeat (`DO-RESEARCH.md` s. 1.3 and 4; full article, "What Due can't repeat"). Do adds `MINUTE` and `HOUR` on the custom How often wheel only, not as one-tap presets. From due date or from completion both work. On is hidden for these units, as it is for Due's day and year units. "Hourly on certain days" is a later spec (out of scope).

**Picker UI.** Still a test harness, no design polish. On the add and edit screen:

- A repeat button (two arrows) at the top of the editor (the top bar is fine). It looks grey while the reminder does not repeat (`due-kb.txt` 383-384).
- The menu offers Daily, Weekdays, Weekly, Monthly, Yearly (labels follow the draft due), Custom…, and Repeat Off. Under the selected choice, show the next date (`due-kb.txt` 387). For from-completion, there is no next date until Done.
- Custom is one screen: Repeat (the rule in words); How often (count on the left, unit on the right, including minute and hour); From (Due Date or Completion); On, for week and month only; Next, hidden when From is Completion. A checkmark at the top right accepts the draft rule; it does not write Room. Save in the editor persists the rule and, if due also changed, may show the this/all prompt. Leaving without Save discards the draft rule and the Quick Times taps, as in Spec 3.
- The chosen rule, in words, shows under the due date in the editor (`due-kb.txt` 386) and on each list row under the due time. Copy Due's wording where we have it: "Every 2 weeks" / "Every 2 weeks on Wednesday"; "Weekdays"; "25th each month at 5:30 pm"; "3rd Friday"; "Repeat 3 months after completion". Use the device 12/24-hour setting for times. A one-shot row shows no repeat line.

**Scheduler and mirror.** Extend `Scheduler.add` and `update` (and the view model, form `onSave`, and `ReminderDao.updateDetails`) to take the rule. `update` must apply the this/all-future choice when the due time of a from-due-date series changes. On a pre-unlock Mark Done, keep today's `pendingDone` behaviour (cancel alarm, clear `nextFireAt`). After unlock, `rescheduleAll` must call the new advance path when the Room row still has a rule, not `dao.markDone`. The mirror does not need the full rule, because advance runs only once Room is readable. Title, `dueAt`, `nagMinutes` and `nextFireAt` stay on the mirror as today.

**Debug hooks** (debug build only, same exported receiver). Keep `.debug.ADD`, `.debug.DONE`, `.debug.SNOOZE`, `.debug.CONFIG_SNOOZE`, `.debug.CONFIG_QAT`, `.debug.CONFIG_POSTPONE`, `.debug.DUMP_QAT` and `.debug.APPLY_QAT`. Add:

- `.debug.ADD` extras, all optional: `interval` (default 1), `unit` (`MINUTE`/`HOUR`/`DAY`/`WEEK`/`MONTH`/`YEAR`; missing means Repeat Off), `from` (`DUE_DATE` or `COMPLETION`, default `DUE_DATE`), `weekDays` (comma-separated `MON`…`SUN`), `monthOn` (for example `DOM:25`, `NTH:3,FRIDAY`, `NTH:LAST,WEEKDAY`, `NTH:LAST,DAY`, `NTH:1,DAY`). First `dueAt` is still `now + inSeconds`. Log the existing `debug-add` line, plus the rule on a `DoAlarm repeat` line.
- `.debug.SET_REPEAT` with `id` and the same rule extras. `unit=OFF`, or a missing `unit`, is Repeat Off. Does not change `dueAt` except to set or clear `patternDueAt` (on a new from-due-date rule, `patternDueAt` becomes the current `dueAt` if it was null).
- `.debug.DUMP_REPEAT` with `id` and optional `count` (default 6, max 24). Logs `DoAlarm repeat id=<id> rule=… from=… dueAt=<ms> patternDue=<ms>` and `DoAlarm repeat-next id=<id> i=<0-based> at=<ms>` for the current occurrence and the next `count-1` pattern instants (from-completion: log the rule and current `dueAt` only, and `next=completion` so the runner can see there is no calendar next).
- `.debug.SHIFT_DUE` with `id`, a new `dueAt` (epoch millis) or `inSeconds` from now, and `mode` (`THIS` or `ALL`). Applies the editor Save rule without the prompt. Log `DoAlarm shift-due id=<id> newDue=<ms> mode=THIS|ALL patternDue=<ms>`.
- `.debug.CONFIG_REPEAT_PROMPT` with `value` `PROMPT` or `ALWAYS_THIS`.

The release build still must not contain the receiver (Spec 1 AT-17).

**UI.** Still a test harness. Add the repeat button, the custom screen, the list/editor rule text, the this/all-future prompt, the warning icon, and Restore warnings and prompts. No design polish.

## 4. Out of scope
End date and "end after N occurrences": Due's help has no such control (only Repeat Off), so this PR does not invent one. Natural-language parsing of repeats, including "Bins out every Tuesday at 7am" (`DO-RESEARCH.md` s. 2, StillDue, and Due's typed dates in `due-kb.txt` 671-708): Spec 5. Hourly or minute repeats restricted to chosen days of the week (`DO-RESEARCH.md` s. 4 Must and s. 6 item 7): a later spec, so the custom On row can stay as Due's. The notification Quick Times grid, Custom Snooze and "Snooze to..." (still deferred from Spec 3). A log of completed occurrences (a Should in `DO-RESEARCH.md` s. 4). Reusable timers (Due's stand-in for hourly repeats). Alert levels, widgets, tiles, Wear OS, quiet hours, the health check, paywall, backup, Open Link, and any design polish. Changing `setAlarmClock()` or the exact-alarm permission plan. Changing the three notification snooze options or the Quick Times factory slots.

## 5. Acceptance tests (Mark)
Setup, package, tolerance and `.debug.ADD` / `.debug.DONE` / `.debug.SNOOZE` / `.debug.APPLY_QAT` are as in Specs 1-3. New: `adb shell am broadcast -a com.evansappstudio.doapp.debug.SET_REPEAT -n com.evansappstudio.doapp/.debug.DebugReceiver --el id <id> --ei interval 1 --es unit DAY --es from DUE_DATE`, and the same pattern for `DUMP_REPEAT`, `SHIFT_DUE` and `CONFIG_REPEAT_PROMPT`. Capture `adb logcat -s DoAlarm`. Tests marked **human** need a person on the add/edit screen or the lock screen; the rest should be scriptable over adb. Restore automatic time zone, and Restore warnings and prompts, before handing the phone back. Do not add a PIN on Tim's phone without his ok.

1. **Picker, labels and list. Human.** Open add or edit. The repeat button is at the top and grey when off. The menu offers Daily, Weekdays, Weekly, Monthly, Yearly (names follow the draft due, for example a due of Wednesday the 25th at 5:30 pm shows a monthly label like "25th each month at 5:30 pm"), Custom… and Repeat Off. The selected preset shows the next date under it. Custom has Repeat in words, How often, From, On (only for week and month) and Next; From Completion hides On and Next. The checkmark does not save; Save does. The rule then shows under the due date in the editor and on the list row. Leave without Save: the stored rule is unchanged.

2. **Presets and Done.** Add a reminder due in 2 minutes, nag off. `SET_REPEAT` Daily, `DUMP_REPEAT` `count` 4: `i=0` is the current `dueAt`, then +1, +2 and +3 calendar days, same local time. `.debug.DONE`: `repeat-advance` `newDue` is +1 calendar day, `isDone` is false, the list still shows it active, `dumpsys alarm` is at `newDue`, and it fires at `newDue` within tolerance. Repeat the DUMP check for Weekdays (On Monday-Friday), Weekly (On the due weekday only), Monthly (that day of month) and Yearly (same month and day).

3. **Every 2 weeks on Wednesday.** Due a Wednesday at 19:00 local, `SET_REPEAT` `interval` 2 `unit` WEEK `weekDays` WED. `DUMP_REPEAT`: each next is 14 days later at 19:00, not 7. Done after a this-occurrence `SHIFT_DUE` of +1 day (Thursday 19:00, `mode` THIS): `newDue` is the Wednesday 14 days after the original Wednesday, at 19:00, not Thursday.

4. **Certain weekdays.** `unit` WEEK `interval` 1 `weekDays` MON,WED,FRI, due a Wednesday. Next is Friday, then Monday, then Wednesday, all at the same local time.

5. **Nth weekday, last Monday, last weekday, first and last day.** Monthly `NTH:3,FRIDAY`, `NTH:LAST,MONDAY`, `NTH:LAST,WEEKDAY`, `NTH:1,DAY` and `NTH:LAST,DAY`. `DUMP_REPEAT` lands on the right calendar dates for at least two future months, including a month where the last day is a Saturday or Sunday (last weekday is that Friday) and a 31 January start with `DOM:31` (February clamps to 28 or 29). Every 3 months on the 25th (`interval` 3, `DOM:25`) jumps three months, not one.

6. **From completion.** Due today at 09:30, rule every 3 months from Completion. `DUMP_REPEAT` has no calendar next. After Done at some later instant (wait, or Done while overdue): `newDue` is the completion local date plus 3 months, at 09:30, not at the tap's clock time. A second copy: `SHIFT_DUE` to 16:00 THIS (or a snooze that changes the time of day), then Done: `newDue` is completion date plus 3 months at 16:00.

7. **Nag continues per occurrence.** Daily, nag 1, due in 2 minutes. It fires and nags at +1 min. `.debug.DONE`. Notification gone, no further nags on the old anchor for 2 minutes. The next occurrence fires at the new `dueAt` within tolerance, then nags at +1 and +2 min from that new due, one notification updated in place.

8. **Snooze is this occurrence.** Daily at a known time, nag off. After it fires, `.debug.SNOOZE` option 0. List and alarm move to now plus 1 hour, rule unchanged. Then Done: `newDue` is the original pattern's next day at the original time of day, not plus 1 hour from the snooze and not plus 1 day from the snoozed clock time.

9. **All future occurrences.** The Due fixture, via debug: a reminder on the 27th at 17:30, every 2 months on `DOM:27`. `SHIFT_DUE` to the 28th at 17:30, `mode` ALL. `DUMP_REPEAT` then shows the 28th every 2 months at 17:30; after Done, next is two months later on the 28th (December 28 in Due's October example, or the equivalent from the test's start date). `mode` THIS on a fresh copy keeps the 27th for the next occurrence (`due-kb.txt` 635-646).

10. **Editor prompt and warning. Human,** with debug for the preference. Edit a daily (or the 27th every 2 months) reminder, change the due date with Quick Times or the picker, Save: Only this occurrence, All future occurrences, and Always only this occurrence appear. Each of the first two matches test 9's maths. Always only this occurrence postpones this one and a second Save with a new due does not prompt. The warning icon appears when this occurrence no longer matches the pattern; tapping it can apply All future. `CONFIG_REPEAT_PROMPT` `ALWAYS_THIS` then `PROMPT` (or Restore warnings and prompts) turns the prompt back on. From-completion and title-only saves do not prompt.

11. **Repeat Off.** A daily reminder, `SET_REPEAT` `unit` OFF (or Repeat Off in the UI and Save): rule gone, `dueAt` unchanged, then `.debug.DONE` marks it done the Spec 1 way (list shows done, no next alarm).

12. **Missed occurrence, clock jump, reboot.** A daily reminder due 10 minutes ago (debug add already in the past, or `TIME_SET` past a pending due). It fires now and stays on that occurrence (DUMP `i=0` is still the missed due, not tomorrow). Done advances to the next pattern date, which may also be past; in that case it fires immediately again. **Reboot:** add a daily due in 5 minutes, `adb reboot`, do not unlock: it fires on the lock screen. Unlock: it is not marked done, the rule is intact, and `dumpsys alarm` shows the next nag or the still-pending due. If this cannot be run without a PIN, mark the unlock-path checks that need one NOT RUN; the clock-jump half is still required.

13. **DST and time zone.** Unit tests required, because Perth has no DST. In Australia/Sydney (or any zone with DST): a daily 09:00 keeps 09:00 across a transition; an every-8-hours rule is elapsed time; a 02:30 daily on a gap date lands on the next valid local time, and on an overlap date uses the earlier offset. **Time zone:** a daily 09:00 AWST, then a change to Australia/Sydney (or Asia/Tokyo), rewrites `dueAt` to 09:00 in the new zone on the same local date, and `DUMP_REPEAT` / `dumpsys alarm` match. An every-8-hours reminder keeps the same instant through that change. Optional phone check: temporary zone change, then turn automatic time zone back on. Do not leave the phone in the wrong zone. Skipping the phone check is allowed if the unit tests pass; mark the phone part NOT RUN.

14. **Hourly and every-N-minutes.** Add one due in 2 minutes, `SET_REPEAT` `interval` 8 `unit` HOUR from due date, nag off. `DUMP_REPEAT`: +8, +16, +24 hours from `patternDueAt`. Done: `newDue` is +8 hours, same path as test 2. A second reminder, `interval` 15 `unit` MINUTE, same checks at +15 and +30 minutes. From completion, every 8 hours: `newDue` is the completion local date at the reminder's due time of day, plus 8 hours (s. 7 call 9). Process death: after a repeating reminder fires, `adb shell am kill com.evansappstudio.doapp`, then `.debug.DONE` still advances and arms `newDue`. Do not use `am force-stop`.

15. **Direct boot Done. Human,** same PIN rule as Spec 1 AT-9 and Spec 2 AT-12. Reboot, do not unlock, wait for a repeating reminder (daily, nag off, due shortly) to fire on the lock screen, then Mark Done. After unlock, the row is not `isDone`, `dueAt` is the next occurrence, and an alarm is armed there. If this cannot be run without creating a PIN, mark it NOT RUN; it stays an outstanding device check.

16. **CI green.** Unit tests cover presets, every-N weeks and months, On days, nth/last weekday, first/last day, short-month and leap-year clamps, from-completion date and time-of-day (including after a postpone), this-occurrence vs all-future (Due's 27th/28th fixture), missed-occurrence catch-up (one step, no auto-skip), wall time across DST, time-zone rewrite for calendar units vs elapsed minute/hour, and the Room v2 migration of a version-1 one-shot row. The release build contains no debug receiver.

## 6. Risks
- The Due scrape in `due-kb.txt` is truncated. The next-date, from-completion time-of-day, and "what Due can't repeat" rules come from the full 27 Oct 2026 article in `src/zd1.json`. If that file and the live help ever disagree, the live article wins and the spec should be updated, not guessed.
- Short months, leap days, every-N-week boundaries and missed-occurrence catch-up are assumed. A wrong call here will ship the wrong series; the unit fixtures in AT-5, AT-3 and AT-12 are the safety net.
- Time-zone rewrite can surprise a traveller if they expected the same instant. Calendar repeats follow wall time; minute and hour repeats do not. Mark should log both.
- DST cannot be proved in Perth without a temporary zone change. Unit tests carry that claim; a phone check must restore automatic time zone.
- Pre-unlock Done on a repeating reminder used to mean "mark the row done after unlock". If `rescheduleAll` still calls `dao.markDone`, the series dies. The reconcile path is the regression to watch.
- `SHIFT_DUE` and `SET_REPEAT` write through, unlike the editor. Restore leftover reminders (Done or delete) after a run.

## 7. Gareth calls
Defaults Jim should build to, unless Tim walks them back:

1. **Minute and hour units ship in this PR,** as a Do addition on the custom wheel only, because Due cannot do them and they are a v1 Must (`DO-RESEARCH.md` s. 4). They are not one-tap presets.
2. **End date and end after N are out.** Due's help has no such control. Do not invent one.
3. **Hourly on chosen days is out of this PR,** so On stays week-and-month only, like Due. It can come back as its own spec.
4. **Missed occurrences stay overdue** and Done advances one pattern step, even if that step is still in the past. No auto-skip to the next future date.
5. **Short months and leap days clamp** to the last real day (`plusMonths` / `plusYears`).
6. **Every-N-weeks uses ISO weeks** (Monday start) measured from the week that contains `patternDueAt`.
7. **Snooze and Snooze all are always this occurrence only.** No prompt on the notification.
8. **Weekly All future,** when several On days are set, updates the time of day but does not rewrite the On set. A single On day retargets to the new weekday.
9. **From-completion plus a minute or hour interval** uses the completion local date, at the reminder's due time of day, then adds the duration.
10. **Interval range is 1 to 99.** Assumed.
11. **"Always only this occurrence" is opt-in,** not the factory default. Factory is prompt each time. Due describes Always as a preference the user may choose (`due-kb.txt` 647-651).
12. **A first `dueAt` that does not match On still fires once,** then the pattern takes over after Done.
