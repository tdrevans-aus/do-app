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
