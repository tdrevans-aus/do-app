import { isIP } from "node:net";
import { settings } from "../core/config";

/**
 * The client's IP for rate limiting. Proxies append to X-Forwarded-For, so
 * anything a client puts there itself is on the left: count CLIENT_IP_HOPS
 * entries from the right. With fewer entries than that, the leftmost one is
 * used. Falls back to the socket address.
 */
export function clientIp(forwardedFor: string | undefined, socketIp: string | undefined): string {
  const parts = (forwardedFor ?? "")
    .split(",")
    .map((it) => it.trim())
    .filter((it) => isIP(it) !== 0);
  if (parts.length > 0) {
    const hops = settings.clientIpHops();
    const index = parts.length > hops ? parts.length - 1 - hops : 0;
    return parts[index]!;
  }
  return socketIp && socketIp.length > 0 ? socketIp : "unknown";
}
