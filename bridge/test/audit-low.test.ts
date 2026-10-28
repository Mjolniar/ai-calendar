import request from "supertest";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp } from "../src/app.js";
import { CanvasSyncService } from "../src/canvas.js";
import { NoopNotifier } from "../src/notifier.js";
import { CalendarService } from "../src/service.js";
import { MemoryCalendarStore } from "../src/store.js";
import type { ReminderEvent, SeriesInput } from "../src/types.js";

/** The low findings of handoff/AUDIT-2026-10-26.md, section 5 (fixed 2026-10-28). */
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

async function pairDevice(app: ReturnType<typeof testApp>) {
  const res = await request(app).post("/v1/devices/register").send({ pairingCode: PAIRING, installationId: "fid-1", deviceName: "Pixel" }).expect(201);
  return `Bearer ${res.body.deviceToken}`;
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("body size limits", () => {
  it("parses at most 16 KB before authentication on public routes", async () => {
    const app = testApp();
    const res = await request(app).post("/v1/bootstrap").set("Content-Type", "application/json")
      .send(JSON.stringify({ pairingCode: "x".repeat(20 * 1024) }));
    expect(res.status).toBe(413);
    await request(app).post("/oauth/register").send({ client_name: "Claude", redirect_uris: ["https://claude.ai/api/mcp/auth_callback"] }).expect(201);
  });

  it("does not parse an MCP body before the token is checked, and still takes a large valid call", async () => {
    const app = testApp();
    // Malformed on purpose: parsed before the token check it would answer 400; unparsed, 401.
    const big = `{"jsonrpc":"2.0","pad":"${"x".repeat(1024 * 1024)}`;
    await request(app).post("/mcp").set("Content-Type", "application/json").send(big).expect(401);
    const { status, body } = await mcpCall(app, "planner_state_put", { key: "big.log", content: "y".repeat(600_000) });
    expect(status).toBe(200);
    expect(body.result.isError).toBeFalsy();
  });

  it("still takes the largest reminder a device may send (10,000 characters of notes, any script)", async () => {
    const app = testApp();
    const auth = await pairDevice(app);
    await request(app).post("/v1/events").set("Authorization", auth).send({
      id: "3f2b7c1e-8a4d-4f6b-9c2e-1d5a7b9c0e12", title: "T".repeat(500), startAt: "2026-11-01T15:00:00Z", timeZone: "America/Denver",
      location: "地".repeat(1000), notes: "😀".repeat(5000),
    }).expect(201);
  });
});

describe("error log hygiene", () => {
  it("logs only the type and status of a rejected request, never the message", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    await request(testApp()).post("/v1/bootstrap").set("Content-Type", "application/json").send("{\"pairingCode\":\"abc");
    const logged = JSON.stringify(warn.mock.calls);
    expect(logged).toContain("400");
    expect(logged).not.toMatch(/message/i);
    expect(logged).not.toContain("abc");
  });
});

describe("planner state", () => {
  it("refuses a key that would share a document with another key, and keeps the first one's data", async () => {
    const app = testApp();
    await mcpCall(app, "planner_state_put", { key: "notes.json", content: "first" });
    const clash = await mcpCall(app, "planner_state_put", { key: "notes_json", content: "second" });
    expect(clash.body.result.isError).toBe(true);
    expect(clash.body.result.content[0].text).toContain("notes.json");
    expect((await mcpCall(app, "planner_state_get", { key: "notes.json" })).body.result.structuredContent.content).toBe("first");
    // The owner can still write it.
    expect((await mcpCall(app, "planner_state_put", { key: "notes.json", content: "third" })).body.result.isError).toBeFalsy();
  });

  it("counts the 700,000 limit in UTF-8 bytes, and trims appends by bytes", async () => {
    const app = testApp();
    const tooBig = await mcpCall(app, "planner_state_put", { key: "wide.log", content: "é".repeat(400_000) });
    expect(tooBig.body.result.isError).toBe(true);
    expect(tooBig.body.result.content[0].text).toMatch(/700,?000 bytes/);
    await mcpCall(app, "planner_state_put", { key: "wide.log", content: "é".repeat(340_000) });
    const appended = await mcpCall(app, "planner_state_put", { key: "wide.log", content: "ü".repeat(20_000), mode: "append" });
    expect(appended.body.result.isError).toBeFalsy();
    const content = (await mcpCall(app, "planner_state_get", { key: "wide.log" })).body.result.structuredContent.content as string;
    expect(Buffer.byteLength(content, "utf8")).toBeLessThanOrEqual(700_000);
    expect(content.endsWith("ü".repeat(20_000))).toBe(true);
  });

  it("appends in a transaction: concurrent appends are all kept", async () => {
    const store = new MemoryCalendarStore();
    // Make reads and writes interleave the way two instances would.
    const getKv = store.getKv.bind(store);
    vi.spyOn(store, "getKv").mockImplementation(async (key) => {
      const value = await getKv(key);
      await new Promise((resolve) => setTimeout(resolve, 5));
      return value;
    });
    const app = testApp(store);
    await mcpCall(app, "planner_state_put", { key: "runs.log", content: "start" });
    await Promise.all(["a", "b", "c"].map((line) => mcpCall(app, "planner_state_put", { key: "runs.log", content: line, mode: "append" })));
    const content = (await store.getKv("planner:runs.log"))?.content as string;
    expect(content.split("\n").sort()).toEqual(["a", "b", "c", "start"]);
  });
});

