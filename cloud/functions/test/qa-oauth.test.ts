import { createHash } from "node:crypto";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { defaultFetcher, isMetadataClientId } from "../src/oauth/clients";
import { fetchMetadataDocument, MAX_DOC_BYTES } from "../src/oauth/fetchMetadata";
import { adminDb } from "./helpers/admin";
import { Assistant, form, pair, REDIRECT } from "./helpers/assistant";
import { BASE } from "./helpers/env";
import { Phone } from "./helpers/phone";

// QA (Spec 13): OAuth and authorise page checks beyond AT-2. Tests marked
// "QA: fails until ..." expose a bug and are expected to fail until it is fixed.

function sha256(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

async function registerWith(body: Record<string, unknown>) {
  const response = await fetch(`${BASE}/oauth/register`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: (await response.json()) as Record<string, any> };
}

/** CSP source matching for one URL, enough for form-action (CSP3 section 6.7.2.x, simplified). */
function formActionAllows(csp: string | null, target: URL, self: URL): boolean {
  if (!csp) return true;
  const directive = csp
    .split(";")
    .map((it) => it.trim())
    .find((it) => it.toLowerCase().startsWith("form-action"));
  if (!directive) return true; // form-action does not fall back to default-src
  const sources = directive.split(/\s+/).slice(1);
  return sources.some((source) => {
    if (source === "*") return true;
    if (source === "'self'") return target.origin === self.origin;
    if (/^[a-z][a-z0-9+.-]*:$/i.test(source)) return target.protocol === source.toLowerCase();
    try {
      return new URL(source).origin === target.origin;
    } catch {
      return false;
    }
  });
}

describe("QA OAuth: authorise page", () => {
  it("QA: fails until the authorise page's CSP form-action lets the browser follow the redirect back to the assistant", async () => {
    // Browsers apply form-action to the whole navigation a form starts, redirects included
    // (CSP Level 3, Chromium enforces it). The POST answers 302 to the assistant's
    // redirect_uri, which is never this origin, so 'self' alone blocks the hand-back in a
    // real browser. The tests' fetch client does not apply CSP, which is why AT-2 passes.
    const phone = await Phone.create();
    const a = new Assistant("CSP Assistant");
    await a.register();
    const page = await a.openAuthorize();
    const csp = page.response.headers.get("content-security-policy");
    const submit = await a.submitCode(page.requestId!, await phone.pairingCode());
    expect(submit.response.status).toBe(302);
    const target = submit.location!;
    expect(`${target.origin}${target.pathname}`).toBe(REDIRECT);
    expect(formActionAllows(csp, target, new URL(BASE))).toBe(true);
  });

  it("escapes a hostile client_name on the page", async () => {
    const reg = await registerWith({ client_name: '<img src=x onerror=alert(1)>"\'', redirect_uris: [REDIRECT] });
    expect(reg.status).toBe(201);
    const a = new Assistant();
    a.clientId = reg.body.client_id;
    const page = await a.openAuthorize();
    expect(page.response.status).toBe(200);
    expect(page.html).not.toContain("<img");
    expect(page.html).toContain("&lt;img src=x onerror=alert(1)&gt;&quot;&#39;");
    expect(page.response.headers.get("x-frame-options")).toBe("DENY");
    expect(page.response.headers.get("content-security-policy")).toContain("frame-ancestors 'none'");
    expect(page.response.headers.get("cache-control")).toBe("no-store");
    expect(page.html).not.toMatch(/<script/i);
  });

  it("QA: fails until client names have bidirectional control characters removed", async () => {
    // U+202E (right-to-left override) passes cleanText, so a registration can make the
    // name read differently on the consent page and on the phone's Assistants screen.
    const reg = await registerWith({ client_name: "Claude ‮drawkcab", redirect_uris: [REDIRECT] });
    expect(reg.status).toBe(201);
    expect(reg.body.client_name).not.toMatch(/[‪-‮⁦-⁩‎‏]/);
  });

  it("gives the same answer for an unknown code and a wrong secret, so selectors do not leak", async () => {
    const phone = await Phone.create();
    const code = await phone.pairingCode();
    const a = new Assistant();
    await a.register();
    const page = await a.openAuthorize();
    const alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    const last = code.at(-1)!;
    const wrongSecret = code.slice(0, 7) + (alphabet[(alphabet.indexOf(last) + 1) % alphabet.length] as string);
    const unknown = (code[0] === "Z" ? "Y" : "Z") + code.slice(1);
    const one = await a.submitCode(page.requestId!, wrongSecret);
    const two = await a.submitCode(page.requestId!, unknown);
    const strip = (html: string) => html.replace(/name="request_id" value="[^"]+"/, "");
    expect(one.response.status).toBe(400);
    expect(two.response.status).toBe(400);
    expect(strip(one.html)).toBe(strip(two.html));
  });

  it("the per-page limit of 5 wrong codes holds under parallel posts (in the emulator)", async () => {
    // finishAuthorization reads pending.tries and updates it outside a transaction, so in
    // production parallel posts on one page could each get a guess. The emulator runs one
    // request at a time per function, so this passes here; see the report (Low).
    const a = new Assistant();
    await a.register();
    const page = await a.openAuthorize();
    const guesses = Array.from({ length: 20 }, (_, i) => `ZZZZZZ${"23456789ABCDEFGHJKMNPQRSTUVWXYZ"[i]}Z`);
    const results = await Promise.all(guesses.map((g) => a.submitCode(page.requestId!, g)));
    const checked = results.filter((r) => r.response.status === 400 && r.html.includes("didn't work")).length;
    // At most 4 wrong-code pages; the fifth wrong code ends the authorisation.
    expect(checked).toBeLessThanOrEqual(4);
  });
});

describe("QA OAuth: redirect URIs, codes and tokens", () => {
  it("matches redirect URIs exactly (loopback may change only the port)", async () => {
    const reg = await registerWith({
      client_name: "Exact",
      redirect_uris: ["https://assistant.example/cb", "http://127.0.0.1:4100/cb"],
    });
    expect(reg.status).toBe(201);
    const a = new Assistant();
    a.clientId = reg.body.client_id;
    const accepted = ["https://assistant.example/cb", "http://127.0.0.1:4100/cb", "http://127.0.0.1:59999/cb"];
    const refused = [
      "https://assistant.example/cb/",
      "https://assistant.example/cb?x=1",
      "https://assistant.example/CB",
      "https://assistant.example.evil.example/cb",
      "https://assistant.example:444/cb",
      "http://assistant.example/cb",
      "http://127.0.0.1:4100/cb/other",
      "http://localhost:4100/cb",
      "http://127.0.0.1:4100/cb?next=https://evil.example",
    ];
    for (const uri of accepted) {
      const page = await a.openAuthorize({ redirect_uri: uri });
      expect(page.response.status, uri).toBe(200);
    }
    for (const uri of refused) {
      const page = await a.openAuthorize({ redirect_uri: uri });
      expect(page.response.status, uri).toBe(400);
      expect(page.response.headers.get("location"), uri).toBeNull();
    }
    // With two registered URIs, leaving redirect_uri out is refused (it can't be chosen for the client).
    const url = new URL(`${BASE}/oauth/authorize`);
    url.searchParams.set("client_id", a.clientId);
    url.searchParams.set("response_type", "code");
    expect((await fetch(url, { redirect: "manual" })).status).toBe(400);
  });

  it("refuses dangerous redirect schemes at registration", async () => {
    for (const uri of ["javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "https://a.example/cb#frag", "http://10.0.0.1/cb"]) {
      const reg = await registerWith({ redirect_uris: [uri] });
      expect(reg.status, uri).toBe(400);
    }
  });

  it("binds a code to its client and redirect URI, and a code expires after 5 minutes", async () => {
    const phone = await Phone.create();
    const a = new Assistant("Code owner");
    await a.register();
    const page = await a.openAuthorize();
    const submit = await a.submitCode(page.requestId!, await phone.pairingCode());
    const code = submit.location!.searchParams.get("code")!;
    const stored = (await adminDb().collection("authCodes").doc(sha256(`code:${code}`)).get()).data()!;
    expect(stored.expiresAt - stored.createdAt).toBe(5 * 60 * 1000);
    expect(JSON.stringify(stored)).not.toContain(code);

    const thief = new Assistant("Thief");
    await thief.register();
    const stolen = await fetch(
      `${BASE}/oauth/token`,
      form({ grant_type: "authorization_code", code, client_id: thief.clientId, code_verifier: page.verifier, redirect_uri: REDIRECT }),
    );
    expect(stolen.status).toBe(400);
    const wrongRedirect = await fetch(
      `${BASE}/oauth/token`,
      form({ grant_type: "authorization_code", code, client_id: a.clientId, code_verifier: page.verifier, redirect_uri: "http://127.0.0.1:1/x" }),
    );
    expect(wrongRedirect.status).toBe(400);
    const noVerifier = await fetch(`${BASE}/oauth/token`, form({ grant_type: "authorization_code", code, client_id: a.clientId }));
    expect(noVerifier.status).toBe(400);

    await adminDb().collection("authCodes").doc(sha256(`code:${code}`)).update({ expiresAt: Date.now() - 1 });
    const late = await a.exchange(code, page.verifier);
    expect(late.status).toBe(400);
    expect(((await late.json()) as any).error).toBe("invalid_grant");
  });

  it("accepts only live access tokens as bearer: not refresh tokens, expired or made-up ones", async () => {
    const phone = await Phone.create();
    const a = await pair(phone, "Bearer checks");
    expect((await a.rpc("tools/list")).status).toBe(200);
    expect((await a.rpc("tools/list", {}, { token: a.tokens!.refresh_token })).status).toBe(401);
    expect((await a.rpc("tools/list", {}, { token: "do_at_" + "A".repeat(43) })).status).toBe(401);
    expect((await a.rpc("tools/list", {}, { token: "do_at_" + "A".repeat(500) })).status).toBe(401);
    const rest = await fetch(`${BASE}/v1/list_reminders`, { headers: { authorization: `Bearer ${a.tokens!.refresh_token}` } });
    expect(rest.status).toBe(401);
    // Tokens and refresh tokens are stored as hashes only.
    const doc = await adminDb().collection("tokens").doc(sha256(`token:${a.tokens!.access_token}`)).get();
    expect(doc.exists).toBe(true);
    expect(JSON.stringify(doc.data())).not.toContain(a.tokens!.access_token);
    await doc.ref.update({ expiresAt: Date.now() - 1 });
    const expired = await a.rpc("tools/list");
    expect(expired.status).toBe(401);
    expect(expired.headers.get("www-authenticate")).toContain('error="invalid_token"');
  });

  it("pairing codes have about 39.6 bits and are stored hashed under a hashed selector", async () => {
    const phone = await Phone.create();
    const codes = new Set<string>();
    for (let i = 0; i < 5; i++) codes.add(await phone.pairingCode());
    expect(codes.size).toBe(5);
    expect(Math.log2(31 ** 8)).toBeGreaterThan(39.5);
    const docs = await adminDb().collection("pairingCodes").where("deviceUid", "==", phone.uid).get();
    // Only the newest code is live: making a code drops the older ones.
    expect(docs.size).toBe(1);
    const last = [...codes].at(-1)!;
    expect(docs.docs[0]!.id).not.toContain(last.slice(0, 4));
    expect(JSON.stringify(docs.docs[0]!.data())).not.toContain(last);
  });
});

describe("QA OAuth: client ID metadata documents (SSRF)", () => {
  it("refuses IP literals, loopback and internal names as client_id URLs", () => {
    for (const url of [
      "https://127.0.0.1/c.json",
      "https://0x7f.1/c.json",
      "https://2130706433/c.json",
      "https://[::1]/c.json",
      "https://[::ffff:127.0.0.1]/c.json",
      "https://169.254.169.254/c.json",
      "https://localhost/c.json",
      "https://app.localhost/c.json",
      "https://metadata.google.internal/c.json",
      "https://user:pass@example.com/c.json",
      "https://example.com/",
      "ftp://example.com/c.json",
      "https://intranet/c.json",
    ]) {
      expect(isMetadataClientId(url), url).toBe(false);
    }
    expect(isMetadataClientId("https://assistant.example/client.json")).toBe(true);
  });

  it("QA: fails until client_id hosts with a trailing dot are refused (localhost. and metadata.google.internal.)", () => {
    // new URL keeps the trailing dot in hostname, so the localhost and .internal checks miss it.
    expect(isMetadataClientId("https://localhost./c.json")).toBe(false);
    expect(isMetadataClientId("https://metadata.google.internal./c.json")).toBe(false);
  });

  describe("the fetcher (M2)", () => {
    // How the cap is proved. Counting what the test server writes doesn't work: Linux
    // loopback buffers megabytes in the kernel before any client can react. Instead:
    // (1) a server that drips 1 KiB every 10 ms and never ends. A fetcher that buffers the
    // whole body (the old response.text()) can only give up at its timeout (3 to 5 s);
    // one that enforces the cap gives up after about 6 drips. The production fetcher
    // (defaultFetcher) must refuse it well inside a second, and the server must see the
    // connection closed by then, having dripped only a few KiB.
    // (2) a server that blasts 8 MiB: the fetcher's own byte count (what it consumed
    // from the socket) must stop within one chunk of the cap.
    let server: http.Server;
    let base = "";
    let dripped = 0;
    let dripClosedAt = 0;
    beforeAll(async () => {
      server = http.createServer(async (req, res) => {
        if (req.url === "/redirect") {
          res.writeHead(302, { location: "http://127.0.0.1:1/elsewhere" });
          res.end();
          return;
        }
        if (req.url === "/small") {
          const doc = JSON.stringify({ client_id: "x", pad: "p".repeat(3000) });
          res.writeHead(200, { "content-type": "application/json" });
          res.end(doc);
          return;
        }
        if (req.url === "/declared") {
          res.writeHead(200, { "content-type": "application/json", "content-length": String(10 * 1024 * 1024) });
          const timer = setInterval(() => res.write(Buffer.alloc(1024, 0x20)), 50);
          res.on("close", () => clearInterval(timer));
          return;
        }
        if (req.url === "/drip") {
          res.writeHead(200, { "content-type": "application/json" });
          res.write("{");
          dripped = 0;
          dripClosedAt = 0;
          const timer = setInterval(() => {
            res.write(Buffer.alloc(1024, 0x20));
            dripped += 1024;
          }, 10);
          res.on("close", () => {
            clearInterval(timer);
            dripClosedAt = Date.now();
          });
          return;
        }
        // /blast: as fast as the socket takes it, until the client goes away.
        res.writeHead(200, { "content-type": "application/json" });
        const chunk = Buffer.alloc(64 * 1024, 0x20);
        let closed = false;
        res.on("close", () => {
          closed = true;
        });
        for (let sent = 0; sent < 8 * 1024 * 1024 && !closed; sent += chunk.length) {
          if (!res.write(chunk)) await new Promise((resolve) => res.once("drain", resolve).once("close", resolve));
        }
        res.end();
      });
      await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
      base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    });
    afterAll(() => {
      server.closeAllConnections();
      return new Promise<void>((resolve) => server.close(() => resolve()));
    });

    it("does not follow redirects", async () => {
      await expect(defaultFetcher(`${base}/redirect`)).rejects.toBeTruthy();
    });

    it("still reads a document under the cap (control)", async () => {
      await expect(defaultFetcher(`${base}/small`)).resolves.toMatchObject({ client_id: "x" });
    });

    it("gives up on an endless body as soon as it passes 5 KB, not at the timeout", async () => {
      const started = Date.now();
      await expect(defaultFetcher(`${base}/drip`)).rejects.toBeTruthy();
      const elapsed = Date.now() - started;
      expect(elapsed).toBeLessThan(1000);
      // The fetcher closed the connection itself, promptly, after only a few KiB.
      for (let i = 0; i < 50 && dripClosedAt === 0; i++) await new Promise((r) => setTimeout(r, 20));
      expect(dripClosedAt).toBeGreaterThan(0);
      expect(dripClosedAt - started).toBeLessThan(1500);
      expect(dripped).toBeLessThan(32 * 1024);
    });

    it("refuses a declared oversized body without reading it, and stops a blast within one chunk of the cap", async () => {
      const declared = { bytesRead: 0 };
      const started = Date.now();
      await expect(fetchMetadataDocument(`${base}/declared`, declared)).rejects.toBeTruthy();
      expect(Date.now() - started).toBeLessThan(1000);
      expect(declared.bytesRead).toBe(0);
      const blast = { bytesRead: 0 };
      await expect(fetchMetadataDocument(`${base}/blast`, blast)).rejects.toBeTruthy();
      expect(blast.bytesRead).toBeGreaterThan(MAX_DOC_BYTES);
      expect(blast.bytesRead).toBeLessThanOrEqual(MAX_DOC_BYTES + 64 * 1024);
    });
  });
});
