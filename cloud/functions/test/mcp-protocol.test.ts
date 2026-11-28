import { describe, expect, it } from "vitest";
import { pair } from "./helpers/assistant";
import { BASE } from "./helpers/env";
import { Phone } from "./helpers/phone";

// The MCP transport details: revision 2026-07-28 (headers, _meta, server/discover)
// and the earlier initialize-based revisions.

describe("MCP Streamable HTTP", () => {
  it("speaks 2026-07-28: discover, header checks, unsupported versions", async () => {
    const phone = await Phone.create();
    const a = await pair(phone);
    const discover = await a.rpc("server/discover");
    expect(discover.status).toBe(200);
    expect(discover.headers.get("content-type")).toContain("application/json");
    expect(discover.body!.result).toMatchObject({ resultType: "complete", supportedVersions: expect.arrayContaining(["2026-07-28", "2025-11-25"]) });
    expect(discover.body!.result._meta["io.modelcontextprotocol/serverInfo"].name).toBe("do-cloud");

    const list = await a.rpc("tools/list");
    expect(list.body!.result).toMatchObject({ resultType: "complete", cacheScope: "private" });
    for (const tool of list.body!.result.tools) expect(tool.inputSchema.type).toBe("object");

    const mismatch = await a.rpc("tools/list", {}, { headers: { "mcp-method": "tools/call" } });
    expect(mismatch.status).toBe(400);
    expect(mismatch.body!.error.code).toBe(-32020);
    const nameMismatch = await a.rpc("tools/call", { name: "list_reminders", arguments: {} }, { headers: { "mcp-name": "get_events" } });
    expect(nameMismatch.body!.error.code).toBe(-32020);
    const encodedName = await a.rpc("tools/call", { name: "list_reminders", arguments: {} }, { headers: { "mcp-name": `=?base64?${Buffer.from("list_reminders").toString("base64")}?=` } });
    expect(encodedName.status).toBe(200);
    const unknownMethod = await a.rpc("resources/list");
    expect(unknownMethod.status).toBe(404);
    expect(unknownMethod.body!.error.code).toBe(-32601);
    const unknownTool = await a.rpc("tools/call", { name: "delete_everything", arguments: {} });
    expect(unknownTool.body!.error.code).toBe(-32602);

    const future = await fetch(`${BASE}/mcp`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${a.tokens!.access_token}`, "mcp-protocol-version": "2099-01-01", "mcp-method": "tools/list" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 9, method: "tools/list", params: { _meta: { "io.modelcontextprotocol/protocolVersion": "2099-01-01" } } }),
    });
    expect(future.status).toBe(400);
    const body = await future.json() as any;
    expect(body.error.code).toBe(-32022);
    expect(body.error.data.supported).toContain("2026-07-28");
  });

  it("serves initialize-based clients (2025-11-25 and earlier) with no session", async () => {
    const phone = await Phone.create();
    const a = await pair(phone);
    const post = (body: unknown, version?: string) =>
      fetch(`${BASE}/mcp`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          accept: "application/json, text/event-stream",
          authorization: `Bearer ${a.tokens!.access_token}`,
          ...(version ? { "mcp-protocol-version": version } : {}),
        },
        body: JSON.stringify(body),
      });
    const init = await post({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "old", version: "1" } } });
    expect(init.status).toBe(200);
    expect(init.headers.get("mcp-session-id")).toBeNull();
    const initBody = await init.json() as any;
    expect(initBody.result.protocolVersion).toBe("2025-06-18");
    expect(initBody.result.capabilities.tools).toEqual({});
    const notified = await post({ jsonrpc: "2.0", method: "notifications/initialized" }, "2025-06-18");
    expect(notified.status).toBe(202);
    const tools = await (await post({ jsonrpc: "2.0", id: 2, method: "tools/list" }, "2025-06-18")).json() as any;
    expect(tools.result.tools).toHaveLength(4);
    expect(tools.result.resultType).toBeUndefined();
    const call = await (await post({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "list_reminders", arguments: {} } }, "2025-06-18")).json() as any;
    expect(call.result.structuredContent).toEqual({ status: "ok", reminders: [] });
    const ping = await (await post({ jsonrpc: "2.0", id: 4, method: "ping" }, "2025-11-25")).json() as any;
    expect(ping.result).toEqual({});
    const newest = await post({ jsonrpc: "2.0", id: 5, method: "initialize", params: { protocolVersion: "2030-01-01" } });
    expect((await newest.json() as any).result.protocolVersion).toBe("2025-11-25");
  });

  it("checks method, Origin, auth and JSON", async () => {
    const phone = await Phone.create();
    const a = await pair(phone);
    expect((await fetch(`${BASE}/mcp`)).status).toBe(405);
    expect((await fetch(`${BASE}/mcp`, { method: "DELETE" })).status).toBe(405);
    const evil = await a.rpc("tools/list", {}, { headers: { origin: "https://evil.example" } });
    expect(evil.status).toBe(403);
    const own = await a.rpc("tools/list", {}, { headers: { origin: BASE } });
    expect(own.status).toBe(200);
    const bad = await a.rpc("tools/list", {}, { token: "do_at_not-a-real-token" });
    expect(bad.status).toBe(401);
    expect(bad.headers.get("www-authenticate")).toContain("resource_metadata=");
    const parse = await fetch(`${BASE}/mcp`, {
      method: "POST",
      headers: { "content-type": "text/plain", authorization: `Bearer ${a.tokens!.access_token}` },
      body: "{not json",
    });
    expect(parse.status).toBe(400);
    expect((await parse.json() as any).error.code).toBe(-32700);
    const batch = await fetch(`${BASE}/mcp`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${a.tokens!.access_token}` },
      body: JSON.stringify([{ jsonrpc: "2.0", id: 1, method: "tools/list" }]),
    });
    expect(batch.status).toBe(400);
  });
});
