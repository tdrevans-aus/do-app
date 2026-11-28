# Spec 11 QA report: Stuff You Should Do v1

QA: Claude test agent (host). Reviewed `git diff 3235db6..43e2d9b` on `do/spec-11-stuff-you-should-do`. Run window: 27 Nov 2026 16:43 AWST to 28 Nov 2026 08:45 AWST (the session stopped on a usage limit overnight and resumed). No phone or emulator. Production code was not changed.

## Verdict

**Not ready to merge as is; close.** The build is green in both exact-alarm variants and release, the pure core is well tested, the catalogue asset is faithful to the starter file, and I found nothing that could change Tim's existing reminders or add anything without a tap on Add. Two Medium bugs should be fixed before Tim's phone run: pre-ticked picks hidden behind "Show more" (the user adds reminders they cannot see), and an item added in its own suggested month being due eleven to twelve months later (tax added on 2 July is due 1 July next year). The rest are Low.

## Commands

All with `--no-daemon --max-workers=2`, JDK 21, SDK at /home/user/android-sdk. No HTTP 429 seen.

| Run | Code | Result |
| --- | --- | --- |
| `assembleDebug testDebugUnitTest lintDebug` | 43e2d9b | BUILD SUCCESSFUL, 209 tests, 0 failures |
| `clean assembleDebug testDebugUnitTest lintDebug -PexactAlarmMode=SCHEDULE` | 43e2d9b | BUILD SUCCESSFUL, 209 tests, 0 failures |
| `assembleDebug testDebugUnitTest --rerun-tasks` | 43e2d9b | BUILD SUCCESSFUL, 209 tests (forced rerun, not cache) |
| `assembleRelease` (unsigned) | 43e2d9b | BUILD SUCCESSFUL; `assets/catalogue/sysd.json` is in the release APK |
| `assembleDebug testDebugUnitTest lintDebug` | cee442e (with QA tests) | 236 tests, 6 fail, all six are the QA tests marked below |
| `lintDebug assembleRelease` | cee442e | BUILD SUCCESSFUL (the Robolectric change does not affect lint or release) |

Lint: 90 warnings, none in Spec 11 code except two existing-pattern items (`UseKtx` in `RemindersUi.kt`, also present before). No errors.

## Acceptance tests

| AT | Verdict | Notes |
| --- | --- | --- |
| 1 Parser | PASS (WEAK on robustness) | The asset parses with 0 skipped, 58 items, ids unique, related ids exist. Malformed-item skipping works for wrong types, but a decimal number in one item empties the whole catalogue (bug 4). |
| 2 Region | PASS | Every `Australia/*` id in the JVM tzdata (including Eucla, Broken_Hill, Lord_Howe, Lindeman, Canberra, Currie and the backward aliases) maps as section B says. Non-Australian zones, `Etc/GMT-8`, Norfolk, Christmas, Cocos and Macquarie are outside. |
| 3 Filtering | PASS | WA hides `money-energy-plan-compare` (57 visible); NSW gets the 6 month safety switch; "AU" (NSW or ACT not yet chosen) gets no state rule; outside shows only `*` items. |
| 4 Pre-tick cap | PASS on the numbers, FAIL on the screen | `Picks.preTicked` never passes the free slots for any area choice and active count 0 to 7, never ticks `needsDate`, Unlimited gets 5. But some pre-ticked items are hidden behind "Show more" (bug 1). The build's tests check the set, not what is shown. |
| 5 First due | PASS (spec-literal), see bug 2 | Spreading, month and year rollover, leap day (27 Feb 2028 gives 28 Feb, 29 Feb, 1 Mar), 31 Dec, DST start and end in Sydney, Melbourne, Adelaide, Hobart, Lord Howe and Broken Hill all keep the local time; 02:30 on the Sydney DST start day lands at 03:30. A suggested month already passed goes to next year as written, which is wrong when it is the current month (bug 2). |
| 6 Add writes the template | PASS | Real test on the Scheduler fixture: source, sourceId, category, templateId, templateVersion, repeat, alert level. |
| 7 Remove soft-deletes | PASS | Real test, including restore. Removal touches only rows with that `templateId`. |
| 8 SuggestionPolicy | PASS | 14 and 60 day backoffs exact (day 13 hidden, day 14 shown; second dismissal to day 60; third stays 60), Stop beats every rule and survives encode/decode, seasonal once a calendar month and wraps December to January, related once per item, one card at most. Minor deviations in Low notes. |
| 9 Keyword match | PASS, with over-matching (bug 5) | "car service" gives tyre rotation and rego, at most two. |
| 10 Core purity | PASS | The four pure files plus `BackupJson.kt` are checked. |
| 11 CI green | PASS | Both variants and release, see Commands. |
| 12 to 15 (phone) | NOT RUN | No device. Risks below. |

