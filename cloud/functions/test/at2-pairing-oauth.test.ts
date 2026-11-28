import { createHash } from "node:crypto";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { adminDb } from "./helpers/admin";
import { Assistant, form, REDIRECT } from "./helpers/assistant";
import { BASE } from "./helpers/env";
import { Phone } from "./helpers/phone";

// AT-2: a pairing code works once, within 10 minutes; a wrong code five times
// burns it; the OAuth flow (metadata discovery, dynamic client registration,
// authorise with PKCE, token, refresh with rotation) completes in a test client.

function wrongVersionOf(code: string): string {
  // Same selector (first 4), different secret part: charged to this code.
  const last = code[7] === "Z" ? "Y" : "Z";
  return `${code.slice(0, 7)}${last}`;
}

describe("AT-2 discovery and metadata", () => {
  it("publishes protected resource and authorisation server metadata", async () => {
    const assistant = new Assistant();
    const d = await assistant.discover();
    const { prm, asm, challenge } = d.metadata as Record<string, Record<string, unknown>>;
    expect(challenge!.resource_metadata).toBe(`${BASE}/.well-known/oauth-protected-resource/mcp`);
    expect(challenge!.scope).toBe("reminders");
    expect(prm!.resource).toBe(`${BASE}/mcp`);
    expect(prm!.authorization_servers).toEqual([BASE]);
    expect(asm!.code_challenge_methods_supported).toEqual(["S256"]);
    expect(asm!.grant_types_supported).toEqual(["authorization_code", "refresh_token"]);
    expect(asm!.client_id_metadata_document_supported).toBe(true);
    expect(asm!.authorization_response_iss_parameter_supported).toBe(true);
    expect(asm!.registration_endpoint).toBe(`${BASE}/oauth/register`);
    const root = await (await fetch(`${BASE}/.well-known/oauth-protected-resource`)).json() as any;
    expect(root).toEqual(prm);
  });
});

