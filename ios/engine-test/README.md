# Do Engine Test (iPhone)

A throwaway iPhone app that tests whether Do's nag-until-done reminders can work on iOS, and compares three ways of firing them:

- **A**: local notifications (`UNUserNotificationCenter`), one request per fire, with Done and Snooze 10 min actions.
- **A+**: the same, with the Time Sensitive interruption level.
- **B**: AlarmKit (iOS 26), one fixed-date alarm per fire, with Done and Snooze 10 min buttons.

It is not product code and will not ship. The plan is in the docs repo at `docs/ios/IPHONE-ENGINE-TEST-PLAN.md`; how to run it on a phone is in [RUNBOOK.md](RUNBOOK.md).

## Layout

- `project.yml`: XcodeGen spec. Run `xcodegen generate` to make `DoEngineTest.xcodeproj` (not committed).
- `Sources/Engine/NagPlan.swift`: the nag maths, mirroring Do's Kotlin `NagSchedule` (fires at due + k * interval, no drift, catch up after a miss), plus the queue-ahead and budget logic.
- `Sources/Engine/Engine.swift`: re-planning, the rolling top-up within the notification budget, Done and Snooze, the event log export.
- `Sources/Engine/AlarmKitEngine.swift`, `AlarmIntents.swift`: mechanism B.
- `Widget/`: a small widget extension with the Live Activity views (lock screen and Dynamic Island) for the AlarmKit alarms. `Shared/DoAlarmMetadata.swift` is compiled into both the app and the extension.
- `Sources/Engine/Store.swift`: reminders, settings and log in one JSON file in Application Support.
- `Sources/UI/`: the list, Add form, Settings and Event log screens.
- `Tests/NagPlanTests.swift`: unit tests for the nag maths.

## CI

`.github/workflows/ios-engine-test.yml` builds for the iOS Simulator and runs the unit tests on GitHub's macOS runners on every push to branch `ios/engine-test`. Simulator builds need no signing.

## Critical Alerts

The `.critical` path is behind a Settings switch that is off by default. The entitlement is deliberately not in `DoEngineTest.entitlements`, because signing fails until Apple grants it.
