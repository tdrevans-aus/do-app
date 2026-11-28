# Spec 10 QA report: subscription billing

QA: Claude test agent. Branch `do/spec-10-subscription-billing`, reviewed head 077ca8c (diff `a97bae6..077ca8c`). Run window: Fri 27 Nov 2026, 16:14 to 16:25 AWST.

**Verdict: PASS WITH BUGS.** The builds are green, the free-tier rule is right (nothing in Spec 10 can stop a reminder nagging), release has no fake billing and Play Billing use is broadly correct. One medium bug (the Settings row shows the A$3.99 intro price without the A$9.99 renewal price) should be fixed before merge. The low bugs can go in this PR or a follow-up.

## 1. Commands and results

| Command | Result |
| --- | --- |
| `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintDebug` (builder head, before QA tests) | BUILD SUCCESSFUL. 154 tests, 0 failures, 0 skipped. Lint 0 errors, 86 warnings, none from Spec 10 files (58 UseKtx, 7 GradleDependency, 5 UnusedResources on older `alert_*` strings, and so on). `checkAlarmManifests` ran. |
| `./gradlew --no-daemon --max-workers=2 assembleDebug -PexactAlarmMode=SCHEDULE` | BUILD SUCCESSFUL (`checkAlarmManifests` ran in SCHEDULE mode). |
| `./gradlew --no-daemon --max-workers=2 assembleRelease` | BUILD SUCCESSFUL, `app-release-unsigned.apk`. |
| Same first command with `Spec10QaTest` added (`--continue`) | 172 tests, 3 failures (the 3 marked QA tests below). Lint unchanged. |

No HTTP 429s. Independent APK check: unzipped the release APK and searched every file for `FakeSubscriptionBilling`, `FAKE_PURCHASE`, `DebugReceiver`, `FakeUnlock`: 0 hits each. `PlaySubscriptionBilling` and `do_unlimited` present. No `3.99` or `9.99` anywhere in the release APK. The compiled release `PlaySubscriptionBilling` classes hold the constant `subs` and no `inapp`.

## 2. Acceptance tests

| AT | Verdict | Notes |
| --- | --- | --- |
| 1 Policy | PASS | Real coverage of PURCHASED, PENDING, OTHER, revoke to FREE or EXPIRED, failed query keeps the cache, old `do_unlock` grants nothing. QA added: PURCHASED plus PENDING is ACTIVE; multi-product purchase; no repeat celebration; a failed query never grants or acknowledges. |
| 2 everSubscribed sticky | PASS | Real restart through `EntitlementStore` over in-memory prefs. Gap found: a cache that is active but has no `ever_subscribed` key revokes to FREE (bug 4). |
| 3 Acknowledge | PASS | Policy level only (which tokens). The actual `acknowledgePurchase` call is in release code with no unit test; read by hand and correct (failure is logged and the next query retries because the purchase is still unacknowledged). |
| 4 Offer choice | PASS | Good pure function and tests. QA added list order, a three-phase offer, an intro id on another base plan, intro-only. |
| 5 Cap in EXPIRED | PASS | Strong: 7 reminders, lapse, the 6th fires and nags, reschedule keeps alarms, edit, snooze, Done, delete all work, cap blocks at 5 and lifts on renew. Uses the fixture's `paid` flag; QA added a check of the real `AppContainer` wiring. |
| 6 Copy | WEAK | The builder's test covers `PaywallCopy` for all three price variants and the states, but nothing checks the other place prices appear, the Settings row, which shows the intro price alone (bug 1). |
| 7 Existing tests | PASS | `UnlockLogicTest` diff is exactly the renames listed in the Build notes (7 lines). All pre-existing tests pass. |
| 8 CI, both variants, no fake in release | PASS | Both variants built; `checkAlarmManifests` needles updated to `FakeSubscriptionBilling` and still `FAKE_PURCHASE`, and it scans release class output (path `built_in_kotlinc/release/...` matches its `/release/` filter). Confirmed independently on the APK. |
| 9 Paywall look (phone) | NOT RUN | Phone test for Mark with Tim. |
| 10 Expired (phone) | NOT RUN | Phone test for Mark with Tim. |
| 11 Real Play | NOT RUN | Later, Tim's go-ahead only. Notes in section 6. |

## 3. Bugs

