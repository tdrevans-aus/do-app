import { safeEqual, sha256Base64Url } from "../core/crypto";

const VERIFIER = /^[A-Za-z0-9\-._~]{43,128}$/;
const CHALLENGE = /^[A-Za-z0-9\-_]{43}$/;

export function validChallenge(challenge: string | undefined): challenge is string {
  return typeof challenge === "string" && CHALLENGE.test(challenge);
}

/** RFC 7636 S256: BASE64URL(SHA256(ASCII(code_verifier))) == code_challenge. */
export function verifierMatches(verifier: string | undefined, challenge: string): boolean {
  if (typeof verifier !== "string" || !VERIFIER.test(verifier)) return false;
  return safeEqual(sha256Base64Url(verifier), challenge);
}
