import request from "supertest";
import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp } from "../src/app.js";
import { FirebaseDeviceNotifier, NoopNotifier, type DeviceNotifier } from "../src/notifier.js";
import { CalendarService } from "../src/service.js";
import { MemoryCalendarStore } from "../src/store.js";
import { AttemptLimiter, secureEqual } from "../src/security.js";
import type { ReminderEvent } from "../src/types.js";

const BASE = "https://calendar.example.test/api";
const PAIRING = "654321";

function testApp(store = new MemoryCalendarStore(), extra: Record<string, unknown> = {}) {
  return createApp(store, new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: PAIRING,
    firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
    publicBaseUrl: BASE,
    ...extra,
  });
}

async function mcpCall(app: ReturnType<typeof testApp>, name: string, args: unknown) {
  const res = await request(app).post("/mcp")
    .set("Authorization", "Bearer test-api-token")
    .set("Accept", "application/json, text/event-stream")
    .send({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } });
  const line = res.text.split("\n").find((l) => l.startsWith("data: "));
  return { status: res.status, body: JSON.parse(line ? line.slice(6) : res.text) };
}

function baseEvent(id: string, overrides: Partial<ReminderEvent> = {}): ReminderEvent {
  const now = new Date().toISOString();
  return {
    id, title: "Original", startAt: "2026-09-03T15:00:00.000Z", timeZone: "America/Denver", location: "", notes: "",
    critical: false, reminderOffsetsMinutes: [1440], done: false, domain: "life", category: "general", source: "chatgpt",
    revision: 1, createdAt: now, updatedAt: now, ...overrides,
  };
}

async function pairDevice(app: ReturnType<typeof testApp>, installationId = "fid-1") {
  const res = await request(app).post("/v1/devices/register")
    .send({ pairingCode: PAIRING, installationId, deviceName: "Pixel" }).expect(201);
  return `Bearer ${res.body.deviceToken}`;
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("audit #3: concurrent writes", () => {
  it("creates one event for simultaneous creates with the same idempotency key", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier());
    const input = { title: "Dentist", startAt: "2026-10-01T09:00:00-06:00", timeZone: "America/Denver", idempotencyKey: "dentist-2026-10-01" };
    const [a, b] = await Promise.all([service.create(input), service.create(input)]);
    expect(a.event.id).toBe(b.event.id);
    expect([a.created, b.created].sort()).toEqual([false, true]);
    expect(await store.listEvents(true)).toHaveLength(1);
  });

  it("keeps both a concurrent Done and a title edit", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier());
    const id = "aac8fded-f89e-5553-84e9-90d9204ae160";
    await store.putEvent(baseEvent(id));
    await Promise.all([service.update(id, { done: true }), service.update(id, { title: "Renamed" })]);
    const event = await store.getEvent(id);
    expect(event?.done).toBe(true);
    expect(event?.title).toBe("Renamed");
  });

  it("does not let Canvas completion overwrite a concurrent edit", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier());
    const published = await service.publishTaskReminders({
      taskKey: "canvas",
      reminders: [{ itemKey: "assignment:1", title: "Essay", startAt: "2026-10-01T09:00:00-06:00", timeZone: "America/Denver" }],
    });
    const id = published.reminders[0].event.id;
    await Promise.all([service.setTaskItemsDone("canvas", new Set(["assignment:1"])), service.update(id, { notes: "edited on phone" })]);
    const event = await store.getEvent(id);
    expect(event?.done).toBe(true);
    expect(event?.notes).toBe("edited on phone");
  });
});

