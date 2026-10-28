import request from "supertest";
import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createApp } from "../src/app.js";
import { CanvasSyncService } from "../src/canvas.js";
import { NoopNotifier } from "../src/notifier.js";
import { OutlookService } from "../src/outlook.js";
import { CalendarService } from "../src/service.js";
import { MemoryCalendarStore } from "../src/store.js";
import * as security from "../src/security.js";
import type { SeriesInput } from "../src/types.js";

/**
 * The medium findings of handoff/AUDIT-2026-10-26.md, section 4 (fixed 2026-10-28). Each block
 * failed on the code before its fix.
 */
const BASE = "https://calendar.example.test/api";
const PAIRING = "654321";
const STRONG_PAIRING = "S".repeat(43);

function testApp(store = new MemoryCalendarStore(), extra: Record<string, unknown> = {}) {
  return createApp(store, new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: PAIRING,
    firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
    publicBaseUrl: BASE,
    ...extra,
  });
}

async function mcpCall(app: ReturnType<typeof testApp>, name: string, args: unknown, token = "test-api-token") {
  const res = await request(app).post("/mcp")
    .set("Authorization", `Bearer ${token}`)
    .set("Accept", "application/json, text/event-stream")
    .send({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } });
  const line = res.text.split("\n").find((l) => l.startsWith("data: "));
  return { status: res.status, body: line || res.text.startsWith("{") ? JSON.parse(line ? line.slice(6) : res.text) : null };
}

async function pairDevice(app: ReturnType<typeof testApp>, installationId = "fid-1", code = PAIRING) {
  const res = await request(app).post("/v1/devices/register").send({ pairingCode: code, installationId, deviceName: "Pixel" }).expect(201);
  return `Bearer ${res.body.deviceToken}`;
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------------------------
describe("M1: outlook_send_mail limits and send log", () => {
  function mailFetch(sent: string[]) {
    return (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/oauth2/v2.0/token")) return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }), { status: 200 });
      if (url.endsWith("/me/sendMail") || url.includes("/reply")) { sent.push(String(init?.body)); return new Response(null, { status: 202 }); }
      return new Response(JSON.stringify({ error: { code: "NotFound" } }), { status: 404 });
    }) as typeof fetch;
  }

  async function mailApp(sent: string[]) {
    const store = new MemoryCalendarStore();
    await store.putKv("outlookAccount", { refreshToken: "rt", email: "owner@example.com", connectedAt: "", updatedAt: "" });
    return { store, app: testApp(store, { outlook: { clientId: "client-1", clientSecret: "secret-1" }, fetcher: mailFetch(sent) }) };
  }

  it("sends at most 20 mails per Denver day, then refuses until the next day", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-28T18:00:00Z"));
    const sent: string[] = [];
    const { app } = await mailApp(sent);
    for (let i = 0; i < 20; i += 1) {
      const ok = await mcpCall(app, "outlook_send_mail", { to: ["office@example.edu"], subject: `Q${i}`, body: "What time?" });
      expect(ok.body.result.isError).toBeFalsy();
    }
    const refused = await mcpCall(app, "outlook_send_mail", { to: ["office@example.edu"], subject: "Q21", body: "What time?" });
    expect(refused.body.result.isError).toBe(true);
    expect(refused.body.result.content[0].text).toMatch(/20 .*per day/i);
    expect(sent).toHaveLength(20);
    vi.setSystemTime(new Date("2026-10-29T18:00:00Z"));
    const nextDay = await mcpCall(app, "outlook_send_mail", { to: ["office@example.edu"], subject: "Q22", body: "What time?" });
    expect(nextDay.body.result.isError).toBeFalsy();
    expect(sent).toHaveLength(21);
  });

  it("refuses more than 10 recipients in total, before anything is sent", async () => {
    const sent: string[] = [];
    const store = new MemoryCalendarStore();
    await store.putKv("outlookAccount", { refreshToken: "rt", email: "owner@example.com", connectedAt: "", updatedAt: "" });
    const outlook = new OutlookService(store, { clientId: "c", clientSecret: "s" }, BASE, PAIRING, mailFetch(sent));
    const addresses = (n: number, domain: string) => Array.from({ length: n }, (_, i) => `p${i}@${domain}`);
    await expect(outlook.sendMail({ to: addresses(6, "a.example"), cc: addresses(5, "b.example"), subject: "Hi", body: "x" })).rejects.toThrow(/10 recipients/);
    expect(sent).toHaveLength(0);
    await expect(outlook.sendMail({ to: addresses(5, "a.example"), cc: addresses(5, "b.example"), subject: "Hi", body: "x" })).resolves.toMatchObject({ sent: true });
  });

  it("logs each send with metadata only: time, mode, recipient count, domains, subject length", async () => {
    const sent: string[] = [];
    const { app, store } = await mailApp(sent);
    const subject = "Private subject line";
    const body = "The secret body text";
    await mcpCall(app, "outlook_send_mail", { to: ["office@Example.EDU"], cc: ["someone@sub.example.com"], subject, body });
    const log = await store.getKv("outlookSendLog") as { entries: Array<Record<string, unknown>> };
    expect(log.entries).toHaveLength(1);
    expect(log.entries[0]).toMatchObject({ mode: "new", outcome: "sent", recipients: 2, domains: ["example.edu", "sub.example.com"], subjectLength: subject.length });
    expect(typeof log.entries[0].at).toBe("string");
    const raw = JSON.stringify(log);
    for (const secret of [subject, body, "office", "someone"]) expect(raw).not.toContain(secret);
  });

  it("does not count a send that Microsoft refused", async () => {
    const store = new MemoryCalendarStore();
    await store.putKv("outlookAccount", { refreshToken: "rt", email: "owner@example.com", connectedAt: "", updatedAt: "" });
    const failing = (async (input: RequestInfo | URL) => String(input).endsWith("/token")
      ? new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }), { status: 200 })
      : new Response(JSON.stringify({ error: { code: "ErrorSendAsDenied" } }), { status: 403 })) as typeof fetch;
    const outlook = new OutlookService(store, { clientId: "c", clientSecret: "s", dailySendLimit: 1 }, BASE, PAIRING, failing);
    await expect(outlook.sendMail({ to: ["a@x.example"], subject: "Hi", body: "x" })).rejects.toThrow(/403/);
    const ok = new OutlookService(store, { clientId: "c", clientSecret: "s", dailySendLimit: 1 }, BASE, PAIRING, mailFetch([]));
    await expect(ok.sendMail({ to: ["a@x.example"], subject: "Hi", body: "x" })).resolves.toMatchObject({ sent: true });
    const log = await store.getKv("outlookSendLog") as { entries: Array<{ outcome: string }> };
    expect(log.entries.map((entry) => entry.outcome)).toEqual(["failed", "sent"]);
  });
});

