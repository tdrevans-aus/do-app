import { limits } from "../core/config";
import { baseUrl } from "../core/config";
import { randomId, randomToken, sha256 } from "../core/crypto";
import { db, agentRef, paths } from "../core/db";
import { logInfo } from "../core/log";
import { redeemPairingCode } from "../core/pairing";
import { cspSourceFor, field, html, redirect, type HttpRequest, type HttpResponse } from "../http/types";
import { limitOAuth } from "./limits";
import { ClientError, markClientUsed, redirectMatches, resolveClient, type MetadataFetcher } from "./clients";
import { canonicalResource, SCOPE } from "./metadata";
import { codePage, messagePage } from "./page";
import { validChallenge } from "./pkce";

// The authorisation endpoint (authorisation code with PKCE). GET shows the
// code form; POST checks the pairing code and, when it is right, makes the
// grant and the agent and sends the browser back with a one-time code.

function withParams(uri: string, params: Record<string, string | undefined>): string {
  const url = new URL(uri);
  for (const [key, value] of Object.entries(params)) if (value !== undefined) url.searchParams.set(key, value);
  return url.toString();
}

function errorRedirect(redirectUri: string, error: string, description: string, state: string | undefined): HttpResponse {
  return redirect(withParams(redirectUri, { error, error_description: description, state, iss: baseUrl() }));
}

function scopeFrom(raw: string | undefined): string | null {
  if (raw === undefined || raw.trim() === "") return SCOPE;
  const asked = raw.split(" ").filter((it) => it.length > 0);
  // offline_access is accepted and ignored: refresh tokens are always issued.
  const unknown = asked.filter((it) => it !== SCOPE && it !== "offline_access");
  return unknown.length === 0 ? SCOPE : null;
}

function hostOf(uri: string): string {
  try {
    const url = new URL(uri);
    return url.host || url.protocol.replace(":", "");
  } catch {
    return "your assistant";
  }
}

/** form-action sources for the code page: the assistant's redirect target, exactly. */
function formActionFor(redirectUri: string): string[] {
  const source = cspSourceFor(redirectUri);
  return source ? [source] : [];
}

export async function startAuthorization(req: HttpRequest, now: number, fetcher?: MetadataFetcher): Promise<HttpResponse> {
  const wait = await limitOAuth("authorize_page", req.ip, now);
  if (wait > 0) return tooMany(wait);
  const q = req.query;
  let client;
  try {
    client = await resolveClient(q.client_id, now, fetcher);
  } catch (error) {
    const message = error instanceof ClientError ? error.message : "Unknown client";
    return html(400, messagePage("Can't connect", `${message}. Go back to your assistant and try connecting again.`));
  }
  const redirectUri = redirectMatches(client, q.redirect_uri);
  if (!redirectUri) {
    return html(400, messagePage("Can't connect", "The return address doesn't match this assistant. Go back to your assistant and try again."));
  }
  const state = q.state;
  if (q.response_type !== "code") return errorRedirect(redirectUri, "unsupported_response_type", "response_type must be code", state);
  if (q.code_challenge_method !== "S256" || !validChallenge(q.code_challenge)) {
    return errorRedirect(redirectUri, "invalid_request", "PKCE with code_challenge_method S256 is required", state);
  }
  const resource = canonicalResource(q.resource);
  if (!resource) return errorRedirect(redirectUri, "invalid_target", "resource must be this server's MCP endpoint", state);
  const scope = scopeFrom(q.scope);
  if (!scope) return errorRedirect(redirectUri, "invalid_scope", "The only scope is reminders", state);

  const requestId = randomToken("", 24);
  await db()
    .collection(paths.authRequests)
    .doc(sha256(`authreq:${requestId}`))
    .set({
      clientId: client.clientId,
      clientName: client.clientName,
      redirectUri,
      state: state ?? null,
      codeChallenge: q.code_challenge,
      resource,
      scope,
      tries: 0,
      createdAt: now,
      expiresAt: now + limits.authRequestMs,
    });
  return html(200, codePage({ requestId, clientName: client.clientName, returnHost: hostOf(redirectUri) }), {}, formActionFor(redirectUri));
}

/**
 * One message for a wrong, unknown, used, expired or burnt code (QA L8), so
 * the page never tells a guesser that a code was live.
 */
export const WRONG_CODE = "That code didn't work. Check the code in Do, or make a new one there, and try again.";

