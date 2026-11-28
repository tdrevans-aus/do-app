import { HttpsError } from "firebase-functions/v2/https";
import { agentRef, db, deviceRef, paths } from "../core/db";
import { logInfo } from "../core/log";
import { isRecord } from "../core/text";
import { revokeGrant } from "../oauth/tokens";

/** Revokes an assistant's tokens and deletes its mirror, events and inbox requests. */
export async function removeAgent(uid: string, agentId: string, now: number): Promise<void> {
  const ref = agentRef(uid, agentId);
  const agent = (await ref.get()).data();
  if (agent?.grantId) await revokeGrant(agent.grantId as string, now);
  const grants = await db().collection(paths.grants).where("deviceUid", "==", uid).where("agentId", "==", agentId).get();
  for (const grant of grants.docs) if (grant.id !== agent?.grantId) await revokeGrant(grant.id, now);
  await db().recursiveDelete(ref);
  const inbox = await deviceRef(uid).collection(paths.inbox).where("agentId", "==", agentId).get();
  const writer = db().bulkWriter();
  inbox.docs.forEach((doc) => void writer.delete(doc.ref));
  await writer.close();
}

/** disconnectAgent({ agentId }) */
export async function disconnectAgent(uid: string, data: unknown, now: number): Promise<{ ok: true }> {
  if (!isRecord(data) || typeof data.agentId !== "string" || !/^ag_[0-9a-f]{16}$/.test(data.agentId)) {
    throw new HttpsError("invalid-argument", "agentId is required");
  }
  const exists = (await agentRef(uid, data.agentId).get()).exists;
  if (!exists) throw new HttpsError("not-found", "No such assistant on this device");
  await removeAgent(uid, data.agentId, now);
  logInfo("agent.disconnected", { agentId: data.agentId });
  return { ok: true };
}
