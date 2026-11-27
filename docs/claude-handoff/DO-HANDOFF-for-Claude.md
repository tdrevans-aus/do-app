# Do: handoff for Claude

A self-contained brief so Claude can take over development of **Do**, Tim Evans's Android nag-until-done reminder app (Evans App Studio). Written 27 Nov 2026, Australia/Perth (AWST). Facts below were read from the public GitHub repo, local specs and QA results, and prior product decisions. Anything not verified is marked **unknown/confirm**. Do not invent data.

**Repo:** https://github.com/tdrevans-aus/do-app  
**Package / applicationId:** `com.evansappstudio.doapp`  
**Language:** Kotlin, Jetpack Compose (Material 3), Room via KSP, Gradle Kotlin DSL, single `:app` module, manual `AppContainer` (no DI framework).  
**SDK:** compileSdk 36, targetSdk 36, minSdk 26. CI uses Temurin JDK 21; the app compiles to JVM 17. `versionCode` 1, `versionName` "1.0" as of this handoff.

This pack also has `BRIEF-for-Claude.md`, which is the earlier, narrower brief for finishing the Spec 8 phone tests. Use that for the rerun steps. Do not duplicate it here.

---

## 1. What Do is, and how it is positioned

Do is a Due-style reminder app for Android: a reminder fires on time and keeps nagging until it is marked Done. It is a product of Evans App Studio, and it is separate from Tim's other apps (Next Train, Mr Night).

Two standing product rules:

- **Copy Due.** The working rule from the start of the specs (24 Nov 2026) is: be as close to Due as possible; if in doubt, copy.
- **Last mile for an AI assistant.** Tim's later voice note, developed in the 26 Nov 2026 design paper, is that Do should be the last mile an assistant cannot do itself: ring through Do Not Disturb when it matters, nag until the user acts, keep quiet hours, work offline, and (later) tell the assistant what happened. Capturing a task is not the moat; enforcement on the phone is.

v1 is local-first and needs no account. The UI started as a test harness in Spec 1 and now has a list, editor, notification actions, Quick Times, repeats, natural-language add, alert levels, a health-check screen, unlock/paywall, and local backup. Visual polish is not finished.

---

## 2. Repo and access

- **GitHub:** https://github.com/tdrevans-aus/do-app (public, default branch `main`).
- **Clone:** `git clone https://github.com/tdrevans-aus/do-app.git`
- **Branch model:** one small spec PR at a time, into `main`. Repo settings allow squash merge only (merge commits and rebase merges are off). Branches are deleted on merge. Squash merge is allowed only after Mark's QA pass, Gareth's (PM) sign-off, green CI, and **Tim's explicit OK**. Do not merge, push, comment, or change the remote yourself unless Tim has named that action.
- **Current `main`:** merge commit `4cf0380e45b07b1f5cb7061f783ea6fc5ce1f6ab` ("Do Spec 7: one-time unlock and backup (#7)"), 26 Nov 2026, 01:39 AWST. Specs 1 to 7 are merged. Spec 8 is open (see below).
- **Open branch:** `do/spec-8-quiet-hours-and-repeating-cap`, head `4f5cf2e1fc515a29603c2c7d7c6a3910c723d6f2`, PR https://github.com/tdrevans-aus/do-app/pull/8. CI is green (`build` pass). `mergeable_state` was `clean` when checked on 27 Nov 2026. **Not merged.** Planned squash title, if Tim later says to merge after tests pass: `Do Spec 8: quiet hours and repeating cap (#8)`.

### Build and CI

CI workflow: `.github/workflows/ci.yml`. It runs only on `pull_request`, on `ubuntu-latest`, and runs:

```
./gradlew assembleDebug testDebugUnitTest lintDebug
```

That is the USE exact-alarm variant. A `checkAlarmManifests` task is wired in as a dependency: the release APK must not contain `DebugReceiver` or the debug hooks, and exact-alarm permissions must match `exactAlarmMode`.

Exact-alarm modes (same APK path; install the one you just built):

