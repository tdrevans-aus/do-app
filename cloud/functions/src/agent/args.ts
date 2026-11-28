import { limits } from "../core/config";
import { AgentFailure } from "../core/errors";
import { cleanText, isRecord } from "../core/text";
import { UUID_PATTERN } from "./views";

// Checks assistant arguments before anything is sent to the phone, so a bad
// call fails fast with invalid_argument. Messages never echo the input.

export interface CreateArgs {
  text: string;
  dueAt: number | null;
  repeat: string | null;
  alertLevel: "normal" | "loud" | "alarm";
}

const ISO_WITH_OFFSET = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/i;

function argsObject(raw: unknown): Record<string, unknown> {
  if (raw === undefined || raw === null) return {};
  if (!isRecord(raw)) throw new AgentFailure("invalid_argument", "Arguments must be a JSON object.");
  return raw;
}

export function parseCreate(raw: unknown, now: number): CreateArgs {
  const args = argsObject(raw);
  const text = cleanText(args.text, limits.maxTextLength + 1);
  if (!text) throw new AgentFailure("invalid_argument", "text is required: say what to be reminded about, and when.");
  if (text.length > limits.maxTextLength) {
    throw new AgentFailure("invalid_argument", `text must be at most ${limits.maxTextLength} characters.`);
  }
  let dueAt: number | null = null;
  if (args.due_at !== undefined && args.due_at !== null && args.due_at !== "") {
    if (typeof args.due_at !== "string" || !ISO_WITH_OFFSET.test(args.due_at)) {
      throw new AgentFailure("invalid_argument", "due_at must be ISO 8601 with an offset, for example 2026-12-01T18:00:00+08:00.");
    }
    const ms = Date.parse(args.due_at);
    if (!Number.isFinite(ms)) throw new AgentFailure("invalid_argument", "due_at is not a real date and time.");
    if (ms < now - 60_000) throw new AgentFailure("invalid_argument", "due_at is in the past.");
    dueAt = ms;
  }
  let repeat: string | null = null;
  if (args.repeat !== undefined && args.repeat !== null && args.repeat !== "") {
    repeat = typeof args.repeat === "string" ? cleanText(args.repeat, 100) : null;
    if (!repeat) throw new AgentFailure("invalid_argument", 'repeat must be text such as "every day" or "every Tuesday".');
  }
  const level = args.alert_level === undefined || args.alert_level === null || args.alert_level === "" ? "normal" : args.alert_level;
  if (level !== "normal" && level !== "loud" && level !== "alarm") {
    throw new AgentFailure("invalid_argument", "alert_level must be normal, loud or alarm.");
  }
  return { text, dueAt, repeat, alertLevel: level };
}

export function parseComplete(raw: unknown): { reminderId: string } {
  const args = argsObject(raw);
  const id = args.reminder_id;
  if (typeof id !== "string" || !UUID_PATTERN.test(id)) {
    throw new AgentFailure("invalid_argument", "reminder_id is required: use the reminder_id from create_reminder or list_reminders.");
  }
  return { reminderId: id };
}

function intArg(value: unknown, name: string, max: number, fallback: number): number {
  if (value === undefined || value === null || value === "") return fallback;
  const n = typeof value === "string" && /^\d+$/.test(value) ? Number(value) : value;
  if (typeof n !== "number" || !Number.isInteger(n) || n < 1) {
    throw new AgentFailure("invalid_argument", `${name} must be a whole number from 1 to ${max}.`);
  }
  return Math.min(n, max);
}

export function parseList(raw: unknown): { status: "active" | "done" | "all"; limit: number } {
  const args = argsObject(raw);
  const status = args.status === undefined || args.status === null || args.status === "" ? "active" : args.status;
  if (status !== "active" && status !== "done" && status !== "all") {
    throw new AgentFailure("invalid_argument", "status must be active, done or all.");
  }
  return { status, limit: intArg(args.limit, "limit", limits.maxListLimit, limits.defaultListLimit) };
}

export function parseEvents(raw: unknown): { since: number; limit: number } {
  const args = argsObject(raw);
  let since = 0;
  const cursor = args.since_cursor;
  if (cursor !== undefined && cursor !== null && cursor !== "") {
    if (typeof cursor !== "string" || !/^\d{1,15}$/.test(cursor)) {
      throw new AgentFailure("invalid_argument", "since_cursor must be a cursor from an earlier get_events call.");
    }
    since = Number(cursor);
  }
  return { since, limit: intArg(args.limit, "limit", limits.maxEventsLimit, limits.defaultEventsLimit) };
}
