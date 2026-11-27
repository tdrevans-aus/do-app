# Spec 9: Data foundation

Owner: Gareth (PM). Build: a Claude build agent, one PR to `tdrevans-aus/do-app`. QA: Mark (phone), a Claude test agent (host). Status: draft by Claude, 27 Nov 2026 (AWST), approved in principle by Tim the same day ("Spec 9 approved"). Gareth to review.
Sources: `docs/strategy/DO-PRODUCT-STRATEGY.md` sections 7 and 9 (on branch `docs/pricing-and-tiers-decision`); `docs/design/DESIGN-PASS-1.md` section "What this means for Spec 9 (data)"; the 26 Nov 2026 design paper's Spec 9 proposal as summarised in `docs/claude-handoff/DO-HANDOFF-for-Claude.md` section 5 item 3; code read from branch `do/spec-8-quiet-hours-and-repeating-cap` at `ce9048a` (Spec 8 with the repeating cap removed). Rule: describe Do's own behaviour; do not copy or cite another app.

## 1. Goal
Give every later feature (Stuff You Should Do, capture surfaces, the cloud agent API, the new UI, and later an iPhone app) one safe way to create and change reminders, and a record of what happened to each one. Concretely: one command layer that every entry point calls, new reminder fields for where a reminder came from and how it has changed, soft delete, an append-only event log, backup support for the new fields, and a guard that keeps the core logic free of Android code so it can be shared with iPhone later. Nothing the user sees changes in this spec.

## 2. Starts from Spec 8
Build on branch `do/spec-8-quiet-hours-and-repeating-cap` at `ce9048a`. If PR #8 has merged by the time this is built, rebase onto `main` instead; the code is the same. Package `com.evansappstudio.doapp`, minSdk 26, target and compile 36, Kotlin, Compose, Room 2.8 via KSP at schema version 4, `setAlarmClock()`.

`Scheduler` (`alarm/Scheduler.kt`) is already the single writer for reminders, guarded by one mutex. Its public suspend functions are the entry points today: `add`, `update`, `markDone`, `snooze`, `snoozeShowing`, `stopAlert`, `setAlertLevel`, `setRepeat`, `shiftDue`, `setTitle`, `delete`, `replaceAll`, `mergeAll`, `onFire`, plus the reschedule paths. Callers are `ReminderViewModel`, `DoneReceiver`, `SnoozeReceiver`, `StopReceiver`, `ProminentAlertActivity`, `AlarmReceiver`, `BackupController` and the debug `DebugReceiver`.

This spec does not change `AlarmScheduler`, `setAlarmClock()`, exact-alarm mode, channels, alert-level behaviour, the nag maths, quiet hours, repeats, parsing, Quick Times, the health checks, billing or any screen.

## 3. Scope

### A. Who did it: `Origin`
A small pure type, in a new file `alarm/ReminderCommands.kt` (no Android imports):

```kotlin
enum class ReminderSource { USER, CATALOGUE, AGENT, SHARE, WIDGET, IMPORT }
enum class Actor { USER, NOTIFICATION, SYSTEM, AGENT, IMPORT, DEBUG }
data class Origin(val actor: Actor, val source: ReminderSource = ReminderSource.USER, val sourceId: String? = null)
```

- `ReminderSource` is stored on the reminder: where it was created. It never changes after create, except by import, which keeps the exported value.
- `Actor` is stored on each event: who caused this change.
- `sourceId` is the catalogue item id or the agent's id. Free text, at most 200 characters (longer is cut, not refused).
- Defaults: `Origin(Actor.USER)`.

### B. The command layer: `ReminderCommands`
`ReminderCommands` is the one public way to change reminders. It wraps `Scheduler`; it does not replace it.

