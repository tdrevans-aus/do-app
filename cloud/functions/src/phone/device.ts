import { HttpsError } from "firebase-functions/v2/https";
import { deviceRef } from "../core/db";
import { logInfo } from "../core/log";
import { isRecord } from "../core/text";

const PLATFORMS = new Set(["android", "ios"]);

/** registerDevice({ fcmToken, platform, appVersion }): stores or refreshes devices/{uid}. */
export async function registerDevice(uid: string, data: unknown, now: number): Promise<{ ok: true }> {
  if (!isRecord(data)) throw new HttpsError("invalid-argument", "Expected an object");
  const { fcmToken, platform, appVersion } = data;
  if (typeof fcmToken !== "string" || fcmToken.length === 0 || fcmToken.length > 4096) {
    throw new HttpsError("invalid-argument", "fcmToken is required");
  }
  if (typeof platform !== "string" || !PLATFORMS.has(platform)) throw new HttpsError("invalid-argument", "platform must be android or ios");
  if (appVersion !== undefined && (typeof appVersion !== "string" || appVersion.length > 40)) {
    throw new HttpsError("invalid-argument", "appVersion must be short text");
  }
  const ref = deviceRef(uid);
  const existing = await ref.get();
  await ref.set(
    {
      fcmToken,
      platform,
      appVersion: appVersion ?? null,
      lastSeenAt: now,
      ...(existing.exists ? {} : { createdAt: now, assistantsEnabled: true }),
    },
    { merge: true },
  );
  logInfo("device.registered", { platform, updated: existing.exists });
  return { ok: true };
}

/** setAssistantsEnabled({ enabled }): the "assistants off" switch; when off every tool call returns disabled. */
export async function setAssistantsEnabled(uid: string, data: unknown, now: number): Promise<{ ok: true; enabled: boolean }> {
  if (!isRecord(data) || typeof data.enabled !== "boolean") throw new HttpsError("invalid-argument", "enabled must be true or false");
  const ref = deviceRef(uid);
  const existing = await ref.get();
  await ref.set({ assistantsEnabled: data.enabled, lastSeenAt: now, ...(existing.exists ? {} : { createdAt: now }) }, { merge: true });
  return { ok: true, enabled: data.enabled };
}

export async function touchDevice(uid: string, now: number): Promise<void> {
  const ref = deviceRef(uid);
  const existing = await ref.get();
  if (existing.exists) await ref.update({ lastSeenAt: now });
}
