import { settings } from "../core/config";
import { randomId } from "../core/crypto";
import { agentRef, deviceRef, paths } from "../core/db";
import { AgentFailure } from "../core/errors";
import { logInfo } from "../core/log";
import type { Caller } from "../oauth/tokens";
import { pushClient, type Push } from "../push/push";
import { parseComplete, parseCreate, parseEvents, parseList } from "./args";
import { eventView } from "./events";
import { errorResult, okResult, parsePhoneFailure, queuedResult, reminderResult, type ToolResult } from "./outcome";
import { checkRateLimit } from "./rateLimit";
import { mirrorFrom, parsePhoneReminder, reminderView } from "./views";

// The four assistant tools (Spec 13, section D). Writes become inbox requests
// that the phone applies; reads come from the mirror.

export const TOOL_NAMES = ["create_reminder", "complete_reminder", "list_reminders", "get_events"] as const;
export type ToolName = (typeof TOOL_NAMES)[number];

export function isToolName(name: unknown): name is ToolName {
  return typeof name === "string" && (TOOL_NAMES as readonly string[]).includes(name);
}

export interface ToolDeps {
  now: () => number;
  push: Push;
  waitMs: number;
}

export function defaultDeps(): ToolDeps {
  return { now: () => Date.now(), push: pushClient(), waitMs: settings.waitMs() };
}

async function requireEnabled(caller: Caller): Promise<FirebaseFirestore.DocumentData> {
  const [device, agent] = await Promise.all([deviceRef(caller.deviceUid).get(), agentRef(caller.deviceUid, caller.agentId).get()]);
  if (!device.exists || !agent.exists) throw new AgentFailure("device_offline", "This assistant is no longer connected to Do. Connect it again.");
  const data = device.data() ?? {};
  if (data.assistantsEnabled === false) throw new AgentFailure("disabled");
  return data;
}

/** Waits for the phone to finish [requestId]. Null when the wait runs out. */
function waitForPhone(ref: FirebaseFirestore.DocumentReference, waitMs: number): Promise<FirebaseFirestore.DocumentData | null> {
  return new Promise((resolve) => {
    let settled = false;
    let unsubscribe: () => void = () => undefined;
    const finish = (value: FirebaseFirestore.DocumentData | null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      unsubscribe();
      resolve(value);
    };
    const timer = setTimeout(() => finish(null), waitMs);
    unsubscribe = ref.onSnapshot(
      (snap) => {
        const data = snap.data();
        if (data && data.status !== "pending") finish(data);
      },
      () => finish(null),
    );
  });
}

function resultFromInbox(requestId: string, data: FirebaseFirestore.DocumentData): ToolResult {
  if (data.status === "done") {
    const reminder = parsePhoneReminder(data.result?.reminder);
    if (reminder) return reminderResult(requestId, reminder);
    return okResult({ status: "done", request_id: requestId });
  }
  if (data.status === "expired") return errorResult("device_offline");
  const failure = parsePhoneFailure(data.result?.error);
  return failure ? errorResult(failure.code, failure.message ?? undefined) : errorResult("internal");
}

async function sendToPhone(caller: Caller, device: FirebaseFirestore.DocumentData, kind: string, args: Record<string, unknown>, deps: ToolDeps): Promise<ToolResult> {
  const now = deps.now();
  const requestId = `rq_${randomId(12)}`;
  const agent = (await agentRef(caller.deviceUid, caller.agentId).get()).data() ?? {};
  const ref = deviceRef(caller.deviceUid).collection(paths.inbox).doc(requestId);
  await ref.set({
    agentId: caller.agentId,
    clientName: agent.clientName ?? "Assistant",
    kind,
    args,
    status: "pending",
    createdAt: now,
    expiresAt: now + settings.requestTtlMs(),
    finishedAt: null,
    result: null,
  });
  await agentRef(caller.deviceUid, caller.agentId).update({ lastUsedAt: now });
  logInfo("inbox.created", { requestId, kind, agentId: caller.agentId });
  if (typeof device.fcmToken === "string" && device.fcmToken.length > 0) {
    const outcome = await deps.push.sendInbox(device.fcmToken);
    if (outcome === "unregistered") await deviceRef(caller.deviceUid).update({ fcmToken: null });
    logInfo("push.sent", { requestId, outcome });
  }
  const answered = await waitForPhone(ref, deps.waitMs);
  if (!answered) {
    logInfo("inbox.queued", { requestId });
    return queuedResult(requestId);
  }
  return resultFromInbox(requestId, answered);
}

async function createReminder(caller: Caller, raw: unknown, deps: ToolDeps): Promise<ToolResult> {
  const device = await requireEnabled(caller);
  const args = parseCreate(raw, deps.now());
  return sendToPhone(caller, device, "create_reminder", { ...args }, deps);
}

async function completeReminder(caller: Caller, raw: unknown, deps: ToolDeps): Promise<ToolResult> {
  const device = await requireEnabled(caller);
  const { reminderId } = parseComplete(raw);
  // Assistants may only touch reminders they created: those are exactly the ones in their mirror.
  const mirror = (await agentRef(caller.deviceUid, caller.agentId).collection(paths.reminders).doc(reminderId).get()).data();
  if (!mirror || mirror.status !== "active") throw new AgentFailure("not_found");
  return sendToPhone(caller, device, "complete_reminder", { reminderUuid: reminderId }, deps);
}

async function listReminders(caller: Caller, raw: unknown): Promise<ToolResult> {
  await requireEnabled(caller);
  const { status, limit } = parseList(raw);
  const col = agentRef(caller.deviceUid, caller.agentId).collection(paths.reminders);
  const query =
    status === "done"
      ? col.where("status", "==", "done").orderBy("updatedAt", "desc")
      : status === "active"
        ? col.where("status", "==", "active").orderBy("dueAt", "asc")
        : col.where("status", "in", ["active", "done"]).orderBy("dueAt", "asc");
  const snap = await query.limit(limit).get();
  const reminders = snap.docs.map((doc) => reminderView(mirrorFrom(doc.data())));
  return okResult({ status: "ok", reminders });
}

async function getEvents(caller: Caller, raw: unknown): Promise<ToolResult> {
  await requireEnabled(caller);
  const { since, limit } = parseEvents(raw);
  const snap = await agentRef(caller.deviceUid, caller.agentId)
    .collection(paths.events)
    .where("seq", ">", since)
    .orderBy("seq", "asc")
    .limit(limit + 1)
    .get();
  const docs = snap.docs.slice(0, limit);
  const events = docs.map((doc) => eventView(doc.data()));
  const last = docs.at(-1)?.data().seq as number | undefined;
  return okResult({ status: "ok", events, next_cursor: String(last ?? since), has_more: snap.size > limit });
}

/** Runs one tool for an authenticated assistant. Never throws for expected failures. */
export async function runTool(caller: Caller, name: ToolName, args: unknown, deps: ToolDeps = defaultDeps()): Promise<ToolResult> {
  try {
    await checkRateLimit(caller.deviceUid, caller.agentId, deps.now());
    switch (name) {
      case "create_reminder":
        return await createReminder(caller, args, deps);
      case "complete_reminder":
        return await completeReminder(caller, args, deps);
      case "list_reminders":
        return await listReminders(caller, args);
      case "get_events":
        return await getEvents(caller, args);
    }
  } catch (error) {
    if (error instanceof AgentFailure) {
      logInfo("tool.failed", { tool: name, code: error.code, agentId: caller.agentId });
      return errorResult(error.code, error.message, error.retryAfterSeconds);
    }
    throw error;
  }
}
