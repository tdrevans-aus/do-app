# Spec 13 QA report: Do Cloud v1 (server and Android client)

QA and security review by a Claude test agent, 28 Nov 2026, run window about 13:30 to 15:30 AWST, on worktree `do-app-cloudclient`, branch `do/spec-13-android-client`, reviewed at head `50e320e`. Emulator only (demo project `demo-do-cloud`); no Firebase account, no deploy. Production code was not changed: only tests under `cloud/functions/test/qa-*.test.ts`, `app/src/test/java/com/evansappstudio/doapp/cloud/Spec13QaTest.kt` and this report were added.

## Verdict

**Not ready to deploy as is; close.** The design is sound and the build is honest: the phone really is the authority, assistants really are confined to their own reminders on both sides, the hold and the cap cannot be bypassed from the cloud, tokens and codes are hashed, PKCE S256 is mandatory, redirect URIs match exactly, Firestore rules hold, and cloud failures never reach alarms. Every acceptance test that can run here is real, not trivial.

But there is one **High** that will very likely stop pairing working at all in a real browser (the authorise page's CSP blocks its own redirect back to the assistant), and three **Medium** issues (purchase token sharing, an unbounded metadata download, and no rate limiting on the unauthenticated OAuth endpoints, which makes pairing-code guessing a matter of volume). All four are small fixes. Fix the High and the Mediums, then run the phone checks below.

## 1. Commands and results

| Command | Result |
| --- | --- |
| `cd cloud/functions && npm ci` | OK. `npm audit --omit=dev`: 0 vulnerabilities. Dev tree: 18 (13 moderate, 5 high), all in tooling (firebase-tools and friends), not deployed. |
| `npm test` at head (before QA tests) | 10 files, **37 passed, 1 skipped** (the Pub/Sub path of AT-8, skipped behind the sandbox proxy as documented). AT-9: 26 titles checked against 173,378 characters of function logs, none found. |
| `npm test` with QA tests | 14 files, **65 tests: 57 passed, 7 failed (all intended, "QA: fails until ..."), 1 skipped**. All 37 original tests still pass. AT-9: 31 titles against 259,116 characters of logs, none found. |
| `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintDebug` (before QA test) | BUILD SUCCESSFUL. **383 tests, 0 failures, 1 skipped** (the emulator E2E test, skipped by default). Lint: 0 errors, 102 warnings (63 UseKtx, the rest dependency and resource nits; nothing Spec 13 specific of note). |
| Same with `-PexactAlarmMode=SCHEDULE` | BUILD SUCCESSFUL, 383 tests, 0 failures, 1 skipped. |
| `./gradlew assembleRelease` | BUILD SUCCESSFUL. Release `gradleResValues.xml` has `cloud_enabled=false`, release `BuildConfig` has every `CLOUD_*` value blank, the merged release manifest has no `networkSecurityConfig` and no `DebugReceiver`, and the release APK contains none of `demo-api-key`, `10.0.2.2`, `demo-do-cloud` or `network_security_config`. |
| Emulator E2E: `firebase emulators:exec --project demo-do-cloud --only auth,firestore,functions "cd .. && DO_CLOUD_E2E=1 ./gradlew :app:testDebugUnitTest --tests '*CloudEmulatorE2ETest' -x checkAlarmManifests"` | BUILD SUCCESSFUL, `CloudEmulatorE2ETest` 1 test, 0 failures, **not skipped** (it really ran against the callables over HTTP). |
| `./gradlew :app:testDebugUnitTest --tests '*Spec13QaTest'` | 10 tests, **9 passed, 1 failed as intended** (L5 below). |
| `./gradlew testDebugUnitTest` with the QA test | **393 tests, 1 failed (intended, L5), 1 skipped** (the E2E test). |

With the QA tests added, `npm test` and `testDebugUnitTest` now fail, by design, until the bugs below are fixed. CI will go red on this branch for that reason.

## 2. Acceptance tests: real or trivial?

| AT | Where | Verdict |
| --- | --- | --- |
| 1 Device registration | `at1-device.test.ts` | Real: stores, updates, owner-only read through the real rules, sign-in required, argument checks. |
| 2 Pairing and OAuth | `at2-pairing-oauth.test.ts` | Real and thorough (401 challenge, RFC 9728 and 8414 discovery, RFC 7591, CIMD, PKCE, code replay revokes, refresh rotation and reuse revokes, hashing, burn after 5). **Gap:** it drives the flow with `fetch`, which ignores CSP, so it cannot see H1. |
| 3 Round trip | `at3-roundtrip.test.ts` | Real: simulated phone answers within the wait; queued, then expired on a fake clock via the clean-up code; content-free push recorded. |
| 4 Errors | `at4-errors.test.ts` | Real: every code over MCP and REST, with HTTP statuses. |
| 5 Isolation | `at5-isolation.test.ts` | Real (cross-assistant, cross-device, mirror skips, rules). QA added collection group queries, list queries, server-only fields and server collections (all denied, `qa-rules.test.ts`). |
| 6 Disconnect | `at6-disconnect.test.ts` | Real. |
| 7 Retention | `at7-retention.test.ts` | Real on a fake clock, but it seeds events with `at` equal to `storedAt`, so it cannot see L4. |
| 8 Purchase | `at8-purchase.test.ts` | Real against the fake Play client (acknowledged once, grace, hold, recovered, expired, revoked, voided, linked token). The Pub/Sub hop is skipped locally and runs in CI. Does not cover a token used by two devices (M1). |
| 9 No titles in logs | `scripts/check-logs.mjs` | Real for titles made with `title()`; the logger also cuts long strings. I found no logging of titles, tokens or codes in `src/` (every log call goes through `core/log.ts` with ids and codes only). |
| 10 Inbox apply | `CloudInboxTest`, `ApprovalTest` | Real: through `AgentFunctions` and `ReminderCommands` with the agent origin, cap, hold, replay safety, no titles in messages. |
| 11 Mirror sync | `MirrorSyncTest` | Real: only the assistant's own reminders and events, nothing private in the encoded JSON. |
| 12 Cloud off | `CloudOffTest`, `checkAlarmManifests` | Real; confirmed independently on the release build (above). |
| 13 CI green | builds above | All three builds pass locally. **Gap:** `.github/workflows/ci.yml` only runs `assembleDebug testDebugUnitTest lintDebug` on pull requests; the `SCHEDULE` variant and `assembleRelease` are not in CI, so AT-13 as written ("both exact-alarm variants and release") is not enforced. |
| 14 to 16 | phone | Not runnable here; see section 6. |

## 3. Bugs (functional), by severity

### H1 (High). The authorise page's CSP blocks the redirect back to the assistant
- `cloud/functions/src/http/types.ts:33`: `form-action 'self'` on every HTML page, including the code form. Browsers apply `form-action` to the whole navigation a form submission starts, redirects included (CSP Level 3; Chromium enforces it and Firefox does too in current versions). `POST /oauth/authorize` answers 302 to the assistant's `redirect_uri` (`authorize.ts:168`, and the cancel and error redirects at `:101`, `:110`), which is never this origin: an https site, a loopback port or a custom scheme. The browser refuses with "Refused to send form data ... violates form-action 'self'" and the person is left on a blank page. Pairing cannot complete.
- Repro: `qa-oauth.test.ts` "QA: fails until the authorise page's CSP form-action lets the browser follow the redirect back". Manual: open the authorise URL in Chrome, enter a valid code, watch the console.
- Fix: on the code page only, either drop `form-action` or set it to `'self'` plus the pending request's redirect origin (or scheme for a custom scheme). Keep `frame-ancestors 'none'` and the rest. Then check in a real Chrome, Firefox and Safari (section 6).

### M1 (Medium). One purchase token entitles any number of devices, and earlier owners stay entitled
- `cloud/functions/src/billing/verify.ts:60` moves `purchases/{token}` to the latest device but leaves the earlier device's `devices/{uid}/entitlement/current` as it was; `rtdn.ts` then updates only the new owner. A token copied from a rooted phone, or simply shared, entitles every device that verifies it, and the earlier ones stay `entitled: true` server-side after expiry or cancellation. The phone re-verifies hourly on resume, so each device can keep taking ownership back.
- Repro: `qa-billing.test.ts` "QA: fails until a purchase token verified by a second device stops entitling the first one".
- Fix: set `obfuscatedAccountId` (a hash of the device uid) in the Play purchase flow and require it to match in `verifyPurchase` (Play returns it in `externalAccountIdentifiers`); and, when ownership moves, mark the earlier owner's entitlement as moved (`entitled: false`, `state: "transferred"`). Server-side verification is only worth having if a token is bound to its buyer.

### M2 (Medium). The client metadata fetcher downloads the whole body before the 5 KB check
- `cloud/functions/src/oauth/clients.ts:128`: `await response.text()` then `text.length > MAX_DOC_BYTES`. Any unauthenticated `GET /oauth/authorize?client_id=https://attacker.example/big` makes the 256 MiB `api` function buffer whatever the attacker sends in 5 seconds, which can crash the instance; a few in parallel take the endpoint down for everyone. (Also, `text.length` counts UTF-16 units, not bytes.)
- Repro: `qa-oauth.test.ts` "QA: fails until the metadata fetcher stops reading after the 5 KB cap" (8 MiB served, all of it read).
- Fix: check `content-length` when present, then read `response.body` with a reader and abort once more than 5 KB has arrived.

### M3 (Medium). No rate limiting on the unauthenticated OAuth endpoints; pairing guesses scale with volume
- `cloud/functions/src/http/app.ts:50-66`, `oauth/authorize.ts:44-123`. `/oauth/register` and `GET /oauth/authorize` each write a Firestore document with no per-IP or global limit, and a wrong code is charged only to the code it names (selector known) and to the one authorise page (5 tries). A guess at an unknown selector costs the attacker one request and a fresh page costs one more, so guessing is unbounded overall. Each guess succeeds with probability live codes / 31^8 (about 8.5e11). With the `api` function's 20 instances at a few hundred requests a second (about 30 million guesses a day) and 1,000 codes live at a time, that is roughly a 3 to 4% chance a day of pairing with a stranger's phone (then: reminders that nag, within the cap and hold). The same volume also burns other people's codes when a guess hits a live selector, and the writes cost money.
- Related (Low): the per-page counter is read and then updated outside a transaction (`authorize.ts:106-112`), so parallel posts on one page each get a guess. The emulator runs one request at a time per function, so `qa-oauth.test.ts` "the per-page limit ... holds under parallel posts (in the emulator)" passes here; it should be re-checked in production.
- Fix: a per-IP (from `x-forwarded-for` as Hosting sets it) and global failed-attempt limiter on `POST /oauth/authorize` and on `/oauth/register` (for example 10 a minute per IP and a global ceiling that pages someone), the page counter in a transaction, and consider App Check or reCAPTCHA Enterprise on the form if abuse appears. Longer codes (10 characters) would also cut the odds 1,000-fold at little cost to the person typing it.

### L3 (Low). `due_at` accepts impossible calendar dates
- `cloud/functions/src/agent/args.ts:36`: `Date.parse("2027-02-30T09:00:00+08:00")` rolls over to 2 March, so the "not a real date and time" check never fires; the reminder is made for a day the assistant did not ask for.
- Repro: `qa-api.test.ts` "QA: fails until due_at refuses impossible calendar dates".
- Fix: after parsing, format the instant back in the given offset and compare the date and time fields with the input.

### L4 (Low). Event retention uses the phone's `at`, not the server's `storedAt`
- `cloud/functions/src/maintenance/cleanup.ts:80` deletes events by `at`, which the phone sends. An event with a future `at` (a phone with a wrong clock, or a bug) is kept for ever, against section G's 30 days; one synced late with an old `at` is deleted at once.
- Repro: `qa-api.test.ts` "QA: fails until event retention uses the server's storedAt" (event with `at` a year ahead survives a clean-up 40 days later).
- Fix: query on `storedAt` (add the collection group index), or clamp `at` to `[now - 30 days, now + 1 day]` in `syncAgentMirror`.

### L5 (Low, client, defence in depth). The phone trusts the inbox's `agentId`
- `app/src/main/java/com/evansappstudio/doapp/cloud/CloudInbox.kt:47` and `:60-70`. `CloudJson.inboxRequest` does not check that `agentId` is a Do Cloud id (`ag_` plus 16 hex), and `CloudInbox` uses it as the owner. A request naming an on-phone AppFunctions caller (`com.google.android.apps.bard`) completes that caller's reminders; one with an empty id creates a reminder owned by nobody. The server only ever writes `ag_` ids, so this needs a bad or compromised server, but the phone is meant to be the authority.
- Repro: `Spec13QaTest.qaFailsUntilNonCloudAgentIdsAreRefused`.
- Fix: in `CloudInbox.apply`, answer `invalid_argument` (or drop) unless `CloudAgent.isAgentId(request.agentId)`; ideally also require the id to be in the cached connected list.

### L9 (Low, process). AT-13 is not enforced by CI
- `.github/workflows/ci.yml:39` runs only the default variant; add `-PexactAlarmMode=SCHEDULE` and `assembleRelease` steps (the release step also runs `checkAlarmManifests` on the release manifest).

## 4. Security findings, by severity

The reviewed points, with what holds and what doesn't.

**Holds (no finding):**
- **PKCE** S256 only, challenge format checked, verifier 43 to 128 characters, compared in constant time (`pkce.ts`). A wrong verifier does not use the code up (fine: the verifier has 256 bits).
- **Redirect URIs**: exact string match, loopback http may change only the port; `javascript:`, `data:`, `file:`, fragments and non-loopback http refused at registration; no redirect is ever sent to an unregistered URI (bad URIs get an HTML error, not a redirect). `qa-oauth.test.ts` tries trailing slashes, query strings, case, look-alike hosts, ports, `localhost` against `127.0.0.1`.
- **State and `iss`**: state is echoed URL-encoded; RFC 9207 `iss` on every response.
- **Codes**: 5 minutes, single use in a transaction, bound to client, redirect URI and resource; replay revokes the grant.
- **Refresh**: rotates every use, reuse revokes the grant and tells the phone (`grantRevokedAt`).
- **Hashing**: tokens, codes, authorise request ids and pairing codes are stored only as SHA-256 under a purpose prefix; no raw value in any stored document (checked).
- **Bearer checks**: only `do_at_` access tokens, length-capped, unexpired and bound to the MCP resource; a refresh token, an expired token or a made-up one get 401 with the RFC 9728 challenge on both `/mcp` and `/v1`.
- **Authorise page XSS and clickjacking**: every interpolated value is escaped (a hostile `client_name` renders inert), no scripts, `X-Frame-Options: DENY`, `frame-ancestors 'none'`, `no-store`, `no-referrer`. CSRF: the form's secret is the pairing code the person types, so a forged post gains nothing.
- **Pairing codes**: 31 symbols, 8 characters (about 39.6 bits), `crypto.randomInt`, 10 minutes, single use, older codes dropped on a new one; unknown selector and wrong secret give the same page (no oracle). Selector and secret split works as described.
- **MCP**: every request is authenticated before parsing; strict argument parsing with fixed messages that never echo input (checked with a marker string); text over 500 characters, oversized text, bad types, odd cursors and path-like ids are `invalid_argument`; unknown argument names never reach the phone (`nag_minutes`, `quiet_hours`, `approval`, `source` are dropped: only `text`, `dueAt`, `repeat`, `alertLevel` are stored). Origin check on `/mcp`.
- **Isolation**: everything is keyed by the token's device and agent; `complete_reminder` is refused unless the id is in that assistant's mirror; `syncAgentMirror` skips unknown reminders; another device cannot complete or see this device's request. Per-device rate limit covers all of a device's assistants together (checked: 60, then 429 with `Retry-After`).
- **Firestore rules**: a device reads only its own tree; no client writes anywhere (own entitlement, agents, mirror, inbox all denied); collection group and list queries across devices denied; every server collection (`tokens`, `grants`, `pairingCodes`, `authCodes`, `authRequests`, `oauthClients`, `purchases`, `rateLimits`, `rtdnSeen`, `_fakePlay`, `_emulatorPushes`) denied (`qa-rules.test.ts`).
- **RTDN state is read back from Play**, not taken from the notification (a forged "expired" or "on hold" for an active purchase changes nothing; another package is ignored). There is no HTTP path to the handler; only the Pub/Sub topic.
- **Test seams** (`FakePlayClient`, `RecordingPush`, http loopback client ids) all require `inEmulator()` as well as the setting, so a copied `.env` cannot switch them on in production.
- **Logging**: one logger, ids and codes only, long strings cut; AT-9 passes.
- **Retention** clean-up covers section G plus tokens, codes, pages, counters, registrations and revoked grants; deleting an idle device revokes its grants and deletes its Auth user.

**Findings:**

| Id | Severity | Finding | Where |
| --- | --- | --- | --- |
| H1 | High | CSP `form-action 'self'` blocks the OAuth hand-back (functional, above). | `http/types.ts:33` |
| M1 | Medium | Purchase tokens are not bound to the buyer; shared tokens entitle many devices and earlier owners stay entitled. | `billing/verify.ts:60` |
| M2 | Medium | Unbounded client metadata download (unauthenticated memory DoS). | `oauth/clients.ts:128` |
| M3 | Medium | No rate limits on `/oauth/register` and `/oauth/authorize`; pairing guesses limited only by volume; the per-page counter is not atomic. | `http/app.ts:50-66`, `oauth/authorize.ts:104-112` |
| L1 | Low | Client ID metadata URLs: a trailing dot gets past the host checks (`https://localhost./`, `https://metadata.google.internal./`), and names that resolve to private addresses (for example `169.254.169.254.nip.io`) are not checked at all. The https-only rule limits what can be reached (the GCP metadata server is plain http), so this is a blind GET to internal https services at most. Fix: strip a trailing dot before the checks, resolve the name and refuse private, loopback and link-local addresses (and pin the connection to the checked address), or send these fetches through an egress proxy. Test: `qa-oauth.test.ts` "trailing dot". | `oauth/clients.ts:149-152` |
| L2 | Low | Client names keep Unicode bidirectional controls (U+202E and friends), so a name can be made to read differently on the consent page and on the Assistants screen. Fix: strip `‎‏‪-‮⁦-⁩` in `cleanText` and in `displayName`. Test: `qa-oauth.test.ts` "bidirectional control characters". More broadly (inherent, Info): any registrant can call itself "Claude"; the page's "you'll go back to <host>" line is the only tell, so keep it prominent. | `core/text.ts:5`, `ui/AssistantsScreen.kt:370` |
| L5 | Low | The phone trusts the inbox's `agentId` (above). | `cloud/CloudInbox.kt:47,60-70` |
| L6 | Low | RTDN `notificationType` 12 (revoked) and `voidedPurchaseNotification` are taken from the notification itself, so a forged one revokes. Only the topic's IAM stops that, which is correct if section 6's deploy check is done. Hardening: confirm a void with `voidedpurchases.list`, and treat 12 as "re-read Play" (Play reports a revoked subscription as expired). Documented by `qa-billing.test.ts`. | `billing/rtdn.ts:54-62,91` |
| L7 | Low | Refresh rotation has no grace window: a client that retries after losing the token response reuses the old refresh token and loses the grant (the phone shows "Connect again"). Strict but standard; watch for it in the field with Claude and ChatGPT. | `oauth/token.ts:65` |
| L8 | Low | "Used up" and "expired" pages differ from "didn't work", which tells a guesser that a selector was live recently. Minor given the numbers; fold into M3's limiter. | `oauth/authorize.ts:113-118` |
| I1 | Info | Anyone can make anonymous devices, pair them with their own codes and get unlimited agents, each with its own 30 a minute. Bounded by `maxInstances` and the budget alert only. Turn on App Check for the callables and set Identity Platform's anonymous sign-up quota at deploy. | `index.ts:20,52` |
| I2 | Info | In the emulator, a malformed `application/json` body to `/mcp` is rejected by the platform's body parser before Do's code, with an HTML page carrying a full stack trace and local paths. Production (Functions Framework with `NODE_ENV=production`) should not include the stack; check at deploy. Do's own parse errors (text bodies) are clean JSON-RPC. | platform |

**Client (Android) review:**
- What an assistant can make the phone do: create a reminder at Normal (Loud or Alarm only as a held request, settled only by a USER or the person's own standing rule), and mark done one of its own active reminders. It cannot set nagging (the person's default is used, payload `nagMinutes` ignored), quiet hours, approval, source or another reminder; it cannot settle its own hold (`resolveApproval` refuses an AGENT origin); the free cap holds across several assistants and across replays (`Spec13QaTest`).
- Malicious payloads: 200,000-character text is cut to 200 (first line only); `dueAt` of `1e300`, negative, more than 10 years ahead, an object for `args` and an unknown `kind` are `invalid_argument`; a string `dueAt` is ignored and the text parsed; a fraction is truncated; a request with no id is dropped; unknown fields are ignored. All pass. Only L5 fails.
- FCM: `DoMessagingService` is `exported="false"` and switched by `@bool/cloud_enabled`; it only enqueues work for `{kind: inbox}`; nothing in a push is trusted beyond that.
- Debug cleartext config and the exported `DebugReceiver` (including `CLOUD_PAIR`, which logs a live pairing code) are in `src/debug` only and absent from the release manifest and APK (checked).
- Config: blank release values mean no Firebase class is touched (`AppContainer.cloud` is null), `FirebaseInitProvider` removed, FCM auto-init off; the API key is a Firebase client key (not a secret) but must be restricted at deploy.
- `do_cloud` prefs (identity, request log, cursor) are not in the backup allowlist, so a restored phone does not reuse another device's identity (checked `backup_rules.xml` and `data_extraction_rules.xml`).
- Cloud failures and alarms: the `ReminderCommands` listener and `Scheduler.onFired` only enqueue WorkManager work, inside try/catch; a failed pull or mirror sync leaves reminders and armed alarms exactly as they were (`Spec13QaTest.aFailedPullLeavesRemindersAndAlarmsAlone`).

## 5. Tests added

Server (`cloud/functions/test/`), run by `npm test`:
- `qa-oauth.test.ts` (14 tests): CSP hand-back (fails, H1), hostile client name escaped, bidi controls (fails, L2), no selector oracle, per-page limit under parallel posts (passes in the emulator, see M3), exact redirect matching, dangerous schemes, code binding and expiry, bearer checks and hashing, pairing code entropy and storage, CIMD host filter, trailing dot (fails, L1), no redirects, metadata size cap (fails, M2).
- `qa-rules.test.ts` (3): collection group and list queries, server-owned fields, other devices and server collections.
- `qa-api.test.ts` (7): only four arguments reach the phone, input validation without echo, impossible dates (fails, L3), clean errors, per-device rate limit across assistants, cross-device request isolation, event retention (fails, L4).
- `qa-billing.test.ts` (3): shared purchase token (fails, M1), RTDN state read back from Play (documents L6), package and product checks.

Android: `app/src/test/java/com/evansappstudio/doapp/cloud/Spec13QaTest.kt` (10 tests, 1 fails: L5).

Every failing test is named or messaged "QA: fails until ...".

## 6. To check on the phone and at deploy

At deploy (Tim):
1. **H1 first**: after the fix, pair from a real browser (Chrome on Android and desktop, Safari on iPhone, Firefox) with both an https redirect (claude.ai, chatgpt.com) and a loopback or custom-scheme client (Claude Code, Cursor). The browser must land on the assistant with `code` and `state`.
2. Pub/Sub topic `play-rtdn`: only `google-play-developer-notifications@system.gserviceaccount.com` may publish (no `allUsers`, no broad project roles). This is what makes L6 safe.
3. Restrict the Android API key to the package and release SHA-1 and to the listed APIs; set the Identity Platform anonymous sign-up quota; consider App Check on the callables (I1).
4. Send a malformed JSON body to `https://<site>/mcp` and confirm the answer has no stack trace (I2).
5. Confirm the Hosting rewrite passes the client IP in `x-forwarded-for` (needed for M3's limiter) and that `PUBLIC_BASE_URL` (or the default `web.app` origin) matches the site exactly, since tokens are bound to `<site>/mcp`.
6. Budget alert, and an alert on `do.oauth.reuse_revoked` and `do.pairing.rejected` rates (guessing shows up there first).
7. Firestore database in `australia-southeast1`; deploy `firestore.rules` and indexes before the functions; add the `storedAt` events index if L4 is fixed that way.

On the phone (Mark with Tim, as the Android build notes list, plus):
8. Pair Claude and ChatGPT (AT-14) and watch for "Connect again" appearing soon after (L7: a refresh retry losing the grant).
9. A Loud request with "Ask me before it can ring through" on shows the card and rings at Normal until allowed (AT-15); Decline deletes it and the assistant sees `deleted`.
10. Flight mode, queue, reconnect within 24 hours: applied once (AT-16); after 24 hours the assistant sees `device_offline` in `get_events`.
11. With a licence tester: buy, verify (`entitled=true`), then verify the same token from a second test device and confirm the first loses it once M1 is fixed.
12. On a release build with blank values: no Assistants entry, no Firebase traffic in a proxy capture.
13. An assistant name containing right-to-left override characters shows plainly once L2 is fixed.

## 7. Re-test after fixes (server, 28 Nov 2026, about 16:00 to 16:45 AWST)

Re-tested at `988f528` (the server builder's fixes) plus this QA commit. Android fixes are not in yet and were not reviewed here; `app/` is unchanged by this pass, so `Spec13QaTest` (L5) still fails until they land.

**`npm test`: 15 files, 84 passed, 1 skipped (the local Pub/Sub path), 0 failed.** AT-9: 31 titles against 267,954 characters of function logs, none found.

**QA test changes in this pass:**
- `qa-oauth.test.ts`, M2: the old check (bytes the test server wrote) could not pass for any fetcher, because Linux loopback buffers megabytes in the kernel before a client can react; the builder was right. Rewritten as "the fetcher (M2)": (1) a server that drips 1 KiB every 10 ms and never ends; the production `defaultFetcher` must refuse it in under a second and close the connection itself after under 32 KiB. The old `response.text()` fetcher takes the full 5 s timeout against this server (checked with a copy of the old code), so the test separates the two. (2) An 8 MiB blast must stop, by the fetcher's own `bytesRead`, within one 64 KiB chunk of the 5 KB cap. (3) A declared 10 MiB `content-length` is refused without reading. (4) A control: a 3 KB document is still read. The builder's `qa-fixes.test.ts` M2 check (fetcher-side `bytesRead`) is sound too; it complements these.
- `qa-billing.test.ts`: the builder's edits test the right behaviour (strict binding: a second device is refused; revoke and void act only when Play confirms). Tightened: the refused device's record is `other_device`, it cannot pass the first device's account id (`invalid-argument`, because the server derives the id from the caller's uid and ignores the claim otherwise), and `purchases/{token}` still names the first device, so RTDN keeps reaching the real owner. Retitled, since it now passes.
- Tests that used to fail keep their "QA: fails until ..." names as a record; all of them now pass.

**Spot checks against the report:**

| Id | Fix | Verdict |
| --- | --- | --- |
| H1 | The code page adds exactly the redirect's origin (or its private-use scheme) to `form-action`; other pages keep `'self'`; `script-src 'none'`, `img-src 'none'` and `nosniff` added. The error and cancel redirects come from the same page, so they are covered. | Fixed. Still to confirm in real browsers (section 6, item 1). |
| M1 | Purchases bound with `obfuscatedAccountId = base64url(SHA-256("do-account:" + uid))`, compared with Play's `externalAccountIdentifiers`; the caller's claim is only checked against the server's own derivation. Unbound or foreign tokens answer `other_device`; a token moving to another installation clears the old owner (`transferred`). | Fixed on the server. Depends on the Android client: it must sign in before a purchase, pass the id in `BillingFlowParams`, and treat `other_device` as "no server opinion" (keep Spec 10's local result, as `CONTRACT.md` says). Until it does, a server verdict would switch real buyers off. Known cost: after a reinstall or a new phone the uid changes and the server can't verify that subscription any more (local Play result only). |
| M2 | Streamed fetch on `node:https`, 3 s deadline, 5 KB cap enforced per chunk, `content-length` checked first, no redirects, DNS answers checked and the connection pinned to the checked address. | Fixed (tests above). |
| M3 | Firestore counters per IP and global on register, authorise page and code posts; the per-page try is charged in a transaction before the code is checked. | Fixed, with one remaining Low (R1 below). |
| L1 | Hosts normalised (case, trailing dots, IDNA), local names and IP literals refused, non-443 ports refused, private, loopback, link-local, CGNAT, documentation and NAT64 addresses refused after DNS. | Fixed. Minor gap: 6to4 (`2002::/16`) addresses that embed a private IPv4 are not refused; negligible. |
| L2 | `cleanText` removes Unicode format, private-use and surrogate characters. | Fixed on the server. Side effect: zero-width joiners go too, so emoji sequences in a name split (cosmetic). The phone's `displayName` is the Android agent's part. |
| L3 | `parseStrictIso` refuses impossible dates, hour 24 and offsets past 14:00. | Fixed. |
| L4 | Events get a server `receivedAt`; retention deletes by it. | Fixed (nothing deployed, so no older events lack the field). |
| L5 | Client side. | Not yet (Android). |
| L6 | A void and a type 12 are acted on only when Play no longer reports the subscription as entitled; voids for one-time products are ignored. | Fixed. The topic IAM check (section 6, item 2) still matters for everything else. |
| L7 | 30 second grace: the old refresh token returns the same new pair, sealed with AES-256-GCM under a key derived from the old token (the database still holds no usable token); after 30 s reuse still revokes. | Fixed. Acceptable trade-off: a thief racing within 30 s gets the same pair as the client, not a new one. |
| L8 | One message for every wrong code. | Fixed. |
| I2 | `NODE_ENV=production` in `functions/.env` (all projects), so Express's own error pages carry no stack. | Fixed in the emulator; still confirm on the deployed site (section 6, item 4). |

**Remaining finding (no failing test, because the right answer depends on the real infrastructure):**
- **R1 (Low, availability).** The per-IP key comes from `X-Forwarded-For`, counting `CLIENT_IP_HOPS` (1) from the right (`http/ip.ts`). The `api` function has `invoker: "public"` (Hosting needs that), so it can also be called on its own `run.app` address. There, Google's front end appends only the real client address, so `X-Forwarded-For: <random>, <real>` puts the caller's own random value where Hosting traffic has the client, and the per-IP limits can be sidestepped from one machine. The global ceilings then are the only bound: they hold the guessing odds down (1,000 code posts an hour is about 24,000 guesses a day, around 3 in 100,000 per day against 1,000 live codes), but they fail closed, so the same caller can use up `g_reg` (500 an hour) or `g_azc` (1,000 an hour) and stop every new pairing until the hour ends. Fix options: refuse requests that didn't come through Hosting (for example, route `/oauth/*` only through Hosting and check a header that Hosting sets and the client can't, if one is confirmed at deploy), or key the per-IP limit on the right-most address when the chain is shorter than expected; and alert on `do.oauth.limited scope=global`. Deploy check: capture real `X-Forwarded-For` values through Hosting and directly on the `run.app` URL, and set `CLIENT_IP_HOPS` to match.

**Server verdict after fixes: ready for deploy, subject to section 6's checks (now including R1's header check) and to the Android side landing L5, the purchase binding (M1's client half) and the `displayName` part of L2.** No High or Medium remains on the server.
