import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { getAuth, type Auth } from "firebase-admin/auth";

function app() {
  return getApps()[0] ?? initializeApp();
}

export function db(): Firestore {
  return getFirestore(app());
}

export function auth(): Auth {
  return getAuth(app());
}

/** Collection names in one place. Paths under devices/{uid} are readable by that device (firestore.rules). */
export const paths = {
  devices: "devices",
  agents: "agents",
  inbox: "inbox",
  reminders: "reminders",
  events: "events",
  entitlement: "entitlement",
  pairingCodes: "pairingCodes",
  oauthClients: "oauthClients",
  authRequests: "authRequests",
  authCodes: "authCodes",
  grants: "grants",
  tokens: "tokens",
  rateLimits: "rateLimits",
  purchases: "purchases",
  rtdnSeen: "rtdnSeen",
  emulatorPushes: "_emulatorPushes",
  fakePlay: "_fakePlay",
} as const;

export function deviceRef(uid: string) {
  return db().collection(paths.devices).doc(uid);
}

export function agentRef(uid: string, agentId: string) {
  return deviceRef(uid).collection(paths.agents).doc(agentId);
}
