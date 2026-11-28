import type { Transaction } from "firebase-admin/firestore";
import { limits, mcpResource } from "../core/config";
import { randomToken, sha256 } from "../core/crypto";
import { agentRef, db, paths } from "../core/db";

// Access tokens (1 hour) and rotating refresh tokens (30 days). Both are
// opaque random strings; only their SHA-256 is stored, as the document id.

export interface Grant {
  grantId: string;
  deviceUid: string;
  agentId: string;
  clientId: string;
  clientName: string;
  scope: string;
  resource: string;
}

export interface TokenPair {
  access_token: string;
  token_type: "Bearer";
  expires_in: number;
  refresh_token: string;
  scope: string;
}

export function tokenRef(token: string) {
  return db().collection(paths.tokens).doc(sha256(`token:${token}`));
}

/** Writes a new access and refresh token for [grant] inside [tx]. */
export function issueTokens(tx: Transaction, grant: Grant, now: number): TokenPair {
  const access = randomToken("do_at_");
  const refresh = randomToken("do_rt_");
  const common = {
    grantId: grant.grantId,
    deviceUid: grant.deviceUid,
    agentId: grant.agentId,
    clientId: grant.clientId,
    scope: grant.scope,
    resource: grant.resource,
    createdAt: now,
  };
  tx.set(tokenRef(access), { ...common, type: "access", expiresAt: now + limits.accessTokenMs });
  tx.set(tokenRef(refresh), { ...common, type: "refresh", expiresAt: now + limits.refreshTokenMs, rotatedAt: null });
  return {
    access_token: access,
    token_type: "Bearer",
    expires_in: Math.floor(limits.accessTokenMs / 1000),
    refresh_token: refresh,
    scope: grant.scope,
  };
}

export interface Caller {
  deviceUid: string;
  agentId: string;
  clientId: string;
  grantId: string;
}

/**
 * Checks a bearer token from /mcp or /v1. It must be a live access token
 * issued for this server's MCP resource (RFC 8707 audience). Revoked grants
 * have their tokens deleted, so one read is enough.
 */
export async function verifyAccessToken(token: string | undefined, now: number): Promise<Caller | null> {
  if (!token || !token.startsWith("do_at_") || token.length > 200) return null;
  const data = (await tokenRef(token).get()).data();
  if (!data || data.type !== "access") return null;
  if (data.expiresAt <= now) return null;
  if (data.resource !== mcpResource()) return null;
  return { deviceUid: data.deviceUid, agentId: data.agentId, clientId: data.clientId, grantId: data.grantId };
}

/** Marks a grant revoked and deletes all its tokens. Used by disconnect and on refresh token reuse. */
export async function revokeGrant(grantId: string, now: number): Promise<number> {
  const grantDoc = db().collection(paths.grants).doc(grantId);
  const grant = (await grantDoc.get()).data();
  await grantDoc.set({ revokedAt: now }, { merge: true });
  if (grant) {
    // The phone shows the assistant as needing to connect again. update() fails on a deleted agent, which is fine.
    await agentRef(grant.deviceUid, grant.agentId)
      .update({ grantRevokedAt: now })
      .catch(() => undefined);
  }
  const tokens = await db().collection(paths.tokens).where("grantId", "==", grantId).get();
  const writer = db().bulkWriter();
  tokens.docs.forEach((doc) => void writer.delete(doc.ref));
  const codes = await db().collection(paths.authCodes).where("grantId", "==", grantId).get();
  codes.docs.forEach((doc) => void writer.delete(doc.ref));
  await writer.close();
  return tokens.size;
}

export function grantFrom(id: string, data: FirebaseFirestore.DocumentData): Grant {
  return {
    grantId: id,
    deviceUid: data.deviceUid,
    agentId: data.agentId,
    clientId: data.clientId,
    clientName: data.clientName,
    scope: data.scope,
    resource: data.resource,
  };
}
