import { randomUUID } from "node:crypto";
import { describe, expect, it } from "vitest";
import { FakePlayClient } from "../src/billing/play";
import { handleRtdn } from "../src/billing/rtdn";
import { adminDb } from "./helpers/admin";
import { CallError } from "./helpers/device";
import { eventually, PACKAGE, PROJECT, PUBSUB } from "./helpers/env";
import { Phone } from "./helpers/phone";

// AT-8: purchase verification with the fake Play API: purchased, acknowledged
// once, grace period, account hold, expired, revoked via RTDN.

const PRODUCT = "do_unlimited";
const DAY = 24 * 60 * 60 * 1000;

function purchase(state: string, ack = "ACKNOWLEDGEMENT_STATE_PENDING", expiry = Date.now() + 30 * DAY, autoRenew = true, linked?: string) {
  return {
    kind: "androidpublisher#subscriptionPurchaseV2",
    subscriptionState: `SUBSCRIPTION_STATE_${state}`,
    acknowledgementState: ack,
    ...(linked ? { linkedPurchaseToken: linked } : {}),
    lineItems: [{ productId: PRODUCT, expiryTime: new Date(expiry).toISOString(), autoRenewingPlan: { autoRenewEnabled: autoRenew } }],
  };
}

async function seed(token: string, body: ReturnType<typeof purchase>) {
  await FakePlayClient.ref(token).set({ packageName: PACKAGE, purchase: body, ackCount: 0 });
}

async function setState(token: string, state: string, extra: Record<string, unknown> = {}) {
  await FakePlayClient.ref(token).update({ "purchase.subscriptionState": `SUBSCRIPTION_STATE_${state}`, ...extra });
}

async function ackCount(token: string): Promise<number> {
  return (await FakePlayClient.ref(token).get()).data()!.ackCount;
}

async function entitlement(uid: string) {
  return (await adminDb().doc(`devices/${uid}/entitlement/current`).get()).data();
}

/**
 * The Emulator Suite's own Pub/Sub to Functions hop sends through HTTPS_PROXY
 * and ignores NO_PROXY, so behind a proxy (this sandbox, not CI) the
 * notification is handed to the same handler in-process instead.
 */
const viaPubSub = !process.env.HTTPS_PROXY && !process.env.https_proxy;

function notificationFor(notification: Record<string, unknown>) {
  return { version: "1.0", packageName: PACKAGE, eventTimeMillis: String(Date.now()), ...notification };
}

/** Delivers an RTDN as Play would: published to the Pub/Sub emulator's topic. */
async function publish(notification: Record<string, unknown>) {
  const body = notificationFor(notification);
  if (!viaPubSub) {
    await handleRtdn(body, `local-${randomUUID()}`, Date.now(), new FakePlayClient());
    return;
  }
  const data = Buffer.from(JSON.stringify(body)).toString("base64");
  const response = await fetch(`${PUBSUB}/v1/projects/${PROJECT}/topics/play-rtdn:publish`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ messages: [{ data }] }),
  });
  expect(response.ok).toBe(true);
}

function verify(phone: Phone, token: string, extra: Record<string, unknown> = {}) {
  return phone.call<Record<string, any>>("verifyPurchase", { platform: "play", packageName: PACKAGE, productId: PRODUCT, purchaseToken: token, ...extra });
}

async function waitForState(uid: string, state: string) {
  return eventually(async () => {
    const e = await entitlement(uid);
    return e?.state === state ? e : null;
  }, 20_000);
}

