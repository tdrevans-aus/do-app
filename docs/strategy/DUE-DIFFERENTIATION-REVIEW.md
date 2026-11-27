# Review: where Do sits too close to Due

**Date:** 27 Nov 2026 (AWST)
**Why now:** Do started as "Due for Android" with the rule "if in doubt, copy Due". Do is now a different product, and Tim plans to launch on Apple too, where Due is sold. Anything that looks copied is now a real risk: App Store review (Apple's guideline on copycat apps), a complaint from Due's developer, and simply looking like a knock-off next to Due in the same store.
**Not legal advice.** This is a product and design review. Before launch, get an Australian IP lawyer to check the name and anything marked High.

What is generally fine: copying **ideas and features** (nag until done, repeats, natural-language dates, alert levels, quick time buttons). What carries risk: copying **names and marks**, **wording**, **distinctive layouts and look**, and **evidence of deliberate copying**.

## High risk

### 1. The name "Do" next to "Due"
- Same category (reminders), and the two words sound almost the same, especially in US English.
- On the App Store they would sit side by side in search results for "Due".
- "Do" is also a common word, which makes it weak as a trademark and very hard to find in store search.
- **Action:** search IP Australia's trademark register, USPTO and the App Store and Google Play for the name before any more brand work. Strongly consider a more distinctive name, or a distinctive composite. Settle it before the icon, store listing and package-facing names are designed.

### 2. Wording copied from Due's app and help centre
Found in the app's `strings.xml` on `main`, in the specs, and in the new design screens:
- **"Remind me to..."** as the add-field label. This is Due's composer placeholder (noted in `specs/due-screens-batch2-notes.md`). It is also in four of the new design screens (Add, first reminder, share sheet, widget). **Change it.**
- **The natural-language preview controls: "Set to", "Keep" and "Set & Remove"** (`set_to`, `keep_words`, `set_and_remove` in `strings.xml`), built from Due's help article (Spec 5). **Rename and redesign** the preview interaction; the design canvas already uses "Set and tidy title" / "Keep text as typed", which is closer to safe but should be reworked again away from Due's three-button flow.
- **"Prominent" as an alert level**, and the trio "Standard, Critical, Prominent" with Due's definitions (Spec 5 quotes Due's help article). "Critical" is also Apple's own term for its Critical Alerts entitlement, so it is less of a problem; "Prominent" is Due's coinage. **Rename the levels in Do's own words** (for example a gentle / insistent / alarm scale) and write fresh explanations.
- **"Quick Times" and "Postpone behaviour"** with "Auto / From due date / From now" options, mirroring Due's "Quick Access Times" and "Postpone Behavior". **Rename** and reword.
- **Notification marks "Critical Alert !!!" and "Prominent Alert ⏰"** (`alert_critical`, `alert_prominent`). Not verified whether these match Due, but they were built to Due's model; replace with Do's own treatment.

### 3. The public repo documents deliberate copying
- The repo is public (handoff, section 2). The specs say "Rule: if in doubt, copy Due", quote Due's help centre text at length with article numbers, and `specs/due-screens-batch2-notes.md` describes Due's screens shot by shot.
- That reads as evidence of intent, and quoting the help centre at length is reproducing someone else's text.
- `specs/due-screens-batch2-notes.md` also records personal details visible in one of Tim's screenshots (an email compose screen). That should not be in a public repo either way.
- **Action:** make the repo private, or remove the Due research files and the Due notes from it (and from its history if the repo stays public; that needs a history rewrite and Tim's explicit OK). Going forward, specs describe Do's behaviour in Do's words and keep any competitor research out of the repo.

## Medium risk

### 4. The add screen's layout and the Quick Times grid
- A 4 x 3 grid of time buttons under the title, with Due's exact twelve defaults (9:30 am, 12:00 pm, 6:30 pm, 10:00 pm, then +10 min, +1 hr, +3 hrs, +1 day, then the minus row). Spec 3 built these as factory labels, and the new Add screen repeats them.
- A quick-time grid as an idea is fine; the identical arrangement and values are not.
- **Action:** choose Do's own defaults (for example "Tonight", "Tomorrow morning", "This weekend", "+1 hr", "+1 day") and a different arrangement (a single scrolling row of chips, or a smaller grid), and drop the minus row unless research shows people use it.

### 5. Repeat menu order and wording
- Spec 4 copied Due's menu order and labels ("Repeat Off", "Daily 6:04 pm", "Weekdays 6:04 pm", "25th each month at 6:04 pm", "Every 25 Nov at 6:04 pm", "Custom...").
- **Action:** keep the capability, reword the menu in Do's voice and order it by what Do's users pick most.

### 6. Settings structure and labels
- Several settings mirror Due's sections and wording ("Defaults for new reminders", "Week starts on", "Parse dates", "Interval on time picker").
- **Action:** low effort to fix during the UI rebuild: group settings the way Do's new design does (Nagging, Helpers, Your data) and reword.

## Low risk, or already fine

- **Nag until done, repeats, natural-language dates, quick times, alert levels, quiet hours:** features and ideas, widely shared across reminder apps (Naggit, Remindio, Todoist and others).
- **The new design:** its own structure (Today, All, Catalogue, Settings), its own look (Do's palette and type), Stuff You Should Do, AI assistants, the first-run flow, and the pricing model. None of these come from Due.
- **Pricing:** the old plan copied Due's A$12.99 plus annual pass; the 27 Nov decision (A$3.99 then A$9.99 a year, free tier) no longer does.

## Suggested order

1. Name check (trademark and store search), before any more brand or icon work.
2. Repo: make it private, or remove the Due research and notes.
3. Wording: replace every Due-derived string listed above. This fits naturally into the UI rebuild spec, but the add-field label and the alert-level names should change in the design now.
4. Add screen and Quick Times: Do's own defaults and layout, in the design now.
5. Repeat menu and settings wording: during the UI rebuild.
6. Before launch: a lawyer's check of the name and the final UI.

## Also changed by the Apple decision

The strategy said iOS "later, not instead". Launching on Apple too changes the roadmap (two platforms, iOS alarm limits, and Due as a direct neighbour in the App Store). That needs its own short decision: same launch, or Android first then iOS.