- Same operations as today's `Scheduler` entry points, each taking an `Origin` as its first parameter: `add(origin, title, dueAt, nagMinutes, rule, alertLevel, category = null, templateId = null, templateVersion = null)`, `update(origin, ...)`, `markDone(origin, id)`, `snooze(origin, ...)`, `snoozeShowing(origin, ...)`, `stopAlert(origin, id)`, `setAlertLevel(origin, id, level)`, `setRepeat(origin, id, rule)`, `shiftDue(origin, ...)`, `setTitle(origin, id, title)`, `delete(origin, id)`, `restore(origin, id)`, `replaceAll(origin, ...)`, `mergeAll(origin, ...)`. Return types are unchanged.
- `onFire` and the reschedule paths stay on `Scheduler` (they are system events, not commands); `onFire` records events with `Actor.SYSTEM` itself.
- Every caller listed in section 2 moves to `ReminderCommands` with the right actor: UI screens `USER`; notification buttons and the Prominent screen `NOTIFICATION`; import `IMPORT`; `DebugReceiver` `DEBUG` (a debug `ADD` may pass `--es source` and `--es sourceId` to simulate other sources).
- `Scheduler`'s public mutators become `internal` (module-wide; acceptable because this is a single module). A unit test or a simple source scan checks that no file outside `alarm/` and tests calls `scheduler.add`, `scheduler.update`, `scheduler.markDone` and the other mutators directly.
- The event write and the reminder write happen inside the same `guarded` block and the same Room transaction (`inTransaction`, already passed to `Scheduler`), so a reminder change never exists without its event, and the reverse.
- `AppContainer` exposes `commands` alongside `scheduler`.

### C. New reminder fields (Room schema 5)
Add to `Reminder`, all with defaults so existing rows and old backups keep working:

| Column | Type | Default for existing rows | Meaning |
| --- | --- | --- | --- |
| `source` | TEXT NOT NULL | `'USER'` | `ReminderSource` name |
| `sourceId` | TEXT NULL | null | Catalogue item id or agent id |
| `category` | TEXT NULL | null | One of `home`, `renter`, `car`, `kids`, `pets`, `health`, `money`, `digital`, or null |
| `templateId` | TEXT NULL | null | Catalogue item id it came from |
| `templateVersion` | INTEGER NULL | null | Catalogue item version it came from |
| `updatedAt` | INTEGER NOT NULL | `createdAt` (backfilled) | Epoch millis of the last change |
| `version` | INTEGER NOT NULL | `1` | Increments on every change |
| `deletedAt` | INTEGER NULL | null | Set by soft delete |
| `approval` | TEXT NOT NULL | `'NONE'` | `NONE`, `PENDING`, `APPROVED`, `DECLINED`. Stored only; no behaviour in this spec (the cloud agent API spec uses it) |
| `requestedAlertLevel` | TEXT NULL | null | The level an agent asked for, kept beside `alertLevel` (what is in force). Stored only |

- `MIGRATION_4_5` in `data/Migrations.kt`, with the SQL as constants in a `MetaSchema` object (same pattern as `UuidSchema`): one `ALTER TABLE` per column, then `UPDATE reminder SET updatedAt = createdAt`. Also creates the event table (section E) and its index.
- `AppDatabase` version 5, entities `Reminder` and `ReminderEvent`; export the schema to `app/schemas/.../5.json`. `AppContainer` adds `MIGRATION_4_5`.
- Every write through `ReminderCommands` sets `updatedAt = clock()` and `version = version + 1` on the rows it changes (including Done, snooze, repeat advance on Done, and `onFire` only when it changes stored columns; a plain nag fire changes nothing in Room and does not bump `version`).
- `category` and `templateId` are only set by `add`. They are not editable yet.

