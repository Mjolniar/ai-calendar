import request from "supertest";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { createMcpServer } from "../src/mcp.js";
import { NoopNotifier, type DeviceNotifier } from "../src/notifier.js";
import { CalendarService } from "../src/service.js";
import { MemoryCalendarStore } from "../src/store.js";
import type { SeriesInput } from "../src/types.js";

class CountingNotifier implements DeviceNotifier {
  calls = 0;
  async signalSync(): Promise<number> {
    this.calls += 1;
    return 1;
  }
}

/** A clock the test moves by hand. Starts Thursday, October 8, 2026, 11:00 AM in Denver. */
function testClock(start = "2026-10-08T17:00:00Z") {
  let now = Date.parse(start);
  return { now: () => now, set: (iso: string) => { now = Date.parse(iso); } };
}

function setup(start?: string) {
  const store = new MemoryCalendarStore();
  const notifier = new CountingNotifier();
  const clock = testClock(start);
  const service = new CalendarService(store, notifier, clock.now);
  return { store, notifier, clock, service };
}

const trash: SeriesInput = {
  title: "Trash pickup",
  timeZone: "America/Denver",
  reminderOffsetsMinutes: [900],
  rule: { freq: "weekly", weekdays: [5], startDate: "2026-10-09", time: "08:00" },
};

async function open(service: CalendarService, seriesId: string) {
  return (await service.list(false)).filter((event) => event.seriesId === seriesId);
}