## Bugs

### 1. Medium: pre-ticked picks can sit hidden behind "Show more"
`ui/SysdScreens.kt:323` and `:400` with `catalogue/Picks.kt:60`. Pre-ticks are the top items by priority excluding `needsDate`, but each area shows its top 4 including `needsDate` items, which take display slots.
Repro (`Spec11QaTest.preTickedItemsAreAllVisibleBeforeShowMore`): a free user with their first reminder picks only Renting: lease end and contents insurance (both `needsDate`, unticked) fill two of the four rows, so `renter-repairs-list` and `renter-condition-photos` are ticked but hidden. The button says "Add 4 reminders" with two ticks visible. Same for Car (`car-tyre-rotation`, `car-fluids-check`). Unlimited users hit it in every single area.
Fix: pre-tick only among the items shown (the first 4 per area), or show every ticked item regardless of the 4 limit, or sort `needsDate` items after the others within an area.

### 2. Medium: an item added in its own suggested month is due next year
`catalogue/Picks.kt:124-128` (`nextMonthStart`). The seasonal card offers items whose month is "this month or next" ("These are usually done around now"), but adding one in its own month gives the 1st of that month next year.
Repro (`anItemOfferedInItsOwnMonthIsNotDueElevenMonthsLater`): 2 July 2026, Money area: the seasonal card offers the tax return; Add makes it due 1 July 2027 (364 days). Same for flu vaccine in April and school forms in January, and for first-run picks in those months.
Fix (needs Gareth's call, the spec text is literal): when the suggested month is the current month, due tomorrow at the suggested time (or the next spread day), keeping the yearly due-date repeat anchored on that date or on the 1st of the month next year.

### 3. Low: a wrongly typed `sysdRegion` in a backup crashes import
`backup/BackupCodec.kt:218-219` use `optionalString`, which throws `JsonParseException` for a non-string. `decodeSettings` is not inside a try, so it escapes `BackupCodec.decode` and the `container.scope.launch` in the import picker (no exception handler) takes the app down. The build notes say bad values are ignored.
Repro (`wronglyTypedNewBackupKeysAreIgnoredNotFatal`): `"sysdRegion": 7`. Only a hand-edited file triggers it.
Fix: use `lenientString` (already in the file) for both keys.

### 4. Low: one non-integer number empties the whole catalogue
`backup/BackupJson.kt:216` rejects any decimal, so `CatalogueParser.parse` gets an exception for the file, not the item, and returns an empty catalogue (the screens then show nothing). Spec A says a malformed item is skipped.
Repro (`aDecimalNumberInOneItemSkipsOnlyThatItem`): one good item and one with `"priority": 50.5`.
Fix: parse decimals as a separate number kind (or a lenient catalogue reader) so only that item fails. The shipped asset has no decimals, so this matters for future content edits and remote updates.

### 5. Low: broad keywords offer Related items for everyday titles
Asset keywords `will`, `tick`, `oil`, `glasses`, `school`, `super` (`app/src/main/assets/catalogue/sysd.json` lines 1668, 1053, 728, 1280, 892, 1636). Repro (`everydayTitlesDoNotOfferRelatedItems`): "I will ring mum" offers "Check your super"; "Buy olive oil" offers tyre pressure and wipers; "Wash the wine glasses" offers a GP check-up; "Super Bowl party" offers the tax return. Fix: use phrases only ("make a will", "flea and tick", "engine oil", "new glasses"), in `docs/catalogue/convert_sysd.py`.

### 6. Low: Today card "Add" buttons do not say what they add
`ui/SysdScreens.kt:616` (and the Catalogue tab, `:563`). With two items, TalkBack reads "Add", "Add". Repro: `Spec11RenderQaTest.todayCardAddButtonsSayWhatTheyAdd`. Fix: a content description "Add <title>" (string resource), or merge the row semantics.

### Low notes (no failing test)
- **Back from the paywall adds the picks.** `ui/RemindersUi.kt:374`: Back on the paywall reached from picks runs `finishPicks` and adds up to the cap, the same as "Keep 5 for free". The user did tap Add first, so this is not an add without consent, but Back would normally return to the picks. Suggest Back returns to step 4.
- **Picks state is lost on rotation.** `ui/SysdScreens.kt:302` and `:323`, and `pendingPlans` at `RemindersUi.kt:159`, use `remember`, not `rememberSaveable`. Rotating on step 4 resets the ticks; rotating on the paywall loses the plans and leaves onboarding `STARTED` with nothing added and no head-start card.
- **System Back on step 3 or 4 leaves the app** (no `BackHandler` anywhere in the app); onboarding stays `STARTED`, so neither the steps nor the head-start card come back. Same if a notification tap opens Edit while step 3 is up.
- **"Shown" is recorded when the card is worked out, not when it is seen.** `SysdViewModel.kt:70` runs from `LaunchedEffect(reminders)` (`RemindersUi.kt:177`) on any screen. If the user never reaches Today that day, that month's seasonal card or that item's related card is spent.
- **Related card can offer a second item the same day.** `SuggestionPolicy.kt:212`: after adding the first related item, the card shows the next one the same day.
- **Car rego is added as CRITICAL without the first-use explanation or the DND access prompt** that the Add form shows (`CatalogueActions.kt:37`). If DND access is missing it falls back quietly.
- **The needs-date picker allows past dates**, giving a past first due that starts nagging at once.
- **Overdue split uses a `now` frozen per list change** (`RemindersUi.kt:529`); a reminder that becomes overdue while Today is open stays in the lower list until the list changes.
- **"Stop suggesting" has no way back** yet (the design puts it in Settings, Helpers; Spec 14).
- **"✓" in code** (`SysdScreens.kt:176`, `:558`) is not in strings.xml and is read as "check mark"; the selected region is not exposed as selected in semantics.
- **Keep 5 for free** is an outlined button under a red filled Buy; the design asks for an equal option.
- **"Step 3 of 4"** appears to existing users who never saw steps 1 and 2.
- **The Added button's outline** is #E5E7EB on white (1.24:1); the label carries it, but the boundary is under 3:1.

## Safety for Tim's real phone (check 7)

- **Nothing is added without Add.** Steps 3 and 4 open once (`RemindersUi.kt:184`) when the list has an active reminder and `sysd_onboarding` is unset. Adds happen only from `finishPicks` (Add N, or Back / Keep 5 for free on the paywall after Add N), the Catalogue tab Add, the Today card Add and the Related line, all user taps.
- **Skip never changes data.** Skip writes `sysd_onboarding=SKIPPED` and the suggestion state only; step 3 Skip does not even save the areas.
- **No existing reminder is changed.** Adds go through `ReminderCommands.add`; removal only soft-deletes rows whose `templateId` is that item's id. `keepFiveForFreeAddsOnlyWhatFitsAndLeavesExistingRemindersAlone` checks existing rows are byte-equal before and after a capped batch and a remove. Residual risk: a reminder created earlier with a debug `--es templateId <catalogue id>` would show as Added and could be removed from the Catalogue tab.
- **Free cap.** "Keep 5 for free" adds in order and stops at the first refusal from `Scheduler.add`, so it never goes past 5 active.
- **No notifications, no alarm changes.** No Spec 11 file references notification or alarm APIs (`spec11FilesNeverNotifyOrScheduleAlarms`); the only scheduling is the normal one for reminders the user added. `SET_TODAY` changes the suggestion date only.
- **Backups.** Schema 1 and 2 files without the new keys import with the keys null and current values kept (`anOlderSchemaFileWithoutTheNewKeysStillImports`; `SettingsStore` only writes region when the source key is present). Older builds read settings by name, so they ignore the new keys. Note every new export carries `"sysdAreas": []` or the areas, so restoring it sets the profile. The onboarding state is not backed up, so a restore on a fresh install shows steps 3 and 4 again (fine).

## Catalogue content (check 3) and notes for Gareth

- 58 items; titles, whys, categories, intervals, repeatFrom, months, times, `stateVaries` and `health` match `sysd-starter.json` exactly. No em or en dashes. Every `link` is null, `confidence` is dropped. Health covers the 7 health items plus `kids-health-check`, `kids-dentist`, `kids-immunisation-check`.
- Alert mapping is right: `STANDARD` to `NORMAL` (stored `STANDARD`), `CRITICAL` to `LOUD` (stored `CRITICAL`), only `car-rego-renewal`.
- The 19 low-confidence items have priorities 34 to 48, below every other item (50 and up). But because pre-ticks are per chosen areas, a user who picks only Renting or Kids gets low-confidence items pre-ticked (`renter-repairs-list`, `renter-condition-photos`). Consider not pre-ticking low-confidence items at all.
- The 12 state-varying items all carry `stateVaries` and show "Check your state". Only `home-safety-switch-test` (NSW 6 months) has a state rule and only `money-energy-plan-compare` is hidden (WA). Two state-varying items have no state wording in their why: `kids-school-forms`, `pets-microchip-details` (the tag still shows).
- All seven `needsDate` items are yearly fixed-date renewals with no month. WA rego can be paid 3, 6 or 12 monthly; a yearly reminder may not suit everyone.
- Lord Howe Island is in NSW, so `Australia/Lord_Howe` could map to `AU-NSW` without the question. Norfolk Island and Macquarie Island are treated as outside.
- Region line wording mixes forms: "Using WA rules", "Using Victorian rules", "Using Queensland rules", "Using Australian rules" for NSW or ACT before the tap.
- The two smoke alarm items share a title (build notes point this out too).
- Keyword list, see bug 5.

## UI code review (check 8)

- Strings are in strings.xml with plurals, Australian English ("tyres", "licence", "immunisation"), no emoji. The only hard-coded text is the "✓ " prefix.
- Contrast of the colours used, on their grounds: ink 16.7:1, secondary 7.1:1, alarm red 5.1:1 (5.4:1 on white, and white on red), amber chip 7.3:1. All pass AA for text.
- Touch targets: `MinTouch` is 44dp per the design, but Material 3 buttons, chips and checkboxes enforce 48dp themselves; the render test confirms 48dp on Not now and Stop suggesting. Pick rows are toggleable with the checkbox role and a null `onCheckedChange`, which is right.
- Compose state: see the Low notes on `remember` versus `rememberSaveable` and recording "shown" outside Today.

## Robolectric

Added in its own commit (cee442e): `org.robolectric:robolectric:4.18.1`, Compose `ui-test-junit4` and `ui-test-manifest`, all `testImplementation`, plus `unitTests.isIncludeAndroidResources = true`. The test manifest is not merged for unit tests, so the test registers `ComponentActivity` with the shadow package manager first. `Spec11RenderQaTest` renders step 3 (Skip, Next disabled until an area is picked, the privacy line), the Today card (actions, 48dp targets, Add) and the Related line. All 209 existing tests still pass with it, and lint and release are unaffected. Step 4 and the Catalogue tab take the view model and `AppContainer`, so they are not rendered yet. Drop the commit if Gareth would rather not carry the dependency.

## QA tests

`app/src/test/java/com/evansappstudio/doapp/catalogue/Spec11QaTest.kt` (23 tests, 5 fail on purpose) and `app/src/test/java/com/evansappstudio/doapp/ui/Spec11RenderQaTest.kt` (4 tests, 1 fails on purpose). Failing, each marked `// QA: fails until ...`:
1. `preTickedItemsAreAllVisibleBeforeShowMore` (bug 1)
2. `anItemOfferedInItsOwnMonthIsNotDueElevenMonthsLater` (bug 2)
3. `wronglyTypedNewBackupKeysAreIgnoredNotFatal` (bug 3)
4. `aDecimalNumberInOneItemSkipsOnlyThatItem` (bug 4)
5. `everydayTitlesDoNotOfferRelatedItems` (bug 5)
6. `todayCardAddButtonsSayWhatTheyAdd` (bug 6)

## Phone ATs, NOT RUN

- **AT-12 first run:** test on a test profile only. Watch for bug 1 (count the visible ticks against the button), rotation on step 4, and system Back on step 3 (leaves the app; the steps do not come back).
- **AT-13 region line:** expect "Using WA rules" on Tim's phone (`Australia/Perth`). Check the Change list scrolls and the choice sticks after a restart.
- **AT-14 Catalogue tab:** Added removes at once with no confirmation (soft delete; restore from the deleted list).
- **AT-15 Today card:** use `SET_TODAY` and `SUGGESTION_DUMP`; reopen Do after each hook. Viewing a dump with the app on another screen still records the card as shown.
- **Tim's real phone, first launch of this build:** steps 3 and 4 will open over Today once, because he already has active reminders. Tapping Skip is safe. If he taps Add, check the picks first (bug 1), and note car rego is Loud.
