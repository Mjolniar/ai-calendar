import request from "supertest";
import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { NoopNotifier } from "../src/notifier.js";
import { MemoryCalendarStore } from "../src/store.js";

function testApp(store = new MemoryCalendarStore()) {
  return createApp(store, new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: "654321",
    firebaseClientConfig: {
      projectId: "test-project",
      appId: "1:123:android:test",
      apiKey: "test-api-key",
      senderId: "123",
    },
    publicBaseUrl: "https://calendar.example.test/api",
  });
}

describe("device API", () => {
  it("returns Firebase bootstrap data only with the pairing code", async () => {
    const app = testApp();
    await request(app).post("/v1/bootstrap").send({ pairingCode: "bad" }).expect(401);
    const response = await request(app)
      .post("/v1/bootstrap")
      .send({ pairingCode: "654321" })
      .expect(200);
    expect(response.body.firebase.projectId).toBe("test-project");
  });

  it("rejects an invalid pairing code", async () => {
    await request(testApp())
      .post("/v1/devices/register")
      .send({ pairingCode: "bad", installationId: "fid-1" })
      .expect(401);
  });

  it("pairs a device and authorizes full sync", async () => {
    const app = testApp();
    const pairing = await request(app)
      .post("/v1/devices/register")
      .send({ pairingCode: "654321", installationId: "fid-1", deviceName: "Pixel" })
      .expect(201);

    expect(pairing.body.deviceToken).toHaveLength(43);
    const sync = await request(app)
      .get("/v1/sync")
      .set("Authorization", `Bearer ${pairing.body.deviceToken}`)
      .expect(200);
    expect(sync.body.events).toEqual([]);

    await request(app)
      .post("/v1/devices/installation-id")
      .set("Authorization", `Bearer ${pairing.body.deviceToken}`)
      .send({ installationId: "fid-2" })
      .expect(204);
  });

  it("registers polling desktop clients without FCM delivery", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);

    await request(app)
      .post("/v1/devices/register")
      .send({
        pairingCode: "654321",
        installationId: "desktop-client-1",
        deviceName: "Windows desktop",
        pushEnabled: false,
      })
      .expect(201);

    expect((await store.listDevices())[0].pushEnabled).toBe(false);
  });

  it("rejects sync without a device token", async () => {
    await request(testApp()).get("/v1/sync").expect(401);
  });

  it("persists Done from an authenticated device", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const now = new Date().toISOString();
    await store.putEvent({
      id: "dac8fded-f89e-5553-84e9-90d9204ae160",
      title: "Test reminder",
      startAt: "2026-08-20T15:00:00Z",
      timeZone: "America/Denver",
      location: "",
      notes: "",
      critical: true,
      reminderOffsetsMinutes: [0],
      done: false,
      domain: "life",
      category: "general",
      source: "chatgpt",
      revision: 1,
      createdAt: now,
      updatedAt: now,
    });
    const pairing = await request(app)
      .post("/v1/devices/register")
      .send({ pairingCode: "654321", installationId: "fid-done", deviceName: "Pixel" })
      .expect(201);
    await request(app)
      .post("/v1/events/dac8fded-f89e-5553-84e9-90d9204ae160/done")
      .set("Authorization", `Bearer ${pairing.body.deviceToken}`)
      .send({})
      .expect(200);
    const event = await store.getEvent("dac8fded-f89e-5553-84e9-90d9204ae160");
    expect(event?.done).toBe(true);
  });

  it("lets an authenticated device edit and permanently delete a remote reminder", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const now = new Date().toISOString();
    const id = "aac8fded-f89e-5553-84e9-90d9204ae160";
    await store.putEvent({
      id,
      title: "Original",
      startAt: "2026-09-03T15:00:00Z",
      timeZone: "America/Denver",
      location: "Room 1",
      notes: "Original notes",
      critical: false,
      reminderOffsetsMinutes: [1440],
      done: false,
      domain: "life",
      category: "general",
      source: "chatgpt",
      revision: 1,
      createdAt: now,
      updatedAt: now,
    });
    const pairing = await request(app)
      .post("/v1/devices/register")
      .send({ pairingCode: "654321", installationId: "fid-edit-delete", deviceName: "Pixel" })
      .expect(201);
    const authorization = `Bearer ${pairing.body.deviceToken}`;

    await request(app)
      .patch(`/v1/events/${id}`)
      .set("Authorization", authorization)
      .send({
        title: "Updated",
        startAt: "2026-09-03T16:30:00Z",
        timeZone: "America/Denver",
        location: "Room 2",
        notes: "Full updated notes",
        critical: true,
        reminderOffsetsMinutes: [1440, 0],
      })
      .expect(200);
    expect((await store.getEvent(id))?.title).toBe("Updated");

    await request(app)
      .delete(`/v1/events/${id}`)
      .set("Authorization", authorization)
      .expect(200);
    expect(await store.getEvent(id)).toBeNull();
  });
});

