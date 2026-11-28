# Do: product strategy

**Version:** draft 1, 27 Nov 2026 (AWST, Australia/Perth)
**Owner:** Tim Evans (Evans App Studio). For Gareth (PM) to review.
**Status:** Draft for Tim's sign-off. Pricing and tiers are decided (see `docs/decisions/2026-11-27-pricing-and-tiers.md`). Everything else here is a proposal unless marked "decided".

## 0. Sources, and what is missing

There is no standalone product brief in the repo. This strategy is built from:

- `docs/claude-handoff/DO-HANDOFF-for-Claude.md` (27 Nov 2026): positioning, decisions log, roadmap, Play policy flags, and short summaries of the two research papers.
- Specs 2 to 8 in `specs/`, and the code on `main` and PR #8.
- `docs/decisions/2026-11-27-pricing-and-tiers.md` (decided today).
- Tim's notes from the morning of 27 Nov 2026 (local-first core, persistent reminders, AI capture layer, Stuff You Should Do catalogue).
- A competitor scan on 27 Nov 2026 (web search, sources at the end).

Not in the repo, and only known through the handoff's summaries: `DO-RESEARCH.md` (24 Nov 2026), `DO-AGENTIC-DESIGN-PAPER.md` (26 Nov 2026), and the Spec 1 markdown. Where this document relies on them it says so. If Tim adds them, sections 4 and 6 should be checked against the full text.

---

## 1. The change in one paragraph

Do started as "copy Due for Android". It is now a bigger product: **the reminder that makes sure things actually get done**, whoever or whatever set it. Three things go into Do: you type it, your AI assistant sends it, or Do suggests it from a catalogue of life maintenance you didn't know to remember (Stuff You Should Do). One thing comes out: a reminder that rings on time, gets through when it matters, and nags until you mark it done. The nag engine, which is already built, is the moat. Capture, including AI, is free and is the way in. The catalogue gives people a reason to install Do before they have anything to remember, and later carries "Get it / Book it" links as a second revenue line.

"Copy Due" is retired as the product rule. It stays as the rule for the nag engine and reminder behaviour only (Auto Snooze, Quick Times, repeats, alert levels), where Due is still the best reference.

## 2. Who it is for

Proposed, not researched with users:

- **Primary:** Android users who forget, or put off, things that matter: busy parents, people with ADHD or similar, people whose phone is their memory. They have been let down by reminders that fire once and are gone. The research's most common 1-star themes (missed or late alerts, the app being killed, ads and subscriptions, actions that only open the app) are this user's complaints.
- **Secondary:** people who already use an AI assistant (Gemini, ChatGPT, Claude) to plan, and want what it sets to actually reach them. Assistants can now schedule things (ChatGPT Tasks, Gemini Scheduled Actions), but they are capped at about 10 active tasks and don't nag or ring through Do Not Disturb.
- **The catalogue user:** homeowners, renters and car owners who know they "should" clean the gutters or test the smoke alarm, but have no system for it.

Market: Australia first (English, AWST-tested, AUD pricing), then other English-speaking markets. **Android first, iPhone close behind** (see section 9A; decided 27 Nov 2026). The earlier "iOS later, not instead" is replaced: Do will launch on Apple too, where it sits next to Due.

## 3. Positioning

**One line (draft):** *Do. It won't let you forget.*

**Against the alternatives:**

