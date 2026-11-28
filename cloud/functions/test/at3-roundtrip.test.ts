import { describe, expect, it } from "vitest";
import { expireRequests } from "../src/maintenance/cleanup";
import { adminDb } from "./helpers/admin";
import { pair } from "./helpers/assistant";
import { title } from "./helpers/env";
import { Phone } from "./helpers/phone";

// AT-3: create_reminder round trip with a simulated phone answering: result
// returned within the wait; with no answer, queued then expired after 24 hours (fake clock).

describe("AT-3 create_reminder round trip", () => {
  it("returns the phone's result within the wait, and pushes a content-free wake-up", async () => {
    const phone = await Phone.create("fcm-roundtrip");
    const assistant = await pair(phone, "Round Trip Assistant");
    phone.start();
    const text = title("Call Mum tomorrow 6pm");
    const started = Date.now();
    const result = await assistant.tool("create_reminder", { text });
    phone.stop();
    expect(Date.now() - started).toBeLessThan(6000);
    expect(result.isError).toBe(false);
    expect(result.status).toBe("done");
    expect(result.request_id).toMatch(/^rq_/);
    expect(result.reminder.title).toBe(text);
    expect(result.reminder.alert_level).toBe("normal");
    expect(result.reminder.status).toBe("active");
    expect(result.raw.content[0].type).toBe("text");
    expect(JSON.parse(result.raw.content[0].text)).toEqual(result.raw.structuredContent);

    // The push: data only, exactly { kind: "inbox" }, nothing about the reminder.
    const pushes = await adminDb().collection("_emulatorPushes").get();
    const mine = pushes.docs.map((d) => d.data()).filter((p) => JSON.stringify(p).includes("inbox"));
    expect(mine.length).toBeGreaterThan(0);
    for (const push of mine) {
      expect(push.data).toEqual({ kind: "inbox" });
      expect(push.priority).toBe("high");
      expect(JSON.stringify(push)).not.toContain(text);
    }

    // The mirror now answers list_reminders without the phone.
    const list = await assistant.tool("list_reminders");
    expect(list.reminders.map((r: { reminder_id: string }) => r.reminder_id)).toEqual([result.reminder.reminder_id]);
    const events = await assistant.tool("get_events");
    expect(events.events.at(-1)).toMatchObject({ type: "request_result", request_id: result.request_id, status: "done" });

    // complete_reminder round trip too.
    phone.start();
    const done = await assistant.tool("complete_reminder", { reminder_id: result.reminder.reminder_id });
    phone.stop();
    expect(done.status).toBe("done");
    expect(done.reminder.status).toBe("done");
    expect((await assistant.tool("list_reminders")).reminders).toEqual([]);
    expect((await assistant.tool("list_reminders", { status: "done" })).reminders).toHaveLength(1);
  });

  it("returns queued when the phone does not answer, then expires after 24 hours with device_offline", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    const result = await assistant.tool("create_reminder", { text: title("Water the ferns at 7am") });
    expect(result.isError).toBe(false);
    expect(result.status).toBe("queued");
    const requestId = result.request_id as string;

    // The phone can still pull it within the 24 hours.
    const pending = await phone.pull();
    expect(pending.map((r) => r.requestId)).toEqual([requestId]);
    expect(pending[0]!.expiresAt - pending[0]!.createdAt).toBe(24 * 60 * 60 * 1000);

    // Fake clock: one minute short of 24 hours nothing expires; just past it, it does.
    const created = pending[0]!.createdAt;
    await expireRequests(created + 24 * 60 * 60 * 1000 - 60_000);
    expect((await adminDb().doc(`devices/${phone.uid}/inbox/${requestId}`).get()).data()!.status).toBe("pending");
    expect(await expireRequests(created + 24 * 60 * 60 * 1000 + 1)).toBeGreaterThanOrEqual(1);
    expect((await adminDb().doc(`devices/${phone.uid}/inbox/${requestId}`).get()).data()!.status).toBe("expired");
    expect(await phone.pull()).toEqual([]);

    const events = await assistant.tool("get_events");
    expect(events.events).toContainEqual(
      expect.objectContaining({ type: "request_result", request_id: requestId, status: "error", error_code: "device_offline" }),
    );
  });

  it("a queued request answered later shows up in get_events", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    const queued = await assistant.tool("create_reminder", { text: title("Book the vet") });
    expect(queued.status).toBe("queued");
    await phone.answerAll();
    const events = await assistant.tool("get_events");
    const result = events.events.find((e: { request_id?: string }) => e.request_id === queued.request_id);
    expect(result).toMatchObject({ type: "request_result", status: "done" });
    expect(result.reminder_id).toBeTypeOf("string");
    // Completing again is harmless.
    const again = await phone.call("completeRequest", {
      requestId: queued.request_id,
      result: { status: "failed", error: { code: "not_found" } },
    });
    expect(again).toMatchObject({ ok: true, duplicate: true });
  });
});
