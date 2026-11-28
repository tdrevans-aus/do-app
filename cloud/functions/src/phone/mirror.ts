import { HttpsError } from "firebase-functions/v2/https";
import { limits } from "../core/config";
import { agentRef, db, paths } from "../core/db";
import { logInfo } from "../core/log";
import { isRecord } from "../core/text";
import { appendEvents, PHONE_EVENT_TYPES, readForAppend, type NewEvent } from "../agent/events";
import { parsePhoneReminder, type MirrorReminder } from "../agent/views";
import { touchDevice } from "./device";

/**
 * syncAgentMirror({ agentId, reminders, events }). Upserts this assistant's
 * mirror and appends events. Only reminders already in the mirror (that is,
 * created through this assistant's own requests) are accepted, so a phone bug
 * can never copy a person's other reminders into the cloud. An older version
 * never overwrites a newer one.
 */
export async function syncAgentMirror(
  uid: string,
  data: unknown,
  now: number,
): Promise<{ ok: true; reminders: number; events: number; skipped: number }> {
  if (!isRecord(data) || typeof data.agentId !== "string" || !/^ag_[0-9a-f]{16}$/.test(data.agentId)) {
    throw new HttpsError("invalid-argument", "agentId is required");
  }
  const agentId = data.agentId;
  const rawReminders = data.reminders ?? [];
  const rawEvents = data.events ?? [];
  if (!Array.isArray(rawReminders) || rawReminders.length > limits.maxSyncReminders) {
    throw new HttpsError("invalid-argument", `reminders must be a list of at most ${limits.maxSyncReminders}`);
  }
  if (!Array.isArray(rawEvents) || rawEvents.length > limits.maxSyncEvents) {
    throw new HttpsError("invalid-argument", `events must be a list of at most ${limits.maxSyncEvents}`);
  }
  const reminders: MirrorReminder[] = [];
  for (const raw of rawReminders) {
    const parsed = parsePhoneReminder(raw);
    if (!parsed) throw new HttpsError("invalid-argument", "A reminder is not valid");
    reminders.push(parsed);
  }
  const events: NewEvent[] = [];
  for (const raw of rawEvents) {
    if (!isRecord(raw)) throw new HttpsError("invalid-argument", "An event is not valid");
    const { id, reminderUuid, type, at } = raw;
    const idText = typeof id === "number" ? String(Math.trunc(id)) : id;
    if (typeof idText !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(idText)) throw new HttpsError("invalid-argument", "event.id is required");
    if (typeof reminderUuid !== "string" || !/^[A-Za-z0-9-]{1,64}$/.test(reminderUuid)) throw new HttpsError("invalid-argument", "event.reminderUuid is required");
    if (typeof type !== "string" || !PHONE_EVENT_TYPES.has(type.toUpperCase())) throw new HttpsError("invalid-argument", "event.type is not a known EventType");
    if (typeof at !== "number" || !Number.isFinite(at)) throw new HttpsError("invalid-argument", "event.at is required");
    events.push({ id: `p_${idText}`, type: type.toLowerCase(), at: Math.trunc(at), reminderUuid });
  }

  await touchDevice(uid, now);
  const agent = agentRef(uid, agentId);
  const mirror = agent.collection(paths.reminders);
  const uuids = [...new Set([...reminders.map((r) => r.uuid), ...events.map((e) => e.reminderUuid as string)])];

  const counts = await db().runTransaction(async (tx) => {
    const known = new Map<string, FirebaseFirestore.DocumentData>();
    if (uuids.length > 0) {
      const snaps = await tx.getAll(...uuids.map((u) => mirror.doc(u)));
      snaps.forEach((s) => {
        const d = s.data();
        if (d) known.set(s.id, d);
      });
    }
    const batch = await readForAppend(tx, uid, agentId, events.map((e) => e.id));
    if (!batch.exists) throw new HttpsError("not-found", "No such assistant on this device");
    let written = 0;
    let skipped = 0;
    for (const r of reminders) {
      const old = known.get(r.uuid);
      if (!old) {
        skipped++;
        continue;
      }
      const newer = r.version > (old.version ?? 0) || (r.version === old.version && r.updatedAt >= (old.updatedAt ?? 0));
      if (newer) {
        tx.set(mirror.doc(r.uuid), { ...r, syncedAt: now });
        written++;
      }
    }
    const accepted = events.filter((e) => known.has(e.reminderUuid as string));
    skipped += events.length - accepted.length;
    const appended = appendEvents(tx, batch, accepted, now);
    return { reminders: written, events: appended, skipped };
  });
  logInfo("mirror.synced", { agentId, ...counts });
  return { ok: true, ...counts };
}
