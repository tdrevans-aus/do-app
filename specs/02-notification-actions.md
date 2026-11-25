# Spec 2: Notification actions

Owner: Gareth (PM). Build: Jim, via a cloud coding agent, one PR to `tdrevans-aus/do-app`. QA: Mark. Status: ready to build, 24 Nov 2026.
Sources: `DO-RESEARCH.md` s. 1.3 and 5.3; Due help in `src/due-kb.txt` (notification snooze, Auto Snooze, Quick Access Times). Rule: if in doubt, copy Due.

## 1. Goal
From the shade or the lock screen, a due reminder can be marked Done, snoozed with Due's one-tap choices, or opened for edit, even if the app process is gone. A snooze reschedules `dueAt` and re-arms the Spec 1 alarm engine, and nagging then continues from that new due time.

## 2. Starts from Spec 1
Package `com.evansappstudio.doapp`, minSdk 26, target and compile 36, Kotlin, Compose, Room via KSP, `setAlarmClock()`. Reuse `Scheduler`, `AlarmReceiver`, `DoneReceiver`, `ReminderNotifier`, the `reminder` table, the device-protected mirror (including `pendingDone`), and the debug receiver. This PR does not change exact-alarm mode, the alarm-clock path, or the Spec 1 Room columns.

The `setAlarmClock()` status-bar icon, left for Tim in Spec 1, is still open: Mark logs what it looks like on the phone, and Tim decides later. This spec does not switch scheduler APIs.

## 3. Scope
**Actions on each reminder notification.** Due's expanded notification offers Mark Done, three one-tap snooze options, and the Quick Access Times grid (s. 1.3; `due-kb.txt` 333-352). The grid is Spec 3. This spec ships Mark Done and the three one-tap snoozes. Tapping the notification body opens that reminder for edit.

Use the label **Mark Done**, not Done, to match Due (`due-kb.txt` 343). Keep the existing `DoneReceiver` path; only the visible label changes.

The three snoozes "act right away, like Mark Done. One tap snoozes the reminder" (`due-kb.txt` 350-352). Defaults are +1 hour, +3 hours and +1 day, all changeable (s. 1.3; `due-kb.txt` 351).

**What a snooze does.** Auto Snooze keeps re-alerting until the reminder is marked done, rescheduled, or nag is turned off (s. 1.3; `due-kb.txt` 53). A one-tap snooze is a reschedule: write a new `dueAt`, leave `isDone` false and `doneAt` null, dismiss this notification, cancel the current alarm, and schedule the next fire at the new `dueAt` through `Scheduler` (same `setAlarmClock()` path as Spec 1). Update the device-protected mirror so the new time survives reboot and direct boot. Log `DoAlarm snooze id=<id> newDue=<ms> option=<0-2>` (no title in the log).

After the snoozed fire, nag is anchored to the new `dueAt`, using the reminder's existing `nagMinutes`. If nag is off (`null`), it alerts once at the new time and then stays quiet, as in Spec 1. If the computed target is already past, treat it as a Spec 1 missed fire: fire "now", then continue the cadence from the new anchor.

**Computing the new `dueAt`.** Due lets each option be At Time or From Now (`due-kb.txt` 353-360).
- **From Now:** `newDue = now + amount`. The factory defaults are durations of 1 hour, 3 hours and 1 day, with 1 day meaning 24 hours from now (Gareth call: the help only says "+1 day" and "From Now", not "same clock time tomorrow").
- **At Time:** a clock time that jumps to the next occurrence of that time of day. The "jumps to the next one" wording is from Quick Access Times (s. 1.3), applied here because the snooze settings use the same At Time / From Now wheel (`due-kb.txt` 360). If that instant is not after now, add 24 hours until it is.

**Settings.** A Notification snooze screen in the test harness, matching Due's Settings > Notifications > Notification Snooze (`due-kb.txt` 354). Three rows; tap one to choose At Time or From Now and set the value. Store them in `SettingsStore`. When posting a notification, bake each option's kind and value into that action's `PendingIntent` extras, and use those extras at tap time so lock-screen and pre-unlock taps do not need credential-storage settings. If the user changes the options, rebuild any currently shown reminder notifications so labels and extras match (Gareth call: Due does not say when already-visible notifications update).

Button labels: From Now as `+1 hour`, `+3 hours`, `+1 day`, and `+N minute(s)` / `hour(s)` / `day(s)` for other values. At Time as the local time, for example `9:00 am` (Gareth call: the help does not give an At Time button label).

**Android's three-action limit (Gareth call).** Standard `addAction` only shows three actions, but Due shows Mark Done plus three snoozes. Use a custom expanded layout (`RemoteViews` / `DecoratedCustomViewStyle`) so the expanded shade and the lock screen show all four. If the compact row can only fit three system actions, show Mark Done and the first two snoozes; expanding reveals all four. Snooze actions go to a manifest-registered, `directBootAware` `SnoozeReceiver`, with `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT` and distinct request codes per reminder and option. Those receivers must not start an activity (Android 12 notification trampoline rule). Keep `VISIBILITY_PUBLIC`, as in Spec 1, so the actions can be used on the lock screen without opening the app.

