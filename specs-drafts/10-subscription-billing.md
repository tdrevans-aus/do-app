# Spec 10: Subscription billing (draft)

Owner: Gareth (PM). Build: a Claude build agent. QA: Mark, and a Claude test agent (host). Status: **draft by Claude, 27 Nov 2026 (AWST), not yet approved.** It follows the pricing decision Tim made on 27 Nov 2026 (`docs/decisions/2026-11-27-pricing-and-tiers.md`). Builds on Spec 9.
Sources: the pricing decision; `docs/design/DESIGN-PASS-1.md` (paywall and Settings screens); code on `do/spec-9-data-foundation` (billing package: `UnlockBilling`, `EntitlementPolicy`, `EntitlementStore`, `FreeTier`; release `PlayUnlockBilling`; debug `FakeUnlockBilling`; `ui/UnlockScreen.kt`); Google Play Billing Library 9 (already a release dependency, `billing = "9.1.0"`). Facts about Play Billing marked "Play" should be checked against the current Play Billing docs by the builder; anything not confirmed is marked assumed.

## 1. Goal
Replace Spec 7's one-time unlock with Do Unlimited, an auto-renewing annual subscription: **A$3.99 for the first year, then A$9.99 a year**. The free tier stays at 5 active reminders and is the only limit. A lapsed subscription never stops an existing reminder; it only blocks adding past 5. Prices always come from Play, never from the code.

## 2. Starts from Spec 9
Branch from `do/spec-9-data-foundation` (or `main` once Specs 8 and 9 have merged). Release billing is `PlayUnlockBilling` (one-time product `do_unlock`, `ProductType.INAPP`). Debug uses `FakeUnlockBilling`, which never talks to Play. The cap is enforced in `Scheduler.add` via `FreeTier.blocksAdd`. Do has never been released, so there are no real `do_unlock` buyers to migrate.

## 3. Scope

### A. The product (set up by Tim in Play Console, with his go-ahead; not by the code)
- Subscription product id `do_unlimited`. One base plan, `annual`, auto-renewing, 1 year, A$9.99.
- One offer on that base plan, `first-year`, for new customers only: one billing period of 1 year at A$3.99, then the base plan price.
- Other countries' prices are Tim's call; Play converts by default.
- `FreeTier.PRODUCT_ID` becomes `do_unlimited`; add `FreeTier.BASE_PLAN_ID = "annual"` and `FreeTier.INTRO_OFFER_ID = "first-year"`. These are the only places the ids live.

### B. Release billing: `PlayUnlockBilling` becomes subscription billing
Rename to `PlaySubscriptionBilling` (and the interface to `SubscriptionBilling`), keeping the debug and release split.
- Query product details with `ProductType.SUBS`. From `subscriptionOfferDetails`, pick the `first-year` offer if Play returns it (Play only returns offers this user is eligible for), else the base plan offer. Keep its `offerToken` for the purchase.
- **Price text from Play's pricing phases**, never hard-coded: with the intro offer, `intro = first phase formattedPrice` and `renewal = last phase formattedPrice`; without it, `renewal` only. Both are shown on the paywall (section D).
- Purchase with `ProductDetailsParams` plus the chosen `offerToken`.
- Query purchases with `ProductType.SUBS`. Play returns only subscriptions the user currently holds (including those in a grace period). Assumed: a subscription on account hold is not returned; check.
- Acknowledge new purchases (Play requires it within 3 days, or it refunds).
- Keep Spec 7's rules: grant only for PURCHASED; pending never grants; a failed query keeps the cached state; a successful query without the product revokes.
- Manage subscription: a button that opens Play's subscription page for Do (`https://play.google.com/store/account/subscriptions?sku=do_unlimited&package=com.evansappstudio.doapp`).
- No server and no Play Developer API in this spec. The app cannot see the exact expiry date; "active" means "Play currently returns a purchase for it". That is enough for Do's rules.

### C. Entitlement states
`EntitlementRecord` gains `everSubscribed: Boolean` (set the first time a grant happens; never cleared, survives backup restore). The UI state is derived:

| State | When | What the user sees |
| --- | --- | --- |
| `FREE` | never subscribed, not active | Free, "x of 5 used" |
| `ACTIVE` | Play returns an active purchase | Do Unlimited |
| `PENDING` | purchase pending | "Payment pending", no grant |
| `EXPIRED` | not active, `everSubscribed` | Free limits again, "Your subscription has ended. Renew any time." |

- `FreeTier.blocksAdd` is unchanged: only `ACTIVE` lifts the cap.
- `EXPIRED` behaves exactly like `FREE` for the cap, and **nothing else changes**: every existing reminder keeps firing and nagging, editing, Done, snooze, backup and export all work. Above-cap reminders stay; only adding a new one is blocked while there are 5 or more active.
- The cached record survives restarts and works offline, as today.

