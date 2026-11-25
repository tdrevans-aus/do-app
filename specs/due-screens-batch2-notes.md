# Due 26.6.1 iPad screenshots, batch 2

Source: Tim's photos of Due 26.6.1 (16468) on iPad, 25 Nov 2026 (AWST). Files are in `/workspace/due-screens/batch2/`. Each `emailN-imageM.png` has a matching 900x1200 `emailN-imageM.jpg`. This note describes only what is visible in those JPGs. It does not invent controls that are off-screen or guess factory defaults where the shot only shows the current value.

Settings pages were scrolled in pieces (email1-image3 to image6, plus the drilled-in pages in email1 and email3). Restore Warnings & Prompts, documented in Due's help, is not visible in this scroll. The Settings sheet in email1-image6 looks like the end (Share Analytics and then empty space), but that is an absence, not proof it was removed.

Batch 1 (`/workspace/due-screens/due-0.jpg` to `due-3.jpg`) is an earlier onboarding / permission walkthrough from the same day and is not catalogued here.

## email1: composer, repeat menu, Settings

**email1-image1.jpg.** Reminder composer over the list, keyboard up. Status bar 6:03 pm, Wed 25 Nov. The list behind shows an OVERDUE section and two tutorial rows: "Swipe left on this reminder to mark it complete" (due 25 Nov at 5:38 pm, 26m ago) and "Swipe further to the left to delete this reminder" (due 25 Nov at 5:43 pm, 21m ago), both with red dates. The tab bar is Reminders (selected), Timers and Logged, plus sidebar, undo, redo, overflow and +. The composer has Close, a zz-style control showing 5, a two-arrow repeat button, a bell, and Save. Title placeholder: "Remind me to...". A red date banner reads "Wed, 25 Nov at 6:03 pm 1 min ago". The 4 by 3 Quick Access Times grid is:

- 9:30 am, 12:00 pm, 6:30 pm, 10:00 pm
- +10 min., +1 hr, +3 hrs, +1 day
- -10 min., -1 hr, -3 hrs, -1 day

Footer icons: trash, share, a check, and "...". Feeds Spec 3 (grid labels; already merged, see follow-ups below), Spec 4 (repeat button placement), the add/edit editor, and later list / Logged / Timers work.

**email1-image2.jpg.** The same composer with the Repeat menu open. Status bar 6:04 pm. The due banner is partly covered; "Wed, 25 Nov at 6:" is visible. Menu heading "Repeat", then in this order: Repeat Off (checked), "Daily 6:04 pm", "Weekdays 6:04 pm", "Wednesdays 6:04 pm", "25th each month at 6:04 pm", "Every 25 Nov at 6:04 pm", "Custom...". No end date or end-after-N row is visible. Feeds Spec 4 (edited from this shot).

**email1-image3.jpg.** Settings, top. Header "Due 26.6.1 (16468) by Lin Junjie". Rows: Upgrade Pass, Become a Supporter. APPEARANCE: Theme Light, App Icon Default, Preview 3 Lines. NOTIFICATIONS: Sounds & Alerts, Badges Overdue only. Feeds paywall / Upgrade Pass, appearance, alert levels, and badge settings.

**email1-image4.jpg.** Settings, continued. Notification Snooze, caption "Specifies how long to snooze a reminder from its notification." AUTO SNOOZE: Repeat At Least 5 Times, Repeat Indefinitely When Possible, caption "Auto Snooze repeatedly alerts you to overdue reminders until they are marked done." DEFAULTS FOR NEW REMINDERS: Due Date None, Auto Snooze Every 5 Minutes, Alert Sound on, caption "Only new reminders will be created with these settings. Existing reminders will not be affected." Section header QUICK ACCESS TIMES at the bottom. Feeds Spec 2 (notification snooze), a later Auto Snooze settings / iOS-budget spec (Repeat At Least / Repeat Indefinitely are not repeat-rule end conditions), Spec 1 / defaults (Auto Snooze every 5 minutes is the selected default here), and the add-editor default due (None). The composer in email1-image1 still shows a filled due of 6:03 pm, so this shot does not by itself prove how "Due Date None" behaves when adding.

