import { randomUUID } from "node:crypto";
import { describe, expect, it } from "vitest";
import { accountIdFor } from "../src/billing/binding";
import { FakePlayClient } from "../src/billing/play";
import { handleRtdn } from "../src/billing/rtdn";
import { adminDb } from "./helpers/admin";
import { PACKAGE } from "./helpers/env";
import { Phone } from "./helpers/phone";

// QA (Spec 13): purchase verification trust and RTDN authenticity. RTDN is
// handed to the handler in-process (the same handler the Pub/Sub trigger runs).

const PRODUCT = "do_unlimited";
const DAY = 24 * 60 * 60 * 1000;

// Fix for M1: purchases are bound to the installation that made them (the app passes
// accountIdFor(uid) as BillingFlowParams.setObfuscatedAccountId), so fixtures name an owner.
function purchase(state: string, expiry = Date.now() + 30 * DAY, owner?: string) {
  return {
    ...(owner ? { externalAccountIdentifiers: { obfuscatedExternalAccountId: accountIdFor(owner) } } : {}),
    subscriptionState: `SUBSCRIPTION_STATE_${state}`,
    acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
    lineItems: [{ productId: PRODUCT, expiryTime: new Date(expiry).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }],
  };
}

const verify = (phone: Phone, token: string) =>
  phone.call<Record<string, any>>("verifyPurchase", {
    platform: "play",
    packageName: PACKAGE,
    productId: PRODUCT,
    purchaseToken: token,
    obfuscatedAccountId: accountIdFor(phone.uid),
  });

const entitlement = async (uid: string) => (await adminDb().doc(`devices/${uid}/entitlement/current`).get()).data();

const rtdn = (body: Record<string, unknown>) =>
  handleRtdn({ version: "1.0", packageName: PACKAGE, eventTimeMillis: String(Date.now()), ...body }, `qa-${randomUUID()}`, Date.now(), new FakePlayClient());

describe("QA purchase verification", () => {
  it("QA: fails until a purchase token verified by a second device stops entitling the first one", async () => {
    // "The last device to verify a token owns it", but the earlier owner's entitlement
    // record is left as it was, and RTDN only updates the new owner. One subscription
    // (a token copied off a rooted phone or shared) entitles any number of devices,
    // and the earlier ones stay entitled server-side after it expires.
    const token = `qa-shared-${randomUUID()}`;
    const first = await Phone.create();
    const second = await Phone.create();
    await FakePlayClient.ref(token).set({ packageName: PACKAGE, purchase: purchase("ACTIVE", undefined, first.uid), ackCount: 0 });
    expect((await verify(first, token)).entitled).toBe(true);
    // After the M1 fix the second device can't take the token at all (stricter than handing it over).
    expect((await verify(second, token)).entitled).toBe(false);
    expect((await entitlement(first.uid))!.entitled).toBe(true);

    await FakePlayClient.ref(token).update({ "purchase.subscriptionState": "SUBSCRIPTION_STATE_EXPIRED" });
    await rtdn({ subscriptionNotification: { version: "1.0", notificationType: 13, purchaseToken: token, subscriptionId: PRODUCT } });
    expect((await entitlement(second.uid))!.entitled).toBe(false);
    expect((await entitlement(first.uid))!.entitled).toBe(false);
  });

  it("an RTDN's claimed state is never trusted: the state is read back from Play", async () => {
    const token = `qa-forged-${randomUUID()}`;
    const phone = await Phone.create();
    await FakePlayClient.ref(token).set({ packageName: PACKAGE, purchase: purchase("ACTIVE", undefined, phone.uid), ackCount: 0 });
    await verify(phone, token);
    // A forged "expired" or "on hold" subscription notification: Play still says active, so it stays active.
    for (const type of [13, 5]) {
      await rtdn({ subscriptionNotification: { version: "1.0", notificationType: type, purchaseToken: token, subscriptionId: PRODUCT } });
    }
    expect((await entitlement(phone.uid))!).toMatchObject({ state: "active", entitled: true });
    // After the L6 fix, type 12 (revoked) and a voided purchase are re-checked with Play too,
    // so forged ones change nothing while Play says the subscription is active.
    await rtdn({ subscriptionNotification: { version: "1.0", notificationType: 12, purchaseToken: token, subscriptionId: PRODUCT } });
    await rtdn({ voidedPurchaseNotification: { purchaseToken: token, orderId: "GPA.1", productType: 1, refundType: 1 } });
    expect((await entitlement(phone.uid))!).toMatchObject({ state: "active", entitled: true });
    // Another package's notification is ignored.
    const other = await handleRtdn(
      { version: "1.0", packageName: "com.example.other", subscriptionNotification: { notificationType: 2, purchaseToken: token } },
      `qa-${randomUUID()}`,
      Date.now(),
      new FakePlayClient(),
    );
    expect(other).toMatchObject({ handled: false, reason: "other_package" });
  });

  it("a device cannot verify a token for another package or product, or with junk", async () => {
    const phone = await Phone.create();
    const token = `qa-other-${randomUUID()}`;
    await FakePlayClient.ref(token).set({ packageName: "com.example.other", purchase: purchase("ACTIVE"), ackCount: 0 });
    await expect(phone.call("verifyPurchase", { platform: "play", packageName: "com.example.other", productId: PRODUCT, purchaseToken: token })).rejects.toMatchObject({ code: "INVALID_ARGUMENT" });
    await expect(verify(phone, token)).rejects.toMatchObject({ code: "NOT_FOUND" });
    await expect(phone.call("verifyPurchase", { platform: "play", packageName: PACKAGE, productId: "do_unlock", purchaseToken: token })).rejects.toMatchObject({ code: "INVALID_ARGUMENT" });
    await expect(phone.call("verifyPurchase", { platform: "play", packageName: PACKAGE, productId: PRODUCT, purchaseToken: "x".repeat(5000) })).rejects.toMatchObject({ code: "INVALID_ARGUMENT" });
    expect(await entitlement(phone.uid)).toBeUndefined();
  });
});
