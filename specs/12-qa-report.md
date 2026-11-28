# Spec 12 QA report: capture surfaces

QA: Claude test agent (host). Branch `do/spec-12-capture-surfaces`, reviewed head `ea74c09` (diff `37daff0..ea74c09`), worktree `/home/user/do-app-spec12`.
Run window: 27 Nov 2026 16:53 AWST to 28 Nov 2026 08:40 AWST (paused part way by a usage limit, then resumed on the same tree).
No production code was changed. QA tests are in `app/src/test/java/com/evansappstudio/doapp/capture/Spec12QaTest.kt`.

## Verdict

**Pass with bugs; no blocker.** The build is green apart from the QA tests that are meant to fail. Nothing in Spec 12 can stop or delay a reminder, change how alarms are scheduled, or get around the free cap of 5. The agent hold works on create. Release has AppFunctions off and no debug receiver. The `FOREGROUND_SERVICE` removal is safe for Glance. One Medium bug: a shared link that contains a date ends up as a broken title and a wrong due date. The other seven bugs are Low.

## Commands

| Command | Result |
| --- | --- |
| `./gradlew --no-daemon --max-workers=2 --continue assembleDebug testDebugUnitTest lintDebug` (USE) | assembleDebug, checkAlarmManifests and lintDebug pass (lint 0 errors, 91 warnings, the same as before the QA tests). 231 tests, 8 failed, all of them the marked QA tests. Without the QA file: 196 tests, 0 failed. |
| same with `-PexactAlarmMode=SCHEDULE` | Same result. The merged debug manifest has `SCHEDULE_EXACT_ALARM`. 231 tests, 8 failed (the same QA tests). |
| `./gradlew --no-daemon --max-workers=2 assembleRelease checkAlarmManifests` | Pass. `app-release-unsigned.apk` built. `BuildConfig.APP_FUNCTIONS_ENABLED = false` in release. |
| `--tests '*Spec12QaTest*' --tests '*DateParser*'` | The DateParser suite passes in full after the "after work" change. |

No HTTP 429s were hit.

## Acceptance tests (host)

| AT | Verdict | Notes |
| --- | --- | --- |
| 1 Share text handling | PASS | `ShareTextTest` covers the first line, the 200 cut, empty text, the parse and its words, repeats, the default and date-only text, with real assertions. It does not cover URLs, invisible text or graphemes (BUG-S12-01, 05, 06). |
| 2 Every surface uses the commands | PASS | Real rows go through the Scheduler fixture: source, sourceId and event actor are checked for Share, the widget add and tick, the tile, shortcuts, links and the agent. The one gap is that the in-app Add only reaches `CaptureCommands` through UI code that has no test. |
| 3 Cap | PASS | Share and the widget get `NeedsUnlock`, and the agent gets `LimitReached` with nothing written. QA added: agent reminders count towards the cap for the person's own adds, and Share at the cap writes no row and no event. The "back to sender" part is UI and is a phone check. |
| 4 Agent level held | PASS | Alarm is stored as STANDARD, with `requestedAlertLevel=PROMINENT` and `approval=PENDING`. Any case and the stored names work. The hold covers create only, not later commands (BUG-S12-04). |
| 5 Deep link parsing | PASS | Spaces, emoji, empty, bad escapes and other hosts are covered. An outside link can claim the SHARE or WIDGET source (BUG-S12-07). |
| 6 CI green, release AppFunctions off | WEAK (passes) | `at6ReleaseHasAppFunctionsOff` only greps `build.gradle.kts`. The real guard is `checkAlarmManifests`, which runs before assembleDebug, lint and test, and I checked it on the merged release manifest. Its AppFunctions needles are trivially absent because there is no AppFunctions dependency yet, so the guard has never been tested against a manifest that has them. Both exact-alarm variants build. |

## Manifest and permissions (merged, debug and release)

