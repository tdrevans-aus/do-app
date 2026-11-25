# Spec 3: Quick Times 12-button grid

Owner: Gareth (PM). Build: Jim, via a cloud coding agent, one PR to `tdrevans-aus/do-app`. QA: Mark. Status: ready to build, 25 Nov 2026.
Sources: `DO-RESEARCH.md` s. 1.3; Due help in `src/due-kb.txt` (Quick Access Times, notification snooze) and the 8 Nov 2026 Quick Access Times article, including the labelled factory-grid screenshot. Rule: if in doubt, copy Due.

## 1. Goal
On the add and edit screen, a 12-button Quick Times grid sets the draft due time with a few taps, the same way Due's Quick Access Times do, so the user does not have to fiddle with a date picker for common times. A tap updates the draft immediately. Nothing is saved, and no alarm is re-armed, until Save. The twelve factory buttons, stacking taps, customisation, postpone behaviour and the date banner all copy Due.

## 2. Starts from Spec 2
Main is at `ae274e3` (Spec 1 alarm engine and Spec 2 notification actions). Package `com.evansappstudio.doapp`, minSdk 26, target and compile 36, Kotlin, Compose, Room via KSP, `setAlarmClock()`. Reuse `Scheduler`, the existing add/edit form (`ReminderFormScreen`), `SettingsStore`, `SnoozeKind` / the At Time and From Now wheel idea, and the debug receiver. This PR does not change Room columns, exact-alarm mode, the alarm-clock path, or the three notification snooze options.

The `setAlarmClock()` status-bar icon is still open for Tim, as in Spec 2. This spec does not switch scheduler APIs.

## 3. Scope
**The grid.** A 4 by 3 grid on the add and edit screen, under a date banner. Do's name is Quick Times (Due's name is Quick Access Times). There are two kinds of button (`due-kb.txt` 11-13):
- **At Time:** a clock time, such as 9:30 am. Sets the draft due to that time of day on the reminder's current due date. If that instant is not after now, move to the next occurrence of that time (`due-kb.txt`; "Choose which day a set time lands on").
- **Adjustment (From Now on the customise wheel):** a signed amount such as +10 min or -1 hr. When the reminder is not overdue, the amount is always applied to the draft due date, not to now. When it is overdue, Postpone behaviour decides (below).

Taps stack and can be mixed. Tapping +10 min twice adds 20 minutes. Tapping 6:30 pm then +1 hr gives 7:30 pm (`due-kb.txt` 16-28). A tap only changes the in-progress draft. Save writes `dueAt` and reschedules, the same path as a Spec 2 editor save. Leaving without Save discards the taps.

**Factory defaults,** copied from Due's "as they are set up at first" screenshot, in row-major slot order 0 to 11:

| Row | Slots | Buttons |
|---|---|---|
| 1. Set a time | 0-3 | 9:30 am, 12:00 pm, 6:30 pm, 10:00 pm |
| 2. Move later | 4-7 | +10 min., +1 hr, +3 hrs, +1 day |
| 3. Move earlier | 8-11 | -10 min., -1 hr, -3 hrs, -1 day |

Store each slot as kind (`AT_TIME` or `FROM_NOW`), a non-zero signed `amount`, and a unit (`MINUTE`, `HOUR`, `DAY`, `WEEK`). At Time stores minutes-from-midnight (0 to 1439) in `amount` and ignores unit. Do not flatten days into 1440 minutes: +1 day and +1 week keep local wall time (see DST). Reuse the At Time / From Now chooser idea from Spec 2's snooze settings, with the From Now wheel offering minute, hour, day and week, and both signs (Due's customise wheel). Amount 0 is invalid and is rejected.

**Button labels.** Copy Due 26.6.1: `+10 min.`, `+1 hr`, `+3 hrs`, `+1 day`, and the matching minus labels (`-10 min.`, `-3 hrs`). The 10-minute slots take a full stop. The 3-hour slots use `hrs`. Other counts stay unpluralised hours and minutes without a stop (`+2 days`, `+2 weeks`, `+5 min`, `+2 hr`). At Time follows the device 12/24-hour setting and the device locale: `9:30 am`, `12:00 pm`, `6:30 pm`, `10:00 pm` in 12-hour; `09:30`, `12:00`, `18:30`, `22:00` in 24-hour. Leave the Spec 2 snooze labels (`+1 hour`, `+3 hours`) alone.