describe("audit #7: request body limits", () => {
  it("accepts a valid maximum-detail 50-item batch", async () => {
    const app = testApp();
    const reminders = Array.from({ length: 50 }, (_, index) => ({
      itemKey: `item-${index}`,
      title: "T".repeat(200),
      startAt: "2026-10-01T09:00:00-06:00",
      timeZone: "America/Denver",
      location: "L".repeat(500),
      notes: "é".repeat(4000),
    }));
    const { status, body } = await mcpCall(app, "publish_task_reminders", { taskKey: "planner", reminders });
    expect(status).toBe(200);
    expect(body.result.isError).toBeFalsy();
    expect(body.result.structuredContent.created).toBe(50);
  });

  it("returns 413 (not 500) for an oversized body and 400 for malformed JSON", async () => {
    const app = testApp();
    const big = await request(app).post("/v1/bootstrap").set("Content-Type", "application/json")
      .send(JSON.stringify({ pairingCode: "x".repeat(5 * 1024 * 1024) }));
    expect(big.status).toBe(413);
    const malformed = await request(app).post("/v1/bootstrap").set("Content-Type", "application/json").send("{\"pairingCode\":");
    expect(malformed.status).toBe(400);
    expect(malformed.body.error).toBe("Malformed request body");
  });

  it("never logs the raw request body of a rejected request", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    await request(testApp()).post("/v1/bootstrap").set("Content-Type", "application/json").send("{\"pairingCode\":\"super-secret-value\",");
    const logged = JSON.stringify([...warn.mock.calls, ...error.mock.calls]);
    expect(logged).not.toContain("super-secret-value");
  });
});

describe("audit #8: device edit validation", () => {
  it("rejects blank titles, bad offsets and bad dates without changing stored data", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const id = "aac8fded-f89e-5553-84e9-90d9204ae160";
    await store.putEvent(baseEvent(id));
    const auth = await pairDevice(app);
    const valid = { title: "Ok", startAt: "2026-09-03T16:30:00Z", timeZone: "America/Denver" };
    await request(app).patch(`/v1/events/${id}`).set("Authorization", auth).send({ ...valid, title: "   " }).expect(400);
    await request(app).patch(`/v1/events/${id}`).set("Authorization", auth).send({ ...valid, reminderOffsetsMinutes: ["oops"] }).expect(400);
    await request(app).patch(`/v1/events/${id}`).set("Authorization", auth).send({ ...valid, startAt: "not a date" }).expect(400);
    expect((await store.getEvent(id))?.title).toBe("Original");
    expect((await store.getEvent(id))?.reminderOffsetsMinutes).toEqual([1440]);
  });

  it("keeps the stored zone when a phone reports a non-IANA zone", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const id = "aac8fded-f89e-5553-84e9-90d9204ae160";
    await store.putEvent(baseEvent(id));
    const auth = await pairDevice(app);
    await request(app).patch(`/v1/events/${id}`).set("Authorization", auth)
      .send({ title: "Renamed", startAt: "2026-09-03T16:30:00Z", timeZone: "Not/AZone" }).expect(200);
    const event = await store.getEvent(id);
    expect(event?.title).toBe("Renamed");
    expect(event?.timeZone).toBe("America/Denver");
  });

  it("rejects blank titles and unknown zones over MCP", async () => {
    const app = testApp();
    const blank = await mcpCall(app, "create_reminder", { title: "   ", startAt: "2026-10-01T09:00:00-06:00", timeZone: "America/Denver" });
    expect(blank.body.result.isError).toBe(true);
    const zone = await mcpCall(app, "create_reminder", { title: "Ok", startAt: "2026-10-01T09:00:00-06:00", timeZone: "Not/AZone" });
    expect(zone.body.result.isError).toBe(true);
  });

  it("returns 404 for event IDs that are not document-safe", async () => {
    const app = testApp();
    const auth = await pairDevice(app);
    await request(app).post("/v1/events/a%2Fb/done").set("Authorization", auth).send({}).expect(404);
    await request(app).delete("/v1/events/a%2Fb").set("Authorization", auth).expect(404);
  });
});

describe("audit #9: OAuth async failures", () => {
  class FailingStore extends MemoryCalendarStore {
    override async getOAuthClient(): Promise<never> { throw new Error("firestore down"); }
    override async putOAuthClient(): Promise<never> { throw new Error("firestore down"); }
    override async takeOAuthCode(): Promise<never> { throw new Error("firestore down"); }
  }

  it("turns store failures into HTTP 500 responses instead of unhandled rejections", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const app = testApp(new FailingStore());
    await request(app).post("/oauth/register").send({ redirect_uris: ["https://claude.ai/cb"] }).expect(500);
    await request(app).get("/oauth/authorize").query({
      response_type: "code", client_id: "c1", redirect_uri: "https://claude.ai/cb",
      code_challenge: "a".repeat(43), code_challenge_method: "S256",
    }).expect(500);
    await request(app).post("/oauth/token").type("form").send({ grant_type: "authorization_code", code: "x" }).expect(500);
  });

  it("rejects array-valued or malformed authorize parameters with 400", async () => {
    const app = testApp();
    const res = await request(app).get("/oauth/authorize?response_type=code&code_challenge_method=S256&code_challenge=a&code_challenge=b&client_id=c&redirect_uri=https://x");
    expect(res.status).toBe(400);
    const bad = await request(app).get("/oauth/authorize").query({
      response_type: "code", client_id: "c1", redirect_uri: "https://claude.ai/cb", code_challenge: "short", code_challenge_method: "S256",
    });
    expect(bad.status).toBe(400);
  });
});

