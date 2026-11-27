# Do: weekend build status

Last updated: Fri 27 Nov 2026, about 11 pm (AWST). Updated by Claude as work lands.

## Running now
- **Spec 9 QA.** An independent Claude test agent is reviewing Spec 9 and adding tests; report will be `specs/09-qa-report.md` on `do/spec-9-data-foundation`.
- **Spec 10, subscription billing.** Being built on `do/spec-10-subscription-billing` (stacked on Spec 9).
- **iPhone test app: widget extension** being added so AlarmKit alarms have their lock-screen view.


## Done
- **Spec 9, data foundation: built.** Branch `do/spec-9-data-foundation` at `bf9d41e`. 146 unit tests (125 existing, 21 new), 0 failures; lint clean on changed files; both exact-alarm builds green. Command layer, schema 5, soft delete with 30-day purge, event log (200 per reminder), backup schema 2, core-purity check. Phone ATs 18 to 21 wait for Monday.
- **iPhone engine test app: built, compiles, 14 unit tests pass** on GitHub's Mac runners (Xcode 26.6, iOS 26.5 simulator), branch `ios/engine-test` at `2db43bc`. Not yet run on a real iPhone. Runbook: `ios/engine-test/RUNBOOK.md`. Things only a real phone can show: whether AlarmKit alarms appear without a widget extension, whether the Done and Snooze buttons work with the app closed, and whether AlarmKit has a cap. In AlarmKit mode the buttons are Done and Snooze 10 min (AlarmKit allows two).

## Next, in order
1. Fix anything Spec 9 QA finds.
2. Spec 11, Stuff You Should Do: build (draft: `specs-drafts/11-stuff-you-should-do.md`).
3. Spec 12, capture surfaces: build (draft: `specs-drafts/12-capture-surfaces.md`).

Specs 10 to 12 are Claude drafts. They are built on branches only, not merged, so Gareth can review and change them before anything goes to `main`.

## Waiting on Tim (from Monday night)
- PR #8: phone rerun (AT-20 to AT-28) and hands-on AT-05, AT-10, AT-12, then your OK to merge.
- iPhone engine test: build to your iPhone from your Mac (runbook will be at `ios/engine-test/RUNBOOK.md` on branch `ios/engine-test`).
- Apply for Apple's Critical Alerts entitlement and Google's AppFunctions early access.
- Name a hosting account for the cloud agent API (Spec 13 can't be specced until then).

## Nothing merged, nothing published
No merges to `main`, no PRs opened, nothing sent to Play or the App Store.