// ---------------------------------------------------------------------------------------------
describe("M2: client registration and the consent page", () => {
  const challenge = createHash("sha256").update("v".repeat(50)).digest("base64url");
  const authorizeQuery = (clientId: string, redirectUri: string) => ({
    response_type: "code", client_id: clientId, redirect_uri: redirectUri, code_challenge: challenge, code_challenge_method: "S256",
  });

  it("names a client without client_name after its redirect host, never ChatGPT", async () => {
    const app = testApp();
    const reg = await request(app).post("/oauth/register").send({ redirect_uris: ["https://claude.ai/api/mcp/auth_callback"] }).expect(201);
    expect(reg.body.client_name).toBe("claude.ai");
    const page = await request(app).get("/oauth/authorize").query(authorizeQuery(reg.body.client_id, "https://claude.ai/api/mcp/auth_callback")).expect(200);
    expect(page.text).not.toContain("ChatGPT");
  });

  it("shows the full redirect URI on the consent page, escaped", async () => {
    const app = testApp();
    const redirectUri = "https://look-alike.example/cb/path?next=\"><b>hi</b>";
    const reg = await request(app).post("/oauth/register").send({ client_name: "Claude", redirect_uris: [redirectUri] }).expect(201);
    const page = await request(app).get("/oauth/authorize").query(authorizeQuery(reg.body.client_id, redirectUri)).expect(200);
    // Shown to the person, not only carried in a hidden field.
    expect(page.text).toContain("Returns to: <code>https://look-alike.example/cb/path?next=&quot;&gt;&lt;b&gt;hi&lt;/b&gt;</code>");
    expect(page.text).not.toContain("<b>hi</b>");
  });

  it("caps redirect URIs at 5 of at most 2000 characters, and the client name at 120", async () => {
    const app = testApp();
    const uri = (n: number) => `https://claude.ai/cb/${n}`;
    await request(app).post("/oauth/register").send({ redirect_uris: [1, 2, 3, 4, 5, 6].map(uri) }).expect(400);
    await request(app).post("/oauth/register").send({ redirect_uris: [`https://claude.ai/${"x".repeat(2000)}`] }).expect(400);
    const ok = await request(app).post("/oauth/register").send({ client_name: `  ${"N".repeat(500)}\u0007`, redirect_uris: [1, 2, 3, 4, 5].map(uri) }).expect(201);
    expect(ok.body.client_name).toBe("N".repeat(120));
  });
});