describe("OAuth hardening", () => {
  async function authorize(app: ReturnType<typeof testApp>) {
    const redirectUri = "https://claude.ai/api/mcp/auth_callback";
    const reg = await request(app).post("/oauth/register").send({ client_name: "Claude", redirect_uris: [redirectUri] }).expect(201);
    const verifier = "v".repeat(50);
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    const page = await request(app).get("/oauth/authorize").query({
      response_type: "code", client_id: reg.body.client_id, redirect_uri: redirectUri, code_challenge: challenge, code_challenge_method: "S256",
    }).expect(200);
    expect(page.text).toContain("claude.ai");
    expect(page.headers["x-frame-options"]).toBe("DENY");
    const auth = await request(app).post("/oauth/authorize").type("form").send({
      response_type: "code", client_id: reg.body.client_id, redirect_uri: redirectUri,
      code_challenge: challenge, code_challenge_method: "S256", pairing_code: PAIRING,
    }).expect(303);
    const code = new URL(auth.headers.location).searchParams.get("code")!;
    const tok = await request(app).post("/oauth/token").type("form").send({
      grant_type: "authorization_code", code, code_verifier: verifier, client_id: reg.body.client_id, redirect_uri: redirectUri,
    }).expect(200);
    expect(tok.headers["cache-control"]).toBe("no-store");
    // Authorization codes are single-use.
    await request(app).post("/oauth/token").type("form").send({
      grant_type: "authorization_code", code, code_verifier: verifier, client_id: reg.body.client_id, redirect_uri: redirectUri,
    }).expect(400);
    return { clientId: reg.body.client_id as string, tokens: tok.body as { access_token: string; refresh_token: string } };
  }

  it("rotates refresh tokens with a short reuse grace window", async () => {
    const app = testApp();
    const { clientId, tokens } = await authorize(app);
    const refresh = () => request(app).post("/oauth/token").type("form")
      .send({ grant_type: "refresh_token", refresh_token: tokens.refresh_token, client_id: clientId });
    const first = await refresh().expect(200);
    expect(first.body.refresh_token).not.toBe(tokens.refresh_token);
    await refresh().expect(200); // lost-response retry inside the grace window
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.now() + 3 * 60 * 1000);
    await refresh().expect(400);
    await request(app).post("/oauth/token").type("form")
      .send({ grant_type: "refresh_token", refresh_token: first.body.refresh_token, client_id: clientId }).expect(200);
  });

  it("throttles dynamic client registration per client", async () => {
    const app = testApp();
    for (let i = 0; i < 20; i += 1) {
      await request(app).post("/oauth/register").set("X-Forwarded-For", "203.0.113.9").send({ redirect_uris: ["https://claude.ai/cb"] }).expect(201);
    }
    await request(app).post("/oauth/register").set("X-Forwarded-For", "203.0.113.9").send({ redirect_uris: ["https://claude.ai/cb"] }).expect(429);
    await request(app).post("/oauth/register").set("X-Forwarded-For", "198.51.100.1").send({ redirect_uris: ["https://claude.ai/cb"] }).expect(201);
  });
});

