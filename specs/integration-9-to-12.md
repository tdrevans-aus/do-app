# Trial integration: Specs 9 to 12

Branch `do/integration-specs-9-to-12`, started at Spec 9's latest commit (06abbea, restore returns `RestoreResult`). The branch only checks that the four specs fit together. It will not be merged as it is. Integration engineer: Claude, 28 Nov 2026.

## Merges
All three are `git merge --no-ff` merge commits, done in this order. There were no rebases or force-pushes.

| # | Merged | Merge commit | Conflicted files |
| --- | --- | --- | --- |
| 1 | `origin/do/spec-10-subscription-billing` (b1585b7) | dd3a297 | none |
| 2 | `origin/do/spec-11-stuff-you-should-do` (4962324) | f570bf5 | `AppContainer.kt`, `RemindersUi.kt`, `UnlockScreen.kt` |
| 3 | `origin/do/spec-12-capture-surfaces` (260e935) | 859fd57 | `debug/AndroidManifest.xml`, `DebugReceiver.kt`, `AppContainer.kt`, `ReminderCommands.kt`, `RemindersUi.kt`, `strings.xml`, `CorePurityTest.kt` |

## Conflicts and how they were resolved

### Merge 1: Spec 10
- No conflicts in the text.
- Spec 9's capped restore calls `FreeTier.blocksAdd(active, paidUnlocked())`. Spec 10 left the `paidUnlocked` parameter unchanged, and `AppContainer` now passes `SubscriptionState.of(record).liftsCap` to it. So a restore past the undo window is blocked for FREE, EXPIRED and PENDING users, the same way an add is. No code change was needed.

### Merge 2: Spec 11
- **`UnlockScreen.kt`.** The screen is Spec 10's Do Unlimited paywall. Spec 10's "Keep 5 for free" button used to just close the screen. It now calls Spec 11's optional `onKeepFree` when that is given (only when the paywall is opened from first-run picks), and otherwise closes as before. Spec 11 had added its own separate "Keep 5 for free" button. That button is dropped so the screen does not show two, and so is its string `unlock_keep_free`, which had a hard-coded 5. The wording now comes from Spec 10's `paywall_keep_free`, which reads the cap. Spec 11's alarm-red Buy button is kept. Behaviour from picks is unchanged:
  - Back adds nothing and returns to step 4 with the ticks as they were.
  - "Keep 5 for free" adds in order and stops at the cap (`Scheduler.add` refuses the rest).
- **`RemindersUi.kt`.** Spec 10's `onManage` and Spec 11's `onKeepFree` are both passed to `UnlockScreen`.
- **`AppContainer.kt`.** The imports are a union of both specs.
- **`SysdViewModel.unlocked()`** (no conflict in the text, but it did not compile). Spec 11 read `EntitlementRecord.unlocked`, which Spec 10 renamed to `active`. It now reads `SubscriptionState.of(record).liftsCap`, the same check `Scheduler.add` uses, so the picks counter and pre-ticks treat EXPIRED and PENDING users as free.

### Merge 3: Spec 12
- **`ReminderCommands.restore`.** It keeps Spec 9's `RestoreResult` return type and Spec 12's `.committed()`, so the widgets refresh after a restore. Spec 12's agent hold in `add`, `update` and `setAlertLevel` merged cleanly and is unchanged.
- **`AppContainer.kt`, the debug manifest, `DebugReceiver` (the `when` branches and the constants), `strings.xml` and `CorePurityTest.CORE_FILES`.** Each is a union of Spec 11 and Spec 12. Spec 11's `SPEC11_CORE` list is kept. Checked that `strings.xml` has no duplicate names and that the debug receiver has no new duplicate extras. `EXTRA_AGENT_LEVEL` and `EXTRA_LEVEL` both being `"level"` was already true on Spec 12.
- **`RemindersUi.kt`.** It keeps Spec 11's `catalogueAdd`, `finishPicks` and onboarding effects, and also Spec 12's `addEntry`, `addText` and `openAdd`. Spec 12's `do://today` goes to `SCREEN_LIST`, which is now Spec 11's Today tab.
- **`ShareActivity.kt`** (no conflict in the text, but it did not compile). Spec 12's share paywall now passes Spec 10's required `onManage`. To do that, `openSubscriptionPage` in `RemindersUi.kt` changed from `private` to `internal`. The cap check after the paywall now reads `SubscriptionState.liftsCap` in place of the renamed `EntitlementRecord.unlocked`.
- **Checked with no change needed:**
  - Spec 12's `cutText` (grapheme cut) and its `DateParser` "after work" change pass every Spec 11 test.
  - Spec 11's catalogue adds go through `ReminderCommands.add`, so they refresh the widgets.
  - `build.gradle.kts` merged on its own: Spec 11's Robolectric test dependency, Spec 12's Glance and WorkManager dependencies, and the `checkAlarmManifests` checks from both Spec 10 (`FakeSubscriptionBilling`) and Spec 12 (AppFunctions, `FOREGROUND_SERVICE`).

