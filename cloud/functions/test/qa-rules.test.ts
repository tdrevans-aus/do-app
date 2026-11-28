import { readFileSync } from "node:fs";
import path from "node:path";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, collectionGroup, deleteDoc, doc, getDoc, getDocs, query, setDoc, updateDoc, where } from "firebase/firestore";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

// QA (Spec 13): Firestore rules, trying harder than AT-5 to read or write other
// devices' data and server-only fields directly from a client.

describe("QA Firestore rules", () => {
  let env: RulesTestEnvironment;
  beforeAll(async () => {
    env = await initializeTestEnvironment({
      projectId: "demo-do-rules-qa",
      firestore: { rules: readFileSync(path.resolve(__dirname, "../../firestore.rules"), "utf8"), host: "127.0.0.1", port: 8080 },
    });
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      for (const uid of ["alice", "bob"]) {
        await setDoc(doc(db, `devices/${uid}`), { fcmToken: `t-${uid}`, assistantsEnabled: true, lastSeenAt: 1 });
        await setDoc(doc(db, `devices/${uid}/agents/ag_1`), { clientName: "A", grantId: "g", eventSeq: 1 });
        await setDoc(doc(db, `devices/${uid}/agents/ag_1/reminders/r1`), { title: `${uid} secret`, status: "active" });
        await setDoc(doc(db, `devices/${uid}/agents/ag_1/events/e1`), { seq: 1, type: "fired", at: 1 });
        await setDoc(doc(db, `devices/${uid}/inbox/rq_1`), { status: "pending", agentId: "ag_1", args: { text: `${uid} secret` } });
        await setDoc(doc(db, `devices/${uid}/entitlement/current`), { entitled: false, state: "expired" });
      }
      for (const p of ["purchases/p1", "rateLimits/r1", "oauthClients/c1", "authCodes/a1", "authRequests/q1", "rtdnSeen/m1", "_fakePlay/f1", "_emulatorPushes/x1", "grants/g1", "tokens/t1", "pairingCodes/s1"]) {
        await setDoc(doc(db, p), { deviceUid: "alice" });
      }
    });
  });
  afterAll(async () => {
    await env.cleanup();
  });

  it("denies collection group queries across devices", async () => {
    const alice = env.authenticatedContext("alice").firestore();
    for (const group of ["inbox", "reminders", "events", "agents", "entitlement"]) {
      await assertFails(getDocs(collectionGroup(alice, group)));
    }
    // Even narrowed to "her own" by a field filter, the rules can't prove ownership.
    await assertFails(getDocs(query(collectionGroup(alice, "inbox"), where("agentId", "==", "ag_1"))));
    await assertFails(getDocs(collection(alice, "devices")));
    expect(true).toBe(true);
  });

  it("lets a device read but never write its own server-owned fields", async () => {
    const alice = env.authenticatedContext("alice").firestore();
    await assertSucceeds(getDoc(doc(alice, "devices/alice/entitlement/current")));
    await assertSucceeds(getDocs(collection(alice, "devices/alice/agents/ag_1/events")));
    await assertFails(setDoc(doc(alice, "devices/alice/entitlement/current"), { entitled: true, state: "active" }));
    await assertFails(updateDoc(doc(alice, "devices/alice"), { assistantsEnabled: false }));
    await assertFails(updateDoc(doc(alice, "devices/alice/agents/ag_1"), { eventSeq: 0 }));
    await assertFails(setDoc(doc(alice, "devices/alice/agents/ag_2"), { clientName: "Fake" }));
    await assertFails(setDoc(doc(alice, "devices/alice/agents/ag_1/reminders/r2"), { title: "x" }));
    await assertFails(updateDoc(doc(alice, "devices/alice/inbox/rq_1"), { status: "done" }));
    await assertFails(deleteDoc(doc(alice, "devices/alice/inbox/rq_1")));
    expect(true).toBe(true);
  });

  it("denies another device and the unauthenticated everything, and everyone the server collections", async () => {
    const alice = env.authenticatedContext("alice").firestore();
    const anon = env.unauthenticatedContext().firestore();
    for (const p of ["devices/bob", "devices/bob/entitlement/current", "devices/bob/inbox/rq_1", "devices/bob/agents/ag_1/events/e1"]) {
      await assertFails(getDoc(doc(alice, p)));
      await assertFails(getDoc(doc(anon, p)));
      await assertFails(setDoc(doc(alice, p), { x: 1 }));
    }
    await assertFails(getDocs(collection(alice, "devices/bob/inbox")));
    for (const p of ["purchases/p1", "rateLimits/r1", "oauthClients/c1", "authCodes/a1", "authRequests/q1", "rtdnSeen/m1", "_fakePlay/f1", "_emulatorPushes/x1", "grants/g1", "tokens/t1", "pairingCodes/s1"]) {
      await assertFails(getDoc(doc(alice, p)));
      await assertFails(setDoc(doc(alice, p), { deviceUid: "alice" }));
      await assertFails(getDocs(collection(alice, p.split("/")[0]!)));
    }
    // A uid that only looks like a path cannot widen the match.
    const tricky = env.authenticatedContext("alice/agents").firestore();
    await assertFails(getDoc(doc(tricky, "devices/alice")));
    expect(true).toBe(true);
  });
});
