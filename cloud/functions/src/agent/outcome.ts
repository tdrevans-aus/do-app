import { DEFAULT_MESSAGES, PHONE_ERROR_CODES, type AgentErrorCode, type PhoneErrorCode } from "../core/errors";
import { isRecord } from "../core/text";
import { reminderView, type MirrorReminder } from "./views";

// What an assistant gets back from a tool, for MCP and REST alike.

export type ToolStatus = "done" | "held_for_approval" | "queued" | "ok" | "error";

export interface ToolResult {
  isError: boolean;
  body: Record<string, unknown>;
  /** REST status code. */
  http: number;
  retryAfterSeconds?: number;
}

const HTTP_FOR_ERROR: Record<AgentErrorCode, number> = {
  invalid_argument: 400,
  disabled: 403,
  not_found: 404,
  limit_reached: 409,
  device_offline: 410,
  rate_limited: 429,
  internal: 500,
};

export function errorResult(code: AgentErrorCode, message?: string, retryAfterSeconds?: number): ToolResult {
  const error: Record<string, unknown> = { code, message: message ?? DEFAULT_MESSAGES[code] };
  if (retryAfterSeconds !== undefined) error.retry_after_seconds = retryAfterSeconds;
  return { isError: true, body: { status: "error", error }, http: HTTP_FOR_ERROR[code], retryAfterSeconds };
}

export function okResult(body: Record<string, unknown>, http = 200): ToolResult {
  return { isError: false, body, http };
}

export const HELD_MESSAGE =
  "Created at Normal. Do will ask the person on their phone before this reminder can ring Loud or Alarm.";

export const QUEUED_MESSAGE =
  "The phone hasn't answered yet. Do will deliver the request when the phone is next online, for up to 24 hours. Call get_events later to see the result.";

/** A reminder result: held_for_approval when Do is holding a Loud or Alarm request at Normal. */
export function reminderResult(requestId: string, reminder: MirrorReminder): ToolResult {
  if (reminder.approval === "pending") {
    return okResult({ status: "held_for_approval", request_id: requestId, reminder: reminderView(reminder), message: HELD_MESSAGE });
  }
  return okResult({ status: "done", request_id: requestId, reminder: reminderView(reminder) });
}

export function queuedResult(requestId: string): ToolResult {
  return okResult({ status: "queued", request_id: requestId, message: QUEUED_MESSAGE }, 202);
}

export interface PhoneFailure {
  code: PhoneErrorCode;
  message: string | null;
}

export function parsePhoneFailure(raw: unknown): PhoneFailure | null {
  if (!isRecord(raw)) return null;
  const code = raw.code;
  if (typeof code !== "string" || !(PHONE_ERROR_CODES as readonly string[]).includes(code)) return null;
  const message = typeof raw.message === "string" && raw.message.trim().length > 0 ? raw.message.trim().slice(0, 300) : null;
  return { code: code as PhoneErrorCode, message };
}
