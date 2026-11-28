import { sha256 } from "./crypto";
import { db, paths } from "./db";

// Fixed-window counters in Firestore, checked and bumped in one transaction,
// so parallel requests can't slip past a limit. Used for the assistant API
// (per assistant and per device) and for the unauthenticated OAuth endpoints
// (per client IP and a global ceiling).

export interface LimitWindow {
  /** What is counted, for example "a_<agentId>" or "ip_reg_<hash>". */
  key: string;
  limit: number;
  sizeMs: number;
}

export function windowDocId(key: string, sizeMs: number, now: number): string {
  return `${key}_${sizeMs}_${Math.floor(now / sizeMs)}`;
}

/** A key for a client IP that doesn't store the address itself. */
export function ipKey(prefix: string, ip: string): string {
  return `${prefix}_${sha256(`ip:${ip}`).slice(0, 32)}`;
}

/**
 * Counts one request against every window. Returns 0 when it is allowed (and
 * counted), or the seconds until the first full window ends (nothing counted).
 */
export async function consume(windows: LimitWindow[], now: number): Promise<number> {
  const col = db().collection(paths.rateLimits);
  const entries = windows.map((w) => ({ w, index: Math.floor(now / w.sizeMs), ref: col.doc(windowDocId(w.key, w.sizeMs, now)) }));
  return db().runTransaction(async (tx) => {
    const snaps = await tx.getAll(...entries.map((e) => e.ref));
    const counts = snaps.map((s) => (s.data()?.count as number | undefined) ?? 0);
    for (let i = 0; i < entries.length; i++) {
      const e = entries[i]!;
      if (counts[i]! >= e.w.limit) return Math.max(1, Math.ceil(((e.index + 1) * e.w.sizeMs - now) / 1000));
    }
    entries.forEach((e, i) => tx.set(e.ref, { count: counts[i]! + 1, expireAt: (e.index + 1) * e.w.sizeMs }));
    return 0;
  });
}
