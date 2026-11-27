# Brief: finish the Do Spec 8 phone tests (PR #8)

You're helping Tim Evans test "Do", his Android reminder app (package `com.evansappstudio.doapp`), on his Samsung Galaxy S24+ (adb serial `R5CXC0FWWVN`), plugged into his Windows PC. Use Australian English, report times in AWST (Perth), and keep it short.

## Hard rules
- Don't merge, push, publish, buy or send anything. That includes Google Play and real purchases. This is a test run and a report, nothing else.
- Never touch the lock screen and never set a PIN. If the phone is locked, stop and ask Tim to unlock it.
- Leave Tim's real reminders alone, for example "Isabelle book list". The runner only touches rows named `T_s8_*`.
- Don't force-stop the app or change the phone's clock or time zone.
- Don't change `stay_on_while_plugged_in` (it's 15 and stays 15).

## Setup
1. Unzip this pack and copy `do-qa\run-spec-08.ps1` and `do-qa\lib\qa-runner-shared.ps1` into `C:\Users\tdrev\do-qa\` (and `C:\Users\tdrev\do-qa\lib\`), overwriting the old copies. Keep a `.bak` of each first.
2. Check `adb devices` lists `R5CXC0FWWVN` as `device`. If it doesn't, ask Tim to reconnect the cable and accept "Allow USB debugging".
3. Check that the PR #8 debug build (head `4f5cf2e`) is the one installed. The runner checks this too.

## Run
From PowerShell (about 20 minutes, keep the phone plugged in and unlocked):

```
powershell -NoProfile -File C:\Users\tdrev\do-qa\run-spec-08.ps1 -Ref 4f5cf2e -Only AT-14,AT-15,AT-16,AT-17,AT-20,AT-21,AT-22,AT-23,AT-24,AT-25,AT-26,AT-27,AT-28
```

To stop cleanly at any point, create the empty file `C:\Users\tdrev\do-qa\STOP-spec08`. The runner then skips the rest and restores the phone. Don't use Ctrl-Break, because last time that broke the restore.

## After the run, confirm the phone is back to normal
- DND is off: `adb shell settings get global zen_mode` returns `0`.
- Samsung's "Sleeping" rule is untouched (22:00 to 07:15).
- Do's quiet hours are back to off, 22:00 to 07:00, with both switches off.
- There are no live `T_s8_*` reminders left.
If any of these is wrong, fix it with the runner's restore or tell Tim exactly what's left.

## Already done (don't rerun)
AT-01 to AT-09, AT-11, AT-13 and AT-19 have passed. AT-09 was rescored as a pass, AT-01/11/19 were checked on the host and in CI, and AT-05 still has its hands-on part outstanding. The earlier cap failures (AT-14 to 17) were runner problems that Mark has since fixed. The cap tests now start from whatever's already on the phone, so Tim's real Daily reminder counts as one of the 2 repeating slots.

## Then the four hands-on checks with Tim
These are in `spec-08-manual-checklist.md`: AT-05 (Prominent screen), AT-10 (reboot), AT-12 (Settings) and AT-18 (editor and paywall). Talk Tim through each one and record pass or fail with what he saw.

## Report back
Write `results-pr8-4f5cf2e-phone-run3.md` next to the runner, then zip it together with the evidence folder the runner creates under `C:\Users\tdrev\do-qa\qa-evidence\`. Include:
- the run window in AWST
- PASS or FAIL for each AT, with a one-line reason for any FAIL and whether it's a bug in Do or in the runner
- the four hands-on results
- the restore checks above
Leave out any personal data, such as contacts or other notification content in dumpsys output.
Tim will give the file to Gareth, the Do product manager, who signs off the merge.
