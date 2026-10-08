import { describe, expect, it } from "vitest";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { NoopNotifier } from "../src/notifier.js";
import { CalendarService } from "../src/service.js";
import { FirestoreCalendarStore } from "../src/store.js";

// Runs only against the Firestore emulator: FIRESTORE_EMULATOR_HOST=127.0.0.1:8085 npx vitest run
const emulator = process.env.FIRESTORE_EMULATOR_HOST;

describe.skipIf(!emulator)("FirestoreCalendarStore (emulator)", () => {
  const projectId = `demo-bridge-${Date.now()}`;
  const app = emulator ? initializeApp({ projectId }, projectId) : undefined;
  const db = () => getFirestore(app!);

  it("serializes concurrent idempotent creates and concurrent edits with transactions", async () => {
    const store = new FirestoreCalendarStore(db());
    const service = new CalendarService(store, new NoopNotifier());
    const input = { title: "Dentist", startAt: "2026-10-01T09:00:00-06:00", timeZone: "America/Denver", idempotencyKey: "dentist-key-1" };
    const results = await Promise.all(Array.from({ length: 5 }, () => service.create(input)));
    expect(new Set(results.map((r) => r.event.id)).size).toBe(1);
    expect(results.filter((r) => r.created)).toHaveLength(1);

    const id = results[0].event.id;
    await Promise.all([service.update(id, { done: true }), service.update(id, { title: "Renamed" }), service.update(id, { notes: "n" })]);
    const event = await store.getEvent(id);
    expect(event).toMatchObject({ done: true, title: "Renamed", notes: "n" });
  });

  it("keeps tombstones with a TTL field and restores them cleanly", async () => {
    const store = new FirestoreCalendarStore(db());
    const service = new CalendarService(store, new NoopNotifier());
    const item = { itemKey: "rent", title: "Rent", startAt: "2026-10-01T09:00:00-06:00", timeZone: "America/Denver" };
    const published = await service.publishTaskReminders({ taskKey: "planner", reminders: [item] });
    const id = published.reminders[0].event.id;
    await service.delete(id);
    const raw = (await db().collection("events").doc(id).get()).data()!;
    expect(raw.deletedAt).toBeTruthy();
    expect(raw.expireAt).toBeInstanceOf(Timestamp);
    expect((await service.publishTaskReminders({ taskKey: "planner", reminders: [item] })).suppressed).toBe(1);
    expect((await service.list(true)).some((event) => event.id === id)).toBe(false);
    await service.publishTaskReminders({ taskKey: "planner", restoreDeleted: true, reminders: [item] });
    const restored = (await db().collection("events").doc(id).get()).data()!;
    expect(restored.deletedAt).toBeUndefined();
    expect(restored.expireAt).toBeUndefined();
  });

  it("rotates refresh tokens atomically, stamps TTL fields, merges kv, and ignores missing devices", async () => {
    const store = new FirestoreCalendarStore(db());
    const expiresAt = Date.now() + 60_000_000;
    await store.putOAuthToken({ tokenHash: "h1", kind: "refresh", clientId: "c", resource: "r", scope: "s", expiresAt });
    expect((await db().collection("oauthTokens").doc("h1").get()).data()!.expireAt).toBeInstanceOf(Timestamp);
    const [a, b] = await Promise.all([store.rotateOAuthRefreshToken("h1", 60_000), store.rotateOAuthRefreshToken("h1", 60_000)]);
    expect(a?.clientId).toBe("c");
    expect(b?.clientId).toBe("c");
    const rotated = await store.getOAuthToken("h1");
    expect(rotated?.rotatedAt).toBeTypeOf("number");
    expect(rotated!.expiresAt).toBeLessThanOrEqual(Date.now() + 60_000);
    expect(await store.rotateOAuthRefreshToken("missing", 1000)).toBeNull();

    await store.mergeKv("planner-index", { a: { key: "a" } });
    await Promise.all([store.mergeKv("planner-index", { b: { key: "b" } }), store.mergeKv("planner-index", { c: { key: "c" } })]);
    expect(Object.keys((await store.getKv("planner-index"))!).sort()).toEqual(["a", "b", "c"]);

    await expect(store.patchDevice("no-such-device", { pushInvalidSince: "x" })).resolves.toBeUndefined();
    await store.putDevice({ id: "d1", installationId: "fid", authHash: "h", deviceName: "Pixel", createdAt: "", lastSeenAt: "" });
    await store.patchDevice("d1", { pushInvalidSince: "2026-09-26T00:00:00Z" });
    expect((await store.listDevices())[0].pushInvalidSince).toBe("2026-09-26T00:00:00Z");
  });
});
