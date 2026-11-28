import { randomUUID } from "node:crypto";
import { call, signInAnonymously, type DeviceIdentity } from "./device";
import { sleep } from "./env";

// A simulated phone. It follows cloud/CONTRACT.md the way the Android client
// will: it pulls the inbox, applies each request with Spec 12's rules (the
// free cap of 5 and the hold on Loud and Alarm), and completes the request.

export interface PhoneReminder {
  uuid: string;
  title: string;
  dueAt: number;
  status: "active" | "done" | "deleted";
  alertLevel: "STANDARD" | "CRITICAL" | "PROMINENT";
  requestedAlertLevel: string | null;
  approval: "NONE" | "PENDING" | "APPROVED" | "DECLINED";
  repeat: string | null;
  version: number;
  updatedAt: number;
  agentId: string;
}

export interface InboxRequest {
  requestId: string;
  agentId: string;
  clientName: string;
  kind: "create_reminder" | "complete_reminder";
  args: Record<string, unknown>;
  createdAt: number;
  expiresAt: number;
}

const LEVELS: Record<string, PhoneReminder["alertLevel"]> = { normal: "STANDARD", loud: "CRITICAL", alarm: "PROMINENT" };

export class Phone {
  readonly reminders = new Map<string, PhoneReminder>();
  unlocked = false;
  assistantsOn = true;
  private eventId = 0;
  readonly events: Array<{ id: number; reminderUuid: string; type: string; at: number }> = [];
  private running = false;

  private constructor(readonly identity: DeviceIdentity) {}

  static async create(fcmToken = `fcm-${randomUUID()}`): Promise<Phone> {
    const phone = new Phone(await signInAnonymously());
    await phone.call("registerDevice", { fcmToken, platform: "android", appVersion: "13.0.0" });
    return phone;
  }

  get uid(): string {
    return this.identity.uid;
  }

  call<T = Record<string, unknown>>(name: string, data: unknown = {}): Promise<T> {
    return call<T>(name, this.identity.idToken, data);
  }

  async pairingCode(): Promise<string> {
    const result = await this.call<{ code: string; expiresAt: number }>("createPairingCode");
    return result.code;
  }

  async pull(): Promise<InboxRequest[]> {
    return (await this.call<{ requests: InboxRequest[] }>("pullInbox")).requests;
  }

  private log(uuid: string, type: string): void {
    this.eventId += 1;
    this.events.push({ id: this.eventId, reminderUuid: uuid, type, at: Date.now() });
  }

  /** Applies one request as AgentFunctions would and returns the completeRequest result. */
  apply(request: InboxRequest): Record<string, unknown> {
    if (!this.assistantsOn) return { status: "failed", error: { code: "disabled", message: "Assistants are off" } };
    if (request.kind === "create_reminder") {
      const active = [...this.reminders.values()].filter((r) => r.status === "active").length;
      if (!this.unlocked && active >= 5) {
        return {
          status: "failed",
          error: { code: "limit_reached", message: "Limit reached: the free version of Do keeps 5 active reminders." },
        };
      }
      const args = request.args as { text: string; dueAt: number | null; repeat: string | null; alertLevel: string };
      const asked = LEVELS[args.alertLevel] ?? "STANDARD";
      const held = asked !== "STANDARD";
      const now = Date.now();
      const reminder: PhoneReminder = {
        uuid: randomUUID(),
        title: args.text,
        dueAt: args.dueAt ?? now + 60 * 60 * 1000,
        status: "active",
        alertLevel: "STANDARD",
        requestedAlertLevel: asked,
        approval: held ? "PENDING" : "NONE",
        repeat: args.repeat ? "DAILY" : null,
        version: 1,
        updatedAt: now,
        agentId: request.agentId,
      };
      this.reminders.set(reminder.uuid, reminder);
      this.log(reminder.uuid, "CREATED");
      return { status: "done", reminder: this.wire(reminder) };
    }
    const uuid = request.args.reminderUuid as string;
    const reminder = this.reminders.get(uuid);
    if (!reminder || reminder.status !== "active") return { status: "failed", error: { code: "not_found", message: "No active reminder" } };
    this.markDone(uuid);
    return { status: "done", reminder: this.wire(reminder) };
  }

  markDone(uuid: string): void {
    const reminder = this.reminders.get(uuid);
    if (!reminder) return;
    reminder.status = "done";
    reminder.version += 1;
    reminder.updatedAt = Date.now();
    this.log(uuid, "DONE");
  }

  fire(uuid: string, type = "FIRED"): void {
    this.log(uuid, type);
  }

  wire(r: PhoneReminder): Record<string, unknown> {
    const { agentId: _agent, ...rest } = r;
    return rest;
  }

  /** Pulls once and answers everything. Returns the requests handled. */
  async answerAll(transform?: (request: InboxRequest) => Record<string, unknown> | null): Promise<InboxRequest[]> {
    const requests = await this.pull();
    for (const request of requests) {
      const result = transform?.(request) ?? this.apply(request);
      await this.call("completeRequest", { requestId: request.requestId, result });
    }
    return requests;
  }

  /** Keeps pulling every [everyMs] until stop() is called, like FCM wake-ups would. */
  start(everyMs = 250, transform?: (request: InboxRequest) => Record<string, unknown> | null): void {
    this.running = true;
    void (async () => {
      while (this.running) {
        try {
          await this.answerAll(transform);
        } catch {
          // keep going
        }
        await sleep(everyMs);
      }
    })();
  }

  stop(): void {
    this.running = false;
  }

  /** syncAgentMirror for one assistant: only its own reminders and their events (AT-11's rule, from the phone side). */
  async sync(agentId: string): Promise<Record<string, unknown>> {
    const mine = [...this.reminders.values()].filter((r) => r.agentId === agentId);
    const uuids = new Set(mine.map((r) => r.uuid));
    return this.call("syncAgentMirror", {
      agentId,
      reminders: mine.map((r) => this.wire(r)),
      events: this.events.filter((e) => uuids.has(e.reminderUuid)),
    });
  }
}
