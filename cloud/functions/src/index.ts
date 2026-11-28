import { onCall, onRequest, HttpsError, type CallableRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import { REGION, settings } from "./core/config";
import { createPairingCode as makePairingCode } from "./core/pairing";
import { logError, logInfo } from "./core/log";
import { handle } from "./http/app";
import type { HttpRequest } from "./http/types";
import { registerDevice as register, setAssistantsEnabled as setEnabled } from "./phone/device";
import { completeRequest as complete, pullInbox as pull } from "./phone/inbox";
import { syncAgentMirror as sync } from "./phone/mirror";
import { disconnectAgent as disconnect } from "./phone/agents";
import { verifyPurchase as verify } from "./billing/verify";
import { playClient } from "./billing/play";
import { handleRtdn } from "./billing/rtdn";
import { runCleanup } from "./maintenance/cleanup";

// Do Cloud v1 (Spec 13). Every export is a Cloud Function (2nd gen).

const callOptions = { region: REGION, memory: "256MiB" as const, maxInstances: 10 };

function deviceUid(request: CallableRequest<unknown>): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");
  return uid;
}

/** Wraps a device callable: auth required, errors logged without their messages. */
function deviceCallable<T>(name: string, run: (uid: string, data: unknown, now: number) => Promise<T>) {
  return onCall(callOptions, async (request) => {
    const uid = deviceUid(request);
    try {
      return await run(uid, request.data, Date.now());
    } catch (error) {
      if (error instanceof HttpsError) throw error;
      logError(`${name}.failed`, error);
      throw new HttpsError("internal", "Something went wrong");
    }
  });
}

export const registerDevice = deviceCallable("registerDevice", register);
export const setAssistantsEnabled = deviceCallable("setAssistantsEnabled", setEnabled);
export const createPairingCode = deviceCallable("createPairingCode", (uid, _data, now) => makePairingCode(uid, now));
export const pullInbox = deviceCallable("pullInbox", (uid, _data, now) => pull(uid, now));
export const completeRequest = deviceCallable("completeRequest", complete);
export const syncAgentMirror = deviceCallable("syncAgentMirror", sync);
export const disconnectAgent = deviceCallable("disconnectAgent", disconnect);
export const verifyPurchase = deviceCallable("verifyPurchase", (uid, data, now) => verify(uid, data, now, playClient()));

/** The public site: OAuth, the MCP endpoint and the REST mirror. Hosting rewrites every path here. */
export const api = onRequest({ region: REGION, memory: "256MiB", timeoutSeconds: 60, maxInstances: 20, invoker: "public" }, async (req, res) => {
  const headers: Record<string, string | undefined> = {};
  for (const [key, value] of Object.entries(req.headers)) headers[key.toLowerCase()] = Array.isArray(value) ? value.join(", ") : value;
  const query: Record<string, string | undefined> = {};
  for (const [key, value] of Object.entries(req.query)) if (typeof value === "string") query[key] = value;
  const request: HttpRequest = {
    method: req.method.toUpperCase(),
    path: req.path,
    headers,
    query,
    body: req.body,
    rawBody: req.rawBody ? req.rawBody.toString("utf8") : undefined,
  };
  const response = await handle(request, { now: () => Date.now() });
  res.status(response.status);
  for (const [key, value] of Object.entries(response.headers ?? {})) res.setHeader(key, value);
  if (response.body === undefined) res.end();
  else if (typeof response.body === "string") res.send(response.body);
  else res.send(JSON.stringify(response.body));
});

/** Play real-time developer notifications. */
export const playRtdn = onMessagePublished({ topic: settings.rtdnTopic(), region: REGION, retry: true }, async (event) => {
  const message = event.data.message;
  let payload: unknown;
  try {
    payload = message.json;
  } catch {
    payload = undefined;
  }
  const outcome = await handleRtdn(payload, message.messageId, Date.now(), playClient());
  logInfo("rtdn.handled", { handled: outcome.handled, reason: outcome.reason, state: outcome.state });
});

/** Daily clean-up (section G), at a quiet hour in Perth. */
export const cleanup = onSchedule({ schedule: "every day 03:17", timeZone: "Australia/Perth", region: REGION }, async () => {
  await runCleanup(Date.now());
});
