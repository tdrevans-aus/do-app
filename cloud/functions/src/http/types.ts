/** A framework-free view of one HTTP request, so handlers stay small and testable. */
export interface HttpRequest {
  method: string;
  /** Path with no query string, for example "/mcp". */
  path: string;
  /** Header names are lower case. */
  headers: Record<string, string | undefined>;
  query: Record<string, string | undefined>;
  /** Parsed form or JSON body, when the platform parsed one. */
  body: unknown;
  /** The body as text, when there was one. */
  rawBody: string | undefined;
}

export interface HttpResponse {
  status: number;
  headers?: Record<string, string>;
  /** Objects are sent as JSON, strings as they are. Undefined sends no body. */
  body?: unknown;
}

export function json(status: number, body: unknown, headers: Record<string, string> = {}): HttpResponse {
  return { status, body, headers: { "content-type": "application/json", ...headers } };
}

export function html(status: number, body: string, headers: Record<string, string> = {}): HttpResponse {
  return {
    status,
    body,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "x-frame-options": "DENY",
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
      "referrer-policy": "no-referrer",
      ...headers,
    },
  };
}

export function redirect(location: string): HttpResponse {
  return { status: 302, headers: { location } };
}

/** Reads a field from a urlencoded or JSON body. */
export function field(body: unknown, name: string): string | undefined {
  if (typeof body !== "object" || body === null) return undefined;
  const value = (body as Record<string, unknown>)[name];
  return typeof value === "string" ? value : undefined;
}
