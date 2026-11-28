import { describe, expect, it } from "vitest";
import { runCleanup } from "../src/maintenance/cleanup";
import { adminDb } from "./helpers/admin";
import { Assistant, pair } from "./helpers/assistant";
import { BASE, sleep, title } from "./helpers/env";
import { Phone } from "./helpers/phone";

// QA (Spec 13): the agent API beyond AT-3 to AT-6. Tests marked
// "QA: fails until ..." expose a bug and are expected to fail until it is fixed.

const DAY = 24 * 60 * 60 * 1000;

async function agentIdOf(phone: Phone): Promise<string> {
  const agents = await adminDb().collection(`devices/${phone.uid}/agents`).get();
  return agents.docs[0]!.id;
}

describe("QA agent API: input", () => {
  it("only the four known arguments reach the phone: no nagging, quiet hours or level overrides", async () => {
    const phone = await Phone.create();
    const a = await pair(phone, "Sneaky");
    const result = await a.tool("create_reminder", {
      text: title("Water the plants"),
      alert_level: "alarm",
      nag_minutes: 0,
      nagMinutes: 0,
      quiet_hours: false,
      quietHours: { enabled: false },
      approval: "approved",
      source: "USER",
      sourceId: "someone-else",
    });
    // Nobody answers, so it is queued: read what the phone would get.
    expect(result.status).toBe("queued");
    const pending = await phone.pull();
    expect(pending).toHaveLength(1);
    expect(Object.keys(pending[0]!.args).sort()).toEqual(["alertLevel", "dueAt", "repeat", "text"]);
    expect(pending[0]!.args.alertLevel).toBe("alarm");
    expect(pending[0]!.agentId).toBe(await agentIdOf(phone));
  });

  it("refuses oversized text, bad levels and odd cursors with invalid_argument, without echoing input", async () => {
    const phone = await Phone.create();
    const a = await pair(phone, "Big text");
    const secret = title("Supersecretmarker");
    const cases: Array<[string, Record<string, unknown>]> = [
      ["create_reminder", { text: `${secret} ${"x".repeat(600)}` }],
      ["create_reminder", { text: `${secret} ${"y".repeat(200_000)}` }],
      ["create_reminder", { text: secret, alert_level: "ALARM!" }],
      ["create_reminder", { text: secret, due_at: "tomorrow" }],
      ["create_reminder", { text: secret, due_at: 1_900_000_000_000 }],
      ["create_reminder", { text: secret, due_at: "2001-01-01T00:00:00Z" }],
      ["create_reminder", { text: secret, repeat: 7 }],
      ["create_reminder", { text: ["array"] }],
      ["complete_reminder", { reminder_id: "../../devices/other" }],
      ["complete_reminder", { reminder_id: "x".repeat(65) }],
      ["list_reminders", { limit: -1 }],
      ["list_reminders", { limit: 1.5 }],
      ["list_reminders", { status: "deleted" }],
      ["get_events", { since_cursor: "-1" }],
      ["get_events", { since_cursor: "9".repeat(16) }],
    ];
    for (const [tool, args] of cases) {
      const result = await a.tool(tool, args);
      expect(result.isError, `${tool} ${JSON.stringify(args).slice(0, 60)}`).toBe(true);
      expect(result.error.code).toBe("invalid_argument");
      expect(JSON.stringify(result)).not.toContain("Supersecretmarker");
    }
    // A non-object arguments value.
    const raw = await a.rpc("tools/call", { name: "create_reminder", arguments: "call Mum" });
    expect(raw.body!.result.structuredContent.error.code).toBe("invalid_argument");
    // Nothing reached the phone.
    expect(await phone.pull()).toEqual([]);
  });

  it("QA: fails until due_at refuses impossible calendar dates (2026-02-30 is read as 2 March)", async () => {
    const phone = await Phone.create();
    const a = await pair(phone, "Calendar");
    const year = new Date().getUTCFullYear() + 1;
    const result = await a.tool("create_reminder", { text: title("Impossible date"), due_at: `${year}-02-30T09:00:00+08:00` });
    expect(result.isError).toBe(true);
    expect(result.error?.code).toBe("invalid_argument");
  });

  it("answers server errors and bad JSON without stack traces or internals", async () => {
    const phone = await Phone.create();
    const a = await pair(phone, "Errors");
    // text/plain so the platform's JSON body parser leaves it to Do's own handler. (With
    // application/json the emulator's body-parser rejects it first, with a stack trace page:
    // see the report's deploy checks.)
    const response = await fetch(`${BASE}/mcp`, {
      method: "POST",
      headers: { "content-type": "text/plain", authorization: `Bearer ${a.tokens!.access_token}` },
      body: '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"create_reminder","arguments":{"text":',
    });
    const text = await response.text();
    expect(response.status).toBe(400);
    expect(text).not.toMatch(/at \w+ \(|node_modules|\/src\/|SyntaxError/);
    expect(JSON.parse(text)).toMatchObject({ jsonrpc: "2.0", error: { code: -32700 } });
    const unknown = await fetch(`${BASE}/v1/delete_everything`, { method: "POST", headers: { authorization: `Bearer ${a.tokens!.access_token}` } });
    expect(unknown.status).toBe(404);
  });
});

describe("QA agent API: limits and isolation", () => {
  it("the per-device limit (60 a minute) covers all assistants on the device together", async () => {
    // Start early in a minute window so the fixed window does not roll over mid-test.
    const second = new Date().getUTCSeconds();
    if (second > 25) await sleep((61 - second) * 1000);
    const phone = await Phone.create();
    const assistants: Assistant[] = [];
    for (const name of ["One", "Two", "Three"]) assistants.push(await pair(phone, name));
    const codes: string[] = [];
    for (const a of assistants.slice(0, 2)) {
      for (let i = 0; i < 30; i++) codes.push((await a.rest("list_reminders", {}, "GET")).body.status);
    }
    expect(codes.filter((c) => c === "ok")).toHaveLength(60);
    const third = await assistants[2]!.rest("list_reminders", {}, "GET");
    expect(third.status).toBe(429);
    expect(Number(third.headers.get("retry-after"))).toBeGreaterThan(0);
  });

  it("another device cannot complete or see this device's inbox request", async () => {
    const phone = await Phone.create();
    const a = await pair(phone, "Owner");
    const queued = await a.tool("create_reminder", { text: title("Mine only") });
    expect(queued.status).toBe("queued");
    const other = await Phone.create();
    // The other phone cannot complete this request: it is not in its inbox.
    await expect(
      other.call("completeRequest", { requestId: queued.request_id, result: { status: "failed", error: { code: "not_found" } } }),
    ).rejects.toMatchObject({ code: "NOT_FOUND" });
    const pending = await phone.pull();
    expect(pending.map((r) => r.requestId)).toEqual([queued.request_id]);
  });
});

describe("QA retention", () => {
  it("QA: fails until event retention uses the server's storedAt, not the phone's at", async () => {
    // cleanup deletes events by `at`, which the phone sends. An event whose `at` is in the
    // future (a wrong phone clock) is kept for ever, against section G's 30 days.
    const phone = await Phone.create();
    const a = await pair(phone, "Clock skew");
    phone.start();
    const made = await a.tool("create_reminder", { text: title("Skewed clock") });
    phone.stop();
    expect(made.status).toBe("done");
    const agentId = await agentIdOf(phone);
    const uuid = made.reminder.reminder_id as string;
    const now = Date.now();
    await phone.call("syncAgentMirror", {
      agentId,
      reminders: [],
      events: [{ id: 990001, reminderUuid: uuid, type: "FIRED", at: now + 365 * DAY }],
    });
    const events = adminDb().collection(`devices/${phone.uid}/agents/${agentId}/events`);
    expect((await events.doc("p_990001").get()).exists).toBe(true);
    await runCleanup(now + 40 * DAY);
    expect((await events.doc("p_990001").get()).exists).toBe(false);
  });
});
