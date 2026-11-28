// Runtime settings. Values come from process.env, which Cloud Functions fills
// from the .env files next to package.json (.env.demo-do-cloud in the emulator).
// Nothing here is a secret.

function intFrom(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === "") return fallback;
  const value = Number.parseInt(raw, 10);
  return Number.isFinite(value) && value >= 0 ? value : fallback;
}

function listFrom(name: string, fallback: string[]): string[] {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === "") return fallback;
  return raw
    .split(",")
    .map((it) => it.trim())
    .filter((it) => it.length > 0);
}

/**
 * The one region for every function (callables, the api function, the
 * scheduled clean-up and the Pub/Sub trigger). Sydney: Do is Australia-first.
 * firebase.json's Hosting rewrites must name the same region; a test checks it.
 */
export const REGION = "australia-southeast1";

/** True only inside the Firebase Emulator Suite. Test seams check this. */
export function inEmulator(): boolean {
  return process.env.FUNCTIONS_EMULATOR === "true" || process.env.FIRESTORE_EMULATOR_HOST !== undefined;
}

export function projectId(): string {
  return process.env.GCLOUD_PROJECT ?? process.env.GOOGLE_CLOUD_PROJECT ?? "demo-do-cloud";
}

/**
 * The public origin of Do Cloud, with no trailing slash, for example
 * https://do-cloud.web.app. Firebase Hosting rewrites every path to the `api`
 * function, so the MCP endpoint is `${baseUrl}/mcp` and the OAuth metadata
 * sits at the root, where RFC 8414 and RFC 9728 clients look for it.
 */
export function baseUrl(): string {
  const raw = process.env.PUBLIC_BASE_URL?.trim();
  const value = raw && raw.length > 0 ? raw : `https://${projectId()}.web.app`;
  return value.replace(/\/+$/, "");
}

export function mcpResource(): string {
  return `${baseUrl()}/mcp`;
}

export const settings = {
  /** How long an assistant call waits for the phone before answering "queued". */
  waitMs: () => intFrom("AGENT_WAIT_MS", 15_000),
  /** How long a queued request stays pending before it expires. */
  requestTtlMs: () => intFrom("REQUEST_TTL_MS", 24 * 60 * 60 * 1000),
  agentPerMinute: () => intFrom("RATE_AGENT_PER_MINUTE", 30),
  agentPerDay: () => intFrom("RATE_AGENT_PER_DAY", 500),
  devicePerMinute: () => intFrom("RATE_DEVICE_PER_MINUTE", 60),
  devicePerDay: () => intFrom("RATE_DEVICE_PER_DAY", 1000),
  /** "fcm" sends real pushes; "record" (emulator only) writes them to Firestore for tests. */
  pushClient: () => process.env.PUSH_CLIENT ?? "fcm",
  /** "google" calls the Play Developer API; "fake" (emulator only) reads Firestore. */
  playClient: () => process.env.PLAY_CLIENT ?? "google",
  playPackageName: () => process.env.PLAY_PACKAGE_NAME ?? "com.evansappstudio.doapp",
  playProductIds: () => listFrom("PLAY_PRODUCT_IDS", ["do_unlimited"]),
  rtdnTopic: () => process.env.RTDN_TOPIC ?? "play-rtdn",
  /** Browser origins allowed to call /mcp. Requests with no Origin header are always allowed. */
  allowedOrigins: () => listFrom("ALLOWED_ORIGINS", [new URL(baseUrl()).origin]),
};

export const limits = {
  accessTokenMs: 60 * 60 * 1000,
  refreshTokenMs: 30 * 24 * 60 * 60 * 1000,
  authCodeMs: 5 * 60 * 1000,
  authRequestMs: 15 * 60 * 1000,
  pairingCodeMs: 10 * 60 * 1000,
  pairingTries: 5,
  inboxKeepMs: 7 * 24 * 60 * 60 * 1000,
  eventKeepMs: 30 * 24 * 60 * 60 * 1000,
  deviceIdleMs: 180 * 24 * 60 * 60 * 1000,
  maxTextLength: 500,
  maxListLimit: 50,
  defaultListLimit: 10,
  maxEventsLimit: 100,
  defaultEventsLimit: 50,
  maxSyncReminders: 200,
  maxSyncEvents: 500,
};
