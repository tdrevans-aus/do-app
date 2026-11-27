# Do design pass 1

**Date:** 27 Nov 2026 (AWST)
**Status:** First draft for Tim and Gareth. Wireframes are on the private design canvas "Do design pass" (link in chat; share it from the canvas before others can open it).
**Builds on:** `docs/strategy/DO-PRODUCT-STRATEGY.md`, `docs/decisions/2026-11-27-pricing-and-tiers.md`, `docs/catalogue/sysd-starter.json`.

## Screens drawn

| # | Screen | What it settles |
| --- | --- | --- |
| 1 | Onboarding: what you look after | Eight life areas (home, rental, car, kids, pets, health, money and admin, digital) drive the catalogue suggestions. Skip goes straight to Today. |
| 2 | Onboarding: Stuff You Should Do picks | Suggested items grouped by area, ticked by default, each showing its interval and how it repeats. A "Check your state" tag on state-dependent items. A live counter against the 5 free slots. |
| 3 | Paywall at the 5 limit | Appears only when picks go over 5. Shows A$3.99 first year and the A$9.99 renewal on the same screen. "Keep 5 for free" is a full-size option. Says plainly that reminders keep nagging if you stop paying. |
| 4 | Today (home) | Overdue, Later today, Coming up. Every reminder shows where it came from (You, Catalogue, Assistant) and whether it is nagging and how often. One tap marks Done. |
| 5 | Add | Natural-language field with the "Do understood" preview (date and repeat), Quick Times grid, nag interval, and the three alert levels with a one-line explanation of each. |
| 6 | Catalogue tab | Browse by area, search, add or remove items. Shows the "why" for each item. |
| 7 | Assistants | Gemini on the phone (AppFunctions) and Share to Do, now. Other assistants through the cloud link, later. An approval card when an assistant asks for Critical or Prominent. Rules every assistant must follow. |

## Structure

Four tabs: **Today**, **All**, **Catalogue**, **Settings** (assistants, quiet hours, health check, backup, subscription). Add is a floating button on Today and All. Onboarding runs once: permissions and health check (not drawn yet), then screens 1 to 3.

## Look

- Type: Space Grotesk for headings, Inter for everything else.
- Ground `#F7F8FA`, ink `#111827`, secondary text `#4B5563` (passes 4.5:1).
- One alarm colour, `#C2391B`, kept for overdue, nagging and the purchase button, so red always means "act now".
- Source chips: You (grey), Catalogue (amber `#7A3E0B` on `#FCEFD9`), Assistant (blue `#1E4FA0` on `#E8EFFB`).
- Touch targets 44 px or more. Real buttons, checkboxes and labels throughout.

## What this means for Spec 9 (data)

The screens need these on each reminder:

- `source`: user, catalogue, agent, share, widget. Drives the chip on Today.
- `sourceId`: the catalogue item id or the agent's id.
- `category`: one of the eight onboarding areas, for the catalogue and for filtering All.
- `templateId` and `templateVersion`: which catalogue item it came from, so catalogue updates can be offered later.
- `approval`: pending, approved, declined, for assistant reminders that ask to ring through.
- `requestedAlertLevel` alongside the stored alert level, so "As Standard" can downgrade without losing what the assistant asked for.

Plus, outside the reminder table:

- A per-user onboarding profile (which of the eight areas were picked), so the catalogue can keep suggesting.
- An assistant rules setting (ask before ring-through, what to report back).

## Open design questions

1. **Renewal dates.** Seven yearly items (lease, contents insurance, rego, car insurance, pet registration, other insurance, health insurance) have no fixed month; the date belongs to the user. The picks screen needs a way to ask for it, or the reminder starts undated.
2. **State-level regions.** Some items only make sense in some states (for example comparing energy plans in WA). The catalogue may need state tags, not only "AU", and onboarding may need to ask for the user's state.
3. **Onboarding permissions step.** Exact alarms, notifications, full-screen and the health check need one combined screen. Not drawn yet.
4. **The notification itself.** The nag notification (Done, Snooze, Snooze to) is the most-seen screen and is not drawn yet.
5. **Not drawn yet:** All list, Settings main, editing an existing reminder, widget, Quick Settings tile, share sheet target.
