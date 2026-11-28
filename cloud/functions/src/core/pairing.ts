import { limits } from "./config";
import { PAIRING_ALPHABET, randomCode, sha256 } from "./crypto";
import { db, deviceRef, paths } from "./db";

// Pairing codes (Spec 13, section C). A code is 8 symbols from PAIRING_ALPHABET.
// The first 4 are a selector that finds the record and the last 4 are the
// secret part, so a wrong code can be charged to the code it was meant to be:
// after 5 wrong tries that code is burnt. Only hashes are stored.

export const CODE_LENGTH = 8;
const SELECTOR_LENGTH = 4;

export function normaliseCode(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const code = raw.replace(/[\s-]+/g, "").toUpperCase();
  if (code.length !== CODE_LENGTH) return null;
  for (const ch of code) if (!PAIRING_ALPHABET.includes(ch)) return null;
  return code;
}

function selectorId(code: string): string {
  return sha256(`selector:${code.slice(0, SELECTOR_LENGTH)}`);
}

function codeHash(code: string): string {
  return sha256(`pairing:${code}`);
}

export interface NewPairingCode {
  code: string;
  expiresAt: number;
}

/** Makes a fresh code for [uid] and drops any older unused one. */
export async function createPairingCode(uid: string, now: number): Promise<NewPairingCode> {
  const codes = db().collection(paths.pairingCodes);
  const old = await codes.where("deviceUid", "==", uid).get();
  const batch = db().batch();
  old.docs.forEach((doc) => batch.delete(doc.ref));
  await batch.commit();

  for (let attempt = 0; attempt < 8; attempt++) {
    const code = randomCode(CODE_LENGTH);
    const ref = codes.doc(selectorId(code));
    const expiresAt = now + limits.pairingCodeMs;
    const made = await db().runTransaction(async (tx) => {
      const existing = await tx.get(ref);
      const device = await tx.get(deviceRef(uid));
      const data = existing.data();
      if (existing.exists && data && !data.used && !data.burnt && data.expiresAt > now) return false;
      tx.set(ref, { deviceUid: uid, codeHash: codeHash(code), createdAt: now, expiresAt, tries: 0, used: false, burnt: false });
      tx.set(deviceRef(uid), device.exists ? { lastSeenAt: now } : { createdAt: now, lastSeenAt: now, assistantsEnabled: true }, { merge: true });
      return true;
    });
    if (made) return { code, expiresAt };
  }
  throw new Error("Could not make a unique pairing code");
}

export type RedeemResult =
  | { ok: true; deviceUid: string }
  | { ok: false; reason: "malformed" | "unknown" | "expired" | "wrong" | "burnt"; triesLeft?: number };

/** Checks a code typed on the authorise page. A match uses the code up. */
export async function redeemPairingCode(raw: unknown, now: number): Promise<RedeemResult> {
  const code = normaliseCode(raw);
  if (!code) return { ok: false, reason: "malformed" };
  const ref = db().collection(paths.pairingCodes).doc(selectorId(code));
  return db().runTransaction(async (tx): Promise<RedeemResult> => {
    const snap = await tx.get(ref);
    const data = snap.data();
    if (!snap.exists || !data) return { ok: false, reason: "unknown" };
    if (data.burnt) return { ok: false, reason: "burnt" };
    if (data.used || data.expiresAt <= now) return { ok: false, reason: "expired" };
    if (data.codeHash !== codeHash(code)) {
      const tries = (data.tries ?? 0) + 1;
      const burnt = tries >= limits.pairingTries;
      tx.update(ref, { tries, burnt });
      return burnt ? { ok: false, reason: "burnt" } : { ok: false, reason: "wrong", triesLeft: limits.pairingTries - tries };
    }
    tx.update(ref, { used: true, usedAt: now });
    return { ok: true, deviceUid: data.deviceUid as string };
  });
}
