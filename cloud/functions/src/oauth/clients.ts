import { inEmulator } from "../core/config";
import { isIpLiteral, isLocalName, normaliseHost } from "../core/hosts";
import { fetchMetadataDocument } from "./fetchMetadata";
import { randomId, sha256 } from "../core/crypto";
import { db, paths } from "../core/db";
import { cleanText, isRecord } from "../core/text";

// OAuth clients. Two ways in, as the MCP authorisation spec describes:
// Client ID Metadata Documents (an https URL as client_id, fetched on use) and
// RFC 7591 dynamic client registration (kept for older clients). Every client
// is public: no secrets, PKCE is required.

export interface OAuthClient {
  clientId: string;
  clientName: string;
  redirectUris: string[];
  kind: "registered" | "metadata";
}

export class ClientError extends Error {
  constructor(
    readonly error: "invalid_client_metadata" | "invalid_redirect_uri" | "invalid_client",
    message: string,
  ) {
    super(message);
    this.name = "ClientError";
  }
}

const BLOCKED_SCHEMES = new Set(["javascript:", "data:", "file:", "vbscript:", "blob:", "about:"]);
const LOOPBACK = new Set(["127.0.0.1", "localhost", "[::1]"]);
const MAX_REDIRECTS = 10;

/** https anywhere, http only on loopback, or a private-use scheme for native apps (RFC 8252). */
export function validRedirectUri(raw: unknown): raw is string {
  if (typeof raw !== "string" || raw.length > 2000) return false;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return false;
  }
  if (url.hash) return false;
  if (url.protocol === "https:") return true;
  if (url.protocol === "http:") return LOOPBACK.has(url.hostname);
  return !BLOCKED_SCHEMES.has(url.protocol) && /^[a-z][a-z0-9+.-]*:$/.test(url.protocol);
}

/**
 * Exact match, except that a loopback http redirect may use any port
 * (OAuth 2.1 section 8.4.2, for native apps on an ephemeral port).
 */
