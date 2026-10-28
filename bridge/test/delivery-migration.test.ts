import request from "supertest";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { NoopNotifier } from "../src/notifier.js";
import { CalendarService } from "../src/service.js";
import { MemoryCalendarStore } from "../src/store.js";

// The planner filed its first delivery records under Life (category personal, no alerts) before the
// Deliveries feed existed. These tests pin the move to Deliveries and that nothing else changes.
const taskKey = "daily-next-month-email-planner-scan";
const lifeDelivery = {
  itemKey: "delivery-amazon-113-0000000-0000000",
  title: "Delivery: Amazon - Coffee (2 items)",
  startAt: "2026-10-02T09:00:00-06:00",
  timeZone: "America/Denver",
  notes: "Amazon order 113-0000000-0000000. Source: confirmed by email",
  domain: "life" as const,
  category: "personal" as const,
  critical: false,
  reminderOffsetsMinutes: [] as number[],
};
const appointment = {
  itemKey: "va-appointment",
  title: "VA clinic appointment",
  startAt: "2026-10-07T13:00:00-06:00",
  timeZone: "America/Denver",
  domain: "life" as const,
  category: "medical" as const,
  critical: true,
  reminderOffsetsMinutes: [1440, 120, 60],
};
const exam = {
  itemKey: "chem-exam-2",
  title: "CHM 110 Exam 2",
  startAt: "2026-11-08T23:59:00-07:00",
  timeZone: "America/Denver",
  domain: "school" as const,
  category: "school" as const,
  reminderOffsetsMinutes: [10080, 1440],
};

async function seeded() {
  const store = new MemoryCalendarStore();
  const service = new CalendarService(store, new NoopNotifier());
  const published = await service.publishTaskReminders({ taskKey, reminders: [lifeDelivery, appointment, exam] });
  const [delivery, life, school] = published.reminders.map((item) => item.event);
  return { store, service, delivery, life, school };
}

describe("moving planner delivery records from Life to Deliveries", () => {
  it("changes only the feed when update sets domain=delivery", async () => {
    const { service, delivery } = await seeded();
    expect(delivery.domain).toBe("life");
    expect(delivery.reminderOffsetsMinutes).toEqual([]);

    const moved = (await service.update(delivery.id, { domain: "delivery" }))!.event;
    expect(moved).toEqual({ ...delivery, domain: "delivery", revision: moved.revision, updatedAt: moved.updatedAt });
    expect(moved.category).toBe("personal");
    expect(moved.reminderOffsetsMinutes).toEqual([]);
    expect(moved.sourceTaskKey).toBe(taskKey);
    expect(moved.sourceItemKey).toBe(lifeDelivery.itemKey);
  });

  it("leaves Life and School records exactly as they were", async () => {
    const { service, delivery, life, school } = await seeded();
    await service.update(delivery.id, { domain: "delivery" });

    const listed = await service.list(true);
    expect(listed.find((event) => event.id === life.id)).toEqual(life);
    expect(listed.find((event) => event.id === school.id)).toEqual(school);
    expect(life.domain).toBe("life");
    expect(life.reminderOffsetsMinutes).toEqual([1440, 120, 60]);
    expect(school.domain).toBe("school");
    expect(school.reminderOffsetsMinutes).toEqual([10080, 1440]);

    // Republishing them unchanged is still a no-op.
    const again = await service.publishTaskReminders({ taskKey, reminders: [appointment, exam] });
    expect(again.unchanged).toBe(2);
  });

  it("keeps a moved record in Deliveries on later publishes of the same itemKey", async () => {
    const { service, delivery } = await seeded();
    await service.update(delivery.id, { domain: "delivery" });

    // Same record, new estimate, published the way the planner will after the move.
    const later = await service.publishTaskReminders({
      taskKey,
      reminders: [{ ...lifeDelivery, domain: "delivery", startAt: "2026-10-03T09:00:00-06:00" }],
    });
    expect(later.created).toBe(0);
    expect(later.updated).toBe(1);
    expect(later.reminders[0].event.id).toBe(delivery.id);
    expect(later.reminders[0].event.domain).toBe("delivery");
    expect(later.reminders[0].event.reminderOffsetsMinutes).toEqual([]);

    // A publish that names no feed does not pull it back to Life.
    const { domain: _domain, ...noFeed } = lifeDelivery;
    const unnamed = await service.publishTaskReminders({ taskKey, reminders: [{ ...noFeed, startAt: "2026-10-03T09:00:00-06:00" }] });
    expect(unnamed.unchanged).toBe(1);
    expect((await service.list()).filter((event) => event.domain === "delivery")).toHaveLength(1);
  });

  it("sends the moved record to a paired phone as a delivery and lists it for AI clients", async () => {
    const { store, service, delivery, life, school } = await seeded();
    await service.update(delivery.id, { domain: "delivery" });
    const app = createApp(store, new NoopNotifier(), {
      apiToken: "test-api-token",
      pairingCode: "654321",
      firebaseClientConfig: { projectId: "test-project", appId: "1:123:android:test", apiKey: "test-api-key", senderId: "123" },
      publicBaseUrl: "https://calendar.example.test/api",
    });
    const pairing = await request(app)
      .post("/v1/devices/register")
      .send({ pairingCode: "654321", installationId: "fid-migration", deviceName: "Pixel" })
      .expect(201);

    const synced = await request(app).get("/v1/sync").set("Authorization", `Bearer ${pairing.body.deviceToken}`).expect(200);
    const byId = new Map<string, { domain: string; reminderOffsetsMinutes: number[] }>(
      synced.body.events.map((event: { id: string }) => [event.id, event]),
    );
    expect(byId.get(delivery.id)).toMatchObject({ domain: "delivery", category: "personal", reminderOffsetsMinutes: [] });
    expect(byId.get(life.id)).toMatchObject({ domain: "life", reminderOffsetsMinutes: [1440, 120, 60] });
    expect(byId.get(school.id)).toMatchObject({ domain: "school", reminderOffsetsMinutes: [10080, 1440] });

    const listed = await request(app)
      .post("/mcp")
      .set("Authorization", "Bearer test-api-token")
      .set("Accept", "application/json, text/event-stream")
      .send({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "list_reminders", arguments: {} } })
      .expect(200);
    expect(listed.text).not.toContain("Output validation error");
    expect(listed.text).toContain(delivery.id);
  });

  it("rejects a feed name the apps do not know", async () => {
    const { store } = await seeded();
    const app = createApp(store, new NoopNotifier(), {
      apiToken: "test-api-token",
      pairingCode: "654321",
      firebaseClientConfig: { projectId: "test-project", appId: "1:123:android:test", apiKey: "test-api-key", senderId: "123" },
      publicBaseUrl: "https://calendar.example.test/api",
    });
    // The apps match the stored value "delivery"; "deliveries" would fall back to Life on the phone.
    const rejected = await request(app)
      .post("/mcp")
      .set("Authorization", "Bearer test-api-token")
      .set("Accept", "application/json, text/event-stream")
      .send({
        jsonrpc: "2.0",
        id: 1,
        method: "tools/call",
        params: { name: "create_reminder", arguments: { title: "Lamp", startAt: "2026-10-06T09:00:00-06:00", timeZone: "America/Denver", domain: "deliveries" } },
      })
      .expect(200);
    expect(rejected.text).toContain("Input validation error");
    expect(await store.listEvents(true)).toHaveLength(3);
  });
});
