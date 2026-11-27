# Do: weekend build status

Last updated: Fri 27 Nov 2026, about 5:30 pm (AWST). Updated by Claude as work lands.

## Running now
A short pause for a usage limit at about 4 pm AWST Friday; resumed straight after. Running:
- **Spec 11 QA.** Independent review of Stuff You Should Do running.
- **Spec 12, capture surfaces.** Being built on `do/spec-12-capture-surfaces` (on the fixed Spec 9).


## Done
- **Spec 11, Stuff You Should Do: built.** Branch `do/spec-11-stuff-you-should-do` at `43e2d9b`. 209 unit tests (43 new), 0 failures; both builds green. 58 catalogue items bundled; region from the time zone; first run steps 3 and 4; Catalogue tab; Today card; related items while typing. **Not yet seen on a screen** (no phone or emulator here). Content for you and Gareth to review: the top-15 priorities, the two identical smoke alarm titles (home and renter), only NSW has a state rule so far, and some keywords may over-match while typing. Uses the system font for now (Space Grotesk and Inter would add a dependency).
- **Spec 10, subscription billing: built, reviewed and fixed.** Now at `b1585b7`, with the fixed Spec 9 merged in: 193 unit tests, 0 failures; both builds and the unsigned release build green. QA found and the builder fixed: the Settings row showing the intro price without the renewal price; "already owned" showing Purchase failed; Play callbacks not on the main thread; an old cache revoking to Free instead of Expired; and no message when Google Play can't be reached. Earlier: Branch `do/spec-10-subscription-billing` at `077ca8c`. 154 unit tests, 0 failures; both builds green; the release Play code compiles. Do Unlimited (`do_unlimited`, base plan `annual`, offer `first-year`), states Free / Active / Pending / Expired, paywall copy from the design with prices from Play, debug fake at A$3.99 and A$9.99. **For you in Play Console later (your go-ahead only):** create `do_unlimited` with base plan `annual` (1 year, A$9.99) and offer `first-year` (new customers, 1 year at A$3.99); consider turning off subscription pause; set up a licence tester and an internal testing track.
- **Spec 9, data foundation: built, reviewed and fixed.** Now at `3235db6`: 166 unit tests, 0 failures (including 19 QA tests). QA found and the builder fixed: a restored overdue reminder's first alert being treated as a nag; merge import lowering a row's version; an id cut that could split an emoji; plus hardening (a purge failure can no longer stop alarms re-arming). Two open questions for Gareth in the spec: should restoring a deleted reminder respect the free limit of 5, and what if an event write fails after an alarm is cancelled. Earlier: Branch `do/spec-9-data-foundation` at `bf9d41e`. 146 unit tests (125 existing, 21 new), 0 failures; lint clean on changed files; both exact-alarm builds green. Command layer, schema 5, soft delete with 30-day purge, event log (200 per reminder), backup schema 2, core-purity check. Phone ATs 18 to 21 wait for Monday.
- **iPhone engine test app: built, compiles, 14 unit tests pass, with an AlarmKit Live Activity widget extension** (`3c5f037`, run 45961826082). You sign two targets in Xcode: the app and DoEngineTestWidget.
- Earlier build: on GitHub's Mac runners (Xcode 26.6, iOS 26.5 simulator), branch `ios/engine-test` at `2db43bc`. Not yet run on a real iPhone. Runbook: `ios/engine-test/RUNBOOK.md`. Things only a real phone can show: whether AlarmKit alarms appear without a widget extension, whether the Done and Snooze buttons work with the app closed, and whether AlarmKit has a cap. In AlarmKit mode the buttons are Done and Snooze 10 min (AlarmKit allows two).

## Next, in order
1. Spec 11 QA, Stuff You Should Do: build (draft: `specs-drafts/11-stuff-you-should-do.md`).
1. Spec 12 QA (draft: `specs-drafts/12-capture-surfaces.md`).

Specs 10 to 12 are Claude drafts. They are built on branches only, not merged, so Gareth can review and change them before anything goes to `main`.

## Waiting on Tim (from Monday night)
- PR #8: phone rerun (AT-20 to AT-28) and hands-on AT-05, AT-10, AT-12, then your OK to merge.
- iPhone engine test: build to your iPhone from your Mac (runbook will be at `ios/engine-test/RUNBOOK.md` on branch `ios/engine-test`).
- Apply for Apple's Critical Alerts entitlement and Google's AppFunctions early access.
- For Play Console later: consider turning off Resubscribe and Pause on the `annual` base plan (Do has no server, so a resubscribe from the Play Store is only acknowledged when Do is next opened, and a paused plan reads as ended).
- Name a hosting account for the cloud agent API (Spec 13 can't be specced until then).

## Nothing merged, nothing published
No merges to `main`, no PRs opened, nothing sent to Play or the App Store.