### D. Paywall (`UnlockScreen`) and copy
Follow the design pass paywall screen. The screen opens from the same places as today (adding past the cap), plus the Settings subscription card (new UI spec) later.
- Title "Keep everything on track", line "Do Unlimited removes the limit, for reminders you add, pick from the catalogue, or get from your assistant."
- Three ticks: "Unlimited reminders and repeats", "Everything else stays free, AI assistants included", "If you stop paying, your reminders keep nagging".
- Price block from Play: with the intro offer, "{intro} for your first year" and under it "then {renewal} a year. Cancel any time in Google Play."; without it, "{renewal} a year. Cancel any time in Google Play." If the price has not loaded, the buy button is disabled and the price block says "Price loading" (as Spec 7).
- Primary button "Get Do Unlimited" (or "Renew Do Unlimited" in `EXPIRED`). Secondary "Keep 5 for free", which closes the screen.
- Small print: "Renews at {renewal} a year after the first year unless cancelled." (only with the intro offer).
- In `ACTIVE`: "You have Do Unlimited." and the Manage subscription button.
- All copy in `strings.xml`, Australian English.

### E. Debug
- `FakeSubscriptionBilling` replaces `FakeUnlockBilling`. It starts in `ACTIVE` (as Spec 7's fake started unlocked), never connects to Play, and reports fake prices `A$3.99` and `A$9.99` so the paywall can be checked.
- `FAKE_PURCHASE --es state <free|active|pending|expired>` sets the state (replacing `unlocked|locked`); `--ez intro <true|false>` toggles whether the fake returns the intro offer.
- `DUMP_UNLOCK` keeps its line and adds `state=<FREE|ACTIVE|PENDING|EXPIRED> everSubscribed=<bool> intro=<price|none> renewal=<price|none> product=do_unlimited`. Existing fields stay so older runner regexes still match.
- The release APK must still contain no fake billing (`checkAlarmManifests` already checks release classes).

### F. Backup
- `everSubscribed` is not backed up (entitlement comes from Play). Restore never grants anything.

## 4. Out of scope
A lifetime option. Monthly plans. A server, the Play Developer API, real-time developer notifications. Free trials. Promo codes (Play can do them without code changes; assumed). Price testing. The Settings subscription card and "x of 5" indicators (new UI spec). Any Play Console setup (Tim, with his go-ahead). iPhone billing.

## 5. Acceptance tests

Host (unit, CI):
1. **Policy: active grants, pending doesn't.** `EntitlementPolicy` with a SUBS purchase in PURCHASED grants `ACTIVE`; PENDING gives `PENDING`; a successful query without it gives `FREE` or `EXPIRED` depending on `everSubscribed`; a failed query keeps the cache.
2. **everSubscribed is sticky.** Once granted, a later revoke gives `EXPIRED`, not `FREE`; restarting keeps it.
3. **Acknowledge only unacknowledged purchases**, as Spec 7.
4. **Offer choice.** Given product details with the `first-year` offer and the base plan, the intro offer's token is chosen and both prices are extracted from its phases; with only the base plan, the base token is chosen and intro is none. (Pure function over a small view of the offer data, so it can be tested without Play.)
5. **Cap in EXPIRED.** A free or expired user with 5 active cannot add a sixth; can still edit, Done, snooze and delete; reminders above 5 (for example from before expiry) keep their alarms.
6. **Copy.** The paywall text builder gives the three price variants (intro and renewal; renewal only; loading).
7. **Existing tests pass**, updated only where the unlock names changed.
8. **CI green**, both exact-alarm variants; release has no fake billing.

Phone (Mark with Tim, debug build with the fake):
9. **Paywall look.** `FAKE_PURCHASE free`, add a sixth reminder: the paywall shows A$3.99 first year, then A$9.99, and the small print. `--ez intro false`: only A$9.99 a year.
10. **Expired.** `FAKE_PURCHASE expired` with 7 active `T_s10_*` reminders: all 7 still fire and nag; adding an eighth opens the paywall with "Renew Do Unlimited".
11. **Real Play (later, Tim's go-ahead only).** On an internal testing track with a licence tester: buy, see the intro price, cancel, confirm the state after the test renewal period. Not part of this PR's sign-off.

## 6. Risks
- **Play behaviour details** (account hold, grace period, offer eligibility) are from Play's documented model, not tested here. AT-11 is where reality is checked, on an internal track.
- **Offline forever.** A cached `ACTIVE` stays active offline until a query succeeds. Accepted, as Spec 7.
- **Price jump at year 2.** The paywall shows the renewal price beside the intro price, and Play sends its own renewal notice.

## 7. Calls (Claude's defaults for Gareth)
1. Product id `do_unlimited`, base plan `annual`, offer `first-year`.
2. No server; "active" = Play returns the purchase.
3. `EXPIRED` is free limits plus different copy; nothing stops nagging.
4. Debug fake starts active.
5. The old `do_unlock` code path is removed, not kept alongside.