// ---------------------------------------------------------------------------------------------
describe("M3: refresh-token reuse and revocation", () => {
  const redirectUri = "https://claude.ai/api/mcp/auth_callback";
  async function connect(app: ReturnType<typeof testApp>) {
    const reg = await request(app).post("/oauth/register").send({ client_name: "Claude", redirect_uris: [redirectUri] }).expect(201);
    const verifier = "v".repeat(50);
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    const auth = await request(app).post("/oauth/authorize").type("form").send({
      response_type: "code", client_id: reg.body.client_id, redirect_uri: redirectUri, code_challenge: challenge, code_challenge_method: "S256", pairing_code: PAIRING,
    }).expect(303);
    const code = new URL(auth.headers.location).searchParams.get("code")!;
    const tok = await request(app).post("/oauth/token").type("form").send({
      grant_type: "authorization_code", code, code_verifier: verifier, client_id: reg.body.client_id, redirect_uri: redirectUri,
    }).expect(200);
    return { clientId: reg.body.client_id as string, tokens: tok.body as { access_token: string; refresh_token: string } };
  }
  const refresh = (app: ReturnType<typeof testApp>, clientId: string, refreshToken: string) =>
    request(app).post("/oauth/token").type("form").send({ grant_type: "refresh_token", refresh_token: refreshToken, client_id: clientId });
  const toolsList = (app: ReturnType<typeof testApp>, token: string) => request(app).post("/mcp")
    .set("Authorization", `Bearer ${token}`).set("Accept", "application/json, text/event-stream")
    .send({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} });

  it("a refresh token used again after the grace window ends the whole grant", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const app = testApp();
    const { clientId, tokens } = await connect(app);
    const next = await refresh(app, clientId, tokens.refresh_token).expect(200);
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.now() + 3 * 60 * 1000);
    // Someone replays the old token: refused, and the chain it started is revoked.
    await refresh(app, clientId, tokens.refresh_token).expect(400);
    await refresh(app, clientId, next.body.refresh_token).expect(400);
    await toolsList(app, next.body.access_token).expect(401);
  });

  it("a second, independent connection keeps working when another grant is revoked", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const app = testApp();
    const first = await connect(app);
    const second = await connect(app);
    await refresh(app, first.clientId, first.tokens.refresh_token).expect(200);
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.now() + 3 * 60 * 1000);
    await refresh(app, first.clientId, first.tokens.refresh_token).expect(400);
    await toolsList(app, second.tokens.access_token).expect(200);
    await refresh(app, second.clientId, second.tokens.refresh_token).expect(200);
  });

  it("tokens issued before grant ids existed keep working and join a grant at their next refresh", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const reg = await request(app).post("/oauth/register").send({ client_name: "Claude", redirect_uris: [redirectUri] }).expect(201);
    const hash = (value: string) => createHash("sha256").update(value, "utf8").digest("hex");
    // Old-shape records: no grantId, no revokedAt.
    await store.putOAuthToken({ tokenHash: hash("legacy-access"), kind: "access", clientId: reg.body.client_id, resource: `${BASE}/mcp`, scope: "reminders:read reminders:write", expiresAt: Date.now() + 3_600_000 });
    await store.putOAuthToken({ tokenHash: hash("legacy-refresh"), kind: "refresh", clientId: reg.body.client_id, resource: `${BASE}/mcp`, scope: "reminders:read reminders:write", expiresAt: Date.now() + 80 * 86_400_000 });
    await toolsList(app, "legacy-access").expect(200);
    const renewed = await refresh(app, reg.body.client_id, "legacy-refresh").expect(200);
    await toolsList(app, renewed.body.access_token).expect(200);
    const grantId = (await store.getOAuthToken(hash(renewed.body.refresh_token)))?.grantId;
    expect(grantId).toBeTruthy();
    expect((await store.getOAuthToken(hash("legacy-refresh")))?.grantId).toBe(grantId);
  });

  it("the admin revoke route is a dry run unless told to apply, and takes only the static API token", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const device = await pairDevice(app);
    const { clientId, tokens } = await connect(app);
    const admin = (body: object, token = "test-api-token") => request(app).post("/admin/revoke-tokens").set("Authorization", `Bearer ${token}`).send(body);

    await admin({ oauth: true }, tokens.access_token).expect(401);
    await request(app).post("/admin/revoke-tokens").send({ oauth: true }).expect(401);
    await admin({}).expect(400);

    const dry = await admin({ oauth: true, devices: true }).expect(200);
    expect(dry.body).toMatchObject({ dryRun: true, oauth: { liveRefreshTokens: 1, liveAccessTokens: 1, revoked: 0 }, devices: { count: 1, revoked: 0 } });
    expect(dry.body.oauth.clients[0]).toMatchObject({ clientId, clientName: "Claude" });
    expect(dry.body.devices.list[0]).toMatchObject({ deviceName: "Pixel" });
    expect(JSON.stringify(dry.body)).not.toMatch(/authHash|tokenHash/);
    await toolsList(app, tokens.access_token).expect(200);

    const applied = await admin({ oauth: true, apply: true }).expect(200);
    expect(applied.body).toMatchObject({ dryRun: false, oauth: { revoked: 2 } });
    await toolsList(app, tokens.access_token).expect(401);
    await refresh(app, clientId, tokens.refresh_token).expect(400);
    await request(app).get("/v1/sync").set("Authorization", device).expect(200);

    const [record] = await store.listDevices();
    await admin({ devices: true, deviceIds: [record.id], apply: true }).expect(200);
    await request(app).get("/v1/sync").set("Authorization", device).expect(401);
    // Pairing again works and replaces the revoked token.
    const again = await pairDevice(app);
    await request(app).get("/v1/sync").set("Authorization", again).expect(200);
    // Reminders are untouched by any of it.
    expect((await store.listDevices())).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------------------------
describe("M4: an idempotency key does not bring back what the user deleted", () => {
  const input = { title: "Dentist", startAt: "2026-11-01T09:00:00-06:00", timeZone: "America/Denver", idempotencyKey: "dentist-2026-11-01" };

  it("create_reminder answers deleted instead of re-creating", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const created = await mcpCall(app, "create_reminder", input);
    const id = created.body.result.structuredContent.event.id;
    await mcpCall(app, "delete_reminder", { id });
    const again = await mcpCall(app, "create_reminder", input);
    expect(again.body.result.isError).toBeFalsy();
    expect(again.body.result.structuredContent).toMatchObject({ id, created: false, deleted: true });
    expect(again.body.result.content[0].text).toMatch(/deleted/i);
    expect((await store.getEvent(id))?.deletedAt).toBeTruthy();
    expect((await mcpCall(app, "list_reminders", { includeDone: true })).body.result.structuredContent.events).toEqual([]);
  });

  it("an event made before deterministic ids, found by its key, is not re-created either", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier());
    const now = new Date().toISOString();
    await store.putEvent({
      id: "0b8e8f40-1111-4222-8333-944455556666", title: "", startAt: "2026-11-01T15:00:00.000Z", timeZone: "America/Denver", location: "", notes: "",
      critical: false, reminderOffsetsMinutes: [], done: true, domain: "life", category: "automatic", source: "chatgpt", revision: 1,
      createdAt: now, updatedAt: now, deletedAt: now, idempotencyKey: input.idempotencyKey,
    });
    const again = await service.create(input);
    expect(again).toMatchObject({ created: false, deleted: true });
    expect(await service.list(true)).toEqual([]);
  });

  it("create_series with the key of a deleted series answers deleted; the phone gets 409", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const series = {
      title: "Water plants", timeZone: "America/Denver",
      rule: { freq: "daily", interval: 3, startDate: "2099-01-01", time: "18:00" }, idempotencyKey: "series-key-0001",
    };
    const created = await mcpCall(app, "create_series", series);
    const id = created.body.result.structuredContent.series.id;
    await mcpCall(app, "delete_series", { id });
    const again = await mcpCall(app, "create_series", series);
    expect(again.body.result.isError).toBeFalsy();
    expect(again.body.result.structuredContent).toMatchObject({ created: false, deleted: true, occurrences: [] });
    expect((await mcpCall(app, "list_series", {})).body.result.structuredContent.series).toEqual([]);
    const auth = await pairDevice(app);
    const phone = await request(app).post("/v1/series").set("Authorization", auth).send(series);
    expect(phone.status).toBe(409);
    expect((await store.getSeries(id))?.deletedAt).toBeTruthy();
  });
});

