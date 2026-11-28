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
  /** The client's IP as seen through Hosting (see http/ip.ts). For rate limits only. */
  ip?: string;
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

/**
 * An HTML page with a strict policy: no scripts at all, no framing, inline
 * styles only. [formAction] lists extra form-action sources: browsers apply
 * form-action to every redirect a form submission leads to (CSP Level 3), so
 * the code form must also allow the assistant's redirect URI, and nothing more.
 */
export function html(status: number, body: string, headers: Record<string, string> = {}, formAction: string[] = []): HttpResponse {
  const formSources = ["'self'", ...formAction].join(" ");
  return {
    status,
    body,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "x-frame-options": "DENY",
      "x-content-type-options": "nosniff",
      "content-security-policy": `default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src 'none'; form-action ${formSources}; frame-ancestors 'none'; base-uri 'none'`,
      "referrer-policy": "no-referrer",
      ...headers,
    },
  };
}

/**
 * The CSP source that matches exactly where a redirect URI points: its
 * origin for http and https, its scheme for a private-use scheme (CSP can't
 * name less than a whole scheme there). Null if it can't be expressed safely.
 */
export function cspSourceFor(uri: string): string | null {
  let url: URL;
  try {
    url = new URL(uri);
  } catch {
    return null;
  }
  if (url.protocol === "https:" || url.protocol === "http:") {
    return /^[a-z0-9.\-\[\]:]+$/i.test(url.host) ? url.origin : null;
  }
  return /^[a-z][a-z0-9+.-]*:$/.test(url.protocol) ? url.protocol : null;
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
