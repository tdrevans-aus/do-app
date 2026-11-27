# Do — Spec 8 manual checklist (Tim or Gareth present)

Device: Samsung Galaxy S24+, serial `R5CXC0FWWVN`. Build: USE debug APK at `4f5cf2e` (`4f5cf2e1fc515a29603c2c7d7c6a3910c723d6f2`). Times are Australia/Perth (AWST).

Do these after (or alongside) `run-spec-08.ps1`. The runner already covers the scriptable ATs (quiet-window fires and dumps, Replace vs Merge of quiet keys, the repeating cap, following DND via the real or `simDnd` path, and the Critical pause setting) and does **not** change the PIN, the clock, or the time zone, never force-stops the app, and never starts a real Play purchase. The runner's `finally` restores leftover `T_s4_*`–`T_s8_*` / `T_off*` reminders (Repeat Off first), quiet hours and `simDnd real`, the fake unlock state, snooze options this run changed, real DND only if this run turned it on from ALL, and the scheduled Sleeping rule from the start-of-run snapshot.

Copy this file to `C:\Users\tdrev\do-qa\spec-08-manual-checklist.md` if you want it next to the runner.

Starting notes:

- Debug starts **unlocked** through `FakeUnlockBilling`. Lock the fake when you need the repeating cap or the unlock screen.
- A WEEK rule from the due date needs a weekday (Spec 4). The cap tests send `weekDays=MON` with `unit=WEEK`, so the repeating cap is what blocks, not the weekday parser.
- Do Not Disturb uses `cmd notification set_dnd` only when the filter is ALL at the start and the command changes the filter within 10 seconds. Otherwise the runner records `DND real path unavailable` or `DND on at start, simulated` and uses `simDnd`. It never turns Do Not Disturb off when it was already on. Each Do Not Disturb result records `path=real` or `path=sim`.
- Score app behaviour from logcat and dumps, not from whether the Sleeping rule silenced a Standard first fire. Tim's Sleeping window is 22:00–07:15; a 07:00 quiet-hours resume can still land inside system DND until 07:15. That is the system, not a product defect.
- Never change the clock or time zone (AT-11 is unit only). Never force-stop or `am kill` during the scripted run: a dead process means a held nag waits for the next anchor, which makes AT-23 (and AT-24 mid-hold) flaky.

---

## Do-AT-05 — Prominent full-screen inside the window — MANUAL (human half)

The runner scripts the dump (`exempt=true`) and that the due fire has no `quiet-defer` line. While looking at the phone:

1. The **full-screen alarm** appears inside the quiet window (or the Spec 5 fallback if full-screen is not allowed).
2. Tap **Stop**.
3. The next nag comes after the interval, not at the window end.

Pass: the Prominent reminder is not paused by quiet hours; Stop returns it to the ordinary nag grid.

Screenshots: `Do-AT-05-prominent-fullscreen.png`.

---

## Do-AT-10 — Reboot inside the window — MANUAL

The runner does not reboot the phone. Do this after the scripted run, or in a quiet window you set yourself. N must be long enough to reboot.

1. With a Standard reminder whose nag is held to the window end (as in AT-03), **reboot** and do not unlock at first. The held nag fires at the window end, from the mirror, not at boot.
2. A Critical reminder with a nag due inside the window fires on its schedule after boot.
3. With following Do Not Disturb on and `dndHeld=true`, turn Do Not Disturb **off while the phone is off**. After boot the held nag fires within a minute of `onExternalClock`, not at the next anchor.

Pass: the held Standard nag waits for the window end (or, in the DND-off-while-off case, for `onExternalClock`); Critical is not held by the window; nothing fires at boot itself.

Do not change the clock or time zone.

---

## Do-AT-12 — Quiet hours Settings — MANUAL

1. Settings shows **Quiet hours** (off), **Starts 22:00** and **Ends 07:00** in the device time format, then **Pause nags while Do Not Disturb is on** (off) and **Critical alerts also pause during quiet hours** (off), with the subtitles and the caption.
2. Turning on the Do Not Disturb switch shows **no permission prompt**.
3. An end equal to the start is refused with **"Start and end cannot be the same."** and the old value stays.
4. Change the window and both switches, leave Settings and come back: the values are still there, and `DUMP_QUIET` matches.

Pass: the defaults, copy, refused equal start/end, and persistence match the spec. No notification-policy prompt.

Screenshots: `Do-AT-12-settings-defaults.png`, `Do-AT-12-same-start-end.png`, `Do-AT-12-persisted.png`.

---

## Do-AT-18 — Editor and paywall — MANUAL

Fake locked, two repeating reminders already open (the runner can leave this state if you run AT-14 first and then lock the fake again; a full run restores unlocked at the end):

1. In the editor, pick **Daily** on a third reminder and tap Save: the **unlock screen** opens. **Back** returns to the editor, and nothing was saved.
2. Typing **"Bins every Tuesday 7am"** in add and saving also opens the unlock screen.
3. Editing an existing repeating reminder's **time** saves normally.

Pass: a new repeat is paywalled; an edit of an already-repeating reminder is not.

Screenshots: `Do-AT-18-unlock-on-daily.png`, `Do-AT-18-unlock-on-parse.png`.

---

## Host / CI rows (no phone work in this list)

- **Do-AT-01**, **Do-AT-11**, and **Do-AT-19** are host / CI (unit tests, both flavours, release free of `SET_QUIET_HOURS` / `DUMP_QUIET` / `FakeUnlockBilling`, zone/clock/DST unit-only). Already covered by `qa/results-pr8-4f5cf2e-host.md`.