// ---------------------------------------------------------------------------------------------
describe("M5: Canvas items that disappear from Canvas", () => {
  const config = { baseUrl: "https://school.example.test", accessToken: "t" };
  const now = new Date("2026-10-28T18:00:00Z");
  const item = (id: number, due = "2026-11-10T23:59:00-07:00") => ({
    course_id: 1, plannable_id: id, plannable_type: "assignment", plannable_date: due, context_name: "ETH 110",
    plannable: { id, title: `Essay ${id}`, due_at: due },
  });
  function canvas(state: { active: unknown[]; complete: unknown[]; failComplete?: boolean }) {
    return (async (input: RequestInfo | URL) => {
      const url = String(input);
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
      if (url.includes("/api/v1/courses?")) return json([]);
      if (url.includes("/assignments/")) return json({ errors: [] }, 404);
      if (url.includes("filter=complete_items")) return state.failComplete ? json({ errors: [] }, 503) : json(state.complete);
      if (url.includes("filter=incomplete_items")) return json(state.active);
      return json([], 404);
    }) as typeof fetch;
  }
  function setup(active: unknown[]) {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.spyOn(console, "log").mockImplementation(() => undefined);
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier(), () => now.getTime());
    const state = { active, complete: [] as unknown[], failComplete: false };
    return { store, service, state, sync: () => new CanvasSyncService(service, canvas(state)).sync(config, now) };
  }
  const openTitles = async (service: CalendarService) => (await service.list(false)).filter((e) => e.source === "canvas").map((e) => e.title).sort();

  it("marks done (never deletes) an open item both Canvas lists no longer show, and reopens it if it comes back", async () => {
    const { service, state, sync, store } = setup([item(1), item(2)]);
    await sync();
    state.active = [item(1)];
    const result = await sync();
    expect(result.retired).toBe(1);
    expect(await openTitles(service)).toEqual(["Essay 1"]);
    const gone = (await store.listEvents(true)).find((e) => e.title === "Essay 2")!;
    expect(gone).toMatchObject({ done: true });
    expect(gone.retiredAt).toBeTruthy();
    expect(gone.deletedAt).toBeUndefined();
    state.active = [item(1), item(2)];
    await sync();
    expect(await openTitles(service)).toEqual(["Essay 1", "Essay 2"]);
    expect((await store.listEvents(true)).find((e) => e.title === "Essay 2")?.retiredAt).toBeUndefined();
  });

  it("retires nothing when a Canvas fetch fails", async () => {
    const { service, state, sync } = setup([item(1), item(2)]);
    await sync();
    state.active = [];
    state.failComplete = true;
    await expect(sync()).rejects.toThrow();
    expect(await openTitles(service)).toEqual(["Essay 1", "Essay 2"]);
  });

  it("retires nothing when more than 10 would go at once (a partial Canvas answer), and says so", async () => {
    const warn = vi.fn();
    const { service, state, sync } = setup(Array.from({ length: 12 }, (_, i) => item(i + 1)));
    vi.spyOn(console, "warn").mockImplementation(warn);
    await sync();
    state.active = [item(1)];
    const result = await sync();
    expect(result.retired).toBe(0);
    expect(await openTitles(service)).toHaveLength(12);
    expect(JSON.stringify(warn.mock.calls)).toMatch(/11/);
  });

  it("leaves items outside the fetched window, items the user reopened, and other sources alone", async () => {
    const { service, state, sync, store } = setup([item(1), item(2), item(3, "2027-09-01T23:59:00-06:00")]);
    await sync();
    const planner = await service.create({ title: "Dentist", startAt: "2026-11-05T09:00:00-07:00", timeZone: "America/Denver" });
    state.active = [item(1), item(2)];
    await sync(); // item 3 is past the 6-month window: Canvas would not list it anyway
    expect(await openTitles(service)).toEqual(["Essay 1", "Essay 2", "Essay 3"]);
    state.active = [item(1)];
    await sync();
    const essay2 = (await store.listEvents(true)).find((e) => e.title === "Essay 2")!;
    await service.update(essay2.id, { done: false }); // the user disagrees
    await sync();
    expect(await openTitles(service)).toEqual(["Essay 1", "Essay 2", "Essay 3"]);
    expect((await service.get(planner.event.id))?.done).toBe(false);
  });
});

