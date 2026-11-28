# Spec 11: Stuff You Should Do v1 (draft)

Owner: Gareth (PM). Build: a Claude build agent. QA: Mark, and a Claude test agent (host). Status: **draft by Claude, 27 Nov 2026 (AWST), not yet approved.** Builds on Spec 9 (commands, `source`, `category`, `templateId`). Independent of Spec 10.
Sources: `docs/strategy/DO-PRODUCT-STRATEGY.md` (pillar 3, section 10 decisions); `docs/design/DESIGN-PASS-1.md` (first run steps 3 and 4, Today card, Catalogue tab, offer rules); `docs/catalogue/sysd-starter.json` and `SYSD-STARTER-NOTES.md` (58 draft items); Tim's decisions of 27 Nov 2026 (name "Stuff You Should Do"; in first run by default with an opt-out; offered again after first run; data global, content Australian; state from the time zone).

## 1. Goal
Ship the catalogue inside Do: a bundled, offline list of periodic life-maintenance tasks; a first-run step that suggests them (opt out, up to the free 5 pre-ticked); a Catalogue tab; and the rules for offering it again later. Each item added becomes an ordinary Do reminder that repeats, tagged with where it came from.

## 2. Starts from Spec 9
`ReminderCommands.add(origin, ..., category, templateId, templateVersion)` exists; `Reminder` has `source`, `sourceId`, `category`, `templateId`, `templateVersion`; repeats from completion and from due date exist (Spec 4). The free tier is 5 active reminders.

This spec builds its screens (first-run steps 3 and 4, the Catalogue tab, the Today card) in the **new design's look** from the design pass, as the first pieces of the new UI (Spec 14 restyles the rest). It does not change the alarm engine.

## 3. Scope

### A. The catalogue file
- Bundled asset `app/src/main/assets/catalogue/sysd.json`, generated from `docs/catalogue/sysd-starter.json` by the builder (a one-off conversion, committed), shaped:

```json
{ "schema": 1, "catalogueVersion": 1, "items": [ {
  "id": "home-smoke-alarm-test", "version": 1, "category": "home",
  "title": "Test the smoke alarms", "why": "...",
  "interval": { "every": 1, "unit": "MONTH" }, "repeatFrom": "COMPLETION",
  "suggestedMonth": null, "suggestedTime": "09:00", "alertLevel": "NORMAL",
  "regions": ["AU"], "stateRules": { "AU-NSW": { "interval": { "every": 6, "unit": "MONTH" }, "why": "..." } },
  "hiddenIn": [], "needsDate": false, "priority": 80, "related": ["home-smoke-alarm-expiry"],
  "keywords": ["smoke alarm", "smoke detector"], "health": false, "link": null } ] }
```