export function redirectMatches(client: OAuthClient, given: string | undefined): string | null {
  if (given === undefined) return client.redirectUris.length === 1 ? (client.redirectUris[0] ?? null) : null;
  if (client.redirectUris.includes(given)) return given;
  let url: URL;
  try {
    url = new URL(given);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" || !LOOPBACK.has(url.hostname)) return null;
  for (const registered of client.redirectUris) {
    try {
      const r = new URL(registered);
      if (r.protocol === "http:" && r.hostname === url.hostname && r.pathname === url.pathname && r.search === url.search) {
        return given;
      }
    } catch {
      // ignore
    }
  }
  return null;
}

function nameFrom(raw: unknown): string {
  return cleanText(raw, 60) ?? "Assistant";
}

/** RFC 7591 registration. Returns the registration response body. */
export async function registerClient(body: unknown, now: number): Promise<Record<string, unknown>> {
  if (!isRecord(body)) throw new ClientError("invalid_client_metadata", "Body must be a JSON object");
  const uris = body.redirect_uris;
  if (!Array.isArray(uris) || uris.length === 0 || uris.length > MAX_REDIRECTS) {
    throw new ClientError("invalid_redirect_uri", "redirect_uris must list 1 to 10 URIs");
  }
  if (!uris.every(validRedirectUri)) throw new ClientError("invalid_redirect_uri", "A redirect URI is not allowed");
  const grantTypes = body.grant_types;
  if (grantTypes !== undefined) {
    if (!Array.isArray(grantTypes) || !grantTypes.includes("authorization_code")) {
      throw new ClientError("invalid_client_metadata", "grant_types must include authorization_code");
    }
  }
  const responseTypes = body.response_types;
  if (responseTypes !== undefined && (!Array.isArray(responseTypes) || !responseTypes.includes("code"))) {
    throw new ClientError("invalid_client_metadata", "response_types must include code");
  }
  const clientId = `do_client_${randomId(16)}`;
  const clientName = nameFrom(body.client_name);
  await db()
    .collection(paths.oauthClients)
    .doc(clientId)
    .set({ kind: "registered", clientName, redirectUris: uris, createdAt: now, usedAt: null });
  // The server may replace values it does not support (RFC 7591 section 3.2.1):
  // every client here is public and uses PKCE.
  return {
    client_id: clientId,
    client_id_issued_at: Math.floor(now / 1000),
    client_name: clientName,
    redirect_uris: uris,
    grant_types: ["authorization_code", "refresh_token"],
    response_types: ["code"],
    token_endpoint_auth_method: "none",
    scope: "reminders",
  };
}

/** Fetches a Client ID Metadata Document. Injected so tests and production can differ. */
export type MetadataFetcher = (url: string) => Promise<unknown>;

/** The production fetcher: streamed, capped, no redirects, public addresses only (see fetchMetadata.ts). */
export const defaultFetcher: MetadataFetcher = async (url) => {
  try {
    return await fetchMetadataDocument(url);
  } catch {
    throw new ClientError("invalid_client", "The client metadata document could not be fetched");
  }
};

/**
 * True for a client_id that is a metadata document URL: https with a path,
 * and a public DNS name (not an IP literal, loopback or local name, after
 * normalising case, trailing dots and IDNA). In the emulator a loopback http
 * URL is also allowed so the test client can host its own document. Names
 * that resolve to private addresses are refused when fetched.
 */
export function isMetadataClientId(clientId: string): boolean {
  let url: URL;
  try {
    url = new URL(clientId);
  } catch {
    return false;
  }
  if (url.hash || url.username || url.password) return false;
  if (url.pathname === "/" || url.pathname === "") return false;
  const host = normaliseHost(url.hostname);
  if (url.protocol === "http:" && inEmulator() && LOOPBACK.has(host)) return true;
  if (url.protocol !== "https:") return false;
  if (url.port !== "" && url.port !== "443") return false;
  if (isIpLiteral(host) || isLocalName(host)) return false;
  return true;
}

const METADATA_CACHE_MS = 60 * 60 * 1000;

async function metadataClient(clientId: string, now: number, fetcher: MetadataFetcher): Promise<OAuthClient> {
  const cacheRef = db().collection(paths.oauthClients).doc(`cimd_${sha256(clientId)}`);
  const cached = (await cacheRef.get()).data();
  if (cached && cached.clientId === clientId && cached.fetchedAt > now - METADATA_CACHE_MS) {
    return { clientId, clientName: cached.clientName, redirectUris: cached.redirectUris, kind: "metadata" };
  }
  let doc: unknown;
  try {
    doc = await fetcher(clientId);
  } catch (error) {
    if (error instanceof ClientError) throw error;
    throw new ClientError("invalid_client", "The client metadata document could not be fetched");
  }
  if (!isRecord(doc)) throw new ClientError("invalid_client", "The client metadata document is not a JSON object");
  if (doc.client_id !== clientId) throw new ClientError("invalid_client", "client_id in the metadata document does not match");
  const uris = doc.redirect_uris;
  if (!Array.isArray(uris) || uris.length === 0 || uris.length > MAX_REDIRECTS || !uris.every(validRedirectUri)) {
    throw new ClientError("invalid_client", "redirect_uris in the metadata document are missing or not allowed");
  }
  const method = doc.token_endpoint_auth_method;
  if (method !== undefined && method !== "none") {
    throw new ClientError("invalid_client", "Only public clients (token_endpoint_auth_method none) are supported");
  }
  const clientName = nameFrom(doc.client_name);
  await cacheRef.set({ kind: "metadata", clientId, clientName, redirectUris: uris, fetchedAt: now, createdAt: now, usedAt: now });
  return { clientId, clientName, redirectUris: uris as string[], kind: "metadata" };
}

export async function resolveClient(
  clientId: string | undefined,
  now: number,
  fetcher: MetadataFetcher = defaultFetcher,
): Promise<OAuthClient> {
  if (!clientId || clientId.length > 2000) throw new ClientError("invalid_client", "client_id is missing");
  if (isMetadataClientId(clientId)) return metadataClient(clientId, now, fetcher);
  if (!/^do_client_[0-9a-f]{32}$/.test(clientId)) throw new ClientError("invalid_client", "Unknown client_id");
  const data = (await db().collection(paths.oauthClients).doc(clientId).get()).data();
  if (!data) throw new ClientError("invalid_client", "Unknown client_id");
  return { clientId, clientName: data.clientName, redirectUris: data.redirectUris, kind: "registered" };
}

/** Marks a client as used, so the clean-up keeps it. */
export async function markClientUsed(client: OAuthClient, now: number): Promise<void> {
  const id = client.kind === "registered" ? client.clientId : `cimd_${sha256(client.clientId)}`;
  await db().collection(paths.oauthClients).doc(id).set({ usedAt: now }, { merge: true });
}