// ---------------------------------------------------------------------------------------------
describe("M6: series and task operations read only their own reminders", () => {
  const trash: SeriesInput = {
    title: "Trash pickup", timeZone: "America/Denver", reminderOffsetsMinutes: [900],
    rule: { freq: "weekly", weekdays: [5], startDate: "2026-10-09", time: "08:00" },
  };

  it("never reads the whole events collection for series changes, the scheduled expansion or Canvas", async () => {
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier(), () => Date.parse("2026-10-08T17:00:00Z"));
    await service.create({ title: "Unrelated", startAt: "2026-10-20T09:00:00-06:00", timeZone: "America/Denver" });
    const whole = vi.spyOn(store, "listEvents");
    const { series } = await service.createSeries(trash);
    await service.updateSeries(series.id, { title: "Trash and recycling" });
    await service.updateSeries(series.id, { from: "2026-10-23", rule: { time: "07:00" } });
    await service.expandAllSeries();
    await service.setTaskItemsDone("canvas", new Set(["assignment:1"]));
    const empty: typeof fetch = async () => new Response("[]", { status: 200, headers: { "content-type": "application/json" } });
    await new CanvasSyncService(service, empty).sync({ baseUrl: "https://school.example.test", accessToken: "t" }, new Date("2026-10-08T17:00:00Z"));
    await service.deleteSeries(series.id);
    expect(whole).not.toHaveBeenCalled();
    // Same answers as before: the unrelated reminder is untouched, the series' future ones are gone.
    expect((await service.list(false)).map((e) => e.title)).toContain("Unrelated");
  });
});

