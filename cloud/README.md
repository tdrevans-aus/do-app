# Do Cloud (Spec 13, server half)

A thin Firebase backend that lets assistants (ChatGPT, Claude and others) send requests to a person's phone over MCP or REST, with OAuth, and hear back; plus server-side Google Play purchase verification. The phone stays the authority for every reminder: the cloud only carries messages and keeps a small mirror of the reminders each assistant created.

- `functions/`: Cloud Functions (2nd gen), TypeScript, Node 22.
- `firestore.rules`, `firestore.indexes.json`, `firebase.json` (emulators, Hosting rewrites).
- `CONTRACT.md`: the JSON the Android client sends and receives.
- No real project id is committed. Everything runs against the Emulator Suite with the demo project `demo-do-cloud`.

## Run the tests

Needs Node 22 and Java 21.

```sh
cd cloud/functions
npm ci
npm test
```

`npm test` builds, starts the Auth, Firestore, Functions and Pub/Sub emulators with `firebase emulators:exec --project demo-do-cloud`, runs vitest inside them, then checks the captured function logs for reminder titles (AT-9). A small front proxy in the tests stands in for Firebase Hosting, sending every path on `127.0.0.1:5002` to the `api` function, as the Hosting rewrites do in production.

## What is where

| Path | What |
| --- | --- |
| `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp` | RFC 9728 protected resource metadata |
| `/.well-known/oauth-authorization-server` | RFC 8414 authorisation server metadata |
| `/oauth/register` | RFC 7591 dynamic client registration (public clients) |
| `/oauth/authorize` | The "Enter the code shown in Do" page (authorisation code with PKCE S256) |
| `/oauth/token` | `authorization_code` and rotating `refresh_token` grants |
| `/mcp` | MCP over Streamable HTTP (revision 2026-07-28, and initialize-based 2025-11-25, 2025-06-18, 2025-03-26) |
| `/v1/create_reminder`, `/v1/complete_reminder`, `/v1/list_reminders`, `/v1/get_events` | REST mirror, same tokens and JSON |

Callables for the phone: `registerDevice`, `setAssistantsEnabled`, `createPairingCode`, `pullInbox`, `completeRequest`, `syncAgentMirror`, `disconnectAgent`, `verifyPurchase`. Background: `playRtdn` (Pub/Sub) and `cleanup` (daily, 03:17 Perth time).

Settings are plain environment variables (see `functions/src/core/config.ts`); the emulator values are in `functions/.env.demo-do-cloud`. None of them is a secret.

## Before the first deploy (Tim)

Nothing here has been deployed and no Firebase or Google Cloud account was used. To go live:

1. **Firebase project.** Create a project (its id replaces `demo-do-cloud` in `.firebaserc` and the file name `functions/.env.<project-id>`). Create the Firestore database (Native mode) in **`australia-southeast1` (Sydney)**, the same region as the functions. A database location cannot be changed later: a wrong choice means a new project. Every function runs in `australia-southeast1` (`REGION` in `functions/src/core/config.ts`; the Hosting rewrites in `firebase.json` name the same region and a test keeps them in step). Sydney supports 2nd gen functions, Cloud Scheduler (for `cleanup`) and Eventarc Pub/Sub triggers (for `playRtdn`); it is a Tier 2 pricing region, slightly dearer than `us-central1`.
2. **Plan.** Upgrade to the Blaze (pay-as-you-go) plan: Cloud Functions need it.
3. **Budget alert.** In Google Cloud Billing, add a budget for the project with email alerts (for example at 50%, 90% and 100% of a small monthly amount). The functions have `maxInstances` caps and per-assistant rate limits, but a budget alert is the backstop.
4. **Authentication.** Turn on the Anonymous sign-in provider, and in Identity Platform (Authentication, Settings) set a quota on anonymous sign-ups per IP so nobody can mint unlimited devices (QA I1). Add the Android app to the project to get the values for the client's `cloudProjectId`, `cloudApiKey` and `cloudAppId` build config (do not commit `google-services.json`).
5. **Settings file.** Create `functions/.env.<project-id>` with at least `PLAY_PACKAGE_NAME=com.evansappstudio.doapp` and `PLAY_PRODUCT_IDS=do_unlimited` (the subscription product id). Set `PUBLIC_BASE_URL` only if you use a custom domain; by default it is `https://<project-id>.web.app`. Do not copy `PUSH_CLIENT` or `PLAY_CLIENT` from the demo file: those fakes work only in the emulator anyway.
6. **Play Developer API access.** In Google Cloud, enable the Google Play Android Developer API for the project. In Play Console, Users and permissions, invite the functions' runtime service account (for 2nd gen functions, the default compute service account `<project-number>-compute@developer.gserviceaccount.com`, or a dedicated one if you set it) with "View financial data, orders and cancellation survey responses" and "Manage orders and subscriptions". No key file is needed: the functions use Application Default Credentials. Never commit a service account key.
7. **Real-time developer notifications.** Create a Pub/Sub topic named `play-rtdn` (or set `RTDN_TOPIC`). Grant `google-play-developer-notifications@system.gserviceaccount.com` the Pub/Sub Publisher role on it, **and nobody else**: no `allUsers`, no `allAuthenticatedUsers`, no broad project roles (Editor, Pub/Sub Admin) for people or other service accounts who don't need them. Check the topic's IAM in the console after creating it. The handler re-reads Play before acting on any notification, so a forged one can't change state, but the topic should still accept only Play. In Play Console, Monetise, Monetisation setup, enter `projects/<project-id>/topics/play-rtdn`, then send a test notification (the function logs `do.rtdn.test`).
8. **Subscription product.** Create the `do_unlimited` subscription in Play Console (or use the id you put in `PLAY_PRODUCT_IDS`).
9. **Deploy** from `cloud/`: `firebase deploy --only functions,firestore,hosting`. This creates the Cloud Scheduler job for `cleanup` and the Pub/Sub trigger for `playRtdn`.
10. **The MCP URL for assistants:** `https://<project-id>.web.app/mcp` (or your custom domain plus `/mcp`). This is what the Assistants screen shows next to the pairing code.
11. **App Check.** Register the Android app with App Check (Play Integrity provider), watch the metrics for a week, then enforce it on the callables (`enforceAppCheck: true` in `callOptions` in `functions/src/index.ts`) and on Firestore. The OAuth pages and `/mcp` stay open: assistants call them, not the app.
12. **API key.** Restrict the Android API key to the package name and release SHA-1 and to the APIs the app uses.
13. **Client IP.** The OAuth rate limits take the client IP from `X-Forwarded-For`, `CLIENT_IP_HOPS` entries from the right (default 1, for Hosting in front of Cloud Run). After deploy, log one request's header (temporarily) or call `/oauth/register` from a known address and check which entry is the real client; change `CLIENT_IP_HOPS` if needed. The global ceilings don't depend on it.
14. **Checks after deploy.** Send a malformed JSON body (`content-type: application/json`) to `https://<site>/mcp` and confirm the 400 has no stack trace (`NODE_ENV=production` is set in `functions/.env`; the platform answers before Do's code). Pair from a real browser (Chrome, Safari, Firefox) with an https and a loopback or custom-scheme client: the browser must land back on the assistant. Add log-based alerts on `do.oauth.limited` with `scope: global`, `do.pairing.rejected` and `do.oauth.reuse_revoked`.
15. **Before release:** privacy policy and the Play data-safety form must say that reminder titles from assistants pass through Do Cloud (kept as in Spec 13 section G).
