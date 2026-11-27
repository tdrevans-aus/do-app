# Do: weekend build status

Last updated: Fri 27 Nov 2026, about 4:30 pm (AWST). Updated by Claude as work lands.

## Running now
A short pause for a usage limit at about 4 pm AWST Friday; resumed straight after. Running:
- **Spec 9 fixes.** QA (report `specs/09-qa-report.md`, commit `0ac932f`) found three bugs: a restored overdue reminder's first alert treated as a nag (and held under Do Not Disturb), merge import lowering a row's version, and an id cut that can split an emoji. Plus smaller hardening. The builder is fixing them now; the branch is deliberately red (4 QA tests fail) until then.
- **Spec 10, subscription billing**: being built on `do/spec-10-subscription-billing` (had just started).


## Done
- **Spec 9, data foundation: built.** Branch `do/spec-9-data-foundation` at `bf9d41e`. 146 unit tests (125 existing, 21 new), 0 failures; lint clean on changed files; both exact-alarm builds green. Command layer, schema 5, soft delete with 30-day purge, event log (200 per reminder), backup schema 2, core-purity check. Phone ATs 18 to 21 wait for Monday.
- **iPhone engine test app: built, compiles, 14 unit tests pass, with an AlarmKit Live Activity widget extension** (`3c5f037`, run 45961826082). You sign two targets in Xcode: the app and DoEngineTestWidget.
- Earlier build: on GitHub's Mac runners (Xcode 26.6, iOS 26.5 simulator), branch `ios/engine-test` at `2db43bc`. Not yet run on a real iPhone. Runbook: `ios/engine-test/RUNBOOK.md`. Things only a real phone can show: whether AlarmKit alarms appear without a widget extension, whether the Done and Snooze buttons work with the app closed, and whether AlarmKit has a cap. In AlarmKit mode the buttons are Done and Snooze 10 min (AlarmKit allows two).

## Next, in order
1. Rebase Spec 10 onto the fixed Spec 9.
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
