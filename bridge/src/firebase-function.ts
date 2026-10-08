import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { createFirebaseApp, unregisteredDeviceMarker } from "./firebase.js";
import { FirestoreCalendarStore } from "./store.js";
import { FirebaseDeviceNotifier } from "./notifier.js";
import { CalendarService } from "./service.js";
import { CanvasSyncService } from "./canvas.js";

export const api = onRequest(
  {
    region: "us-central1",
    cors: false,
    invoker: "public",
    minInstances: 0,
    // Caps cost/abuse from the public endpoint; MCP Canvas sync can take longer than the 60 s default.
    maxInstances: 10,
    timeoutSeconds: 180,
    secrets: ["AI_CALENDAR_API_TOKEN", "AI_CALENDAR_PAIRING_CODE", "CANVAS_BASE_URL", "CANVAS_ACCESS_TOKEN", "MS_CLIENT_ID", "MS_CLIENT_SECRET"],
  },
  createFirebaseApp(),
);

export const canvasSync = onSchedule(
  {
    region: "us-central1",
    schedule: "every 30 minutes",
    timeZone: "America/Denver",
    retryCount: 2,
    minBackoffSeconds: 60,
    maxInstances: 1,
    timeoutSeconds: 300,
    secrets: ["CANVAS_BASE_URL", "CANVAS_ACCESS_TOKEN"],
  },
  async () => {
    const baseUrl = process.env.CANVAS_BASE_URL ?? "";
    const accessToken = process.env.CANVAS_ACCESS_TOKEN ?? "";
    const store = new FirestoreCalendarStore();
    const service = new CalendarService(store, new FirebaseDeviceNotifier(unregisteredDeviceMarker(store)));
    // Repeating reminders ride on this schedule. A Canvas failure must not stop them, and the
    // reverse.
    try {
      console.log("Series expanded", await service.expandAllSeries());
    } catch (error) {
      console.error("Series expansion failed", error instanceof Error ? error.message : error);
    }
    const result = await new CanvasSyncService(service).sync({ baseUrl, accessToken });
    console.log("Canvas sync completed", result);
  },
);
