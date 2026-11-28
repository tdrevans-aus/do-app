# Spec 12: Capture surfaces (draft)

Owner: Gareth (PM). Build: a Claude build agent. QA: Mark, and a Claude test agent (host). Status: **draft by Claude, 27 Nov 2026 (AWST), not yet approved.** Builds on Spec 9. Independent of Specs 10 and 11.
Sources: `docs/strategy/DO-PRODUCT-STRATEGY.md` (pillar 2; section 10: AppFunctions decision); `docs/design/DESIGN-PASS-1.md` (screens 13 widget and Quick Settings tile, 14 share sheet); Android developer documentation for App Widgets (Jetpack Glance), `TileService`, share targets (`ACTION_SEND`), App Shortcuts and AppFunctions (Jetpack `androidx.appfunctions`). The builder confirms current API details against those docs; anything not confirmed is marked assumed.

## 1. Goal
Let people get a reminder into Do in under two seconds from anywhere on the phone: sharing text from another app, a home-screen widget, a Quick Settings tile, a long-press app shortcut, a deep link, and (when Google opens it to other apps) Gemini through AppFunctions. Everything goes through Spec 9's `ReminderCommands`, with the right `source`. No network.

## 2. Starts from Spec 9
`ReminderCommands` with `Origin` and `ReminderSource` (`SHARE`, `WIDGET` exist; this spec adds nothing to the enum unless needed). Natural-language parsing (`DateParser`, Spec 5) turns text into a due time and repeat. The free cap of 5 applies to every surface.

## 3. Scope

