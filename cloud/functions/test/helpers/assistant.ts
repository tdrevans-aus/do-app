import { createHash, randomBytes } from "node:crypto";
import { BASE } from "./env";

// A test OAuth client that runs the whole MCP authorisation flow the way an
// assistant would: 401 challenge, protected resource metadata (RFC 9728),
// authorisation server metadata (RFC 8414), dynamic client registration
// (RFC 7591) or a client ID metadata document, authorise with PKCE and the
// pairing code, the token exchange and refresh rotation. Then it speaks MCP.

export const REDIRECT = "http://127.0.0.1:33418/callback";

export interface Discovery {
  resource: string;
  issuer: string;
  authorizationEndpoint: string;
  tokenEndpoint: string;
  registrationEndpoint: string;
  metadata: Record<string, unknown>;
}

export interface Tokens {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  token_type: string;
  scope: string;
}

function pkce() {
  const verifier = randomBytes(32).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge };
}

function parseChallenge(header: string | null): Record<string, string> {
  const out: Record<string, string> = {};
  if (!header) return out;
  for (const match of header.matchAll(/(\w+)="([^"]*)"/g)) out[match[1]!] = match[2]!;
  return out;
}

export function form(body: Record<string, string>): RequestInit {
  return {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(body).toString(),
    redirect: "manual",
  };
}

export class Assistant {
  clientId = "";
  tokens: Tokens | null = null;
  discovery: Discovery | null = null;
  private nextId = 1;

  constructor(readonly name = "Test Assistant") {}

