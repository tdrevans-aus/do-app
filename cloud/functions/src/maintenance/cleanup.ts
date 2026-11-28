import type { Query } from "firebase-admin/firestore";
import { limits } from "../core/config";
import { auth, db, paths } from "../core/db";
import { logInfo } from "../core/log";
import { appendEvents, readForAppend } from "../agent/events";
import { revokeGrant } from "../oauth/tokens";

// The daily clean-up (Spec 13, section G). [now] is passed in so tests can
// run it on a fake clock.

const DAY = 24 * 60 * 60 * 1000;
const PAGE = 300;

async function deleteAll(query: Query): Promise<number> {
  let total = 0;
  for (;;) {
    const snap = await query.limit(PAGE).get();
    if (snap.empty) return total;
    const writer = db().bulkWriter();
    snap.docs.forEach((doc) => void writer.delete(doc.ref));
    await writer.close();
    total += snap.size;
    if (snap.size < PAGE) return total;
  }
}

/** Marks pending requests past their 24 hours as expired and tells the assistant (device_offline). */
export async function expireRequests(now: number): Promise<number> {
  let total = 0;
  for (;;) {
    const snap = await db()
      .collectionGroup(paths.inbox)
      .where("status", "==", "pending")
      .where("expiresAt", "<=", now)
      .limit(PAGE)
      .get();
    for (const doc of snap.docs) {
      const uid = doc.ref.parent.parent?.id;
      if (!uid) continue;
      await db().runTransaction(async (tx) => {
        const fresh = (await tx.get(doc.ref)).data();
        if (!fresh || fresh.status !== "pending") return;
        const id = `r_${doc.id}`;
        const batch = await readForAppend(tx, uid, fresh.agentId as string, [id]);
        tx.update(doc.ref, { status: "expired", finishedAt: now, result: { status: "expired", error: { code: "device_offline" } } });
        appendEvents(tx, batch, [{ id, type: "request_result", at: now, requestId: doc.id, status: "error", errorCode: "device_offline" }], now);
      });
      total++;
    }
    if (snap.size < PAGE) return total;
  }
}

async function deleteDevice(uid: string, now: number): Promise<void> {
  const grants = await db().collection(paths.grants).where("deviceUid", "==", uid).get();
  for (const grant of grants.docs) {
    await revokeGrant(grant.id, now);
    await grant.ref.delete();
  }
  await deleteAll(db().collection(paths.purchases).where("deviceUid", "==", uid));
  await deleteAll(db().collection(paths.pairingCodes).where("deviceUid", "==", uid));
  await db().recursiveDelete(db().collection(paths.devices).doc(uid));
  await auth()
    .deleteUser(uid)
    .catch(() => undefined);
}

export interface CleanupReport {
  expired: number;
  inbox: number;
  events: number;
  devices: number;
  tokens: number;
  other: number;
}

export async function runCleanup(now: number): Promise<CleanupReport> {
  const expired = await expireRequests(now);
  const inbox = await deleteAll(db().collectionGroup(paths.inbox).where("finishedAt", "<=", now - limits.inboxKeepMs));
  // By the server's receivedAt, never the phone's `at`: a phone with a wrong clock can't keep events for ever.
  const events = await deleteAll(db().collectionGroup(paths.events).where("receivedAt", "<=", now - limits.eventKeepMs));

  let devices = 0;
  for (;;) {
    const idle = await db().collection(paths.devices).where("lastSeenAt", "<=", now - limits.deviceIdleMs).limit(50).get();
    for (const doc of idle.docs) await deleteDevice(doc.id, now);
    devices += idle.size;
    if (idle.size < 50) break;
  }

  const tokens = await deleteAll(db().collection(paths.tokens).where("expiresAt", "<=", now));
  let other = 0;
  other += await deleteAll(db().collection(paths.authCodes).where("expiresAt", "<=", now));
  other += await deleteAll(db().collection(paths.authRequests).where("expiresAt", "<=", now));
  other += await deleteAll(db().collection(paths.pairingCodes).where("expiresAt", "<=", now));
  other += await deleteAll(db().collection(paths.rateLimits).where("expireAt", "<=", now));
  other += await deleteAll(db().collection(paths.rtdnSeen).where("at", "<=", now - 7 * DAY));
  other += await deleteAll(db().collection(paths.grants).where("revokedAt", "<=", now - limits.eventKeepMs));
  other += await deleteAll(db().collection(paths.oauthClients).where("usedAt", "==", null).where("createdAt", "<=", now - DAY));
  other += await deleteAll(db().collection(paths.oauthClients).where("kind", "==", "metadata").where("usedAt", "<=", now - limits.eventKeepMs));

  const report = { expired, inbox, events, devices, tokens, other };
  logInfo("cleanup.done", { ...report });
  return report;
}