**Lock screen and direct boot.** Mark Done and snooze must work from the lock screen, including before first unlock. Before unlock, Room is unavailable: snooze writes a pending snooze (the new `dueAt`) on the mirror, cancels and re-arms the alarm from the mirror, and dismisses the notification. `rescheduleAll()` after unlock applies it to Room, the same idea as `pendingDone`. If both a pending done and a pending snooze are set, done wins. Do not add a PIN on Tim's phone without his ok; lock-screen taps are human tests and may be marked NOT RUN.

**Open to edit.** Tapping the notification body opens that reminder in the existing add form, filled in, where title, due time and nag interval can be changed and saved. The Due notification article only describes expanding and tapping actions (`due-kb.txt` 340-352), so this is a Gareth call, taken from the product brief. The content intent must be an Activity `PendingIntent` (`doapp://open/<id>` already exists in Spec 1's notifier; `MainActivity` must honour it). Opening the editor does not mark done, does not snooze, and does not dismiss the notification (`setAutoCancel` stays false). Saving a new `dueAt` is a reschedule, same path as a snooze. Saving only title or `nagMinutes` updates the row and refreshes a visible notification; a `nagMinutes` change re-arms the next fire from the current `dueAt`. A list-row tap in the harness also opens the editor, as a convenience. A lock-screen body tap may require unlock first, which is expected.

**Grouping when several fire (Gareth call).** Due's help does not describe a stacked summary. s. 5.3 proposes "One notification updated in place, plus a summary such as '3 overdue: snooze all 1 h'". Keep one notification per reminder, updated in place (Spec 1, s. 5.3). When two or more reminder notifications are visible, put them in one group (`setGroup`) with a silent summary ("N overdue") and `GROUP_ALERT_CHILDREN`, so the summary does not sound on its own. The summary has a **Snooze all** action that applies snooze option 0 to every overdue reminder currently showing. No Done all (too easy to clear everything). When only one is showing, post no summary. Swiping the summary must not cancel the children's alarms; the children stay or are re-posted at the next nag.

**Process death.** Mark Done and snooze must work after the app process is gone. Manifest-registered receivers plus the baked `PendingIntent` extras are the mechanism. QA kills the process with `adb shell am kill com.evansappstudio.doapp` (not `force-stop`, which also drops alarms) after the notification is up, then fires the debug snooze or the real action.

**Debug hooks** (debug build only, same exported receiver as Spec 1):
- `.debug.SNOOZE` with extras `id` and `option` (0, 1 or 2), applying that configured choice.
- `.debug.CONFIG_SNOOZE` with `option`, `kind` (`FROM_NOW` or `AT_TIME`), and `value` (minutes for From Now; minutes-from-midnight, 0 to 1439, for At Time), then refresh any shown notifications.
Keep `.debug.ADD` and `.debug.DONE`. The release build still must not contain the receiver (Spec 1 AT-17).

**UI.** Still a test harness. Add the snooze settings screen and the edit path. No design polish.

## 4. Out of scope
The Quick Access Times grid, Custom Snooze and "Snooze to..." in the notification (Spec 3). Repeats (Spec 4). Natural-language add (Spec 5), including typing a time into the notification with `RemoteInput` (s. 5.3; that is StillDue, not Due). Alert levels (Spec 6). Open Link buttons and "Offer to Open Links" (s. 1.3; `due-kb.txt` 359-363). Widgets, tiles, Wear OS, timers, quiet hours, the health check, paywall, backup, and any design polish. Changing `setAlarmClock()` or the exact-alarm permission plan.

## 5. Acceptance tests (Mark)
Setup, package, tolerance and `.debug.ADD` / `.debug.DONE` are as in Spec 1. New: `adb shell am broadcast -a com.evansappstudio.doapp.debug.SNOOZE -n com.evansappstudio.doapp/.debug.DebugReceiver --el id <id> --ei option 0`. Capture `adb logcat -s DoAlarm`. Inspect notifications with `adb shell dumpsys notification --noredact`. Tests marked **human** need a person on the shade or lock screen; the rest should be scriptable over adb.

1. **Actions present.** Add T1, wait for the fire. The expanded notification shows Mark Done, +1 hour, +3 hours and +1 day. Automation checks action titles via `dumpsys notification`. **Human:** confirm the same four labels on the expanded shade and on the lock screen.
2. **Done still stops it.** Same as Spec 1 AT-5, including `.debug.DONE`. The button label is Mark Done. Notification gone, no T1 alarm, no further fires for 5 minutes, list shows it done.
3. **Snooze +1 hour (option 0).** After T1 fires, `.debug.SNOOZE` option 0. Notification dismissed. `DoAlarm snooze` logs a `newDue` of now plus 1 hour (within 2 s of the action, not of the original due). `dumpsys alarm` shows one alarm-clock alarm at that `newDue`. The list shows the new local time. No fire before `newDue`. It fires at `newDue` within tolerance.
4. **Nag continues from the new due.** T1 has nag 1. Snooze option 0. After the snoozed fire, it nags at +1, +2 and +3 min from the new `dueAt`, each within tolerance, one notification updated in place. It does not keep the old `dueAt` cadence.
5. **+3 hours and +1 day.** Same checks as test 3 for option 1 (now + 3 hours) and option 2 (now + 24 hours): the log, the list and `dumpsys alarm`. Do not wait for those fires.
6. **Custom From Now.** `.debug.CONFIG_SNOOZE` option 0 to `FROM_NOW` 15 minutes. A fired T1 then option 0 snoozes to now + 15 min, and the visible label is +15 minutes.
7. **Custom At Time.** Configure option 1 to `AT_TIME` 09:00 (`value` 540). Snooze option 1 sets `dueAt` to the next 9:00 am local: today if that is still ahead, otherwise tomorrow. `dumpsys alarm` matches. Also cover the already-past case (snooze after 9:00 am, or use a second time that has already passed today).
8. **Nag off.** Add with `nagMinutes 0`, fire, then snooze option 0: one alarm at `newDue`, it fires once, then no alarm and no second notification.
9. **Swipe still does not stop it.** Unchanged from Spec 1 AT-4, and still true after a snooze has fired (swipe the snoozed fire; it is back at the next nag).
10. **Open to edit. Human** for the shade tap; the content intent may also be started over adb. Tap the notification body: the editor opens on that reminder, the notification stays, and nagging continues if you leave without saving. Change the due time to 2 minutes from now and save: notification dismissed, alarm at the new time, it fires within tolerance.
11. **Lock screen. Human.** Do not add a PIN without Tim's ok. From the lock screen, Mark Done works without opening the app (Spec 1 AT-5). A separate reminder: a one-tap snooze works without opening the app, the notification goes, and after unlock the list and `dumpsys alarm` show the new due. A body tap may prompt unlock, then opens the editor. If Tim has no lock and does not want one, mark this NOT RUN; do not fail the build for that alone.
12. **Direct boot snooze. Human,** same PIN rule as Spec 1 AT-9. Reboot, do not unlock, wait for the fire on the lock screen, then snooze it. The next alarm-clock alarm is at the snoozed time (`dumpsys` works before unlock). Unlock: Room matches, and the reminder is not done. If this cannot be run without creating a PIN, mark it NOT RUN; it stays an outstanding device check.
13. **Process death.** After T1 fires, `adb shell am kill com.evansappstudio.doapp`. Confirm the process is gone. `.debug.SNOOZE` option 0 (or the real action) still dismisses, writes the new `dueAt`, and arms the alarm. Repeat with `.debug.DONE`. Do not use `am force-stop`.
14. **Grouping.** Add 3 reminders due in the same minute. All three notifications show, in one group, with a silent summary "3 overdue" that did not add a fourth sound at fire time. Snooze all (option 0) dismisses all three, and each is re-armed at now + 1 hour. After two are marked done or snoozed individually, the remaining one is ungrouped (no summary). Ten at once (Spec 1 AT-16) still all fire; they group, and the summary stays silent.
15. **Settings persist.** Set a custom option, kill the app process, relaunch: the custom option is still there and test 6 still passes.
16. **CI green.** Unit tests cover From Now, At Time (today and next day), and nag re-anchor after a snooze. The release build contains no debug receiver.

## 6. Risks
- Custom `RemoteViews` on OEM shades (Samsung, Xiaomi) can crop or drop buttons. Mark screenshots the expanded shade and lock screen on the real phone. If four buttons will not fit on the S24+ shade or lock screen, ship three standard system actions instead (Mark Done, snooze option 0, snooze option 1), with option 2 offered in the editor. Gareth's call, no need to wait on Tim; note it in the PR.
- Lock-screen action visibility varies by OEM and lock type. `VISIBILITY_PUBLIC` is required, not sufficient on every device.
- Group summary "Snooze all" is a Do addition, not a documented Due control. If it feels heavy-handed we can drop it and keep visual grouping only.
- Notification trampolines: a receiver that starts the editor will fail on Android 12+. The body tap must stay an Activity `PendingIntent`.
- Direct-boot pending snooze has to compose with `pendingDone` and with Spec 1's unlock reconcile, or a pre-unlock snooze could be lost or overwritten.

## 7. Gareth calls
Defaults Jim should build to, unless Tim walks them back:

1. **+1 day is 24 hours from now,** not the same clock time tomorrow. The help only says "+1 day" / "From Now".
2. **At Time jumps to the next occurrence** of that time of day, using the Quick Access Times rule (s. 1.3) because the snooze settings use the same wheel.
3. **Already-visible notifications are rebuilt** when the three snooze options change, so labels and extras stay in sync.
4. **At Time button label** is the local time, for example `9:00 am`.
5. **Custom expanded layout** so Mark Done and all three snoozes are visible, because Android's three-action limit cannot copy Due with standard actions.
6. **Tap opens that reminder's editor** and does not dismiss the notification. Saving a new due time is a reschedule. The Due article does not describe a body tap.
7. **Visual grouping plus a silent "N overdue" summary,** with **Snooze all** applying option 0, and no Done all. Grouping is not documented in Due; the summary idea is from s. 5.3.