- `confidence` from the starter file is dropped (it is editorial). `alertLevel` uses Do's names (`NORMAL`, `LOUD`, `ALARM`), mapped to today's stored levels (`STANDARD`, `CRITICAL`, `PROMINENT`) until Spec 14 renames them in code.
- `regions`: `"*"` (everywhere), a country (`"AU"`), or a state (`"AU-WA"`). `stateRules` override `interval`, `why` or `title` for one state. `hiddenIn` lists states where the item does not apply (for example energy plan comparison in `AU-WA`).
- `needsDate: true` for the yearly renewals whose date belongs to the user (lease, contents insurance, rego, car insurance, pet registration, other insurance, health insurance; see the starter notes).
- `priority` (0 to 100) orders suggestions and decides what is pre-ticked. The builder sets it from the starter file (high-confidence, common items higher) and lists the top 15 in the PR for Gareth to review.
- `related` and `keywords` support the "related item" offers (section F).
- A pure Kotlin model and parser (`catalogue/Catalogue.kt`, no Android imports, added to `CorePurityTest`'s list) and a loader that reads the asset. A malformed item is skipped and logged; the rest load.

### B. Where you are: region
- A pure `RegionResolver` maps the device time zone to a state: `Australia/Perth`, `Australia/Eucla` to `AU-WA`; `Australia/Brisbane`, `Australia/Lindeman` to `AU-QLD`; `Australia/Adelaide` to `AU-SA`; `Australia/Darwin` to `AU-NT`; `Australia/Hobart` to `AU-TAS`; `Australia/Melbourne` to `AU-VIC`; `Australia/Sydney` and `Australia/Lord_Howe` to "NSW or ACT" (needs one tap); `Australia/Canberra` (an alias) to `AU-ACT`; `Australia/Broken_Hill` to `AU-NSW` (it uses South Australian time but is in NSW). Any other zone: country unknown, so only `"*"` items show.
- Stored as a setting with its source (`AUTO` or `USER`). The picks screen and the Catalogue tab show "Using WA rules. Change". Change opens a short list of the eight states and territories plus "Outside Australia".
- No location permission. Region is in backup settings (optional key).

### C. First run, steps 3 and 4
After step 2 (first reminder), per the design pass:
- **Step 3, "What do you look after?":** the eight life areas, one tap each, Skip visible, the privacy line. Answers saved as the onboarding profile (a setting, backed up).
- **Step 4, picks:** items for the chosen areas and the region, grouped by area, highest priority first, at most 4 per area shown (a "Show more" per area). Pre-ticked: the highest-priority items, up to the free slots left (5 minus active reminders, which includes the first reminder), never more. `needsDate` items are never pre-ticked; ticking one asks for the date. A live counter ("2 free left, incl. your first"). Skip visible. "Add N reminders" adds them; if that would go over the free limit for a free user, the paywall opens first (adding stops at the limit if they choose "Keep 5 for free").
- Skipping either step goes to Today and arms the head-start card for the next day (section F).

### D. How an item becomes a reminder
Through `ReminderCommands.add` with `Origin(Actor.USER, ReminderSource.CATALOGUE, sourceId = item.id)`, `category`, `templateId = item.id`, `templateVersion = item.version`; repeat from the item (after `stateRules`); alert level from the item; nag interval the user's default.
- **First due**, so a batch doesn't all land at once: from-completion items are spread over the coming days, the i-th added item due at its `suggestedTime` i days from tomorrow (i starting at 0); from-due-date items with a `suggestedMonth` are due on the 1st of the next such month at `suggestedTime`; `needsDate` items use the date the user picked, at `suggestedTime`.
- Removing an item in the Catalogue tab soft-deletes the reminder created from it (Spec 9), so it can be restored.
- An item is "added" if any non-deleted reminder has its `templateId`.

### E. Catalogue tab
The design's Catalogue screen: area chips, search over titles and keywords, each item with its why, interval and an Add or Added toggle, the region line. Adding here follows section D (one item, due at `suggestedTime` tomorrow, or the picked date). The bottom navigation tab can point at this screen even before Spec 14 rebuilds the rest of the navigation.

### F. Offering it again (never as a notification)
A pure `SuggestionPolicy` decides which card (if any) shows on Today, from: the onboarding profile, what is added, the date, and a `SuggestionState` setting (last shown per card kind, dismiss count, "Stop suggesting").
1. Skipped in first run: the head-start card the next day.
2. "Not now": that card kind hidden for 14 days; a second "Not now", 60 days. "Stop suggesting" turns all cards off (the Catalogue tab stays).
3. Seasonal: at most once a calendar month, for items with a `suggestedMonth` in this month or next, in the user's areas, not added.
4. After a catalogue item is marked Done for the first time: one card for its first `related` item not added, once per item.
5. While typing in Add: if the title matches an item's keywords, a small "Related: ..." line under the field (not a card), at most two items; tapping adds them per section D.
6. Never in a notification. Never more than one card at a time. The card sits below Overdue on Today.
- If the existing Today/list screen has no slot for a card yet, add one at the top of the active list, below overdue items.

### G. Debug hooks
`CATALOGUE_DUMP` (count, catalogueVersion, region), `SET_REGION --es region AU-WA|...|none`, `SUGGESTION_DUMP` (which card would show now and why), `SUGGESTION_RESET`, `SET_PROFILE --es areas home,car`.

## 4. Out of scope
Links, sponsors and "Book it". Remote catalogue updates (the version fields make them possible later). Editing the catalogue in the app. Packs for other countries (data allows them). Asking for location. Any change to the alarm engine.

## 5. Acceptance tests
Host (unit, CI):
1. **Parser.** The bundled file parses; a malformed item is skipped; ids are unique; every `related` id exists.
2. **Region.** Each listed zone maps as in section B; an unknown zone gives "outside Australia".
3. **Filtering.** For `AU-WA`, items `hiddenIn` WA are excluded and `stateRules` for WA apply; outside Australia only `"*"` items show.
4. **Pre-tick never exceeds the free slots.** With 1 active reminder, at most 4 pre-ticked; with 5 active, none; `needsDate` never pre-ticked; an Unlimited user gets the top 5 pre-ticked (the same cap, so first run stays short).
5. **First due spreading** as in section D, including a month rollover and a `suggestedMonth` already passed this year.
6. **Add writes the template.** A reminder added from an item has `source=CATALOGUE`, `sourceId`, `category`, `templateId`, `templateVersion` and the item's repeat.
7. **Remove soft-deletes** the template's reminder; it no longer counts as added.
8. **SuggestionPolicy** for each rule in section F, including the 14 and 60 day backoffs and "Stop suggesting".
9. **Keyword match** for "car service" returns the related car items, at most two.
10. **Core purity** covers the new pure files.
11. **CI green**, both exact-alarm variants.

Phone (Mark with Tim):
12. **First run on a fresh install** (debug, data cleared on a test profile only, never on Tim's real data): steps 1 to 4 then Today; the counter is right; Skip works on steps 3 and 4.
13. **Region line** shows "Using WA rules" on Tim's phone; Change works.
14. **Catalogue tab** add and remove.
15. **Today card** after skipping first run (use `SUGGESTION_DUMP` and a clock-independent debug override rather than changing the phone's clock).

## 6. Risks
- **Content accuracy.** 19 items are low confidence and 12 vary by state (starter notes). Gareth and Tim review the content before release; the app shows "check your state's rules" wording where the starter file does.
- **First run getting long.** Steps 3 and 4 must stay one screen each and skippable.
- **Free limit surprises.** Pre-ticking stays within the free slots; the paywall only appears if the user ticks more.

## 7. Calls (Claude's defaults for Gareth)
1. Bundled JSON, versioned per item and per catalogue, no network.
2. State from the time zone; "NSW or ACT" is the only question.
3. Pre-tick up to the free slots left, `needsDate` never pre-ticked.
4. First dues spread one day apart.
5. Screens built in the new design's look now.
6. **(Added after QA, the coordinator's call, for Gareth to confirm.)** An item with a `suggestedMonth` added during that month is due tomorrow at its `suggestedTime`, not on the 1st of that month next year. Its yearly from-due-date repeat then runs from that date. Added in any other month: the 1st of the next such month, as before.

**Tim's content decisions (28 Nov 2026):**
7. `renter-smoke-alarm-test` is merged into `home-smoke-alarm-test`, one item shown under both Home and Renting. It is never listed, offered or pre-ticked twice.
8. Low-confidence items are never pre-ticked. They still show and can be ticked by hand.
9. Energy plan comparison is hidden in WA, Tasmania and the NT. **Tasmania and the NT need a source check before release.** The two state-varying items whose why did not mention it (`kids-school-forms`, `pets-microchip-details`) now end "Check your state's rules."

## Build notes
Built on `do/spec-11-stuff-you-should-do` from `a645db2` (Spec 9 at `3235db6` plus this draft). Notes by the Claude build agent, 27 Nov 2026 (AWST).

### Top 15 by priority (for Gareth to review)
Priority is a judgement from the starter file: high-confidence, common items higher, the 19 low-confidence items 34 to 48. All 58 are in `docs/catalogue/convert_sysd.py`.

| # | Priority | Item | Title |
| --- | --- | --- | --- |
| 1 | 95 | `home-smoke-alarm-test` | Test the smoke alarms (Home and Renting, after Tim's merge) |
| 2 | 93 | `car-rego-renewal` | Renew the car registration (needs a date, so never pre-ticked) |
| 3 | 91 | `money-tax-return` | Do your tax return |
| 4 | 89 | `health-dentist` | Book a dental check-up |
| 5 | 88 | `home-safety-switch-test` | Test the safety switches |
| 6 | 86 | `health-flu-vaccine` | Get your flu vaccine |
| 7 | 85 | `pets-vet-check` | Book the yearly vet check |
| 8 | 84 | `car-tyre-pressure` | Check the tyre pressures |
| 9 | 80 | `home-smoke-alarm-expiry` | Check smoke alarm expiry dates |
| 10 | 78 | `car-service` | Book the car service |
| 11 | 76 | `digital-phone-backup` | Back up your phone |
| 12 | 74 | `kids-dentist` | Book the kids' dental check-up |
| 13 | 72 | `home-termite-inspection` | Book a termite inspection |
| 14 | 70 | `renter-lease-end-check` | Check when your lease ends (needs a date) |
| 15 | 68 | `pets-worming` | Worm the pets |

(Updated 28 Nov 2026 after Tim's merge removed `renter-smoke-alarm-test`, which was 4th.)

### The asset
- `app/src/main/assets/catalogue/sysd.json` is generated by `python3 docs/catalogue/convert_sysd.py` (committed; edit its tables and run it again). 58 items (57 after Tim's merge, see the end), `schema` 1, `catalogueVersion` 1, every item `version` 1.
- `alertLevel`: `STANDARD` became `NORMAL`, `CRITICAL` became `LOUD` (only `car-rego-renewal`). Stored on the reminder as today's `STANDARD` / `CRITICAL` / `PROMINENT`.
- `stateRules`: only `home-safety-switch-test` in `AU-NSW` (every 6 months, with a NSW `why`), the one state difference the starter notes give a number for.
- `hiddenIn`: only `money-energy-plan-compare` in `AU-WA`.
- `needsDate`: the seven yearly renewals in the starter notes.
- `related`: one to three per item, all within the catalogue, best first. `keywords`: two or three lower-case phrases per item.
- `confidence` is dropped. `stateVaries` is kept (not in the spec's shape): it drives the "Check your state" tag the design asks for.

### Choices where the spec left room
1. **No first-run flow existed.** There are no steps 1 and 2 yet. Steps 3 and 4 open once, the first time the Today list has an active reminder: right after the first reminder is saved, or on the first launch of this build when reminders already exist (existing installs see it once). The setting `sysd_onboarding` goes `PENDING`, `STARTED` (on opening step 3), then `DONE` or `SKIPPED`, so killing the app mid-way does not show it again.
2. **Step 3** needs at least one area for Next; Skip is always there. The privacy line is under the areas.
3. **Step 4** groups by area in the fixed order (home, renting, car, kids, pets, health, money and admin, digital), highest priority first, four shown per area with Show more. Pre-ticks are the highest priority across all chosen areas, never `needsDate`, never more than `5 - active` for a free user. **An Unlimited user gets the top 5 pre-ticked whatever is active** (AT-4 read literally). Ticking a `needsDate` item opens a date picker; cancelling leaves it unticked. The counter reads "N free left, counting your first reminder", "N more than the free 5" in red when over, or "N selected" when unlocked.
4. **Paywall from picks.** If the ticked count passes the free cap, the existing Unlock screen opens with a new "Keep 5 for free" button (an optional parameter, shown only from picks). Back or Keep 5 for free adds in order and stops at the cap (the existing `Scheduler.add` refusal). After buying, Back adds them all. The Buy button is now alarm red, per the design.
5. **First due.** From-completion items (and any from-due-date item without a month or a date) are spread i = 0, 1, 2 ... days from tomorrow at their `suggestedTime`, counted over those items only. A suggested month is the 1st of that month at the suggested time, this year if still ahead of now, else next year. `needsDate` uses the picked date. Monthly or weekly from-due-date repeats take the day from the first due (none in v1).
6. **Catalogue tab adds** use the same rule with one item, so a month item (tax in July) is due on the 1st of its month rather than tomorrow. Removing an item soft-deletes every live reminder made from it. "Added" means any non-deleted reminder has the `templateId`, done or not.
7. **Region.** `Australia/Sydney` and `Lord_Howe` give country `AU` with no state until the one tap ("NSW or ACT? Choose"); until then only `AU` and `*` items show and no state rule applies. Old alias zones (`Australia/West`, `Australia/NSW` and so on) map like their main zone. AUTO is worked out from the time zone on every read, so a move follows; a USER choice (a state, or Outside Australia) sticks until "Work it out from my time zone" is chosen. Region, its source and the profile are in `do_settings` and travel in backups as optional settings keys (`sysdRegion`, `sysdRegionSource`, `sysdAreas`), following the Spec 8 quiet-hours pattern: a file without them leaves the current values; bad values are ignored.
8. **SuggestionPolicy details.**
   - Order when several qualify: head start, then related, then seasonal. One card at most.
   - Head start shows from the day after a skip, every day, until the user taps "See the catalogue" on it, adds from it, or anything from the catalogue is added. Up to five items.
   - "Once a month" and "once per item" mean the card is on Today for the day it is first shown and not again that month (seasonal) or ever (related for that item). Showing is recorded when the card is worked out for Today.
   - "Not now" counts per card kind: 14 days after the first, 60 days after the second and later ones.
   - No areas picked (Skip on step 3) means all areas for the head-start and seasonal cards.
   - Seasonal takes up to three items whose `suggestedMonth` is this month or next, in the user's areas, not added.
   - "First Done" is read from the Spec 9 event log: a `DONE` or `ADVANCED` event on a reminder with a `templateId`. No DAO change was needed.
9. **The Related line** (rule 5) uses whole-word keyword matches on what is typed and offers the matched items' `related` items, not added, at most two, as "Add <title>" buttons. "car service" gives "Check tread and rotate the tyres" and "Renew the car registration" (which asks for its date). Only on Add, not Edit.
10. **Today card placement.** The list had no sections, so overdue reminders now come first under a red "Overdue" heading with their due time in red, then the card, then the rest. Catalogue reminders show the amber Catalogue chip.
11. **Navigation.** A bottom bar with two tabs, Today and Catalogue, on the list and the Catalogue screen. Spec 14 adds All and Settings.
12. **Look.** The new screens (steps 3 and 4, Catalogue, the card, the tabs) use `ui/DoLook.kt`: ground #F7F8FA, ink #111827, secondary #4B5563, alarm red #C2391B only for overdue, the over-cap counter and Buy, amber chip #7A3E0B on #FCEFD9, 44dp minimum touch targets. **Fonts:** Space Grotesk and Inter would need the downloadable-fonts dependency (`ui-text-google-fonts`), so headings use the system sans serif in bold with slightly tighter tracking and body text the system sans serif. No new dependency.
13. **Pure core.** `catalogue/Catalogue.kt`, `Region.kt`, `Picks.kt` and `SuggestionPolicy.kt` are in `CorePurityTest`, and so is `backup/BackupJson.kt`, the existing import-free JSON codec they parse with (Android's `org.json` is a stub in JVM tests). `CatalogueActions.kt` (adds and removes through `ReminderCommands`) has no Android imports either but uses the DAO interfaces, so it is not in the core list. `CatalogueService.kt` reads the asset and the settings.
14. **Malformed items** are skipped and logged as `DoAlarm catalogue skipped item N: <reason>`; a repeated id keeps the first; a `related` id that names no item is dropped and logged.
15. **Adds** use `Origin(Actor.USER, ReminderSource.CATALOGUE, sourceId = item.id)`, `category`, `templateId = item.id`, `templateVersion = item.version`, the title after state rules, the item's alert level and the user's default nag.

### Debug hooks
`adb shell am broadcast -n com.evansappstudio.doapp/.debug.DebugReceiver -a com.evansappstudio.doapp.debug.<ACTION>`, logs under tag `DoAlarm`:
- `CATALOGUE_DUMP`: `DoAlarm catalogue count=58 catalogueVersion=1 skipped=0 region=AU-WA source=AUTO needsChoice=false visible=57 areas=... onboarding=... added=...`
- `SET_REGION --es region AU-WA|AU-NSW|...|AU|none|auto` (`auto` goes back to the time zone).
- `SUGGESTION_DUMP`: `DoAlarm suggestion today=... override=... card=HEAD_START|RELATED|SEASONAL|none items=... reason="..." stopped=... skippedOn=... dismiss=...`
- `SUGGESTION_RESET [--ez firstRun true]`: clears the suggestion state; with `firstRun` also sets first run back to pending, so steps 3 and 4 show again without clearing data.
- `SET_PROFILE --es areas home,car|none`.
- `SET_TODAY --es date 2026-11-28|none`: the date `SuggestionPolicy` uses (and records against). The phone clock and every reminder time are untouched. Stored in `do_settings` as `sysd_debug_today`; only the debug receiver writes it. Reopen the app (or bring it to the front) after a hook so the screens re-read the settings.

For AT-15: skip step 3 or 4, then `SET_TODAY --es date <tomorrow>`, bring Do to the front, and `SUGGESTION_DUMP`.

### AT map
| AT | Tests |
| --- | --- |
| 1 | `catalogue/CatalogueParserTest` (5) |
| 2 | `catalogue/RegionTest` `at2*` (4) |
| 3 | `catalogue/RegionTest` `at3*` (3) |
| 4 | `catalogue/PicksTest` `at4*` (5) |
| 5 | `catalogue/PicksTest` `at5*` (3) |
| 6 | `catalogue/CatalogueActionsTest` `at6*` (2) |
| 7 | `catalogue/CatalogueActionsTest` `at7*` (1) |
| 8 | `catalogue/SuggestionPolicyTest` (10), `CatalogueActionsTest.at8DoneCatalogueRemindersAreSeenOldestFirst` |
| 9 | `catalogue/KeywordMatchTest` (3) |
| 10 | `core/CorePurityTest.at10Spec11PureFilesAreInTheCoreAndImportNoAndroidCode` (and the existing `at15*` over the longer list) |
| 11 | `core/Spec11BuildTest` (2), plus the two Gradle runs below |

Also `backup/SysdBackupKeysTest` (3) for the optional backup keys.

### Changed existing tests
- `core/CorePurityTest.kt`: five files added to `CORE_FILES`, and one new test. No existing assertion changed.
- No other existing test changed. `UnlockScreen` gained an optional parameter; `ReminderListScreen` and `ReminderFormScreen` gained optional parameters.

### For Gareth and Tim (content)
- The priorities and the top 15 above are a first pass.
- `home-smoke-alarm-test` and `renter-smoke-alarm-test` have the same title; someone who picks both Home and Renting can get both pre-ticked. Consider dropping one or not pre-ticking the renter one when Home is picked.
- Only NSW has a state rule (safety switches). ACT, and any other state differences, need a source before a rule is added.
- Energy plan comparison is hidden only in WA. The NT and Tasmania also have limited retail choice; worth checking.
- Some keywords are broad and may over-match while typing: `will`, `super`, `tax`, `gp`, `vet`, `oil`, `tick`, `school`, `inspection`, `insurance`, `registration`, `roof`, `dryer`.
- The region line uses short names: "Using WA rules", "Using Victorian rules", "Using Tasmanian rules", "Using Queensland rules".
- The 19 low-confidence items are in the catalogue with low priorities, so they are rarely pre-ticked but are offered.

### CI
`./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintDebug` and `./gradlew --no-daemon --max-workers=2 assembleDebug -PexactAlarmMode=SCHEDULE` both pass locally. No phone or emulator was available to the build agent, so ATs 12 to 15 and the look of the screens are untested on a device.

### Fixes after QA
From `specs/11-qa-report.md` (QA at `8e9de69`). All 27 QA tests pass, including the six that failed on purpose; the Robolectric render tests from `cee442e` are kept.

1. **Bug 1, pre-ticked picks hidden behind Show more.** Within each area, step 4 now lists items that can be pre-ticked first and `needsDate` items after them (`Picks.displayOrder`), and `Picks.preTicked` chooses only among the first four shown in each area. Every ticked item is on screen before Show more, for free and Unlimited users.
2. **Bug 2, an item added in its own month due next year.** Now due tomorrow at its suggested time (section 7, call 6). Supersedes choice 5's month rule for that case. `PicksTest.at5SuggestedMonthAlreadyPassedGoesToNextYear` was updated to match (it had asserted the old 1 July behaviour).
3. **Bug 3, a wrongly typed backup key crashed import.** `sysdRegion` and `sysdRegionSource` are read leniently; a wrong type is ignored. A list of plain values in a backup (only `sysdAreas`) is now written on one line; the file is still the same JSON.
4. **Bug 4, one decimal emptied the catalogue.** The JSON reader takes non-integer numbers when the catalogue asks (`parseJson(text, allowFractions = true)`) and the item with one is skipped as malformed. The backup file still refuses them.
5. **Bug 5, broad keywords.** Single words that match everyday titles are gone or replaced by phrases: `will` (now "make a will", "update my will" and similar), `tick`, `oil`, `glasses`, `school`, `super`, `tax`, `vet`, `gp`, `doctor`, `check-up`, `insurance`, `licence`, `registration`, `lease`, `repairs`, `inspection`, `roof`, `dryer`, `electricity`, `password`, `streaming`, `scripts`, `repeats`, `needles`. Edited in `docs/catalogue/convert_sysd.py` and the asset regenerated (priorities, rules and related items unchanged).
6. **Bug 6, TalkBack.** Add buttons on the Today card and the Catalogue tab read "Add <title>"; the Added button reads "Added <title>. Tap to remove."; the region choices expose selected state instead of a typed check mark.

Low notes:
- **Back from the paywall** reached from picks returns to step 4 with the ticks as they were and adds nothing. Only "Keep 5 for free" adds what fits. After buying, Add on step 4 adds them all.
- **Picks state** (ticks, picked dates, Show more, and the picks waiting on the paywall) lives in `SysdViewModel`, so rotation and the trip to the paywall keep it.
- **The date picker** offers today onwards only.
- **Lord Howe** (`Australia/Lord_Howe`, `Australia/LHI`) maps straight to `AU-NSW`. Only Sydney asks "NSW or ACT?". This changed one expectation in QA's `everyAustraliaZoneInTzdataResolvesInsideAustralia` and in `RegionTest` (on the coordinator's instruction).
- **Region line wording** is one style for all eight: "Using NSW / Vic / Qld / WA / SA / Tas / ACT / NT rules." ("Using Australia-wide rules." if `AU` is set with no state.)
- **"Stop suggesting" undo:** a "Stuff You Should Do suggestions" switch in the existing settings screen (the Snooze settings button on Today). Off is the same as Stop suggesting; on brings the cards back. Spec 14 moves it to Settings, Helpers.
- Not changed, listed for Spec 14 or Gareth: system Back on steps 3 and 4 still leaves the app (the app has no Back handling anywhere); "shown" is still recorded when the card is worked out rather than when Today is on screen; the related card can offer a second item the same day; car rego is added as Loud without the first-use explanation; the overdue split refreshes only when the list changes; "Step 3 of 4" for users who never saw steps 1 and 2; Keep 5 for free is still an outlined button under Buy; the Added outline contrast; not pre-ticking low-confidence items.

Changed existing tests after QA: `catalogue/PicksTest` (the July cases, bug 2), `catalogue/RegionTest` (Lord Howe), and one map entry in QA's `catalogue/Spec11QaTest` (Lord Howe). Nothing else was weakened.

### Tim's content changes (28 Nov 2026)
All in `docs/catalogue/convert_sysd.py`, asset regenerated: 57 items (was 58).
1. **Merged smoke alarm test.** `renter-smoke-alarm-test` is gone (`MERGED` in the script); its keyword "smoke alarm rental" moved to `home-smoke-alarm-test`, which keeps its id, title and why. No other item named the removed id in `related`. The model gained an optional `areas` list per item, primary `category` first (the parser puts it first and drops duplicates; an unknown area skips the item). The reminder still stores the primary `category` (`home`). Step 4 lists an item under exactly one area, the first of its areas the user picked (`Picks.displayArea`), so it is listed and ticked once; with only Renting picked it shows under Renting. The head-start and seasonal cards and the Catalogue tab's area chips read every area of an item. A reminder already made from `renter-smoke-alarm-test` (only possible on a test build) keeps working as an ordinary reminder; it no longer matches a catalogue item, so the Catalogue tab does not show it as added.
2. **No pre-tick for low confidence.** The starter file's `confidence` is now `preTick` in the asset (`false` for the 19 low-confidence items, `true` otherwise; the parser defaults to `true`). `Picks.preTicked` skips `preTick: false`. They are still listed and can be ticked by hand. For example, picking only Kids pre-ticks only `kids-dentist`.
3. **Energy plan comparison** `hiddenIn` is `AU-WA`, `AU-TAS`, `AU-NT`. **TAS and NT: needs a source check before release.** `kids-school-forms` and `pets-microchip-details` now end "Check your state's rules.", so every `stateVaries` item's why mentions the state.

Tests: `CatalogueParserTest.at1TimsContentDecisionsAreInTheAsset` and `at1AreasAndPreTickParse`, `RegionTest.at3EnergyPlanHiddenInWaTasAndNt`, `PicksTest.at4AnItemInTwoAreasIsListedAndTickedOnce` and `at4LowConfidenceIsNeverPreTicked`. Changed for the new content: the item count in `CatalogueParserTest` (58 to 57) and the energy plan `hiddenIn` list there; in QA's `Spec11QaTest`, the item counts (58 to 57 for AU, 57 to 56 for WA), the starter id set (less the merged id) and the starter-text check (skips the merged item and allows the two new why endings). Nothing else changed.