  /** Steps 1 to 3: unauthenticated MCP call, then the two metadata documents. */
  async discover(): Promise<Discovery> {
    const first = await fetch(`${BASE}/mcp`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 0, method: "tools/list" }),
    });
    if (first.status !== 401) throw new Error(`expected 401, got ${first.status}`);
    const challenge = parseChallenge(first.headers.get("www-authenticate"));
    const prm = (await (await fetch(challenge.resource_metadata!)).json()) as Record<string, unknown>;
    const issuer = (prm.authorization_servers as string[])[0]!;
    // RFC 8414 section 3.1, issuer with no path.
    const asm = (await (await fetch(`${issuer}/.well-known/oauth-authorization-server`)).json()) as Record<string, unknown>;
    if (asm.issuer !== issuer) throw new Error("issuer mismatch");
    this.discovery = {
      resource: prm.resource as string,
      issuer,
      authorizationEndpoint: asm.authorization_endpoint as string,
      tokenEndpoint: asm.token_endpoint as string,
      registrationEndpoint: asm.registration_endpoint as string,
      metadata: { prm, asm, challenge },
    };
    return this.discovery;
  }

  async register(): Promise<string> {
    const d = this.discovery ?? (await this.discover());
    const response = await fetch(d.registrationEndpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        client_name: this.name,
        redirect_uris: [REDIRECT],
        grant_types: ["authorization_code", "refresh_token"],
        response_types: ["code"],
        token_endpoint_auth_method: "none",
        application_type: "native",
      }),
    });
    if (response.status !== 201) throw new Error(`register failed ${response.status}`);
    const body = (await response.json()) as { client_id: string };
    this.clientId = body.client_id;
    return this.clientId;
  }

  /** Opens the authorise page. Returns the page HTML and the PKCE verifier for this attempt. */
  async openAuthorize(extra: Record<string, string> = {}) {
    const d = this.discovery ?? (await this.discover());
    const { verifier, challenge } = pkce();
    const state = randomBytes(8).toString("hex");
    const url = new URL(d.authorizationEndpoint);
    const params: Record<string, string> = {
      response_type: "code",
      client_id: this.clientId,
      redirect_uri: REDIRECT,
      code_challenge: challenge,
      code_challenge_method: "S256",
      state,
      scope: "reminders",
      resource: d.resource,
      ...extra,
    };
    for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
    const response = await fetch(url, { redirect: "manual" });
    const html = await response.text();
    const requestId = /name="request_id" value="([^"]+)"/.exec(html)?.[1] ?? null;
    return { response, html, requestId, verifier, state };
  }

  async submitCode(requestId: string, code: string, action = "approve") {
    const d = this.discovery!;
    const response = await fetch(d.authorizationEndpoint, form({ request_id: requestId, code, action }));
    const location = response.headers.get("location");
    return { response, location: location ? new URL(location) : null, html: response.status === 302 ? "" : await response.text() };
  }

  async exchange(code: string, verifier: string): Promise<Response> {
    const d = this.discovery!;
    return fetch(
      d.tokenEndpoint,
      form({ grant_type: "authorization_code", code, redirect_uri: REDIRECT, client_id: this.clientId, code_verifier: verifier, resource: d.resource }),
    );
  }

  async refresh(refreshToken = this.tokens!.refresh_token): Promise<Response> {
    return fetch(this.discovery!.tokenEndpoint, form({ grant_type: "refresh_token", refresh_token: refreshToken, client_id: this.clientId }));
  }

  /** The whole flow with a pairing code. Leaves the assistant holding tokens. */
  async connect(pairingCode: string): Promise<Tokens> {
    if (!this.clientId) await this.register();
    const page = await this.openAuthorize();
    if (!page.requestId) throw new Error(`no authorise form (status ${page.response.status})`);
    const submit = await this.submitCode(page.requestId, pairingCode);
    if (!submit.location) throw new Error(`no redirect (status ${submit.response.status})`);
    if (submit.location.searchParams.get("iss") !== this.discovery!.issuer) throw new Error("iss mismatch (RFC 9207)");
    if (submit.location.searchParams.get("state") !== page.state) throw new Error("state mismatch");
    const code = submit.location.searchParams.get("code");
    if (!code) throw new Error(`authorise error ${submit.location.searchParams.get("error")}`);
    const response = await this.exchange(code, page.verifier);
    if (!response.ok) throw new Error(`token failed ${response.status}`);
    this.tokens = (await response.json()) as Tokens;
    return this.tokens;
  }

  /** A modern (2026-07-28) MCP request with the required headers and _meta. */
  async rpc(method: string, params: Record<string, unknown> = {}, opts: { token?: string; headers?: Record<string, string> } = {}) {
    const meta = {
      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
      "io.modelcontextprotocol/clientInfo": { name: this.name, version: "1.0.0" },
      "io.modelcontextprotocol/clientCapabilities": {},
    };
    const headers: Record<string, string> = {
      "content-type": "application/json",
      accept: "application/json, text/event-stream",
      "mcp-protocol-version": "2026-07-28",
      "mcp-method": method,
      authorization: `Bearer ${opts.token ?? this.tokens?.access_token ?? ""}`,
    };
    if (typeof params.name === "string") headers["mcp-name"] = params.name;
    const response = await fetch(`${BASE}/mcp`, {
      method: "POST",
      headers: { ...headers, ...opts.headers },
      body: JSON.stringify({ jsonrpc: "2.0", id: this.nextId++, method, params: { ...params, _meta: meta } }),
    });
    const text = await response.text();
    return { status: response.status, body: text ? (JSON.parse(text) as Record<string, any>) : null, headers: response.headers };
  }

  /** Calls a tool over MCP and returns structuredContent plus isError. */
  async tool(name: string, args: Record<string, unknown> = {}) {
    const response = await this.rpc("tools/call", { name, arguments: args });
    if (response.status !== 200) throw new Error(`tools/call HTTP ${response.status}`);
    const result = response.body!.result as { structuredContent: Record<string, any>; isError: boolean; content: Array<{ text: string }> };
    return { ...result.structuredContent, isError: result.isError, raw: result } as Record<string, any> & { isError: boolean };
  }

  /** The REST mirror. */
  async rest(name: string, args: Record<string, unknown> = {}, method: "GET" | "POST" = "POST") {
    const headers: Record<string, string> = { authorization: `Bearer ${this.tokens!.access_token}` };
    let url = `${BASE}/v1/${name}`;
    let body: string | undefined;
    if (method === "GET") {
      const qs = new URLSearchParams(Object.entries(args).map(([k, v]): [string, string] => [k, String(v)]));
      url += `?${qs}`;
    } else {
      headers["content-type"] = "application/json";
      body = JSON.stringify(args);
    }
    const response = await fetch(url, { method, headers, body });
    return { status: response.status, body: (await response.json()) as Record<string, any>, headers: response.headers };
  }
}

/** A phone and an assistant, paired. */
export async function pair(phone: { pairingCode(): Promise<string> }, name = "Test Assistant"): Promise<Assistant> {
  const assistant = new Assistant(name);
  await assistant.connect(await phone.pairingCode());
  return assistant;
}
