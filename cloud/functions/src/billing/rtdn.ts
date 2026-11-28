import { settings } from "../core/config";
import { db, paths } from "../core/db";
import { logInfo, logWarn } from "../core/log";
import { isRecord } from "../core/text";
import { entitlementFrom } from "./entitlement";
import { PlayError, type PlayClient } from "./play";
import { acknowledgeIfNeeded, clearIfFrom, purchaseRef, storeEntitlement } from "./verify";
import { accountIdFor, boundTo } from "./binding";

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

interface Owner {
  uid: string;
  productId: string;
  token: string;
}

async function deviceFor(tokens: Array<string | null | undefined>): Promise<Owner | null> {
  for (const token of tokens) {
    if (!token) continue;
    const data = (await purchaseRef(token).get()).data();
    if (data) return { uid: data.deviceUid, productId: data.productId, token };
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
    const voided = payload.voidedPurchaseNotification;
    const token = voided.purchaseToken;
    if (typeof token !== "string") return { handled: false, reason: "no_token" };
    if (Number(voided.productType) !== 1) return { handled: false, reason: "not_subscription" };
    const owner = await deviceFor([token]);
    if (!owner) return { handled: false, reason: "unknown_purchase" };
    // Re-check with Play before acting (QA L6): a forged void for a live subscription changes nothing.
    let stillEntitled = false;
    try {
      const purchase = await play.getSubscription(packageName, token);
      stillEntitled = entitlementFrom(purchase, owner.productId, now).entitled;
    } catch (error) {
      if (!(error instanceof PlayError && error.kind === "not_found")) throw error; // Pub/Sub retries.
    }
    if (stillEntitled) {
      logWarn("rtdn.void_not_confirmed", {});
      return { handled: false, reason: "not_confirmed" };
    }
    await storeEntitlement(owner.uid, { state: "revoked", entitled: false, expiryTime: null, autoRenewing: false, productId: owner.productId }, now, "rtdn", token);
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
  if (!boundTo(purchase, owner.uid)) {
    // A newer token (resubscribe or upgrade) bound to another installation: the old
    // owner loses it, and the new installation gets it when it verifies.
    await clearIfFrom(owner.uid, owner.token, now, "transferred");
    logInfo("rtdn.transferred", { type });
    return { handled: true, state: "transferred" };
  }
  if (owner.token !== token) {
    await purchaseRef(token).set({ deviceUid: owner.uid, packageName, productId, accountId: accountIdFor(owner.uid), updatedAt: now });
    await purchaseRef(owner.token).delete();
  }
  // Type 12 (revoked) is never taken on trust (QA L6): Play reports a revoked
  // subscription as no longer entitled, and only then is it marked revoked.
  const fromPlay = entitlementFrom(purchase, productId, now);
  const revoked = type === SUBSCRIPTION_REVOKED && !fromPlay.entitled;
  const entitlement = revoked ? entitlementFrom(purchase, productId, now, true) : fromPlay;
  await storeEntitlement(owner.uid, entitlement, now, "rtdn", token);
  logInfo("rtdn.updated", { type, state: entitlement.state, entitled: entitlement.entitled });
  return { handled: true, state: entitlement.state };
}
