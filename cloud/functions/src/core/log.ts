import * as logger from "firebase-functions/logger";

/**
 * The only way Do Cloud writes to function logs. Fields are limited to ids,
 * codes, counts and flags: never reminder text, titles, tokens or codes.
 * String values longer than 64 characters are cut, so a title passed here by
 * mistake can never appear whole (AT-9 also checks the logs).
 */
export type LogValue = string | number | boolean | null | undefined;

function clean(fields: Record<string, LogValue>): Record<string, LogValue> {
  const out: Record<string, LogValue> = {};
  for (const [key, value] of Object.entries(fields)) {
    out[key] = typeof value === "string" && value.length > 64 ? `${value.slice(0, 16)}...` : value;
  }
  return out;
}

export function logInfo(event: string, fields: Record<string, LogValue> = {}): void {
  logger.info(`do.${event}`, clean(fields));
}

export function logWarn(event: string, fields: Record<string, LogValue> = {}): void {
  logger.warn(`do.${event}`, clean(fields));
}

/** Logs the error's class and code only: messages can echo user input. */
export function logError(event: string, error: unknown, fields: Record<string, LogValue> = {}): void {
  const name = error instanceof Error ? error.name : typeof error;
  const code = (error as { code?: unknown } | null)?.code;
  logger.error(`do.${event}`, clean({ ...fields, error: name, code: typeof code === "string" || typeof code === "number" ? code : undefined }));
}
