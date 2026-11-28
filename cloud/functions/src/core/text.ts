/**
 * Trims, turns control characters and line or paragraph separators into
 * spaces, removes format characters (Unicode Cf: bidirectional overrides and
 * isolates, zero-width characters and the like), private-use and lone
 * surrogate code points, and cuts to [max] characters. Null when nothing is left.
 */
export function cleanText(raw: unknown, max: number): string | null {
  if (typeof raw !== "string") return null;
  const text = raw
    .replace(/[\p{Cf}\p{Co}\p{Cs}]+/gu, "")
    .replace(/[\p{Cc}\p{Zl}\p{Zp}]+/gu, " ")
    .replace(/ {2,}/g, " ")
    .trim();
  if (text.length === 0) return null;
  return Array.from(text).slice(0, max).join("");
}

/** Escapes text placed between tags (not in attributes): &, < and > only. */
export function escapeText(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
