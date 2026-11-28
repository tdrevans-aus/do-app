import { BlockList, isIP } from "node:net";

// Host checks for URLs Do Cloud fetches itself (client ID metadata documents).

/**
 * Lower case, no trailing dots, IDNA (punycode) form. `new URL` already
 * lower-cases and converts IDNA, but keeps a trailing dot, which would get
 * "localhost." past a name check.
 */
export function normaliseHost(hostname: string): string {
  let host = hostname.toLowerCase();
  while (host.endsWith(".")) host = host.slice(0, -1);
  try {
    host = new URL(`https://${host}/`).hostname;
  } catch {
    return "";
  }
  return host;
}

const LOCAL_NAMES = new Set(["localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback"]);
const LOCAL_SUFFIXES = [".localhost", ".local", ".internal", ".intranet", ".lan", ".home.arpa", ".corp"];

/** A name that is local by convention, or not a public DNS name at all. */
export function isLocalName(host: string): boolean {
  if (host === "" || LOCAL_NAMES.has(host)) return true;
  if (LOCAL_SUFFIXES.some((s) => host.endsWith(s))) return true;
  if (!host.includes(".")) return true;
  return false;
}

/** IP literals of any form (URL turns 0x7f.1 and 2130706433 into dotted quads). */
export function isIpLiteral(host: string): boolean {
  return host.startsWith("[") || isIP(host) !== 0 || /^[0-9.]+$/.test(host);
}

const PRIVATE = new BlockList();
for (const [net, bits] of [
  ["0.0.0.0", 8],
  ["10.0.0.0", 8],
  ["100.64.0.0", 10],
  ["127.0.0.0", 8],
  ["169.254.0.0", 16],
  ["172.16.0.0", 12],
  ["192.0.0.0", 24],
  ["192.0.2.0", 24],
  ["192.168.0.0", 16],
  ["198.18.0.0", 15],
  ["198.51.100.0", 24],
  ["203.0.113.0", 24],
  ["224.0.0.0", 4],
  ["240.0.0.0", 4],
] as const) {
  PRIVATE.addSubnet(net, bits, "ipv4");
}
for (const [net, bits] of [
  ["::", 128],
  ["::1", 128],
  ["fc00::", 7],
  ["fe80::", 10],
  ["ff00::", 8],
  ["2001:db8::", 32],
  ["64:ff9b::", 96],
] as const) {
  PRIVATE.addSubnet(net, bits, "ipv6");
}

/** True for loopback, private, link-local, carrier-grade NAT, documentation, multicast and reserved addresses. */
export function isPrivateAddress(address: string): boolean {
  const family = isIP(address);
  if (family === 4) return PRIVATE.check(address, "ipv4");
  if (family === 6) {
    const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(address);
    if (mapped) return PRIVATE.check(mapped[1]!, "ipv4");
    return PRIVATE.check(address, "ipv6");
  }
  return true;
}