| Alternative | What it does well | Where Do wins |
| --- | --- | --- |
| Due (iOS only) | The original nag-until-done app on iPhone | Android; free to start; AI capture; catalogue |
| Android nag apps (Naggit, StillDue, Againly, nag reminder) | Nag until done | Reliability work (health check, exact alarms, quiet hours), alert levels, AI and catalogue |
| Google Tasks, Todoist, TickTick | Capture, lists, AI agents can already write to them | They notify once. Do enforces. |
| ChatGPT Tasks, Gemini Scheduled Actions | The assistant sets the reminder itself | About 10 active tasks, paid tiers, no nag, no ring-through. Do is their last mile. |
| Home maintenance apps (HomeZada, BrightNest, Centriq, many 2026 iOS apps, Mantle and Kepthouse on Android) | Home-specific task libraries, inventory | Mostly iOS, mostly US, mostly a due-day ping plus weekly digest, mostly US$59 to US$199 a year. None nag. |
| Thumbtack (US) | Free maintenance plan with built-in booking (pay-per-lead) | Not in Australia. Do is a reminder first; "Book it" comes later. |
| Tody, Sweepy (chores) | Indoor cleaning cadence | Do covers the whole of life admin, not only cleaning, and enforces. |

**The gap Do fills:** nobody combines (1) an Android nag engine you can trust, (2) open capture from any AI, and (3) a ready-made list of life maintenance. Each exists alone.

## 4. The four pillars

### Pillar 1: Enforcement (the moat). Built in Specs 1 to 8.

