import request from "supertest";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { NoopNotifier } from "../src/notifier.js";
import { MemoryCalendarStore } from "../src/store.js";

async function paired() {
  const store = new MemoryCalendarStore();
  const app = createApp(store, new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: "654321",
    firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
    publicBaseUrl: "https://calendar.example.test/api",
  });
  const phone = await request(app).post("/v1/devices/register").send({ pairingCode: "654321", installationId: "phone-1" }).expect(201);
  const pc = await request(app).post("/v1/devices/register").send({ pairingCode: "654321", installationId: "pc-1" }).expect(201);
  return { app, store, phone: `Bearer ${phone.body.deviceToken}`, pc: `Bearer ${pc.body.deviceToken}` };
}

const id = "3f2b7c1e-8a4d-4f6b-9c2e-1d5a7b9c0e12";
const reminder = {
  id,
  title: "Paint the back fence",
  startAt: "2026-10-22T22:30:00.000Z",
  timeZone: "America/Denver",
  location: "",
  notes: "Caulk is in the garage.",
  critical: false,
  done: false,
  domain: "life",
  category: "housing",
  reminderOffsetsMinutes: [30, 0],
};

describe("reminders made on a device", () => {
  it("join the cloud calendar under the device's id and reach every other device", async () => {
    const { app, phone, pc } = await paired();
    await request(app).post("/v1/events").send(reminder).expect(401);
    const created = await request(app).post("/v1/events").set("Authorization", phone).send(reminder).expect(201);
    expect(created.body.event).toMatchObject({ id, source: "manual", domain: "life", category: "housing", reminderOffsetsMinutes: [30, 0], done: false });

    const sync = await request(app).get("/v1/sync").set("Authorization", pc).expect(200);
    expect(sync.body.events.map((event: { id: string }) => event.id)).toEqual([id]);

    // Afterwards it is an ordinary cloud reminder: any device can change, complete or delete it.
    await request(app).post(`/v1/events/${id}/done`).set("Authorization", pc).expect(200);
    await request(app).delete(`/v1/events/${id}`).set("Authorization", phone).expect(200);
  });

  it("a retry returns the reminder it already made, unchanged", async () => {
    const { app, phone } = await paired();
    await request(app).post("/v1/events").set("Authorization", phone).send(reminder).expect(201);
    const retry = await request(app).post("/v1/events").set("Authorization", phone).send({ ...reminder, title: "Edited meanwhile" }).expect(200);
    expect(retry.body.created).toBe(false);
    expect(retry.body.event.title).toBe(reminder.title);
  });

  it("never overwrites a reminder from elsewhere, and never brings back a deleted one", async () => {
    const { app, store, phone } = await paired();
    await store.putEvent({ ...reminder, source: "chatgpt", revision: 1, createdAt: "", updatedAt: "" } as never);
    await request(app).post("/v1/events").set("Authorization", phone).send(reminder).expect(409);
    await store.putEvent({ ...reminder, source: "manual", revision: 1, createdAt: "", updatedAt: "", deletedAt: "2026-10-01T00:00:00Z" } as never);
    const gone = await request(app).post("/v1/events").set("Authorization", phone).send(reminder).expect(409);
    expect(gone.body.error).toContain("deleted");
  });

  it("rejects ids that are not UUIDs and invalid fields", async () => {
    const { app, phone } = await paired();
    await request(app).post("/v1/events").set("Authorization", phone).send({ ...reminder, id: "../events/x" }).expect(400);
    await request(app).post("/v1/events").set("Authorization", phone).send({ ...reminder, title: "  " }).expect(400);
    await request(app).post("/v1/events").set("Authorization", phone).send({ ...reminder, startAt: "soon" }).expect(400);
  });

  it("keeps a delivery silent and falls back to UTC for a zone the server does not know", async () => {
    const { app, phone } = await paired();
    const created = await request(app).post("/v1/events").set("Authorization", phone)
      .send({ ...reminder, domain: "delivery", category: "delivery", timeZone: "GMT+05:00" }).expect(201);
    expect(created.body.event.reminderOffsetsMinutes).toEqual([]);
    expect(created.body.event.timeZone).toBe("UTC");
  });
});
