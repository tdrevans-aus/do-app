import { baseUrl, mcpResource } from "../core/config";

export const SCOPE = "reminders";
export const SCOPES_SUPPORTED = [SCOPE];

/** RFC 8414 authorisation server metadata. The issuer is the site origin. */
export function authorizationServerMetadata(): Record<string, unknown> {
  const base = baseUrl();
  return {
    issuer: base,
    authorization_endpoint: `${base}/oauth/authorize`,
    token_endpoint: `${base}/oauth/token`,
    registration_endpoint: `${base}/oauth/register`,
    scopes_supported: SCOPES_SUPPORTED,
    response_types_supported: ["code"],
    response_modes_supported: ["query"],
    grant_types_supported: ["authorization_code", "refresh_token"],
    token_endpoint_auth_methods_supported: ["none"],
    code_challenge_methods_supported: ["S256"],
    client_id_metadata_document_supported: true,
    authorization_response_iss_parameter_supported: true,
    service_documentation: `${base}/`,
  };
}

/** RFC 9728 protected resource metadata for the MCP endpoint (and its REST mirror). */
export function protectedResourceMetadata(): Record<string, unknown> {
  return {
    resource: mcpResource(),
    authorization_servers: [baseUrl()],
    scopes_supported: SCOPES_SUPPORTED,
    bearer_methods_supported: ["header"],
    resource_name: "Do",
  };
}

export function resourceMetadataUrl(): string {
  return `${baseUrl()}/.well-known/oauth-protected-resource/mcp`;
}

/** The RFC 6750 / RFC 9728 challenge sent with every 401 from /mcp and /v1. */
export function bearerChallenge(error?: "invalid_token" | "invalid_request", description?: string): string {
  const parts = [`resource_metadata="${resourceMetadataUrl()}"`, `scope="${SCOPE}"`];
  if (error) parts.push(`error="${error}"`);
  if (description) parts.push(`error_description="${description.replace(/"/g, "'")}"`);
  return `Bearer ${parts.join(", ")}`;
}

/**
 * Normalises an RFC 8707 resource value. Accepts the MCP endpoint and the
 * site origin (both name this server) and returns the canonical MCP resource.
 * Scheme and host compare case-insensitively; a trailing slash is ignored.
 */
export function canonicalResource(raw: string | undefined): string | null {
  const wanted = mcpResource();
  if (raw === undefined || raw === "") return wanted;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.hash || url.search) return null;
  const given = `${url.protocol}//${url.host}${url.pathname}`.replace(/\/+$/, "");
  const base = new URL(baseUrl());
  const origin = `${base.protocol}//${base.host}`;
  if (given === wanted || given === origin) return wanted;
  return null;
}