### Bug 1 (Medium): Settings row shows the intro price without the renewal price
- `app/src/main/java/com/evansappstudio/doapp/ui/SnoozeSettingsScreen.kt:114`: `Text(text = copy.statusLine ?: copy.priceHeadline)`.
- Repro: debug build, `FAKE_PURCHASE --es state free`, open Settings. The Do Unlimited row reads "A$3.99 for your first year" with no mention of A$9.99. The spec (section 6) relies on the renewal price always sitting beside the intro price, and Play's subscription policy expects the full price after an offer to be clear wherever the offer is shown.
- Fix: show `copy.priceDetail` (or the small print) under the headline in the row, or use the renewal-only line there.
- Test: `Spec10QaTest.theSettingsRowNeverShowsTheIntroPriceWithoutTheRenewalPrice` (fails).

### Bug 2 (Low): ITEM_ALREADY_OWNED from `launchBillingFlow` shows "Purchase failed"
- `app/src/release/java/com/evansappstudio/doapp/billing/PlaySubscriptionBilling.kt:89-95`. Only OK and USER_CANCELED are excused; Google's `BillingClient.launchBillingFlow` reference says it can return ITEM_ALREADY_OWNED.
- Repro (needs Play): cache says EXPIRED or FREE while Play holds an active subscription (renewed on another device or in the Play Store while offline), tap Renew. The user sees "Purchase failed. You are still on the free version." and nothing re-queries.
- Fix: treat ITEM_ALREADY_OWNED in `buy()` the same as in `purchasesUpdated` (call `queryPurchases()`, do not set `failed`).
- Test: `Spec10QaTest.anAlreadyOwnedLaunchResultRequeriesInsteadOfShowingPurchaseFailed` (fails, source check).

### Bug 3 (Low): billing state written from callbacks without synchronisation
- `PlaySubscriptionBilling.kt:35-40` (`productDetails`, `offerToken`, `price`, `failed`, `celebrate` are plain `var`s) and `publish()` at 226-235. They are written in the purchases, product-details and purchases-updated callbacks and read on the main thread in `buy()`. Google's reference says async callbacks for `@AnyThread` methods return "on the same thread" as the call, and these calls are made from inside other callbacks, so the thread is not guaranteed to be main. `publish()` is read-then-write, so two callbacks finishing together can publish a stale snapshot (for example `price = null` after the details callback set it), leaving "Price loading" and Buy disabled until the next resume.
- Fix: post every callback body to the existing `main` handler (one thread owns the state), or mark the fields `@Volatile` and publish with `uiState.update { current() }` under a lock.
- No unit test (release-only classes); for AT-11, watch for "Price loading" sticking after the first open.