**Date banner and time picker.** The banner above the grid always shows the draft due (local date and time, plus a short relative phrase such as "in 18 hours" or "1 hour ago"). It turns red when the draft is due or overdue (`dueAt <= now`). Tapping the banner opens the existing date and time pickers. A picker change updates the draft and the banner; the next Quick Times tap uses that new draft as its base. The editor time picker must follow the device 12/24-hour setting (it is currently hardcoded to 24-hour; fix that here). The separate Pick date / Pick time buttons may stay as a second way in, or be folded into the banner. New reminders keep today's starting draft of now plus 5 minutes (already in the form). Quick Times then apply from that draft.

**Postpone behaviour.** Default is **Auto**, stored in `SettingsStore` with the twelve slots. When the draft is not overdue, adjustments always count from the draft due. When it is overdue (`due-kb.txt`):
- **Auto:** apply from the due date whenever that result is after now; otherwise apply from now. Due's example: due 2:30 pm, now 2:43 pm, +1 hr gives 3:30 pm, +10 min gives 2:53 pm.
- **From due date:** always from the due date, even if the result is still in the past. The user can tap again. Same example: +1 hr gives 3:30 pm, +10 min gives 2:40 pm (still overdue).
- **From now (if overdue):** always from now while overdue. Same example: +1 hr gives 3:43 pm, +10 min gives 2:53 pm.

A minus adjustment can land in the past. That is allowed: the banner turns red, and Save is a Spec 1 missed fire (fire now, then nag from the new anchor) if the saved `dueAt` is not after now.

**Applying an adjustment.** For `MINUTE` and `HOUR`, add the duration to the base instant (`ZonedDateTime.plusMinutes` / `plusHours` in the device zone). For `DAY` and `WEEK`, add calendar days (`plusDays(amount)` or `plusWeeks(amount)`) and keep the local wall time. That matches Due's note that adding days should "keep its original time of day". This is deliberately not Spec 2's snooze rule, where +1 day is 24 hours from now.

**DST.** Computations use the device time zone. +1 day / +1 week keep wall time across a transition. +1 hr / +10 min are elapsed durations. If an At Time local time does not exist (spring-forward gap), use java.time's default resolver (the next valid local time). If it is ambiguous (fall-back overlap), use the earlier offset. Perth has no DST, so unit tests must cover this, and any phone check temporarily uses a zone that does (Australia/Sydney), then restores automatic time zone.

**Long-press, this tap only.** Postpone behaviour is the default for every adjustment tap. Touch and hold an adjustment for **From now** or **From due date** for that tap only; the setting does not change. Touch and hold an At Time for **Today**, **Tomorrow**, or **Due date** at that time, so the user can pick 9:30 am tomorrow when 9:30 am today is still ahead. Copy Due.

**Settings.** A Quick Times section in the test harness: Postpone behaviour, and Customise times (the 12-button grid; tap a slot, then At Time or From Now and the wheel). Persist in `SettingsStore` the same way as Spec 2's snooze options (`commit`, so a process kill right after a debug configure still sees the new values). A device-protected mirror is not required in this PR, because the grid is only used in the editor after unlock. Missing keys stay on the factory defaults.

**Debug hooks** (debug build only, same exported receiver):
- `.debug.CONFIG_QAT` with `slot` (0 to 11), `kind` (`AT_TIME` or `FROM_NOW`), `amount` (minutes-from-midnight for At Time; signed count for From Now), and `unit` (`MINUTE`, `HOUR`, `DAY`, `WEEK`) for From Now.
- `.debug.CONFIG_POSTPONE` with `value` (`AUTO`, `FROM_DUE`, or `FROM_NOW`).
- `.debug.DUMP_QAT` logs every slot, the postpone setting, and the labels, for example `DoAlarm qat-config postpone=AUTO slot=0 kind=AT_TIME amount=570 label=9:30 am`.
- `.debug.APPLY_QAT` with `id`, `slot`, optional `count` (default 1, for stacking), and optional long-press extras `from` (`NOW` or `DUE`) or `day` (`TODAY`, `TOMORROW`, or `DUE`). Reads the reminder's current `dueAt` as the draft base, applies the tap `count` times, writes `dueAt`, reschedules, and logs `DoAlarm qat id=<id> newDue=<ms> slot=<n>` (no title). This write-through is a test hook; the real editor still waits for Save.
Keep `.debug.ADD`, `.debug.DONE`, `.debug.SNOOZE` and `.debug.CONFIG_SNOOZE`. The release build still must not contain the receiver (Spec 1 AT-17).

