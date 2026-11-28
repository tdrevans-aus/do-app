import { openWith, sealWith, sha256 } from "../core/crypto";
import { db, paths } from "../core/db";
import { logInfo, logWarn } from "../core/log";
import { field, json, type HttpRequest, type HttpResponse } from "../http/types";
import { canonicalResource } from "./metadata";
import { verifierMatches } from "./pkce";
import { grantFrom, issueTokens, revokeGrant, tokenRef, type TokenPair } from "./tokens";

// The token endpoint: authorization_code (with PKCE) and refresh_token (rotating).

const NO_STORE = { "cache-control": "no-store", pragma: "no-cache" };

function oauthError(error: string, description: string, status = 400): HttpResponse {
  return json(status, { error, error_description: description }, NO_STORE);
}

class GrantRejected extends Error {
  constructor(
    readonly description: string,
    readonly revokeGrantId?: string,
  ) {
    super(description);
  }
}

async function exchangeCode(req: HttpRequest, now: number): Promise<TokenPair> {
  const code = field(req.body, "code");
  const clientId = field(req.body, "client_id");
  if (!code || !clientId) throw new GrantRejected("code and client_id are required");
  const codeRef = db().collection(paths.authCodes).doc(sha256(`code:${code}`));
  return db().runTransaction(async (tx) => {
    const data = (await tx.get(codeRef)).data();
    if (!data) throw new GrantRejected("The authorisation code is not valid");
    // A second use of a code means it leaked: revoke what it granted (OAuth 2.1 section 4.1.2).
    if (data.used) throw new GrantRejected("The authorisation code was already used", data.grantId);
    if (data.expiresAt <= now) throw new GrantRejected("The authorisation code has expired");
    if (data.clientId !== clientId) throw new GrantRejected("The code was issued to another client");
    const redirectUri = field(req.body, "redirect_uri");
    if (redirectUri !== undefined && redirectUri !== data.redirectUri) throw new GrantRejected("redirect_uri does not match");
    if (!verifierMatches(field(req.body, "code_verifier"), data.codeChallenge)) {
      throw new GrantRejected("code_verifier does not match the code_challenge");
    }
    const resourceRaw = field(req.body, "resource");
    if (resourceRaw !== undefined && canonicalResource(resourceRaw) !== data.resource) {
      throw new GrantRejected("resource does not match the authorisation request");
    }
    const grantSnap = await tx.get(db().collection(paths.grants).doc(data.grantId));
    const grantData = grantSnap.data();
    if (!grantData || grantData.revokedAt) throw new GrantRejected("The grant was revoked");
    tx.update(codeRef, { used: true, usedAt: now });
    return issueTokens(tx, grantFrom(grantSnap.id, grantData), now);
  });
}

/**
 * A client that loses the token response (a dropped connection) will retry
 * with the same refresh token. Within this window it gets the same new pair
 * again instead of losing the grant; after it, reuse still revokes (QA L7).
 */
export const REFRESH_GRACE_MS = 30_000;
const GRACE_PURPOSE = "do-refresh-grace";

async function rotateRefresh(req: HttpRequest, now: number): Promise<TokenPair> {
  const refresh = field(req.body, "refresh_token");
  const clientId = field(req.body, "client_id");
  if (!refresh || !clientId) throw new GrantRejected("refresh_token and client_id are required");
  const ref = tokenRef(refresh);
  return db().runTransaction(async (tx) => {
    const data = (await tx.get(ref)).data();
    if (!data || data.type !== "refresh") throw new GrantRejected("The refresh token is not valid");
    if (data.clientId !== clientId) throw new GrantRejected("The refresh token was issued to another client");
    if (data.rotatedAt) {
      if (now - (data.rotatedAt as number) <= REFRESH_GRACE_MS && typeof data.graceCipher === "string") {
        const plain = openWith(refresh, GRACE_PURPOSE, data.graceCipher);
        const grant = (await tx.get(db().collection(paths.grants).doc(data.grantId))).data();
        if (plain && grant && !grant.revokedAt) {
          const pair = JSON.parse(plain) as TokenPair & { issuedAt: number };
          const { issuedAt, ...rest } = pair;
          return { ...rest, expires_in: Math.max(1, rest.expires_in - Math.floor((now - issuedAt) / 1000)) };
        }
      }
      // Reuse of a rotated refresh token after the grace window: treat the whole grant as stolen (OAuth 2.1 section 4.3.1).
      throw new GrantRejected("The refresh token was already used", data.grantId);
    }
    if (data.expiresAt <= now) throw new GrantRejected("The refresh token has expired");
    const resourceRaw = field(req.body, "resource");
    if (resourceRaw !== undefined && canonicalResource(resourceRaw) !== data.resource) {
      throw new GrantRejected("resource does not match the grant");
    }
    const grantSnap = await tx.get(db().collection(paths.grants).doc(data.grantId));
    const grantData = grantSnap.data();
    if (!grantData || grantData.revokedAt) throw new GrantRejected("The grant was revoked");
    const pair = issueTokens(tx, grantFrom(grantSnap.id, grantData), now);
    tx.update(ref, { rotatedAt: now, graceCipher: sealWith(refresh, GRACE_PURPOSE, JSON.stringify({ ...pair, issuedAt: now })) });
    return pair;
  });
}

export async function tokenEndpoint(req: HttpRequest, now: number): Promise<HttpResponse> {
  if (req.method !== "POST") return oauthError("invalid_request", "Use POST", 405);
  const grantType = field(req.body, "grant_type");
  try {
    if (grantType === "authorization_code") {
      const pair = await exchangeCode(req, now);
      logInfo("oauth.token", { grant: "authorization_code" });
      return json(200, pair, NO_STORE);
    }
    if (grantType === "refresh_token") {
      const pair = await rotateRefresh(req, now);
      logInfo("oauth.token", { grant: "refresh_token" });
      return json(200, pair, NO_STORE);
    }
    return oauthError("unsupported_grant_type", "grant_type must be authorization_code or refresh_token");
  } catch (error) {
    if (error instanceof GrantRejected) {
      if (error.revokeGrantId) {
        await revokeGrant(error.revokeGrantId, now);
        logWarn("oauth.reuse_revoked", { grantType: grantType ?? "none" });
      }
      return oauthError("invalid_grant", error.description);
    }
    throw error;
  }
}
