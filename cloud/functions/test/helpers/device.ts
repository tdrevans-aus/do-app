import { AUTH, FUNCTIONS } from "./env";

// A device as the Android app will be: Anonymous Auth, then callables.

export class CallError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(`${status} ${code}: ${message}`);
  }
}

export interface DeviceIdentity {
  uid: string;
  idToken: string;
}

export async function signInAnonymously(): Promise<DeviceIdentity> {
  const response = await fetch(`${AUTH}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-api-key`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ returnSecureToken: true }),
  });
  const body = (await response.json()) as { idToken: string; localId: string };
  if (!response.ok) throw new Error(`signUp failed ${response.status}`);
  return { uid: body.localId, idToken: body.idToken };
}

export async function call<T = Record<string, unknown>>(name: string, idToken: string | null, data: unknown = {}): Promise<T> {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (idToken) headers.authorization = `Bearer ${idToken}`;
  const response = await fetch(`${FUNCTIONS}/${name}`, { method: "POST", headers, body: JSON.stringify({ data }) });
  const body = (await response.json()) as { result?: T; error?: { status: string; message: string } };
  if (!response.ok || body.error) throw new CallError(response.status, body.error?.status ?? "UNKNOWN", body.error?.message ?? "");
  return body.result as T;
}
