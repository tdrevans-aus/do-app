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
