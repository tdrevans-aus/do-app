import { settings } from "../core/config";
import { AgentFailure } from "../core/errors";
import { db, paths } from "../core/db";

// Fixed-window counters per assistant and per device: a minute window and a
// day window (UTC). The same limits apply to free and paid users.

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

interface Window {
  key: string;
  limit: number;
  size: number;
}

export async function checkRateLimit(deviceUid: string, agentId: string, now: number): Promise<void> {
  const windows: Window[] = [
    { key: `a_${agentId}`, limit: settings.agentPerMinute(), size: MINUTE },
    { key: `a_${agentId}`, limit: settings.agentPerDay(), size: DAY },
    { key: `d_${deviceUid}`, limit: settings.devicePerMinute(), size: MINUTE },
    { key: `d_${deviceUid}`, limit: settings.devicePerDay(), size: DAY },
  ];
  const col = db().collection(paths.rateLimits);
  const refs = windows.map((w) => {
    const index = Math.floor(now / w.size);
    return { w, index, ref: col.doc(`${w.key}_${w.size === MINUTE ? "m" : "d"}${index}`) };
  });
  const blocked = await db().runTransaction(async (tx) => {
    const snaps = await Promise.all(refs.map((r) => tx.get(r.ref)));
    for (let i = 0; i < refs.length; i++) {
      const entry = refs[i]!;
      const count = (snaps[i]!.data()?.count as number | undefined) ?? 0;
      if (count >= entry.w.limit) return Math.ceil(((entry.index + 1) * entry.w.size - now) / 1000);
    }
    refs.forEach((entry, i) => {
      const count = (snaps[i]!.data()?.count as number | undefined) ?? 0;
      tx.set(entry.ref, { count: count + 1, expireAt: (entry.index + 1) * entry.w.size });
    });
    return 0;
  });
  if (blocked > 0) {
    throw new AgentFailure("rate_limited", `Too many requests. Try again in ${blocked} seconds.`, blocked);
  }
}