**email1-image5.jpg.** Settings, continued. Postpone Behavior Auto, Customize Times. REMINDER EDITOR: Parse Dates on, Date Picker Style Calendar, Interval on Time Picker 5 minutes. GENERAL: Week Starts on Monday, Swipe Options, Sound Effects on, Haptic Feedback on. "Interval on Time Picker" is the time-picker minute step, not a repeat interval (see email3-image6). Feeds Spec 3 postpone (already Auto), Spec 5 (Parse Dates), editor settings, Spec 4 Week Starts on (edited), swipe options, and chrome (sound effects / haptics). Due uses the US spellings "Behavior" and "Customize".

**email1-image6.jpg.** Settings, lower part. Use with Siri. SYSTEM REMINDERS: Import, Auto Import Off, Show in Tab Bar off, caption "Access your iOS Reminders alongside your Due reminders from the tab bar." PRIVACY: Share Analytics on, caption "Usage and diagnostic data, used to fix bugs and prioritize improvements. Never linked to your identity and never sold." Feeds Siri / shortcuts, Apple Reminders import (not a Do v1 Must), and a privacy / analytics decision for Do (Due is local-first in the research; this toggle is additional).

**email1-image7.jpg.** Theme: Ash, Light (checked), Black, Automatic. Feeds appearance.

**email1-image8.jpg.** Sounds & Alerts, upper part. REMINDERS: Default Sound Lasting Impression, Sounds on, Alerts on, Auto Snooze on, Critical Alerts Off, Prominent Alerts Off. TIMERS: Default Sound Deepening Mystery, Sounds on, Alerts on, Critical Alerts Off. The sheet is scrolled and a scrollbar is visible, so more timer rows may sit below (they do: see email3-image10). Feeds alert levels (Spec 6) and timers.

**email1-image9.jpg.** App Icon: Default (checked), Black labelled Feb 2022, Midnight Glass labelled Nov 2025. Feeds appearance / Upgrade Pass (whether the dated icons are locked is not shown).

**email1-image10.jpg.** Preview: 1 Line through 6 Lines, 3 Lines checked. Caption "Choose how many lines of each reminder's title to show." Feeds list appearance.

## email2: notification banner, Timers, overflow, Tools, import, swipe

**email2-image1.jpg.** Mail compose in the background (To: Tim Evans, From: tdrevans@hotmail.com). A compact iPad notification banner at the top: "TIME SENSITIVE", Due icon, title "Due", body "Get a notification", "now". No notification action buttons are visible on this collapsed banner. Status bar 6:13 pm. Feeds Spec 2 (notification appearance; actions are not shown here) and the later Notification Title setting (email3-image10 has "Show App Name", which matches this title).

**email2-image2.jpg.** Timers tab, Search Timers field. Three stopped timers with play buttons: "00:04:00 Coffee steeping", "00:20:00 Power nap" (a zz-style mark and a small extra icon sit beside the duration), "01:00:00 Pick up laundry". Feeds the timers spec. Whether these are factory samples or Tim's own timers is not stated on the screen.

**email2-image3.jpg.** Reminder overflow menu on "Get a notification" (TODAY, 25 Nov at 6:13 pm, in 5m). Items, in order: Mark Done; Postpone by 10 Minutes; Postpone by 1 Hour; Postpone by 1 Day; Auto Snooze (submenu); Standard Alert (checked); Critical Alert; Prominent Alert; Create Event; Duplicate; Share (submenu). The postpone amounts here are 10 minutes, 1 hour and 1 day, which are not the notification snooze options in email3-image2 (+1 hr, +3 hrs, +1 day). Feeds list overflow / postpone, alert levels (Spec 6), Auto Snooze per reminder, calendar (Create Event), duplicate and share.

**email2-image4.jpg.** Left sidebar / more menu. Settings at the top, then Tools: Export, Print, Backup, Sync with the subtitle "Reminders Not Backed Up", then Help. A yellow warning mark is visible on the sidebar button in other shots; this menu gives a concrete backup warning. Feeds backup / export, print, and sync (Due's iCloud sync; Do's no-account backup is the relevant later spec).

**email2-image5.jpg.** Sheet titled "iOS Reminders". One row: "Tasks" with a count of 1. Reached from Settings > Import (see email1-image6). Feeds Apple Reminders import, which is iOS-specific and not a Do v1 Must.

