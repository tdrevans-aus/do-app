# Spec 7 manual checklist

The phone runner is `qa/run-spec-07.ps1`. It does not replace `run-spec-05.ps1` or `run-spec-06.ps1`. It never sets a PIN or a lock screen, never starts Play billing, and never makes a purchase.

Human rows:

- **AT-04.** Lock the fake (`FAKE_PURCHASE --es state locked`). Settings, at the top, shows Unlock Do and Price unavailable on a sideloaded debug build. Saving a sixth active reminder opens the unlock screen. Buy is visible and stays disabled while the price is unavailable. Restore purchases is visible. The pitch is: "Unlock Do for a one-time purchase. No ads, no account, and no subscription." No billing sheet appears.
- **AT-10 badge.** `DUMP_HEALTH` is scripted. On the phone, the yellow menu mark is on while backup is a Warning, unless another Warning is already the reason. Do not fail the product only because battery optimisation is already a Warning. After a successful Export, the Backup row is OK and names the last success time. Fix on that row opens the Backup screen.
- **AT-12.** Export uses the system create-document picker, suggested name `do-backup-YYYYMMDD-HHmm.json`. Choose backup folder uses the tree picker. Restore from file uses the open-document picker (`application/json` and `*/*`), shows a preview, and offers Replace all, Merge, and a per-reminder selection. Cancel in a picker leaves data unchanged. Replace all asks before it deletes, and the question names how many current reminders will be deleted.
- **AT-13.** `checkAlarmManifests` on the release output: no debug receiver, no `FAKE_PURCHASE`, no `FakeUnlockBilling`, no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Run it for the default USE mode and for `-PexactAlarmMode=SCHEDULE`.

Debug extras the runner uses, beyond the named actions:

- `CONFIG_BACKUP_FOLDER --es clearSuccess true` clears the last success time without deleting a folder when `--es path` is omitted. An empty `--es path` clears the folder.
- `AUTO_BACKUP` writes now when the folder is set and the last success is empty or the last automatic backup is at least 24 hours old. Otherwise it logs `DoAlarm backup auto skipped`.
- `SET_TITLE --el id --es title` changes a title and keeps the uuid. The runner needs this because no older hook edits a title in place.
