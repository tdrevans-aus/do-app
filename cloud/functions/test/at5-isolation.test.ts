import { readFileSync } from "node:fs";
import path from "node:path";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, collection, getDocs, deleteDoc } from "firebase/firestore";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { pair } from "./helpers/assistant";
import { title } from "./helpers/env";
import { Phone } from "./helpers/phone";

// AT-5: assistant A cannot list or complete assistant B's reminders, or
// another device's; Firestore rules deny direct client access.

describe("AT-5 isolation between assistants and devices", () => {
  it("keeps each assistant to its own reminders", async () => {
    const phone = await Phone.create();
    const a = await pair(phone, "Assistant A");
    const b = await pair(phone, "Assistant B");
    const other = await Phone.create();
    const c = await pair(other, "Assistant C");

    phone.start();
    other.start();
    const fromA = await a.tool("create_reminder", { text: title("A's secret errand") });
    const fromB = await b.tool("create_reminder", { text: title("B's secret errand") });
    const fromC = await c.tool("create_reminder", { text: title("C's secret errand") });

    const listA = await a.tool("list_reminders", { status: "all" });
    expect(listA.reminders.map((r: { reminder_id: string }) => r.reminder_id)).toEqual([fromA.reminder.reminder_id]);
    const listB = await b.rest("list_reminders", { status: "all" }, "GET");
    expect(listB.body.reminders.map((r: { reminder_id: string }) => r.reminder_id)).toEqual([fromB.reminder.reminder_id]);

    // A cannot complete B's reminder, nor the other device's, over MCP or REST.
    expect((await a.tool("complete_reminder", { reminder_id: fromB.reminder.reminder_id })).error.code).toBe("not_found");
    expect((await a.rest("complete_reminder", { reminder_id: fromC.reminder.reminder_id })).status).toBe(404);
    expect((await c.tool("complete_reminder", { reminder_id: fromA.reminder.reminder_id })).error.code).toBe("not_found");

    // Events too: A sees only its own.
    const eventsA = await a.tool("get_events");
    for (const e of eventsA.events) expect([undefined, fromA.reminder.reminder_id]).toContain(e.reminder_id);
    phone.stop();
    other.stop();

    // B's reminder is untouched on the phone.
    expect(phone.reminders.get(fromB.reminder.reminder_id)!.status).toBe("active");

    // The refused completes never reached the phone.
    expect(await phone.pull()).toEqual([]);
  });

  it("syncAgentMirror refuses another device's assistant and skips reminders the assistant did not create", async () => {
    const phone = await Phone.create();
    const a = await pair(phone, "Assistant A");
    phone.start();
    const made = await a.tool("create_reminder", { text: title("Mirror me") });
    phone.stop();
    const agentId = phone.reminders.get(made.reminder.reminder_id)!.agentId;

    const sync = await phone.call<{ reminders: number; skipped: number }>("syncAgentMirror", {
      agentId,
      reminders: [
        { ...phone.wire(phone.reminders.get(made.reminder.reminder_id)!), version: 2, updatedAt: Date.now() },
        { uuid: "11111111-2222-4333-8444-555555555555", title: title("Person's own private reminder"), dueAt: Date.now(), status: "active", version: 1, updatedAt: Date.now() },
      ],
      events: [],
    });
    expect(sync).toMatchObject({ reminders: 1, skipped: 1 });
    const list = await a.tool("list_reminders", { status: "all" });
    expect(list.reminders).toHaveLength(1);

    const stranger = await Phone.create();
    await expect(stranger.call("syncAgentMirror", { agentId, reminders: [], events: [] })).rejects.toMatchObject({ code: "NOT_FOUND" });
    await expect(stranger.call("disconnectAgent", { agentId })).rejects.toMatchObject({ code: "NOT_FOUND" });
  });
});

describe("AT-5 Firestore rules", () => {
  let env: RulesTestEnvironment;
  beforeAll(async () => {
    env = await initializeTestEnvironment({
      projectId: "demo-do-rules",
      firestore: { rules: readFileSync(path.resolve(__dirname, "../../firestore.rules"), "utf8"), host: "127.0.0.1", port: 8080 },
    });
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, "devices/alice"), { fcmToken: "t" });
      await setDoc(doc(db, "devices/alice/agents/ag_1"), { clientName: "A" });
      await setDoc(doc(db, "devices/alice/agents/ag_1/reminders/r1"), { title: "x" });
      await setDoc(doc(db, "devices/alice/inbox/rq_1"), { status: "pending" });
      await setDoc(doc(db, "tokens/abc"), { type: "access" });
      await setDoc(doc(db, "pairingCodes/abc"), { deviceUid: "alice" });
      await setDoc(doc(db, "grants/g1"), { deviceUid: "alice" });
    });
  });
  afterAll(async () => {
    await env.cleanup();
  });

  it("lets a device read its own documents and nothing else, and write nothing", async () => {
    const alice = env.authenticatedContext("alice").firestore();
    const bob = env.authenticatedContext("bob").firestore();
    const anon = env.unauthenticatedContext().firestore();
    await assertSucceeds(getDoc(doc(alice, "devices/alice")));
    await assertSucceeds(getDocs(collection(alice, "devices/alice/agents")));
    await assertSucceeds(getDoc(doc(alice, "devices/alice/agents/ag_1/reminders/r1")));
    await assertSucceeds(getDoc(doc(alice, "devices/alice/inbox/rq_1")));

    await assertFails(setDoc(doc(alice, "devices/alice"), { fcmToken: "mine" }));
    await assertFails(setDoc(doc(alice, "devices/alice/inbox/rq_2"), { status: "done" }));
    await assertFails(deleteDoc(doc(alice, "devices/alice/agents/ag_1")));
    await assertFails(getDoc(doc(bob, "devices/alice")));
    await assertFails(getDoc(doc(bob, "devices/alice/agents/ag_1/reminders/r1")));
    await assertFails(getDoc(doc(anon, "devices/alice")));
    for (const p of ["tokens/abc", "pairingCodes/abc", "grants/g1"]) {
      await assertFails(getDoc(doc(alice, p)));
      await assertFails(setDoc(doc(alice, p), { x: 1 }));
    }
    expect(true).toBe(true);
  });
});
