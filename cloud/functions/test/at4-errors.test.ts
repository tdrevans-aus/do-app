import { describe, expect, it } from "vitest";
import { pair, type Assistant } from "./helpers/assistant";
import { sleep, title } from "./helpers/env";
import { Phone } from "./helpers/phone";

// AT-4: limit_reached, held_for_approval, not_found, invalid_argument,
// rate_limited and disabled all surface correctly over MCP and REST.

async function withPhone<T>(phone: Phone, run: () => Promise<T>): Promise<T> {
  phone.start();
  try {
    return await run();
  } finally {
    phone.stop();
  }
}

async function fill(assistant: Assistant, phone: Phone, count: number) {
  await withPhone(phone, async () => {
    for (let i = 0; i < count; i++) {
      const r = await assistant.tool("create_reminder", { text: title(`Filler ${i}`) });
      expect(r.status).toBe("done");
    }
  });
}

describe("AT-4 errors over MCP and REST", () => {
  it("limit_reached (free cap of 5) over MCP and REST", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    await fill(assistant, phone, 5);
    await withPhone(phone, async () => {
      const mcp = await assistant.tool("create_reminder", { text: title("Sixth reminder") });
      expect(mcp.isError).toBe(true);
      expect(mcp.status).toBe("error");
      expect(mcp.error.code).toBe("limit_reached");
      expect(mcp.error.message).toContain("5 active reminders");
      const rest = await assistant.rest("create_reminder", { text: title("Seventh reminder") });
      expect(rest.status).toBe(409);
      expect(rest.body.error.code).toBe("limit_reached");
    });
  });

  it("held_for_approval for Loud and Alarm over MCP and REST", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    await withPhone(phone, async () => {
      const mcp = await assistant.tool("create_reminder", { text: title("Take the pills"), alert_level: "loud" });
      expect(mcp.isError).toBe(false);
      expect(mcp.status).toBe("held_for_approval");
      expect(mcp.reminder.alert_level).toBe("normal");
      expect(mcp.reminder.requested_alert_level).toBe("loud");
      expect(mcp.reminder.approval).toBe("pending");
      const rest = await assistant.rest("create_reminder", { text: title("Leave for the airport"), alert_level: "alarm" });
      expect(rest.status).toBe(200);
      expect(rest.body.status).toBe("held_for_approval");
      expect(rest.body.reminder.requested_alert_level).toBe("alarm");
    });
  });

  it("not_found for unknown ids, without waking the phone", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    const mcp = await assistant.tool("complete_reminder", { reminder_id: "00000000-0000-4000-8000-000000000000" });
    expect(mcp.isError).toBe(true);
    expect(mcp.error.code).toBe("not_found");
    const rest = await assistant.rest("complete_reminder", { reminder_id: "00000000-0000-4000-8000-000000000000" });
    expect(rest.status).toBe(404);
    expect(rest.body.error.code).toBe("not_found");
    expect(await phone.pull()).toEqual([]);
  });

  it("not_found reported by the phone (the reminder went away there)", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    const created = await withPhone(phone, () => assistant.tool("create_reminder", { text: title("Pay the rates") }));
    phone.reminders.get(created.reminder.reminder_id)!.status = "deleted";
    const result = await withPhone(phone, () => assistant.tool("complete_reminder", { reminder_id: created.reminder.reminder_id }));
    expect(result.error.code).toBe("not_found");
  });

  it("invalid_argument over MCP and REST", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    const cases: Array<Record<string, unknown>> = [
      {},
      { text: "   " },
      { text: "x".repeat(501) },
      { text: "Ring Dad", alert_level: "siren" },
      { text: "Ring Dad", due_at: "tomorrow at six" },
      { text: "Ring Dad", due_at: "2026-12-01T18:00:00" },
      { text: "Ring Dad", due_at: "2001-01-01T18:00:00+08:00" },
    ];
    for (const args of cases) {
      const mcp = await assistant.tool("create_reminder", args);
      expect(mcp.isError).toBe(true);
      expect(mcp.error.code).toBe("invalid_argument");
      const rest = await assistant.rest("create_reminder", args);
      expect(rest.status).toBe(400);
      expect(rest.body.error.code).toBe("invalid_argument");
    }
    expect((await assistant.tool("list_reminders", { status: "maybe" })).error.code).toBe("invalid_argument");
    expect((await assistant.tool("list_reminders", { limit: 0 })).error.code).toBe("invalid_argument");
    expect((await assistant.rest("get_events", { since_cursor: "abc" }, "GET")).status).toBe(400);
    expect((await assistant.tool("complete_reminder", {})).error.code).toBe("invalid_argument");
    // A phone-side invalid_argument (Do's own parser refused the text) comes back as it is.
    phone.start(250, () => ({ status: "failed", error: { code: "invalid_argument", message: "text is required" } }));
    const phoneSide = await assistant.tool("create_reminder", { text: title("Nothing to read") });
    phone.stop();
    expect(phoneSide.isError).toBe(true);
    expect(phoneSide.error).toEqual({ code: "invalid_argument", message: "text is required" });
  });

  it("disabled when the person turns assistants off, over MCP and REST", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    await phone.call("setAssistantsEnabled", { enabled: false });
    const mcp = await assistant.tool("create_reminder", { text: title("Should not arrive") });
    expect(mcp.isError).toBe(true);
    expect(mcp.error.code).toBe("disabled");
    const rest = await assistant.rest("list_reminders", {}, "GET");
    expect(rest.status).toBe(403);
    expect(rest.body.error.code).toBe("disabled");
    expect(await phone.pull()).toEqual([]);
    await phone.call("setAssistantsEnabled", { enabled: true });
    // The phone can also refuse with disabled (Spec 12's AgentError.Disabled).
    phone.assistantsOn = false;
    const fromPhone = await withPhone(phone, () => assistant.tool("create_reminder", { text: title("Phone says no") }));
    expect(fromPhone.error.code).toBe("disabled");
  });

  it("rate_limited after 30 requests a minute, over MCP and REST", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone);
    // Stay inside one fixed minute window.
    const intoMinute = Date.now() % 60_000;
    if (intoMinute > 40_000) await sleep(60_000 - intoMinute + 200);
    for (let i = 0; i < 30; i++) {
      const r = await assistant.tool("list_reminders");
      expect(r.isError).toBe(false);
    }
    const mcp = await assistant.tool("list_reminders");
    expect(mcp.isError).toBe(true);
    expect(mcp.error.code).toBe("rate_limited");
    expect(mcp.error.retry_after_seconds).toBeGreaterThan(0);
    const rest = await assistant.rest("list_reminders", {}, "GET");
    expect(rest.status).toBe(429);
    expect(rest.body.error.code).toBe("rate_limited");
    expect(Number(rest.headers.get("retry-after"))).toBeGreaterThan(0);
  });
});
