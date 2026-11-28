import { createHash } from "node:crypto";
import type { SubscriptionPurchaseV2 } from "./play";

// Binding a Play purchase to the Do installation that made it (QA M1).
// The app passes accountIdFor(uid) to BillingFlowParams.setObfuscatedAccountId
// when it starts the purchase; Play returns it with the purchase as
// externalAccountIdentifiers.obfuscatedExternalAccountId. A token whose id
// doesn't match the caller never entitles the caller.

/** Play allows up to 64 characters; base64url of SHA-256 is 43. Not reversible to the uid. */
export const ACCOUNT_ID_MAX = 64;

export function accountIdFor(uid: string): string {
  return createHash("sha256").update(`do-account:${uid}`, "utf8").digest("base64url").slice(0, ACCOUNT_ID_MAX);
}

export function boundAccountId(purchase: SubscriptionPurchaseV2): string | null {
  const id = purchase.externalAccountIdentifiers?.obfuscatedExternalAccountId;
  return typeof id === "string" && id.length > 0 ? id : null;
}

export function boundTo(purchase: SubscriptionPurchaseV2, uid: string): boolean {
  return boundAccountId(purchase) === accountIdFor(uid);
}