### Bug 4 (Low): an active cache with no `ever_subscribed` key revokes to FREE, not EXPIRED
- `app/src/main/java/com/evansappstudio/doapp/billing/EntitlementPolicy.kt:48`: `everSubscribed = cache.everSubscribed || active`. A cache written before the key existed (a Spec 7 build on a tester's phone, or an Auto Backup restore of one) reads as ACTIVE, and the first revoke gives FREE with "Get" copy instead of EXPIRED with "Renew". Do has never been released, so real users are not affected; testers upgrading a Spec 7 build are.
- Fix: `everSubscribed = cache.everSubscribed || cache.active || active`.
- Test: `Spec10QaTest.anActiveCacheWrittenBeforeEverSubscribedExistedRevokesToExpiredNotFree` (fails).

### Bug 5 (Low): a failed connection or details query is silent and drops a loaded price
- `PlaySubscriptionBilling.kt:132-136`: a non-OK `onBillingSetupFinished` (BILLING_UNAVAILABLE on a phone with no Play account, say) does nothing, so the paywall shows "Price loading" for ever with Buy disabled and no explanation. `PlaySubscriptionBilling.kt:180-185`: a failed details refresh on resume sets `price = null`, so a price shown a moment ago turns back into "Price loading".
- Fix: keep the last good `PriceInfo`, offer token and details on a failed refresh; on a failed setup or details query set a flag the paywall can show as "Can't reach Google Play right now".

## 4. Play Billing review (release `PlaySubscriptionBilling`)

Checked against Google's docs (Integrate, Subscription lifecycle, `BillingClient` reference) on 27 Nov 2026.
- Product type: `ProductType.SUBS` for both the details query and the purchases query; no INAPP left in source or in compiled release classes. Correct.
- Offer token: chosen by `SubscriptionOffers.choose` and passed with `setOfferToken(token)`. Correct.
- Acknowledgement: only PURCHASED and unacknowledged, never PENDING; renewals need none ("You don't need to acknowledge subscription renewals"). Correct.
- Failed purchases query keeps the cache; a successful one without the product revokes. Correct.
- Partial details query: Billing 8+ `QueryProductDetailsResult` with `unfetchedProductList`; the code uses `productDetailsList` and treats a missing product as nothing to sell. Correct.
- Grace period returned, account hold and paused not returned unless `includeSuspendedSubscriptions` (not set), cancelled returned until expiry: all confirmed in the docs, so the Build notes are right.
- Connection: `enableAutoServiceReconnection()` is set and `onBillingServiceDisconnected` is a no-op, which is what Google recommends with auto reconnection. `connect()` calls `startConnection` again on each resume while the client is not ready; harmless. `enablePendingPurchases(enableOneTimeProducts())` matches the docs.
- Prices: none hard-coded in main or release (QA test `noPriceIsHardCodedInMainOrReleaseSources`, plus the APK search). The fake prices live only in the debug source set.
- Management link format: matches the Build notes and spec.

## 5. Free tier, copy, release hygiene

- Only `Scheduler.add` consults the cap (`FreeTier.blocksAdd`), and only `ACTIVE.liftsCap` is true. FREE, EXPIRED and PENDING are identical for the cap at every count (QA test). Nothing in Spec 10 touches firing, nagging, snooze, Done, edit, backup or export. Info: Spec 9's `Scheduler.restore` (undelete) is not capped, so delete, add, undelete can reach 6 active in FREE or EXPIRED. That is Spec 9 behaviour, and Spec 10 does not make it worse.
- Paywall copy matches section D word for word: title, line, three ticks, intro, "then ... a year. Cancel any time in Google Play.", renewal-only, "Price loading", "Get" or "Renew Do Unlimited", "Keep 5 for free", small print, "You have Do Unlimited." with Manage subscription. On the paywall the renewal price is always beside the intro price (QA test over every state). Australian English ("catalogue", "cancelled"); no em dashes. The Settings row is the exception (bug 1).
- Release hygiene: FakeSubscriptionBilling, DebugReceiver and FAKE_PURCHASE are all absent from release. `checkAlarmManifests` is updated correctly and still asserts that the fake is present in debug.
- Debug fake (info, debug only): `FAKE_PURCHASE --es state pending` writes `everSubscribed=false`, so going pending and then expired cannot model "lapsed, then pending renewal". That is fine for AT-9 and AT-10.

## 6. Notes for AT-11 (real Play, NOT RUN)

- Check the paywall shows Play's intro and renewal prices for a new tester, and renewal only for a tester who has already used the offer.
- Buy, then confirm acknowledgement happened (Play Console order shows acknowledged; no refund after 3 days).
- Cancel: the app should stay ACTIVE until the test period ends, then show EXPIRED with "Renew Do Unlimited" and all reminders still nagging.
- Grace period and account hold: force a declined card on a licence tester; ACTIVE during grace, EXPIRED on hold, ACTIVE again on recovery.
- **Resubscribe outside the app.** Google's lifecycle doc says a resubscribe from the Play Store after expiry is a new purchase that must be acknowledged within three days, normally by a backend. Do has no backend, so it acknowledges only when the user next opens Do. The Manage subscription link can lead an expired user to exactly that flow. Tim should either turn off Resubscribe on the `annual` base plan or accept the risk; AT-11 should test resubscribing in the Play Store and then not opening Do for a day.
- Pause: Google allows pause on annual plans (1 to 3 months). Paused reads as EXPIRED in Do. Consider turning pause off, as the Build notes suggest.
- Watch for "Price loading" sticking after first open (bug 3) and for "Purchase failed" when tapping Renew while Play already holds the subscription (bug 2).
- Open question for Gareth: section F says `everSubscribed` is not backed up, but Android Auto Backup does include `do_entitlement.xml` (as since Spec 7), so it does come back on a new phone. The builder's reading (F means Do's own backup file) seems right; please confirm.

## 7. QA tests added

`app/src/test/java/com/evansappstudio/doapp/billing/Spec10QaTest.kt`: 18 tests, 15 pass, 3 fail on purpose:
- `theSettingsRowNeverShowsTheIntroPriceWithoutTheRenewalPrice` (bug 1)
- `anAlreadyOwnedLaunchResultRequeriesInsteadOfShowingPurchaseFailed` (bug 2)
- `anActiveCacheWrittenBeforeEverSubscribedExistedRevokesToExpiredNotFree` (bug 4)

No production code was changed.
