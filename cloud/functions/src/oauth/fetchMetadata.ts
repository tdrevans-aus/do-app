import { lookup as dnsLookup, type LookupAddress } from "node:dns";
import http from "node:http";
import https from "node:https";
import { inEmulator } from "../core/config";
import { isPrivateAddress, normaliseHost } from "../core/hosts";

// Fetches a Client ID Metadata Document safely (QA M2 and L1): https only,
// no redirects, a short timeout, at most 5 KB read (the stream is cut, not
// buffered), and the name must resolve only to public addresses. The
// connection uses the address that was checked, so DNS rebinding can't
// switch it to a private one afterwards.

export const MAX_DOC_BYTES = 5 * 1024;
export const FETCH_TIMEOUT_MS = 3000;

export class FetchRefused extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FetchRefused";
  }
}

const LOOPBACK = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);

type LookupCallback = (err: NodeJS.ErrnoException | null, address: string | LookupAddress[], family?: number) => void;

/** A dns.lookup that refuses private answers. In the emulator, loopback is allowed for the test client. */
function checkedLookup(allowLoopback: boolean) {
  return (hostname: string, options: { all?: boolean; family?: number }, callback: LookupCallback) => {
    dnsLookup(hostname, { all: true, family: options.family ?? 0 }, (err, addresses) => {
      if (err) return callback(err, "", 0);
      const list = addresses as LookupAddress[];
      const bad = list.find((a) => isPrivateAddress(a.address) && !(allowLoopback && (a.address === "127.0.0.1" || a.address === "::1")));
      if (list.length === 0 || bad) {
        const refused = new Error("The client metadata host resolves to a private address") as NodeJS.ErrnoException;
        refused.code = "EPRIVATE";
        return callback(refused, "", 0);
      }
      if (options.all) return callback(null, list);
      return callback(null, list[0]!.address, list[0]!.family);
    });
  };
}

/** What a fetch read from the network (for tests). */
export interface FetchStats {
  bytesRead: number;
}

export function fetchMetadataDocument(rawUrl: string, stats: FetchStats = { bytesRead: 0 }): Promise<unknown> {
  return new Promise((resolve, reject) => {
    let url: URL;
    try {
      url = new URL(rawUrl);
    } catch {
      reject(new FetchRefused("Not a URL"));
      return;
    }
    const host = normaliseHost(url.hostname);
    const emulatorLoopback = inEmulator() && url.protocol === "http:" && LOOPBACK.has(host);
    if (url.protocol !== "https:" && !emulatorLoopback) {
      reject(new FetchRefused("Only https client metadata URLs are fetched"));
      return;
    }
    const client = url.protocol === "https:" ? https : http;
    const request = client.request(
      url,
      {
        method: "GET",
        headers: { accept: "application/json", "user-agent": "do-cloud/1.0" },
        lookup: checkedLookup(emulatorLoopback) as unknown as typeof dnsLookup,
        timeout: FETCH_TIMEOUT_MS,
      },
      (response) => {
        const status = response.statusCode ?? 0;
        if (status !== 200) {
          // Redirects included: they are never followed.
          response.destroy();
          reject(new FetchRefused(`Status ${status}`));
          return;
        }
        const declared = Number(response.headers["content-length"] ?? "0");
        if (declared > MAX_DOC_BYTES) {
          response.destroy();
          request.destroy();
          reject(new FetchRefused("Too large"));
          return;
        }
        const chunks: Buffer[] = [];
        let size = 0;
        response.on("data", (chunk: Buffer) => {
          size += chunk.length;
          stats.bytesRead = size;
          if (size > MAX_DOC_BYTES) {
            response.destroy();
            request.destroy();
            reject(new FetchRefused("Too large"));
            return;
          }
          chunks.push(chunk);
        });
        response.on("end", () => {
          if (size > MAX_DOC_BYTES) return;
          try {
            resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
          } catch {
            reject(new FetchRefused("Not JSON"));
          }
        });
        response.on("error", () => reject(new FetchRefused("Read failed")));
      },
    );
    const deadline = setTimeout(() => {
      request.destroy();
      reject(new FetchRefused("Timed out"));
    }, FETCH_TIMEOUT_MS);
    request.on("close", () => clearTimeout(deadline));
    request.on("timeout", () => {
      request.destroy();
      reject(new FetchRefused("Timed out"));
    });
    request.on("error", () => reject(new FetchRefused("Fetch failed")));
    request.end();
  });
}