describe("pairing-code brute force protection", () => {
  it("locks a client out after repeated failures across pairing endpoints", async () => {
    const app = testApp();
    const attacker = "203.0.113.7";
    for (let i = 0; i < 5; i += 1) {
      await request(app).post("/v1/bootstrap").set("X-Forwarded-For", attacker).send({ pairingCode: `bad-${i}` }).expect(401);
      await request(app).post("/v1/devices/register").set("X-Forwarded-For", attacker).send({ pairingCode: `bad-${i}`, installationId: "fid" }).expect(401);
    }
    const locked = await request(app).post("/v1/bootstrap").set("X-Forwarded-For", attacker).send({ pairingCode: PAIRING });
    expect(locked.status).toBe(429);
    expect(Number(locked.headers["retry-after"])).toBeGreaterThan(0);
    await request(app).post("/oauth/authorize").set("X-Forwarded-For", attacker).type("form").send({}).expect(400);
    // A different client is unaffected.
    await request(app).post("/v1/bootstrap").set("X-Forwarded-For", "198.51.100.2").send({ pairingCode: PAIRING }).expect(200);
  });

  it("keys on the proxy-appended hop, not a spoofable first hop", async () => {
    const app = testApp();
    for (let i = 0; i < 10; i += 1) {
      await request(app).post("/v1/bootstrap").set("X-Forwarded-For", `10.0.0.${i}, 203.0.113.8`).send({ pairingCode: "bad" }).expect(401);
    }
    await request(app).post("/v1/bootstrap").set("X-Forwarded-For", "10.9.9.9, 203.0.113.8").send({ pairingCode: PAIRING }).expect(429);
  });

  it("limiter window expires", () => {
    let now = 0;
    const limiter = new AttemptLimiter(2, 1000, () => now);
    limiter.record("k");
    limiter.record("k");
    expect(limiter.retryAfter("k")).toBe(1);
    now = 1001;
    expect(limiter.retryAfter("k")).toBe(0);
  });

  it("compares secrets in constant time regardless of length", () => {
    expect(secureEqual("abc", "abc")).toBe(true);
    expect(secureEqual("abc", "abcd")).toBe(false);
    expect(secureEqual("", "x")).toBe(false);
  });
});

describe("audit #10: post-save signaling", () => {
  it("reports success when the device lookup fails after the event is saved", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    class BrokenDevices extends MemoryCalendarStore {
      override async listDevices(): Promise<never> { throw new Error("devices unavailable"); }
    }
    const store = new BrokenDevices();
    const service = new CalendarService(store, new NoopNotifier());
    const created = await service.create({ title: "Pay rent", startAt: "2026-10-01T09:00:00-06:00", timeZone: "America/Denver" });
    expect(created.created).toBe(true);
    expect(created.notified).toBe(0);
    expect(await store.getEvent(created.event.id)).not.toBeNull();
  });
});

describe("audit #11: durable deletion of task items", () => {
  const item = { itemKey: "rent", title: "Rent due", startAt: "2026-10-01T09:00:00-06:00", timeZone: "America/Denver" };

  it("does not resurrect a deleted task item on republish unless restore is requested", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier());
    const first = await service.publishTaskReminders({ taskKey: "planner", reminders: [item] });
    const id = first.reminders[0].event.id;
    expect((await service.delete(id)).deleted).toBe(true);
    expect(await service.get(id)).toBeNull();
    expect(await service.list(true)).toHaveLength(0);
    expect((await service.delete(id)).deleted).toBe(false);
    expect(await service.update(id, { title: "x" })).toBeNull();

    const again = await service.publishTaskReminders({ taskKey: "planner", reminders: [item] });
    expect(again.created).toBe(0);
    expect(again.suppressed).toBe(1);
    expect(again.suppressedItemKeys).toEqual(["rent"]);
    expect(await service.list(true)).toHaveLength(0);

    const restored = await service.publishTaskReminders({ taskKey: "planner", restoreDeleted: true, reminders: [item] });
    expect(restored.created).toBe(1);
    expect((await service.get(id))?.done).toBe(false);
  });

  it("erases the content of every deleted reminder and hides tombstones from device sync", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const auth = await pairDevice(app);
    const published = await mcpCall(app, "publish_task_reminders", { taskKey: "planner", reminders: [item] });
    const taskId = published.body.result.structuredContent.reminders[0].event.id;
    const created = await mcpCall(app, "create_reminder", { title: "Call mom", startAt: "2026-10-01T09:00:00-06:00", timeZone: "America/Denver" });
    const plainId = created.body.result.structuredContent.event.id;
    await request(app).delete(`/v1/events/${taskId}`).set("Authorization", auth).expect(200);
    await request(app).delete(`/v1/events/${plainId}`).set("Authorization", auth).expect(200);
    // Both are tombstones now, so neither can come back; neither keeps what it said.
    for (const id of [plainId, taskId]) {
      const tombstone = await store.getEvent(id);
      expect(tombstone?.deletedAt).toBeTruthy();
      expect(tombstone).toMatchObject({ title: "", notes: "", location: "" });
    }
    const sync = await request(app).get("/v1/sync").set("Authorization", auth).expect(200);
    expect(sync.body.events).toEqual([]);
    const republished = await mcpCall(app, "publish_task_reminders", { taskKey: "planner", reminders: [item] });
    expect(republished.body.result.structuredContent.suppressed).toBe(1);
  });
});

