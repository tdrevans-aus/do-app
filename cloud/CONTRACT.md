# Do Cloud v1: the phone contract

This is what the Android client (Spec 13, section 4) must send and expect. The server half lives in `cloud/functions`; the simulated phone in `cloud/functions/test/helpers/phone.ts` follows this file and is the executable version of it.

All phone calls are Firebase **callable functions** (region `us-central1`), made with the Firebase SDK after **Anonymous Auth** sign-in. The anonymous uid is the device id. Every call needs that sign-in; a call without it fails with `unauthenticated`. Times are epoch milliseconds (UTC) unless a field says ISO 8601.

Errors from callables use the standard callable codes: `invalid-argument` (the request was malformed: a client bug), `not-found`, `unauthenticated`, `unavailable` (try again later) and `internal`.

## 1. Identity and push

### `registerDevice({ fcmToken, platform, appVersion })`
- `fcmToken`: string, the current FCM registration token (call again on every token refresh).
- `platform`: `"android"` (later `"ios"`).
- `appVersion`: short string, optional.
- Returns `{ ok: true }`. Stores `devices/{uid}` with `fcmToken`, `platform`, `appVersion`, `createdAt`, `lastSeenAt` and `assistantsEnabled` (true on first registration).

### The push
An FCM **data** message, high priority, with exactly this data and nothing else:

```json
{ "kind": "inbox" }
```

There is no notification block and nothing about any reminder. On receipt, enqueue the expedited pull job. Also pull on app start and when the network comes back.

### `setAssistantsEnabled({ enabled })`
- `enabled`: boolean. When false, every assistant call is answered `disabled` by the server without waking the phone. Returns `{ ok: true, enabled }`.

## 2. Pairing and assistants

### `createPairingCode()`
Returns `{ code, expiresAt }`. `code` is 8 characters from `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (no 0, O, 1, I or L). It is valid for 10 minutes and works once; making a new code drops the previous unused one. Show it as `ABCD EFGH` if you like: the authorise page ignores spaces, hyphens and case.

Show the MCP URL with it: `https://<site>/mcp` (see section 6 of `cloud/README.md`).

### Connected assistants
Read them directly from Firestore (the rules allow a device to read its own documents): `devices/{uid}/agents/{agentId}`:

| field | meaning |
| --- | --- |
| `clientName` | what the assistant called itself (max 60 characters, untrusted: show it as plain text) |
| `clientId` | its OAuth client id |
| `createdAt` | when it was connected |
| `lastUsedAt` | last create or complete request, or null |
| `grantRevokedAt` | set if Do Cloud revoked its tokens (refresh token reuse); show "Connect again" |

`agentId` looks like `ag_` plus 16 hex digits. Use it as `Origin.sourceId` for everything this assistant does.

### `disconnectAgent({ agentId })`
Revokes the assistant's tokens and deletes its mirror, its events and its inbox requests. Returns `{ ok: true }`; `not-found` if there is no such assistant on this device.

## 3. The inbox

### `pullInbox()`
Returns pending, unexpired requests, oldest first, at most 50:

```json
{
  "requests": [
    {
      "requestId": "rq_0123456789abcdef01234567",
      "agentId": "ag_0123456789abcdef",
      "clientName": "Claude",
      "kind": "create_reminder",
      "args": { "text": "call Mum tomorrow 6pm", "dueAt": null, "repeat": null, "alertLevel": "normal" },
      "createdAt": 1764300000000,
      "expiresAt": 1764386400000
    }
  ]
}
```

`kind` and `args`:

