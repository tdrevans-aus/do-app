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

Open items for Gareth and Tim (raised by QA, not yet decided):
6. **Resubscribe and Pause on the `annual` base plan.** A resubscribe from the Play Store after expiry is a new purchase that must be acknowledged within three days; Do has no backend, so it acknowledges only when the user next opens Do (Play would refund otherwise). A paused subscription reads as ended in Do (only adding past 5 is blocked). Either turn both off in Play Console, or accept them and test both in AT-11 (resubscribe in the Play Store, then don't open Do for a day; pause, then resume).
7. **Auto Backup and `everSubscribed`.** Android Auto Backup includes `do_entitlement.xml` (since Spec 7), so `everSubscribed` comes back on a new phone, and a restored cache can read ACTIVE until the first successful Play query. Section F is read as "not in Do's own backup file". Please confirm that is acceptable, or say if the entitlement file should leave the Auto Backup rules.

## Build notes (Claude build agent, 27 Nov 2026)

### Play Billing facts checked
Checked against Google's Play Billing docs (integrate, subscriptions, subscription lifecycle) on 27 Nov 2026:
- `queryProductDetailsAsync` with `ProductType.SUBS` returns the product and up to 50 offers **the user is eligible for** in `subscriptionOfferDetails`. Each has `basePlanId`, `offerId` (null for the base plan's own offer), `offerToken` and `pricingPhases.pricingPhaseList` in order, each phase with `formattedPrice`. Confirmed.
- Purchase with `ProductDetailsParams.setProductDetails(...).setOfferToken(...)`. Confirmed.
- `queryPurchasesAsync` with `ProductType.SUBS`: returns subscriptions in a **grace period** (still `PURCHASED`) and cancelled ones until the paid period ends. It does **not** return them on **account hold** (confirmed, so the spec's assumption is right) or while **paused** (unless `includeSuspendedSubscriptions` is set, which Do does not set). Both read as `EXPIRED` here until Play returns the purchase again.
- New subscription purchases must be acknowledged within three days or Play refunds and revokes them. Renewals need no acknowledgement (they arrive already acknowledged, so the existing "acknowledge only unacknowledged" rule is right). Never acknowledge `PENDING`.
- Pending transactions for subscriptions are only for **prepaid** plans (Billing 7+), enabled explicitly. Do sells an auto-renewing plan, so `enablePrepaidPlans()` is not called and `PENDING` should be rare for Do Unlimited. It is still mapped to `PENDING` (no grant) in case Play reports it. The builder still needs `enablePendingPurchases(enableOneTimeProducts())`, kept as before.
- The management deep link `https://play.google.com/store/account/subscriptions?sku=<productId>&package=<package>` is the documented format. The app builds it from `FreeTier.PRODUCT_ID` and the running package name.

### Choices
1. **Offer choice** (`SubscriptionOffers.choose`, pure Kotlin): only offers on base plan `annual` with a non-blank token and non-blank phase prices count. `first-year` is chosen if present with at least two phases (intro = first phase, renewal = last); otherwise the base plan offer (`offerId == null`), renewal only. Any other offer (a future winback, say) is ignored, so it can't be sold by accident. Nothing usable means "Price loading" and Buy disabled.
2. **Names.** `UnlockBilling` is now `SubscriptionBilling`, `UnlockUiState` is `SubscriptionUiState` (holds a `SubscriptionState` and a `PriceInfo(intro, renewal)` in place of `unlocked`, `pending` and `price`), `PlayUnlockBilling` is `PlaySubscriptionBilling`, `FakeUnlockBilling` is `FakeSubscriptionBilling`, `createUnlockBilling` is `createSubscriptionBilling`, `formattedPrice()` is `price()`. `EntitlementRecord.unlocked` is renamed `active`; the SharedPreferences key stays `unlocked`. The screen keeps its file and composable name `UnlockScreen` (the spec calls it that). `Scheduler`'s `paidUnlocked` parameter is unchanged (Spec 9 code); `AppContainer` now passes `SubscriptionState.of(record).liftsCap`.
3. **State derivation** (`SubscriptionState.of`): active wins, then pending, then `everSubscribed` gives `EXPIRED`, else `FREE`. So a pending renewal after a lapse shows "Payment pending". Only `ACTIVE` lifts the cap.
4. **Paywall copy** is built by `PaywallCopy.build` from templates in `strings.xml` (`%1$s` is replaced, not `String.format`, so a price containing `%` is safe). "Price loading" replaces Spec 7's "Price unavailable". "Keep 5 for free" takes the number from `FreeTier.ACTIVE_REMINDER_CAP`. The ticks are drawn with a plain tick character. PENDING and EXPIRED add a status line above the price. Restore purchases is kept (re-queries Play). The success dialog now says "You have Do Unlimited." The old `unlock_*` strings are removed.
5. **Settings row.** The existing Spec 7 row on the snooze settings screen now reads "Do Unlimited" with the state line or the price headline. The full Settings subscription card is still out of scope.
6. **Debug fake.** Starts `ACTIVE` (with `everSubscribed=true`). `buy` stands in for a completed purchase and grants `ACTIVE` (Spec 7's fake had no price, so Buy was disabled; now the fake has prices, so the button needed to do something). `FAKE_PURCHASE` accepts `free|active|pending|expired`, and Spec 7's `unlocked` and `locked` as aliases for `active` and `free`. `--ez intro` on its own keeps the state; with neither extra the state is `active` (Spec 7's default). `free` clears `everSubscribed` (Play never would; debug only). The intro toggle is in memory and resets to true on process restart. The `FAKE_PURCHASE` log line gains `intro=<bool>` at the end.
7. **`DUMP_UNLOCK`** is one line: the Spec 7 fields first, unchanged in name and order (first `state=` is `unlocked|locked`, `price=` is the renewal price, `product=` is now `do_unlimited`), then `state=<FREE|ACTIVE|PENDING|EXPIRED> everSubscribed= intro= renewal= product=do_unlimited` exactly as section E. So `state=` and `product=` appear twice; a runner wanting the new state should match `state=(FREE|ACTIVE|PENDING|EXPIRED)`.
8. **Backup (section F and the "survives backup restore" in C).** `everSubscribed` is never in Do's own backup file (the entitlement prefs never were). Android Auto Backup still includes `do_entitlement.xml`, as since Spec 7 (an existing test asserts it), so `everSubscribed` comes back on a new phone via Auto Backup; that is what "survives backup restore" means here. A restored cache can show `ACTIVE` only until the first successful Play query, the same offline risk Spec 7 accepted. The rules are unchanged.
9. **`EntitlementStore`** gains an internal constructor over `SharedPreferences` so AT-2 can check a restart with an in-memory store. No new dependencies.

### Tests changed (AT-7)
`UnlockLogicTest`: `record.unlocked` is now `record.active` (5 asserts), the `EntitlementRecord(unlocked = true, ...)` argument is now `active = true`, and the product id assertion is now `do_unlimited`. Nothing else changed. `checkAlarmManifests` now looks for `FakeSubscriptionBilling` in place of `FakeUnlockBilling` (release must not have it, debug must).

### AT map
| AT | Test |
| --- | --- |
| 1 | `billing/SubscriptionLogicTest.at1ActiveGrantsPendingDoesNotAndAFailedQueryKeepsTheCache` |
| 2 | `billing/SubscriptionLogicTest.at2EverSubscribedIsStickyAndSurvivesARestart` |
| 3 | `billing/SubscriptionLogicTest.at3OnlyUnacknowledgedPurchasedSubscriptionsAreAcknowledged` (plus the existing `UnlockLogicTest.acknowledgementIsOnlyForPurchasedUnacknowledgedTokens`) |
| 4 | `billing/SubscriptionLogicTest.at4TheIntroOfferIsChosenWhenPlayReturnsItElseTheBasePlan` |
| 5 | `alarm/SubscriptionCapTest.at5ExpiredKeepsEveryReminderWorkingAndOnlyBlocksAddingPastFive`, `at5FreeAndExpiredHaveTheSameCapAndPendingDoesNotLiftIt` |
| 6 | `billing/SubscriptionLogicTest.at6PaywallTextHasTheThreePriceVariants` |
| 7 | the whole `testDebugUnitTest` suite, with `UnlockLogicTest` changed only as listed above |
| 8 | `assembleDebug testDebugUnitTest lintDebug` and `assembleDebug -PexactAlarmMode=SCHEDULE`; both run `checkAlarmManifests`, which compiles the release source set and checks release has no `FakeSubscriptionBilling` or `FAKE_PURCHASE` |

### For Tim in Play Console (with his go-ahead)
- Create subscription `do_unlimited`, base plan `annual` (auto-renewing, 1 year, A$9.99), offer `first-year` on it (new customers only, one 1-year phase at A$3.99). The ids must match exactly.
- Grace period and account hold are on by default; the app needs no change for either. Consider turning **pause** off for this plan: a paused subscription reads as ended in the app (only adding past 5 is blocked).
- Add a licence tester and an internal testing track for AT-11.

### Fixes after QA
QA (`specs/10-qa-report.md`, `Spec10QaTest`, 18 tests) found one medium and four low bugs. All fixed; the 3 failing QA tests now pass unchanged.
1. **Settings row showed the intro price alone (medium).** The row now shows the renewal line (`priceDetail`, "then A$9.99 a year...") under the intro headline whenever there is no status line, so an intro price never appears without its renewal price. Paywall unchanged (it already did this).
2. **ITEM_ALREADY_OWNED from `launchBillingFlow`** now re-queries purchases instead of showing "Purchase failed", as `onPurchasesUpdated` already did.
3. **Callback threading.** Every Play callback body (setup, purchases, details, purchases updated) and `restore`, `clearCelebrate` and `connect` now run on the main thread through `onMain`, so the billing fields have one owner and `publish()` can't publish a stale snapshot. `buy()` was already main-only. The details callback reads Play's result off-thread and hands only the chosen `ProductDetails` to the main thread.
4. **Cached active record without `ever_subscribed`.** `EntitlementPolicy` now sets `everSubscribed = cache.everSubscribed || cache.active || active`, so a Spec 7-era or restored active cache revokes to EXPIRED, not FREE.
5. **Play unreachable.** A failed `onBillingSetupFinished` or details query sets `SubscriptionUiState.storeUnavailable`. With no price loaded the paywall and Settings row say "Can't reach Google Play right now" (new string `paywall_store_unavailable`) in place of "Price loading"; Buy stays disabled. A failed refresh keeps the last good price, offer token and `ProductDetails`, and a later successful query clears the flag. Each resume retries. Google advises against caching `ProductDetails`; if the kept one has gone stale, `launchBillingFlow` fails and the paywall shows "Purchase failed", and the next successful refresh replaces it. New fields have defaults (`storeUnavailable = false`, `PaywallTemplates.unavailable = null`), so QA's constructor calls are unchanged.
New test: `SubscriptionLogicTest.anUnreachablePlayStoreSaysSoAndKeepsALoadedPrice` (AT-6, bug 5). Bugs 2 and 3 are release-only code and are covered by QA's source check (bug 2) and AT-11 (bug 3).

**Spec 9 merged.** `origin/do/spec-9-data-foundation` (Spec 9 QA tests and fixes, 3235db6) was merged with a merge commit, not a rebase, because this branch is already pushed. No textual conflicts. Spec 9's move of pure types into `alarm/CommandTypes.kt` and its Scheduler changes needed no Spec 10 edits: `Origin`, `Actor` and `EditResult` keep their package, and the cap path in `Scheduler.add` is unchanged.

**Checks after the fixes and merge.** `assembleDebug testDebugUnitTest lintDebug`: 193 tests, 0 failures, 0 skipped (Spec 9 QA 19, Spec 10 QA 18), lint 0 errors. `assembleDebug -PexactAlarmMode=SCHEDULE` and `assembleRelease` (unsigned APK) both succeed.

