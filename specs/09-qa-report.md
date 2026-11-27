# Spec 9 QA report

QA by a Claude test agent (host only), independent of the build agent. Branch `do/spec-9-data-foundation`, reviewed at head `bf9d41e` against base `ce9048a` (Spec 8).

Run window: Fri 27 Nov 2026, 15:56 to 16:20 AWST (one pause in the middle for a usage limit).

## Verdict

**Needs fixes before merge.** The data foundation is solid: the migration, the real SQL, soft delete, purge, retention, backup schema 2 and the caller rewiring all hold up under independent tests, and nothing a button does has changed. Four new QA tests fail. They expose three bugs: one Medium that a user can see (a restored overdue reminder's first alert can be held by Do Not Disturb), one Medium data-integrity bug (merge import lowers `version`), and one Low (the 200-character cut can split a character).

**The branch is red with the QA tests.** `testDebugUnitTest` now fails with 4 of 165 tests failing. They are expected failures, each marked `// QA: fails until ...`. The fix agent should make them pass and should not delete them.

## Commands run and results

| Command | Result |
| --- | --- |
| `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintDebug` at `bf9d41e` | BUILD SUCCESSFUL. 146 tests in 20 classes, 0 failures, 0 skipped. Lint clean. `checkAlarmManifests` ran (USE). |
| `./gradlew --no-daemon --max-workers=2 assembleDebug -PexactAlarmMode=SCHEDULE` | BUILD SUCCESSFUL. `checkAlarmManifests` passed (SCHEDULE). |
| `./gradlew --no-daemon --max-workers=2 assembleRelease` | BUILD SUCCESSFUL. No keystore, so the output is `app-release-unsigned.apk`. |
| Release APK check (`aapt2 dump xmltree`, `strings` on the dex files) | The manifest has no `DebugReceiver` and no `com.evansappstudio.doapp.debug` action. The dex has no `DebugReceiver` class. The only Spec 9 debug-flavoured text in release is the `DoAlarmLog` format strings (`DoAlarm meta`, `events end`, `purge removed`), which are unused log helpers in main, as the build notes say. Acceptable. |
| `./gradlew ... assembleDebug testDebugUnitTest lintDebug --continue` with the QA tests | assembleDebug and lintDebug pass. testDebugUnitTest: **165 tests, 4 failed** (the 4 QA bug tests below). Every builder test and every Spec 1 to 8 test still passes. |
| Schema check (`4.json` against `5.json`) | Every version 4 column of `reminder` is identical in 5.json. The 10 new columns have the types, nullability and defaults the migration creates. |

## New QA tests

`app/src/test/java/com/evansappstudio/doapp/data/Spec9QaMigrationTest.kt`: 6 tests, all pass. They migrate a realistic version 4 table (plain, weekly from due, every 3 days from completion, weekly Mon/Wed/Fri Critical, a done Prominent row, monthly with a non-ASCII title, a null nag). Then they run the **real `@Query` text parsed from `ReminderDao.kt` and `ReminderEventDao.kt`** through sqlite-jdbc. The builder's tests only exercise the hand-written in-memory DAO, so these are the only host tests of the real SQL. They cover:
- every old column of every row unchanged;
- the deletedAt filters on `observeAll`, `active`, `activeCount` and `all`, while `getById` and `allIncludingDeleted` still return the row;
- every live-row write refusing a deleted row;
- exactly one version bump per write;
- the purge boundary;
- `trim` keeping the newest 200 (with tied timestamps) and leaving other reminders alone;
- the reminder table indices matching 5.json.

`app/src/test/java/com/evansappstudio/doapp/alarm/Spec9QaTest.kt`: 13 tests, 4 fail by design.

| Test | Result |
| --- | --- |
| `qaMergeOntoALiveRowNeverLowersVersionOrUpdatedAt` | **FAILS** (bug 2) |
| `qaTheCatchUpFireAfterRestoringAPastDueRowIsLoggedAsFired` | **FAILS** (bug 1) |
| `qaTheCatchUpFireAfterRestoringAPastDueRowStillAlertsUnderDnd` | **FAILS** (bug 1) |
| `qaTheSourceIdCutDoesNotSplitASurrogatePair` | **FAILS** (bug 3) |
| `qaAOneMinuteNagForADayKeeps200EventsAndNeverBumpsVersion` (1,440 fires) | pass |
| `qaCommandsOnASoftDeletedRowChangeNothingAndWriteNoEvent` (all 9 mutators) | pass |
| `qaReplaceImportClearsSoftDeletedRowsAndAllTheirEvents` | pass |
| `qaNoEventEverCarriesTheTitle` (every command plus a fire) | pass |
| `qaASnoozeTappedBeforeUnlockIsRecordedOnceWhenRoomOpens` (two reschedules, one event) | pass |
| `qaPurgeAndRestoreAgreeOnTheThirtyDayBoundary` | pass |
| `qaDeletingADoneRowKeepsItDoneAndRestoreDoesNotArmIt` | pass |
| `qaAZoneChangeBumpsAWallClockRepeatOnceWithASystemEdit` | pass |
| `qaARealSchemaOneFileImportsAndMergeRestoresTheSoftDeletedMatch` (hand-written Spec 8 file with quiet keys) | pass |

## AT by AT

| AT | Verdict | Reason |
| --- | --- | --- |
| 1 Migration 4 to 5 | PASS | The builder's test is thin (two similar rows). The QA migration test adds done, Critical, Prominent, completion, weekday and monthly rows, plus a full before/after compare of old columns. |
| 2 Schema 5 exported | PASS | Columns match the migrated table. QA adds the index check Room also makes. |
| 3 Add writes source and event | PASS | Includes the sourceId cut and unknown category. See bug 3 for a multi-byte edge. |
| 4 Every command bumps once, one event | PASS | Also checked at the SQL level by QA. Before-unlock Stop writes no event and no bump (documented choice). |
| 5 Fires write events, no bump | WEAK | FIRED, NAGGED and HELD (DND) are covered. HELD is never written for quiet hours, because nags are placed outside the window; that is a defensible reading (build note 6), but Gareth should confirm it. Bug 1 shows FIRED is wrong for a catch-up after restore. |
| 6 Soft delete hides everywhere | WEAK | Cap, list, active, count, alarm and fire are covered. Export is tested through `dao.all().exportable()`, not `BackupController`. `ReminderViewModel.reminderOrNull` and `HealthMonitor` are Android-bound and untested; I checked them by reading and they are correct. The real SQL filters are now tested by QA. |
| 7 Restore | PASS (with bug 1) | Re-arms and catches up. The catch-up fire is then misclassified as a nag. |
| 8 Purge | PASS | The QA test adds the exact 30-day boundary and checks restore and purge agree. |
| 9 Event retention | PASS | The builder test uses the fake trim. QA runs the real trim SQL and a one-day, one-minute nag. |
| 10 Atomic writes | PASS | The fixture's snapshot transaction proves the event and the reminder change share one `inTransaction`. By reading, every Room write in `Scheduler` is inside `tx { }`, and `AppContainer` maps it to `withTransaction`. |
| 11 Backup round trip | PASS | |
| 12 Old backups import | PASS | The builder builds "schema 1" by stripping keys from a schema 2 encode. QA adds a hand-written Spec 8-shaped file. |
| 13 Merge restores soft-deleted match | PASS | But see bug 2: the same code lowers version on a live match. |
| 14 No direct Scheduler mutations | WEAK | The scan only matches an identifier spelt `scheduler` or `scheduler()`. `val s = container.scheduler; s.markDone(id)` or `with(container.scheduler) { markDone(id) }` would pass. Real callers today are all correct (checked by grep). |
| 15 Core purity | PASS (note) | The test passes and catches an added import. Note: `ReminderCommands.kt` imports `data.Reminder` (a Room `@Entity`) and `Scheduler`, so the "pure" file is not portable in practice. The spec's own escape hatch (`alarm/CommandTypes.kt`) is arguably required. Low, see note N4. |
| 16 Existing tests pass | PASS | The only change is the `QuietSchedulerTest` fixture moving to `SchedulerFixture.kt`. I compared it line by line: same behaviour, and the transaction now snapshots instead of running the body plainly. |
| 17 CI green | PASS at `bf9d41e`; **FAIL with the QA tests** | All four Gradle commands pass on the builder's head. Release has no debug receiver. With the QA commit, testDebugUnitTest is red until bugs 1 to 3 are fixed. |
| 18 Upgrade on phone | NOT RUN | Phone only. |
| 19 Events on phone | NOT RUN | Phone only. |
| 20 Soft delete and restore on phone | NOT RUN | Phone only. |
| 21 Backup on phone | NOT RUN | Phone only. |

## Bugs

### Bug 1 (Medium): restoring an overdue reminder treats its first alert as a nag
- Where: `app/src/main/java/com/evansappstudio/doapp/alarm/Scheduler.kt:463` (`restore`: `NagSchedule.catchUp(stored.dueAt, now)` gives `now`, which is after `dueAt`). `onFire` at `Scheduler.kt:580` (`wasNag` from `pendingNag()`) and `holdNagIfNeeded` at `Scheduler.kt:988` then treat it as a nag.
- Effect:
  - (a) The first fire is logged `NAGGED`, not `FIRED`.
  - (b) With quiet hours set to follow Do Not Disturb and DND on, the first alert after restore is **held and not shown**. Spec 8 says first fires always alert.
- Reproduce: `Spec9QaTest.qaTheCatchUpFireAfterRestoringAPastDueRowIsLoggedAsFired` and `...StillAlertsUnderDnd`. Add a reminder due in 1 minute, delete it, move the clock a day on, restore it, fire it.
- Context: `add` with a past due behaves the same in Spec 8, but the UI does not offer a past due, so it was not reachable. Restore makes it reachable, and the next UI spec plans an undo-delete.
- Suggested fix: arm the catch-up so the mirror keeps the "first fire" marker. Either store `nextFireAt = dueAt` and arm the alarm at `now`, as `rescheduleFromMirrorLocked` already does ("A missed first fire stays nextFireAt == dueAt"), or use `ScheduleResolver.resolve(...)` as `rescheduleAllLocked` does. Apply the same to `add` and `finishDoneLocked` for consistency.

### Bug 2 (Medium): merge import lowers `version` and `updatedAt` on an existing row
- Where: `Scheduler.kt:553` (`dao().update(incoming.copy(id = local.id))` in `mergeAll`).
- Effect: merging an older backup over a row that has changed since puts the file's `version` and `updatedAt` back. In the test, version goes from 5 to 1. Spec section C says every write through `ReminderCommands` sets `updatedAt = clock()` and `version + 1`. `version` exists to be compared by sync and the agent API, so it must never go down. The same applies when a merge restores a soft-deleted row. Build note 8 keeps the exported values so AT-11 passes; that reasoning holds for Replace (a fresh table), not for Merge onto a local row.
- Reproduce: `Spec9QaTest.qaMergeOntoALiveRowNeverLowersVersionOrUpdatedAt`.
- Suggested fix: when `local != null`, write `version = max(local.version, incoming.version) + 1` and `updatedAt = clock()`. Keep the exported values for inserts and for Replace. `BackupMetaTest.at13` asserts `version == 7` for an incoming 7 over a local 2, and would become 8 under this fix. Update that assertion with a note.

### Bug 3 (Low): the 200-character cut can split a surrogate pair
- Where: `app/src/main/java/com/evansappstudio/doapp/alarm/ReminderCommands.kt:56` (`clean.substring(0, MAX_SOURCE_ID)`), used for `sourceId` and `templateId`. The same pattern is in `EventLog.clipDetail` (`ReminderCommands.kt:140`). The detail is never user text today, so that one is harmless.
- Effect: an id with an emoji or other non-BMP character at position 200 is stored ending in a lone high surrogate. That is invalid UTF-16 and turns into a replacement character in Room and in the backup JSON.
- Reproduce: `Spec9QaTest.qaTheSourceIdCutDoesNotSplitASurrogatePair`.
- Suggested fix: when `Character.isHighSurrogate(clean[MAX_SOURCE_ID - 1])`, cut at `MAX_SOURCE_ID - 1`.

### Notes (no failing test; fix or confirm)
- **N1 (Low to Medium, robustness): a purge failure stops boot rescheduling.** `purgeLocked` runs first in `rescheduleAllLocked` (`Scheduler.kt:695`). Any exception there aborts the reschedule at app start, boot, time change or update, so alarms are not re-armed. One known trigger: `deleteForReminders` uses `IN (:uuids)` with no chunking (`ReminderEventDao.kt:24`). SQLite on API 26 to 29 allows 999 bound variables, so purging more than 999 rows at once would throw. That is unlikely now but possible after a big catalogue import. sqlite-jdbc on the host allows 32,766, so I could not reproduce it here. Suggested fix: wrap the purge in try/catch with `DoAlarmLog.handlingFailed`, and either delete events with a subquery (`DELETE FROM reminder_event WHERE reminderUuid IN (SELECT uuid FROM reminder WHERE deletedAt < :cutoff)`) or chunk the list.
- **N2 (Low): the import log line shows the app's schema, not the file's.** `DoAlarmLog.backupImport` (`DoAlarmLog.kt:217`) prints `schema=${BackupNames.SCHEMA_VERSION}`, so importing a Spec 8 file logs `schema=2`. Build note 12 says the logs "print the real schema number". For export and auto that is true; for import it is not. Pass `document.schemaVersion` through. This matters if AT-21 scripts grep for the schema.
- **N3 (Low, spec question): restore ignores the free-tier cap.** A free user at 5 can delete one, add one, then restore the deleted one and have 6 active. Restore is debug-only today, but the undo-delete snackbar planned for the new UI will expose it. Gareth to decide: refuse restore at the cap, or allow it as an undo.
- **N4 (Low, spec intent): `ReminderCommands.kt` is in the pure set but imports `data.Reminder` (Room entity) and `Scheduler`.** It passes the import-line check, but the file is not shareable with iPhone as it stands. Section G's fallback (move `Origin`, `ReminderSource`, `Actor`, `EventType`, `Approval`, `ReminderCategory`, `SoftDelete` and `EventLog` into `alarm/CommandTypes.kt` and list that file instead) is the cleaner fit.
- **N5 (Low): an event-insert failure now fails a Done or snooze after the alarm is cancelled.** `markDone` and `snooze` cancel the alarm and dismiss the notification before the transaction (unchanged order, build note 14). If the event insert throws, for example on a full disk, the reminder stays active in Room with no alarm until the next reschedule. Before Spec 9 only the reminder write could fail there; now the event write is a second way to fail. This is acceptable, but the receivers should keep logging `handlingFailed`, as they do.
- **N6 (trivial):** debug `ADD --es source IMPORT` is accepted. The spec lists only USER, CATALOGUE, AGENT, SHARE and WIDGET.
- **N7 (test strength):** AT-14's scan is name-based (see the AT table). Consider also making the `Scheduler` mutators reachable only through `ReminderCommands`, for example by moving them into an internal interface that only `ReminderCommands` receives. That is a later refactor.

## Behaviour checks (by reading and tests)
- **Buttons:** Done, snooze, snooze-all, Stop, the Prominent screen buttons and volume keys, edit, delete and alert level all call the same `Scheduler` logic as before, with an `Origin` added. Arm and cancel order is unchanged: cancel and dismiss come before the Room write for Done, snooze and Stop, and delete still cancels, dismisses and removes the mirror entry. Edit arms after the transaction.
- **Quiet hours and DND (Spec 8):** `reconcileQuietLocked`, `holdNagIfNeeded`, `deferIfNag` and `deferNag` are unchanged. `QuietSchedulerTest` passes unchanged.
- **Direct boot:** the before-unlock paths (`markDone`, `applySnooze`, `stopAlert`, the mirror branch of `onFire`, `rescheduleFromMirrorLocked`) do not touch Room or events. A pending Done or snooze is written once at the first unlocked reschedule with `Actor.SYSTEM` and `deferred=1`. QA checked that a second reschedule does not write it again. A Stop before unlock is never recorded (documented).
- **Event volume:** the QA test fires a one-minute nag 1,440 times. It ends at exactly 200 `NAGGED` events, version stays 1 and every fire alerts. Each fire costs one insert and one trim in one transaction.
- **Titles in events:** none, across every command.

## Risks for the phone ATs (NOT RUN)
- **AT-18 (upgrade):** the migration is additive and the host tests show old columns survive and the indices match 5.json. Room's on-device validation is the remaining unknown; if it fails, the app crashes on first open. Export a backup first, as the spec says. `DUMP_META` on Tim's real reminders should show `version=1` unless a timezone alignment ran at first open (that writes `version=2` with a SYSTEM `EDITED` event; this is expected, not a bug).
- **AT-19 (events):** the sequence holds only if every fire happens after first unlock. A fire while the phone is still before first unlock writes no event. Do not reboot during the run. If the fixture is snoozed and the snooze due is already past (not normal), bug 1's misclassification can show `NAGGED` for the post-snooze fire.
- **AT-20 (delete and restore):** if the fixture is overdue when restored and DND with follow-DND is on, the first alert after restore is held (bug 1). Run with DND off, or fix bug 1 first.
- **AT-21 (backup):** the import log line says `schema=2` whatever the file was (N2). Scripts should not rely on it to tell file versions apart.
- **Spot-check from Specs 2, 5 and 8:** notification Done, Snooze and Stop, and the Prominent screen buttons on a Critical reminder, including the volume key Stop.