describe("phone update download", () => {
  it("answers an error instead of crashing when the APK cannot be read", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const dir = mkdtempSync(join(tmpdir(), "release-"));
    // A directory where the file should be: stat succeeds, reading fails.
    mkdirSync(join(dir, "app.apk"));
    writeFileSync(join(dir, "release.json"), JSON.stringify({
      versionCode: 27, versionName: "0.15.0", sha256: createHash("sha256").update("").digest("hex"), size: 0, publishedAt: "2026-10-21T18:00:00Z",
    }));
    const app = testApp(new MemoryCalendarStore(), { releaseDir: dir });
    const auth = await pairDevice(app);
    const res = await request(app).get("/v1/app/apk").set("Authorization", auth).then((r) => r.status, () => "aborted");
    expect(res === "aborted" || (typeof res === "number" && res >= 500)).toBe(true);
    // The app is still alive.
    await request(app).get("/health").expect(200);
  });
});

describe("series input", () => {
  const body = { title: "Water plants", timeZone: "America/Denver", rule: { freq: "weekly", startDate: "2099-01-01", time: "18:00" } };

  it("answers 400, not 500, for malformed rule fields from a device", async () => {
    const app = testApp();
    const auth = await pairDevice(app);
    for (const weekdays of [{ length: 2 }, { 0: 1 }, "1,2"]) {
      const res = await request(app).post("/v1/series").set("Authorization", auth).send({ ...body, rule: { ...body.rule, weekdays } });
      expect(res.status, JSON.stringify(weekdays)).toBe(400);
    }
    const created = await request(app).post("/v1/series").set("Authorization", auth).send(body).expect(201);
    await request(app).patch(`/v1/series/${created.body.series.id}`).set("Authorization", auth).send({ rule: { weekdays: { length: 1 } } }).expect(400);
    await request(app).patch(`/v1/series/${created.body.series.id}`).set("Authorization", auth).send({ rule: { interval: "2" } }).expect(400);
  });

  it("bounds the first date to 2000-01-01 .. 2100-12-31", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new NoopNotifier());
    const input = (startDate: string): SeriesInput => ({ title: "X", timeZone: "America/Denver", rule: { freq: "yearly", startDate, time: "09:00" } });
    await expect(service.createSeries(input("0001-01-01"))).rejects.toThrow(/2000-01-01/);
    await expect(service.createSeries(input("2101-01-01"))).rejects.toThrow(/2100-12-31/);
    await expect(service.createSeries(input("2026-12-25"))).resolves.toMatchObject({ created: true });
  });

  it("refuses alerts further ahead than occurrences are listed, except for yearly series", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new NoopNotifier(), () => Date.parse("2026-10-08T17:00:00Z"));
    const weekly: SeriesInput = { title: "Trash", timeZone: "America/Denver", rule: { freq: "weekly", startDate: "2026-10-09", time: "08:00" } };
    await expect(service.createSeries({ ...weekly, reminderOffsetsMinutes: [90 * 1440] })).rejects.toThrow(/60 days/);
    const ok = await service.createSeries({ ...weekly, reminderOffsetsMinutes: [60 * 1440] });
    await expect(service.updateSeries(ok.series.id, { reminderOffsetsMinutes: [61 * 1440] })).rejects.toThrow(/60 days/);
    await expect(service.createSeries({ title: "Car registration", timeZone: "America/Denver", reminderOffsetsMinutes: [90 * 1440], rule: { freq: "yearly", startDate: "2027-03-01", time: "09:00" } }))
      .resolves.toMatchObject({ created: true });
  });

  it("a whole-series change of the first date of a split-born series keeps notBefore", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new NoopNotifier(), () => Date.parse("2026-10-08T17:00:00Z"));
    const { series } = await service.createSeries({ title: "Trash", timeZone: "America/Denver", rule: { freq: "weekly", weekdays: [5], startDate: "2026-10-09", time: "08:00" } });
    const split = await service.updateSeries(series.id, { from: "2026-10-23", title: "Trash (new hauler)" });
    expect(split!.series.rule.notBefore).toBe("2026-10-23");
    const moved = await service.updateSeries(split!.series.id, { rule: { startDate: "2026-10-02" } });
    expect(moved!.series.rule.notBefore).toBe("2026-10-23");
    const dates = (await service.list(false)).filter((e) => e.seriesId === split!.series.id).map((e) => e.occurrenceDate);
    expect(dates.every((date) => date! >= "2026-10-23")).toBe(true);
    // Moving the first date past notBefore makes it redundant.
    const later = await service.updateSeries(split!.series.id, { rule: { startDate: "2026-10-30" } });
    expect(later!.series.rule.notBefore).toBeUndefined();
  });
});

