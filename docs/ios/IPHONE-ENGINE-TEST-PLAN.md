# iPhone engine test: plan

**Date:** 27 Nov 2026 (AWST). **Approved by Tim** the same day (strategy section 9A).
**Owner:** Claude (build), Tim (device runs on his Mac and iPhone, from Monday 30 Nov 2026 at the earliest).
**Time box:** about two weeks from the first device run.
**What it is:** a throwaway iPhone app that proves whether Do's nag-until-done behaviour can work on iOS, and which iOS mechanism each Do alert level should use. It is not product code and will not ship.

## 1. The questions it must answer

1. **Does it fire on time?** A reminder set for a minute fires within a few seconds of that minute, with the app in the foreground, in the background, swiped away, and with the phone locked.
2. **Can it nag until Done?** After the first alert, it alerts again every 1 or 5 minutes until the user taps Done, and stops straight away when they do, including from the lock screen.
3. **Does it survive?** The app being swiped away, the phone restarting, Low Power Mode, and an app update.
4. **Does it get through?** With the ring/silent switch on silent, and with a Focus (Do Not Disturb, Sleep) on.
5. **What are the limits?** iOS allows an app only 64 pending local notifications. How does a rolling top-up hold up with several reminders nagging every minute? Does AlarmKit have its own cap on scheduled alarms?
6. **How should Do's levels map to iOS?** Normal, Loud and Alarm (the names from the 27 Nov design pass).
7. **Is the Critical Alerts entitlement needed**, and is it worth applying for?

## 2. The mechanisms compared

The app tests three ways of firing a nag, selectable per test reminder:

| Mechanism | How it works | Expected strengths | Expected weaknesses |
| --- | --- | --- | --- |
| **A. Local notifications** (`UNUserNotificationCenter`) | The first fire plus the next nags are scheduled as separate notification requests. Done removes the rest. A rolling top-up keeps the soonest requests within the 64 limit. | Works on all supported iOS versions; Done and Snooze as notification actions; no special permission beyond notifications. | Silent switch and Focus silence it (unless Time Sensitive is allowed by the user, which lets it through Focus but not the silent switch); 64-request cap. |
| **A+. Time Sensitive** | Same as A, with `interruptionLevel = .timeSensitive`. | Gets through Focus if the user allows it for Do. Needs the Time Sensitive Notifications capability, which any developer can add. | Still respects the silent switch. |
| **B. AlarmKit** (iOS 26 and later) | Each fire (the first, then each nag) is a scheduled AlarmKit alarm with Stop and a secondary "Done" or "Snooze" button. | System alarm UI on the lock screen; designed to break through silent mode and Focus; survives the app being closed. | iOS 26 only; needs the user's AlarmKit permission; per-app limits unknown; an alarm per nag may be heavy-handed for Normal reminders. |

Critical Alerts (`.critical`) is wired behind a switch but can only be tested if Apple grants the entitlement. Recommendation: **Tim applies for it now**, from his developer account (Apple's Critical Alerts entitlement request form), because approval is slow and not guaranteed. Expect a no for a general reminder app; AlarmKit may make it unnecessary.

## 3. The test app

- **Name:** "Do Engine Test" (bundle id `com.evansappstudio.doenginetest`), iOS 26 deployment target (AlarmKit), SwiftUI, no third-party code.
- **Screens:** one list. Add a test reminder with: title, due in N seconds (30, 60, 120, 300), nag interval (1 or 5 minutes, or off), mechanism (A, A+, B), and how many nags to queue ahead. Each row shows planned times and what the app has seen.
- **Actions:** Done and Snooze 10 min, as notification actions (A, A+) and as AlarmKit buttons (B). Done cancels every queued nag for that reminder.
- **Top-up:** on launch, on every notification action, and on a background app refresh task, re-plan every active reminder and keep the soonest requests within a budget (default 60 of the 64, so there is room for one-off tests).
- **Log:** an in-app event log (planned time, when the app saw it, which mechanism, which action), exportable as text through the share sheet so Tim can send it back. Background delivery times can't always be observed from inside the app; the log records what can be seen, and the runbook asks Tim to note the rest.
- **Shared logic:** the nag maths (`due + k * interval`, no drift; catch up after a miss) is re-implemented in Swift in one small file, mirroring Do's Kotlin `NagSchedule`. Sharing real Kotlin code (Kotlin Multiplatform) is out of scope for this test.
- **Build:** an XcodeGen `project.yml` (no hand-edited `.xcodeproj`), compiled for the iOS Simulator on GitHub's macOS runners by `.github/workflows/ios-engine-test.yml` on pushes to branch `ios/engine-test`. Simulator builds need no signing. Tim builds to his own iPhone from Xcode on his Mac with his team selected.

## 4. Test matrix (Tim, on the iPhone)

For each mechanism (A, A+, B), with a 1-minute nag:

| # | Condition | Pass when |
| --- | --- | --- |
| 1 | App open | First fire within 5 s of due; nags each minute; Done stops them |
| 2 | App in background | Same |
| 3 | App swiped away | Same |
| 4 | Phone locked | Same, and Done works from the lock screen |
| 5 | Silent switch on | Note whether it makes sound or vibrates |
| 6 | Focus (Do Not Disturb) on | Note whether it shows and sounds |
| 7 | Restart the phone before due, don't open the app | Note whether it still fires |
| 8 | Low Power Mode | Same as 1 |
| 9 | Ten reminders nagging every minute for 15 minutes | Note gaps; check the top-up keeps nags coming |
| 10 | Snooze 10 min | Next alert 10 minutes later, then nagging resumes |

Results go into a copy of the table with what was seen, the iOS version, and the phone model. Never set a passcode for this or change the phone's clock.

## 5. What the result decides

- Which mechanism each Do level uses on iPhone. A likely outcome: Normal uses A (or A+), Loud uses A+ or B, Alarm uses B.
- Whether Do on iPhone needs iOS 26 as its minimum (if AlarmKit is essential).
- Whether to keep pursuing Critical Alerts.
- How the iPhone app's engine is built (native Swift around a shared Kotlin core, or not), written up as a short report with a recommendation.

## 6. Steps and status

1. Plan (this document). Done 27 Nov 2026.
2. Build the test app and the CI compile. In progress over the weekend of 28 to 29 Nov 2026.
3. Runbook for Tim: how to build it to his iPhone from Xcode, and how to run the matrix. Written with step 2.
4. Device runs: Tim, from Monday 30 Nov 2026.
5. Report and recommendation: Claude, from Tim's results.