// ---------------------------------------------------------------------------------------------
describe("M7: moving a series' time earlier", () => {
  it("removes today's occurrence that still sits at the old, later time", async () => {
    // Thursday October 8, 2026, 1:00 PM in Denver.
    const store = new MemoryCalendarStore();
    const service = new CalendarService(store, new NoopNotifier(), () => Date.parse("2026-10-08T19:00:00Z"));
    const { series } = await service.createSeries({
      title: "Evening walk", timeZone: "America/Denver", rule: { freq: "daily", startDate: "2026-10-08", time: "18:00" },
    });
    expect((await service.list(false)).some((e) => e.occurrenceDate === "2026-10-08")).toBe(true);
    await service.updateSeries(series.id, { rule: { time: "08:00" } });
    const listed = (await service.list(false)).filter((e) => e.seriesId === series.id);
    expect(listed.map((e) => e.occurrenceDate)).not.toContain("2026-10-08");
    expect(listed[0]).toMatchObject({ occurrenceDate: "2026-10-09", startAt: "2026-10-09T14:00:00.000Z" });
  });
});

// ---------------------------------------------------------------------------------------------
describe("M9: pairing code strength and a global failure limit", () => {
  it("warns at startup about a code under 32 characters, without printing it", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    security.warnIfWeakPairingCode("hunter2");
    expect(warn).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(warn.mock.calls)).not.toContain("hunter2");
    warn.mockClear();
    security.warnIfWeakPairingCode(STRONG_PAIRING);
    security.warnIfWeakPairingCode("");
    expect(warn).not.toHaveBeenCalled();
  });

  it("with a weak code, 50 failures in an hour from anywhere stop pairing for the rest of the hour", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const app = testApp();
    for (let i = 0; i < 50; i += 1) {
      await request(app).post("/v1/bootstrap").set("X-Forwarded-For", `198.51.100.${i}`).send({ pairingCode: `bad-${i}` }).expect(401);
    }
    const blocked = await request(app).post("/v1/bootstrap").set("X-Forwarded-For", "203.0.113.200").send({ pairingCode: PAIRING });
    expect(blocked.status).toBe(429);
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(Date.now() + 61 * 60 * 1000);
    await request(app).post("/v1/bootstrap").set("X-Forwarded-For", "203.0.113.201").send({ pairingCode: PAIRING }).expect(200);
  });

  it("with a strong code the global limit never engages, so failures elsewhere cannot lock the owner out", async () => {
    const app = testApp(new MemoryCalendarStore(), { pairingCode: STRONG_PAIRING });
    for (let i = 0; i < 60; i += 1) {
      await request(app).post("/v1/bootstrap").set("X-Forwarded-For", `198.51.100.${i}`).send({ pairingCode: `bad-${i}` }).expect(401);
    }
    await request(app).post("/v1/bootstrap").set("X-Forwarded-For", "203.0.113.200").send({ pairingCode: STRONG_PAIRING }).expect(200);
    await pairDevice(app, "fid-strong", STRONG_PAIRING);
  });
});
