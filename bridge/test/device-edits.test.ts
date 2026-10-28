import request from "supertest";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { NoopNotifier } from "../src/notifier.js";
import { MemoryCalendarStore } from "../src/store.js";

/** Two paired devices (a phone and the PC) editing the same reminder. */
async function twoDevices() {
  const store = new MemoryCalendarStore();
  const app = createApp(store, new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: "654321",
    firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
    publicBaseUrl: "https://calendar.example.test/api",
  });
  const pair = async (installationId: string) =>
    `Bearer ${(await request(app).post("/v1/devices/register").send({ pairingCode: "654321", installationId }).expect(201)).body.deviceToken}`;
  const phone = await pair("phone-1");
  const pc = await pair("pc-1");
  const id = "3f2b7c1e-8a4d-4f6b-9c2e-1d5a7b9c0e12";
  const created = await request(app).post("/v1/events").set("Authorization", phone).send({
    id, title: "Dentist", startAt: "2026-11-05T16:00:00Z", timeZone: "America/Denver", location: "Main St", notes: "Bring card", critical: true,
  }).expect(201);
  return { app, phone, pc, id, revision: created.body.event.revision as number };
}

const fullEdit = (overrides: Record<string, unknown> = {}) => ({
  title: "Dentist", startAt: "2026-11-05T16:00:00Z", timeZone: "America/Denver", location: "Main St", notes: "Bring card",
  critical: true, done: false, domain: "life", category: "medical", reminderOffsetsMinutes: [1440, 0], ...overrides,
});

describe("device edits never overwrite a newer change", () => {
  it("an edit made from an older copy is refused, and the newer change stays", async () => {
    const { app, phone, pc, id, revision } = await twoDevices();
    await request(app).post(`/v1/events/${id}/done`).set("Authorization", pc).expect(200);
    // The phone edits the notes from the copy it had before the PC ticked Done.
    const stale = await request(app).patch(`/v1/events/${id}`).set("Authorization", phone)
      .send(fullEdit({ notes: "Bring card and X-rays", baseRevision: revision })).expect(409);
    expect(stale.body.event).toMatchObject({ done: true, notes: "Bring card" });
    const sync = await request(app).get("/v1/sync").set("Authorization", phone).expect(200);
    expect(sync.body.events.find((event: { id: string }) => event.id === id)).toMatchObject({ done: true });
  });

  it("an edit made from the current copy is applied", async () => {
    const { app, phone, id, revision } = await twoDevices();
    const edited = await request(app).patch(`/v1/events/${id}`).set("Authorization", phone)
      .send(fullEdit({ notes: "Bring card and X-rays", baseRevision: revision })).expect(200);
    expect(edited.body.event.notes).toBe("Bring card and X-rays");
    expect(edited.body.event.revision).toBeGreaterThan(revision);
  });

  it("Done and not done have their own calls that change nothing else", async () => {
    const { app, pc, phone, id } = await twoDevices();
    await request(app).patch(`/v1/events/${id}`).set("Authorization", phone).send({ notes: "Moved to 10:00" }).expect(200);
    const done = await request(app).post(`/v1/events/${id}/done`).set("Authorization", pc).expect(200);
    expect(done.body.event).toMatchObject({ done: true, notes: "Moved to 10:00" });
    const undone = await request(app).post(`/v1/events/${id}/undone`).set("Authorization", pc).expect(200);
    expect(undone.body.event).toMatchObject({ done: false, notes: "Moved to 10:00" });
    await request(app).post("/v1/events/missing-id/undone").set("Authorization", pc).expect(404);
  });

  it("a partial edit keeps the fields it does not name", async () => {
    const { app, phone, id } = await twoDevices();
    const edited = await request(app).patch(`/v1/events/${id}`).set("Authorization", phone).send({ notes: "Ask about the crown" }).expect(200);
    expect(edited.body.event).toMatchObject({ title: "Dentist", location: "Main St", critical: true, notes: "Ask about the crown" });
    await request(app).patch(`/v1/events/${id}`).set("Authorization", phone).send({}).expect(400);
  });

  it("apps installed before this change still work: a full edit without a base revision is applied", async () => {
    const { app, phone, id } = await twoDevices();
    const edited = await request(app).patch(`/v1/events/${id}`).set("Authorization", phone).send(fullEdit({ title: "Dentist checkup" })).expect(200);
    expect(edited.body.event.title).toBe("Dentist checkup");
  });
});
