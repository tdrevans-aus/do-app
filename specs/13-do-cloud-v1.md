# Spec 13: Do Cloud v1 (draft)

Owner: Gareth (PM). Build: Claude build agents (server first, then the Android client). QA: Mark, and a Claude test agent. Status: **draft by Claude, 27 Nov 2026 (AWST).** Architecture decided by Tim on 27 Nov 2026 (strategy section 9B: local-first with a thin cloud, Firebase recommended). Builds on Specs 9 and 12 (and uses Spec 10's billing).
Sources: strategy sections 9B and 10; `docs/design/DESIGN-PASS-1.md` (screen 8, Assistants: pairing, approval card, rules for every assistant); Spec 9 (`ReminderCommands`, `Origin`, `approval`, `requestedAlertLevel`, event log); Spec 12 (`AgentFunctions` adapter: `createReminder`, `completeReminder`, `listReminders`, the hold rule); Spec 10 (`do_unlimited`, entitlement states). External facts the builder must confirm against current docs: the Model Context Protocol specification (transport and authorization: Streamable HTTP, OAuth 2.1 with PKCE, authorization server metadata, protected resource metadata, dynamic client registration), Firebase (Cloud Functions 2nd gen, Firestore, Anonymous Auth, Cloud Messaging data messages, Emulator Suite), and the Google Play Developer API (`purchases.subscriptionsv2.get`, subscription acknowledge, Real-time Developer Notifications).

## 1. Goal
Let assistants that don't run on the phone (ChatGPT, Claude and others) add, complete and hear back about Do reminders, through Do's own small cloud, without the user making an account, and without the cloud ever becoming the source of truth. Also move purchase verification to the server so subscriptions are acknowledged reliably. The phone keeps working exactly as today with no network.

## 2. Shape

```
Assistant  --MCP/REST + OAuth-->  Do Cloud (Functions + Firestore)  --FCM data push-->  Phone
                                         ^                                               |
                                         +---------- results and events (HTTPS) ---------+
```

- **The phone decides.** Every assistant request is a message in the device's inbox. The phone applies it through `ReminderCommands` (Spec 9) with `Origin(Actor.AGENT, ReminderSource.AGENT, sourceId = agentId)`, enforcing the free cap, the hold rule (Spec 12) and quiet hours exactly as for any other reminder, then writes the result back.
- **The cloud only carries messages** and keeps a minimal mirror of reminders that each assistant created (so it can answer `list` without waking the phone every time).
- **One small interface on the phone** (`CloudPort`) so the host can be swapped (Firebase now; Cloudflare possible later).

## 3. Scope: server (folder `cloud/` in the repo)

### A. Project layout
- `cloud/functions/`: TypeScript, Firebase Cloud Functions (2nd gen), Node LTS. `cloud/firestore.rules`, `cloud/firestore.indexes.json`, `cloud/firebase.json` with the emulator config. No real project id is committed; use a demo project id (`demo-do-cloud`) so the Emulator Suite runs with no account.
- Tests with the Firebase Emulator Suite (Auth, Firestore, Functions) and a standard TypeScript test runner. `npm test` starts the emulators and runs everything. A GitHub Actions workflow `.github/workflows/cloud.yml` runs it on pushes touching `cloud/**` (Ubuntu runner).

### B. Device identity and push
- On first use of any cloud feature (connecting an assistant, or the first purchase), the phone signs in with **Firebase Anonymous Auth**. The anonymous uid is the device id. No email, no name.
- `registerDevice({ fcmToken, platform, appVersion })` (callable, auth required) stores `devices/{uid}` with the token, `platform` (`android`, later `ios`), `createdAt`, `lastSeenAt`. Token refreshes call it again.
- Push is an FCM **data** message with no user-visible content (`{ "kind": "inbox" }`), high priority, so the phone wakes and pulls. Nothing about a reminder is in the push.

### C. Pairing an assistant (no account)
- In Do (Assistants screen, "Connect another assistant"), the phone calls `createPairingCode()`, which returns an 8-character code (letters and digits, no look-alikes such as 0/O and 1/I), valid for 10 minutes, single use, stored hashed.
- The assistant connects to Do's MCP endpoint. The endpoint is an **OAuth 2.1 protected resource**; Do Cloud is also the authorisation server, with authorisation server metadata, protected resource metadata and dynamic client registration as the MCP authorization spec requires (the builder confirms the current spec version and required endpoints).
- The authorise page is a small Do-branded HTML form: "Enter the code shown in Do". A correct code binds the new grant to `{ deviceUid, agentId, clientName }` and finishes the OAuth flow (authorisation code with PKCE). Wrong codes are rate-limited (5 tries per code, then it is burnt).
- The phone then shows the assistant on the Assistants screen (from `devices/{uid}/agents/{agentId}`), with Disconnect. Disconnect revokes its tokens and deletes its mirror and events.
- Access tokens are short-lived (1 hour) with refresh tokens (30 days, rotating). Tokens are stored hashed.

### D. The agent API (MCP, plus a REST mirror)
- **MCP endpoint** (Streamable HTTP) with tools:
  - `create_reminder { text, due_at?, repeat?, alert_level? }`: `text` is natural language ("call Mum tomorrow 6pm"); the phone parses it with Do's own parser unless `due_at` is given (ISO 8601 with offset). `alert_level` one of `normal`, `loud`, `alarm`.
  - `complete_reminder { reminder_id }`
  - `list_reminders { status?: "active" | "done" | "all", limit? <= 50 }`: only reminders **this assistant** created.
  - `get_events { since_cursor?, limit? <= 100 }`: events for this assistant's reminders (created, fired, nagged, snoozed, done, deleted), with a cursor.
- **REST mirror** of the same four under `/v1/` with the same tokens and JSON, for assistants or automations without MCP.
- **Request flow:** the function writes `devices/{uid}/inbox/{requestId}` (`pending`), sends the push, then waits up to 15 seconds for the phone to set `done` or `failed`. If the phone answers in time, the tool returns the result; otherwise it returns `{ status: "queued", request_id }` and the assistant can call `get_events` later. A queued request expires after 24 hours (`expired`).
- **Results map from Spec 12's adapter errors:** `limit_reached` (free cap of 5, with a short human message suggesting Do Unlimited), `held_for_approval` (asked for Loud or Alarm; created at Normal until the user approves on the phone), `not_found`, `invalid_argument`, `device_offline` (queued and expired), `disabled` (the user turned assistants off).
- **Rate limits** per agent and per device (for example 30 requests a minute, 500 a day), returning `rate_limited`. Same for free and paid.
- **Every assistant must follow the user's rules** (design screen 8): an assistant can never change quiet hours, turn off nagging, or touch reminders it did not create.

### E. Phone side of the exchange (server endpoints the phone calls)
- `pullInbox()` (callable, auth = device): returns pending requests for this device, oldest first.
- `completeRequest({ requestId, result })`: writes the result.
- `syncAgentMirror({ agentId, reminders, events })`: upserts the mirror for reminders this assistant created (`uuid`, `title`, `dueAt`, `status`, `version`, `updatedAt`) and appends their events. The phone sends only agent-created reminders, only to their own assistant.
- Firestore security rules: devices can only read and write their own documents; nothing is client-writable except through these callables; assistants never touch Firestore directly.

### F. Purchase verification
- `verifyPurchase({ platform: "play", packageName, productId, purchaseToken })` (callable, auth = device): calls the Play Developer API (`purchases.subscriptionsv2.get`) with a service account, acknowledges the purchase if needed, and stores `devices/{uid}/entitlement` (`state`, `expiryTime`, `autoRenewing`, `productId`, `lastCheckedAt`).
- An RTDN handler (Pub/Sub topic) updates the entitlement on renewals, cancellations, grace period, account hold and expiry, by looking up the purchase token.
- In the emulator and tests, the Play API is a fake behind an interface. The real service account and topic are created by Tim with the Firebase project, before deploy.
- The phone's local entitlement (Spec 10) stays the fast path and works offline; the server result, when available, wins.

### G. Data kept, and for how long
- Inbox requests: deleted 7 days after they finish or expire.
- Agent mirror: kept while the assistant is connected; deleted on disconnect.
- Events: 30 days.
- Devices with no `lastSeenAt` for 180 days: deleted with everything under them.
- A scheduled function does the clean-up daily.
- No analytics, no logging of reminder titles in function logs.

## 4. Scope: Android client (package `cloud`)
- `CloudPort` interface (pure types in a pure file on `CorePurityTest`'s list) with `FirebaseCloud` as the implementation. Firebase is initialised in code from build config values (`cloudProjectId`, `cloudApiKey`, `cloudAppId`; defaults point at the emulator in debug, blank in release until Tim creates the project). No `google-services.json` committed. If the release values are blank, every cloud feature is hidden and the app behaves exactly like Spec 12.
- `DoMessagingService` (FCM) handles the `inbox` push by enqueuing a short expedited WorkManager job that calls `pullInbox`, applies each request through Spec 12's `AgentFunctions` (which uses `ReminderCommands`), and calls `completeRequest`. Also pulls on app start and on network regained.
- After any change to an agent-created reminder, a debounced job calls `syncAgentMirror` for that assistant.
- Assistants screen (design screen 8, in the current UI style or the new look if Spec 14 has landed): Connect another assistant (shows the pairing code, its expiry and the MCP URL to paste into the assistant), the list of connected assistants with Disconnect, the approval card for held requests (Allow, As Normal, Decline), and "Ask me before it can ring through" (on by default).
- Purchase: after a Play purchase or on resume, call `verifyPurchase` when the device has a cloud identity; otherwise Spec 10 behaviour stands.
- Debug hooks: `CLOUD_DUMP` (identity, token present, agents, pending inbox), `CLOUD_PULL`, `CLOUD_PAIR` (logs a code).

## 5. Out of scope
Accounts with Google or Apple sign-in. Sync between devices. The iPhone client (the server keeps `platform` so APNs can join later). Webhooks to assistants. App Store verification. Real deployment (Tim creates the project and gives the go-ahead). AppFunctions (needs compileSdk 37).

## 6. Acceptance tests
Server (emulator, `npm test`, CI):
1. **Device registration** stores the token; a second call updates it; another device can't read it.
2. **Pairing**: a code works once, within 10 minutes; a wrong code five times burns it; the OAuth flow (metadata discovery, dynamic client registration, authorise with PKCE, token, refresh with rotation) completes in a test client.
3. **create_reminder round trip** with a simulated phone answering: result returned within the wait; with no answer, `queued` then `expired` after 24 hours (fake clock).
4. **Errors**: `limit_reached`, `held_for_approval`, `not_found`, `invalid_argument`, `rate_limited`, `disabled` all surface correctly over MCP and REST.
5. **Isolation**: assistant A cannot list or complete assistant B's reminders, or another device's; Firestore rules deny direct client access.
6. **Disconnect** revokes tokens and deletes the mirror and events.
7. **Retention** clean-up deletes per section G (fake clock).
8. **Purchase verification** with the fake Play API: purchased, acknowledged once, grace period, account hold, expired, revoked via RTDN.
9. **No titles in logs**: function logs during the tests never contain a reminder title.

Android (unit, CI):
10. **Inbox apply**: requests go through `AgentFunctions` and `ReminderCommands` with the agent origin; the cap and hold rules apply; results map to the server's codes.
11. **Mirror sync** sends only agent-created reminders, only to their own assistant.
12. **Cloud off**: blank release config hides every cloud feature and changes nothing else.
13. **CI green**, both exact-alarm variants and release.

Phone (Mark with Tim, after Tim creates the project and gives the go-ahead):
14. Pair Claude or ChatGPT with the code; "remind me to call Mum in 5 minutes" fires on the phone and nags; Done on the phone shows up in `get_events`.
15. A Loud request shows the approval card; As Normal keeps it Normal.
16. Phone in flight mode: the request comes back `queued`, and is applied when the phone is back online if within 24 hours.

## 7. Risks
- **Push delivery.** FCM high-priority data messages can be delayed by the system; the 15-second wait then returns `queued`. Acceptable: the phone still applies it on next pull.
- **OAuth complexity.** The MCP authorization flow has several moving parts; AT-2 runs the whole flow in a test client.
- **Privacy and Play data safety.** Reminder titles from assistants pass through the cloud. Keep the minimum (section G), document it in the privacy policy and the Play data-safety form before release.
- **Cost.** Firebase Functions need the pay-as-you-go plan. Free users are capped at 5 reminders; rate limits bound abuse. Set a budget alert when the project is created.
- **Lock-in.** `CloudPort` on the phone and a thin function layer keep a move to Cloudflare possible.

## 8. Calls (Claude's defaults for Gareth)
1. Firebase, built against the emulator first; no account used until Tim creates the project.
2. Anonymous device identity; no accounts in v1.
3. Pairing by an 8-character code shown in Do, inside a standard MCP OAuth flow.
4. Assistants see only reminders they created.
5. 15-second synchronous wait, then queued, expiring after 24 hours.
6. Retention as in section G.
7. Server-side purchase verification is part of v1.

## Build notes (server)

Built by Claude on 28 Nov 2026 against the Firebase Emulator Suite with the demo project `demo-do-cloud`. No Firebase or Google Cloud account was used and nothing was deployed. Code in `cloud/`, the phone contract in `cloud/CONTRACT.md`, the deploy checklist in `cloud/README.md`.

**Versions checked.** MCP: the current revision is **2026-07-28** (stateless: protocol version, client info and capabilities in each request's `_meta`; `server/discover`; `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers that must match the body; no sessions; Client ID Metadata Documents preferred, dynamic client registration deprecated but kept). Do Cloud targets 2026-07-28 and also serves initialize-based clients on 2025-11-25, 2025-06-18 and 2025-03-26, because most assistants in the field still use those. The JSON-RPC handling is written directly (no SDK), so it fits a stateless Cloud Function; every answer is a single `application/json` response. Firebase: `firebase-functions` 7.4, `firebase-admin` 14.5, `firebase-tools` 15.37, Node 22. Play: `purchases.subscriptionsv2.get` for state; acknowledge exists only as `purchases.subscriptions.acknowledge` (v1, needs the product id); RTDN payload and notification types as in Play's RTDN reference.

**Decisions where the spec was open or wrong.**
1. **One public site.** Firebase Hosting rewrites every path to one HTTPS function, `api`, so the issuer is the site origin and the well-known documents sit at the root where RFC 8414 and RFC 9728 clients look. The MCP endpoint and canonical resource is `https://<site>/mcp`; tokens are bound to it (RFC 8707) and also work on `/v1/` (the spec's "same tokens").
2. **Client registration.** Both Client ID Metadata Documents (an https `client_id` URL, fetched with a 5 second timeout, 5 KB cap, no redirects, no IP or local hosts) and RFC 7591 registration. All clients are public with PKCE S256; a registration asking for a secret is answered with `token_endpoint_auth_method: none` (RFC 7591 lets the server replace values). Unused registrations are deleted after a day.
3. **"5 tries per code".** A wrong code can't be charged to a code unless the server knows which code was meant, so the 8 characters are a 4-character selector plus a 4-character secret. A wrong secret for a live selector counts against that code and the fifth burns it. Separately, one authorise page allows 5 wrong entries of any kind and then returns `access_denied`. Codes and tokens are stored as SHA-256 hashes only.
4. **Code and token safety.** Authorisation codes last 5 minutes and work once; replaying one revokes its grant. Refresh tokens rotate on every use (30 days from each rotation); reusing a rotated one revokes the whole grant and marks the agent `grantRevokedAt` so the phone can offer "Connect again". The authorise response carries `iss` (RFC 9207).
5. **`reminder_id` is the reminder's `uuid`**, not the phone's local id, so it is stable across export and import. The phone maps it back.
6. **`held_for_approval` is a success** (`isError: false`): the reminder exists at Normal. The phone sends `done` with the reminder; the server derives `held_for_approval` from `approval: PENDING`.
7. **`device_offline`** surfaces in `get_events` as a `request_result` event with `error_code: device_offline` when a queued request expires, because by then the tool call has long returned `queued`. Every finished request (answered in time or not) also produces a `request_result` event, so a queued result can always be found. A result the phone sends after expiry is still accepted, because the phone has applied it.
8. **`disabled`** comes from a new callable, `setAssistantsEnabled({ enabled })`, checked before anything is sent to the phone; the phone can also answer `disabled` itself.
9. **Assistants only touch their own reminders, enforced in the cloud too.** `complete_reminder` is refused with `not_found` unless the id is in that assistant's mirror, without waking the phone. `syncAgentMirror` only accepts reminders already in that assistant's mirror (created through its own requests) and skips others, so a phone bug can't copy a person's other reminders into the cloud.
10. **Events.** All Spec 9 `EventType` names are accepted (not only the six listed), in lower case, without `detail` or actor. The cursor is a per-assistant sequence number.
11. **Rate limits.** Fixed one-minute and one-day windows: 30 a minute and 500 a day per assistant, 60 and 1,000 per device, on every tool call. MCP returns a tool error `rate_limited` with `retry_after_seconds`; REST returns 429 with `Retry-After`. REST status codes: 200 done or held, 202 queued, 400 invalid_argument, 403 disabled, 404 not_found, 409 limit_reached, 410 device_offline, 429 rate_limited.
12. **Entitlement path.** `devices/{uid}/entitlement` is a collection in Firestore, so the record is `devices/{uid}/entitlement/current`. It adds `entitled` (true for active, grace period, and cancelled until expiry) so the phone needn't re-derive it. Unacknowledged active purchases are acknowledged by RTDN too, even before the phone checks in, so Play does not refund them after 3 days. `linkedPurchaseToken` (upgrade or resubscribe) moves the record to the new token. The last device to verify a token owns it. Spec 10 is not in the repo and the app's current product is the one-time `do_unlock`; the server verifies subscriptions only, with the product ids in `PLAY_PRODUCT_IDS` (default `do_unlimited`).
13. **Clean-up.** As section G, plus: expired tokens, codes and authorise pages, rate-limit counters, RTDN dedupe records after 7 days, revoked grants after 30 days. Deleting an idle device also revokes its grants, deletes its purchase records and its Anonymous Auth user.
14. **Origin.** `/mcp` refuses a request whose `Origin` header is present and not in `ALLOWED_ORIGINS` (default: the site's own origin); server-side assistants send none. No CORS for browser-based clients in v1.
15. **Wait time.** 15 seconds in production (`AGENT_WAIT_MS`); the emulator uses 6 seconds to keep the tests quick.
16. **Fake clock.** Expiry and retention are tested by calling the clean-up code in the test process with a chosen `now`, against the same emulator Firestore. Play is a fake behind `PlayClient` (Firestore-backed, emulator only); FCM is a recording fake (emulator only).
17. **Emulator quirk.** The Hosting emulator and the emulator's Pub/Sub-to-Functions hop send local traffic through `HTTPS_PROXY` and ignore `NO_PROXY`. The tests therefore use a small front proxy in place of Hosting, and when a proxy is set (the build sandbox, not CI) RTDN notifications are handed to the same handler in-process; in CI they go through the Pub/Sub emulator.

18. **Region.** Every function runs in `australia-southeast1` (Sydney), from one constant (`REGION` in `cloud/functions/src/core/config.ts`); the Hosting rewrites name the same region and `test/region.test.ts` checks both. The Firestore database must be created in `australia-southeast1` too (its location can't be changed later). The phone must use `Firebase.functions("australia-southeast1")`. Sydney supports 2nd gen functions, Cloud Scheduler and Eventarc Pub/Sub triggers (Tier 2 pricing).

**Server AT map.** AT-1 `test/at1-device.test.ts`; AT-2 `test/at2-pairing-oauth.test.ts`; AT-3 `test/at3-roundtrip.test.ts`; AT-4 `test/at4-errors.test.ts`; AT-5 `test/at5-isolation.test.ts`; AT-6 `test/at6-disconnect.test.ts`; AT-7 `test/at7-retention.test.ts`; AT-8 `test/at8-purchase.test.ts`; AT-9 `scripts/check-logs.mjs` (run by `npm test` after the suite, over every title recorded by `test/helpers/env.ts`). Transport details: `test/mcp-protocol.test.ts`.

## Build notes (Android client)

Built by Claude on 28 Nov 2026 against `cloud/CONTRACT.md` and the Firebase Emulator Suite (demo project `demo-do-cloud`). No Firebase or Google account was used and nothing was deployed. `cloud/` is unchanged: the client matches the contract as written.

**Shape.** Package `cloud`:
- `CloudPort.kt` (pure, on `CorePurityTest`'s list): the `CloudPort` interface and its types (`CloudConfig`, `InboxRequest`, `WireReminder`, `RequestResult`, `PhoneErrorCode`, `MirrorBatch`, `PurchaseCheck`, `VerifiedEntitlement`, `CloudException` with the callable codes).
- `CloudJson.kt` (pure): the contract's JSON, on the existing pure `backup/JsonValue` codec, plus the bridge to the plain maps the callable SDK sends.
- `CloudEntitlement.kt` (pure): how the server's verdict meets Spec 10's record. `billing/EntitlementPolicy.kt`, already import-free, joins the core list because it uses `EntitlementRecord`.
- `FirebaseCloud.kt`: the only Firebase user besides the FCM service. `FirebaseApp.initializeApp` from `FirebaseOptions` built from BuildConfig; Auth (anonymous) and `Firebase.functions("australia-southeast1")`, both pointed at the emulator when `CLOUD_EMULATOR_HOST` is set. The connected assistants are read from Firestore's REST API with the device's ID token (the rules allow a device to read its own documents), so the Firestore SDK and its gRPC stack are not in the app.
- `CloudInbox.kt` applies one request through `AgentFunctions` (Origin(AGENT, AGENT, agentId), so the cap and hold apply), and maps `AgentError` one to one to `limit_reached`, `not_found`, `invalid_argument` and `disabled`. `complete_reminder` looks up the uuid and refuses anything whose `source` is not AGENT with `sourceId` equal to this agent, or that is done or deleted, as `not_found`.
- `CloudMirror.kt` (`MirrorPlanner`) and `DoCloud.kt` (the facade the app, workers, screen and debug hooks use; Android-free, so the tests drive it with a fake port). `CloudStore.kt`: its own prefs file `do_cloud`, deliberately not in the backup rules (a restored phone must not reuse another device's identity, request log or cursor). `CloudWork.kt`: WorkManager jobs, `DoMessagingService`, the network watcher.

**Config.** Gradle properties, all optional. Release: `cloudProjectId`, `cloudApiKey`, `cloudAppId` (blank by default: cloud off), `cloudSiteUrl` (blank means `https://<project>.web.app`). Debug: `cloudDebugProjectId` (default `demo-do-cloud`), `cloudDebugApiKey` (`demo-api-key`), `cloudDebugAppId` (`1:000000000000:android:0000000000000000`), `cloudDebugSiteUrl`, `cloudEmulatorHost` (default `10.0.2.2`, the host from the Android emulator; for a phone on the LAN use the host's IP, and start the emulators with `--host 0.0.0.0` or edit `firebase.json`; `-PcloudEmulatorHost=` with real debug values uses the real servers). The MCP address on the screen is `<site>/mcp`, or `http://<emulatorHost>:5002/mcp` against the emulator (the front proxy the server tests use, or the Hosting emulator). A `cloud_enabled` bool resource from the same values switches the FCM service and receiver off in the manifest when the cloud is off; `AppContainer.cloud` is then null, the Settings entry is hidden and no Firebase class is touched.

**Dependencies.** Firebase BoM **35.2.0** (latest on Google Maven, published 11 Nov 2026), with only `firebase-auth` (25.0.0), `firebase-functions` (23.0.0) and `firebase-messaging` (26.0.2). No google-services plugin, no `google-services.json`. Test-only: nothing new.

**Merged into the manifest** (both variants; `checkAlarmManifests` now fails on any permission not in this list, and on any of the removed components):
- New permissions: `com.google.android.c2dm.permission.RECEIVE` and `com.google.android.providers.gsf.permission.READ_GSERVICES` (FCM), `INTERNET` (new in debug; release already had it from Play Billing). `ACCESS_NETWORK_STATE` and `WAKE_LOCK` were already there from WorkManager. No foreground service permission; the existing FOREGROUND_SERVICE check still passes.
- New components: `cloud.DoMessagingService` (not exported, `enabled=@bool/cloud_enabled`), Firebase's `FirebaseMessagingService` and `FirebaseInstanceIdReceiver` (both switched by `@bool/cloud_enabled`), `ComponentDiscoveryService` (Firebase's registrar list, inert until Firebase is set up), and in debug the data-transport services and `GoogleApiActivity` that release already had from Billing.
- Removed with `tools:node="remove"`: `FirebaseInitProvider` (Firebase starts only from code), the Auth sign-in and reCAPTCHA activities (`GenericIdpActivity`, `RecaptchaActivity`, `SignInHubActivity`, `RevocationBoundService`), the Credential Manager pieces (`CredentialProviderMetadataHolder`, `HiddenActivity`) and `PlayCoreDialogWrapperActivity`. Do uses Anonymous Auth only.
- `firebase_messaging_auto_init_enabled=false` (no FCM token until the device has a cloud identity) and analytics deactivated (no analytics library is present anyway).
- Debug only: a network security config that allows clear text, for the emulator suite.

**Flow.** Identity is made only by "Connect another assistant" (or a fresh purchase, as section B says): sign in anonymously, get the FCM token, `registerDevice` when the token is new, `createPairingCode`. Nothing in the background ever signs in. With an identity: app start, each resume and network regained enqueue a pull (unique work, network constraint, exponential backoff, 8 attempts); the `inbox` push enqueues an **expedited** pull on Android 12 and later (JobScheduler expedited job, `RUN_AS_NON_EXPEDITED_WORK_REQUEST` when out of quota) and a plain one before that, because expedited work there needs a foreground service, which Do does not have. The pull applies each request once: results are kept by request id for 8 days, so a request pulled again after a failed `completeRequest` is answered from the log and never applied twice. A request whose apply throws something that is not an `AgentError` (locked user, database error) stays in the inbox for the next pull. After applying, a mirror sync is enqueued. Cloud work never runs on the alarm path: the `ReminderCommands` listener and a new `Scheduler` `onFired` hook only enqueue a debounced (10 s, REPLACE) WorkManager job, and only when an assistant is connected; failures are logged as `DoAlarm cloud ...` (never a title) and retried.

**Mirror sync.** For each connected assistant (Firestore list, falling back to the cached one): its reminders (`source = AGENT`, `sourceId = agentId`, done and soft-deleted included, as `done`/`deleted`) whose `version` differs from the last one sent, and new events for them after a global event cursor (`id`, `reminderUuid`, `type`, `at` only). On-phone (AppFunctions) agents' reminders are never sent: their ids are not `ag_` ids. Results sent with `completeRequest` count as synced. Nothing advances unless every call succeeds; `not-found` drops that assistant locally. If a Replace import empties the event log the cursor resets (SQLite can reuse ids; the server then treats a reused id as a duplicate, so a few events after a Replace import can be missed by assistants).

**Decisions where the spec was open.**
1. **Approval card.** Allow: the requested level, approval `APPROVED`. As Normal: stays Normal, approval `DECLINED` (the request stays in `requestedAlertLevel`, as the design pass asked). Decline: as As Normal, then the reminder is soft-deleted (undoable for 30 days); the assistant sees it as `deleted`. New `ReminderCommands.resolveApproval(origin, id, ApprovalDecision)` over a new `Scheduler.settleApproval`; only a PENDING row changes, one `LEVEL_CHANGED` event with `approval=` in its detail; an AGENT origin can never settle.
2. **"Ask me before it can ring through" off** (default on): a held request is allowed at once by the person's standing rule (Origin(SYSTEM, USER, "assistant-rule")), so the result goes back as `done`, not held. It applies to cloud assistants; Spec 12's on-phone agents keep the hold.
3. **Assistants switch.** Stored on the phone at once and sent with `setAssistantsEnabled`; if that fails it is resent before the next pull. While off, the phone itself also answers `disabled` (the cloud `AgentFunctions` is built with `enabled` = the switch).
4. **Purchases.** `SubscriptionBilling` gains `setPurchaseObserver` and `reload` (no-ops in the debug fake, which has no tokens). Release billing hands every PURCHASED `do_unlimited` token to Do Cloud after it has written the local record (the offline fast path). Do Cloud calls `verifyPurchase` at once after a purchase (signing in first if needed, per section B) and at most hourly on resume, only with an identity. The verdict is written to the Spec 10 cache (`grantSource = cloud`, `everSubscribed` never cleared) and cached for 24 hours, so a later Play query cannot undo it; an entitled verdict past its `expiryTime` is not trusted offline. `unavailable`, `not-found` and `invalid-argument` keep the local result.
5. **Agent names** come from the untrusted `clientName`, shown as plain text, control characters stripped, 60 characters.
6. **FCM on the emulator.** FCM has no emulator, so with the demo project the token is null and `registerDevice` is skipped; pairing, pulls (on start, resume, network and `CLOUD_PULL`) and everything else still work. The server sends no push to a device without a token.

**Debug hooks** (`adb shell am broadcast -n com.evansappstudio.doapp/.debug.DebugReceiver -a com.evansappstudio.doapp.debug.<ACTION>`): `CLOUD_DUMP` (enabled, emulator, identity, token registered, switch, ask rule, agent ids, applied count, mirror cursor, MCP address, held count), `CLOUD_PULL` (pulls and syncs now, in process, and logs the counts), `CLOUD_PAIR` (signs in if needed and logs a fresh code and the MCP address).

**Tests.** 383 unit tests (334 before), 0 failures, 1 skipped by default (the emulator test). New: `cloud/CloudInboxTest` (AT-10), `cloud/MirrorSyncTest` (AT-11), `cloud/CloudOffTest` (AT-12), `cloud/ApprovalTest`, `cloud/CloudPurchaseTest`, `cloud/CloudContractTest` (fixtures in `src/test/resources/cloud` are the contract's own examples, and a test checks they still appear in `CONTRACT.md`; it also checks the error table, the server's accepted error codes and event types), `ui/AssistantsRenderTest` (Robolectric), and a Spec 13 purity check in `CorePurityTest`. `CloudEmulatorE2ETest` runs DoCloud, CloudInbox and CloudMirror against the real callables over HTTP (anonymous sign-in, registerDevice, createPairingCode, agents through the Firestore rules, pullInbox, completeRequest into the mirror with `pending` for a held request, syncAgentMirror after Done, setAssistantsEnabled, disconnectAgent, verifyPurchase `not-found`); the assistant side is seeded into Firestore with the emulator's owner token. It passed in this sandbox (the proxy problem only affects Hosting and Pub/Sub, which it does not use). Run it from `cloud/` after `npm ci && npm run build` in `functions/`:
`functions/node_modules/.bin/firebase emulators:exec --project demo-do-cloud --only auth,firestore,functions "cd .. && DO_CLOUD_E2E=1 ./gradlew :app:testDebugUnitTest --tests '*CloudEmulatorE2ETest' -x checkAlarmManifests"`.
Builds: `assembleDebug testDebugUnitTest lintDebug`, the same with `-PexactAlarmMode=SCHEDULE`, and `assembleRelease` (blank, and with test cloud values, where `checkAlarmManifests` sees `cloud_enabled=true`) all pass.

**AT map (Android).** AT-10 `CloudInboxTest` (plus `ApprovalTest`); AT-11 `MirrorSyncTest`; AT-12 `CloudOffTest` and `checkAlarmManifests`; AT-13 the three builds above (CI runs the first). Contract: `CloudContractTest`, `CloudEmulatorE2ETest`.

**For Mark and Tim on the phone, once the project exists.**
1. Build release with `-PcloudProjectId=<id> -PcloudApiKey=<Android API key> -PcloudAppId=<1:...:android:...>` (and `-PcloudSiteUrl=` if a custom domain is used). Add the Android app in the Firebase console with package `com.evansappstudio.doapp`; restrict the API key to that package and the release SHA-1, and allow it Identity Toolkit, Token Service, Cloud Functions, Firestore and FCM Registration APIs.
2. Settings shows Assistants; with blank values it does not (AT-12 on a device).
3. Connect: the code shows as `ABCD EFGH` with a 10 minute countdown and the MCP address; `CLOUD_DUMP` shows `identity=true tokenRegistered=true`. Pair Claude or ChatGPT (AT-14), check the request lands within the 15 seconds with the phone idle and screen off (the expedited job), and that Done on the phone appears in `get_events` within about 10 seconds of a network connection.
4. A Loud request shows the approval card; Allow, As Normal and Decline each do what decision 1 says (AT-15). Turn "Ask me before it can ring through" off and check an Alarm request rings as Alarm.
5. Flight mode, ask the assistant, then reconnect within 24 hours: the request is applied once, on network regained (AT-16). Kill the app and repeat with only the push to wake it.
6. On Android 11 or older, check the pull still runs after a push (plain job, no foreground service).
7. Buy Do Unlimited with a licence tester: `DoAlarm cloud purchase outcome=verified` in logcat and the server record `entitled=true`; cancel, and the state follows RTDN on the next resume (within an hour).
8. Privacy policy and data safety: reminder titles from assistants, and the anonymous device id, go to Do Cloud.
