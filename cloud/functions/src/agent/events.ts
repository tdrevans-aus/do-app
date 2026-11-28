import type { DocumentReference, Transaction } from "firebase-admin/firestore";
import { agentRef, paths } from "../core/db";
import { iso } from "./views";

// The per-assistant event feed behind get_events. Each event gets the next
// number in the agent's eventSeq counter, which is the cursor. Reminder
// events come from the phone (syncAgentMirror); request_result events are
// written when an inbox request finishes, fails or expires.

/** Spec 9 EventType names the phone may send, stored lower case. */
export const PHONE_EVENT_TYPES = new Set([
  "CREATED",
  "EDITED",
  "FIRED",
  "NAGGED",
  "HELD",
  "SNOOZED",
  "ALERT_STOPPED",
  "LEVEL_CHANGED",
  "REPEAT_CHANGED",
  "DONE",
  "ADVANCED",
  "DELETED",
  "RESTORED",
  "IMPORTED",
]);

export interface NewEvent {
  /** Document id, which makes a resend harmless. */
  id: string;
  type: string;
  at: number;
  reminderUuid?: string | null;
  requestId?: string | null;
  status?: string | null;
  errorCode?: string | null;
}

export interface EventBatch {
  agent: DocumentReference;
  seq: number;
  existing: Set<string>;
  exists: boolean;
}

/** Reads what [appendEvents] needs. Call before any write in the transaction. */
export async function readForAppend(tx: Transaction, uid: string, agentId: string, ids: string[]): Promise<EventBatch> {
  const agent = agentRef(uid, agentId);
  const snap = await tx.get(agent);
  const existing = new Set<string>();
  if (ids.length > 0) {
    const refs = ids.map((id) => agent.collection(paths.events).doc(id));
    const docs = await tx.getAll(...refs);
    docs.forEach((doc) => {
      if (doc.exists) existing.add(doc.id);
    });
  }
  return { agent, seq: (snap.data()?.eventSeq as number | undefined) ?? 0, existing, exists: snap.exists };
}

/** Writes the events not already stored and bumps the counter. Returns how many were written. */
export function appendEvents(tx: Transaction, batch: EventBatch, events: NewEvent[], now: number): number {
  if (!batch.exists) return 0;
  let seq = batch.seq;
  let written = 0;
  for (const event of events) {
    if (batch.existing.has(event.id)) continue;
    batch.existing.add(event.id);
    seq += 1;
    written += 1;
    tx.set(batch.agent.collection(paths.events).doc(event.id), {
      seq,
      type: event.type,
      at: event.at,
      receivedAt: now,
      reminderUuid: event.reminderUuid ?? null,
      requestId: event.requestId ?? null,
      status: event.status ?? null,
      errorCode: event.errorCode ?? null,
    });
  }
  if (written > 0) tx.update(batch.agent, { eventSeq: seq });
  batch.seq = seq;
  return written;
}

export function eventView(data: FirebaseFirestore.DocumentData): Record<string, unknown> {
  const out: Record<string, unknown> = { cursor: String(data.seq), type: data.type, at: iso(data.at) };
  if (data.reminderUuid) out.reminder_id = data.reminderUuid;
  if (data.requestId) out.request_id = data.requestId;
  if (data.status) out.status = data.status;
  if (data.errorCode) out.error_code = data.errorCode;
  return out;
}
