import { isToolName, runTool, type ToolDeps } from "../agent/service";
import type { Caller } from "../oauth/tokens";
import { json, type HttpRequest, type HttpResponse } from "./types";

// The REST mirror (Spec 13, section D): POST /v1/{tool} with the tool's
// arguments as a JSON body, and GET for the two read tools with arguments in
// the query string. The response body is the tool result, the same JSON MCP
// returns in structuredContent.

const READ_TOOLS = new Set(["list_reminders", "get_events"]);

export async function handleRest(req: HttpRequest, caller: Caller, deps?: ToolDeps): Promise<HttpResponse> {
  const name = req.path.slice("/v1/".length);
  if (!isToolName(name)) {
    return json(404, { status: "error", error: { code: "not_found", message: "Unknown endpoint" } });
  }
  let args: unknown;
  if (req.method === "GET" && READ_TOOLS.has(name)) {
    args = { ...req.query };
  } else if (req.method === "POST") {
    args = req.body === undefined || req.body === "" ? {} : req.body;
  } else {
    return json(405, { status: "error", error: { code: "invalid_argument", message: "Method not allowed" } }, { allow: READ_TOOLS.has(name) ? "GET, POST" : "POST" });
  }
  const outcome = await runTool(caller, name, args, deps);
  const headers: Record<string, string> = {};
  if (outcome.retryAfterSeconds !== undefined) headers["retry-after"] = String(outcome.retryAfterSeconds);
  return json(outcome.http, outcome.body, headers);
}
