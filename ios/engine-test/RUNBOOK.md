# Do Engine Test: runbook for Tim

This gets the test app onto your iPhone and walks through the test matrix. Allow about an hour for set-up and the first few rows, then spread the rest over a few days.

**Two rules for the whole test:** never set a passcode on the phone for this, and never change the phone's clock.

## What you need

- A Mac with Xcode 26 (from the Mac App Store).
- Your iPhone on iOS 26 or later, and its cable.
- Your Apple developer account, signed in to Xcode (Xcode > Settings > Accounts).
- Homebrew on the Mac (https://brew.sh).

## 1. Build it to your phone

1. Open Terminal and run:
   ```
   git clone https://github.com/tdrevans-aus/do-app.git
   cd do-app
   git checkout ios/engine-test
   cd ios/engine-test
   brew install xcodegen
   xcodegen generate
   open DoEngineTest.xcodeproj
   ```
2. In Xcode, click the blue **DoEngineTest** project at the top of the left sidebar, then the **DoEngineTest** target, then **Signing & Capabilities**.
3. Tick **Automatically manage signing** and pick your team under **Team**.
4. If Xcode says the Time Sensitive Notifications capability is missing, click **+ Capability** and add **Time Sensitive Notifications**. Do not add Critical Alerts.
5. If the bundle id `com.evansappstudio.doenginetest` is taken or refused, change it to something like `com.evansappstudio.doenginetest.tim`.
6. Plug in the phone and unlock it. Trust the Mac if asked. If the phone asks for Developer Mode, turn it on (Settings > Privacy & Security > Developer Mode) and let it restart.
7. Choose your iPhone at the top of the Xcode window and press **Run** (the play button).
8. If the phone says the developer is not trusted: Settings > General > VPN & Device Management, tap your account, then **Trust**.

If you run `xcodegen generate` again later (after a `git pull`), repeat steps 2 to 4.

## 2. First launch

1. Open **Do Engine Test**. Allow notifications when asked.
2. Scroll to **Settings** and tap **Ask for AlarmKit permission**. Allow it. (It also asks the first time you add a B reminder.)
3. In iPhone Settings > Notifications > Do Engine Test: make sure **Time Sensitive Notifications** is on, and banners and sounds are on.
4. In iPhone Settings > General > Background App Refresh: make sure it is on for Do Engine Test.
5. In Settings in the app, check the **Notifications** line says Allowed with Time Sensitive on, and **AlarmKit** says Allowed.

## 3. How to run a row

For each row below, do it three times: once with mechanism **A**, once **A+**, once **B**.

1. Tap **Add** (the + at the top).
2. Title: something you will recognise, such as "Row 4 A+".
3. Due in: **60 seconds** (use 120 or 300 if the row needs time to set up, such as locking or restarting).
4. Nag: **Every 1 minute**. Mechanism: as needed. Nags to queue ahead: **10** (leave it unless the row says otherwise).
5. Tap **Add**, then set up the condition for the row.
6. Note the time of the first alert and the next two or three nags (a stopwatch or a second device helps).
7. Tap **Done** on the alert (or on the row in the app). Wait two more minutes and note whether anything else fires.

The app logs what it can see. It cannot always see deliveries while it is closed, so your own notes matter, especially for B.

## 4. The matrix

| # | Condition | What to do | Pass when |
| --- | --- | --- | --- |
| 1 | App open | Leave the app on screen. | First alert within 5 s of due; nags each minute; Done stops them. |
| 2 | App in background | After adding, go to the Home Screen. | Same as 1. |
| 3 | App swiped away | After adding, open the app switcher and swipe the app away. | Same as 1. |
| 4 | Phone locked | After adding, press the side button to lock. Tap Done on the lock screen. | Same, and Done works from the lock screen without opening the app. |
| 5 | Silent switch on | Turn on silent mode (switch or Action button), then add. | Note whether it makes a sound or vibrates. |
| 6 | Focus on | Turn on Do Not Disturb in Control Centre, then add. | Note whether it shows and whether it sounds. |
| 7 | Restart | Add with due 300 s, restart the phone straight away, unlock once, do not open the app. | Note whether it still fires, and whether nags continue. |
| 8 | Low Power Mode | Turn on Low Power Mode, then add. | Same as 1. |
| 9 | Load | Add ten reminders with nag every 1 minute (use A, then repeat with B) and leave them for 15 minutes with the app closed. Open the app at the end. | Note gaps; nags keep coming (the app tops up the queue when opened and in background refresh). |
| 10 | Snooze | When the alert comes, tap **Snooze 10 min**. | Next alert 10 minutes later, then nags each minute again. |

For row 9, note the queue limits: with a notification budget of 60 and ten reminders, each A reminder gets about six queued nags, so expect gaps unless the app is opened or background refresh runs. That is what we want to measure.

## 5. Export the log

1. In the app, tap **Event log**, then **Export** (top right).
2. Send it to yourself (Mail, Messages or AirDrop) and pass it on with your results table.
3. After exporting, you can tap **Clear log** to start fresh. Swipe a reminder left to delete it, or use **Clear finished reminders** in Settings.

The log has, for each event: when the app saw it, the planned time, how late it was in seconds, the mechanism, and the event. "Delivered (read from Notification Centre)" lines use iOS's own delivery time, so they are accurate even for background deliveries, but only for notifications still in Notification Centre when the app was next opened.

## 6. Results

Phone model: ______  iOS version: ______  Date: ______

| # | Condition | A | A+ | B | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | App open | | | | |
| 2 | Background | | | | |
| 3 | Swiped away | | | | |
| 4 | Locked (Done from lock screen?) | | | | |
| 5 | Silent switch (sound? vibrate?) | | | | |
| 6 | Do Not Disturb (shows? sounds?) | | | | |
| 7 | Restart before due | | | | |
| 8 | Low Power Mode | | | | |
| 9 | Ten reminders, 15 min (gaps?) | | | | |
| 10 | Snooze 10 min | | | | |

In each cell, write pass or fail, how late the first alert was, and anything odd.

Also note:

- Did any AlarmKit schedule fail (look for "AlarmKit schedule failed" in the log)? If so, how many alarms were queued at the time? This tells us whether AlarmKit has a cap.
- On the AlarmKit alert, which button was Done and which was Snooze, and did both work from the lock screen?
- Anything in the log that does not match what you saw.

## 7. Critical Alerts (optional, later)

Leave the **Use critical alerts for A+** switch off. It only does anything once Apple grants the Critical Alerts entitlement and it is added to the app. If Apple grants it, tell Claude and the app will be updated to include it.