**UI.** Still a test harness. Add the grid, the banner, the settings section and the long-press menus to the existing form. No design polish.

## 4. Out of scope
The same grid in the expanded notification, Custom Snooze and "Snooze to..." (Spec 2 reserved these for Spec 3; they stay a later spec so this PR stays tight; the settings stored here are what that spec will reuse). The "How to Use Quick Times" tutorial in Due's more menu. Repeats, including how repeating reminders keep local wall time across zones (Spec 1's forward reference was for repeats, not this grid). Natural-language add, including "Set to ..." on the first button (Spec 5). Alert levels, widgets, tiles, Wear OS, timers, quiet hours, the health check, paywall, backup, Open Link, and any design polish. Changing `setAlarmClock()` or the exact-alarm permission plan. Changing the three notification snooze options.

## 5. Acceptance tests (Mark)
Setup, package, tolerance and `.debug.ADD` / `.debug.DONE` are as in Spec 1. New: `adb shell am broadcast -a com.evansappstudio.doapp.debug.APPLY_QAT -n com.evansappstudio.doapp/.debug.DebugReceiver --el id <id> --ei slot 5`. Same pattern for `CONFIG_QAT`, `CONFIG_POSTPONE` and `DUMP_QAT`. Capture `adb logcat -s DoAlarm`. Tests marked **human** need a person on the add/edit screen; the rest should be scriptable over adb. Restore factory slots, Auto postpone, and automatic time zone before handing the phone back.

1. **Factory grid and defaults.** Fresh settings (or after a restore). `.debug.DUMP_QAT` shows Auto and the twelve factory slots and labels above. **Human:** open add or edit; the 4 by 3 grid sits under the date banner, all twelve labels are readable and tappable at the default font on the S24+.

2. **At Time: due date, roll forward, stay today.** Add a reminder due tomorrow at 08:00 local. `APPLY_QAT` slot 0 (9:30 am): `newDue` is tomorrow 9:30 am, not today. Add one due today at 08:00. After 9:30 am, slot 0 moves to tomorrow 9:30 am. Before 9:30 am, slot 0 stays today 9:30 am. `dumpsys alarm` and the list match `newDue`.

3. **Jump then nudge.** Add one due today, at a time where 6:30 pm today is still ahead (or due tomorrow morning). Apply slot 2 (6:30 pm) then slot 5 (+1 hr): `newDue` is 7:30 pm on that same date.

4. **Stacking.** Apply slot 4 (+10 min) with `count` 2: `newDue` is the base plus 20 minutes (from the due date if not overdue). One +10 min then another +10 min, as two broadcasts, must match.

5. **Postpone Auto.** Add one due at 2:30 pm with nag off, then (after 2:43 pm, or with a debug add already in the past) apply the Due example at now = 2:43 pm: slot 4 (+10 min) gives 2:53 pm; a fresh copy, slot 5 (+1 hr) gives 3:30 pm. Log and list match.

6. **From due date and From now (if overdue).** Same 2:30 / 2:43 setup. `CONFIG_POSTPONE` `FROM_DUE`: +10 min gives 2:40 pm (still past); +1 hr gives 3:30 pm. `FROM_NOW`: +10 min gives 2:53 pm; +1 hr gives 3:43 pm. When the reminder is not overdue, both modes still count from the due date. Restore Auto afterwards.

7. **Customise a slot.** `CONFIG_QAT` slot 0 to `AT_TIME` 08:00 (`amount` 480). `DUMP_QAT` label follows 12/24-hour. Apply slot 0: `newDue` is 8:00 am on the due date, or the next 8:00 am if that has passed. Then set slot 4 to `FROM_NOW` `amount` -30 `unit` MINUTE; apply subtracts 30 minutes from the base. Restore factory slots afterwards.

8. **Settings persist.** Set a custom slot and postpone `FROM_NOW`, `adb shell am kill com.evansappstudio.doapp`, relaunch: `DUMP_QAT` still shows both, and test 7 still applies the custom slot.

9. **Banner and picker. Human.** The banner shows the draft due and turns red when it is due or overdue. Tap the banner (or the existing pickers): change the date or time, the banner updates, then a Quick Times tap applies from that new draft. A Quick Times tap updates the banner at once and does not save. Leave without Save: the stored due is unchanged.