describe("partial failures", () => {
  it("createSeries answers success once the series is saved, even if writing occurrences fails", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier(), () => Date.parse("2026-10-08T17:00:00Z"));
    const mutate = store.mutateEvent.bind(store);
    let calls = 0;
    vi.spyOn(store, "mutateEvent").mockImplementation(async (id, fn) => {
      calls += 1;
      if (calls === 2) throw new Error("write failed");
      return mutate(id, fn);
    });
    const created = await service.createSeries({ title: "Trash", timeZone: "America/Denver", rule: { freq: "weekly", weekdays: [5], startDate: "2026-10-09", time: "08:00" } });
    expect(created.created).toBe(true);
    expect(await store.getSeries(created.series.id)).not.toBeNull();
  });

  it("one series that fails to expand does not stop the others", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const store = new MemoryCalendarStore();
    let now = Date.parse("2026-10-08T17:00:00Z");
    const service = new CalendarService(store, new NoopNotifier(), () => now);
    const a = await service.createSeries({ title: "A", timeZone: "America/Denver", rule: { freq: "weekly", weekdays: [5], startDate: "2026-10-09", time: "08:00" } });
    const b = await service.createSeries({ title: "B", timeZone: "America/Denver", rule: { freq: "weekly", weekdays: [3], startDate: "2026-10-14", time: "08:00" } });
    now = Date.parse("2026-10-29T17:00:00Z");
    // Series A cannot be expanded (A sorts first, so it runs first); B still gets its new dates.
    const getSeries = store.getSeries.bind(store);
    vi.spyOn(store, "getSeries").mockImplementation(async (id) => {
      if (id === a.series.id) throw new Error("bad series");
      return getSeries(id);
    });
    const result = await service.expandAllSeries();
    expect(result.failed).toBe(1);
    const bDates = (await service.list(false)).filter((e) => e.seriesId === b.series.id).map((e) => e.occurrenceDate!);
    expect(bDates.at(-1)! > "2026-12-20").toBe(true);
  });
});

describe("Canvas fields removed in Canvas", () => {
  it("clears lock and unlock dates and details once Canvas no longer has them", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier());
    let detail: Record<string, unknown> = {
      id: 42, name: "Essay", description: "<p>Write it.</p>", due_at: "2026-11-10T23:59:00-07:00",
      lock_at: "2026-11-12T23:59:00-07:00", unlock_at: "2026-10-01T00:00:00-06:00", submission_types: ["online_upload"],
    };
    let title = "Essay";
    const fetcher: typeof fetch = async (input) => {
      const url = String(input);
      const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
      if (url.includes("/api/v1/courses?")) return json([]);
      if (url.endsWith("/assignments/42")) return json(detail);
      if (url.includes("incomplete_items")) return json([{ course_id: 1, plannable_id: 42, plannable_type: "assignment", plannable_date: "2026-11-10T23:59:00-07:00", plannable: { id: 42, title, due_at: "2026-11-10T23:59:00-07:00" } }]);
      return json([]);
    };
    const sync = new CanvasSyncService(service, fetcher);
    const config = { baseUrl: "https://school.example.test", accessToken: "t" };
    await sync.sync(config, new Date("2026-10-28T18:00:00Z"));
    let event = (await store.listEvents(true))[0];
    expect(event).toMatchObject({ lockAt: "2026-11-13T06:59:00.000Z", unlockAt: "2026-10-01T06:00:00.000Z" });
    expect(event.details).toContain("Write it.");

    // Canvas drops the dates and the description; the title change makes the sync fetch details again.
    detail = { id: 42, name: "Essay (revised)", due_at: "2026-11-10T23:59:00-07:00", submission_types: ["online_upload"] };
    title = "Essay (revised)";
    await sync.sync(config, new Date("2026-10-28T18:30:00Z"));
    event = (await store.listEvents(true))[0];
    expect(event.lockAt).toBeUndefined();
    expect(event.unlockAt).toBeUndefined();
    expect(event.details ?? "").toBe("");

    // Without a fresh detail fetch nothing is cleared.
    detail = { id: 42, name: "Essay (revised)", due_at: "2026-11-10T23:59:00-07:00", lock_at: "2026-11-12T23:59:00-07:00" };
    title = "Essay (rev. 2)";
    await sync.sync(config, new Date("2026-10-28T19:00:00Z"));
    const fetchFails: typeof fetch = async (input) => (String(input).includes("/assignments/") ? new Response("no", { status: 500 }) : fetcher(input));
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    title = "Essay (rev. 3)";
    await new CanvasSyncService(service, fetchFails).sync(config, new Date("2026-10-28T19:30:00Z"));
    event = (await store.listEvents(true))[0] as ReminderEvent;
    expect(event.lockAt).toBe("2026-11-13T06:59:00.000Z");
  });
});