### D. Soft delete
- `delete(origin, id)` sets `deletedAt = now`, bumps `updatedAt` and `version`, cancels the alarm and the notification, and removes the mirror entry, exactly as hard delete does today for the alarm side. The row stays.
- `restore(origin, id)` clears `deletedAt` and re-arms the reminder through the existing schedule path (a past due catches up as today), if the row is not done. Only valid within the purge window.
- Every query that feeds the UI, counts, scheduling, health, backup export or the free-tier cap ignores rows with `deletedAt IS NOT NULL`: `observeAll`, `active`, `activeCount`, `all` (rename the raw one `allIncludingDeleted` for internal use), and anything else that reads reminders. `getById` keeps returning deleted rows (needed for restore) but every caller that acts on a reminder checks `deletedAt` and treats a deleted row as gone.
- **Purge:** rows deleted more than 30 days ago are removed for good, with their events, on `rescheduleAll` (app start, boot, update). One constant, `SoftDelete.PURGE_AFTER_DAYS = 30`.
- The free-tier cap of 5 does not count deleted rows.
- Replace import (`replaceAll`) still clears everything, deleted rows included, and their events.

### E. The event log: `reminder_event`

```kotlin
@Entity(tableName = "reminder_event", indices = [Index(value = ["reminderUuid", "at"])])
data class ReminderEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderUuid: String,
    val at: Long,
    val type: String,
    val actor: String,
    val sourceId: String? = null,
    val detail: String? = null,
)
```

- Append-only. No update query. Deleting only happens with purge and Replace import.
- Keyed by `uuid`, not local `id`, so events survive export and import of their reminder.
- `type` values (an enum `EventType` in `ReminderCommands.kt`): `CREATED`, `EDITED`, `FIRED` (first fire at due), `NAGGED` (each nag fire that alerts), `HELD` (a nag held by quiet hours or Do Not Disturb), `SNOOZED`, `ALERT_STOPPED`, `LEVEL_CHANGED`, `REPEAT_CHANGED`, `DONE`, `ADVANCED` (a repeating reminder moved to its next occurrence on Done), `DELETED`, `RESTORED`, `IMPORTED`.
- `detail` is short, human-readable key=value text, at most 500 characters (for example `due=1764212400000 nag=5` or `option=2 until=1764213000000`). It never contains the title (titles live on the reminder only).
- **Retention:** keep the newest 200 events per reminder; older ones are dropped when a new one is written. One constant, `EventLog.MAX_PER_REMINDER = 200`. A reminder nagging every minute for a day would otherwise write about 1,440 rows.
- `ReminderEventDao`: `insert`, `forReminder(uuid, limit)`, `trim(uuid, keep)`, `deleteForReminders(uuids)`, `deleteAll`, `count()`.
- Events are local only in this spec. They are not exported in backups (section F) and not sent anywhere.