10. **Save reschedules.** Edit a reminder, apply a Quick Times tap in the UI (or `APPLY_QAT`), then Save if the UI was used: the notification is dismissed if it was showing, `dumpsys alarm` is at the new `dueAt`, and it fires within tolerance.

11. **12/24-hour locale. Human.** With the device in 12-hour mode, At Time labels look like `9:30 am`. Switch the system to 24-hour: they become `09:30`, `12:00`, `18:30`, `22:00` (or the locale equivalent, no am/pm), and the editor time picker is in 24-hour mode. Switch back when done. Adjustment labels do not change.

12. **DST.** Unit tests required, because Perth has no DST. In Australia/Sydney (or any zone with DST): +1 day (and +1 week) across a transition keep the local wall time; +1 hr and +10 min are elapsed durations; At Time on a gap date lands on the next valid local time, and on an overlap date uses the earlier offset. Optional phone check: temporarily set the zone to Australia/Sydney, `APPLY_QAT` a +1 day across the transition, then turn automatic time zone back on. Do not leave the phone in the wrong zone. Skipping the phone check is allowed if the unit tests pass; mark the phone part NOT RUN.

13. **Large font fit on the S24+. Human / RECORD.** At the default font, all twelve buttons fit as a 4 by 3 grid and are readable and tappable. At the next font step up, screenshot and record wrap or clip. Large-font wrap is not a FAIL on its own if the default font passes. Do not drop buttons.

14. **Long-press. Human,** with debug extras for the maths. Touch and hold +1 hr: From now and From due date appear; choosing one applies only that tap, and Postpone behaviour is unchanged. Touch and hold 9:30 am: Today, Tomorrow and Due date at 9:30 am appear. `APPLY_QAT` with `from` or `day` must match those choices (for example `day=TOMORROW` on slot 0 sets tomorrow 9:30 am even when today 9:30 am is still ahead).

15. **Minus into the past.** A reminder due in 5 minutes, `APPLY_QAT` slot 9 (-1 hr): `newDue` is in the past, the list shows it overdue, and on Save (or after the write-through hook) it is a Spec 1 missed fire: a fire now, then nag from that new anchor if nag is on.

16. **CI green.** Unit tests cover At Time (due date, roll forward, stay today), stacking, the Auto / From due date / From now table (Due's 2:30 pm / 2:43 pm example), day and week wall time across DST, hour and minute durations, minus into the past, and long-press `from` / `day` overrides. The release build contains no debug receiver.

## 6. Risks
- Twelve buttons at a large font on a 4-column row may clip on the S24+. Default-font fit is the pass; large font is recorded, as in Spec 2.
- DST cannot be proved in Perth without a temporary zone change. Unit tests carry that claim; a phone check must restore automatic time zone.
- `APPLY_QAT` writes through, unlike the editor. A leftover hook in a run could surprise a later test; restore factory slots and mark leftover reminders Done.
- Minus values and From due date can save a past `dueAt`. That is intended, but it will fire immediately, so tests should use nag off unless they mean to check the missed-fire path.

## 7. Gareth calls
Defaults Jim should build to, unless Tim walks them back:

1. **Factory slots are Due's screenshot:** 9:30 am, 12:00 pm, 6:30 pm, 10:00 pm, +10 min., +1 hr, +3 hrs, +1 day, -10 min., -1 hr, -3 hrs, -1 day.
2. **+1 day and +1 week keep local wall time** when applied to a due date. Due's postpone help says adding days should keep the original time of day. This is not Spec 2's snooze +1 day (24 hours from now).
3. **At Time lands on the current due date first,** and only rolls forward if that instant is not after now.
4. **Postpone default is Auto,** with Due's 2:30 pm / 2:43 pm table as the fixture.
5. **Quick Times labels use Due's min / hr / day / week wording.** Spec 2 snooze labels stay as they are.
6. **12/24-hour labels and the editor time picker follow the device setting.**
7. **Long-press one-shot overrides ship in this PR,** because they are how Due picks tomorrow when today is still ahead.
8. **The notification grid stays out of this PR,** even though Spec 2 reserved it for Spec 3, so this stays tight. Settings are stored so a later spec can reuse them.
9. **New reminders still start at now plus 5 minutes.** The research does not give Due's factory draft due.
10. **DST gaps and overlaps use java.time's default resolver** (gap: next valid local time; overlap: earlier offset). Due does not document this.
