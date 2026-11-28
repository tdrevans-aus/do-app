import { isRecord } from "../core/text";

// The reminder as stored in the mirror (written from the phone) and as an
// assistant sees it. Assistants get snake_case and the names people see:
// alert levels normal, loud and alarm.

export type MirrorStatus = "active" | "done" | "deleted";

export interface MirrorReminder {
  uuid: string;
  title: string;
  dueAt: number;
  status: MirrorStatus;
  alertLevel: string;
  requestedAlertLevel: string | null;
  approval: string;
  repeat: string | null;
  version: number;
  updatedAt: number;
}

const LEVEL_NAMES: Record<string, string> = {
  standard: "normal",
  normal: "normal",
  critical: "loud",
  loud: "loud",
  prominent: "alarm",
  alarm: "alarm",
};

export function levelName(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  return LEVEL_NAMES[raw.trim().toLowerCase()] ?? null;
}

const APPROVALS = new Set(["none", "pending", "approved", "declined"]);

export function approvalName(raw: unknown): string {
  const value = typeof raw === "string" ? raw.trim().toLowerCase() : "";
  return APPROVALS.has(value) ? value : "none";
}

export const UUID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;

/** Reads a reminder sent by the phone. Null when it is not usable. */
export function parsePhoneReminder(raw: unknown): MirrorReminder | null {
  if (!isRecord(raw)) return null;
  const { uuid, title, dueAt, status, version, updatedAt } = raw;
  if (typeof uuid !== "string" || !UUID_PATTERN.test(uuid)) return null;
  if (typeof title !== "string" || title.length > 1000) return null;
  if (typeof dueAt !== "number" || !Number.isFinite(dueAt)) return null;
  if (status !== "active" && status !== "done" && status !== "deleted") return null;
  const repeat = typeof raw.repeat === "string" && raw.repeat.length <= 200 ? raw.repeat : null;
  return {
    uuid,
    title,
    dueAt: Math.trunc(dueAt),
    status,
    alertLevel: levelName(raw.alertLevel) ?? "normal",
    requestedAlertLevel: levelName(raw.requestedAlertLevel),
    approval: approvalName(raw.approval),
    repeat,
    version: typeof version === "number" && Number.isFinite(version) ? Math.trunc(version) : 1,
    updatedAt: typeof updatedAt === "number" && Number.isFinite(updatedAt) ? Math.trunc(updatedAt) : 0,
  };
}

export function iso(ms: number | null | undefined): string | null {
  if (typeof ms !== "number" || !Number.isFinite(ms)) return null;
  return new Date(ms).toISOString();
}

/** What an assistant sees of one of its reminders. */
export function reminderView(r: MirrorReminder): Record<string, unknown> {
  return {
    reminder_id: r.uuid,
    title: r.title,
    due_at: iso(r.dueAt),
    status: r.status,
    alert_level: r.alertLevel,
    requested_alert_level: r.requestedAlertLevel,
    approval: r.approval,
    repeat: r.repeat,
    updated_at: iso(r.updatedAt),
  };
}

export function mirrorFrom(data: FirebaseFirestore.DocumentData): MirrorReminder {
  return {
    uuid: data.uuid,
    title: data.title,
    dueAt: data.dueAt,
    status: data.status,
    alertLevel: data.alertLevel,
    requestedAlertLevel: data.requestedAlertLevel ?? null,
    approval: data.approval ?? "none",
    repeat: data.repeat ?? null,
    version: data.version ?? 1,
    updatedAt: data.updatedAt ?? 0,
  };
}