```bash
# USE (default). Manifest has USE_EXACT_ALARM, and SCHEDULE_EXACT_ALARM only with maxSdkVersion=32.
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# SCHEDULE fallback. Manifest has SCHEDULE_EXACT_ALARM with no maxSdk, and no USE_EXACT_ALARM.
./gradlew :app:assembleDebug -PexactAlarmMode=SCHEDULE
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Never commit a keystore, signing passwords, or API keys. Release signing, if used, reads an untracked `keystore.properties`. This work so far has been debug builds.

### Package layout on `main` (under `app/src/`)

| Area | Role |
| --- | --- |
| `com.evansappstudio.doapp` | `DoApp`, `MainActivity`, `AppContainer`, `UserUnlock` |
| `...alarm` | Scheduler, `AlarmScheduler` (`setAlarmClock()`), receivers, nag math, repeats, alerts, health |
| `...notify` | `ReminderNotifier` |
| `...data` | Room (`AppDatabase`, `Reminder`, `RepeatRule`, `SettingsStore`). Schema is version 4 on `main`. Spec 8 does not bump it. |
| `...ui` | List, editor, Quick Times, repeats, snooze settings, health check, backup, unlock |
| `...billing` | `FreeTier`, entitlement, `UnlockBilling`. Debug: `FakeUnlockBilling` (starts unlocked, never talks to Play). Release: `PlayUnlockBilling` (product id `do_unlock`). |
| `...backup` | Local backup codec and store |
| `...debug` (debug source set only) | `DebugReceiver` |

Spec 8 adds `QuietHours`, `QuietHoursStore` (`do_quiet`), `DndState`, `AlarmPorts`, and debug `SET_QUIET_HOURS` / `DUMP_QUIET`, plus `DndDebugOverride`.

### Debug hooks (debug builds only)

Broadcasts go to `com.evansappstudio.doapp/.debug.DebugReceiver`. Release must not contain these. The runners use them to add, edit, dump and clean test rows. Do not use them on Tim's real reminders.

On `main` (and PR #8, plus the two quiet hooks): `ADD`, `DONE`, `SNOOZE`, `CONFIG_SNOOZE`, `CONFIG_QAT`, `CONFIG_POSTPONE`, `DUMP_QAT`, `APPLY_QAT`, `SET_REPEAT`, `DUMP_REPEAT`, `SHIFT_DUE`, `CONFIG_REPEAT_PROMPT`, `PARSE`, `SET_ALERT_LEVEL`, `CONFIG_PARSE`, `CONFIG_ALERT_DEFAULT`, `DUMP_ALERT`, `DUMP_HEALTH`, `TEST_HEALTH`, `NOTE_LATE`, `FAKE_PURCHASE`, `DUMP_UNLOCK`, `EXPORT`, `IMPORT`, `DUMP_BACKUP`, `CONFIG_BACKUP_FOLDER`, `AUTO_BACKUP`, `SET_TITLE`. PR #8 also has `SET_QUIET_HOURS` and `DUMP_QUIET`. `DUMP_UNLOCK` / `FAKE_PURCHASE` on PR #8 also log `repeating=` and `repeatCap=`.

Example (Spec 1 add):

```bash
adb shell am broadcast -a com.evansappstudio.doapp.debug.ADD -n com.evansappstudio.doapp/.debug.DebugReceiver --es title T1 --ei inSeconds 120 --ei nagMinutes 1
```

### Specs in the repo (gap)

On `main`, `specs/` has Specs 2 to 7 plus `due-screens-batch2-notes.md`. **Spec 1's markdown is not in the GitHub repo** (confirmed 404 for `specs/01-alarm-engine-nag-loop.md` on `main`). Spec 8's markdown is on the PR #8 branch only, as `specs/08-quiet-hours-and-repeating-cap.md`. A local Spec 1 copy exists with the team if you need it; ask Tim rather than reconstructing it.

Research papers (`DO-RESEARCH.md`, 24 Nov 2026; `DO-AGENTIC-DESIGN-PAPER.md`, 26 Nov 2026) are **not** in the GitHub repo. Short summaries are in sections 5 and 6. Ask Tim if you need the full files.

---

## 3. Built so far

Times below are converted from GitHub UTC to Australia/Perth (AWST, UTC+8).

| Spec | Title | PR | Status | Outstanding checks |
| --- | --- | --- | --- | --- |
| 1 | Scaffold, alarm engine and nag loop | [PR #1](https://github.com/tdrevans-aus/do-app/pull/1) | Merged `24deefe9f583101070916b7142eecc718aaac9d2`, 25 Nov 2026, 08:24 AWST. QA: conditional pass (scripted FAILs were runner bugs, rescored pass). | Open manuals / device rows include shade or lock Done, reboot / direct boot (no PIN unless Tim approves), 60-minute nag wait, clock change via Settings, package replace, SCHEDULE / exact-alarm deny, notification deny, DST. AT-08 Doze was recorded as invalid (device did not enter deep idle), not a product fail. |
| 2 | Notification actions | [PR #2](https://github.com/tdrevans-aus/do-app/pull/2) | Merged `ae274e308af10b821e287598756bf447e450f316`, 25 Nov 2026, 12:00 AWST. QA: conditional pass. | Open manuals include shade/lock layout, the AT-10 human path, Snooze all from the shade, process-death, and AT-09 / 11 / 12. |
| 3 | Quick Times grid | [PR #3](https://github.com/tdrevans-aus/do-app/pull/3) | Merged `ef930ae830230085b205eb4e7da7b2961fc365f3`, 25 Nov 2026, 17:50 AWST. QA: conditional pass. Scripted phone ATs passed. | Human: AT-01 visual/layout, AT-09, AT-11, AT-13 (large font RECORD), AT-14 menus. |
| 4 | Repeating reminders | [PR #4](https://github.com/tdrevans-aus/do-app/pull/4) | Merged `72b5fc69a1d9e09985a2f41fb3eb1046b7af7854`, 26 Nov 2026, 01:07 AWST. QA: scripted pass, conditional on manual. | Human: AT-01, AT-10, AT-12 reboot half, AT-13 phone DST (optional; default NOT RUN in Perth), AT-15 direct boot (no PIN). Saturday/Sunday Week Starts On was host-only. |
| 5 | Natural-language add and alert levels (combined in one PR) | [PR #5](https://github.com/tdrevans-aus/do-app/pull/5) | Merged `3564a94a44aef0457f59dba93fbbeefd99f48cb1`, 26 Nov 2026, 01:13 AWST. QA: scripted pass, conditional on manual. | Human: AT-04, AT-05, AT-06 editor half, AT-08, AT-09 sound, AT-10, AT-12, AT-13 unlocked half, AT-14 reboot (no PIN). AT-06/07/14 process-death stayed RECORD (`am kill` did not drop the pid). |
| 6 | Health check and warning badge | [PR #6](https://github.com/tdrevans-aus/do-app/pull/6) | Merged `1ce56d46ff0c9b1d25dd6dcf6a219a2b58d3d4cc`, 26 Nov 2026, 01:18 AWST. GitHub squash subject is "Do Spec 6: battery health check (#6)". QA: scripted pass, conditional on manual. | Human: AT-03 badge, AT-04 Fix, AT-07 Fix, AT-09 Fix, AT-10 badge, AT-11 button, AT-12, AT-13, AT-15 reboot (no PIN). A later note asked for an AT-06 runner rerun after a predicate fix; **unknown/confirm** whether that rerun was done on the merge commit. |
| 7 | One-time unlock and local backup | [PR #7](https://github.com/tdrevans-aus/do-app/pull/7) | Merged `4cf0380e45b07b1f5cb7061f783ea6fc5ce1f6ab`, 26 Nov 2026, 01:39 AWST. QA on pre-merge heads: scripted bar passing so far, still conditional. | Human still outstanding: AT-04 unlock screen / price / copy (no real billing), AT-10 badge / Backup-row copy / Fix, AT-12 SAF pickers. Last phone notes also wanted a rerun of AT-08, AT-09 and AT-11 on then-current heads; **unknown/confirm** whether those were re-run on the merge commit. |
| 8 | Quiet hours and the repeating cap | [PR #8](https://github.com/tdrevans-aus/do-app/pull/8) | **Open.** Head `4f5cf2e`. CI green. Host pass. Phone run aborted, not a product pass or fail. | See the exact test state below. Do not merge until the rerun and human ATs are done, CI is still green, and Tim says to merge. |

There are no PRs numbered above 8. Only PR #8 is open.

### What each merged spec actually built

- **Spec 1.** Project scaffold and CI. Room reminder (title, `dueAt`, nag interval 1/5/10/15/30/60 or off, default 5 minutes). One `setAlarmClock()` alarm per active reminder. Nag times are `dueAt + k * interval` (no drift). Missed fire: alert now, then rejoin the anchor. Survives reboot, clock and time-zone change, and app update via reschedule receivers. Permission banners. Debug add/done. Unit tests.
- **Spec 2.** Done, preset snoozes and "Snooze to..." on the notification. Snooze settings. Edit path.
- **Spec 3.** Customisable 12-button Quick Times grid with stacking taps. Factory labels include +10 min. and +3 hrs. Week Starts On defaults to Monday (also drives later weekday pickers).
- **Spec 4.** Repeats: daily, weekdays, weekly, monthly, yearly, and custom every-N including minutes and hours; chosen weekdays; nth or last weekday; last day; from completion. Mark Done advances one occurrence; snooze moves only this one. Repeat Off leads the menu. Minute-precision repeat tests were required (not day-only).
- **Spec 5.** Natural-language add (English dates, times, offsets, Spec 4 repeats) with a visible preview: Set to, Keep, and Set & Remove, then Save. Alert levels: Standard, Critical, and Prominent (full-screen), with permission fallbacks. Built without a foreground service and without a Play declaration at this stage.
- **Spec 6.** One health-check screen, yellow menu badge when a blocked or warning check is real, one-minute test alarm. Battery optimisation opens the system list (Samsung Device Care attempts fall back to that list). No one-tap exemption request.
- **Spec 7.** Free tier of five not-done reminders, cap in one constant (`ACTIVE_REMINDER_CAP`). Release billing reads the Play price for `do_unlock`. Debug fake starts unlocked and never connects to Play. Local backup (export/import, including SAF in the human path). Reminder `uuid`. Room schema version 4.

### Spec 8, exact test state (PR #8, head `4f5cf2e`)

What landed in the PR (not yet on `main`): daily quiet hours, off by default, 22:00 to 07:00. The first fire at the due time still alerts. Nags inside the window are placed at the window end, then rejoin `dueAt + k * interval`. A second setting, "Pause nags while Do Not Disturb is on", holds the same nags while the live interruption filter is PRIORITY, NONE or ALARMS, and releases them when it returns to ALL. It reads `getCurrentInterruptionFilter()` only (no new permission). Critical ignores both quiet hours and DND-follow unless "Critical alerts also pause during quiet hours" is on. Prominent never pauses. Free tier also blocks a third repeating reminder (`FreeTier.REPEATING_REMINDER_CAP = 2`) via the existing unlock screen. The 5-reminder cap is checked first. Import above the repeat cap is kept. `MirrorEntry.dndHeld` is a mirror-file field, not a Room column. `Scheduler.update` can return `BLOCKED_REPEAT`; the editor keeps the draft when Back is pressed from the unlock screen.

**Already passed (do not rerun just to re-prove them):**

- Host / CI: AT-01, AT-11, AT-19.
- Scripted phone: AT-02 to AT-09, and AT-13. AT-09 was rescored pass (runner predicate bug, later fixed).
- AT-05 scripted dump passed (`exempt=true`, not deferred). The Prominent full-screen / Stop / next-nag half is still a human check.

**Needs rerun with the fixed runner** (earlier cap FAILs were leftover-cleanup and baseline bugs, not proven product defects; the DND block was aborted mid AT-20):

- AT-14, AT-15, AT-16, AT-17 (repeating cap; baseline-aware: Tim's existing Daily reminder counts as one of the two repeating slots).
- AT-20, AT-21, AT-22, AT-23, AT-24, AT-25, AT-26, AT-27, AT-28.

**Human ATs still outstanding:** AT-05 (Prominent screen), AT-10 (reboot), AT-12 (Settings), AT-18 (editor and paywall).

How to run the rerun, the stop-file, restore checks, and the report filename are in `BRIEF-for-Claude.md` in this pack (and the runner, spec and shared lib under `do-qa/`). Copy those runner files into `C:\Users\tdrev\do-qa\` as that brief says, keeping a `.bak` first.

---

## 4. Decisions log (with dates)

Dates are Australia/Perth (AWST). Where a decision was recorded as "earlier" rather than a clock time, that is said plainly.

| When | Decision |
| --- | --- |
| 24 Nov 2026, as Spec 1 was written | If in doubt, copy Due. Kotlin/Compose, target and compile 36, min 26, package `com.evansappstudio.doapp` (`com.evansappstudio.do` is not usable because `do` is a Java keyword). Public repo for now. `setAlarmClock()` is the scheduling path. |
| Earlier, then built in Specs 7 and 8 | Price: **A$12.99**, one-time unlock, no ads. Free tier: **5 nagging** (every not-done reminder counts) and **2 repeating**. Intended Play product id: `do_unlock`. |
| 25 Nov 2026 | Natural-language add and alert levels shipped as **one combined PR** (Spec 5), not two. |
| During Spec 4 (25-26 Nov 2026) | Repeat tests must be **minute-precision**, not day-only. Week Starts On defaults to Monday. |
| Spec 8, 26 Nov 2026 | Quiet hours default **off**, window **22:00 to 07:00**. Plus "Pause nags while DND is on" (also default off). **Critical ignores quiet hours and DND-follow by default**, with a setting to opt in. Prominent never pauses. First fire at due still alerts. |
| 26 Nov 2026 | Design paper: position Do as the last mile for an AI assistant. v2 is cloud sync plus an agent API (MCP and REST), with a small "Do Connect" subscription on top of the one-time unlock. Spec 9 groundwork (command layer, event log, source and sync fields) was recommended before the first public release, still no account and no network in that PR. |
| After that paper, still awaiting Tim | A **v1 re-scope** was proposed: Spec 9 groundwork plus a **light Spec 10 "Do Connect"**. The agent API, which had been a v2 item, would move into that v1 re-scope. **This is not approved.** It waits on Tim's confirmation and his OK on a **hosting account**. Do not start Spec 9 or 10, and do not use any of Tim's hosting or Vercel (or other) accounts, until he confirms both. |
| Earlier, and again in the 26 Nov paper | An iOS version was originally a "won't" for the first release (Due already owns iOS). Tim may reconsider later. The paper's recommendation is: later, not instead; build any server so an iOS client can join. |
| 26 Nov 2026 | Tim's usage rule for this Grok workspace: stop Do dev at 93% of the weekly cap, and pause Do until after the reset at **Wed 2 Dec 2026, 23:02 AWST**. That is why this handoff exists. It does not by itself stop work Claude does under Tim's other credits, but do not spend Grok Do-usage if that is the constraint Tim is steering around. |
| 27 Nov 2026 | **Stuff You Should Do (SYSD)** approved for the roadmap as **v1.1**: a catalogue of periodic life-maintenance tasks, plus an onboarding quiz that turns picks into Do repeating reminders. Catalogue items may have an optional link field. Proposed commercial path (not a final decision): start with affiliate "Get it / Book it" links, then sponsors; paid users can hide the links; **never sponsor health items**. |
| 27 Nov 2026 | Tim said the one-off price and no-ads model is "just an idea" and a proof of concept, and that decisions may still be needed. Follow-up: **"Let's make a note to decide before the first release."** So the A$12.99 / no-ads model stands as the current plan, but **monetisation must be re-decided with Tim before the first release**. |

---

## 5. Planned / roadmap

Research MoSCoW (24 Nov 2026, `DO-RESEARCH.md`) listed the v1 Musts as: nag-until-done, bullet-proof on-device delivery, health/OEM checker, notification actions, Quick Times, natural-language add, full repeats including every-N minutes and hours, alert levels, no account with local backup, and a one-time price with a usable free tier. **Those Musts are already built in Specs 1 to 7**, with quiet hours (a research Should) and the repeating cap in open Spec 8. What remains for v1 is finishing, not rebuilding those features.

**Still to do for v1**

1. Finish Spec 8 QA (rerun + four human ATs), then merge PR #8 only with Tim's OK.
2. Close or honestly mark NOT RUN the outstanding human / device ATs from Specs 1 to 7 (see the table in section 3). Several need Tim present (reboot, Settings, sound, SAF, unlock copy). Never set a PIN or touch the lock screen to close a direct-boot row.
3. **Spec 9 groundwork + light Spec 10 "Do Connect"** only if and when Tim confirms the re-scope and a hosting account. Until then, do not start them and do not open or use a hosting account. The 26 Nov paper's Spec 9 proposal (for when it is approved) is: a `ReminderCommands` layer every entry point calls; Room v5 fields (`source`, `sourceId`, `updatedAt`, `version`, `deletedAt`, `approval`); an append-only `reminder_event` table; soft delete; backup keys for the new fields; keep domain logic free of Android imports; use the API's words now. That paper said v1 groundwork should still have no account, no network, no FCM, and no AppFunctions UI.
4. Play listing, exact-alarm and full-screen declarations, signing, and store listing copy: only with Tim's explicit go-ahead. No submission.
5. **Re-decide monetisation with Tim before the first release.**

**Shoulds** (research, after v1 Musts / Spec 8): **reusable countdown timers with auto snooze, first**; then an interactive home-screen widget, a Quick Settings tile, share-sheet quick add, a completed-reminder log with undo, bulk select to defer or complete, and an optional "give up after N nags". Quiet hours was also a Should and is already in Spec 8.

**v1.1: Stuff You Should Do.** Catalogue + onboarding quiz that creates repeating Do reminders. Optional link field; affiliate then sponsor idea; paid users can hide links; never sponsor health items. A competitor scan was requested (Nico) and is meant to run after the 2 Dec reset, then a spec. Do not start building SYSD until that spec exists and Tim has agreed it.

**v2** (design paper, 26 Nov 2026, unless the pending re-scope pulls a light slice forward): cloud relay and ledger, MCP plus REST, per-agent OAuth, approve-before-ring by default, device-enforced caps agents cannot override, events back by webhook and a pull cursor, on-device AppFunctions in the same release, and a small "Do Connect" subscription for the cloud part while the A$12.99 unlock (if still the model) covers everything on the phone. Open questions still for Tim include sign-in, default consent, agent alert levels, pricing, and hosting (Firebase, Cloudflare or Supabase were compared on published prices only; no account was used).

Could-haves and won'ts from the research (Wear OS, Tasker, escalation, Drive-file sync, location, iOS as a first-release item, projects/tags, group reminders, accessibility tricks, gamification) stay out of v1 unless Tim changes that.

---

## 6. Google Play policy flags

From `DO-RESEARCH.md` section 5 (accessed 24 Nov 2026). These matter before any store submission. None of this is permission to publish.

| Topic | Flag | What Do should do |
| --- | --- | --- |
| Exact alarms | `USE_EXACT_ALARM` needs a **Play declaration and review**. Accepted cases are worded as an alarm or timer app, or a calendar app that shows event notifications. A reminder app is not explicitly listed, so relying on USE is a risk. `SCHEDULE_EXACT_ALARM` is **special access**, no Play form, denied by default on Android 14+ for new installs (send the user to Alarms & reminders and check `canScheduleExactAlarms()`). | The app defaults to USE, with a SCHEDULE fallback build. Treat the USE declaration as a review risk, not a given. |
| Full-screen intent | `USE_FULL_SCREEN_INTENT` needs a **Play declaration** to claim the pre-grant (core function "setting an alarm" or calls). Otherwise it is special access on Android 14+. | Needed for Prominent / Alarm-level alerts (and later, finished timers). Spec 5 already falls back if it is not granted. |
| Battery exemption | Play prohibits requesting a **direct** exemption from power management unless the core function is adversely affected. | **Do not make a direct one-tap exemption request.** Open the system battery-optimisation list (Spec 6 already does this). |
| Foreground services | Apps targeting 14+ must declare an FGS type, and in Play Console give a description, user impact, and a **video** of the user triggering the feature. | Prefer exact alarms plus notifications. Use an FGS only while sound is playing or a timer screen is active, and only with the declaration and video. Spec 5's Prominent path was built without an FGS. |
| Location | Background location needs a Play declaration, and is easy to get wrong. | **Avoid for v1.** Research parked geofences as a Could. |
| Accessibility | Heavily restricted; needs a Play declaration. | **Do not use.** A research Won't for the first release. |

Related, not a declaration but a design issue: Android 15+ notification cooldown can soften a 1-minute nag; critical / alarm-style alerts are the intended way around it, explained in health check. Android 17 background audio ties loud alerts to holding the exact-alarm permission.

---

## 7. Test setup

- **Device:** Tim's Samsung Galaxy S24+, Android 17 / API 37, talking to his Windows PC over adb. Runners live in `C:\Users\tdrev\do-qa\`, with shared helpers at `C:\Users\tdrev\do-qa\lib\qa-runner-shared.ps1`. Copy updated runners there (keep a `.bak` first). This pack's `do-qa\` folder is the Spec 8 set.
- **Stop-file:** to stop a run cleanly, create an empty file such as `C:\Users\tdrev\do-qa\STOP-spec08`. The runner should then skip the rest and restore the phone. Do not use Ctrl-Break; that previously broke restore.
- **Leave Tim's real reminders alone.** A known live example is a Daily reminder titled "Isabelle book list". Cap tests must be baseline-aware and must not Done, Repeat-Off, or overwrite it. Cleanup must only touch this run's test rows (names like `T_s8_*`, via debug hooks, not by wiping the database).
- **No PIN, and never touch the lock screen.** If the phone is locked, stop and ask Tim to unlock it. Direct-boot tests stay NOT RUN unless Tim agrees to use an existing lock; do not set a PIN.
- Do not force-stop the app, and do not change the phone's clock or time zone, unless a specific AT that Tim has agreed to requires it and the runner will restore it.
- Do not change `stay_on_while_plugged_in` (it is 15 and stays 15).
- Samsung's "Sleeping" rule (22:00 to 07:15) must be left untouched. After a Spec 8 run, also confirm DND is off, Do's quiet hours are back to off / 22:00-07:00 with both switches off, and no live `T_s8_*` rows remain.
- Debug billing only. **No real Play purchases.** The fake unlock is the test path.
- adb has at times shown no devices (cable or the USB debugging prompt). If `adb devices` is empty, ask Tim to reconnect and accept the prompt.
- Mark's later Spec 8 runner fixes (already in this pack's `run-spec-08.ps1` and shared lib): leftover cleanup through debug hooks (Repeat Off, Done, targeted IMPORT merge); cap ATs read `DUMP_UNLOCK` and only top up with `T_s8_*` fixtures; shared logcat; helper tests were 254/254 when last recorded.

---

## 8. Ground rules for Claude

- Nothing is published, submitted to Google Play, paid for, or sent to anyone outside Tim, without Tim's explicit go-ahead. That includes store listings, declarations filed in Play Console, real purchases, and messages to third parties.
- No real Play purchases. Use the debug fake for unlock tests.
- Do not use Tim's Vercel or other hosting, billing, or cloud accounts. Do not create or attach a hosting account for Do Connect until Tim names the account and says to use it.
- Never invent data, test results, prices, or "passes". If something was not run, write NOT RUN.
- Keep PRs small: one spec per PR, into `main`, squash only after QA, PM sign-off, green CI, and Tim's OK. Do not merge without that OK. Do not push to `main` directly.
- Do not comment on PRs, change GitHub settings, or force-push unless Tim asked for that exact action.
- Australian English, plain words, no em dashes. Report times in AWST (Australia/Perth) and say so.
- Never set a PIN or touch the lock screen. Never spend Tim's money.
- Do is not Next Train or Mr Night; do not mix those codebases or accounts.
- If you are asked for both a product change and a merge or a store submission, do the reversible work and stop before the irreversible step, unless Tim has already named that step.

---

## 9. Suggested first steps

1. Clone https://github.com/tdrevans-aus/do-app and read `main` (through Spec 7) plus PR #8. Ask Tim for the Spec 1 markdown and the two research papers if you need them; they are not in the repo.
2. If Tim wants the Spec 8 tests run: follow `BRIEF-for-Claude.md` in this pack only. Report `results-pr8-4f5cf2e-phone-run3.md` plus evidence. Do not merge.
3. If Tim wants new feature work: do not start Spec 9, Spec 10, SYSD, timers, or any hosting work unless he has confirmed that item. After PR #8 is merged, the next research Should is reusable countdown timers, but wait for a written spec and Tim's OK, same as previous specs.
4. Keep each change in a small spec PR. Use the existing debug hooks and QA runner patterns rather than inventing a new test path.
5. When you write results or a PR, say what was run, on which SHA, and what was not run.

## 10. Research snapshot (brief)

Full text is in `DO-RESEARCH.md` (24 Nov 2026) and `DO-AGENTIC-DESIGN-PAPER.md` (26 Nov 2026). Neither file is in the GitHub repo.

**Due and rivals (research, store pages accessed 24 Nov 2026).** Due is the product to copy: iOS/iPad/Mac/Watch, a one-time price of A$12.99 plus an optional Annual Upgrade Pass (A$6.99 a year on mobile). It has Auto Snooze, Quick Access Times, a full repeat set, Standard / Critical / Prominent alerts, and no Android app. The closest Android rivals called out in the research are **nag reminder** and **StillDue** (modelled closely on Due). Others scanned include Naggit, Persistent, Remindio, Galarm, BZ Reminder, TickTick (Constant Reminder), Todoist, Google Tasks and Keep, and Tasks.org. Common 1-star themes the MoSCoW was built from: missed or late alerts, Android killing the app, ads and subscriptions, and notification actions that only open the app.

**Design paper (26 Nov 2026).** The moat is on-device enforcement, not capture: Google Tasks, Todoist, TickTick and others already let agents add and complete tasks. Todoist (webhooks including `reminder:fired` and `item:completed`) and Beeminder come closest to closing the loop, and neither nags the way Do does. The paper recommends shipping v1 as planned, adding the small Spec 9 groundwork so v2 is an add-on rather than a rewrite, and treating v2 as "the alarm your AI can set, and that tells your AI what happened." iOS is worth doing later, not instead (iOS 26 AlarmKit removes a previous technical barrier, but Due already owns that market).

---

## 11. Sources, and what could not be verified

Read, not invented:

- GitHub repo `tdrevans-aus/do-app`: README, `main` and PR #8 trees, PRs #1 to #8 (titles, merge commits, merge times, PR #8 head and CI), `.github/workflows/ci.yml`, package layout, `app/build.gradle.kts`.
- Local specs under the team's `do-app/specs` (including Spec 1, which is not on GitHub) and PR #8's `specs/08-...`.
- QA results and runners, including `results-pr8-4f5cf2e-phone.md` and `run-spec-08.ps1`.
- `BRIEF-for-Claude.md` in this pack.
- `DO-RESEARCH.md` (24 Nov 2026) and `DO-AGENTIC-DESIGN-PAPER.md` (26 Nov 2026), summarised only.
- Prior product decisions recorded with Tim and Gareth.

**Unknown / confirm:**

- Whether the Spec 6 AT-06 rerun, and the Spec 7 AT-08 / 09 / 11 reruns, were repeated on the merge commits after those result files were written.
- Whether `do_unlock` is already configured in a Play Console account, and at what price in the console (A$12.99 is the product decision, not a verified live listing).
- Exact wording Tim wants for a future Spec 10, if he approves the re-scope.
- Whether Tim will supply the missing GitHub copy of Spec 1, and the two research papers, as extra files for you.

When this brief and a later test result disagree, trust the result file from the run you or Mark just did, and say so.
