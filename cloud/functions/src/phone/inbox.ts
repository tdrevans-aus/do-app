import { HttpsError } from "firebase-functions/v2/https";
import { agentRef, db, deviceRef, paths } from "../core/db";
import { logInfo } from "../core/log";
import { isRecord } from "../core/text";
import { appendEvents, readForAppend } from "../agent/events";
import { parsePhoneFailure } from "../agent/outcome";
import { parsePhoneReminder } from "../agent/views";
import { touchDevice } from "./device";

// The phone's side of the exchange (Spec 13, section E).

const PULL_LIMIT = 50;

/** pullInbox(): pending, unexpired requests for this device, oldest first. */
export async function pullInbox(uid: string, now: number): Promise<{ requests: Record<string, unknown>[] }> {
  await touchDevice(uid, now);
  const snap = await deviceRef(uid)
    .collection(paths.inbox)
    .where("status", "==", "pending")
    .orderBy("createdAt", "asc")
    .limit(PULL_LIMIT)
    .get();
  const requests = snap.docs
    .filter((doc) => (doc.data().expiresAt as number) > now)
    .map((doc) => {
      const d = doc.data();
      return {
        requestId: doc.id,
        agentId: d.agentId,
        clientName: d.clientName,
        kind: d.kind,
        args: d.args,
        createdAt: d.createdAt,
        expiresAt: d.expiresAt,
      };
    });
  logInfo("inbox.pulled", { count: requests.length });
  return { requests };
}

/**
 * completeRequest({ requestId, result }). result is { status: "done", reminder }
 * or { status: "failed", error: { code, message? } }. Finishing a request twice
 * is harmless. A result that arrives after the request expired is still kept,
 * because the phone has applied it.
 */
export async function completeRequest(uid: string, data: unknown, now: number): Promise<{ ok: true; duplicate: boolean }> {
  if (!isRecord(data) || typeof data.requestId !== "string" || !/^rq_[0-9a-f]{24}$/.test(data.requestId)) {
    throw new HttpsError("invalid-argument", "requestId is required");
  }
  const result = data.result;
  if (!isRecord(result)) throw new HttpsError("invalid-argument", "result is required");
  let stored: Record<string, unknown>;
  let reminder = null;
  if (result.status === "done") {
    reminder = parsePhoneReminder(result.reminder);
    if (!reminder) throw new HttpsError("invalid-argument", "result.reminder is missing or not valid");
    stored = { status: "done", reminder };
  } else if (result.status === "failed") {
    const failure = parsePhoneFailure(result.error);
    if (!failure) throw new HttpsError("invalid-argument", "result.error.code must be limit_reached, not_found, invalid_argument or disabled");
    stored = { status: "failed", error: failure };
  } else {
    throw new HttpsError("invalid-argument", "result.status must be done or failed");
  }

  const requestId = data.requestId;
  const ref = deviceRef(uid).collection(paths.inbox).doc(requestId);
  const duplicate = await db().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const request = snap.data();
    if (!request) throw new HttpsError("not-found", "No such request");
    if (request.status === "done" || request.status === "failed") return true;
    const agentId = request.agentId as string;
    const mirrorRef = reminder ? agentRef(uid, agentId).collection(paths.reminders).doc(reminder.uuid) : null;
    const mirrorSnap = mirrorRef ? await tx.get(mirrorRef) : null;
    const batch = await readForAppend(tx, uid, agentId, [`r_${requestId}`]);

    tx.update(ref, { status: stored.status, result: stored, finishedAt: now });
    if (batch.exists && reminder && mirrorRef) {
      const older = mirrorSnap?.data();
      if (!older || (older.version as number) <= reminder.version) tx.set(mirrorRef, { ...reminder, syncedAt: now });
    }
    const failure = stored.status === "failed" ? (stored.error as { code: string }) : null;
    appendEvents(
      tx,
      batch,
      [
        {
          id: `r_${requestId}`,
          type: "request_result",
          at: now,
          requestId,
          reminderUuid: reminder?.uuid ?? null,
          status: failure ? "error" : reminder?.approval === "pending" ? "held_for_approval" : "done",
          errorCode: failure?.code ?? null,
        },
      ],
      now,
    );
    return false;
  });
  logInfo("inbox.completed", { requestId, status: String(stored.status), duplicate });
  return { ok: true, duplicate };
}