- **Share:** `ShareActivity` is exported with a single filter, `SEND` plus `text/plain`. There is no `text/*` and no `SEND_MULTIPLE`. An explicit intent from another app can open the sheet with any text, but the person still has to tap Save. `sharedText` ignores anything that is not `ACTION_SEND`.
- **MainActivity** (exported as the launcher activity): the `do://add|today` VIEW filter and the internal `ACTION_ADD` both open Add prefilled. Nothing saves without a tap. An outside caller can set the `entry` extra to choose the stored source (BUG-S12-07).
- **Tile:** exported, but protected by `BIND_QUICK_SETTINGS_TILE`, so only the system can bind it.
- **Widget receivers:** exported, which is required for `APPWIDGET_UPDATE`. Any app can send them an explicit update, delete or options broadcast, which at most re-renders the widget or drops its session. That is the same exposure every widget app has. Tick-to-done goes through Glance's `ActionCallbackBroadcastReceiver` (`exported=false`), so outside apps cannot mark reminders done. `GlanceRemoteViewsService` needs `BIND_REMOTEVIEWS`. The trampoline activities are not exported.
- **WorkManager:** `SystemJobService` is exported but needs `BIND_JOB_SERVICE`, and `DiagnosticsReceiver` needs `DUMP`. Both are normal. `FOREGROUND_SERVICE` and `SystemForegroundService` are gone from both variants. **This is safe for Glance:** I decompiled `glance` and `glance-appwidget` 1.2.0, and the session worker uses no `setForeground`, `ForegroundInfo` or `setExpedited`. The only foreground reference is `actionStartService(isForegroundService)`, which Do does not use. `WAKE_LOCK` and `ACCESS_NETWORK_STATE` are added and are normal permissions.
- **Network:** Spec 12 adds no network code (the only `java.net` use is `URLDecoder` and `URLEncoder`). Release has `INTERNET`, which the Play Billing library merges in, as it did before Spec 12. Debug has no `INTERNET`.
- **Release:** no `DebugReceiver`, no `AGENT_CREATE`, `AGENT_COMPLETE` or `AGENT_LIST`, no `FAKE_PURCHASE`, no AppFunctions entries. None of those strings appear in the release dex either.
- **Debug:** `DebugReceiver` is exported with no permission, as before, and now has the AGENT actions, so any app on a debug phone can create agent reminders. This is debug only and matches the existing pattern.

## Safety

- **Reminders are never stopped or delayed.** `ReminderCommands.committed()` runs listeners only after the scheduler call returns. A listener that throws is caught. `WidgetRefresher.request` is a non-blocking `trySend` on a conflated channel. In `AlarmReceiver`, `widgets.request()` comes after `onFire` and cannot throw. No alarm code changed apart from `Scheduler.add` gaining `approval` and `requestedAlertLevel`.
- **Cap:** every Spec 12 write calls `ReminderCommands.add`, which goes to `Scheduler.add` and its `FreeTier.blocksAdd` check. There is no other write path.
- **Direct boot:** the widget receivers and Glance components are not direct-boot aware. `WidgetData.summaries` and `WidgetDoneAction` check `UserUnlock` before touching the container. Before first unlock, `RescheduleReceiver` (on `LOCKED_BOOT_COMPLETED`) and `AlarmReceiver` call `widgets.request()`, and `refreshNow()` then calls `GlanceAppWidget.update`. WorkManager is not initialised yet, because its startup provider is not direct-boot aware, so the update throws. `WidgetRefresher` catches the exception and logs it as `handlingFailed`. Room is not touched, nothing crashes and no reminder is affected. It does add log noise (see Observations), and the widget keeps its pre-reboot view until `BOOT_COMPLETED`.
- **Widget Done** calls `CaptureCommands.widgetDone`, which calls `ReminderCommands.markDone(Origin(USER, WIDGET))` and reaches the same `Scheduler.markDone` as notification Done (`alarms.cancel`, `notifications.dismiss`, `AlertSession.attended`). Before unlock it goes to the mirror as a pending Done. QA tests check that the alarm is cancelled, that a repeat moves on, that a missing id changes nothing, and the mirror path.

## Bugs

