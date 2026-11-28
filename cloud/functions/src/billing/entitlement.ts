import type { SubscriptionPurchaseV2 } from "./play";

// Maps a Play subscription to Do's entitlement record (Spec 13, section F).

export type EntitlementState =
  | "active"
  | "canceled"
  | "grace_period"
  | "on_hold"
  | "paused"
  | "pending"
  | "expired"
  | "revoked"
  /** The purchase moved to another installation (a resubscribe or upgrade bound to it). */
  | "transferred"
  /** This purchase is bound to another installation of Do: not entitled here. */
  | "other_device"
  | "unknown";

export interface Entitlement {
  state: EntitlementState;
  /** True when Do Unlimited is on: active, grace period, or cancelled but not yet expired. */
  entitled: boolean;
  expiryTime: number | null;
  autoRenewing: boolean;
  productId: string;
}

const STATES: Record<string, EntitlementState> = {
  SUBSCRIPTION_STATE_ACTIVE: "active",
  SUBSCRIPTION_STATE_CANCELED: "canceled",
  SUBSCRIPTION_STATE_IN_GRACE_PERIOD: "grace_period",
  SUBSCRIPTION_STATE_ON_HOLD: "on_hold",
  SUBSCRIPTION_STATE_PAUSED: "paused",
  SUBSCRIPTION_STATE_PENDING: "pending",
  SUBSCRIPTION_STATE_EXPIRED: "expired",
  SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED: "expired",
};

export function lineItemFor(purchase: SubscriptionPurchaseV2, productId: string) {
  return (purchase.lineItems ?? []).find((item) => item.productId === productId) ?? null;
}

export function entitlementFrom(purchase: SubscriptionPurchaseV2, productId: string, now: number, revoked = false): Entitlement {
  const item = lineItemFor(purchase, productId);
  const expiry = item?.expiryTime ? Date.parse(item.expiryTime) : NaN;
  const expiryTime = Number.isFinite(expiry) ? expiry : null;
  const state: EntitlementState = revoked ? "revoked" : (STATES[purchase.subscriptionState ?? ""] ?? "unknown");
  const autoRenewing = state !== "revoked" && item?.autoRenewingPlan?.autoRenewEnabled === true;
  const notExpired = expiryTime === null || expiryTime > now;
  const entitled =
    state === "active" || state === "grace_period" ? true : state === "canceled" ? expiryTime !== null && notExpired : false;
  return { state, entitled, expiryTime, autoRenewing, productId };
}

/** Play wants a purchase acknowledged within 3 days; only an active (or grace) one can be. */
export function needsAcknowledge(purchase: SubscriptionPurchaseV2): boolean {
  const state = purchase.subscriptionState;
  return (
    purchase.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING" &&
    (state === "SUBSCRIPTION_STATE_ACTIVE" || state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD")
  );
}
