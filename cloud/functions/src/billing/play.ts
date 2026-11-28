import { GoogleAuth } from "google-auth-library";
import { inEmulator, settings } from "../core/config";
import { sha256 } from "../core/crypto";
import { db, paths } from "../core/db";

// The Google Play Developer API, behind an interface so tests use a fake.
// Real calls: purchases.subscriptionsv2.get, and purchases.subscriptions.acknowledge
// (acknowledge exists only on the v1 subscriptions resource and needs the product id).

/** The fields of SubscriptionPurchaseV2 that Do reads. */
export interface SubscriptionPurchaseV2 {
  subscriptionState?: string | null;
  acknowledgementState?: string | null;
  linkedPurchaseToken?: string | null;
  /** Set when the app passed BillingFlowParams.setObfuscatedAccountId. */
  externalAccountIdentifiers?: { obfuscatedExternalAccountId?: string | null; obfuscatedExternalProfileId?: string | null } | null;
  testPurchase?: unknown;
  lineItems?: Array<{
    productId?: string | null;
    expiryTime?: string | null;
    autoRenewingPlan?: { autoRenewEnabled?: boolean | null } | null;
  }> | null;
}

export class PlayError extends Error {
  constructor(
    readonly kind: "not_found" | "unavailable",
    message: string,
  ) {
    super(message);
    this.name = "PlayError";
  }
}

export interface PlayClient {
  getSubscription(packageName: string, purchaseToken: string): Promise<SubscriptionPurchaseV2>;
  acknowledge(packageName: string, productId: string, purchaseToken: string): Promise<void>;
}

const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

/** Uses the function's service account (Application Default Credentials). Tim gives it access in Play Console. */
export class GooglePlayClient implements PlayClient {
  private readonly auth = new GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] });

  private async call<T>(url: string, method: "GET" | "POST"): Promise<T> {
    const client = await this.auth.getClient();
    try {
      const response = await client.request<T>({ url, method, data: method === "POST" ? {} : undefined, timeout: 10_000 });
      return response.data;
    } catch (error) {
      const status = (error as { response?: { status?: number }; status?: number }).response?.status ?? (error as { status?: number }).status;
      if (status === 404 || status === 410) throw new PlayError("not_found", "Purchase not found");
      throw new PlayError("unavailable", `Play API failed with ${status ?? "no status"}`);
    }
  }

  getSubscription(packageName: string, purchaseToken: string): Promise<SubscriptionPurchaseV2> {
    const url = `${API}/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
    return this.call<SubscriptionPurchaseV2>(url, "GET");
  }

  async acknowledge(packageName: string, productId: string, purchaseToken: string): Promise<void> {
    const url = `${API}/${encodeURIComponent(packageName)}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;
    await this.call<unknown>(url, "POST");
  }
}

/**
 * Emulator only. Purchases live in Firestore at _fakePlay/{sha256(token)} as
 * { purchase, packageName, ackCount }; tests write them. acknowledge flips
 * acknowledgementState and counts calls, so tests can check "acknowledged once".
 */
export class FakePlayClient implements PlayClient {
  static ref(purchaseToken: string) {
    return db().collection(paths.fakePlay).doc(sha256(`play:${purchaseToken}`));
  }

  async getSubscription(packageName: string, purchaseToken: string): Promise<SubscriptionPurchaseV2> {
    const data = (await FakePlayClient.ref(purchaseToken).get()).data();
    if (!data || data.packageName !== packageName) throw new PlayError("not_found", "Purchase not found");
    return data.purchase as SubscriptionPurchaseV2;
  }

  async acknowledge(packageName: string, _productId: string, purchaseToken: string): Promise<void> {
    const ref = FakePlayClient.ref(purchaseToken);
    await db().runTransaction(async (tx) => {
      const data = (await tx.get(ref)).data();
      if (!data || data.packageName !== packageName) throw new PlayError("not_found", "Purchase not found");
      tx.update(ref, {
        ackCount: (data.ackCount ?? 0) + 1,
        "purchase.acknowledgementState": "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
      });
    });
  }
}

export function playClient(): PlayClient {
  if (settings.playClient() === "fake" && inEmulator()) return new FakePlayClient();
  return new GooglePlayClient();
}