describe("series", () => {
  it("creates one reminder per occurrence for the next 60 days, with the series' alerts", async () => {
    const { service, notifier } = setup();
    const created = await service.createSeries(trash);
    expect(created.created).toBe(true);
    expect(created.occurrences.map((event) => event.occurrenceDate)).toEqual([
      "2026-10-09", "2026-10-16", "2026-10-23", "2026-10-30", "2026-11-06", "2026-11-13", "2026-11-20", "2026-11-27", "2026-12-04",
    ]);
    const first = created.occurrences[0];
    expect(first).toMatchObject({
      title: "Trash pickup",
      startAt: "2026-10-09T14:00:00.000Z",
      reminderOffsetsMinutes: [900],
      seriesId: created.series.id,
      done: false,
      domain: "life",
      source: "chatgpt",
    });
    // Still 8:00 AM after the change to standard time.
    expect(created.occurrences[4].startAt).toBe("2026-11-06T15:00:00.000Z");
    expect(notifier.calls).toBe(1);
  });

  it("is idempotent: retrying a create and expanding again never duplicates", async () => {
    const { service } = setup();
    const first = await service.createSeries({ ...trash, idempotencyKey: "phone-save-0001" });
    const retry = await service.createSeries({ ...trash, idempotencyKey: "phone-save-0001" });
    expect(retry.created).toBe(false);
    expect(retry.series.id).toBe(first.series.id);
    const again = await service.expandAllSeries();
    expect(again).toMatchObject({ series: 1, created: 0, updated: 0, removed: 0, notified: 0 });
    expect(await open(service, first.series.id)).toHaveLength(9);
  });

  it("adds occurrences as time passes and keeps past ones", async () => {
    const { service, clock } = setup();
    const { series } = await service.createSeries(trash);
    clock.set("2026-10-22T17:00:00Z");
    const expanded = await service.expandAllSeries();
    expect(expanded.created).toBe(2);
    const dates = (await open(service, series.id)).map((event) => event.occurrenceDate);
    expect(dates[0]).toBe("2026-10-09");
    expect(dates.at(-1)).toBe("2026-12-18");
  });

  it("keeps at least the next two occurrences of a rare series", async () => {
    const { service } = setup();
    const created = await service.createSeries({ ...trash, title: "Renew registration", rule: { freq: "yearly", startDate: "2027-01-15", time: "09:00" } });
    expect(created.occurrences.map((event) => event.occurrenceDate)).toEqual(["2027-01-15", "2028-01-15"]);
  });

  it("completing one occurrence marks only that one done, and expansion leaves it done", async () => {
    const { service } = setup();
    const { occurrences } = await service.createSeries(trash);
    await service.update(occurrences[0].id, { done: true });
    await service.expandAllSeries();
    const all = (await service.list(true)).filter((event) => event.seriesId);
    expect(all.filter((event) => event.done).map((event) => event.occurrenceDate)).toEqual(["2026-10-09"]);
    expect(all.find((event) => event.done)?.detached).toBeUndefined();
  });

  it("deleting one occurrence skips its date for good", async () => {
    const { service } = setup();
    const { series, occurrences } = await service.createSeries(trash);
    const deleted = await service.delete(occurrences[1].id);
    expect(deleted.deleted).toBe(true);
    expect((await service.getSeries(series.id))?.skippedDates).toEqual(["2026-10-16"]);
    await service.expandAllSeries();
    expect((await open(service, series.id)).map((event) => event.occurrenceDate)).not.toContain("2026-10-16");
  });

  it("an occurrence edited on its own is detached and survives series changes", async () => {
    const { service } = setup();
    const { series, occurrences } = await service.createSeries(trash);
    const moved = await service.update(occurrences[2].id, { startAt: "2026-10-24T08:00:00-06:00" });
    expect(moved?.event.detached).toBe(true);
    await service.updateSeries(series.id, { title: "Trash and recycling" });
    const after = await open(service, series.id);
    const detached = after.find((event) => event.id === occurrences[2].id)!;
    expect(detached.title).toBe("Trash pickup");
    expect(detached.startAt).toBe("2026-10-24T14:00:00.000Z");
    expect(after.filter((event) => event.id !== detached.id).every((event) => event.title === "Trash and recycling")).toBe(true);
  });

  it("changing the whole series rewrites future unfinished occurrences and removes dates it no longer has", async () => {
    const { service } = setup();
    const { series, occurrences } = await service.createSeries(trash);
    await service.update(occurrences[0].id, { done: true });
    const updated = await service.updateSeries(series.id, { rule: { weekdays: [4], time: "07:30" } });
    expect(updated?.series.id).toBe(series.id);
    const listed = await open(service, series.id);
    expect(listed.every((event) => event.occurrenceDate && new Date(`${event.occurrenceDate}T12:00:00Z`).getUTCDay() === 4)).toBe(true);
    expect(listed[0]).toMatchObject({ occurrenceDate: "2026-10-15", startAt: "2026-10-15T13:30:00.000Z" });
    // The done Friday stays.
    expect((await service.list(true)).find((event) => event.id === occurrences[0].id)?.done).toBe(true);
  });

  it("this and all future ones: ends the series the day before and continues with the change", async () => {
    const { service } = setup();
    const { series, occurrences } = await service.createSeries({ ...trash, rule: { ...trash.rule, count: 6 } });
    await service.update(occurrences[3].id, { notes: "Bulk item day" }); // detached, Oct 30
    const split = await service.updateSeries(series.id, { from: "2026-10-23", title: "Trash (new hauler)", idempotencyKey: "split-key-0001" });
    expect(split?.previous?.rule.until).toBe("2026-10-22");
    expect(split?.series.id).not.toBe(series.id);
    expect(split?.series.rule).toMatchObject({ startDate: "2026-10-09", notBefore: "2026-10-23", count: 6 });
    expect((await open(service, series.id)).map((event) => [event.occurrenceDate, event.title])).toEqual([
      ["2026-10-09", "Trash pickup"],
      ["2026-10-16", "Trash pickup"],
      ["2026-10-30", "Trash pickup"], // detached: left alone
    ]);
    // The new part keeps the count from the original start (occurrences 3 to 6) and skips the date
    // the old series still shows as its own edited occurrence, so October 30 is listed once.
    expect(split?.series.skippedDates).toEqual(["2026-10-30"]);
    expect((await open(service, split!.series.id)).map((event) => event.occurrenceDate)).toEqual(["2026-10-23", "2026-11-06", "2026-11-13"]);
    // A retried split returns the same new series and changes nothing.
    const retry = await service.updateSeries(series.id, { from: "2026-10-23", title: "Trash (new hauler)", idempotencyKey: "split-key-0001" });
    expect(retry?.series.id).toBe(split?.series.id);
    expect(await open(service, split!.series.id)).toHaveLength(3);
  });

  it("this and all future ones with a new start date re-anchors and carries the remaining count", async () => {
    const { service } = setup();
    const { series } = await service.createSeries({ ...trash, rule: { ...trash.rule, count: 5 } });
    const split = await service.updateSeries(series.id, { from: "2026-10-23", rule: { startDate: "2026-10-24", weekdays: [6] } });
    expect(split?.series.rule).toMatchObject({ startDate: "2026-10-24", weekdays: [6], count: 3 });
    expect(split?.series.rule.notBefore).toBeUndefined();
    expect((await open(service, split!.series.id)).map((event) => event.occurrenceDate)).toEqual(["2026-10-24", "2026-10-31", "2026-11-07"]);
  });

  it("deleting a series removes future unfinished occurrences and keeps past and done ones", async () => {
    const { service, clock } = setup("2026-10-01T17:00:00Z");
    const { series, occurrences } = await service.createSeries({ ...trash, rule: { ...trash.rule, startDate: "2026-10-02" } });
    await service.update(occurrences[2].id, { done: true }); // Oct 16, done early
    clock.set("2026-10-12T17:00:00Z");
    const deleted = await service.deleteSeries(series.id);
    expect(deleted.deleted).toBe(true);
    const left = (await service.list(true)).filter((event) => event.seriesId === series.id).map((event) => event.occurrenceDate);
    expect(left).toEqual(["2026-10-02", "2026-10-09", "2026-10-16"]);
    expect(await service.listSeries()).toEqual([]);
    expect((await service.expandAllSeries()).created).toBe(0);
  });

  it("deleting from a date ends the series there", async () => {
    const { service } = setup();
    const { series } = await service.createSeries(trash);
    const deleted = await service.deleteSeries(series.id, "2026-10-30");
    expect(deleted.removed).toBe(6);
    expect((await open(service, series.id)).map((event) => event.occurrenceDate)).toEqual(["2026-10-09", "2026-10-16", "2026-10-23"]);
    expect((await service.getSeries(series.id))?.rule.until).toBe("2026-10-29");
  });

  it("rejects deliveries and bad rules", async () => {
    const { service } = setup();
    await expect(service.createSeries({ ...trash, domain: "delivery" })).rejects.toThrow("Deliveries cannot repeat");
    await expect(service.createSeries({ ...trash, category: "delivery" })).rejects.toThrow("Deliveries cannot repeat");
    await expect(service.createSeries({ ...trash, rule: { ...trash.rule, time: "8am" } })).rejects.toThrow("rule.time");
    await expect(service.createSeries({ ...trash, rule: { ...trash.rule, startDate: "2026-02-30" } })).rejects.toThrow("rule.startDate");
    await expect(service.createSeries({ ...trash, rule: { ...trash.rule, until: "2026-12-01", count: 3 } })).rejects.toThrow("until or count");
    await expect(service.createSeries({ ...trash, rule: { freq: "monthly", weekdays: [1], startDate: "2026-10-09", time: "08:00" } })).rejects.toThrow("weekly rules only");
    await expect(service.createSeries({ ...trash, timeZone: "Mountain" })).rejects.toThrow("timeZone");
  });

  it("the routine class rule never archives an occurrence of a series", async () => {
    const { service } = setup();
    const { series } = await service.createSeries({
      ...trash,
      title: "ETH 110 class every Tuesday",
      domain: "school",
      rule: { freq: "weekly", weekdays: [2], startDate: "2026-10-13", time: "09:00" },
    });
    const archived = await service.archiveRoutineSchoolMeetings();
    expect(archived.updated).toBe(0);
    expect((await open(service, series.id)).length).toBeGreaterThan(0);
  });
});