### A. Share to Do
- An activity that accepts `ACTION_SEND` with `text/plain` (and `text/*`), shown as "Add to Do" in the share sheet.
- A bottom sheet over the sending app (design screen 14): "What needs doing?" prefilled with the shared text, trimmed to the first 200 characters and the first line; the parsed date shown with the words it came from ("from "Friday after work""); four quick choices (Tonight, Tomorrow, Weekend, Choose...); "Save and go back".
- No date found: due defaults to tomorrow 9 am (the user's default if Spec 14 adds one), shown before Save.
- Saves with `Origin(Actor.USER, ReminderSource.SHARE, sourceId = <calling package if Android provides it, else null>)`.
- Over the cap: the paywall, then back to the sending app.
- Works with the app not running; finishes and returns to the sender after Save.

### B. Home-screen widgets (Jetpack Glance, assumed the current recommended toolkit)
- **Large (4 x 3):** "Do", the count overdue, the next three active reminders with tick-to-done (Done through `ReminderCommands.markDone` with `Actor.USER`), and a "What needs doing?" row that opens Add.
- **Small (2 x 2):** the number overdue, or "All clear", and tap opens Add.
- Updated whenever reminders change (hook the Spec 9 command layer's commit point) and on boot and time change; never polls.
- Respects the system's dark theme. No personal data beyond titles, which the user already sees in notifications.

### C. Quick Settings tile
- A `TileService` "Do: Add a reminder" that opens Add (collapsing the shade). Label and icon only; no state.

### D. App shortcuts and deep link
- Static shortcuts on the launcher icon: "Add a reminder", "Today".
- Deep link `do://add?text=<urlencoded>` (and the same as an `https://` app link later, out of scope) opens Add prefilled. Used by automation apps and, later, assistants.

### E. AppFunctions (built now, switched on later)
- Expose functions through Jetpack AppFunctions: `createReminder(text, dueAt?, repeat?)`, `completeReminder(id)`, `listReminders(limit)`. Each is a thin call into `ReminderCommands` with `Origin(Actor.AGENT, ReminderSource.AGENT, sourceId = <caller>)`; agent reminders count towards the 5.
- Alert level from an agent is stored as `requestedAlertLevel`, and the reminder is created at Normal (`STANDARD`) until the user approves (`approval = PENDING`); approval UI comes with Spec 13 or 14.
- Built behind a build flag, `appFunctionsEnabled`, default on in debug and **off in release** until Google opens Gemini access to other apps (Tim applies for early access). Assumed: AppFunctions needs Android 16 or later; on older phones the functions simply aren't registered.

## 4. Out of scope
The cloud agent API (Spec 13). Approval screens for agents (Spec 13 or 14). Voice input. Lock-screen widgets. Wear OS. iPhone.

## 5. Acceptance tests
Host (unit, CI):
1. **Share text handling.** First line, 200-character trim, parse result and its source words; empty text refused.
2. **Every surface uses the commands** with the right `Origin` (unit over the small adapters).
3. **Cap.** A free user at 5 is sent to the paywall from Share, the widget's add row and AppFunctions (which returns a clear "limit reached" error to the caller instead).
4. **Agent level held.** `createReminder` with an Alarm request stores `requestedAlertLevel=PROMINENT`, `alertLevel=STANDARD`, `approval=PENDING`.
5. **Deep link parsing** with spaces, emoji and an empty `text`.
6. **CI green**, both exact-alarm variants; the release manifest has AppFunctions off when the flag is off.

Phone (Mark with Tim):
7. Share a chat message "Grab the dry cleaning Friday after work": the sheet shows Fri at 5:30 pm (or the parser's after-work time) and saves.
8. Add both widgets; tick one done from the widget; the app and the notification agree.
9. Add the tile; it opens Add from the lock screen after unlock (never test by removing the lock).
10. Long-press shortcuts work.
11. AppFunctions on the S24+: only if Google has granted early access; otherwise NOT RUN.

## 6. Risks
- **Widget staleness.** Updating from the command layer's commit point keeps it current; boot and time-change refreshes cover the rest.
- **AppFunctions API changes.** It is a preview; keeping it behind a flag and thin limits rework.
- **Share sheet clutter.** Accept only text, so Do doesn't appear for images.

## 7. Calls (Claude's defaults for Gareth)
1. Share sheet saves without opening the full app.
2. No date found means tomorrow 9 am, shown before Save.
3. Widgets in Glance.
4. AppFunctions built now, off in release until Gemini access opens.
5. Agent alert requests are held at Normal until approved.

## Build notes (Claude build agent, 27 Nov 2026)

### Dependencies added
| Artefact | Version | Why |
| --- | --- | --- |
| `androidx.glance:glance-appwidget` | 1.2.0 (stable, 26 Aug 2026) | Both home-screen widgets. 1.3.0 is alpha and needs compileSdk 37. |
| `androidx.glance:glance-material3` | 1.2.0 | `ColorProviders(light, dark)` so the widgets follow the system dark theme. |
| `androidx.work:work-runtime` | 2.11.2 (stable) | Glance renders through WorkManager and asks for 2.7.1; pinned to a current stable release. |

WorkManager's manifest asks for `FOREGROUND_SERVICE` and declares `SystemForegroundService`. Do runs no foreground work, so both are removed with `tools:node="remove"`; `checkAlarmManifests` now fails if `FOREGROUND_SERVICE` comes back. `WAKE_LOCK` and `ACCESS_NETWORK_STATE` (normal permissions WorkManager needs) stay. No network is used.

### AppFunctions: thin layer, no annotations yet
- `androidx.appfunctions` is stable at 1.0.0 (18 Nov 2026; `@AppFunctionServiceEntryPoint`, `@AppFunctionDeclaration`, KSP `appfunctions-compiler`, Android 16 or later at run time). Every release from 1.0.0-alpha09 on, including 1.0.0, has `minCompileSdk=37` and AGP 9.1 or later in its AAR metadata. This project compiles against 36 and the build machine has only platform 36. Older alphas that compile against 36 (alpha07, alpha08) use the `@AppFunction` and `AppFunctionConfiguration` API that 1.0.0 removed, so they would be throw-away work.
- So, per the brief's fallback, Spec 12 ships the adapter only: `capture/AgentFunctions.kt` (`createReminder`, `completeReminder`, `listReminders`) over `ReminderCommands` with `Origin(AGENT, AGENT, caller)`, its errors as `AgentError` (`Disabled`, `LimitReached`, `InvalidArgument`, `NotFound`, each matching an AppFunctions exception), and the pure mapping in `capture/AgentRequests.kt`. Omitted parameters are handled the AppFunctions way (an omitted `dueAt` arrives as 0, `limit` as 0).
- The build flag exists now: `BuildConfig.APP_FUNCTIONS_ENABLED`, on in debug and off in release, overridable with `-PappFunctionsEnabled=true|false`. With it off every agent call fails with `Disabled` and nothing is read or written. `checkAlarmManifests` fails if the release manifest mentions `android.app.appfunctions`, `BIND_APP_FUNCTION_SERVICE` or `AppFunctionService` while the release flag is off.
- To switch it on later: raise compileSdk to 37 (a project-wide change for Gareth to schedule, not a Spec 12 side effect), add `appfunctions` and `ksp(appfunctions-compiler)` 1.0.0, write an abstract `@AppFunctionServiceEntryPoint` service in `src/debug` (or a flag-gated source set) whose three `@AppFunctionDeclaration` functions call `container.agent` and map `AgentError` to the AppFunctions exceptions, and declare the generated service only in the debug manifest until release is approved.
- Debug builds can drive the layer now: `adb shell am broadcast -n com.evansappstudio.doapp/.debug.DebugReceiver -a com.evansappstudio.doapp.debug.AGENT_CREATE --es text "Bins tomorrow 7pm" --es level Alarm` (also `AGENT_COMPLETE --el id N` and `AGENT_LIST --ei limit N`); results are logged as `DoAlarm agent-...`.

### Choices where the draft was open
1. **Agent hold lives in the command layer.** `ReminderCommands.add` applies `AgentHold` to every `Actor.AGENT` add: above Normal is stored at `STANDARD` with `requestedAlertLevel` and `approval=PENDING`; an agent asking for Normal stores `requestedAlertLevel=STANDARD`, `approval=NONE`. A person's add is untouched. `Scheduler.add` gained `approval` and `requestedAlertLevel`.
2. **Agent level words.** Normal, Loud and Alarm (and the stored names Standard, Critical, Prominent), any case; blank means Normal; anything else is refused.
3. **Agent dates.** A `dueAt` more than a minute in the past is refused. With no `dueAt`, the text is parsed like a share, else tomorrow 9 am. `repeat` goes through the Spec 5 parser, so only the phrases it supports work ("every day", "every Tuesday", "every month", "every 2 hours"; not "every week" or "every 3 days").
4. **Share accepts `text/plain` only**, not `text/*`: `text/*` would put Do on contact (vCard) and calendar shares, which send files, and that is the clutter section 6 warns about. Browsers and chat apps share text and links as `text/plain`. `EXTRA_SUBJECT` is used when `EXTRA_TEXT` is missing.
5. **Share sheet title.** The field holds the shared first line with the date words taken out ("Grab the dry cleaning"); the date line under it shows the time and `from "Friday after work"`. If the text is only a date, the whole text stays as the title. A quick choice replaces the parsed date and drops a parsed repeat. No date found shows "No date found, so tomorrow at 9 am" before Save. Nag and alert level are the user's defaults.
6. **Quick choices.** Tonight is today 7 pm (design screen 6's "Tonight 7 pm"), or an hour from now once 7 pm has passed; Tomorrow is 9 am; Weekend is the next Saturday 9 am still ahead; Choose... is a date then a time picker.
7. **"After work" is 5:30 pm.** The Spec 5 parser did not know it, so AT-7's message would have got Friday at 9 am. `DateParser` now reads "after work" as a time (5:30 pm). That is the only parser change.
8. **Calling package.** `Activity.launchedFromPackage` on Android 14 and later (only when the sender shares its identity), else the `android-app://` referrer host, else null. It is informational (the referrer can be set by the sender) and is clipped to 200 characters by `Origin`.
9. **Sources.** Share stores `SHARE`, the widget's add row and tick store `WIDGET` (the tick's event actor is `USER`). The tile, shortcuts and deep link are the person opening Add, so they store `USER`; no enum value was added. The in-app Add screen now saves through the same `CaptureCommands` adapter, with the entry that opened it.
10. **Paywall.** The widget's add row, tile, shortcuts and links open the normal Add screen, which already goes to the paywall on the cap. Share shows the same Unlock screen over the sender; backing out of it returns to the sender, or to the sheet if the purchase went through.
11. **Widget refresh.** `ReminderCommands.addListener` runs after each command returns (after its Room transaction). The app registers one listener, `WidgetRefresher.request`, which folds bursts into one refresh. Fires (`AlarmReceiver`), boot and time or zone changes (`RescheduleReceiver`) request one too, because they change what is overdue without a Room write. Inside the widget, a Room `Flow` plus a time tick feed the composition; nothing polls and `updatePeriodMillis` is 0.
12. **Widget content.** Large (4 x 3 target): "Do", "N overdue" in alarm red or "All clear", the next three active reminders (overdue first) with a 44dp checkbox row and due time (red when overdue), and a "What needs doing?" row that opens Add. Small (2 x 2): the overdue number in red with "overdue", or "All clear"; tapping anywhere opens Add. Before first unlock the widgets say to unlock instead of reading Room. Dark colours: ground #111827, ink #F3F4F6, secondary #C7CCD4, overdue #FF8A70.
13. **Tile.** Label "Do: Add a reminder", a plus icon, no state. When locked it calls `unlockAndRun` and then opens Add; on Android 14 and later it uses `startActivityAndCollapse(PendingIntent)`.
14. **Shortcuts and links.** Static shortcuts "Add a reminder" (`do://add`) and "Today" (`do://today`, the reminder list until the Today tab exists), with an `entry=SHORTCUT` extra. `do://add?text=` takes `%20` or `+` for spaces, any UTF-8 (emoji), and an empty, missing or bad value opens an empty Add; text is cleaned like a share. Other hosts or paths are ignored. The `https://` app link stays out of scope. No direct-share targets (sharing shortcuts) were added.
15. **Pure files** added to `CorePurityTest`: `capture/ShareText.kt`, `capture/DeepLinks.kt`, `capture/CaptureEntry.kt`, `capture/AgentRequests.kt`, `widget/WidgetModel.kt`. `AgentRequests.kt` uses `AlertLevel` from `alarm/AlertLevel.kt`, which is not itself on the list.

### AT map (unit)
| AT | Tests |
| --- | --- |
| 1 | `capture/ShareTextTest`: `at1KeepsOnlyTheFirstLine`, `at1CutsToTwoHundredCharacters`, `at1EmptyTextIsRefused`, `at1ParsesTheDateAndKeepsItsWords`, `at1KeepsARepeatPhrase`, `at1NoDateMeansTomorrowNineAm`, `at1DateOnlyTextKeepsItsTitle` |
| 2 | `capture/CaptureCommandsTest`: `at2EntriesMapToTheirOrigins`, `at2ShareSavesWithTheShareOrigin`, `at2ShareQuickChoiceAndNoCaller`, `at2WidgetAddAndDoneUseTheWidgetOrigin`, `at2TileShortcutAndLinkSaveAsUser`, `at2AgentUsesTheAgentOrigin` |
| 3 | `capture/CaptureCommandsTest`: `at3CapSendsShareAndWidgetToThePaywall`, `at3CapGivesTheAgentALimitReachedError` |
| 4 | `capture/AgentFunctionsTest`: `at4AlarmRequestIsHeldAtNormal`, `at4OnlyAgentRequestsAboveNormalAreHeld` |
| 5 | `capture/DeepLinksTest`: `at5DecodesSpaces`, `at5DecodesEmoji`, `at5EmptyTextOpensAnEmptyAdd`, `at5TextIsCleanedLikeShare`, `at5OtherLinksAreRefused`, `at5LaunchRequestsPickTheEntry` |
| 6 | `capture/AgentFunctionsTest`: `at6FlagOffRefusesEveryFunction`, `at6ReleaseHasAppFunctionsOff`; plus `checkAlarmManifests` (release manifest) and the three CI commands |

Also: `agentRequestsAreChecked`, `listAndCompleteFollowTheirLimits`, `commandListenersRunAfterEachCommandForTheWidgets`, `quickChoicesLandWhereTheSheetSays`, `widget/WidgetModelTest`.

### Phone checks (Mark with Tim)
- AT-7: share from Messages or WhatsApp; the sheet should show Fri at 5:30 pm with `from "Friday after work"`; Save returns to the chat and a toast says "Added to Do".
- AT-8: tick from the large widget, then check the app list and that the notification for that reminder is gone. Also check the widgets update after adding in the app, after a fire (overdue count), after reboot, and in dark mode.
- AT-9: tile from the lock screen should ask for unlock first, then open Add. Do not remove the lock.
- AT-10: long-press the icon, both shortcuts. Optional: `adb shell am start -a android.intent.action.VIEW -d "do://add?text=Buy%20milk%20tomorrow"`.
- AT-11: NOT RUN (no AppFunctions service in this build; see above).
- Worth a look: Share at the free cap (paywall, back returns to the sender), and that Do does not appear when sharing a photo or a contact.

### Fixes after QA (28 Nov 2026, QA report `specs/12-qa-report.md`, tests `capture/Spec12QaTest.kt`)
All 8 failing QA tests now pass with production changes only; no QA or earlier test was changed, except `at5LaunchRequestsPickTheEntry`, which now passes `trusted = true` for the internal Add action and also checks that an untrusted one is ignored.
- **BUG-S12-01 (links read as dates).** `CaptureDrafts.fromText` hides each `http://`, `https://` or `www.` link from the parser with a same-length run of `x`, so parser spans still index the original line. A link on its own is the title as it was, with the default due; text plus a link parses only the rest, and the link stays in the title.
- **BUG-S12-02/03 (agent dueAt).** A negative `dueAt` is refused (only null or 0 mean omitted). A `dueAt` more than 10 years ahead (`AgentRequests.MAX_YEARS_AHEAD`) is refused. The past limit stays at one minute of grace, stricter than the suggested day, because an agent has no reason to set a past time.
- **BUG-S12-04 (hold on every agent path).** `ReminderCommands.update` and `setAlertLevel` with `Actor.AGENT` and a level above Normal now keep the level in force (update saves the other fields with the current level) and call the new `Scheduler.holdAlertLevel`, which stores `requestedAlertLevel` and `approval=PENDING`, bumps the version and writes a `LEVEL_CHANGED` event with `held=<level>`. An agent setting Normal is applied as usual (it can only lower). Spec 13 can rely on this.
- **BUG-S12-05 (invisible text).** `ShareText.clean` treats a line as blank when nothing is left after removing format characters (`\p{Cf}`: directional marks, isolates, zero-width characters). They are not removed from the kept text, because joined emoji need the zero-width joiner.
- **BUG-S12-06 (grapheme cut). This changes Spec 9 code:** `cutText` in `alarm/CommandTypes.kt` now cuts at the last `java.text.BreakIterator.getCharacterInstance()` boundary at or before the limit, so flags and joined emoji are never split. That also changes `Origin.clipSourceId` and `EventLog.clipDetail`, which use it. `BreakIterator` is fully qualified so the file still has no imports (CorePurityTest).
- **BUG-S12-07 (links claiming a surface).** A `do://` link may claim only `SHORTCUT`; any other `entry` extra is recorded as `DEEP_LINK` (source USER). The widgets and the tile now open Add through a non-exported `activity-alias` (`.InternalAdd`), and `ACTION_ADD` honours its entry only when it arrives through that alias. From any other app it is `APP`.
- **BUG-S12-08 ("every day after work").** `parseSimpleEvery` no longer stops at "after" when it is "after work", so "every day after work" repeats daily at 5:30 pm. Other "every N after ..." phrases stay refused, and the whole DateParser suite passes.
- **Smaller fixes.**
  - The share sheet follows dark mode: design colours, dark ground #111827.
  - The title field stops at 200 characters (grapheme-safe).
  - A save survives rotation: the result is held in a `ShareSaveModel` ViewModel and the write runs on the app scope.
  - The misplaced `DebugReceiver` doc comment is back on `unlockedLaunch`.
  - `WidgetRefresher` skips the refresh before first unlock instead of logging a failure on every locked fire.
- **Not changed:** `callerPackage` through the system Sharesheet is often null or the chooser's package. The phone check stays in place.
