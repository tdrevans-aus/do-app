# Do design pass 1

**Date:** 27 Nov 2026 (AWST)
**Status:** First draft for Tim and Gareth. Wireframes are on the private design canvas "Do design pass" (link in chat; share it from the canvas before others can open it).
**Builds on:** `docs/strategy/DO-PRODUCT-STRATEGY.md`, `docs/decisions/2026-11-27-pricing-and-tiers.md`, `docs/catalogue/sysd-starter.json`.

## Do and Stuff You Should Do: how they fit (decided 27 Nov 2026)

Do is the product, and Stuff You Should Do is **part of the default way in, with an opt-out**, not a mode you have to find.

- **First run, four steps:** permissions, your first reminder, "What do you look after?", then picks, then Today. People get what they came for (a reminder) before any questions.
- **Skip is always visible** on steps 2 to 4.
- **Picks come pre-ticked, but only up to the free 5**, counting the first reminder. Nobody sees the paywall in first run unless they tick more themselves.
- **Privacy line:** the life-areas answers stay on the phone and only shape suggestions.
- **Offered again after first run** (rules below), always inside the app, never as a notification.
- **A second way in:** a Google Play custom store listing aimed at life maintenance can deep-link into the catalogue. (Custom store listings are a Play Console feature; the deep-link wiring is to be confirmed when it is specced.)

### When Stuff You Should Do is offered after first run

1. Skipped in first run: a head-start card on Today the next day.
2. "Not now" hides cards for 14 days; a second "Not now", 60 days. "Stop suggesting" turns all cards off; the Catalogue tab stays.
3. Seasonal: at most once a month, from catalogue items with a suggested month or season (southern hemisphere).
4. After a catalogue item is marked Done for the first time: one related item, once.
5. While typing in Add: if the text matches a catalogue item ("car service"), a small "Related: tyres, rego" line.
6. Never in a notification. The nag channel is only for reminders.
7. At most one card on Today at a time, always below Overdue.

## Screens drawn

| # | Screen | What it settles |
| --- | --- | --- |
| 1 | First run: let Do get through | One screen for the four things Android must allow: notifications, alarms and reminders (exact alarms), full-screen alarms (optional, only for Prominent), and battery (opens the system list, never a direct exemption request, per Play policy). Red dot means needed and missing. A "Test in 1 min" alarm. You can continue with gaps; the health check picks them up later. |
| 2 | First run: your first reminder | One field, natural language, with a live preview and four example chips. Says it will nag until done. |
| 3 | First run: what you look after | Eight life areas (home, rental, car, kids, pets, health, money and admin, digital). Skip visible. Says the answers stay on the phone. |
| 4 | First run: picks | Suggested items grouped by area, up to 5 pre-ticked (counting the first reminder), each with its interval and how it repeats. "Check your state" tag where rules vary. A live counter against the free 5. |
| 5 | Today | Overdue, Later today, Coming up. Every reminder shows where it came from (You, Catalogue, Assistant), and whether it is nagging and how often. One tap marks Done. The Stuff You Should Do card sits below Overdue; three variants in the artboard's Tweaks: head start, seasonal, after a catalogue item is done. |
| 6 | Add | Natural-language field with the "Do understood" preview (date and repeat), Quick Times grid, nag interval, and the three alert levels with a one-line explanation of each. |
| 7 | Catalogue tab | Browse by area, search, add or remove items. Shows the "why" for each item. |
| 8 | Assistants | Gemini on the phone (AppFunctions) and Share to Do, now. Other assistants through the cloud link, later. An approval card when an assistant asks for Critical or Prominent. Rules every assistant must follow. |
| 9 | The nag | Two views (switch in the artboard's Tweaks): the lock-screen notification and the Prominent full-screen alarm. Notification: title, due time, "nagging every 5 min until you mark it done", nag count, and Done, 10 min, Snooze to... as real actions, plus a grouped "2 more overdue, Snooze all". Full-screen: a big Done; Snooze 10 min; Stop sound, which stops the ringing but keeps the nag going. Only Done ends a reminder. |
| - | Paywall | Only when the user ticks more than the free 5. A$3.99 first year and the A$9.99 renewal on the same screen; "Keep 5 for free" as an equal option; reminders keep nagging if you stop paying. |

## Structure

Four tabs: **Today**, **All**, **Catalogue**, **Settings** (assistants, quiet hours, health check, backup, subscription). Add is a floating button on Today and All.

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
- Suggestion state: when each card was last shown, how many times it was dismissed, and "Stop suggesting".
- An assistant rules setting (ask before ring-through, what to report back).

## Open design questions

1. **Renewal dates.** Seven yearly items (lease, contents insurance, rego, car insurance, pet registration, other insurance, health insurance) have no fixed month; the date belongs to the user. The picks screen needs a way to ask for it, or the reminder starts undated.
2. **State-level regions.** Some items only make sense in some states (for example comparing energy plans in WA). The catalogue may need state tags, not only "AU", and onboarding may need to ask for the user's state.
3. **Stop sound on the full-screen alarm.** Drawn as "stops the ringing, keeps nagging". Spec 5 has Stop; confirm it behaves this way today, or decide it should.
4. **Snooze all** in the grouped notification is drawn from Spec 2's grouping; confirm the wording.
5. **Not drawn yet:** All list, Settings main, editing an existing reminder, widget, Quick Settings tile, share sheet target.
