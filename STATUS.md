# Do: weekend build status

Last updated: Fri 27 Nov 2026, late evening (AWST). Updated by Claude as work lands.

## Running now
- **Spec 9, data foundation.** Being built by a Claude build agent on branch `do/spec-9-data-foundation`. Spec: `specs/09-data-foundation.md` on that branch.


## Done
- **iPhone engine test app: built, compiles, 14 unit tests pass** on GitHub's Mac runners (Xcode 26.6, iOS 26.5 simulator), branch `ios/engine-test` at `2db43bc`. Not yet run on a real iPhone. Runbook: `ios/engine-test/RUNBOOK.md`. Things only a real phone can show: whether AlarmKit alarms appear without a widget extension, whether the Done and Snooze buttons work with the app closed, and whether AlarmKit has a cap. In AlarmKit mode the buttons are Done and Snooze 10 min (AlarmKit allows two).

## Next, in order
1. Spec 9 tested by a separate Claude test agent (host tests, code review against the spec).
2. Spec 10, subscription billing: build on top of Spec 9 (draft: `specs-drafts/10-subscription-billing.md`).
3. Spec 11, Stuff You Should Do: build (draft: `specs-drafts/11-stuff-you-should-do.md`).
4. Spec 12, capture surfaces: build (draft: `specs-drafts/12-capture-surfaces.md`).

Specs 10 to 12 are Claude drafts. They are built on branches only, not merged, so Gareth can review and change them before anything goes to `main`.

## Waiting on Tim (from Monday night)
- PR #8: phone rerun (AT-20 to AT-28) and hands-on AT-05, AT-10, AT-12, then your OK to merge.
- iPhone engine test: build to your iPhone from your Mac (runbook will be at `ios/engine-test/RUNBOOK.md` on branch `ios/engine-test`).
- Apply for Apple's Critical Alerts entitlement and Google's AppFunctions early access.
- Name a hosting account for the cloud agent API (Spec 13 can't be specced until then).

## Nothing merged, nothing published
No merges to `main`, no PRs opened, nothing sent to Play or the App Store.
