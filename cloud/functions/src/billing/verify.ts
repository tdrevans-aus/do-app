import { HttpsError } from "firebase-functions/v2/https";
import { settings } from "../core/config";
import { sha256 } from "../core/crypto";
import { db, deviceRef, paths } from "../core/db";
import { logInfo } from "../core/log";
import { isRecord } from "../core/text";
import { entitlementFrom, lineItemFor, needsAcknowledge, type Entitlement } from "./entitlement";
import { PlayError, type PlayClient, type SubscriptionPurchaseV2 } from "./play";
import { accountIdFor, boundAccountId, boundTo } from "./binding";

export function purchaseRef(purchaseToken: string) {
  return db().collection(paths.purchases).doc(sha256(`play:${purchaseToken}`));
}

export function entitlementRef(uid: string) {
  return deviceRef(uid).collection(paths.entitlement).doc("current");
}

export interface EntitlementView extends Entitlement {
  lastCheckedAt: number;
}

/** Acknowledges if Play says it is still pending. Returns whether this call acknowledged it. */
export async function acknowledgeIfNeeded(play: PlayClient, packageName: string, productId: string, token: string, purchase: SubscriptionPurchaseV2): Promise<boolean> {
  if (!needsAcknowledge(purchase)) return false;
  await play.acknowledge(packageName, productId, token);
  logInfo("play.acknowledged", { productId });
  return true;
}

export function tokenHash(purchaseToken: string): string {
  return sha256(`play:${purchaseToken}`);
}

export async function storeEntitlement(uid: string, entitlement: Entitlement, now: number, source: string, purchaseToken?: string): Promise<EntitlementView> {
  const view = { ...entitlement, lastCheckedAt: now };
  await entitlementRef(uid).set({ ...view, source, purchaseTokenHash: purchaseToken ? tokenHash(purchaseToken) : null });
  return view;
}

/**
 * When [purchaseToken] stops belonging to [uid] (it moved to another
 * installation, or a newer token replaced it there), that device's
 * entitlement from it is switched off.
 */
export async function clearIfFrom(uid: string, purchaseToken: string, now: number, state: "transferred" | "expired"): Promise<void> {
  const ref = entitlementRef(uid);
  const current = (await ref.get()).data();
  if (!current || current.purchaseTokenHash !== tokenHash(purchaseToken)) return;
  await ref.set({ ...current, state, entitled: false, autoRenewing: false, lastCheckedAt: now, source: "transfer" });
  logInfo("play.cleared", { state });
}

/** verifyPurchase({ platform: "play", packageName, productId, purchaseToken, obfuscatedAccountId }) */
export async function verifyPurchase(uid: string, data: unknown, now: number, play: PlayClient): Promise<EntitlementView> {
  if (!isRecord(data)) throw new HttpsError("invalid-argument", "Expected an object");
  const { platform, packageName, productId, purchaseToken, obfuscatedAccountId } = data;
  if (platform !== "play") throw new HttpsError("invalid-argument", "platform must be play");
  if (packageName !== settings.playPackageName()) throw new HttpsError("invalid-argument", "packageName is not Do's");
  if (typeof productId !== "string" || !settings.playProductIds().includes(productId)) {
    throw new HttpsError("invalid-argument", "productId is not a Do subscription");
  }
  if (typeof purchaseToken !== "string" || purchaseToken.length === 0 || purchaseToken.length > 4096) {
    throw new HttpsError("invalid-argument", "purchaseToken is required");
  }
  let purchase: SubscriptionPurchaseV2;
  try {
    purchase = await play.getSubscription(packageName, purchaseToken);
  } catch (error) {
    if (error instanceof PlayError && error.kind === "not_found") throw new HttpsError("not-found", "Play does not know this purchase");
    throw new HttpsError("unavailable", "Play could not be reached; try again later");
  }
  if (!lineItemFor(purchase, productId)) throw new HttpsError("invalid-argument", "The purchase is for another product");
  const expectedId = accountIdFor(uid);
  if (obfuscatedAccountId !== expectedId) {
    throw new HttpsError("invalid-argument", "obfuscatedAccountId must be the id Do derives from this installation");
  }

  // Strict binding: no purchases were made before binding existed, so an unbound one is refused too.
  if (!boundTo(purchase, uid)) {
    logInfo("play.other_device", { bound: boundAccountId(purchase) !== null });
    const base = entitlementFrom(purchase, productId, now);
    const refused: Entitlement = { ...base, state: "other_device", entitled: false, autoRenewing: false };
    // Recorded for this device only if that can't hide an entitlement it has from its own purchase.
    const current = (await entitlementRef(uid).get()).data();
    if (!current || !current.entitled || current.purchaseTokenHash === tokenHash(purchaseToken)) {
      return storeEntitlement(uid, refused, now, "verify", purchaseToken);
    }
    return { ...refused, lastCheckedAt: now };
  }

  const acknowledged = await acknowledgeIfNeeded(play, packageName, productId, purchaseToken, purchase);
  const entitlement = entitlementFrom(purchase, productId, now);
  const ref = purchaseRef(purchaseToken);
  const previous = (await ref.get()).data();
  await ref.set({ deviceUid: uid, packageName, productId, accountId: expectedId, updatedAt: now });
  if (previous && previous.deviceUid !== uid) await clearIfFrom(previous.deviceUid as string, purchaseToken, now, "transferred");
  if (purchase.linkedPurchaseToken) {
    const linked = (await purchaseRef(purchase.linkedPurchaseToken).get()).data();
    if (linked) {
      if (linked.deviceUid !== uid) await clearIfFrom(linked.deviceUid as string, purchase.linkedPurchaseToken, now, "transferred");
      await purchaseRef(purchase.linkedPurchaseToken).delete();
    }
  }
  const view = await storeEntitlement(uid, entitlement, now, "verify", purchaseToken);
  logInfo("play.verified", { state: entitlement.state, entitled: entitlement.entitled, acknowledged });
  return view;
}
