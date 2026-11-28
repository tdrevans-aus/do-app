# Decision: pricing, free tier and AI access

**Date:** 27 Nov 2026 (AWST, Australia/Perth)
**Decided by:** Tim Evans
**Status:** Decided, except the items under "Still open". Prices are to be tested before the first release.

This replaces the earlier plan of an A$12.99 one-time unlock with a free tier of 5 nagging and 2 repeating reminders (Specs 7 and 8). It settles the handoff note "re-decide monetisation with Tim before the first release".

## Context

Do has grown from "copy Due" into a bigger idea: the Due-style nag engine, the last mile for an AI assistant, and Stuff You Should Do (a catalogue of periodic life-maintenance tasks), with sponsors and links as a possible later revenue line. The revenue model had to be settled first, because it decides the free tier, onboarding, network use and the UI.

## Decisions

1. **No display ads, ever.** No ad SDK. Ads need daily use at scale, which a nag app does not get, and they damage trust in an app that rings through Do Not Disturb. "Ads" was a common 1-star theme in rival reviews.
2. **Freemium.** The app is free to install and use, and the main revenue is a paid tier.
3. **All AI assistant features are free.** That includes on-device capture (natural-language add, share sheet, shortcuts, widget, tile, AppFunctions) and, when built, the cloud agent API (MCP and REST), events back to the agent, and assistant-set alert levels. There is no separate paid "Do Connect" product. Rate limits apply to everyone, to stop abuse, and do not depend on paying.
4. **One paywall: the number of active reminders.** The free tier allows **5 active (not-done) reminders**. Paid is unlimited. **The repeating cap of 2 (Spec 8, `FreeTier.REPEATING_REMINDER_CAP`) is dropped.** Repeating reminders count towards the 5 like any other, with no separate limit. The catalogue is mostly repeating tasks, so a separate repeat cap would have worked against it.
5. **Reminders created by an AI agent count towards the free limit**, the same as any other reminder. The reasoning: people who connect an assistant and like it will want more than 5, and that is the natural upgrade moment. The cap also keeps free users' server load small.
6. **Stuff You Should Do (catalogue) is in the free tier.** Items it creates count towards the same limit.
7. **Price: annual subscription only, auto-renewing. A$3.99 for the first year, then A$9.99 a year.** The first year uses Play's introductory pricing (once per user). Over 3 years that is A$23.97, against about A$27 to A$34 for Due (A$12.99 up front plus its A$6.99 a year pass), and Do has a free tier. The ongoing A$9.99 is above Due's A$6.99 pass. The paywall and Play's renewal notice must state the A$9.99 renewal price clearly, so the jump at year 2 is not a surprise. Watch renewal churn at year 2.
8. **No lifetime option at launch.** One can be added later; removing one after launch would upset people. A 3-year prepaid pass was considered and rejected, because Google Play subscriptions only go up to 1 year, so it could not auto-renew.
9. **A lapsed subscription never stops reminders.** Every existing reminder keeps nagging. The user cannot add more beyond 5 active until they renew. Backup and export always work.
10. **Sponsors, affiliate and "Book it" links are a later, second revenue line**, not v1. They come after there is an audience in one region. Paid users can hide them. Health items are never sponsored. A sponsor never changes when or how a reminder fires.

## Decided 28 Nov 2026 (Tim)

11. **Play Console settings for the `annual` base plan:** Resubscribe **on** (lapsed users can rejoin from the Play Store in one tap; Do Cloud's server confirms the purchase straight away, so the 3-day acknowledgement gap closes once Do Cloud is live). Pause **off** (a paused plan would read as "ended" in Do, and pausing adds confusion on an annual plan; anyone wanting a break can cancel and resubscribe).

12. **Restoring a deleted reminder and the free limit:** an immediate undo (within 30 seconds of deleting) always works; a later restore respects the free limit of 5, like adding. Imports are unchanged (allowed above the limit).

13. **Android Auto Backup restoring "has subscribed before":** acceptable. It only changes the paywall wording ("Renew" rather than "Get"); Google Play still decides whether a subscription is active, at the first successful query.

## Still open

- **Free trial:** whether to add a short free trial of the paid tier on top of the A$3.99 first year. Probably not needed, since the free tier already lets people try everything. Not decided.
- **Where the repeating cap is removed:** from open PR #8 before it merges, or in a later spec after it merges. Not decided.
- **Name:** "Stuff You Should Do" or "Stuff You Can Do". A trademark check is needed either way ("Stuff You Should Know" is a well-known podcast).
- **Catalogue scope:** Australian-first (rego, state smoke alarm rules) or global.
- **Spec 9 groundwork and the cloud agent API** are still not approved, and no hosting account may be used until Tim names one.

## What this changes in the code (for a later spec, not done here)

- Spec 7's one-time `do_unlock` product is replaced by an annual subscription in `billing`.
- Entitlement gains an "expired" state, which keeps existing reminders working and only blocks adding beyond 5.
- `FreeTier.REPEATING_REMINDER_CAP`, the `BLOCKED_REPEAT` result from `Scheduler.update`, and the repeat-cap path to the unlock screen (all from Spec 8) are removed. Spec 8 ATs 14 to 18 that test the repeat cap are retired, not rerun. `ACTIVE_REMINDER_CAP` stays at 5.
- Test prices before launch, with Play Console price experiments or a single-market launch.

## Reference prices (from research, not tested on Do users)

- Due (iOS): A$12.99 one-time plus an optional A$6.99 a year Annual Upgrade Pass. One reviewer reported widgets need the pass.
- Tody (chores): about US$10 a year.
- Home maintenance apps: mostly US$59 to US$199 a year.
- Android reminder rivals price low (for example BZ Reminder, US$2.49 to US$4.99 per item).