### F. Backup
- `BackupNames.SCHEMA_VERSION` goes up by one. Export writes, per reminder, the new keys `source`, `sourceId`, `category`, `templateId`, `templateVersion`, `updatedAt`, `version`, `approval`, `requestedAlertLevel`. It never writes soft-deleted rows, and it never writes `deletedAt`.
- Import accepts files from the new and every older schema version. Every new key is optional on decode; missing keys take the column defaults (with `updatedAt` defaulting to the row's `createdAt`). Follow the optional-key rule Spec 8 used for quiet hours in `decodeSettings`.
- An unknown `source`, `category` or `approval` value decodes as the default (`USER`, null, `NONE`), never as a failure.
- Import writes one `IMPORTED` event per imported reminder, with `Actor.IMPORT`.
- Merge import (`mergeAll`) keeps its current match-by-uuid behaviour. If an incoming row matches a soft-deleted local row, the incoming row wins and the local one is restored with the incoming values.

### G. Core logic stays free of Android code
So the core can later be shared with an iPhone app (for example through Kotlin Multiplatform), the pure files must not import Android or AndroidX.

- The pure set today: `alarm/RepeatMath.kt`, `alarm/DateParser.kt`, `alarm/NagSchedule.kt`, `alarm/QuietHours.kt`, `alarm/SnoozeDue.kt`, `alarm/RepeatText.kt`, `alarm/QuickTimes.kt`, `data/RepeatRule.kt`, and the new `alarm/ReminderCommands.kt` types (`Origin`, `ReminderSource`, `Actor`, `EventType`, the constants). If `ReminderCommands` itself needs Android types, keep the pure types in a separate file, `alarm/CommandTypes.kt`, and put that file in the pure set instead.
- Add a unit test, `CorePurityTest`, that reads those source files from `src/main/java` and fails if any line starts with `import android.` or `import androidx.`. The list of files is one constant in the test, with a comment that adding a file to the core means adding it here.
- No file moves or package renames in this spec.

### H. Debug hooks
Debug builds only, on `DebugReceiver`, following the existing patterns and log tags:

- `ADD` gains optional `--es source <USER|CATALOGUE|AGENT|SHARE|WIDGET>`, `--es sourceId`, `--es category`, `--es templateId`, `--ei templateVersion`.
- `DUMP_META --el id <id>` logs one line: `DoAlarm meta id=<id> uuid=<uuid> source=<..> sourceId=<..|none> category=<..|none> templateId=<..|none> templateVersion=<n|none> version=<n> updatedAt=<millis> deletedAt=<millis|none> approval=<..> requestedAlertLevel=<..|none>`.
- `DUMP_EVENTS --el id <id> [--ei limit 20]` logs one line per event, newest first: `DoAlarm event id=<id> at=<millis> type=<..> actor=<..> sourceId=<..|none> detail=<..|none>`, then `DoAlarm events end count=<n>`.
- `DELETE --el id <id>` (soft) and `RESTORE --el id <id>`, each logging `DoAlarm deleted id=<id> deletedAt=<millis>` and `DoAlarm restored id=<id>`.
- `PURGE_NOW` runs the purge with a `--ei days` override (default the constant) and logs `DoAlarm purge removed=<n>`.
- Release must not contain any of these (the existing `checkAlarmManifests` check covers the receiver).

## 4. Out of scope
Any UI change (no undo-delete snackbar yet, no history list on the edit screen; those come with the new UI spec). Exporting events. Syncing, network, accounts, hosting. Agent approval behaviour. The onboarding profile and suggestion state for Stuff You Should Do (they belong to the catalogue spec). Editing `category` or `templateId` after create. Moving files into a new module or package. Kotlin Multiplatform itself. Any change to alarms, quiet hours, repeats, parsing, billing or the free-tier rules.

## 5. Acceptance tests

Host (unit tests, run in CI: `./gradlew assembleDebug testDebugUnitTest lintDebug` plus `checkAlarmManifests` in USE and SCHEDULE):

1. **Migration 4 to 5. Unit.** Build a version 4 table with the existing schema constants (as `UuidMigrationTest` does with sqlite-jdbc), insert two rows, apply `MetaSchema`. Both rows keep every old column; `source = 'USER'`, `version = 1`, `approval = 'NONE'`, `updatedAt = createdAt`, the nullable columns are null; `reminder_event` exists and is empty, and its index exists.
2. **Room schema 5 exported.** `app/schemas/.../5.json` exists, has both tables, and matches the entities (KSP generates it; the build fails if the migration and the entity disagree at runtime, which the instrumented check would catch; host-side, assert the JSON has the new columns).
3. **Add writes source and an event. Unit** (using `QuietSchedulerTest`'s in-memory fixture, extended with an in-memory `ReminderEventDao`). `add(Origin(Actor.AGENT, ReminderSource.AGENT, "agent-1"), ...)` stores `source=AGENT`, `sourceId=agent-1`, `version=1`, `updatedAt=clock`, and one `CREATED` event with `actor=AGENT`.
4. **Every command bumps version and writes one event. Unit.** For update, markDone (plain and repeating), snooze, stopAlert, setAlertLevel, setRepeat, shiftDue, setTitle, delete, restore: `version` goes up by exactly one, `updatedAt` becomes the clock, and exactly one event of the right type is written with the given actor. A repeating Done writes `DONE` then `ADVANCED` (two events, one version bump).
5. **Fires write events but do not bump version. Unit.** First fire writes `FIRED` with `Actor.SYSTEM`; a nag writes `NAGGED`; a nag held by quiet hours or Do Not Disturb writes `HELD`; `version` is unchanged by plain fires.
6. **Soft delete hides the row everywhere. Unit.** After `delete`, the row is absent from `observeAll`, `active`, `activeCount` and export; its alarm is cancelled; the free-tier cap counts it as gone (a free user at 5 who deletes one can add one). `getById` still returns it with `deletedAt` set.
7. **Restore. Unit.** `restore` within 30 days brings the row back with its alarm armed (past due catches up as today), writes `RESTORED`, bumps `version`.
8. **Purge. Unit.** A row deleted 31 days ago is removed with its events on `rescheduleAll`; one deleted 29 days ago stays.
9. **Event retention. Unit.** Writing 205 events for one reminder leaves the newest 200.
10. **Atomic writes. Unit.** If the event insert throws, the reminder change is rolled back (the fixture's `inTransaction` simulates a transaction; assert neither write is visible).
11. **Backup round trip. Unit.** Export then Replace import keeps every new field except `deletedAt`; soft-deleted rows are not exported; one `IMPORTED` event per row with `Actor.IMPORT`.
12. **Old backups still import. Unit.** A schema-version-N-minus-1 document with none of the new keys imports with the defaults; an unknown `source` value imports as `USER`.
13. **Merge restores a soft-deleted match. Unit.** Merge import of a row whose uuid matches a soft-deleted local row restores it with the incoming values.
14. **No direct Scheduler mutations outside the core. Unit.** The source scan in section B passes, and fails if a caller is put back.
15. **Core purity. Unit.** `CorePurityTest` passes, and fails if `import android.util.Log` is added to `RepeatMath.kt` (prove this once by hand in the PR description; don't commit it).
16. **Existing tests still pass.** All Spec 1 to 8 unit tests pass unchanged, except where a test called a now-internal `Scheduler` function from outside its package; such tests move to `ReminderCommands` with `Origin(Actor.DEBUG)` or `Origin(Actor.USER)`.
17. **CI green.** `assembleDebug`, `testDebugUnitTest`, `lintDebug` and `checkAlarmManifests` (USE and SCHEDULE) pass; the release APK has no debug receiver.

Phone (Mark, with Tim; can run after Monday 30 Nov 2026):

18. **Upgrade on the phone keeps everything. Scripted.** Install the Spec 8 build, add `T_s9_*` fixtures through debug `ADD` (one plain, one repeating, one Critical), then install the Spec 9 build over it. `DUMP_META` shows `source=USER version=1` and `updatedAt=createdAt` for each; each still fires on time. Tim's real reminders (for example "Isabelle book list") are not touched, but their count before and after the upgrade is the same.
19. **Events on the phone. Scripted.** For one `T_s9_` fixture: let it fire and nag twice, snooze it once, mark it done. `DUMP_EVENTS` shows `CREATED`, `FIRED`, `NAGGED`, `NAGGED`, `SNOOZED`, `FIRED`, `DONE` in that order with the expected actors (`NOTIFICATION` for the snooze and Done buttons if tapped, `DEBUG` if done by hook).
20. **Soft delete and restore on the phone. Scripted.** Debug `DELETE` a fixture: it disappears from the list and no longer fires; `RESTORE` brings it back and it fires.
21. **Backup on the phone. Manual.** Export to a file, Replace import it: fixtures come back with their `source` and `version`.

## 6. Risks
- **Migration on Tim's phone.** Tim has real reminders. The migration only adds columns with defaults and one backfill; AT-1 and AT-18 cover it. Before installing on the phone, export a backup.
- **Event volume.** A one-minute nag writes an event a minute. The cap of 200 per reminder and the index keep it small; trimming runs inside the same transaction.
- **Behaviour drift while rewiring callers.** Moving every caller to `ReminderCommands` must not change what any button does. AT-16 (all existing tests) is the main guard; the phone ATs from Specs 2, 5 and 8 that exercise notification buttons should be spot-checked in the next phone run.
- **Soft-deleted rows leaking.** A missed query would show a deleted reminder or count it against the cap. AT-6 checks the known reads; the builder must list every `ReminderDao` read in the PR description and say how each handles `deletedAt`.
- **Backup compatibility.** Older apps cannot read the new schema version (they refuse a higher `schemaVersion`, as today). That is expected; say so in the PR.

## 7. Calls
Claude's defaults for Gareth to confirm or change:

1. `ReminderCommands` wraps `Scheduler` rather than replacing it, to keep the diff small and the alarm logic untouched.
2. `source` is set once at create; `actor` is per event.
3. Soft delete with a 30-day purge; restore only within that window.
4. 200 events kept per reminder; events not exported.
5. `approval` and `requestedAlertLevel` are stored now and used later.
6. The core-purity check is a unit test over a listed set of files, not a module split.
7. No UI change.

## Build notes
Built on `do/spec-8-quiet-hours-and-repeating-cap` at `ce9048a` (PR #8 had not merged). Notes by the Claude build agent, 27 Nov 2026.

### Choices where the spec left room
1. **Pure types stay in `alarm/ReminderCommands.kt`.** The file imports no Android or AndroidX code (it only imports `Scheduler`, `Reminder`, `RepeatRule` and `DueShiftMode` from the app), so `alarm/CommandTypes.kt` was not needed. Besides `Origin`, `ReminderSource`, `Actor` and `EventType`, it holds `Approval`, `ReminderCategory` (the eight names; anything else becomes null, case is folded to lower case), `SoftDelete` (`PURGE_AFTER_DAYS = 30`) and `EventLog` (`MAX_PER_REMINDER = 200`, detail cut at 500 characters).
2. **How the wrap works.** `ReminderCommands` is a thin facade. Each internal `Scheduler` mutator takes the `Origin` as a trailing parameter (default `Origin(Actor.USER)`, or `Origin(Actor.IMPORT)` for `replaceAll` and `mergeAll`), so the event is written by `Scheduler` inside its own `guarded` block. Within that block the reminder write and the event insert (plus the retention trim) run in one `inTransaction` call, before any alarm, notification or mirror side effect. The DAO writes return the number of rows changed, and the event is written only when a row changed.
3. **Commands that change nothing write nothing.** An edit that changes nothing (`EditResult.UNCHANGED`), setting the level it already has, setting the same repeat, the same title, or a shift that lands on the same due: no version bump, no event.
4. **Stop bumps version.** `stopAlert` changes only the mirror, but AT-4 lists it, so it uses a `touch` query (updatedAt and version only) beside the `ALERT_STOPPED` event.
5. **Before unlock.** Room is closed in direct boot, so mirror-only paths (Done, snooze, Stop and fires before the first unlock) write no event. When `rescheduleAll` later applies a pending Done or snooze from the mirror, it writes `DONE` (and `ADVANCED`) or `SNOOZED` with `Actor.SYSTEM` and `deferred=1` in the detail; who tapped is not recorded. Once unlocked, notification buttons and the Prominent screen record `NOTIFICATION`.
6. **`HELD`.** Written when a nag fire is held at fire time, which in the current code is Do Not Disturb with quiet hours set to follow it. A quiet-hours window is applied when the nag is placed, so the nag never fires inside the window and there is no fire to hold; no separate event is written for that placement.
7. **`FIRED` or `NAGGED`.** Decided from the mirror before the fire: a stored next fire after the due is a nag, anything else (including a catch-up of a missed first fire and the first fire after a snooze) is `FIRED`. Fire events are written after the alarm work, and a failed fire event is logged rather than thrown, so the nag loop cannot be stopped by the log. Fires never bump version.
8. **Import keeps exported version and updatedAt.** AT-11 needs every new field to survive Export then Replace, so `replaceAll` and `mergeAll` do not bump `version`; a row with no `updatedAt` (old file) takes its `createdAt`. Each imported row gets one `IMPORTED` event. A merge onto a soft-deleted row clears `deletedAt` and takes the incoming values; its single `IMPORTED` event carries `restored=1` (no separate `RESTORED`).
9. **Zone alignment is a change.** When a timezone change moves a wall-clock repeat's due in Room, the row's version goes up and an `EDITED` event is written with `Actor.SYSTEM` and `zone=<id>` in the detail.
10. **Purge.** `Scheduler.purgeDeleted(days)` is public (a system path, like `rescheduleAll`) and is also run at the start of every internal reschedule, so it covers `rescheduleAll` (app start), `onExternalClock` (boot, time, timezone and app update) and the reschedule after an import. Restore is refused once a row is past the window, even if the purge has not run yet.
11. **Health test reminder.** `HealthMonitor` (not in the section 2 list) also moved to `ReminderCommands`, with `Actor.SYSTEM`.
12. **Backup decode is lenient for the new keys.** A missing, null, unknown or wrongly typed value takes the default (`USER`, null, `NONE`, version 1, updatedAt from createdAt). An unknown `requestedAlertLevel` becomes null. `sourceId` and `templateId` are cut to 200 characters. The backup log lines now print the real schema number (`schema=2`) instead of a fixed `schema=1`.
13. **Debug `ADD`.** Accepts any `ReminderSource` name for `--es source`; an unknown source or category logs `DoAlarm debug-add bad source` or `bad category` and adds nothing. `PURGE_NOW --ei days 0` purges every soft-deleted row.
14. **Existing order kept.** `markDone`, `snooze` and `stopAlert` still cancel the alarm and dismiss the notification before the Room write, as before. If the transaction fails, the reminder is unchanged in Room and the next reschedule re-arms it.
15. **Release.** No new debug code is in main except the `DoAlarmLog` line formats (`meta`, `event`, `events end`, `deleted`, `restored`, `purge`), which are plain log helpers. `checkAlarmManifests` passes in USE and SCHEDULE and still proves the release manifest has no debug receiver.
16. **Backup compatibility.** Backups are now schema 2. Older builds refuse a schema 2 file, as they refuse any higher `schemaVersion`. That is expected.

### Changed existing tests (AT-16)
- `alarm/QuietSchedulerTest.kt`: its private in-memory fixture (`Fixture`, `FakeAlarms`, `FakeAlerts`, `MemoryReminderDao`) moved, unchanged in behaviour, to the shared `alarm/SchedulerFixture.kt`, extended with an in-memory `ReminderEventDao`, a simulated transaction and the new DAO methods. `fixture()` now returns `schedulerFixture()`. No test body changed. The tests are in package `alarm`, so the now-internal `Scheduler` calls still compile.
- No other existing test changed.

### ReminderDao reads and deletedAt
| Read | deletedAt | Callers |
| --- | --- | --- |
| `observeAll()` | Excludes deleted | `ReminderViewModel.reminders` (the list) |
| `active()` | Excludes deleted | `Scheduler` reschedule and zone alignment, `HealthMonitor` mirror check |
| `activeCount()` | Excludes deleted | Free-tier cap in `Scheduler.add`; debug `FAKE_PURCHASE` and `DUMP_UNLOCK` |
| `all()` | Excludes deleted | Backup export (`BackupController.encodeCurrent`, also filtered again by `exportable()`), `BackupController.reminderCount`, the id list `replaceAll` returns |
| `allIncludingDeleted()` | Includes deleted (new; the old raw `all`) | `Scheduler.replaceAll` only, to cancel every alarm before clearing the table |
| `getById(id)` | Includes deleted | `Scheduler` reads through `liveById`, which treats a deleted row as gone, except `restore` (needs the deleted row) and the read-backs after insert and restore. `ReminderViewModel.reminderOrNull` returns null for a deleted row. `HealthMonitor.testStillActive` treats deleted as gone. Debug `APPLY_QAT` and `SET_ALERT_LEVEL` treat deleted as inactive. Debug `ADD`, `SET_REPEAT`, `DUMP_REPEAT`, `DUMP_ALERT`, `DUMP_META`, `DUMP_EVENTS` and `DELETE` read it for logging only |
| `getByUuid(uuid)` | Includes deleted | `Scheduler.mergeAll`, so a match on a deleted row is restored with the incoming values |
| `deletedBefore(cutoff)` | Only deleted | Purge |

Every write query that changes a live row (`markDone`, `updateDueAt`, `updateDueAndPattern`, `advanceOccurrence`, `updateDetails`, `touch`) also has `deletedAt IS NULL` in its `WHERE`, so a deleted row cannot be changed by accident. The old hard delete (`deleteById`) is gone.

### Acceptance tests to unit tests
| AT | Test |
| --- | --- |
| 1 | `MetaMigrationTest.at1Version4RowsKeepTheirColumnsAndGainTheDefaults` |
| 2 | `MetaMigrationTest.at2Schema5IsExportedWithBothTablesAndMatchesTheMigration` (also checks the migrated table's columns, types, nullability and defaults equal the exported `5.json`, which is what Room validates on the phone) |
| 3 | `ReminderCommandsTest.at3AddStoresTheSourceAndWritesOneCreatedEvent`, `at3LongSourceIdIsCutAndAnUnknownCategoryIsNull` |
| 4 | `ReminderCommandsTest.at4EveryCommandBumpsVersionOnceAndWritesOneEvent`, `at4RepeatingDoneWritesDoneThenAdvancedWithOneBump`, `at4AnUnchangedEditDoesNotBumpOrWriteAnEvent` |
| 5 | `ReminderCommandsTest.at5FiresWriteEventsButDoNotBumpVersion` |
| 6 | `ReminderCommandsTest.at6SoftDeleteHidesTheRowEverywhere` |
| 7 | `ReminderCommandsTest.at7RestoreWithinTheWindowReArmsAndCatchesUp` |
| 8 | `ReminderCommandsTest.at8PurgeRemovesRowsDeletedMoreThan30DaysAgoWithTheirEvents` |
| 9 | `ReminderCommandsTest.at9RetentionKeepsTheNewest200EventsPerReminder` |
| 10 | `ReminderCommandsTest.at10AFailedEventInsertRollsBackTheReminderChange` |
| 11 | `BackupMetaTest.at11ExportThenReplaceKeepsEveryNewFieldButDeletedAt` |
| 12 | `BackupMetaTest.at12AnOldBackupWithoutTheNewKeysImportsWithTheDefaults` |
| 13 | `ReminderCommandsTest.at13MergeRestoresASoftDeletedMatchWithTheIncomingValues` |
| 14 | `CommandCallerScanTest.at14NoFileOutsideTheCoreCallsASchedulerMutator`, `at14TheScanCatchesADirectCall`. The scan is stricter than the spec: only `Scheduler.kt` and `ReminderCommands.kt` may call the mutators, so the receivers in `alarm/` are checked too |
| 15 | `CorePurityTest.at15CoreFilesImportNoAndroidCode`, `at15TheCheckCatchesAnAndroidImport`. Proved by hand: with `import android.util.Log` added to `RepeatMath.kt`, both tests fail (`alarm/RepeatMath.kt: import android.util.Log`); the change was reverted, not committed |
| 16 | Every Spec 1 to 8 unit test passes |
| 17 | `./gradlew assembleDebug testDebugUnitTest lintDebug` and `./gradlew assembleDebug -PexactAlarmMode=SCHEDULE` pass; `checkAlarmManifests` runs as a dependency of `assembleDebug`, `testDebugUnitTest` and `lintDebug` |

`ReminderCommandsTest.aDoneTappedBeforeUnlockIsRecordedWhenRoomOpens` covers note 5.

### Phone notes (AT-18 to AT-21)
- The new hooks take the id as `--el id <id>`, as the other hooks do. `DUMP_EVENTS` takes `--ei limit` (1 to 200, default 20).
- Export a backup before installing on Tim's phone (section 6).
