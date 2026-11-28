import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";

// The Admin SDK in the test process, pointed at the Firestore emulator by
// FIRESTORE_EMULATOR_HOST (set by firebase emulators:exec).

export function adminDb() {
  const app = getApps()[0] ?? initializeApp({ projectId: "demo-do-cloud" });
  return getFirestore(app);
}