describe("AT-2 full OAuth flow", () => {
  it("registers, authorises with the pairing code and PKCE, exchanges, refreshes with rotation", async () => {
    const phone = await Phone.create();
    const assistant = new Assistant("Flow Assistant");
    await assistant.discover();
    const clientId = await assistant.register();
    expect(clientId).toMatch(/^do_client_/);

    const code = await phone.pairingCode();
    expect(code).toMatch(/^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$/);

    const page = await assistant.openAuthorize();
    expect(page.response.status).toBe(200);
    expect(page.html).toContain("Enter the code shown in Do");
    expect(page.html).toContain('lang="en-AU"');
    expect(page.html).toContain('<label for="code">');
    expect(page.response.headers.get("x-frame-options")).toBe("DENY");

    const submit = await assistant.submitCode(page.requestId!, code.toLowerCase().replace(/^(.{4})/, "$1-"));
    expect(submit.response.status).toBe(302);
    const location = submit.location!;
    expect(`${location.origin}${location.pathname}`).toBe(REDIRECT);
    expect(location.searchParams.get("state")).toBe(page.state);
    expect(location.searchParams.get("iss")).toBe(BASE);
    const authCode = location.searchParams.get("code")!;

    // A wrong verifier fails (PKCE), and so does a second use of the code afterwards.
    const wrong = await assistant.exchange(authCode, "x".repeat(43));
    expect(wrong.status).toBe(400);
    expect((await wrong.json() as any).error).toBe("invalid_grant");
    // The code was not used up by the failed attempt.
    const ok = await assistant.exchange(authCode, page.verifier);
    expect(ok.status).toBe(200);
    expect(ok.headers.get("cache-control")).toBe("no-store");
    const tokens = await ok.json() as any;
    expect(tokens.token_type).toBe("Bearer");
    expect(tokens.expires_in).toBe(3600);
    expect(tokens.scope).toBe("reminders");
    assistant.tokens = tokens;

    const replay = await assistant.exchange(authCode, page.verifier);
    expect(replay.status).toBe(400);

    // Replaying the code revoked the grant (OAuth 2.1 4.1.2), so connect afresh for the rest.
    const listAfterReplay = await assistant.rpc("tools/list");
    expect(listAfterReplay.status).toBe(401);
    expect(listAfterReplay.headers.get("www-authenticate")).toContain('error="invalid_token"');

    await assistant.connect(await phone.pairingCode());
    const list = await assistant.rpc("tools/list");
    expect(list.status).toBe(200);
    expect(list.body!.result.tools.map((t: { name: string }) => t.name)).toEqual([
      "create_reminder",
      "complete_reminder",
      "list_reminders",
      "get_events",
    ]);

    // Refresh rotation: a new pair each time; the old refresh token is dead, and reusing it kills the grant.
    const firstRefresh = assistant.tokens!.refresh_token;
    const r1 = await assistant.refresh();
    expect(r1.status).toBe(200);
    const rotated = await r1.json() as any;
    expect(rotated.refresh_token).not.toBe(firstRefresh);
    expect(rotated.access_token).not.toBe(assistant.tokens!.access_token);
    assistant.tokens = rotated;
    expect((await assistant.rpc("tools/list")).status).toBe(200);

    // Within 30 seconds a retry with the old refresh token gets the same new pair (a lost response).
    const retry = await assistant.refresh(firstRefresh);
    expect(retry.status).toBe(200);
    const retried = (await retry.json()) as any;
    expect(retried.refresh_token).toBe(rotated.refresh_token);
    expect(retried.access_token).toBe(rotated.access_token);
    // After the grace window, reuse is theft: the grant is revoked.
    const oldDoc = adminDb().collection("tokens").doc(createHash("sha256").update(`token:${firstRefresh}`).digest("hex"));
    await oldDoc.update({ rotatedAt: Date.now() - 31_000 });
    const reuse = await assistant.refresh(firstRefresh);
    expect(reuse.status).toBe(400);
    expect((await reuse.json() as any).error).toBe("invalid_grant");
    expect((await assistant.rpc("tools/list")).status).toBe(401);
    expect((await assistant.refresh(rotated.refresh_token)).status).toBe(400);

    // Tokens are stored hashed: no stored document holds the raw token.
    const stored = await adminDb().collection("tokens").get();
    for (const doc of stored.docs) {
      expect(doc.id).not.toContain(rotated.access_token);
      expect(JSON.stringify(doc.data())).not.toContain(rotated.access_token);
    }
  });

  it("a code works once and only within 10 minutes", async () => {
    const phone = await Phone.create();
    const code = await phone.pairingCode();
    const first = new Assistant();
    await first.connect(code);
    expect(first.tokens!.access_token).toMatch(/^do_at_/);

    const second = new Assistant();
    await second.register();
    const page = await second.openAuthorize();
    const again = await second.submitCode(page.requestId!, code);
    expect(again.response.status).toBe(400);
    expect(again.html).toContain("That code didn't work");

    // Expiry: move the stored expiry into the past (the 10 minutes have passed).
    const fresh = await phone.pairingCode();
    const docs = await adminDb().collection("pairingCodes").where("deviceUid", "==", phone.uid).get();
    expect(docs.size).toBe(1);
    const stored = docs.docs[0]!.data();
    expect(stored.expiresAt - stored.createdAt).toBe(10 * 60 * 1000);
    expect(JSON.stringify(stored)).not.toContain(fresh);
    await docs.docs[0]!.ref.update({ expiresAt: Date.now() - 1 });
    const late = new Assistant();
    await late.register();
    const latePage = await late.openAuthorize();
    const lateSubmit = await late.submitCode(latePage.requestId!, fresh);
    expect(lateSubmit.response.status).toBe(400);
    expect(lateSubmit.html).toContain("That code didn't work");
  });

  it("five wrong tries burn the code", async () => {
    const phone = await Phone.create();
    const code = await phone.pairingCode();
    const wrong = wrongVersionOf(code);
    const assistants: Assistant[] = [];
    for (let i = 0; i < 5; i++) {
      // A fresh authorise page each time, so it is the code that is burnt, not the page.
      const a = new Assistant();
      await a.register();
      const page = await a.openAuthorize();
      const submit = await a.submitCode(page.requestId!, wrong);
      expect(submit.response.status).toBe(400);
      expect(submit.html).toContain('role="alert"');
      assistants.push(a);
    }
    const last = new Assistant();
    await last.register();
    const page = await last.openAuthorize();
    const submit = await last.submitCode(page.requestId!, code);
    expect(submit.response.status).toBe(400);
    expect(submit.html).toContain("That code didn't work");
  });

  it("five wrong codes on one page end the authorisation with access_denied", async () => {
    const a = new Assistant();
    await a.register();
    const page = await a.openAuthorize();
    let last;
    for (let i = 0; i < 5; i++) last = await a.submitCode(page.requestId!, "ZZZZZZZZ");
    expect(last!.response.status).toBe(302);
    expect(last!.location!.searchParams.get("error")).toBe("access_denied");
  });

  it("cancel sends access_denied back to the assistant", async () => {
    const a = new Assistant();
    await a.register();
    const page = await a.openAuthorize();
    const submit = await a.submitCode(page.requestId!, "", "cancel");
    expect(submit.location!.searchParams.get("error")).toBe("access_denied");
    expect(submit.location!.searchParams.get("state")).toBe(page.state);
  });

  it("refuses requests without PKCE S256, with a bad redirect or a foreign resource", async () => {
    const a = new Assistant();
    await a.register();
    const plain = await a.openAuthorize({ code_challenge_method: "plain" });
    expect(plain.response.status).toBe(302);
    expect(new URL(plain.response.headers.get("location")!).searchParams.get("error")).toBe("invalid_request");
    const badRedirect = await a.openAuthorize({ redirect_uri: "https://evil.example/cb" });
    expect(badRedirect.response.status).toBe(400);
    expect(badRedirect.response.headers.get("location")).toBeNull();
    const foreign = await a.openAuthorize({ resource: "https://other.example/mcp" });
    expect(new URL(foreign.response.headers.get("location")!).searchParams.get("error")).toBe("invalid_target");
    const unknown = await fetch(`${BASE}/oauth/authorize?client_id=do_client_${"0".repeat(32)}&redirect_uri=${encodeURIComponent(REDIRECT)}`);
    expect(unknown.status).toBe(400);
    const reg = await fetch(`${BASE}/oauth/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ redirect_uris: ["http://evil.example/cb"] }),
    });
    expect(reg.status).toBe(400);
    expect((await reg.json() as any).error).toBe("invalid_redirect_uri");
    const badGrant = await fetch(`${BASE}/oauth/token`, form({ grant_type: "password", username: "x" }));
    expect((await badGrant.json() as any).error).toBe("unsupported_grant_type");
  });
});

describe("AT-2 client ID metadata documents", () => {
  let server: http.Server;
  let clientId = "";
  beforeAll(async () => {
    server = http.createServer((req, res) => {
      if (req.url === "/client.json") {
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ client_id: clientId, client_name: "Metadata Assistant", redirect_uris: [REDIRECT], token_endpoint_auth_method: "none" }));
      } else {
        res.writeHead(404);
        res.end();
      }
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    clientId = `http://127.0.0.1:${(server.address() as AddressInfo).port}/client.json`;
  });
  afterAll(() => new Promise<void>((resolve) => server.close(() => resolve())));

  it("uses a URL client_id without registration", async () => {
    const phone = await Phone.create();
    const a = new Assistant("Metadata Assistant");
    a.clientId = clientId;
    await a.connect(await phone.pairingCode());
    const agents = await adminDb().collection(`devices/${phone.uid}/agents`).get();
    expect(agents.docs.map((d) => d.data().clientName)).toEqual(["Metadata Assistant"]);
    expect((await a.rpc("tools/list")).status).toBe(200);
  });
});
