import { describe, expect, it } from "vitest";
import { adminDb } from "./helpers/admin";
import { pair } from "./helpers/assistant";
import { title } from "./helpers/env";
import { Phone } from "./helpers/phone";

// AT-6: disconnect revokes tokens and deletes the mirror and events.

describe("AT-6 disconnect", () => {
  it("revokes tokens and deletes the mirror, events and inbox", async () => {
    const phone = await Phone.create();
    const assistant = await pair(phone, "Leaving Assistant");
    const stays = await pair(phone, "Staying Assistant");
    phone.start();
    const made = await assistant.tool("create_reminder", { text: title("Return the library books") });
    await stays.tool("create_reminder", { text: title("Stay put") });
    phone.stop();
    const agentId = phone.reminders.get(made.reminder.reminder_id)!.agentId;
    phone.fire(made.reminder.reminder_id);
    await phone.sync(agentId);
    expect((await assistant.tool("get_events")).events.length).toBeGreaterThan(1);
    // A queued request for this assistant, never answered.
    await assistant.tool("create_reminder", { text: title("Queued then gone") });

    const agentPath = `devices/${phone.uid}/agents/${agentId}`;
    expect((await adminDb().collection(`${agentPath}/reminders`).get()).size).toBe(1);

    await phone.call("disconnectAgent", { agentId });

    expect((await adminDb().doc(agentPath).get()).exists).toBe(false);
    expect((await adminDb().collection(`${agentPath}/reminders`).get()).size).toBe(0);
    expect((await adminDb().collection(`${agentPath}/events`).get()).size).toBe(0);
    const inbox = await adminDb().collection(`devices/${phone.uid}/inbox`).where("agentId", "==", agentId).get();
    expect(inbox.size).toBe(0);
    const tokens = await adminDb().collection("tokens").where("agentId", "==", agentId).get();
    expect(tokens.size).toBe(0);

    const after = await assistant.rpc("tools/list");
    expect(after.status).toBe(401);
    expect((await assistant.refresh()).status).toBe(400);
    const rest = await fetch(`http://127.0.0.1:5002/v1/list_reminders`, { headers: { authorization: `Bearer ${assistant.tokens!.access_token}` } });
    expect(rest.status).toBe(401);

    // The other assistant is untouched.
    expect((await stays.tool("list_reminders")).reminders).toHaveLength(1);
  });
});