describe("deliveries over the device API", () => {
  it("lets a paired device move a reminder to Deliveries, which clears its alerts", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const now = new Date().toISOString();
    const id = "bbc8fded-f89e-5553-84e9-90d9204ae161";
    await store.putEvent({
      id,
      title: "Desk lamp arrives",
      startAt: "2026-10-06T15:00:00Z",
      timeZone: "America/Denver",
      location: "",
      notes: "",
      critical: false,
      reminderOffsetsMinutes: [1440],
      done: false,
      domain: "life",
      category: "automatic",
      source: "chatgpt",
      revision: 1,
      createdAt: now,
      updatedAt: now,
    });
    const pairing = await request(app)
      .post("/v1/devices/register")
      .send({ pairingCode: "654321", installationId: "fid-deliveries", deviceName: "Pixel" })
      .expect(201);
    const authorization = `Bearer ${pairing.body.deviceToken}`;

    const moved = await request(app)
      .patch(`/v1/events/${id}`)
      .set("Authorization", authorization)
      .send({
        title: "Desk lamp arrives",
        startAt: "2026-10-06T15:00:00Z",
        timeZone: "America/Denver",
        domain: "delivery",
        category: "delivery",
        reminderOffsetsMinutes: [1440],
      })
      .expect(200);
    expect(moved.body.event.domain).toBe("delivery");
    expect(moved.body.event.category).toBe("delivery");
    expect(moved.body.event.reminderOffsetsMinutes).toEqual([]);

    const synced = await request(app).get("/v1/sync").set("Authorization", authorization).expect(200);
    expect(synced.body.events.find((event: { id: string }) => event.id === id).domain).toBe("delivery");
  });
});

describe("MCP endpoint", () => {
  it("accepts a delivery from an AI client and stores it without alerts", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const created = await request(app)
      .post("/mcp")
      .set("Authorization", "Bearer test-api-token")
      .set("Accept", "application/json, text/event-stream")
      .send({
        jsonrpc: "2.0",
        id: 20,
        method: "tools/call",
        params: {
          name: "publish_task_reminders",
          arguments: {
            taskKey: "order-emails",
            reminders: [{
              itemKey: "lamp",
              title: "Desk lamp arrives",
              startAt: "2026-10-06T09:00:00-06:00",
              timeZone: "America/Denver",
              domain: "delivery",
              critical: true,
            }],
          },
        },
      })
      .expect(200);
    expect(created.text).not.toContain("Input validation error");
    const events = await store.listEvents(true);
    expect(events).toHaveLength(1);
    expect(events[0].domain).toBe("delivery");
    expect(events[0].reminderOffsetsMinutes).toEqual([]);
  });

  it("requires the ChatGPT API token", async () => {
    await request(testApp()).post("/mcp").send({}).expect(401);
  });

  it("advertises reminder tools", async () => {
    const app = testApp();
    const initialize = await request(app)
      .post("/mcp")
      .set("Authorization", "Bearer test-api-token")
      .set("Accept", "application/json, text/event-stream")
      .send({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-06-18",
          capabilities: {},
          clientInfo: { name: "test", version: "1.0.0" },
        },
      })
      .expect(200);
    expect(initialize.text).toContain("ai-calendar");

    const tools = await request(app)
      .post("/mcp")
      .set("Authorization", "Bearer test-api-token")
      .set("Accept", "application/json, text/event-stream")
      .send({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} })
      .expect(200);
    expect(tools.text).toContain("create_reminder");
    expect(tools.text).toContain("delete_reminder");
    expect(tools.text).toContain("publish_task_reminders");
    expect(tools.text).toContain("securitySchemes");
    expect(tools.text).toContain("oauth2");
    expect(tools.text).toContain("outputSchema");
  });

  it("rejects invalid and oversized task reminder batches", async () => {
    const app = testApp();
    const call = (reminders: unknown[]) => request(app)
      .post("/mcp")
      .set("Authorization", "Bearer test-api-token")
      .set("Accept", "application/json, text/event-stream")
      .send({
        jsonrpc: "2.0",
        id: 10,
        method: "tools/call",
        params: { name: "publish_task_reminders", arguments: { taskKey: "planner", reminders } },
      });
    const invalid = await call([{
      itemKey: "bad-time",
      title: "Bad time",
      startAt: "tomorrow morning",
      timeZone: "America/Denver",
    }]).expect(200);
    expect(invalid.text).toContain("Input validation error");

    const item = {
      itemKey: "item",
      title: "Reminder",
      startAt: "2026-08-20T09:00:00-06:00",
      timeZone: "America/Denver",
    };
    const oversized = await call(Array.from({ length: 51 }, (_, index) => ({ ...item, itemKey: `item-${index}` }))).expect(200);
    expect(oversized.text).toContain("Input validation error");
  });

  it("completes OAuth PKCE and authorizes MCP calls", async () => {
    const app = testApp();
    const redirectUri = "https://chatgpt.com/connector/oauth/test-callback";
    const registration = await request(app)
      .post("/oauth/register")
      .send({ client_name: "ChatGPT", redirect_uris: [redirectUri] })
      .expect(201);
    const clientId = registration.body.client_id;
    const verifier = "test-verifier-value-with-at-least-forty-three-characters";
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    const authorization = await request(app)
      .post("/oauth/authorize")
      .type("form")
      .send({
        response_type: "code",
        client_id: clientId,
        redirect_uri: redirectUri,
        code_challenge: challenge,
        code_challenge_method: "S256",
        resource: "https://calendar.example.test/api/mcp",
        scope: "reminders:read reminders:write",
        state: "test-state",
        pairing_code: "654321",
      })
      .expect(303);
    const code = new URL(authorization.headers.location).searchParams.get("code");
    expect(code).toBeTruthy();

    const token = await request(app)
      .post("/oauth/token")
      .type("form")
      .send({
        grant_type: "authorization_code",
        code,
        client_id: clientId,
        redirect_uri: redirectUri,
        code_verifier: verifier,
        resource: "https://calendar.example.test/api/mcp",
      })
      .expect(200);
    expect(token.body.scope).toContain("reminders:write");

    const tools = await request(app)
      .post("/mcp")
      .set("Authorization", `Bearer ${token.body.access_token}`)
      .set("Accept", "application/json, text/event-stream")
      .send({ jsonrpc: "2.0", id: 3, method: "tools/list", params: {} })
      .expect(200);
    expect(tools.text).toContain("create_reminder");
  });
});
