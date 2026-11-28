import { HttpsError } from "firebase-functions/v2/https";
import { settings } from "../core/config";
import { sha256 } from "../core/crypto";
import { db, deviceRef, paths } from "../core/db";
import { logInfo } from "../core/log";
import { isRecord } from "../core/text";
import { entitlementFrom, lineItemFor, needsAcknowledge, type Entitlement } from "./entitlement";
import { PlayError, type PlayClient, type SubscriptionPurchaseV2 } from "./play";

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

export async function storeEntitlement(uid: string, entitlement: Entitlement, now: number, source: string): Promise<EntitlementView> {
  const view = { ...entitlement, lastCheckedAt: now };
  await entitlementRef(uid).set({ ...view, source });
  return view;
}

/** verifyPurchase({ platform: "play", packageName, productId, purchaseToken }) */
export async function verifyPurchase(uid: string, data: unknown, now: number, play: PlayClient): Promise<EntitlementView> {
  if (!isRecord(data)) throw new HttpsError("invalid-argument", "Expected an object");
  const { platform, packageName, productId, purchaseToken } = data;
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

  const acknowledged = await acknowledgeIfNeeded(play, packageName, productId, purchaseToken, purchase);
  const entitlement = entitlementFrom(purchase, productId, now);
  const batch = db().batch();
  batch.set(purchaseRef(purchaseToken), { deviceUid: uid, packageName, productId, updatedAt: now });
  if (purchase.linkedPurchaseToken) batch.delete(purchaseRef(purchase.linkedPurchaseToken));
  await batch.commit();
  const view = await storeEntitlement(uid, entitlement, now, "verify");
  logInfo("play.verified", { state: entitlement.state, entitled: entitlement.entitled, acknowledged });
  return view;
}