describe("push device hygiene", () => {
  it("skips devices FCM reported as unregistered and re-enables them on a new installation ID", async () => {
    const notifier = new FirebaseDeviceNotifier();
    const device = { id: "d1", installationId: "fid", authHash: "h", deviceName: "Pixel", createdAt: "", lastSeenAt: "", pushInvalidSince: "2026-09-01T00:00:00Z" };
    // Returns before touching FCM because no device is eligible.
    expect(await notifier.signalSync([device], 1)).toBe(0);

    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const auth = await pairDevice(app, "fid-old");
    const [registered] = await store.listDevices();
    await store.patchDevice(registered.id, { pushInvalidSince: "2026-09-01T00:00:00Z" });
    await request(app).post("/v1/devices/installation-id").set("Authorization", auth).send({ installationId: "fid-old" }).expect(204);
    expect((await store.listDevices())[0].pushInvalidSince).toBeTruthy();
    await request(app).post("/v1/devices/installation-id").set("Authorization", auth).send({ installationId: "fid-new" }).expect(204);
    expect((await store.listDevices())[0].pushInvalidSince).toBeUndefined();
  });

  it("does not fail the request when push signaling hangs past the timeout", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const hanging: DeviceNotifier = { signalSync: () => new Promise<number>(() => undefined) };
    const service = new CalendarService(new MemoryCalendarStore(), hanging);
    const pending = service.create({ title: "Flight", startAt: "2026-10-01T09:00:00-06:00", timeZone: "America/Denver" });
    await vi.advanceTimersByTimeAsync(10_001);
    const created = await pending;
    expect(created.created).toBe(true);
    expect(created.notified).toBe(0);
  });
});

describe("Outlook hardening", () => {
  function outlookApp(store: MemoryCalendarStore, fetcher: typeof fetch) {
    return testApp(store, { outlook: { clientId: "client-1", clientSecret: "secret-1" }, fetcher });
  }

  it("refuses continuation links to Graph resources other than mail folders", async () => {
    const store = new MemoryCalendarStore();
    await store.putKv("outlookAccount", { refreshToken: "rt", email: "x", connectedAt: "", updatedAt: "" });
    const calls: string[] = [];
    const fetcher = (async (input: RequestInfo | URL) => {
      calls.push(String(input));
      return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }), { status: 200 });
    }) as typeof fetch;
    const res = await mcpCall(outlookApp(store, fetcher), "outlook_list_messages", { nextLink: "https://graph.microsoft.com/v1.0/me/drive/root/children" });
    expect(res.body.result.isError).toBe(true);
    expect(calls.some((url) => url.includes("/me/drive"))).toBe(false);
  });

  it("sends a timeout signal, retries one 429, and survives non-JSON error bodies", async () => {
    const store = new MemoryCalendarStore();
    await store.putKv("outlookAccount", { refreshToken: "rt", email: "x", connectedAt: "", updatedAt: "" });
    let graphCalls = 0;
    const signals: Array<AbortSignal | null | undefined> = [];
    const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
      signals.push(init?.signal);
      const url = String(input);
      if (url.endsWith("/token")) return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }), { status: 200 });
      graphCalls += 1;
      if (url.includes("/messages/m1?")) {
        return graphCalls === 1
          ? new Response("slow down", { status: 429, headers: { "Retry-After": "0" } })
          : new Response(JSON.stringify({ id: "m1", subject: "Hi" }), { status: 200 });
      }
      return new Response("<html>Bad gateway</html>", { status: 502 });
    }) as typeof fetch;
    const app = outlookApp(store, fetcher);
    const ok = await mcpCall(app, "outlook_get_message", { id: "m1" });
    expect(ok.body.result.isError).toBeFalsy();
    expect(graphCalls).toBe(2);
    expect(signals.every((signal) => signal instanceof AbortSignal)).toBe(true);
    const bad = await mcpCall(app, "outlook_move_message", { id: "m2", destination: "archive" });
    expect(bad.body.result.isError).toBe(true);
    expect(bad.body.result.content[0].text).toContain("502");
  });
});
