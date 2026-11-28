import { limits } from "../core/config";
import { baseUrl } from "../core/config";
import { randomId, randomToken, sha256 } from "../core/crypto";
import { db, agentRef, paths } from "../core/db";
import { logInfo } from "../core/log";
import { redeemPairingCode } from "../core/pairing";
import { field, html, redirect, type HttpRequest, type HttpResponse } from "../http/types";
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

export async function startAuthorization(req: HttpRequest, now: number, fetcher?: MetadataFetcher): Promise<HttpResponse> {
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
  return html(200, codePage({ requestId, clientName: client.clientName, returnHost: hostOf(redirectUri) }));
}

const WRONG_CODE = "That code didn't work. Check the code in Do and try again.";

export async function finishAuthorization(req: HttpRequest, now: number, fetcher?: MetadataFetcher): Promise<HttpResponse> {
  const requestId = field(req.body, "request_id");
  if (!requestId || requestId.length > 100) {
    return html(400, messagePage("Can't connect", "This page has expired. Go back to your assistant and connect again."));
  }
  const requestRef = db().collection(paths.authRequests).doc(sha256(`authreq:${requestId}`));
  const pending = (await requestRef.get()).data();
  if (!pending || pending.expiresAt <= now) {
    return html(400, messagePage("Can't connect", "This page has expired. Go back to your assistant and connect again."));
  }
  const state = pending.state ?? undefined;
  if (field(req.body, "action") === "cancel") {
    await requestRef.delete();
    return errorRedirect(pending.redirectUri, "access_denied", "The person cancelled", state);
  }

  const result = await redeemPairingCode(field(req.body, "code"), now);
  if (!result.ok) {
    const tries = (pending.tries ?? 0) + 1;
    logInfo("pairing.rejected", { reason: result.reason, tries });
    if (tries >= limits.pairingTries) {
      await requestRef.delete();
      return errorRedirect(pending.redirectUri, "access_denied", "Too many wrong codes", state);
    }
    await requestRef.update({ tries });
    const message =
      result.reason === "burnt"
        ? "That code has been used up after too many tries. Make a new code in Do and try again."
        : result.reason === "expired"
          ? "That code has expired or was already used. Make a new code in Do and try again."
          : WRONG_CODE;
    return html(
      400,
      codePage({ requestId, clientName: pending.clientName, returnHost: hostOf(pending.redirectUri), error: message }),
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