const EXPIRED_PAGE = "This page has expired. Go back to your assistant and connect again.";

function tooMany(wait: number): HttpResponse {
  return html(429, messagePage("Too many tries", "There have been too many attempts. Wait a few minutes, then go back to your assistant and connect again."), {
    "retry-after": String(wait),
  });
}

type Reserved =
  | { kind: "gone" }
  | { kind: "cancel"; pending: FirebaseFirestore.DocumentData }
  | { kind: "spent"; pending: FirebaseFirestore.DocumentData }
  | { kind: "try"; pending: FirebaseFirestore.DocumentData; tries: number };

export async function finishAuthorization(req: HttpRequest, now: number, fetcher?: MetadataFetcher): Promise<HttpResponse> {
  const requestId = field(req.body, "request_id");
  if (!requestId || requestId.length > 100) return html(400, messagePage("Can't connect", EXPIRED_PAGE));
  const wait = await limitOAuth("authorize_post", req.ip, now);
  if (wait > 0) return tooMany(wait);
  const requestRef = db().collection(paths.authRequests).doc(sha256(`authreq:${requestId}`));
  const cancel = field(req.body, "action") === "cancel";

  // Charge the try before checking the code, in one transaction, so parallel
  // posts on one page can't each get a guess beyond the limit.
  const reserved = await db().runTransaction(async (tx): Promise<Reserved> => {
    const snap = await tx.get(requestRef);
    const pending = snap.data();
    if (!pending || pending.expiresAt <= now) return { kind: "gone" };
    if (cancel) {
      tx.delete(requestRef);
      return { kind: "cancel", pending };
    }
    const tries = (pending.tries ?? 0) as number;
    if (tries >= limits.pairingTries) {
      tx.delete(requestRef);
      return { kind: "spent", pending };
    }
    tx.update(requestRef, { tries: tries + 1 });
    return { kind: "try", pending, tries: tries + 1 };
  });
  if (reserved.kind === "gone") return html(400, messagePage("Can't connect", EXPIRED_PAGE));
  const pending = reserved.pending;
  const state = pending.state ?? undefined;
  if (reserved.kind === "cancel") return errorRedirect(pending.redirectUri, "access_denied", "The person cancelled", state);
  if (reserved.kind === "spent") return errorRedirect(pending.redirectUri, "access_denied", "Too many wrong codes", state);

  const result = await redeemPairingCode(field(req.body, "code"), now);
  if (!result.ok) {
    logInfo("pairing.rejected", { reason: result.reason, tries: reserved.tries });
    if (reserved.tries >= limits.pairingTries) {
      await requestRef.delete();
      return errorRedirect(pending.redirectUri, "access_denied", "Too many wrong codes", state);
    }
    return html(
      400,
      codePage({ requestId, clientName: pending.clientName, returnHost: hostOf(pending.redirectUri), error: WRONG_CODE }),
      {},
      formActionFor(pending.redirectUri),
    );
  }

  const deviceUid = result.deviceUid;
  const agentId = `ag_${randomId(8)}`;
  const grantId = randomId(16);
  const code = randomToken("do_ac_");
  await db().runTransaction(async (tx) => {
    tx.set(db().collection(paths.grants).doc(grantId), {
      deviceUid,
      agentId,
      clientId: pending.clientId,
      clientName: pending.clientName,
      scope: pending.scope,
      resource: pending.resource,
      createdAt: now,
      revokedAt: null,
    });
    tx.set(agentRef(deviceUid, agentId), {
      clientName: pending.clientName,
      clientId: pending.clientId,
      grantId,
      createdAt: now,
      lastUsedAt: null,
      eventSeq: 0,
    });
    tx.set(db().collection(paths.authCodes).doc(sha256(`code:${code}`)), {
      grantId,
      clientId: pending.clientId,
      redirectUri: pending.redirectUri,
      codeChallenge: pending.codeChallenge,
      resource: pending.resource,
      scope: pending.scope,
      createdAt: now,
      expiresAt: now + limits.authCodeMs,
      used: false,
    });
    tx.delete(requestRef);
  });
  try {
    const client = await resolveClient(pending.clientId, now, fetcher);
    await markClientUsed(client, now);
  } catch {
    // The grant stands even if the client record is gone.
  }
  logInfo("pairing.accepted", { agentId });
  return redirect(withParams(pending.redirectUri, { code, state, iss: baseUrl() }));
}