| ID | Severity | Where | Repro | Fix |
| --- | --- | --- | --- | --- |
| BUG-S12-01 | **Medium** | `capture/ShareText.kt:57` (`CaptureDrafts.fromText`) | Share `https://www.theguardian.com/australia-news/2026/nov/27/perth-heatwave-warning`. The title becomes `.../australia-news/2026/ /perth-heatwave-warning` and the due date is 27 Nov. ABC-style `/2026-11-27/` and `/12/05/` paths are read as dates too. The person saves a broken link. Test: `urlOnlyShareIsNotReadAsADate`. | Do not run the parser over URL tokens: mask `\bhttps?://\S+` (and `www.`) before parsing and keep them in the title, or skip parsing when the line is only a URL. |
| BUG-S12-02 | Low | `capture/AgentRequests.kt:59-62` | `createReminder(dueAt = Long.MAX_VALUE)` or a date in the year 9999 is accepted. The reminder never fires but uses one of the 5 free slots. Test: `agentAbsurdDueAtIsRefused`. | Refuse a `dueAt` more than, say, 10 years ahead with `InvalidArgument`. |
| BUG-S12-03 | Low | `capture/AgentRequests.kt:59` | `dueAt = -1` is treated as omitted (parse the text, else tomorrow 9 am) instead of being refused. Test: `agentNegativeDueAtIsRefused`. | Treat only `0` or null as omitted, and refuse values below 0. |
| BUG-S12-04 | Low (latent) | `alarm/ReminderCommands.kt:74` (update), `:95` (setAlertLevel) | `commands.setAlertLevel(Origin(AGENT, ...), id, PROMINENT)` stores PROMINENT with no hold. The doc comment and `at4OnlyAgentRequestsAboveNormalAreHeld` say "any agent path gets it". No Spec 12 agent function calls these, but Spec 13's cloud agent will. Test: `agentCannotRaiseTheLevelAfterCreate`. | Apply `AgentHold` in `update` and `setAlertLevel` when `origin.actor == AGENT`, or reject those calls for agents. |
| BUG-S12-05 | Low | `capture/ShareText.kt:34` | Sharing only `U+200F` (a right-to-left mark) or other bidi marks or isolates gives an invisible, non-empty title that can be saved. Test: `invisibleOnlyTextIsRefused`. | Strip `\p{Cf}` (format characters) before the blank check. |
| BUG-S12-06 | Low | `alarm/CommandTypes.kt:148` (`cutText`, used by `ShareText.clean`) | 198 characters followed by a flag emoji keeps half the flag (one regional indicator letter). ZWJ sequences split the same way. Test: `cutNeverLeavesHalfAFlag`. | Cut at a `BreakIterator.getCharacterInstance()` boundary at or before 200. |
| BUG-S12-07 | Low | `capture/CaptureEntry.kt:59` | Another app opens `do://add?text=x` with extra `entry=SHARE` or `WIDGET`. The saved row records source SHARE or WIDGET. This affects provenance only (no sourceId, no safety effect). Test: `aLinkCannotClaimAnotherSurface`. | For VIEW links, accept only `SHORTCUT` from the extra (or nothing) and default to `DEEP_LINK`. |
| BUG-S12-08 | Low | `alarm/DateParser.kt:547-565` (`unsupportedRepeatEnd`) | "Walk the dog every day after work" finds no date, so the sheet says tomorrow 9 am and the repeat is lost. The guard against unsupported "every N after ..." phrases poisons the span. "daily after work" and "every Tuesday after work" work. This is not a regression, but it is the most natural repeat with the new phrase. Test: `everyDayAfterWorkRepeats`. | In the guard, stop at `after work` (or ask `phraseTime`) before treating it as "every N after X". |

Failing tests: 8, each marked `// QA: fails until ... (BUG-S12-nn)`.

## "After work" parser change

The whole DateParser suite still passes. New QA tests pass for: `work`, `Finish work` and `after` alone (no match); `after workout` and `after workouts` (no match); `homework` (no match; "Do homework tomorrow" keeps "Do homework"); `after work drinks` (today 5:30 pm, title "drinks"); "after work tomorrow"; "after-work Friday"; and after 5:30 pm the due time is in the future. Two oddities, neither a regression: "Drinks Friday after work at 6pm" gives Fri 5:30 pm with title "Drinks at 6pm" (the explicit time loses), and "after work every day" drops the repeat into the title. The second follows the parser's existing rule that a repeat must come first.

## Observations (not filed as bugs)

- `ShareActivity`: if the activity is recreated (rotation) while a save is running, the result goes to the old instance. The new sheet then stays with Save disabled until Cancel, although the reminder was saved. The sheet title field has no length limit, unlike the 200 cut (the same is true of in-app Add). The sheet uses hard-coded light colours in dark mode.
- `DebugReceiver.kt`: the KDoc "Runs [block] off the main thread..." now sits above `agentCreate` instead of `unlockedLaunch`.
- The widget refresh in direct boot throws and is logged as `handlingFailed` on every locked fire. Skipping `refreshNow` while `!UserUnlock.isUnlocked` would remove the noise.
- `callerPackage`: through the system Sharesheet, `launchedFromPackage` is usually null (the sender has to opt in) and the referrer may be the chooser (`com.android.intentresolver`) rather than the app. Check this on the phone.
- `AgentFunctions.listReminders` gives agents every active title (allowed by spec section 3E).

## Phone ATs (NOT RUN; no device in this session)

| AT | Status | Risk to watch |
| --- | --- | --- |
| 7 Share "Grab the dry cleaning Friday after work" | NOT RUN | Host test gives Fri 5:30 pm. Check that the sheet draws over the sender, the toast shows, Save returns to the chat, and what sourceId is stored (see `callerPackage`). |
| 8 Widgets, tick done | NOT RUN | Glance on One UI: whether the 44dp rows fit 4 x 3, dark mode, the update after a fire and after reboot (the direct-boot refresh fails until `BOOT_COMPLETED`), and that the notification is gone after a tick. |
| 9 Tile from the lock screen | NOT RUN | `unlockAndRun` plus `startActivityAndCollapse(PendingIntent)` on Android 14 and later. Do not remove the lock. |
| 10 Shortcuts | NOT RUN | `do://today` opens the list. Add opens with entry SHORTCUT. |
| 11 AppFunctions | NOT RUN | No AppFunctions service exists in this build (compileSdk 37 is needed). |
| Extra | NOT RUN | Share at the cap, paywall, then back to the sender. Do must not appear for photo or contact shares. |