| kind | args | apply with |
| --- | --- | --- |
| `create_reminder` | `text` (string, 1 to 500 characters, cleaned), `dueAt` (epoch ms or null: when null, parse `text` with Do's own parser), `repeat` (string or null, for `DateParser`), `alertLevel` (`normal`, `loud` or `alarm`) | `AgentFunctions.createReminder(agentId, AgentCreateRequest(text, dueAt, repeat, alertLevel))` |
| `complete_reminder` | `reminderUuid` (the reminder's `uuid`) | look up the local id by uuid, then `AgentFunctions.completeReminder(agentId, id)` |

The server has already checked the arguments, but the phone stays the authority: run them through `AgentRequests.plan` as usual. `alertLevel` uses the names people see, which `AgentRequests.alertLevel` accepts. A `complete_reminder` only ever names a reminder this assistant created (the server checks its mirror first); the phone should still refuse a reminder whose `sourceId` is not this `agentId`, with `not_found`.

### `completeRequest({ requestId, result })`
Send exactly one result per request:

Success (create or complete):
```json
{ "status": "done", "reminder": <Reminder> }
```

Failure:
```json
{ "status": "failed", "error": { "code": "limit_reached", "message": "optional, at most 300 characters" } }
```

Error codes map one to one from Spec 12's `AgentError`:

| AgentError | code |
| --- | --- |
| `LimitReached` | `limit_reached` |
| `NotFound` | `not_found` |
| `InvalidArgument` | `invalid_argument` |
| `Disabled` | `disabled` |

Never put a reminder title in `message` (Spec 12's messages don't). For `limit_reached`, the adapter's own message (which names the cap) is passed to the assistant as it is.

There is no "held" status from the phone: send `done` with the reminder, and the server answers `held_for_approval` when `reminder.approval` is `PENDING`.

Returns `{ ok: true, duplicate }`. A second completion of the same request is ignored (`duplicate: true`), so retries are safe. A result for a request that already expired is still accepted and reported to the assistant through `get_events`.

### `<Reminder>` (used by `completeRequest` and `syncAgentMirror`)

```json
{
  "uuid": "9b2f6c1e-3a0d-4c55-9d8e-6f1a2b3c4d5e",
  "title": "Call Mum",
  "dueAt": 1764324000000,
  "status": "active",
  "alertLevel": "STANDARD",
  "requestedAlertLevel": "CRITICAL",
  "approval": "PENDING",
  "repeat": "DAILY",
  "version": 3,
  "updatedAt": 1764300000000
}
```

| field | type | notes |
| --- | --- | --- |
| `uuid` | string | `Reminder.uuid`, letters, digits and hyphens, max 64 |
| `title` | string | max 1000 |
| `dueAt` | number | epoch ms |
| `status` | `active`, `done` or `deleted` | soft-deleted rows are `deleted` |
| `alertLevel` | `STANDARD`, `CRITICAL`, `PROMINENT` (or `normal`, `loud`, `alarm`) | what it rings at now |
| `requestedAlertLevel` | same, or null | what the assistant asked for |
| `approval` | `NONE`, `PENDING`, `APPROVED`, `DECLINED` | `Approval` name |
| `repeat` | string or null | `RepeatRule.token()`, max 200 |
| `version` | integer | `Reminder.version`; bump on every change |
| `updatedAt` | number | epoch ms of the last change |

Assistants see the alert levels as `normal`, `loud` and `alarm`, and `approval` in lower case.

## 4. The mirror

### `syncAgentMirror({ agentId, reminders, events })`
After any change to a reminder an assistant created (debounced), send that assistant's reminders and their new events. Only reminders whose `sourceId` is that `agentId`; never anyone else's.

- `reminders`: up to 200 `<Reminder>`s. The server only accepts reminders already in that assistant's mirror (that is, ones created through its own requests) and skips the rest. An older `version` never overwrites a newer one.
- `events`: up to 500, each:

```json
{ "id": 4711, "reminderUuid": "9b2f6c1e-...", "type": "FIRED", "at": 1764324000000 }
```

  `id` is `ReminderEvent.id` (number or string: it makes a resend harmless). `type` is an `EventType` name (`CREATED`, `FIRED`, `NAGGED`, `HELD`, `SNOOZED`, `ALERT_STOPPED`, `LEVEL_CHANGED`, `REPEAT_CHANGED`, `DONE`, `ADVANCED`, `DELETED`, `RESTORED`, `EDITED`, `IMPORTED`). Never send `detail`, the actor or anything with the title. Events for reminders not in the mirror are skipped.

Returns `{ ok: true, reminders, events, skipped }` (counts written and skipped); `not-found` if the assistant is not on this device.

## 5. Purchases

### `verifyPurchase({ platform: "play", packageName, productId, purchaseToken })`
- `packageName`: `com.evansappstudio.doapp`.
- `productId`: a Do subscription id (`do_unlimited` by default; the server's `PLAY_PRODUCT_IDS`).
- `purchaseToken`: from Play Billing.

The server reads the subscription from the Play Developer API, acknowledges it if Play says it is still pending, and stores `devices/{uid}/entitlement/current`, which the phone may also read directly. Returns the same record:

```json
{ "state": "active", "entitled": true, "expiryTime": 1766900000000, "autoRenewing": true, "productId": "do_unlimited", "lastCheckedAt": 1764300000000 }
```

`state` is one of `active`, `canceled` (still entitled until `expiryTime`), `grace_period` (entitled), `on_hold`, `paused`, `pending`, `expired`, `revoked` or `unknown`. Use `entitled` to switch Do Unlimited on or off. Play's notifications (RTDN) keep the record up to date afterwards. Errors: `invalid-argument` (wrong package or product), `not-found` (Play does not know the token), `unavailable` (Play could not be reached: keep the local Spec 10 result and try later).

## 6. What assistants see (for the Assistants screen help text)

Tools (MCP) and `POST /v1/<tool>` (REST, same JSON): `create_reminder`, `complete_reminder`, `list_reminders`, `get_events`. Results have `status`:

| status | meaning |
| --- | --- |
| `done` | the phone applied it; `reminder` is included |
| `held_for_approval` | made at Normal; the person must allow Loud or Alarm on the phone |
| `queued` | the phone didn't answer within 15 seconds; `request_id` is included; the result arrives later in `get_events` as a `request_result` event |
| `ok` | a read (`list_reminders`, `get_events`) |
| `error` | `error.code` is `limit_reached`, `not_found`, `invalid_argument`, `device_offline` (queued and expired after 24 hours), `disabled` or `rate_limited` |

`reminder_id` in every assistant-facing result is the reminder's `uuid`.