**email2-image6.jpg.** Swipe Options. SWIPE TO SEARCH: Sensitivity Medium, caption "Higher sensitivity triggers more easily." SWIPE TO DELETE: Swipe to Delete on, Sensitivity Low, same caption. The list tutorials in email1-image1 already describe swipe left to complete and further left to delete. Feeds list swipe behaviour.

## email3: snooze, Auto Snooze, picker, Quick Access Times, week start, date picker, notification appearance

**email3-image1.jpg.** Badges: Upcoming + Overdue, Today + Overdue, Overdue only (checked), None. Feeds badge / icon-count settings.

**email3-image2.jpg.** Notification Snooze. Option 1 +1 hr, Option 2 +3 hrs, Option 3 +1 day. Caption "Directly snooze a reminder from its notification with these options." Custom Snooze is on, caption "Use Quick Access Times to snooze reminders." A wheel is open on Option 1: At Time / From Now (From Now selected), units minute, hour (selected), day, and week (visible below day), value 1. Feeds Spec 2 (option values match the already-shipped defaults; the visible labels are +1 hr / +3 hrs, not +1 hour / +3 hours) and the still-deferred Custom Snooze / notification Quick Times grid. The From Now wheel includes week, which Spec 2's snooze settings should be checked against as follow-up.

**email3-image3.jpg.** Repeat At Least: Once, Twice, 3 Times, 5 Times (checked), 10 Times. Caption "Auto snooze can only repeat indefinitely when the reminder becomes overdue. Choose the minimum number of times you'd like to be alerted." Feeds a later Auto Snooze / platform-budget spec. This is not an end-after-N control on a repeating reminder (see Spec 4 out of scope).

**email3-image4.jpg.** Repeat Indefinitely: Never, When Possible (checked). Caption "Attempt to repeat overdue reminders indefinitely. This can happen whenever you launch Due or act on notifications from Due. You can also set up a Shortcuts automation to refresh notifications throughout the day, giving overdue reminders more opportunities to repeat indefinitely." A Learn More... button. Same feed as email3-image3; iOS notification-budget behaviour, not the Spec 4 repeat rule.

**email3-image5.jpg.** Auto Snooze interval for new reminders: None, Every Minute, Every 5 Minutes (checked), Every 10 Minutes, Every 15 Minutes, Every 30 Minutes, Every Hour. Caption "Repeatedly alerts you to overdue reminders until they are marked done." Feeds Spec 1 (the allowed intervals already match; this shot also shows the selected default is Every 5 Minutes).

**email3-image6.jpg.** Interval on Time Picker: 1 minute, 5 minutes (checked), 10 minutes, 15 minutes, 30 minutes. Feeds the editor time picker only. It is not a repeat-rule interval and did not change Spec 4.

**email3-image7.jpg.** Quick Access Times editor. The same 12-button grid as email1-image1, with 9:30 am highlighted. At Time is selected (From Now is not). A time wheel shows 9, 30, am. Feeds Spec 3 customisation (labels as in the composer; At Time / From Now wheel).

**email3-image8.jpg.** Week Starts on: Monday (checked), Saturday, Sunday. Feeds Spec 4 (edited from this shot) and any calendar / date-picker week display.

**email3-image9.jpg.** Date Picker Style: Calendar (checked), Wheels. Feeds the editor date picker.

**email3-image10.jpg.** Sounds & Alerts, lower part, completing email1-image8. TIMERS also has Prominent Alerts Off. NOTIFICATION APPEARANCE: Notification Title Show App Name. The sheet may continue below; nothing further is visible. Feeds alert levels, timers, and notification title / appearance (matches the "Due" title on email2-image1).

## Spec 3 follow-ups (do not edit Spec 3; it is merged)

Factory times match (`email1-image1.jpg`, `email3-image7.jpg`): 9:30 am, 12:00 pm, 6:30 pm, 10:00 pm, and the same +/- amounts. Label wording does not:

- Due writes `+10 min.` and `-10 min.` with a full stop after min. Spec 3 uses `+10 min` / `-10 min`.
- Due writes `+3 hrs` and `-3 hrs` (plural hrs). Spec 3 uses `+3 hr` / `-3 hr`, and says to pluralise only the count in forms like `+2 hr`.
- `+1 hr`, `-1 hr`, `+1 day` and `-1 day` match.

