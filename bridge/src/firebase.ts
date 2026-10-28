import { applicationDefault, getApps, initializeApp } from "firebase-admin/app";
import { FirestoreCalendarStore } from "./store.js";
import { FirebaseDeviceNotifier } from "./notifier.js";
import { createApp } from "./app.js";
import { warnIfWeakPairingCode } from "./security.js";

export function createFirebaseApp() {
  if (getApps().length === 0) {
    initializeApp({
      credential: applicationDefault(),
      projectId: process.env.GCLOUD_PROJECT ?? process.env.AI_CALENDAR_FIREBASE_PROJECT_ID,
    });
  }
  const store = new FirestoreCalendarStore();
  const pairingCode = (process.env.AI_CALENDAR_PAIRING_CODE ?? "").trim();
  // Logged once per instance, without the code. Not enforced: refusing to start would lock out
  // every paired device and AI connector at once.
  warnIfWeakPairingCode(pairingCode);
  return createApp(
    store,
    new FirebaseDeviceNotifier(unregisteredDeviceMarker(store)),
    {
      apiToken: (process.env.AI_CALENDAR_API_TOKEN ?? "").trim(),
      pairingCode,
      firebaseClientConfig: {
        projectId: process.env.GCLOUD_PROJECT ?? process.env.AI_CALENDAR_FIREBASE_PROJECT_ID ?? "",
        appId: process.env.AI_CALENDAR_FIREBASE_APP_ID ?? "",
        apiKey: process.env.AI_CALENDAR_FIREBASE_API_KEY ?? "",
        senderId: process.env.AI_CALENDAR_FIREBASE_SENDER_ID ?? "",
      },
      publicBaseUrl: process.env.AI_CALENDAR_PUBLIC_BASE_URL ?? "",
      ...(process.env.MS_CLIENT_ID && process.env.MS_CLIENT_SECRET ? {
        outlook: { clientId: process.env.MS_CLIENT_ID.trim(), clientSecret: process.env.MS_CLIENT_SECRET.trim() },
      } : {}),
      ...(process.env.CANVAS_BASE_URL && process.env.CANVAS_ACCESS_TOKEN ? {
        canvas: {
          baseUrl: process.env.CANVAS_BASE_URL,
          accessToken: process.env.CANVAS_ACCESS_TOKEN,
        },
      } : {}),
    },
  );
}

/** Marks devices whose FCM installation is gone so later signals skip them until the device reports a new ID. */
export function unregisteredDeviceMarker(store: FirestoreCalendarStore) {
  return async (deviceIds: string[]) => {
    const pushInvalidSince = new Date().toISOString();
    await Promise.all(deviceIds.map((id) => store.patchDevice(id, { pushInvalidSince })));
  };
}
