import { logError, logInfo } from "../core/log";
import type { ToolDeps } from "../agent/service";
import { finishAuthorization, startAuthorization } from "../oauth/authorize";
import { ClientError, registerClient, type MetadataFetcher } from "../oauth/clients";
import { authorizationServerMetadata, bearerChallenge, protectedResourceMetadata } from "../oauth/metadata";
import { tokenEndpoint } from "../oauth/token";
import { verifyAccessToken } from "../oauth/tokens";
import { handleMcpMessage, originAllowed } from "./mcp";
import { handleRest } from "./rest";
import { html, json, type HttpRequest, type HttpResponse } from "./types";
import { messagePage } from "../oauth/page";

// Every public path of Do Cloud, behind one HTTPS function (`api`). Firebase
// Hosting rewrites the whole site to it, so the well-known documents sit at
// the root of the origin.

export interface AppDeps {
  now: () => number;
  tools?: ToolDeps;
  fetcher?: MetadataFetcher;
}

const JSON_PUBLIC = { "cache-control": "public, max-age=300", "access-control-allow-origin": "*" };

function bearer(req: HttpRequest): string | undefined {
  const header = req.headers.authorization;
  if (!header) return undefined;
  const match = /^Bearer\s+(\S+)$/i.exec(header);
  return match?.[1];
}

function unauthorised(hadToken: boolean): HttpResponse {
  return json(
    401,
    { error: hadToken ? "invalid_token" : "unauthorized", error_description: hadToken ? "The access token is not valid or has expired" : "Sign in with OAuth first" },
    { "www-authenticate": hadToken ? bearerChallenge("invalid_token", "The access token is not valid or has expired") : bearerChallenge() },
  );
}

async function route(req: HttpRequest, deps: AppDeps): Promise<HttpResponse> {
  const now = deps.now();
  const { method, path } = req;

  if (method === "GET" && (path === "/.well-known/oauth-protected-resource" || path === "/.well-known/oauth-protected-resource/mcp")) {
    return json(200, protectedResourceMetadata(), JSON_PUBLIC);
  }
  if (method === "GET" && path === "/.well-known/oauth-authorization-server") {
    return json(200, authorizationServerMetadata(), JSON_PUBLIC);
  }
  if (path === "/oauth/register") {
    if (method !== "POST") return json(405, { error: "invalid_request" }, { allow: "POST" });
    try {
      const body = await registerClient(req.body, now);
      logInfo("oauth.registered", {});
      return json(201, body, { "cache-control": "no-store" });
    } catch (error) {
      if (error instanceof ClientError) return json(400, { error: error.error, error_description: error.message });
      throw error;
    }
  }
  if (path === "/oauth/authorize") {
    if (method === "GET") return startAuthorization(req, now, deps.fetcher);
    if (method === "POST") return finishAuthorization(req, now, deps.fetcher);
    return json(405, { error: "invalid_request" }, { allow: "GET, POST" });
  }
  if (path === "/oauth/token") return tokenEndpoint(req, now);

  if (path === "/mcp") {
    if (method !== "POST") return json(405, { error: "Use POST for MCP requests" }, { allow: "POST" });
    if (!originAllowed(req.headers.origin)) {
      return json(403, { jsonrpc: "2.0", error: { code: -32600, message: "Origin not allowed" } });
    }
    const token = bearer(req);
    const caller = await verifyAccessToken(token, now);
    if (!caller) return unauthorised(token !== undefined);
    return handleMcpMessage(req, caller, deps.tools);
  }
  if (path.startsWith("/v1/")) {
    const token = bearer(req);
    const caller = await verifyAccessToken(token, now);
    if (!caller) return unauthorised(token !== undefined);
    return handleRest(req, caller, deps.tools);
  }
  if (method === "GET" && (path === "/" || path === "")) {
    return html(200, messagePage("Do Cloud", "This is the service that lets assistants send reminders to Do on your phone. To connect one, open Do and go to Assistants."));
  }
  return json(404, { error: "not_found" });
}

export async function handle(req: HttpRequest, deps: AppDeps): Promise<HttpResponse> {
  try {
    const response = await route(req, deps);
    response.headers = { "cache-control": "no-store", ...response.headers };
    return response;
  } catch (error) {
    logError("http.failed", error, { path: req.path.slice(0, 40) });
    return json(500, { error: "server_error" }, { "cache-control": "no-store" });
  }
}