No separate +/- stepper appears outside the grid. The red banner "Wed, 25 Nov at 6:03 pm 1 min ago" fits Spec 3's overdue banner idea; the exact date wording is tighter than the spec's examples. Settings also show new-reminder Due Date as None (`email1-image4.jpg`); Spec 3 still starts a new reminder at now plus 5 minutes, which was a Gareth call when Due's default was unknown.

## Spec 4

Edited only where these shots show a real difference from the written spec: the Repeat menu labels and order (`email1-image2.jpg`), the Week Starts on setting (`email1-image5.jpg`, `email3-image8.jpg`), and a clarification that Repeat At Least / Repeat Indefinitely are Auto Snooze controls, not repeat-rule end options. The Custom repeat screen was not photographed. No end date or end-after-N control appears on the repeat menu.

## What this adds to, or clashes with, `DO-RESEARCH.md`

Additions, not clashes, unless noted:

- Auto Snooze default for new reminders is shown as Every 5 Minutes (`email1-image4.jpg`, `email3-image5.jpg`). The research said Due's factory default was not documented. This device has that value selected; it is not independently proven to be factory-fresh.
- The allowed Auto Snooze intervals (1, 5, 10, 15, 30, 60 minutes, or None) match the research and Spec 1.
- Repeat At Least (Once, Twice, 3, 5, 10) and Repeat Indefinitely (Never / When Possible), plus the "when the reminder becomes overdue" copy, give exact UI for the iOS notification-budget behaviour the help already described.
- New-reminder Due Date is shown as None. The research did not give Due's default draft due.
- A Logged tab is a first-class top-level section, which supports the "log of completed reminders" Should.
- List overflow postpone is 10 minutes, 1 hour and 1 day, which is a different triple from notification snooze (+1 hr, +3 hrs, +1 day).
- Notification snooze option labels in Settings are +1 hr and +3 hrs (Spec 2 shipped +1 hour and +3 hours).
- Custom Snooze is a toggle, on in this shot, and the copy says it uses Quick Access Times to snooze.
- Week Starts on offers Monday, Saturday and Sunday (this device: Monday).
- Tools include Export, Print, Backup and Sync, with "Reminders Not Backed Up" visible. Share Analytics is on, with a "never linked to your identity and never sold" caption.
- Notification Title can be "Show App Name". Themes are Ash, Light, Black and Automatic. Reminder preview is 1 to 6 title lines. Badges can be Upcoming + Overdue, Today + Overdue, Overdue only, or None. Default reminder sound on this device is Lasting Impression; default timer sound is Deepening Mystery.
- Nothing in this batch contradicts Due's documented lack of every-N-minutes or every-N-hours repeats: the Repeat menu has no such preset, and the Custom screen was not shown.

## Top findings for later specs

1. Auto Snooze settings are richer than "interval plus a default": Repeat At Least and Repeat Indefinitely are first-class Settings, with copy that ties indefinite nags to becoming overdue and to launching the app or handling notifications (email3-image3, email3-image4). Android will not need the iOS budget workaround, but the visible defaults (5 times, When Possible, every 5 minutes) are still useful.
2. The list overflow is a full reminder menu: Mark Done, three postpones (10 minutes / 1 hour / 1 day), Auto Snooze, Standard / Critical / Prominent, Create Event, Duplicate and Share (email2-image3). Alert levels and per-reminder Auto Snooze already have a visible home.
3. Custom Snooze is a toggle that puts the Quick Access Times grid on the notification (email3-image2). That is the deferred notification-grid work from Specs 2 and 3. Settings labels there are +1 hr / +3 hrs / +1 day, and the From Now wheel includes week.
4. Backup, export, print and sync already live under Tools, and this device is shown as "Reminders Not Backed Up" (email2-image4). That is the later no-account backup spec, plus a health-style warning in the sidebar.
5. Timers are a top-level tab with named reusable durations and their own sounds, including Prominent Alerts (email2-image2, email1-image8, email3-image10). Logged is likewise a top-level tab (visible throughout, not opened in this batch).
