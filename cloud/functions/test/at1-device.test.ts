import { describe, expect, it } from "vitest";
import { adminDb } from "./helpers/admin";
import { call, CallError, signInAnonymously } from "./helpers/device";
import { FIRESTORE, PROJECT } from "./helpers/env";

// AT-1: device registration stores the token; a second call updates it; another device can't read it.

async function readAsClient(idToken: string, docPath: string): Promise<number> {
  const response = await fetch(`${FIRESTORE}/v1/projects/${PROJECT}/databases/(default)/documents/${docPath}`, {
    headers: { authorization: `Bearer ${idToken}` },
  });
  return response.status;
}

describe("AT-1 device registration", () => {
  it("stores the token, updates it on a second call, and only the owner can read it", async () => {
    const a = await signInAnonymously();
    await call("registerDevice", a.idToken, { fcmToken: "token-one", platform: "android", appVersion: "13.0.0" });
    const first = (await adminDb().doc(`devices/${a.uid}`).get()).data()!;
    expect(first.fcmToken).toBe("token-one");
    expect(first.platform).toBe("android");
    expect(first.createdAt).toBeTypeOf("number");
    expect(first.lastSeenAt).toBeTypeOf("number");

    await call("registerDevice", a.idToken, { fcmToken: "token-two", platform: "android", appVersion: "13.0.1" });
    const second = (await adminDb().doc(`devices/${a.uid}`).get()).data()!;
    expect(second.fcmToken).toBe("token-two");
    expect(second.appVersion).toBe("13.0.1");
    expect(second.createdAt).toBe(first.createdAt);
    expect(second.lastSeenAt).toBeGreaterThanOrEqual(first.lastSeenAt);

    const b = await signInAnonymously();
    expect(await readAsClient(a.idToken, `devices/${a.uid}`)).toBe(200);
    expect(await readAsClient(b.idToken, `devices/${a.uid}`)).toBe(403);
  });

  it("needs sign-in and checks its arguments", async () => {
    await expect(call("registerDevice", null, { fcmToken: "x", platform: "android" })).rejects.toMatchObject({ code: "UNAUTHENTICATED" });
    const a = await signInAnonymously();
    const bad = await call("registerDevice", a.idToken, { fcmToken: "", platform: "android" }).catch((e: CallError) => e);
    expect(bad).toBeInstanceOf(CallError);
    expect((bad as CallError).code).toBe("INVALID_ARGUMENT");
    const badPlatform = await call("registerDevice", a.idToken, { fcmToken: "t", platform: "windows" }).catch((e: CallError) => e);
    expect((badPlatform as CallError).code).toBe("INVALID_ARGUMENT");
  });
});
