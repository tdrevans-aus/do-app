import { getMessaging } from "firebase-admin/messaging";
import { inEmulator, settings } from "../core/config";
import { sha256 } from "../core/crypto";
import { db, paths } from "../core/db";
import { logError } from "../core/log";

// The wake-up push (Spec 13, section B): an FCM data message with no
// user-visible content and nothing about any reminder, high priority.

export const INBOX_PUSH = { kind: "inbox" } as const;

export type PushOutcome = "sent" | "unregistered" | "failed";

export interface Push {
  sendInbox(fcmToken: string): Promise<PushOutcome>;
}

class FcmPush implements Push {
  async sendInbox(fcmToken: string): Promise<PushOutcome> {
    try {
      await getMessaging().send({
        token: fcmToken,
        data: { ...INBOX_PUSH },
        android: { priority: "high", ttl: 24 * 60 * 60 * 1000 },
      });
      return "sent";
    } catch (error) {
      const code = (error as { code?: string }).code;
      if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
        return "unregistered";
      }
      logError("push.failed", error);
      return "failed";
    }
  }
}

/** Emulator only: there is no FCM emulator, so pushes are written to Firestore for tests to read. */
class RecordingPush implements Push {
  async sendInbox(fcmToken: string): Promise<PushOutcome> {
    await db()
      .collection(paths.emulatorPushes)
      .add({ tokenHash: sha256(fcmToken), data: { ...INBOX_PUSH }, priority: "high", at: Date.now() });
    return "sent";
  }
}

export function pushClient(): Push {
  if (settings.pushClient() === "record" && inEmulator()) return new RecordingPush();
  return new FcmPush();
}
