import { getMessaging } from "firebase-admin/messaging";
import type { DeviceRecord } from "./types.js";

export interface DeviceNotifier {
  signalSync(devices: DeviceRecord[], revision: number): Promise<number>;
  /** Kitchen changed (shopping list, pantry, week, notes). Optional: a notifier without it sends nothing. */
  signalKitchen?(devices: DeviceRecord[], revision: number): Promise<number>;
}

export class NoopNotifier implements DeviceNotifier {
  async signalSync(): Promise<number> {
    return 0;
  }

  async signalKitchen(): Promise<number> {
    return 0;
  }
}

const UNREGISTERED_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

export class FirebaseDeviceNotifier implements DeviceNotifier {
  /** @param onUnregistered called with device IDs whose installation FCM reports as gone, so they are skipped until re-registered. */
  constructor(private readonly onUnregistered?: (deviceIds: string[]) => Promise<void>) {}

  async signalSync(devices: DeviceRecord[], revision: number): Promise<number> {
    return this.send(devices, "calendar_sync", revision, "high", 60 * 60 * 1000);
  }

  /**
   * Phones before 0.18.0 ignore this type. Normal priority and a short life: only a Kitchen view
   * that is open acts on it, and every view fetches the kitchen again when it is opened anyway.
   */
  async signalKitchen(devices: DeviceRecord[], revision: number): Promise<number> {
    return this.send(devices, "kitchen_sync", revision, "normal", 10 * 60 * 1000);
  }

  private async send(devices: DeviceRecord[], type: string, revision: number, priority: "high" | "normal", ttl: number): Promise<number> {
    const pushDevices = devices.filter((device) => device.pushEnabled !== false && !device.pushInvalidSince);
    if (pushDevices.length === 0) return 0;
    const messages = pushDevices.map((device) => ({
      fid: device.installationId,
      data: {
        type,
        revision: revision.toString(),
      },
      android: {
        priority,
        ttl,
      },
    }));
    const result = await getMessaging().sendEach(messages);
    const unregistered: string[] = [];
    result.responses.forEach((response, index) => {
      if (!response.success) {
        const deviceId = pushDevices[index]?.id;
        if (deviceId && UNREGISTERED_CODES.has(response.error?.code ?? "")) unregistered.push(deviceId);
        console.error("FCM signal failed", {
          type,
          deviceId: pushDevices[index]?.id,
          code: response.error?.code,
          message: response.error?.message,
        });
      }
    });
    if (unregistered.length > 0 && this.onUnregistered) {
      await this.onUnregistered(unregistered).catch((error: unknown) => {
        console.error("Could not mark unregistered push devices", error instanceof Error ? error.message : error);
      });
    }
    return result.successCount;
  }
}
