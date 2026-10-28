import request from "supertest";
import { afterAll, describe, expect, it } from "vitest";
import { deleteApp, initializeApp, type App } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { createApp } from "../../src/app.js";
import { NoopNotifier } from "../../src/notifier.js";
import { OutlookService } from "../../src/outlook.js";
import { KvPairingFailureCounter } from "../../src/security.js";
import { CalendarService } from "../../src/service.js";
import { FirestoreCalendarStore } from "../../src/store.js";

/**
 * The parts of the 2026-10-28 audit fixes that only Firestore can show: equality queries, token
 * rotation and revocation fields, transactions under contention, and two planner keys that share a
 * document id. Run with scripts/test-emulator.ps1 (demo project; it cannot reach the real one).
 */
const emulator = process.env.FIRESTORE_EMULATOR_HOST;

describe.skipIf(!emulator)("audit fixes against Firestore (emulator)", () => {
  const projectId = `demo-audit-${Date.now()}`;
  const firebase: App | undefined = emulator ? initializeApp({ projectId }, projectId) : undefined;
  const db = () => getFirestore(firebase!);

  afterAll(async () => {
    if (firebase) await deleteApp(firebase);
  });

  it("lists a series' and a task's reminders with equality queries", async () => {
    const store = new FirestoreCalendarStore(db());
    const service = new CalendarService(store, new NoopNotifier(), () => Date.parse("2026-10-08T17:00:00Z"));
    const { series } = await service.createSeries({ title: "TEST trash", timeZone: "America/Denver", rule: { freq: "weekly", weekdays: [5], startDate: "2026-10-09", time: "08:00" } });
    await service.publishTaskReminders({ taskKey: "canvas", reminders: [{ itemKey: "assignment:1", title: "TEST essay", startAt: "2026-10-20T23:59:00-06:00", timeZone: "America/Denver" }] });
    await service.create({ title: "TEST other", startAt: "2026-10-21T09:00:00-06:00", timeZone: "America/Denver" });
    const bySeries = await store.listEventsBySeries(series.id);
    expect(bySeries.length).toBeGreaterThan(2);
    expect(bySeries.every((event) => event.seriesId === series.id)).toBe(true);
    expect(bySeries.map((event) => event.startAt)).toEqual([...bySeries.map((event) => event.startAt)].sort());
    expect((await store.listEventsByTaskKey("canvas")).map((event) => event.title)).toEqual(["TEST essay"]);
  });

  it("keeps a rotated refresh token for reuse detection, joins legacy tokens to a grant, and revokes by grant", async () => {
    const store = new FirestoreCalendarStore(db());
    const expiresAt = Date.now() + 80 * 86_400_000;
    await store.putOAuthToken({ tokenHash: "legacy", kind: "refresh", clientId: "c", resource: "r", scope: "s", expiresAt });
    const rotated = await store.rotateOAuthRefreshToken("legacy", 60_000, { grantId: "g-1", keepMs: 30 * 86_400_000 });
    expect(rotated?.grantId).toBe("g-1");
    const raw = (await db().collection("oauthTokens").doc("legacy").get()).data()!;
    expect(raw.grantId).toBe("g-1");
    expect(raw.expiresAt).toBeLessThanOrEqual(Date.now() + 60_000);
    // The record stays (TTL) well past the grace window.
    expect((raw.expireAt as Timestamp).toMillis()).toBeGreaterThan(Date.now() + 29 * 86_400_000);
    // A second rotation keeps the grant it already has.
    expect((await store.rotateOAuthRefreshToken("legacy", 60_000, { grantId: "g-2" }))?.grantId).toBe("g-1");

    await store.putOAuthToken({ tokenHash: "a1", kind: "access", clientId: "c", resource: "r", scope: "s", expiresAt: Date.now() + 3_600_000, grantId: "g-1" });
    await store.putOAuthToken({ tokenHash: "other", kind: "access", clientId: "c", resource: "r", scope: "s", expiresAt: Date.now() + 3_600_000, grantId: "g-9" });
    expect((await store.listOAuthTokens("g-1")).map((token) => token.tokenHash).sort()).toEqual(["a1", "legacy"]);
    expect(await store.revokeOAuthTokens(["a1", "missing"], Date.now())).toBe(1);
    const revoked = await store.getOAuthToken("a1");
    expect(revoked?.revokedAt).toBeTypeOf("number");
    expect(revoked!.expiresAt).toBeLessThanOrEqual(Date.now());
    expect((await store.getOAuthToken("other"))?.revokedAt).toBeUndefined();
  });

  it("counts mail sends in a transaction: concurrent sends never pass the daily limit", async () => {
    const store = new FirestoreCalendarStore(db());
    await store.putKv("outlookAccount", { refreshToken: "rt", email: "user@example.com", connectedAt: "", updatedAt: "" });
    let sent = 0;
    const fetcher = (async (input: RequestInfo | URL) => {
      if (String(input).endsWith("/token")) return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }), { status: 200 });
      sent += 1;
      return new Response(null, { status: 202 });
    }) as typeof fetch;
    const outlook = new OutlookService(store, { clientId: "c", clientSecret: "s", dailySendLimit: 3 }, "https://calendar.example.test/api", "x".repeat(40), fetcher);
    const results = await Promise.allSettled(Array.from({ length: 6 }, (_, i) => outlook.sendMail({ to: [`p${i}@example.com`], subject: "TEST", body: "x" })));
    expect(results.filter((r) => r.status === "fulfilled")).toHaveLength(3);
    expect(sent).toBe(3);
    const log = await store.getKv("outlookSendLog") as { entries: Array<{ outcome: string; domains: string[] }> };
    expect(log.entries.filter((entry) => entry.outcome === "sent")).toHaveLength(3);
    expect(log.entries.every((entry) => JSON.stringify(entry).indexOf("p0@") === -1)).toBe(true);
  });

  it("counts failed pairing attempts across concurrent requests", async () => {
    const store = new FirestoreCalendarStore(db());
    const counter = new KvPairingFailureCounter(store, 5, 3_600_000, "pairingFailures-test");
    await Promise.all(Array.from({ length: 5 }, () => counter.record()));
    expect(await counter.retryAfter()).toBeGreaterThan(0);
  });

  it("refuses a planner key whose document id another key already uses", async () => {
    const store = new FirestoreCalendarStore(db());
    const app = createApp(store, new NoopNotifier(), {
      apiToken: "test-api-token",
      pairingCode: "x".repeat(40),
      firebaseClientConfig: { projectId, appId: "a", apiKey: "k", senderId: "s" },
      publicBaseUrl: "https://calendar.example.test/api",
    });
    const put = async (key: string, content: string) => {
      const res = await request(app).post("/mcp").set("Authorization", "Bearer test-api-token").set("Accept", "application/json, text/event-stream")
        .send({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "planner_state_put", arguments: { key, content } } });
      const line = res.text.split("\n").find((l) => l.startsWith("data: "))!;
      return JSON.parse(line.slice(6)).result as { isError?: boolean };
    };
    expect((await put("it-clash.json", "first")).isError).toBeFalsy();
    // Same document (integrations/planner_it-clash_json), different key.
    expect((await put("it-clash_json", "second")).isError).toBe(true);
    expect((await db().collection("integrations").doc("planner_it-clash_json").get()).data()).toMatchObject({ key: "it-clash.json", content: "first" });
  });

  it("retiring and reopening a Canvas reminder adds and then removes retiredAt", async () => {
    const store = new FirestoreCalendarStore(db());
    const service = new CalendarService(store, new NoopNotifier());
    const published = await service.publishTaskReminders({ taskKey: "canvas-it", reminders: [{ itemKey: "assignment:9", title: "TEST gone", startAt: "2026-11-20T23:59:00-07:00", timeZone: "America/Denver" }] });
    const id = published.reminders[0].event.id;
    expect((await service.retireTaskItems("canvas-it", [id])).retired).toBe(1);
    expect((await db().collection("events").doc(id).get()).data()).toMatchObject({ done: true, retiredAt: expect.any(String) });
    await service.reopenRetiredTaskItems("canvas-it", new Set(["assignment:9"]), await service.listByTaskKey("canvas-it"));
    const raw = (await db().collection("events").doc(id).get()).data()!;
    expect(raw.done).toBe(false);
    expect(raw.retiredAt).toBeUndefined();
  });
});
