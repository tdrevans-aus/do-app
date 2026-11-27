# Spec 8 manual checklist

The phone runner is `qa/run-spec-08.ps1`. It does not replace the Spec 1 to 7 runners. It never sets a PIN or a lock screen, never changes the clock or time zone, never force-stops the app, and never starts a real purchase.

Scripted rows are decided by the runner from logcat and dumps. These rows need a person:

- **AT-05 screen.** The runner checks the dump (`exempt=true`) and that the due fire has no `quiet-defer` line. On the phone, the full-screen alarm appears inside the quiet window (or the Spec 5 fallback). Tap Stop. The next nag comes after the interval, not at the window end.
- **AT-10.** With a Standard nag held to the window end, reboot and do not unlock at first. The held nag fires at the window end, from the mirror, not at boot. A Critical nag due inside the window fires on its schedule after boot. With following Do Not Disturb on and `dndHeld=true`, turn Do Not Disturb off while the phone is off. After boot the held nag fires within a minute of `onExternalClock`, not at the next anchor.
- **AT-11.** Zone, clock and DST are unit tests only. Do not change the phone's clock or zone.
- **AT-12.** Settings shows Quiet hours (off), Starts 22:00 and Ends 07:00 in the device time format, then "Pause nags while Do Not Disturb is on" (off) and "Critical alerts also pause during quiet hours" (off), with the subtitles and the caption. Turning on the Do Not Disturb switch shows no permission prompt. An end equal to the start is refused with "Start and end cannot be the same." and the old value stays. Leave Settings and come back: the values are still there, and `DUMP_QUIET` matches.
- **AT-18.** Withdrawn 27 Nov 2026 with the repeating cap. Not run.
- **AT-01 and AT-19.** Unit tests, `assembleDebug`, `lintDebug`, and `checkAlarmManifests` in USE and SCHEDULE are the CI gates. The release APK has no debug receiver, no `SET_QUIET_HOURS` or `DUMP_QUIET`, no fake billing, and `do_quiet.xml` is in both backup rule files under `device_sharedpref`.

Runner notes:

- Do Not Disturb uses `cmd notification set_dnd` only when the filter is ALL at the start and the command changes the filter within 10 seconds. Otherwise the runner records `DND real path unavailable` or `DND on at start, simulated` and uses `simDnd`. It never turns Do Not Disturb off when it was already on. Each Do Not Disturb result records `path=real` or `path=sim`.
- The Sleeping rule is recorded and restored only if this run snoozed it. This run does not snooze it.
