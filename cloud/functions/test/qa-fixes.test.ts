import { randomUUID } from "node:crypto";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { fetchMetadataDocument } from "../src/oauth/fetchMetadata";
import { afterEach, describe, expect, it } from "vitest";
import { parseStrictIso } from "../src/agent/args";
import { accountIdFor } from "../src/billing/binding";
import { FakePlayClient } from "../src/billing/play";
import { handleRtdn } from "../src/billing/rtdn";
import { consume, ipKey, windowDocId } from "../src/core/limiter";
import { isPrivateAddress, normaliseHost } from "../src/core/hosts";
import { cleanText } from "../src/core/text";
import { cspSourceFor } from "../src/http/types";
import { clientIp } from "../src/http/ip";
import { adminDb } from "./helpers/admin";
import { Assistant, pair, REDIRECT } from "./helpers/assistant";
import { BASE, PACKAGE } from "./helpers/env";
import { Phone } from "./helpers/phone";

// Checks for the fixes made after the QA report (specs/13-qa-report.md),
// beyond QA's own tests.

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const PRODUCT = "do_unlimited";

/** Fills the counter windows (this one and the next, in case it rolls over) for a limiter key. */
async function fill(key: string, sizeMs: number, count: number): Promise<string[]> {
  const now = Date.now();
  const ids = [windowDocId(key, sizeMs, now), windowDocId(key, sizeMs, now + sizeMs)];
  for (const id of ids) await adminDb().collection("rateLimits").doc(id).set({ count, expireAt: now + 2 * sizeMs });
  return ids;
}

async function clear(ids: string[]) {
  for (const id of ids) await adminDb().collection("rateLimits").doc(id).delete();
}

