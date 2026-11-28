import { settings } from "../core/config";
import { db, paths } from "../core/db";
import { logInfo, logWarn } from "../core/log";
import { isRecord } from "../core/text";
import { entitlementFrom } from "./entitlement";
import { PlayError, type PlayClient } from "./play";
import { acknowledgeIfNeeded, purchaseRef, storeEntitlement } from "./verify";

// Real-time developer notifications (Pub/Sub). The notification only says
// that something changed; the state is always read back from the Play API.

const SUBSCRIPTION_REVOKED = 12;

export interface RtdnOutcome {
  handled: boolean;
  reason?: string;
  state?: string;
}

function seenRef(messageId: string) {
  return db().collection(paths.rtdnSeen).doc(messageId.replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 100));
}

async function deviceFor(tokens: Array<string | null | undefined>): Promise<{ uid: string; productId: string } | null> {
  for (const token of tokens) {
    if (!token) continue;
    const data = (await purchaseRef(token).get()).data();
    if (data) return { uid: data.deviceUid, productId: data.productId };
  }
  return null;
}

/**
 * Handles one notification. Pub/Sub can deliver a message more than once, so
 * each messageId is handled once; it is marked only after it succeeds, and a
 * thrown error makes Pub/Sub retry.
 */
export async function handleRtdn(payload: unknown, messageId: string, now: number, play: PlayClient): Promise<RtdnOutcome> {
  if (messageId && (await seenRef(messageId).get()).exists) return { handled: false, reason: "duplicate" };
  const outcome = await handleOnce(payload, now, play);
  if (messageId) await seenRef(messageId).set({ at: now });
  return outcome;
}

async function handleOnce(payload: unknown, now: number, play: PlayClient): Promise<RtdnOutcome> {
  if (!isRecord(payload)) return { handled: false, reason: "not_json" };
  if (payload.packageName !== settings.playPackageName()) return { handled: false, reason: "other_package" };
  if (isRecord(payload.testNotification)) {
    logInfo("rtdn.test", {});
    return { handled: true, reason: "test" };
  }
  const packageName = payload.packageName as string;

  if (isRecord(payload.voidedPurchaseNotification)) {
    const token = payload.voidedPurchaseNotification.purchaseToken;
    if (typeof token !== "string") return { handled: false, reason: "no_token" };
    const owner = await deviceFor([token]);
    if (!owner) return { handled: false, reason: "unknown_purchase" };
    await storeEntitlement(owner.uid, { state: "revoked", entitled: false, expiryTime: null, autoRenewing: false, productId: owner.productId }, now, "rtdn");
    logInfo("rtdn.voided", {});
    return { handled: true, state: "revoked" };
  }

  const note = payload.subscriptionNotification;
  if (!isRecord(note)) return { handled: false, reason: "not_subscription" };
  const token = note.purchaseToken;
  const type = typeof note.notificationType === "number" ? note.notificationType : Number(note.notificationType);
  if (typeof token !== "string" || token.length === 0) return { handled: false, reason: "no_token" };

  let purchase;
  try {
    purchase = await play.getSubscription(packageName, token);
  } catch (error) {
    if (error instanceof PlayError && error.kind === "not_found") return { handled: false, reason: "play_not_found" };
    throw error; // Pub/Sub retries.
  }
  const owner = await deviceFor([token, purchase.linkedPurchaseToken]);
  const productId =
    owner?.productId ?? (purchase.lineItems ?? []).map((it) => it.productId).find((id) => id && settings.playProductIds().includes(id)) ?? null;
  if (!productId) return { handled: false, reason: "other_product" };
  // Acknowledge even before the phone has checked in, so Play does not refund it after 3 days.
  await acknowledgeIfNeeded(play, packageName, productId, token, purchase);
  if (!owner) {
    logWarn("rtdn.unknown_purchase", { type });
    return { handled: false, reason: "unknown_purchase" };
  }
  if (purchase.linkedPurchaseToken) {
    await purchaseRef(token).set({ deviceUid: owner.uid, packageName, productId, updatedAt: now });
    await purchaseRef(purchase.linkedPurchaseToken).delete();
  }
  const entitlement = entitlementFrom(purchase, productId, now, type === SUBSCRIPTION_REVOKED);
  await storeEntitlement(owner.uid, entitlement, now, "rtdn");
  logInfo("rtdn.updated", { type, state: entitlement.state, entitled: entitlement.entitled });
  return { handled: true, state: entitlement.state };
}