## Tests changed
Only one test changed: `catalogue/CatalogueActionsTest.at7RemoveSoftDeletesAndTheItemIsNoLongerAdded`.
- **Change:** `assertTrue(fx.commands.restore(...))` became `assertEquals(RestoreResult.RESTORED, fx.commands.restore(...))`, and the import was added.
- **Why:** Spec 9's `restore` now returns `RestoreResult`, not `Boolean`.
- **Effect:** It asserts the same outcome. The fixture is paid by default, so the cap does not apply.

`CaptureCommandsTest.commandListenersRunAfterEachCommandForTheWidgets` calls `restore` without using the result, so it needed no change. No test was deleted, weakened or skipped.

## Results
Unit tests (`testDebugUnitTest`) after each merge:

| After | Tests | Failures | Skipped |
| --- | --- | --- | --- |
| Spec 10 | 196 | 0 | 0 |
| Spec 11 | 266 | 0 | 0 |
| Spec 12 (final) | 334 | 0 | 0 |

The final count of 334 adds up as follows:
- 166 at Spec 9 3235db6
- plus 3 from Spec 9's restore fix
- plus 27 from Spec 10
- plus 70 from Spec 11
- plus 68 from Spec 12

Builds (run on the final tree):

| Command | Result |
| --- | --- |
| `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintDebug` | passed, lint 0 errors (warnings only: UseKtx, GradleDependency, UnusedResources and similar) |
| `assembleDebug -PexactAlarmMode=SCHEDULE` | passed, including `checkAlarmManifests` |
| `assembleRelease` | passed (unsigned APK) |

## Questions for Gareth (interactions no spec covers)
1. **First run and capture from outside the app.** Spec 11 starts steps 3 and 4 the first time there is an active reminder. If that first reminder comes from Spec 12 (share, widget, tile or agent), the steps open the next time Do is opened, not after a save in the app. Is that what you want?
2. **A link or shortcut during first run.** If a `do://` link, shortcut, widget or tile opens Do while steps 3 or 4 are showing, Spec 12 switches to Add or Today. Onboarding is then left as "started" and does not come back. Should a launch request wait until first run ends, or should onboarding stay pending?
3. **Share at the cap and "Keep 5 for free".** On the share paywall, "Keep 5 for free" closes it like Back. Do returns to the sending app and the shared text is not saved. From first-run picks, the same button adds what fits. Share has only one item, so nothing fits. Is losing the shared text acceptable, or should Do say so (for example in a toast)?
4. **The paywall from picks after buying.** Spec 10's paywall shows "You have Do Unlimited" and Manage subscription. Back then returns to step 4 with the ticks kept, and the user taps Add again, as Spec 11's build notes describe. Spec 11's section 3 says "After buying, Back adds them all". That was left as built; please confirm which you want.
5. **Hard-coded 5 in Spec 11 copy.** The picks counter plurals say "more than the free 5" with a fixed 5, while Spec 10's paywall reads the number from `FreeTier.ACTIVE_REMINDER_CAP`. Not changed here, because that would be a copy change rather than integration work.