describe("series over the device API and MCP", () => {
  async function paired() {
    const store = new MemoryCalendarStore();
    const app = createApp(store, new NoopNotifier(), {
      apiToken: "test-api-token",
      pairingCode: "654321",
      firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
      publicBaseUrl: "https://calendar.example.test/api",
    });
    const pairing = await request(app).post("/v1/devices/register").send({ pairingCode: "654321", installationId: "fid-1" }).expect(201);
    return { app, store, auth: `Bearer ${pairing.body.deviceToken}` };
  }

  const body = {
    title: "Water plants",
    timeZone: "America/Denver",
    reminderOffsetsMinutes: [0],
    rule: { freq: "daily", interval: 3, startDate: "2099-01-01", time: "18:00" },
    idempotencyKey: "phone-0000-0001",
  };

  it("creates, retries, syncs, changes from a date and deletes", async () => {
    const { app, auth } = await paired();
    await request(app).post("/v1/series").send(body).expect(401);
    const created = await request(app).post("/v1/series").set("Authorization", auth).send(body).expect(201);
    await request(app).post("/v1/series").set("Authorization", auth).send(body).expect(200);
    const id = created.body.series.id;
    expect(created.body.occurrences.length).toBeGreaterThanOrEqual(2);

    const sync = await request(app).get("/v1/sync").set("Authorization", auth).expect(200);
    // The old shape is intact; series and the occurrence fields are additions.
    expect(Array.isArray(sync.body.events)).toBe(true);
    expect(typeof sync.body.revision).toBe("number");
    expect(sync.body.series.map((item: { id: string }) => item.id)).toEqual([id]);
    expect(sync.body.events[0]).toMatchObject({ seriesId: id, occurrenceDate: "2099-01-01", title: "Water plants" });

    const split = await request(app).patch(`/v1/series/${id}`).set("Authorization", auth)
      .send({ from: "2099-01-04", title: "Water the plants", idempotencyKey: "phone-0000-0002" }).expect(200);
    expect(split.body.previous.rule.until).toBe("2099-01-03");
    await request(app).patch(`/v1/series/${id}`).set("Authorization", auth).send({ rule: { time: "6pm" } }).expect(400);

    await request(app).delete(`/v1/series/${split.body.series.id}?from=2099-01-07`).set("Authorization", auth).expect(200);
    await request(app).delete(`/v1/series/${id}`).set("Authorization", auth).expect(200);
    await request(app).delete(`/v1/series/${id}`).set("Authorization", auth).expect(404);
  });

  it("rejects a delivery series and a missing rule", async () => {
    const { app, auth } = await paired();
    await request(app).post("/v1/series").set("Authorization", auth).send({ ...body, domain: "delivery" }).expect(400);
    await request(app).post("/v1/series").set("Authorization", auth).send({ ...body, rule: undefined }).expect(400);
  });

  it("deleting an occurrence through the event endpoint skips its date", async () => {
    const { app, auth } = await paired();
    const created = await request(app).post("/v1/series").set("Authorization", auth).send(body).expect(201);
    const occurrence = created.body.occurrences[0];
    await request(app).delete(`/v1/events/${occurrence.id}`).set("Authorization", auth).expect(200);
    const sync = await request(app).get("/v1/sync").set("Authorization", auth).expect(200);
    expect(sync.body.series[0].skippedDates).toEqual(["2099-01-01"]);
    expect(sync.body.events.map((event: { id: string }) => event.id)).not.toContain(occurrence.id);
  });

  it("registers the four series tools and mentions series in the instructions", async () => {
    const service = new CalendarService(new MemoryCalendarStore(), new NoopNotifier());
    const server = createMcpServer(service);
    const tools = Object.keys((server as unknown as { _registeredTools: Record<string, unknown> })._registeredTools);
    expect(tools).toEqual(expect.arrayContaining(["list_series", "create_series", "update_series", "delete_series", "list_reminders"]));
    const instructions = (server.server as unknown as { _instructions?: string })._instructions ?? "";
    expect(instructions).toContain("create_series");
  });
});