describe("fix H1: the code page lets the form hand back to the assistant, and nothing else", () => {
  it("names exactly the redirect origin, or the private-use scheme", async () => {
    expect(cspSourceFor("https://claude.ai/api/mcp/auth_callback")).toBe("https://claude.ai");
    expect(cspSourceFor("http://127.0.0.1:33418/callback")).toBe("http://127.0.0.1:33418");
    expect(cspSourceFor("cursor://anysphere.cursor-retrieval/oauth/callback")).toBe("cursor:");
    const reg = await fetch(`${BASE}/oauth/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ client_name: "Native", redirect_uris: ["com.example.app:/oauth"] }),
    });
    const a = new Assistant();
    a.clientId = ((await reg.json()) as any).client_id;
    const page = await a.openAuthorize({ redirect_uri: "com.example.app:/oauth" });
    const csp = page.response.headers.get("content-security-policy")!;
    expect(csp).toContain("form-action 'self' com.example.app:;");
    expect(csp).toContain("script-src 'none'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(page.html).not.toMatch(/<script/i);
  });

  it("other pages keep form-action 'self' only", async () => {
    const root = await fetch(`${BASE}/`);
    expect(root.headers.get("content-security-policy")).toContain("form-action 'self';");
  });
});

describe("fix M3: limits on the unauthenticated OAuth endpoints", () => {
  const touched: string[] = [];
  afterEach(async () => {
    await clear(touched.splice(0));
  });

  it("refuses registration over the per-IP limit, for that IP only", async () => {
    const ip = "198.51.100.7";
    touched.push(...(await fill(ipKey("ip_reg", ip), HOUR, 100_000)));
    const body = JSON.stringify({ client_name: "Flood", redirect_uris: [REDIRECT] });
    const limited = await fetch(`${BASE}/oauth/register`, { method: "POST", headers: { "content-type": "application/json", "x-forwarded-for": ip }, body });
    expect(limited.status).toBe(429);
    expect(Number(limited.headers.get("retry-after"))).toBeGreaterThan(0);
    const other = await fetch(`${BASE}/oauth/register`, { method: "POST", headers: { "content-type": "application/json", "x-forwarded-for": "198.51.100.8" }, body });
    expect(other.status).toBe(201);
  });

  it("refuses authorise pages and code posts over the per-IP limits", async () => {
    const ip = "198.51.100.9";
    const a = new Assistant();
    await a.register();
    const page = await a.openAuthorize();
    touched.push(...(await fill(ipKey("ip_azp", ip), MINUTE, 100_000)));
    touched.push(...(await fill(ipKey("ip_azc", ip), 10 * MINUTE, 100_000)));
    const url = new URL(page.response.url);
    const pageLimited = await fetch(url, { headers: { "x-forwarded-for": ip }, redirect: "manual" });
    expect(pageLimited.status).toBe(429);
    const post = await fetch(`${BASE}/oauth/authorize`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", "x-forwarded-for": ip },
      body: new URLSearchParams({ request_id: page.requestId!, code: "ABCDEFGH" }).toString(),
      redirect: "manual",
    });
    expect(post.status).toBe(429);
    // The page's own try counter was not charged by the refused post.
    const ok = await a.submitCode(page.requestId!, "ZZZZZZZZ");
    expect(ok.response.status).toBe(400);
  });

  it("a global ceiling stops code posts from every IP", async () => {
    const a = new Assistant();
    await a.register();
    const page = await a.openAuthorize();
    touched.push(...(await fill("g_azc", HOUR, 1000)));
    const submit = await a.submitCode(page.requestId!, "ZZZZZZZZ");
    expect(submit.response.status).toBe(429);
  });

  it("the limiter counts atomically under parallel use", async () => {
    const key = `test_${randomUUID()}`;
    const now = Date.now();
    const results = await Promise.all(Array.from({ length: 12 }, () => consume([{ key, limit: 5, sizeMs: HOUR }], now)));
    expect(results.filter((r) => r === 0)).toHaveLength(5);
    touched.push(windowDocId(key, HOUR, now));
  });

  it("takes the client IP from the right of X-Forwarded-For", () => {
    expect(clientIp("203.0.113.1", "10.0.0.1")).toBe("203.0.113.1");
    expect(clientIp("6.6.6.6, 203.0.113.1, 35.1.2.3", undefined)).toBe("203.0.113.1");
    expect(clientIp("not-an-ip", "10.0.0.1")).toBe("10.0.0.1");
    expect(clientIp(undefined, undefined)).toBe("unknown");
  });
});

describe("fix M2: the metadata fetcher reads at most 5 KB", () => {
  it("stops reading a large body and refuses redirects", async () => {
    const server = http.createServer((req, res) => {
      if (req.url === "/redirect") {
        res.writeHead(301, { location: "https://example.com/c.json" });
        res.end();
        return;
      }
      if (req.url === "/declared") {
        res.writeHead(200, { "content-type": "application/json", "content-length": String(10 * 1024 * 1024) });
        res.end();
        return;
      }
      res.writeHead(200, { "content-type": "application/json" });
      const chunk = Buffer.alloc(16 * 1024, 0x20);
      const timer = setInterval(() => res.write(chunk), 5);
      res.on("close", () => clearInterval(timer));
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    try {
      const stats = { bytesRead: 0 };
      await expect(fetchMetadataDocument(`${base}/big`, stats)).rejects.toBeTruthy();
      expect(stats.bytesRead).toBeGreaterThan(5 * 1024);
      expect(stats.bytesRead).toBeLessThanOrEqual(5 * 1024 + 16 * 1024);
      const declared = { bytesRead: 0 };
      await expect(fetchMetadataDocument(`${base}/declared`, declared)).rejects.toBeTruthy();
      expect(declared.bytesRead).toBe(0);
      await expect(fetchMetadataDocument(`${base}/redirect`)).rejects.toBeTruthy();
      await expect(fetchMetadataDocument("http://example.com/c.json")).rejects.toBeTruthy();
    } finally {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });
});

describe("fix L1 and L2: hosts and names", () => {
  it("normalises hosts and refuses private addresses", () => {
    expect(normaliseHost("LocalHost.")).toBe("localhost");
    expect(normaliseHost("bücher.example..")).toBe("xn--bcher-kva.example");
    for (const ip of ["127.0.0.1", "10.1.2.3", "172.20.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1"]) {
      expect(isPrivateAddress(ip), ip).toBe(true);
    }
    expect(isPrivateAddress("8.8.8.8")).toBe(false);
    expect(isPrivateAddress("2606:4700:4700::1111")).toBe(false);
  });

  it("strips format and control characters from names and text", () => {
    expect(cleanText("Claude‮ drawkcab⁦x⁩​", 60)).toBe("Claude drawkcabx");
    expect(cleanText("a b\u0007c", 60)).toBe("a b c");
    expect(cleanText("‮‏", 60)).toBeNull();
  });
});

describe("fix L3: strict due_at", () => {
  it("accepts real instants and refuses impossible ones", () => {
    expect(parseStrictIso("2027-02-28T09:00:00+08:00")).toBe(Date.parse("2027-02-28T01:00:00Z"));
    expect(parseStrictIso("2028-02-29T09:00:00Z")).toBe(Date.parse("2028-02-29T09:00:00Z"));
    for (const bad of ["2027-02-29T09:00:00Z", "2027-02-30T09:00:00+08:00", "2027-04-31T09:00:00Z", "2027-13-01T09:00:00Z", "2027-01-01T24:00:00Z", "2027-01-01T09:60:00Z", "2027-01-01T09:00:00+15:00"]) {
      expect(parseStrictIso(bad), bad).toBeNull();
    }
  });
});

describe("fix M1 and L6: purchases bound to their installation", () => {
  function purchase(state: string, owner?: string, linked?: string) {
    return {
      subscriptionState: `SUBSCRIPTION_STATE_${state}`,
      acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
      ...(owner ? { externalAccountIdentifiers: { obfuscatedExternalAccountId: accountIdFor(owner) } } : {}),
      ...(linked ? { linkedPurchaseToken: linked } : {}),
      lineItems: [{ productId: PRODUCT, expiryTime: new Date(Date.now() + 30 * DAY).toISOString(), autoRenewingPlan: { autoRenewEnabled: true } }],
    };
  }
  const verify = (phone: Phone, token: string, account: string | null = accountIdFor(phone.uid)) =>
    phone.call<Record<string, any>>("verifyPurchase", { platform: "play", packageName: PACKAGE, productId: PRODUCT, purchaseToken: token, obfuscatedAccountId: account ?? undefined });
  const entitlement = async (uid: string) => (await adminDb().doc(`devices/${uid}/entitlement/current`).get()).data();

  it("the account id is base64url SHA-256, within Play's 64 characters", () => {
    expect(accountIdFor("abc")).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(accountIdFor("abc")).not.toBe(accountIdFor("abd"));
  });

  it("requires the caller's own obfuscatedAccountId", async () => {
    const phone = await Phone.create();
    const token = `fix-${randomUUID()}`;
    await FakePlayClient.ref(token).set({ packageName: PACKAGE, purchase: purchase("ACTIVE", phone.uid), ackCount: 0 });
    await expect(verify(phone, token, null)).rejects.toMatchObject({ code: "INVALID_ARGUMENT" });
    await expect(verify(phone, token, accountIdFor("someone-else"))).rejects.toMatchObject({ code: "INVALID_ARGUMENT" });
    expect(await verify(phone, token)).toMatchObject({ entitled: true, state: "active" });
  });

  it("an unbound purchase entitles nobody (strict: none were sold before binding)", async () => {
    const phone = await Phone.create();
    const token = `fix-${randomUUID()}`;
    await FakePlayClient.ref(token).set({ packageName: PACKAGE, purchase: purchase("ACTIVE"), ackCount: 0 });
    expect(await verify(phone, token)).toMatchObject({ entitled: false, state: "other_device" });
    expect((await FakePlayClient.ref(token).get()).data()!.ackCount).toBe(0);
  });

  it("another installation's token doesn't entitle, and doesn't hide the caller's own purchase", async () => {
    const mine = await Phone.create();
    const theirs = await Phone.create();
    const myToken = `fix-${randomUUID()}`;
    const theirToken = `fix-${randomUUID()}`;
    await FakePlayClient.ref(myToken).set({ packageName: PACKAGE, purchase: purchase("ACTIVE", mine.uid), ackCount: 0 });
    await FakePlayClient.ref(theirToken).set({ packageName: PACKAGE, purchase: purchase("ACTIVE", theirs.uid), ackCount: 0 });
    await verify(mine, myToken);
    expect(await verify(mine, theirToken)).toMatchObject({ entitled: false, state: "other_device" });
    expect(await entitlement(mine.uid)).toMatchObject({ entitled: true, state: "active" });
  });

  it("a resubscribe bound to a new installation clears the old owner", async () => {
    const oldPhone = await Phone.create();
    const newPhone = await Phone.create();
    const oldToken = `fix-${randomUUID()}`;
    const newToken = `fix-${randomUUID()}`;
    await FakePlayClient.ref(oldToken).set({ packageName: PACKAGE, purchase: purchase("ACTIVE", oldPhone.uid), ackCount: 0 });
    await verify(oldPhone, oldToken);
    await FakePlayClient.ref(newToken).set({ packageName: PACKAGE, purchase: purchase("ACTIVE", newPhone.uid, oldToken), ackCount: 0 });
    // RTDN for the new token arrives first: the old owner loses it.
    await handleRtdn({ packageName: PACKAGE, subscriptionNotification: { notificationType: 4, purchaseToken: newToken } }, `fix-${randomUUID()}`, Date.now(), new FakePlayClient());
    expect(await entitlement(oldPhone.uid)).toMatchObject({ entitled: false, state: "transferred" });
    expect(await verify(newPhone, newToken)).toMatchObject({ entitled: true });
  });

  it("a void confirmed by Play revokes; one Play doesn't confirm is ignored", async () => {
    const phone = await Phone.create();
    const token = `fix-${randomUUID()}`;
    await FakePlayClient.ref(token).set({ packageName: PACKAGE, purchase: purchase("ACTIVE", phone.uid), ackCount: 0 });
    await verify(phone, token);
    const voided = { packageName: PACKAGE, voidedPurchaseNotification: { purchaseToken: token, orderId: "GPA.2", productType: 1, refundType: 1 } };
    expect(await handleRtdn(voided, `fix-${randomUUID()}`, Date.now(), new FakePlayClient())).toMatchObject({ reason: "not_confirmed" });
    await FakePlayClient.ref(token).delete();
    expect(await handleRtdn(voided, `fix-${randomUUID()}`, Date.now(), new FakePlayClient())).toMatchObject({ state: "revoked" });
    expect(await entitlement(phone.uid)).toMatchObject({ entitled: false, state: "revoked" });
  });
});

describe("fix L7 and I2", () => {
  it("malformed application/json to /mcp gets no stack trace", async () => {
    const phone = await Phone.create();
    const a = await pair(phone, "Bad JSON");
    const response = await fetch(`${BASE}/mcp`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${a.tokens!.access_token}` },
      body: '{"jsonrpc":"2.0","id":1,',
    });
    const text = await response.text();
    expect(response.status).toBe(400);
    expect(text).not.toMatch(/at \w+ \(|node_modules|SyntaxError|\/src\//);
  });
});
