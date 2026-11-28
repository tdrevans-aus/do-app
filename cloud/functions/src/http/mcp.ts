import { settings } from "../core/config";
import { isRecord } from "../core/text";
import { runTool, isToolName, type ToolDeps } from "../agent/service";
import { SERVER_INSTRUCTIONS, TOOLS } from "../agent/tools";
import type { Caller } from "../oauth/tokens";
import { json, type HttpRequest, type HttpResponse } from "./types";

// MCP over Streamable HTTP, written straight to the spec rather than through
// the SDK so it fits a stateless Cloud Function. Targets revision 2026-07-28
// (stateless: version and capabilities in each request's _meta, server/discover)
// and still serves 2025-11-25, 2025-06-18 and 2025-03-26 clients, which open
// with initialize. No sessions are issued, so no Mcp-Session-Id. Every answer
// is a single application/json response.

export const MODERN_VERSIONS = ["2026-07-28"];
export const LEGACY_VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26"];
const SUPPORTED = [...MODERN_VERSIONS, ...LEGACY_VERSIONS];
const SERVER_INFO = { name: "do-cloud", title: "Do", version: "1.0.0" };

const META_VERSION = "io.modelcontextprotocol/protocolVersion";

const ERR = {
  parse: -32700,
  invalidRequest: -32600,
  methodNotFound: -32601,
  invalidParams: -32602,
  internal: -32603,
  headerMismatch: -32020,
  unsupportedVersion: -32022,
};

type Id = string | number | null;

function rpcError(status: number, id: Id, code: number, message: string, data?: unknown): HttpResponse {
  const error: Record<string, unknown> = { code, message };
  if (data !== undefined) error.data = data;
  return json(status, { jsonrpc: "2.0", id, error });
}

function rpcResult(id: Id, result: unknown): HttpResponse {
  return json(200, { jsonrpc: "2.0", id, result });
}

/** RFC 9110 header values are ASCII; Mcp-Name may carry =?base64?...?= for anything else. */
function decodeHeader(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const match = /^=\?base64\?(.*)\?=$/.exec(value);
  return match ? Buffer.from(match[1] ?? "", "base64").toString("utf8") : value;
}

export function originAllowed(origin: string | undefined): boolean {
  if (origin === undefined) return true;
  return settings.allowedOrigins().includes(origin);
}

function toolCallResult(body: Record<string, unknown>, isError: boolean): Record<string, unknown> {
  return { content: [{ type: "text", text: JSON.stringify(body) }], structuredContent: body, isError };
}

async function dispatch(
  method: string,
  params: Record<string, unknown>,
  modern: boolean,
  caller: Caller,
  deps: ToolDeps | undefined,
): Promise<{ result?: unknown; error?: { code: number; message: string } }> {
  const complete = (result: Record<string, unknown>) =>
    modern ? { resultType: "complete", ...result, _meta: { "io.modelcontextprotocol/serverInfo": SERVER_INFO } } : result;
  switch (method) {
    case "server/discover":
      return {
        result: complete({
          supportedVersions: SUPPORTED,
          capabilities: { tools: {} },
          instructions: SERVER_INSTRUCTIONS,
          ttlMs: 3_600_000,
          cacheScope: "private",
        }),
      };
    case "ping":
      return { result: complete({}) };
    case "tools/list":
      return { result: complete(modern ? { tools: TOOLS, ttlMs: 3_600_000, cacheScope: "private" } : { tools: TOOLS }) };
    case "tools/call": {
      const name = params.name;
      if (!isToolName(name)) return { error: { code: ERR.invalidParams, message: "Unknown tool" } };
      const outcome = await runTool(caller, name, params.arguments, deps);
      return { result: complete(toolCallResult(outcome.body, outcome.isError)) };
    }
    default:
      return { error: { code: ERR.methodNotFound, message: "Method not found" } };
  }
}

export async function handleMcpMessage(req: HttpRequest, caller: Caller, deps?: ToolDeps): Promise<HttpResponse> {
  let message: unknown;
  try {
    message = req.rawBody !== undefined && req.rawBody !== "" ? JSON.parse(req.rawBody) : req.body;
  } catch {
    return rpcError(400, null, ERR.parse, "Parse error");
  }
  if (Array.isArray(message)) return rpcError(400, null, ERR.invalidRequest, "Batches are not supported");
  if (!isRecord(message) || message.jsonrpc !== "2.0" || typeof message.method !== "string") {
    // A JSON-RPC response or anything else that is not a request or notification.
    return rpcError(400, null, ERR.invalidRequest, "Invalid request");
  }
  const id = message.id;
  const isNotification = id === undefined;
  if (!isNotification && typeof id !== "string" && typeof id !== "number") {
    return rpcError(400, null, ERR.invalidRequest, "Invalid id");
  }
  if (isNotification) return { status: 202 };

  const method = message.method;
  const params = isRecord(message.params) ? message.params : {};
  const meta = isRecord(params._meta) ? params._meta : {};
  const headerVersion = req.headers["mcp-protocol-version"];

  if (method === "initialize") {
    const asked = typeof params.protocolVersion === "string" ? params.protocolVersion : "";
    const version = LEGACY_VERSIONS.includes(asked) ? asked : LEGACY_VERSIONS[0];
    return rpcResult(id, {
      protocolVersion: version,
      capabilities: { tools: {} },
      serverInfo: SERVER_INFO,
      instructions: SERVER_INSTRUCTIONS,
    });
  }

  const bodyVersion = typeof meta[META_VERSION] === "string" ? (meta[META_VERSION] as string) : undefined;
  let modern = false;
  if (bodyVersion !== undefined) {
    // Revision 2026-07-28: the headers mirror the body and must match it.
    if (headerVersion !== bodyVersion) {
      return rpcError(400, id, ERR.headerMismatch, "Header mismatch: MCP-Protocol-Version must match _meta protocolVersion");
    }
    if (!MODERN_VERSIONS.includes(bodyVersion)) {
      return rpcError(400, id, ERR.unsupportedVersion, "Unsupported protocol version", { supported: SUPPORTED, requested: bodyVersion });
    }
    if (req.headers["mcp-method"] !== method) {
      return rpcError(400, id, ERR.headerMismatch, "Header mismatch: Mcp-Method must match method");
    }
    if (method === "tools/call" && decodeHeader(req.headers["mcp-name"]) !== params.name) {
      return rpcError(400, id, ERR.headerMismatch, "Header mismatch: Mcp-Name must match params.name");
    }
    modern = true;
  } else if (headerVersion !== undefined && !LEGACY_VERSIONS.includes(headerVersion)) {
    // A legacy client (or a modern one without _meta) asking for a version we don't serve.
    return rpcError(400, id, ERR.unsupportedVersion, "Unsupported protocol version", { supported: SUPPORTED, requested: headerVersion });
  }

  if (modern && method === "ping") return rpcError(404, id, ERR.methodNotFound, "Method not found");
  if (!modern && method === "server/discover") {
    // Allowed for probing: answer as a modern server would.
    modern = true;
  }
  const { result, error } = await dispatch(method, params, modern, caller, deps);
  if (error) return rpcError(modern && error.code === ERR.methodNotFound ? 404 : 200, id, error.code, error.message);
  return rpcResult(id, result);
}
