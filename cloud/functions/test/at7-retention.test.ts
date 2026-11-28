import { getAuth } from "firebase-admin/auth";
import { describe, expect, it } from "vitest";
import { runCleanup } from "../src/maintenance/cleanup";
import { adminDb } from "./helpers/admin";
import { pair } from "./helpers/assistant";
import { title } from "./helpers/env";
import { Phone } from "./helpers/phone";

// AT-7: the clean-up deletes per section G, on a fake clock: inbox requests 7
// days after they finish or expire, events after 30 days, devices idle for
// 180 days with everything under them. The mirror stays while connected.

const DAY = 24 * 60 * 60 * 1000;

describe("AT-7 retention clean-up", () => {
  it("deletes old inbox requests and events and keeps recent ones and the mirror", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    phone.start();
    const made = await assistant.tool("create_reminder", { text: title("Renew the rego") });
    phone.stop();
    const agentId = phone.reminders.get(made.reminder.reminder_id)!.agentId;
    const now = Date.now();
    const db = adminDb();
    const device = db.doc(`devices/${phone.uid}`);
    const inbox = device.collection("inbox");
    const events = device.collection(`agents/${agentId}/events`);

    await inbox.doc("rq_old_done").set({ agentId, status: "done", createdAt: now - 9 * DAY, finishedAt: now - 7 * DAY - 1000, expiresAt: now - 8 * DAY });
    await inbox.doc("rq_recent_done").set({ agentId, status: "failed", createdAt: now - 6 * DAY, finishedAt: now - 6 * DAY, expiresAt: now - 5 * DAY });
    await inbox.doc("rq_old_expired").set({ agentId, status: "expired", createdAt: now - 10 * DAY, finishedAt: now - 8 * DAY, expiresAt: now - 9 * DAY });
    await inbox.doc("rq_stale_pending").set({ agentId, status: "pending", createdAt: now - 2 * DAY, finishedAt: null, expiresAt: now - DAY, kind: "create_reminder", args: {} });
    await events.doc("p_old").set({ seq: 900, type: "fired", at: now - 31 * DAY, receivedAt: now - 31 * DAY });
    await events.doc("p_recent").set({ seq: 901, type: "nagged", at: now - 29 * DAY, receivedAt: now - 29 * DAY });

    const report = await runCleanup(now);
    expect(report.expired).toBeGreaterThanOrEqual(1);

    expect((await inbox.doc("rq_old_done").get()).exists).toBe(false);
    expect((await inbox.doc("rq_old_expired").get()).exists).toBe(false);
    expect((await inbox.doc("rq_recent_done").get()).exists).toBe(true);
    // Stale pending became expired now, and stays for another 7 days.
    expect((await inbox.doc("rq_stale_pending").get()).data()!.status).toBe("expired");
    expect((await events.doc("p_old").get()).exists).toBe(false);
    expect((await events.doc("p_recent").get()).exists).toBe(true);
    expect((await device.collection(`agents/${agentId}/reminders`).get()).size).toBe(1);

    // Seven days later the expired request goes too.
    await runCleanup(now + 7 * DAY + 1000);
    expect((await inbox.doc("rq_stale_pending").get()).exists).toBe(false);
  });

  it("deletes devices idle for 180 days with everything under them, and keeps 179-day ones", async () => {
    const idle = await Phone.create();
    const assistant = await pair(idle);
    idle.start();
    await assistant.tool("create_reminder", { text: title("Idle phone reminder") });
    idle.stop();
    const kept = await Phone.create();
    const now = Date.now();
    const db = adminDb();
    await db.doc(`devices/${idle.uid}`).update({ lastSeenAt: now - 180 * DAY - 1000 });
    await db.doc(`devices/${kept.uid}`).update({ lastSeenAt: now - 179 * DAY });
    await db.collection("purchases").doc(`test_${idle.uid}`).set({ deviceUid: idle.uid, productId: "do_unlimited" });

    await runCleanup(now);

    expect((await db.doc(`devices/${idle.uid}`).get()).exists).toBe(false);
    expect((await db.collection(`devices/${idle.uid}/agents`).get()).size).toBe(0);
    expect((await db.collection(`devices/${idle.uid}/inbox`).get()).size).toBe(0);
    expect((await db.collection("grants").where("deviceUid", "==", idle.uid).get()).size).toBe(0);
    expect((await db.collection("tokens").where("deviceUid", "==", idle.uid).get()).size).toBe(0);
    expect((await db.collection("purchases").where("deviceUid", "==", idle.uid).get()).size).toBe(0);
    await expect(getAuth().getUser(idle.uid)).rejects.toBeTruthy();
    expect((await assistant.rpc("tools/list")).status).toBe(401);

    expect((await db.doc(`devices/${kept.uid}`).get()).exists).toBe(true);
  });

  it("deletes expired tokens, codes and unused registrations", async () => {
    const db = adminDb();
    const now = Date.now();
    await db.collection("tokens").doc("test_expired").set({ type: "access", expiresAt: now - 1 });
    await db.collection("tokens").doc("test_live").set({ type: "access", expiresAt: now + 60_000 });
    await db.collection("pairingCodes").doc("test_old").set({ deviceUid: "nobody", expiresAt: now - 1 });
    await db.collection("authRequests").doc("test_old").set({ expiresAt: now - 1 });
    await db.collection("oauthClients").doc("test_unused").set({ kind: "registered", usedAt: null, createdAt: now - 2 * DAY });
    await db.collection("oauthClients").doc("test_used").set({ kind: "registered", usedAt: now - 2 * DAY, createdAt: now - 3 * DAY });
    await runCleanup(now);
    expect((await db.collection("tokens").doc("test_expired").get()).exists).toBe(false);
    expect((await db.collection("tokens").doc("test_live").get()).exists).toBe(true);
    expect((await db.collection("pairingCodes").doc("test_old").get()).exists).toBe(false);
    expect((await db.collection("authRequests").doc("test_old").get()).exists).toBe(false);
    expect((await db.collection("oauthClients").doc("test_unused").get()).exists).toBe(false);
    expect((await db.collection("oauthClients").doc("test_used").get()).exists).toBe(true);
    await db.collection("tokens").doc("test_live").delete();
  });
});