What exists: one `setAlarmClock()` alarm per reminder (chosen over `setExactAndAllowWhileIdle`, which is throttled to about once per 9 minutes in Doze), nag every 1 to 60 minutes anchored to the due time, Done and snooze on the notification, Quick Times, full repeats including "from completion", Standard / Critical / Prominent alerts, health check, quiet hours and Do Not Disturb follow (PR #8), local backup.

Gaps and checks before release:

- **Swiped-away notifications.** Today a swipe does not re-post; the next nag comes at the next interval. Tim's morning notes proposed an instant re-post loop via `deleteIntent`. Recommendation: **don't**. An instant re-post feels hostile, Android 14+ deliberately lets users dismiss, and the timed nag already does the job. Revisit only if testing shows swiped nags are missed.
- **Android 15+ notification cooldown** can soften one-minute nags. Critical and Prominent are the way around it; the health check explains it.
- The outstanding human and device ATs from Specs 1 to 7 (handoff section 3) must be closed or honestly marked NOT RUN before release.

Principle: **enforcement is never sold or sponsored.** No payment state, sponsor or agent can make a reminder fire later, quieter, or not at all. A lapsed subscription never stops an existing reminder (decided).

### Pillar 2: Capture from anywhere, including AI (the way in). Partly built.

All of it is free (decided). Agent-created reminders count towards the 5 active limit (decided).

**On the phone (no server, v1):**

- Natural-language add with preview (built, Spec 5).
- Share-sheet target ("share to Do" from any app or chat). Proposed v1.
- Home-screen widget and Quick Settings tile for capture in under two seconds. Proposed v1 (research Shoulds).
- App Shortcuts and a deep link (`do://add?...`) so assistants and automation apps can open a prefilled add screen. Proposed v1.
- Android AppFunctions, so on-device assistants such as Gemini can create and complete Do reminders directly. Proposed v1 **if** the API is stable on Tim's target devices; **unknown/confirm**. The 26 Nov paper put AppFunctions in v2 with the cloud release; this strategy proposes pulling the on-device part forward, because it needs no server and is the cheapest proof of "your AI can set a Do alarm".

**Cloud agent API (needs a server, later):**

- MCP and REST, per-agent OAuth, events back (fired, done, snoozed, ignored) by webhook and a pull cursor. From the 26 Nov paper.
- Safety rules from the paper, kept: approve-before-ring by default (an agent's first reminder asks the user), device-enforced caps agents cannot override, the user can revoke any agent.
- Free for everyone (decided), with rate limits for abuse. Not before Tim approves the hosting account and Spec 10 scope. There is no separate "Do Connect" paid product any more.

**Why this order:** the on-device slice delivers the "AI last mile" story at launch with no hosting, no accounts and no running cost. The cloud API makes Do reachable from assistants that don't run on the phone, which is valuable but can follow.

### Pillar 3: Stuff You Should Do (the reason to install). Approved for the roadmap 27 Nov 2026, as v1.1; this strategy proposes a thin slice in v1.

**What it is:** a bundled, offline catalogue of periodic life-maintenance tasks, and an onboarding picker that turns choices into Do repeating reminders.

**v1 slice (proposed):**

- 40 to 60 items across Home, Car, Health, Money and admin, Family and pets, Digital. Australian wording and intervals first (car rego, smoke alarm testing; state rules differ, so items say "check your state's rules" rather than state a law). **Scope Australian-first or global is still open.**
- Each item: id, title, category, short "why", default interval, **repeat type** (fixed calendar, such as "every 1 July", or from completion, such as "6 months after you last did it", so overdue maintenance never stacks), suggested time of day, suggested alert level, region tags, a health flag, and an optional link field left empty in v1.
- **In first run by default, with an opt-out (decided 27 Nov 2026).** First run: permissions, first reminder, then "What do you look after?" and picks (Skip always visible; up to the free 5 pre-ticked, counting the first reminder), then Today. After first run it is offered again periodically (a head-start card for people who skipped, seasonal suggestions, a related item after a catalogue item is done, related items while typing), never in a notification. A catalogue-focused Play custom store listing can deep-link into it. Rules in `docs/design/DESIGN-PASS-1.md`.
- Items added count towards the 5 active limit (decided). That is a deliberate upgrade moment: someone who picks 8 useful items sees the value before they pay.
- Catalogue content ships in the APK in v1 (no network). Updates come with app releases.

**Built already:** repeats "from completion" (Spec 4) are exactly the completion-anchored behaviour Tim's notes asked for. Only the catalogue data, the template link and the picker UI are new.

**Later (post-launch):** remote catalogue updates, the link field ("Get it" for consumables, "Book it" for services), then sponsors. Rules: paid users can hide links, health items are never sponsored, and a sponsor never changes when or how a reminder fires (decided).

**Name:** "Stuff You Should Do" (roadmap) or "Stuff You Can Do" (Tim, 27 Nov). Open. A trademark check is needed, since "Stuff You Should Know" is a well-known podcast.

### Pillar 4: Commerce (the second revenue line). Later, not v1.

The competitor scan found that affiliate links on cheap consumables earn little (Amazon AU is about 1 to 12% by category, mostly low), and the real money in home maintenance is service leads (Thumbtack's pay-per-lead; hipages' tradie subscriptions, about A$2,300 a year per tradie). Both need an audience in one region first. So: launch without it, measure which catalogue items people add, then approach partners for the categories with buying intent (gutters, HVAC service, car service, pest control).

## 5. Business model (decided 27 Nov 2026)

- No display ads, ever. Freemium.
- Free: the whole app, all AI features, the catalogue, up to **5 active reminders** (the only limit; the repeating cap of 2 is dropped).
- Paid: unlimited. Annual subscription only, **A$3.99 the first year, then A$9.99 a year**, auto-renewing. No lifetime option at launch.
- A lapsed subscription never stops existing reminders; it only blocks adding beyond 5 active.
- Prices to be tested before release (Play price experiments or a single-market launch).

## 6. Product principles

1. **The reminder always fires.** Nothing (payment, sponsor, agent, bug in a non-core feature) is allowed to stop a due reminder.
2. **Local-first.** No account and no network needed for anything on the phone. The cloud is an add-on for agents and, later, sync.
3. **Capture is open; enforcement is the product.**
4. **The user is in charge of interruptions.** Quiet hours, Do Not Disturb follow, approve-before-ring for agents, per-reminder alert levels.
5. **Play-policy safe by design.** No direct battery-exemption request, no accessibility service, no background location, foreground service only when there is a live timer or sound, and exact-alarm and full-screen declarations treated as review risks (handoff section 6).
6. **Australian English, plain words, AUD first.**

## 7. Design and UX: how much has to change

**Engine: no redesign.** Specs 1 to 8 are the right foundation.

**Data model: extend, don't replace.** Add, in one schema bump (Room v5): `source` (user, catalogue, agent, share, widget), `sourceId` (agent id or catalogue item id), `category`, `templateId` and `templateVersion`, plus the 26 Nov paper's `updatedAt`, `version`, `deletedAt` (soft delete), `approval` (for agent approve-before-ring) and an append-only `reminder_event` table. That is the Spec 9 groundwork, widened slightly for the catalogue.

**UI: redo the structure and the look before release.** The UI started as a test harness and visual polish was never done, so this costs less now than it ever will. Proposed structure:

- **Today** (overdue, due today, next up) as home, not a flat list.
- **All reminders** with categories.
- **Catalogue** (Stuff You Should Do).
- **Settings**, including Connected assistants (later) and Health check.
- Onboarding: permissions (exact alarms, notifications, full-screen), the health check, and the catalogue picker, in one flow.

Brand: a name and icon pass for Do, separate from Next Train and Mr Night.

## 8. Roadmap re-examined

How each item in the handoff's roadmap (section 5), and in Tim's 27 Nov notes, fares against this strategy:

| Item (from) | Before | Now | Why |
| --- | --- | --- | --- |
| Finish Spec 8 QA and merge PR #8 (handoff) | v1, next | **v1, next, smaller.** Repeating cap removed; ATs 14 to 18 retired | Decided 27 Nov |
| Close Spec 1 to 7 human ATs (handoff) | v1 | **v1, before release** | Enforcement is the moat |
| Monetisation re-decision (handoff) | Before release | **Done 27 Nov** | Decision note |
| Spec 9 groundwork: command layer, event log, source and sync fields (26 Nov paper) | Recommended, not approved | **v1, the next build after PR #8**, widened with category and template fields | Everything new (catalogue, agents, share sheet, widget) writes reminders through it. No network, no hosting needed |
| Billing: one-time `do_unlock` | Built (Spec 7) | **Change in v1** to an annual subscription with an introductory first year, and an "expired" entitlement state | Decided 27 Nov |
| Stuff You Should Do (27 Nov) | v1.1, after a competitor scan and spec | **Thin slice in v1** (bundled catalogue and picker, no links); links and sponsors post-launch | It solves the empty-app problem and drives upgrades through the 5 limit. The competitor scan is done (this document); Nico's scan can deepen it |
| Capture surfaces: share sheet, widget, Quick Settings tile, shortcuts (research Shoulds) | After v1 | **v1** | They are the free, on-device half of the AI last mile |
| On-device AppFunctions (26 Nov paper: v2) | v2 | **v1 if the API is ready; confirm** | No server; cheapest proof of the AI story |
| Cloud agent API, MCP and REST (26 Nov paper: v2; proposed light Spec 10 "Do Connect") | v2, or v1 if re-scoped, as a paid add-on | **At launch (decided 27 Nov 2026), free, as Spec 13** | Decided: AI is free and the cloud API ships with the Android launch. Needs a hosting account |
| Reusable countdown timers (research Should, "first" after v1) | Next after Spec 8 | **Moved after the catalogue and capture work** | Doesn't serve the new strategy as directly |
| Completed log with undo, bulk select, "give up after N nags" (research Shoulds) | After v1 | Unchanged, post-launch | |
| UI and brand redesign (not on the roadmap) | Polish "not finished" | **v1, before release, starting now as design work** | Section 7 |
| Play listing, declarations, signing (handoff) | With Tim's go-ahead | Unchanged; the subscription adds a Play Console subscription product | |
| Tim's notes: `setExactAndAllowWhileIdle` | | **No.** Keep `setAlarmClock()` | Doze throttling |
| Tim's notes: instant re-post on swipe | | **No for now** | Section 4, pillar 1 |
| Tim's notes: Room in WAL mode | | Already the Room default on modern Android; nothing to do | |
| Tim's notes: fixed versus completion-anchored repeats | | **Already built** (Spec 4) | |
| iOS | Later, not instead | **Changed 27 Nov 2026: iPhone close behind Android.** An iPhone engine test starts early, in parallel; shared core logic from Spec 9 | Tim plans to launch on Apple too |
| Due-derived names, wording and layouts (Specs 2 to 5) | Copied on purpose | **Replace before any launch** (see `DUE-DIFFERENTIATION-REVIEW.md`); in the design now, in code with the new UI spec | Do now launches next to Due |
| Name and trademark check | Not planned | **Now, before brand or icon work** | "Do" sounds like "Due" and is weak as a mark |
| Repo visibility | Public | **Make private, or remove the Due research and notes** | Public repo shows the roadmap and documents copying |

## 9. Proposed sequence to release

**Now (no code, can start straight away):**

1. **Name: keep "Do" (decided by Tim, 27 Nov 2026).** Still worth a trademark and store search before brand and icon work, to know what can be registered and what the store listing title needs (for example "Do" plus a descriptive subtitle). Reserve the name on both stores.
2. **Repo: stays public until launch (decided by Tim, 27 Nov 2026)**, because the risk is low and a private repo costs money (on GitHub's free plan, private repos themselves are free, but Actions minutes are capped at 2,000 a month; public repos have no cap). **Pre go-live action:** make it private, or remove the Due research and notes, before launch.
3. **Design pass:** drafted on 27 Nov 2026 (`docs/design/DESIGN-PASS-1.md`, 14 screens). Review with Gareth; brand and icon wait for the name.

**Build, in order:**

4. **PR #8** (quiet hours, cap removed): CI, the shorter phone rerun (AT-20 to AT-28), three hands-on ATs (05, 10, 12), Gareth's sign-off, Tim's OK to merge.
5. **Spec 9: data foundation (approved by Tim, 27 Nov 2026).** `ReminderCommands` layer that every entry point calls; Room v5 fields (source, sourceId, category, templateId, templateVersion, updatedAt, version, deletedAt, approval, requested alert level); `reminder_event` log; backup keys for the new fields; onboarding profile and suggestion state. No network, no UI change. **Core logic (repeats, natural-language parsing, quiet hours, catalogue, the commands) stays free of Android code so it can be shared with iPhone later**, for example through Kotlin Multiplatform.
6. **Spec 10: subscription billing.** Annual product with the introductory first year, entitlement with "expired", paywall wording that shows the renewal price.
7. **Spec 11: Stuff You Should Do v1.** Catalogue data and schema (from `docs/catalogue/sysd-starter.json`, with region tags down to state), state worked out from the time zone, first-run life areas and picks (opt out, up to 5 pre-ticked), Catalogue tab, the offer rules.
8. **Spec 12: capture surfaces.** Share sheet, widget, Quick Settings tile, shortcuts and deep link, and AppFunctions entry points (built now; switched on for Gemini when Google opens access).
9. **Spec 13: Do Cloud v1, at launch (decided by Tim, 27 Nov 2026; see 9B).** Device registration and push, assistant pairing without an account, the MCP and REST agent API with approve-before-ring, events back to the assistant, and server-side purchase verification. Free for everyone; agent reminders count towards the 5. Built against the Firebase emulator first; Tim creates the Firebase project before deploy. Can be built alongside Specs 10 to 12 and 14.
10. **Spec 14: the new UI** from the design pass, **including replacing every Due-derived name and string** (alert levels become Normal, Loud and Alarm; new add-field wording; Do's own time shortcuts; new preview interaction; reworded repeat menu and settings).
11. **Android release preparation:** close the Spec 1 to 7 human ATs, make the repo private (or remove the Due material), a lawyer's check of the name and final UI, Play declarations, store listing, price test. Only with Tim's go-ahead.

**In parallel, from soon after PR #8:**

- **iPhone engine test** (section 9A). Small, time-boxed, separate from the Android specs.

**After the Android launch:**

- **iPhone app** using the shared core and the engine test's findings, launching a couple of months after Android, with what was learned (pricing, onboarding, Stuff You Should Do) already in.
- Catalogue links, then sponsors and "Book it"; timers; the other Shoulds.

## 9A. Launch plan: Android first, iPhone close behind (decided by Tim, 27 Nov 2026)

**Recommendation.** Launch on Android first. Build for both from now on, prove the iPhone nag engine early in parallel, and launch on iPhone a couple of months after Android.

**Why not both at once:**

1. **iPhone is a new app, not a port.** Do is native Android (Kotlin, Compose, `setAlarmClock()`). On iOS the core engine works differently: an app can only have 64 scheduled local notifications at a time, which limits queued nags; ringing through silent mode and Focus needs Apple's Critical Alerts entitlement, which Apple grants case by case; AlarmKit (iOS 26) is the likely path but is untested for Do. The engine is the moat, so it needs proving on iPhone on its own terms.
2. **Double the QA.** One build team and one tester cannot hold the Specs 1 to 8 standard on two platforms without halving the speed.
3. **Learn once, apply twice.** Pricing, Stuff You Should Do in first run and the paywall are untested. Fixing them on one platform is cheaper.
4. **On iPhone, Do sits next to Due.** Apple's review is stricter about apps that resemble existing ones. Arriving with a proven, clearly different product is safer.

**On the risk of being copied and beaten to Apple:**

- Features can't be protected, so launching both at once does not stop copying; Due could add a catalogue or AI features at any time.
- Copying follows visible traction. A head start of a few months matters less than arriving with the better product.
- What protects Do: a nag engine people trust, a genuinely good catalogue, a name Do owns, and not publishing the roadmap (make the repo private).

**When to switch to launching both together:** if the iPhone engine test shows the engine works without a fight, there is budget for two build and QA tracks, or there is a single big launch-day publicity push planned.

### iPhone engine test (roadmap item)

- **Goal:** prove, on a real iPhone, that Do's nag-until-done behaviour can work: a reminder fires on time, nags at a set interval until Done, survives the app being closed and the phone restarting, and (where allowed) gets through silent mode and Focus.
- **Questions to answer:** what AlarmKit allows for repeated nags; how to stay within the 64-notification limit (for example rolling re-scheduling); whether Critical Alerts is needed and whether Apple is likely to grant it (apply early: it has the longest lead time); how the Loud and Alarm levels map to iOS; what can be shared from the Spec 9 core.
- **Shape:** a throwaway test app, not product code; time-boxed (suggest two weeks); result written up as a short report with a recommendation on the iPhone build approach.
- **Needs:** an Apple Developer account and a test iPhone. **Approved by Tim, 27 Nov 2026.**
- **When:** soon after PR #8, in parallel with Spec 9.

## 9B. Architecture: local-first with a thin cloud (decided by Tim, 27 Nov 2026)

The original "no server" design was right for a pure reminder app. With the cloud agent API at launch, an iPhone app, and subscriptions across two stores, Do now needs a small cloud. It does **not** become cloud-first.

- **The phone stays the authority** for every reminder and alarm. Do works fully with no network and no account. If the cloud is down, nothing stops nagging.
- **A thin cloud ("Do Cloud")** with four jobs: receive assistant requests and push them to the phone; relay the phone's events back to the assistant; verify purchases with Google Play (and later the App Store), which also fixes the resubscribe gap found in Spec 10's QA; and, later, optional sync between devices.
- **No account by default.** A device gets an anonymous identity only when it first uses a cloud feature. An account (Sign in with Google or Apple) is only for using two devices or carrying a subscription across Android and iPhone, later.
- **Store the minimum:** assistant requests until processed, events for assistant-created reminders for 30 days, nothing else. Unpairing an assistant deletes its data. Future sync can be end-to-end encrypted.
- **Hosting: Firebase recommended** (push for Android and iPhone, anonymous and Google/Apple sign-in, Cloud Functions and Firestore in one place). Cloudflare is the cheaper, less locked-in alternative. The phone talks to the cloud through one small interface so the host can be swapped. Firebase's Cloud Functions need the pay-as-you-go plan; free users' load is bounded by the 5-reminder cap.
- **Build without an account first:** Spec 13 is built and tested against the Firebase Emulator Suite. Tim creates the real Firebase project (his account, his go-ahead) only when it is time to deploy.
- **Not chosen:** cloud-first (server as the source of truth). It is why other apps' reminders arrive late, and it is the gap Do wins on.

The phone side needs no redesign: Spec 9's stable ids, versions, soft delete, event log, source and approval fields are what sync and agents need.

## 10. Decisions and open questions

**Decided by Tim, 27 Nov 2026:**

- Launch plan: Android first, iPhone close behind (9A).
- Name: keep "Do".
- Repo: stays public until launch; making it private (or removing the Due material) is a pre go-live action.
- Spec 9 (data foundation): approved as the next build after PR #8.
- iPhone engine test: approved.
- Catalogue name: "Stuff You Should Do" (SHOULD, not CAN). "Stuff" over "Things" is Tim's instinct; recommendation agrees, because "Things" is also the name of a well-known to-do app (Things 3), which is a closer clash than "Stuff You Should Know" (a podcast).
- Cloud agent API: at launch.
- Architecture: local-first with a thin cloud, Firebase recommended (9B).
- **compileSdk 37 (decided by Tim, 28 Nov 2026):** move Do to compileSdk 37 so Google's AppFunctions 1.0.0 can be wired in, as its own small change after PR #8 and Spec 9 merge. It does not change which phones Do runs on (minSdk stays 26); targetSdk is a separate decision.

- **Catalogue regions:** the data is built to work globally (each item tagged everywhere, a country such as `AU`, or a state such as `AU-WA`); the launch content is written for Australia. US, UK and NZ packs can follow without a redesign. Do works out the user's state from the phone's time zone (Perth: WA, Brisbane: Qld, Adelaide: SA, Darwin: NT, Hobart: Tas, Melbourne: Vic), with no location permission. "Sydney" covers NSW and ACT, so that one asks with a single tap. The picks screen shows "Using WA rules. Change" rather than asking a question. State-specific items get the right interval and wording; items that don't apply in that state are hidden.
- **AppFunctions:** build Do's AppFunctions entry points in Spec 12 as a thin layer over the Spec 9 commands; apply for Google's AppFunctions early access now so Do can be tested with Gemini on the S24+; do not make launch depend on it. Share to Do, the widget and the cloud agent API all work without it. If Gemini access for other apps has not opened by launch, the Assistants screen shows Gemini as "Coming soon". (As at late 2026, Gemini calling third-party apps' AppFunctions is still a limited preview.)

**Still open:**

1. Creating the Firebase project for Do Cloud (Tim, before deploy; not needed to build).

3. Applying for Google's AppFunctions early access (Tim, with a Google developer account).

## Sources (competitor scan, 27 Nov 2026)

- Home maintenance apps: selecthomewarranty.com/blog/best-home-maintenance-apps, housekeepr.ai/guides/best-home-maintenance-apps, fixreminder.com/blog/best-home-maintenance-apps-2026, realestateledger.io/guides/home-maintenance-schedule-app
- Android listings: play.google.com/store/apps/details?id=com.trymantle.app, play.google.com/store/apps/details?id=app.kepthouse, play.google.com/store/apps/details?id=com.againly
- Thumbtack: thumbtack.com/guide/content/the-biggest-product-update-in-our-history, research.contrary.com/company/thumbtack
- hipages: ASX announcements and quartr.com H2 2025 summary
- Amazon AU affiliate: affiliate-program.amazon.com.au (rate table not retrieved), indiehackers.com 2026 AU affiliate roundup
- Assistants: help.openai.com/en/articles/10291617-scheduled-tasks-in-chatgpt, tomsguide.com (Gemini Scheduled Actions)
- Due pricing: apps.apple.com/app/id390017969
- Tody: apps.apple.com/us/app/-/id595339588