describe("AT-8 purchase verification", () => {
  it("purchased and acknowledged once, then grace period, account hold, recovered and expired via RTDN", async () => {
    const phone = await Phone.create();
    const token = `tok-${randomUUID()}`;
    await seed(token, purchase("ACTIVE"));

    const first = await verify(phone, token);
    expect(first).toMatchObject({ state: "active", entitled: true, autoRenewing: true, productId: PRODUCT });
    expect(first.expiryTime).toBeGreaterThan(Date.now());
    expect(await ackCount(token)).toBe(1);
    const stored = await entitlement(phone.uid);
    expect(stored).toMatchObject({ state: "active", entitled: true, autoRenewing: true, productId: PRODUCT });
    expect(stored!.lastCheckedAt).toBeTypeOf("number");

    // Verifying again does not acknowledge again.
    await verify(phone, token);
    expect(await ackCount(token)).toBe(1);

    await setState(token, "IN_GRACE_PERIOD");
    await publish({ subscriptionNotification: { version: "1.0", notificationType: 6, purchaseToken: token } });
    expect(await waitForState(phone.uid, "grace_period")).toMatchObject({ entitled: true });

    await setState(token, "ON_HOLD");
    await publish({ subscriptionNotification: { version: "1.0", notificationType: 5, purchaseToken: token } });
    expect(await waitForState(phone.uid, "on_hold")).toMatchObject({ entitled: false });

    await setState(token, "ACTIVE");
    await publish({ subscriptionNotification: { version: "1.0", notificationType: 1, purchaseToken: token } });
    expect(await waitForState(phone.uid, "active")).toMatchObject({ entitled: true });

    await setState(token, "CANCELED", { "purchase.lineItems": [{ productId: PRODUCT, expiryTime: new Date(Date.now() + 5 * DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: false } }] });
    await publish({ subscriptionNotification: { version: "1.0", notificationType: 3, purchaseToken: token } });
    expect(await waitForState(phone.uid, "canceled")).toMatchObject({ entitled: true, autoRenewing: false });

    await setState(token, "EXPIRED", { "purchase.lineItems": [{ productId: PRODUCT, expiryTime: new Date(Date.now() - DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: false } }] });
    await publish({ subscriptionNotification: { version: "1.0", notificationType: 13, purchaseToken: token } });
    expect(await waitForState(phone.uid, "expired")).toMatchObject({ entitled: false });
    expect(await ackCount(token)).toBe(1);
  });

  it("revoked via RTDN, and a voided purchase is revoked too", async () => {
    const phone = await Phone.create();
    const token = `tok-${randomUUID()}`;
    await seed(token, purchase("ACTIVE", "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"));
    expect(await verify(phone, token)).toMatchObject({ state: "active", entitled: true });
    expect(await ackCount(token)).toBe(0);

    await setState(token, "EXPIRED");
    await publish({ subscriptionNotification: { version: "1.0", notificationType: 12, purchaseToken: token } });
    expect(await waitForState(phone.uid, "revoked")).toMatchObject({ entitled: false, autoRenewing: false });

    const other = await Phone.create();
    const token2 = `tok-${randomUUID()}`;
    await seed(token2, purchase("ACTIVE"));
    await verify(other, token2);
    await publish({ voidedPurchaseNotification: { purchaseToken: token2, orderId: "GPA.0000", productType: 1, refundType: 1 } });
    expect(await waitForState(other.uid, "revoked")).toMatchObject({ entitled: false });
  });

  it("an upgrade or resubscribe with linkedPurchaseToken moves to the new token", async () => {
    const phone = await Phone.create();
    const oldToken = `tok-${randomUUID()}`;
    const newToken = `tok-${randomUUID()}`;
    await seed(oldToken, purchase("EXPIRED", "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"));
    await verify(phone, oldToken);
    await seed(newToken, purchase("ACTIVE", "ACKNOWLEDGEMENT_STATE_PENDING", Date.now() + 30 * DAY, true, oldToken));
    await publish({ subscriptionNotification: { version: "1.0", notificationType: 4, purchaseToken: newToken } });
    expect(await waitForState(phone.uid, "active")).toMatchObject({ entitled: true });
    expect(await ackCount(newToken)).toBe(1);
  });

  it("checks its input and reports an unknown purchase", async () => {
    const phone = await Phone.create();
    const wrongPackage = await verify(phone, "t", { packageName: "com.example.other" }).catch((e: CallError) => e);
    expect((wrongPackage as CallError).code).toBe("INVALID_ARGUMENT");
    const wrongProduct = await verify(phone, "t", { productId: "something_else" }).catch((e: CallError) => e);
    expect((wrongProduct as CallError).code).toBe("INVALID_ARGUMENT");
    const unknown = await verify(phone, `tok-${randomUUID()}`).catch((e: CallError) => e);
    expect((unknown as CallError).code).toBe("NOT_FOUND");
    expect(await entitlement(phone.uid)).toBeUndefined();
  });

  it.skipIf(!viaPubSub)("RTDN arrives through the Pub/Sub topic and the playRtdn function", async () => {
    const phone = await Phone.create();
    const token = `tok-${randomUUID()}`;
    await seed(token, purchase("ACTIVE", "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"));
    await verify(phone, token);
    await setState(token, "ON_HOLD");
    await publish({ subscriptionNotification: { version: "1.0", notificationType: 5, purchaseToken: token } });
    expect(await waitForState(phone.uid, "on_hold")).toMatchObject({ entitled: false, source: "rtdn" });
  });

  it("RTDN: acknowledges a purchase before the phone checks in, and ignores duplicates and test notifications", async () => {
    const play = new FakePlayClient();
    const token = `tok-${randomUUID()}`;
    await seed(token, purchase("ACTIVE"));
    const payload = { version: "1.0", packageName: PACKAGE, subscriptionNotification: { version: "1.0", notificationType: 4, purchaseToken: token } };
    const id = `msg-${randomUUID()}`;
    expect(await handleRtdn(payload, id, Date.now(), play)).toMatchObject({ handled: false, reason: "unknown_purchase" });
    expect(await ackCount(token)).toBe(1);
    expect(await handleRtdn(payload, id, Date.now(), play)).toMatchObject({ handled: false, reason: "duplicate" });
    expect(await handleRtdn({ version: "1.0", packageName: PACKAGE, testNotification: { version: "1.0" } }, `msg-${randomUUID()}`, Date.now(), play)).toMatchObject({ reason: "test" });
    expect(await handleRtdn({ packageName: "com.example.other" }, `msg-${randomUUID()}`, Date.now(), play)).toMatchObject({ reason: "other_package" });
    expect(await ackCount(token)).toBe(1);
  });
});
