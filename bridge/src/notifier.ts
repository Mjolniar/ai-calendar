import { getMessaging } from "firebase-admin/messaging";
import type { DeviceRecord } from "./types.js";

export interface DeviceNotifier {
  signalSync(devices: DeviceRecord[], revision: number): Promise<number>;
}

export class NoopNotifier implements DeviceNotifier {
  async signalSync(): Promise<number> {
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
    const pushDevices = devices.filter((device) => device.pushEnabled !== false && !device.pushInvalidSince);
    if (pushDevices.length === 0) return 0;
    const messages = pushDevices.map((device) => ({
      fid: device.installationId,
      data: {
        type: "calendar_sync",
        revision: revision.toString(),
      },
      android: {
        priority: "high" as const,
        ttl: 60 * 60 * 1000,
      },
    }));
    const result = await getMessaging().sendEach(messages);
    const unregistered: string[] = [];
    result.responses.forEach((response, index) => {
      if (!response.success) {
        const deviceId = pushDevices[index]?.id;
        if (deviceId && UNREGISTERED_CODES.has(response.error?.code ?? "")) unregistered.push(deviceId);
        console.error("FCM sync signal failed", {
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
