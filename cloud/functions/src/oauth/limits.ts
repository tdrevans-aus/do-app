import { settings } from "../core/config";
import { consume, ipKey, type LimitWindow } from "../core/limiter";
import { logWarn } from "../core/log";

// Rate limits for the unauthenticated OAuth endpoints (QA M3): per client IP
// and one global ceiling for each, so pairing-code guessing and Firestore
// writes can't scale with volume.

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

export type OAuthAction = "register" | "authorize_page" | "authorize_post";

function windowsFor(action: OAuthAction, ip: string): { perIp: LimitWindow; global: LimitWindow } {
  switch (action) {
    case "register":
      return {
        perIp: { key: ipKey("ip_reg", ip), limit: settings.registerPerIpHour(), sizeMs: HOUR },
        global: { key: "g_reg", limit: settings.registerGlobalHour(), sizeMs: HOUR },
      };
    case "authorize_page":
      return {
        perIp: { key: ipKey("ip_azp", ip), limit: settings.authorizePagePerIpMinute(), sizeMs: MINUTE },
        global: { key: "g_azp", limit: settings.authorizePageGlobalHour(), sizeMs: HOUR },
      };
    case "authorize_post":
      return {
        perIp: { key: ipKey("ip_azc", ip), limit: settings.authorizePostPerIpTenMinutes(), sizeMs: 10 * MINUTE },
        global: { key: "g_azc", limit: settings.authorizePostGlobalHour(), sizeMs: HOUR },
      };
  }
}

/** Counts one request. Returns 0 when allowed, else seconds to wait. */
export async function limitOAuth(action: OAuthAction, ip: string | undefined, now: number): Promise<number> {
  const { perIp, global } = windowsFor(action, ip ?? "unknown");
  const perIpWait = await consume([perIp], now);
  if (perIpWait > 0) {
    logWarn("oauth.limited", { action, scope: "ip" });
    return perIpWait;
  }
  const globalWait = await consume([global], now);
  if (globalWait > 0) {
    logWarn("oauth.limited", { action, scope: "global" });
    return globalWait;
  }
  return 0;
}
