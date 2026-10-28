import { MemoryCalendarStore } from "./store.js";
import { NoopNotifier } from "./notifier.js";
import { createApp } from "./app.js";
import { warnIfWeakPairingCode } from "./security.js";

const port = Number(process.env.PORT ?? 8787);
// No default: a well-known local code would pair anything that can reach this port.
const pairingCode = (process.env.AI_CALENDAR_PAIRING_CODE ?? "").trim();
if (!pairingCode) {
  console.error("Set AI_CALENDAR_PAIRING_CODE (at least 32 random characters) before starting the local bridge.");
  process.exit(1);
}
warnIfWeakPairingCode(pairingCode);
const app = createApp(new MemoryCalendarStore(), new NoopNotifier(), {
  apiToken: process.env.AI_CALENDAR_API_TOKEN ?? "local-development-token",
  pairingCode,
  firebaseClientConfig: {
    projectId: process.env.AI_CALENDAR_FIREBASE_PROJECT_ID ?? "local-project",
    appId: process.env.AI_CALENDAR_FIREBASE_APP_ID ?? "1:123456789:android:local",
    apiKey: process.env.AI_CALENDAR_FIREBASE_API_KEY ?? "local-api-key",
    senderId: process.env.AI_CALENDAR_FIREBASE_SENDER_ID ?? "123456789",
  },
  publicBaseUrl: process.env.AI_CALENDAR_PUBLIC_BASE_URL ?? `http://127.0.0.1:${port}`,
  ...(process.env.CANVAS_BASE_URL && process.env.CANVAS_ACCESS_TOKEN ? {
    canvas: { baseUrl: process.env.CANVAS_BASE_URL, accessToken: process.env.CANVAS_ACCESS_TOKEN },
  } : {}),
});

app.listen(port, () => {
  console.log(`AI Calendar bridge listening on http://127.0.0.1:${port}`);
});
