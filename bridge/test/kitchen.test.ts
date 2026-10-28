import request from "supertest";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { KitchenService, localDate, normalizeName } from "../src/kitchen.js";
import { NoopNotifier, type DeviceNotifier } from "../src/notifier.js";
import type { DeviceRecord } from "../src/types.js";
import { MemoryCalendarStore } from "../src/store.js";

const PAIRING = "654321";

function testApp(store = new MemoryCalendarStore()) {
  return createApp(store, new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: PAIRING,
    firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
    publicBaseUrl: "https://calendar.example.test/api",
  });
}

async function pair(app: ReturnType<typeof testApp>, deviceName = "Pixel 10 Pro XL") {
  const res = await request(app).post("/v1/devices/register")
    .send({ pairingCode: PAIRING, installationId: `fid-${deviceName}`, deviceName }).expect(201);
  return `Bearer ${res.body.deviceToken as string}`;
}

async function mcp(app: ReturnType<typeof testApp>, method: string, params: unknown) {
  const res = await request(app).post("/mcp")
    .set("Authorization", "Bearer test-api-token")
    .set("Accept", "application/json, text/event-stream")
    .send({ jsonrpc: "2.0", id: 1, method, params });
  const line = res.text.split("\n").find((l) => l.startsWith("data: "));
  return JSON.parse(line ? line.slice(6) : res.text);
}

function plan(start: string, end: string, dinners: string[]) {
  const first = new Date(`${start}T12:00:00Z`);
  return {
    week: { start, end, title: `Meal Plan ${start}` },
    status: "ordered",
    days: dinners.map((dinner, index) => ({
      date: new Date(first.getTime() + index * 86_400_000).toISOString().slice(0, 10),
      label: `Day ${index + 1}`, dinner, base: "rice", kind: "cook", recipe: `r${index}`,
    })),
    recipes: dinners.map((dinner, index) => ({ id: `r${index}`, title: dinner, sub: "30 min", ingredients: "stuff", steps: ["Cook it."] })),
  };
}

describe("kitchen names", () => {
  it("treats case, spacing, punctuation, accents and a plural s as the same item", () => {
    expect(normalizeName("Aluminum Foil")).toBe(normalizeName("aluminum-foil"));
    expect(normalizeName("Eggs")).toBe(normalizeName("egg"));
    expect(normalizeName("Jalapeños")).toBe(normalizeName("jalapeno"));
    expect(normalizeName("Glass cleaner")).not.toBe(normalizeName("Gla cleaner"));
    expect(normalizeName("Milk")).not.toBe(normalizeName("Silk"));
  });

  it("reads today in Denver time", () => {
    expect(localDate(new Date("2026-10-28T05:30:00Z"))).toBe("2026-10-27");
    expect(localDate(new Date("2026-10-28T07:30:00Z"))).toBe("2026-10-28");
  });
});

describe("KitchenService rules", () => {
  it("rule 1: an open item with the same name is reused and its qty replaced", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const [first] = await kitchen.addItems([{ name: "Aluminum foil" }], "device", "Pixel");
    const [again] = await kitchen.addItems([{ name: "aluminum  FOIL", qty: "2" }], "ai", "Claude");
    expect(first.created).toBe(true);
    expect(again.created).toBe(false);
    expect(again.item.id).toBe(first.item.id);
    expect(again.item.qty).toBe("2");
    const snapshot = await kitchen.snapshot();
    expect(snapshot.list).toHaveLength(1);
    expect(snapshot.list[0]).toMatchObject({ name: "Aluminum foil", source: "device", addedBy: "Pixel", status: "need", qty: "2" });
  });

  it("a got item does not block adding the same thing again", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const [foil] = await kitchen.addItems([{ name: "Foil" }], "device", "Pixel");
    await kitchen.updateItem(foil.item.id, { status: "got" });
    const [again] = await kitchen.addItems([{ name: "Foil" }], "device", "Pixel");
    expect(again.created).toBe(true);
    expect((await kitchen.snapshot()).list.map((item) => item.status)).toEqual(["need", "got"]);
  });

  it("rule 2: pantry out adds the item to the list once", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const [soy] = await kitchen.setPantry([{ name: "Soy sauce", section: "Sauces & condiments", status: "have" }]);
    expect(soy.listItem).toBeUndefined();
    const out = await kitchen.updatePantryItem(soy.item!.id, { status: "out" });
    expect(out?.listItem).toMatchObject({ name: "Soy sauce", source: "pantry", addedBy: "Pantry", status: "need" });
    // Already open on the list: no second item, and staying out adds nothing.
    await kitchen.updatePantryItem(soy.item!.id, { status: "low" });
    const again = await kitchen.updatePantryItem(soy.item!.id, { status: "out" });
    expect(again?.listItem).toBeUndefined();
    expect((await kitchen.snapshot()).list).toHaveLength(1);
  });

  it("rule 2 also applies to AI upserts and new pantry items", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const results = await kitchen.setPantry([{ name: "BBQ sauce", status: "out" }, { name: "Rice", status: "have" }]);
    expect(results[0].listItem?.name).toBe("BBQ sauce");
    expect(results[1].listItem).toBeUndefined();
    const added = await kitchen.addPantryItem({ name: "Spaghetti sauce", status: "out" });
    expect(added.listItem?.name).toBe("Spaghetti sauce");
    expect((await kitchen.snapshot()).list.map((item) => item.name)).toEqual(["BBQ sauce", "Spaghetti sauce"]);
  });

  it("rule 3: got restocks the pantry item with that name", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    await kitchen.setPantry([{ name: "Cheese", section: "Fridge & counter", status: "out" }]);
    const listItem = (await kitchen.snapshot()).list[0];
    await kitchen.updateItem(listItem.id, { status: "got" });
    const snapshot = await kitchen.snapshot();
    expect(snapshot.pantry[0].status).toBe("have");
    expect(snapshot.list[0].doneAt).toBeTruthy();
    // Unticking does not change the pantry back.
    await kitchen.updateItem(listItem.id, { status: "need" });
    const after = await kitchen.snapshot();
    expect(after.pantry[0].status).toBe("have");
    expect(after.list[0].doneAt).toBeUndefined();
  });

  it("rule 4: the device snapshot keeps got items for 7 days and storage for 30", async () => {
    let now = new Date("2026-10-20T18:00:00Z");
    const store = new MemoryCalendarStore();
    const kitchen = new KitchenService(store, () => now);
    const [a] = await kitchen.addItems([{ name: "Foil" }], "device", "Pixel");
    await kitchen.updateItem(a.item.id, { status: "got" });
    now = new Date("2026-10-26T18:00:00Z");
    expect((await kitchen.snapshot()).list).toHaveLength(1);
    now = new Date("2026-10-28T18:00:00Z");
    expect((await kitchen.snapshot()).list).toHaveLength(0);
    expect((await kitchen.snapshot(30)).list).toHaveLength(1);
    now = new Date("2026-11-25T18:00:00Z");
    await kitchen.addItems([{ name: "Milk" }], "device", "Pixel");
    expect((await kitchen.snapshot(60)).list.map((item) => item.name)).toEqual(["Milk"]);
  });

  it("orders the list need, ordered, then got newest first", async () => {
    let now = new Date("2026-10-26T15:00:00Z");
    const kitchen = new KitchenService(new MemoryCalendarStore(), () => now);
    const names = ["Eggs", "Milk", "Bread", "Foil", "Litter"];
    const ids: Record<string, string> = {};
    for (const name of names) {
      ids[name] = (await kitchen.addItems([{ name }], "device", "Pixel"))[0].item.id;
      now = new Date(now.getTime() + 60_000);
    }
    await kitchen.updateItem(ids.Milk, { status: "ordered", orderRef: "Walmart #1" });
    await kitchen.updateItem(ids.Eggs, { status: "got" });
    now = new Date(now.getTime() + 60_000);
    await kitchen.updateItem(ids.Bread, { status: "got" });
    expect((await kitchen.snapshot()).list.map((item) => item.name)).toEqual(["Foil", "Litter", "Milk", "Bread", "Eggs"]);
  });

  it("picks the current and next week by Denver date and returns their notes", async () => {
    let now = new Date("2026-10-27T03:00:00Z"); // Mon 10/26, 9 PM in Denver
    const kitchen = new KitchenService(new MemoryCalendarStore(), () => now);
    await kitchen.publishWeek(plan("2026-10-20", "2026-10-26", ["A", "B", "C", "D", "E", "F", "G"]));
    await kitchen.publishWeek(plan("2026-10-27", "2026-11-02", ["Salmon", "Jambalaya", "Leftovers", "Burgers", "Chili", "BBQ chicken", "Bean burgers"]));
    await kitchen.setNote("2026-10-26", { cooked: "yes", rating: "loved" });
    await kitchen.setNote("2026-10-27", { rating: "ok", note: "a bit dry" });
    await kitchen.setNote("2026-09-01", { cooked: "skipped" });
    let snapshot = await kitchen.snapshot();
    expect(snapshot.week?.week.start).toBe("2026-10-20");
    expect(snapshot.nextWeek?.week.start).toBe("2026-10-27");
    expect(Object.keys(snapshot.notes).sort()).toEqual(["2026-10-26", "2026-10-27"]);
    expect(snapshot.notes["2026-10-27"]).toMatchObject({ cooked: "", rating: "ok", note: "a bit dry" });
    now = new Date("2026-10-27T15:00:00Z");
    snapshot = await kitchen.snapshot();
    expect(snapshot.week?.days[0].dinner).toBe("Salmon");
    expect(snapshot.nextWeek).toBeNull();
    const republished = await kitchen.publishWeek(plan("2026-10-27", "2026-11-02", ["Cod", "Jambalaya"]));
    expect(republished.replaced).toBe(true);
    expect((await kitchen.snapshot()).week?.days[0].dinner).toBe("Cod");
    expect((await kitchen.weeks()).map((week) => week.start)).toEqual(["2026-10-27", "2026-10-20"]);
  });

  it("refuses malformed plans, names and notes", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    await expect(kitchen.publishWeek({ week: { start: "2026-10-27" }, days: [] })).rejects.toThrow(/start and end/);
    await expect(kitchen.publishWeek({ week: { start: "2026-10-27", end: "2026-10-20" }, days: [{ date: "2026-10-27", dinner: "x" }] })).rejects.toThrow(/before/);
    await expect(kitchen.publishWeek({ week: { start: "2026-10-27", end: "2026-11-02" }, days: [{ date: "2026-02-30", dinner: "x" }] })).rejects.toThrow(/date/);
    await expect(kitchen.publishWeek({ week: { start: "2026-10-27", end: "2026-11-02" }, days: [{ date: "2026-10-27" }] })).rejects.toThrow(/dinner/);
    await expect(kitchen.addItems([{ name: "  " }], "device", "Pixel")).rejects.toThrow(/name/);
    await expect(kitchen.addItems([{ name: "x".repeat(121) }], "device", "Pixel")).rejects.toThrow(/name/);
    await expect(kitchen.setNote("2026-13-01", { cooked: "yes" })).rejects.toThrow(/date/);
    await expect(kitchen.setNote("2026-10-27", { rating: "meh" as never })).rejects.toThrow(/rating/);
    await expect(kitchen.updatePantryItem("p1", { status: "gone" as never })).rejects.toThrow(/status/);
  });
});

describe("kitchen device API", () => {
  it("requires a device token on every route", async () => {
    const app = testApp();
    await request(app).get("/v1/kitchen").expect(401);
    await request(app).post("/v1/kitchen/items").send({ name: "Foil" }).expect(401);
    await request(app).patch("/v1/kitchen/items/kabc").send({ status: "got" }).expect(401);
    await request(app).delete("/v1/kitchen/items/kabc").expect(401);
    await request(app).post("/v1/kitchen/pantry").send({ name: "Rice" }).expect(401);
    await request(app).patch("/v1/kitchen/pantry/pabc").send({ status: "out" }).expect(401);
    await request(app).delete("/v1/kitchen/pantry/pabc").expect(401);
    await request(app).put("/v1/kitchen/notes/2026-10-27").send({ rating: "loved" }).expect(401);
    await request(app).get("/v1/kitchen").set("Authorization", "Bearer nope").expect(401);
  });

  it("adds, ticks, unticks and removes list items, naming the device", async () => {
    const app = testApp();
    const auth = await pair(app, "SM-S942U");
    const empty = await request(app).get("/v1/kitchen").set("Authorization", auth).expect(200);
    expect(empty.body).toMatchObject({ list: [], pantry: [], week: null, nextWeek: null, notes: {} });

    const added = await request(app).post("/v1/kitchen/items").set("Authorization", auth).send({ name: "Aluminum foil", qty: "1" }).expect(201);
    expect(added.body).toMatchObject({ created: true, item: { name: "Aluminum foil", qty: "1", addedBy: "SM-S942U", source: "device", status: "need" } });
    expect(added.body.revision).toBeGreaterThan(empty.body.revision);
    const dup = await request(app).post("/v1/kitchen/items").set("Authorization", auth).send({ name: "aluminum foil" }).expect(200);
    expect(dup.body.created).toBe(false);

    const id = added.body.item.id as string;
    const got = await request(app).patch(`/v1/kitchen/items/${id}`).set("Authorization", auth).send({ status: "got" }).expect(200);
    expect(got.body.item.status).toBe("got");
    await request(app).patch(`/v1/kitchen/items/${id}`).set("Authorization", auth).send({ status: "ordered" }).expect(400);
    await request(app).patch(`/v1/kitchen/items/${id}`).set("Authorization", auth).send({ status: "need" }).expect(200);
    await request(app).patch("/v1/kitchen/items/knothere").set("Authorization", auth).send({ status: "got" }).expect(404);
    await request(app).patch("/v1/kitchen/items/bad%2Fid").set("Authorization", auth).send({ status: "got" }).expect(404);
    await request(app).post("/v1/kitchen/items").set("Authorization", auth).send({ name: "" }).expect(400);

    await request(app).delete(`/v1/kitchen/items/${id}`).set("Authorization", auth).expect(200);
    await request(app).delete(`/v1/kitchen/items/${id}`).set("Authorization", auth).expect(404);
    expect((await request(app).get("/v1/kitchen").set("Authorization", auth)).body.list).toEqual([]);
  });

  it("marks pantry items out, which lists them, and records dinner notes", async () => {
    const app = testApp();
    const auth = await pair(app);
    const rice = await request(app).post("/v1/kitchen/pantry").set("Authorization", auth).send({ name: "Jasmine rice", section: "Grains" }).expect(201);
    expect(rice.body.item).toMatchObject({ name: "Jasmine rice", section: "Grains", status: "have" });
    await request(app).post("/v1/kitchen/pantry").set("Authorization", auth).send({ name: "jasmine rice" }).expect(200);
    const out = await request(app).patch(`/v1/kitchen/pantry/${rice.body.item.id}`).set("Authorization", auth).send({ status: "out" }).expect(200);
    expect(out.body.listItem).toMatchObject({ name: "Jasmine rice", source: "pantry" });
    await request(app).patch(`/v1/kitchen/pantry/${rice.body.item.id}`).set("Authorization", auth).send({ status: "empty" }).expect(400);
    await request(app).patch("/v1/kitchen/pantry/pmissing").set("Authorization", auth).send({ status: "low" }).expect(404);

    const note = await request(app).put("/v1/kitchen/notes/2026-10-27").set("Authorization", auth).send({ cooked: "yes", rating: "loved", note: "Do again" }).expect(200);
    expect(note.body).toMatchObject({ date: "2026-10-27", note: { cooked: "yes", rating: "loved", note: "Do again" } });
    const partial = await request(app).put("/v1/kitchen/notes/2026-10-27").set("Authorization", auth).send({ rating: "ok" }).expect(200);
    expect(partial.body.note).toMatchObject({ cooked: "yes", rating: "ok", note: "Do again" });
    await request(app).put("/v1/kitchen/notes/not-a-date").set("Authorization", auth).send({ rating: "ok" }).expect(400);

    await request(app).delete(`/v1/kitchen/pantry/${rice.body.item.id}`).set("Authorization", auth).expect(200);
    const after = await request(app).get("/v1/kitchen").set("Authorization", auth).expect(200);
    expect(after.body.pantry).toEqual([]);
    expect(after.body.list).toHaveLength(1);
  });
});

describe("kitchen MCP tools", () => {
  it("lists the kitchen tools with their scopes", async () => {
    const answer = await mcp(testApp(), "tools/list", {});
    const tools = answer.result.tools as Array<{ name: string; securitySchemes: Array<{ scopes: string[] }> }>;
    const kitchen = tools.filter((tool) => tool.name.startsWith("kitchen_"));
    expect(kitchen.map((tool) => tool.name).sort()).toEqual(["kitchen_corrections_resolve", "kitchen_get", "kitchen_list_add", "kitchen_list_update", "kitchen_pantry_set", "kitchen_week_publish"]);
    expect(kitchen.find((tool) => tool.name === "kitchen_get")?.securitySchemes[0].scopes).toEqual(["reminders:read"]);
    expect(kitchen.find((tool) => tool.name === "kitchen_list_add")?.securitySchemes[0].scopes).toEqual(["reminders:read", "reminders:write"]);
  });

  it("runs a Monday: read, order, publish, and the phone sees it", async () => {
    const store = new MemoryCalendarStore();
    const app = testApp(store);
    const auth = await pair(app);
    await request(app).post("/v1/kitchen/items").set("Authorization", auth).send({ name: "Foil" }).expect(201);

    const pantry = await mcp(app, "tools/call", { name: "kitchen_pantry_set", arguments: { items: [
      { name: "Soy sauce", section: "Sauces & condiments", status: "low" },
      { name: "BBQ sauce", section: "Sauces & condiments", status: "out" },
    ] } });
    expect(pantry.result.structuredContent.results[1].listItem.name).toBe("BBQ sauce");

    const added = await mcp(app, "tools/call", { name: "kitchen_list_add", arguments: { items: [{ name: "Whole milk", qty: "1 gal" }, { name: "foil" }] } });
    expect(added.result.structuredContent.items.map((entry: { created: boolean }) => entry.created)).toEqual([true, false]);

    const read = await mcp(app, "tools/call", { name: "kitchen_get", arguments: {} });
    const list = read.result.structuredContent.list as Array<{ id: string; name: string }>;
    expect(list.map((item) => item.name)).toEqual(["Foil", "BBQ sauce", "Whole milk"]);

    const ordered = await mcp(app, "tools/call", { name: "kitchen_list_update", arguments: { updates: [
      ...list.map((item) => ({ id: item.id, status: "ordered", orderRef: "Walmart #200015, Tue 11/3" })),
      { id: "kmissing", status: "got" },
    ] } });
    expect(ordered.result.content[0].text).toBe("3 updated, 1 not found.");

    const today = localDate(new Date());
    const start = new Date(`${today}T12:00:00Z`);
    const end = new Date(start.getTime() + 6 * 86_400_000).toISOString().slice(0, 10);
    const published = await mcp(app, "tools/call", { name: "kitchen_week_publish", arguments: { plan: plan(today, end, ["Salmon", "Jambalaya"]) } });
    expect(published.result.structuredContent).toMatchObject({ start: today, replaced: false });
    const bad = await mcp(app, "tools/call", { name: "kitchen_week_publish", arguments: { plan: { week: { start: today, end }, days: [] } } });
    expect(bad.result.isError).toBe(true);

    const phone = await request(app).get("/v1/kitchen").set("Authorization", auth).expect(200);
    expect(phone.body.list.every((item: { status: string; orderRef: string }) => item.status === "ordered" && item.orderRef.startsWith("Walmart"))).toBe(true);
    expect(phone.body.week.days[0].dinner).toBe("Salmon");
    expect(phone.body.pantry.map((item: { name: string }) => item.name)).toEqual(["BBQ sauce", "Soy sauce"]);

    const got = await request(app).patch(`/v1/kitchen/items/${list[1].id}`).set("Authorization", auth).send({ status: "got" }).expect(200);
    expect(got.body.item.name).toBe("BBQ sauce");
    const restocked = await request(app).get("/v1/kitchen").set("Authorization", auth);
    expect(restocked.body.pantry.find((item: { name: string }) => item.name === "BBQ sauce").status).toBe("have");
  });
});

describe("kitchen push signal", () => {
  class KitchenCounter implements DeviceNotifier {
    calls: string[][] = [];
    fail = false;
    async signalSync(): Promise<number> {
      return 0;
    }
    async signalKitchen(devices: DeviceRecord[]): Promise<number> {
      if (this.fail) throw new Error("FCM down");
      this.calls.push(devices.map((device) => device.deviceName).sort());
      return devices.length;
    }
  }

  function appWith(notifier: KitchenCounter) {
    return createApp(new MemoryCalendarStore(), notifier, {
      apiToken: "test-api-token",
      pairingCode: PAIRING,
      firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
      publicBaseUrl: "https://calendar.example.test/api",
    });
  }

  it("signals the other devices after a device edit, not the device that made it", async () => {
    const notifier = new KitchenCounter();
    const app = appWith(notifier);
    const pixel = await pair(app, "Pixel");
    await pair(app, "Samsung");
    await pair(app, "PC");
    const added = await request(app).post("/v1/kitchen/items").set("Authorization", pixel).send({ name: "Foil" }).expect(201);
    expect(notifier.calls).toEqual([["PC", "Samsung"]]);
    await request(app).patch(`/v1/kitchen/items/${added.body.item.id}`).set("Authorization", pixel).send({ status: "got" }).expect(200);
    await request(app).put("/v1/kitchen/notes/2026-10-27").set("Authorization", pixel).send({ rating: "loved" }).expect(200);
    expect(notifier.calls).toHaveLength(3);
    // Reads and refused edits send nothing.
    await request(app).get("/v1/kitchen").set("Authorization", pixel).expect(200);
    await request(app).patch("/v1/kitchen/items/kmissing").set("Authorization", pixel).send({ status: "got" }).expect(404);
    await request(app).post("/v1/kitchen/pantry").set("Authorization", pixel).send({ name: "" }).expect(400);
    expect(notifier.calls).toHaveLength(3);
  });

  it("signals every device after an AI change", async () => {
    const notifier = new KitchenCounter();
    const app = appWith(notifier);
    await pair(app, "Pixel");
    await pair(app, "Samsung");
    await mcp(app, "tools/call", { name: "kitchen_list_add", arguments: { items: [{ name: "Milk" }] } });
    await mcp(app, "tools/call", { name: "kitchen_pantry_set", arguments: { items: [{ name: "Rice", status: "low" }] } });
    expect(notifier.calls).toEqual([["Pixel", "Samsung"], ["Pixel", "Samsung"]]);
  });

  it("never fails an edit when the push fails", async () => {
    const notifier = new KitchenCounter();
    notifier.fail = true;
    const app = appWith(notifier);
    const pixel = await pair(app, "Pixel");
    await pair(app, "Samsung");
    await request(app).post("/v1/kitchen/items").set("Authorization", pixel).send({ name: "Foil" }).expect(201);
    const read = await request(app).get("/v1/kitchen").set("Authorization", pixel).expect(200);
    expect(read.body.list).toHaveLength(1);
  });
});

describe("pantry estimate and corrections", () => {
  it("keeps Claude's basis and check time on the estimate", async () => {
    let now = new Date("2026-10-27T18:00:00Z");
    const kitchen = new KitchenService(new MemoryCalendarStore(), () => now);
    await kitchen.setPantry([{ name: "Eggs", section: "Fridge & counter", status: "have", basis: "bought 10/26" }]);
    now = new Date("2026-10-30T18:00:00Z");
    await kitchen.setPantry([{ name: "eggs", status: "low", basis: "used in Fri burgers" }]);
    const [eggs] = (await kitchen.snapshot()).pantry;
    expect(eggs).toMatchObject({ name: "Eggs", status: "low", basis: "used in Fri burgers", checkedAt: "2026-10-30T18:00:00.000Z" });
  });

  it("applies out and have at once, keeps notes open for Claude, and lets Claude answer them", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const [eggs] = await kitchen.setPantry([{ name: "Eggs", section: "Fridge & counter", status: "have", basis: "bought 10/26" }]);
    const out = await kitchen.addCorrection({ kind: "out", itemId: eggs.item!.id }, "SM-S942U");
    expect(out?.correction).toMatchObject({ kind: "out", status: "applied", itemName: "Eggs", reply: "Marked out and added to the shopping list." });
    expect(out?.item).toMatchObject({ status: "out", basis: "SM-S942U said it's out" });
    expect(out?.listItem?.name).toBe("Eggs");
    const have = await kitchen.addCorrection({ kind: "have", itemId: eggs.item!.id }, "Pixel");
    expect(have?.item).toMatchObject({ status: "have", basis: "Pixel said you have it" });

    const note = await kitchen.addCorrection({ kind: "note", text: "Tossed the cilantro" }, "Pixel");
    expect(note?.correction).toMatchObject({ status: "open", reply: "" });
    let snapshot = await kitchen.snapshot();
    expect(snapshot.corrections.map((entry) => entry.status)).toEqual(["open", "applied", "applied"]);

    const [answered] = await kitchen.resolveCorrections([{ id: note!.correction.id, status: "applied", reply: "Set cilantro to out." }]);
    expect(answered.correction).toMatchObject({ status: "applied", reply: "Set cilantro to out." });
    snapshot = await kitchen.snapshot();
    expect(snapshot.corrections.find((entry) => entry.id === note!.correction.id)?.reply).toBe("Set cilantro to out.");
    expect(await kitchen.withdrawCorrection(note!.correction.id)).toBe(false);
  });

  it("refuses bad corrections", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    await expect(kitchen.addCorrection({ kind: "gone" }, "Pixel")).rejects.toThrow(/kind/);
    await expect(kitchen.addCorrection({ kind: "note", text: "  " }, "Pixel")).rejects.toThrow(/text/);
    await expect(kitchen.addCorrection({ kind: "out" }, "Pixel")).rejects.toThrow(/itemId/);
    expect(await kitchen.addCorrection({ kind: "out", itemId: "pmissing" }, "Pixel")).toBeNull();
  });

  it("serves corrections to devices and Claude", async () => {
    const app = testApp();
    const auth = await pair(app, "Pixel");
    const [eggs] = (await mcp(app, "tools/call", { name: "kitchen_pantry_set", arguments: { items: [{ name: "Eggs", status: "have", basis: "bought 10/26" }] } })).result.structuredContent.results;
    await request(app).post("/v1/kitchen/corrections").send({ kind: "note", text: "x" }).expect(401);
    const out = await request(app).post("/v1/kitchen/corrections").set("Authorization", auth).send({ kind: "out", itemId: eggs.item.id }).expect(201);
    expect(out.body.listItem.name).toBe("Eggs");
    await request(app).post("/v1/kitchen/corrections").set("Authorization", auth).send({ kind: "out", itemId: "pnothere" }).expect(404);
    await request(app).post("/v1/kitchen/corrections").set("Authorization", auth).send({ kind: "note" }).expect(400);
    const note = await request(app).post("/v1/kitchen/corrections").set("Authorization", auth).send({ kind: "note", text: "We have 2 cans of black beans" }).expect(201);

    const read = await mcp(app, "tools/call", { name: "kitchen_get", arguments: {} });
    const open = read.result.structuredContent.corrections.filter((entry: { status: string }) => entry.status === "open");
    expect(open.map((entry: { text: string }) => entry.text)).toEqual(["We have 2 cans of black beans"]);
    const answered = await mcp(app, "tools/call", { name: "kitchen_corrections_resolve", arguments: { updates: [{ id: note.body.correction.id, status: "applied", reply: "Added black beans: have." }] } });
    expect(answered.result.content[0].text).toBe("1 answered.");

    const phone = await request(app).get("/v1/kitchen").set("Authorization", auth).expect(200);
    expect(phone.body.corrections.map((entry: { reply: string }) => entry.reply)).toContain("Added black beans: have.");
    expect(phone.body.pantry[0]).toMatchObject({ name: "Eggs", status: "out", basis: "Pixel said it's out" });
    await request(app).delete(`/v1/kitchen/corrections/${note.body.correction.id}`).set("Authorization", auth).expect(404);
  });
});

describe("review fixes, October 28", () => {
  it("out on an item that is already out lists it again, and the reply says what happened", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const [eggs] = await kitchen.setPantry([{ name: "Eggs", status: "out" }]);
    expect(eggs.listItem?.name).toBe("Eggs");
    await kitchen.deleteItem(eggs.listItem!.id);
    const again = await kitchen.addCorrection({ kind: "out", itemId: eggs.item!.id }, "Pixel");
    expect(again?.listItem?.name).toBe("Eggs");
    expect(again?.correction.reply).toBe("Marked out and added to the shopping list.");
    const third = await kitchen.addCorrection({ kind: "out", itemId: eggs.item!.id }, "Pixel");
    expect(third?.listItem).toBeUndefined();
    expect(third?.correction.reply).toBe("Marked out; it was already on the shopping list.");
    expect((await kitchen.snapshot()).list.filter((item) => item.name === "Eggs")).toHaveLength(1);
  });

  it("have takes a pantry-added item off the list, but leaves ones a person added or that are ordered", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const [eggs] = await kitchen.setPantry([{ name: "Eggs", status: "out" }]);
    const have = await kitchen.addCorrection({ kind: "have", itemId: eggs.item!.id }, "Pixel");
    expect(have?.removedListItemId).toBe(eggs.listItem!.id);
    expect(have?.correction.reply).toBe("Marked as on hand and taken off the shopping list.");
    expect((await kitchen.snapshot()).list).toHaveLength(0);

    await kitchen.addItems([{ name: "Milk" }], "device", "Pixel");
    const [milk] = await kitchen.setPantry([{ name: "Milk", status: "out" }]);
    const keep = await kitchen.addCorrection({ kind: "have", itemId: milk.item!.id }, "Pixel");
    expect(keep?.removedListItemId).toBeUndefined();
    expect(keep?.correction.reply).toBe("Marked as on hand; it's still on the shopping list.");

    const [rice] = await kitchen.setPantry([{ name: "Rice", status: "out" }]);
    await kitchen.updateItem(rice.listItem!.id, { status: "ordered", orderRef: "Order 1" });
    const ordered = await kitchen.addCorrection({ kind: "have", itemId: rice.item!.id }, "Pixel");
    expect(ordered?.removedListItemId).toBeUndefined();
    expect((await kitchen.snapshot()).list.map((item) => item.name).sort()).toEqual(["Milk", "Rice"]);
  });

  it("a device PATCH to out lists the item even when it was already out, and have unlists it", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const [beans] = await kitchen.setPantry([{ name: "Black beans", status: "out" }]);
    await kitchen.deleteItem(beans.listItem!.id);
    const out = await kitchen.updatePantryItem(beans.item!.id, { status: "out" });
    expect(out?.listItem?.name).toBe("Black beans");
    const have = await kitchen.updatePantryItem(beans.item!.id, { status: "have" });
    expect(have?.removedListItemId).toBe(out!.listItem!.id);
  });

  it("a correction sent twice with the same clientId is stored and applied once", async () => {
    const app = testApp();
    const auth = await pair(app, "Pixel");
    const kitchenNote = { kind: "note", text: "Tossed the cilantro", clientId: "u-1234" };
    const first = await request(app).post("/v1/kitchen/corrections").set("Authorization", auth).send(kitchenNote).expect(201);
    const second = await request(app).post("/v1/kitchen/corrections").set("Authorization", auth).send(kitchenNote).expect(200);
    expect(second.body.repeated).toBe(true);
    expect(second.body.correction.id).toBe(first.body.correction.id);
    expect(second.body.revision).toBe(first.body.revision);
    const read = await request(app).get("/v1/kitchen").set("Authorization", auth).expect(200);
    expect(read.body.corrections).toHaveLength(1);
    await request(app).post("/v1/kitchen/corrections").set("Authorization", auth).send({ ...kitchenNote, clientId: "bad id!" }).expect(400);
  });

  it("keeps a note about an item that was removed, without the item", async () => {
    const app = testApp();
    const auth = await pair(app, "Pixel");
    const note = await request(app).post("/v1/kitchen/corrections").set("Authorization", auth)
      .send({ kind: "note", text: "Only half a bag left", itemId: "pgone" }).expect(201);
    expect(note.body.correction).toMatchObject({ kind: "note", status: "open", text: "Only half a bag left" });
    expect(note.body.correction.itemId).toBeUndefined();
    await request(app).post("/v1/kitchen/corrections").set("Authorization", auth).send({ kind: "out", itemId: "pgone" }).expect(404);
  });

  it("checks the open-note limit before changing anything, and out and have are not limited by it", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const [eggs] = await kitchen.setPantry([{ name: "Eggs", status: "have" }]);
    for (let index = 0; index < 200; index++) await kitchen.addCorrection({ kind: "note", text: `note ${index}` }, "Pixel");
    await expect(kitchen.addCorrection({ kind: "note", text: "one too many", itemId: eggs.item!.id }, "Pixel")).rejects.toThrow(/open/);
    const out = await kitchen.addCorrection({ kind: "out", itemId: eggs.item!.id }, "Pixel");
    expect(out?.correction.status).toBe("applied");
  });

  it("the 400-item limit counts open items only, not the month of bought ones", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    for (let batch = 0; batch < 8; batch++) {
      const added = await kitchen.addItems(Array.from({ length: 50 }, (_, index) => ({ name: `Thing ${batch}-${index}` })), "ai", "Claude");
      for (const { item } of added) await kitchen.updateItem(item.id, { status: "got" });
    }
    const [more] = await kitchen.addItems([{ name: "Bread" }], "device", "Pixel");
    expect(more.created).toBe(true);
    await expect(kitchen.addItems(Array.from({ length: 400 }, (_, index) => ({ name: `Open ${index}` })), "ai", "Claude")).rejects.toThrow(/at most 400/);
  });

  it("the snapshot says which day it is, so the apps can move on at midnight", async () => {
    let now = new Date("2026-10-28T05:30:00Z");
    const kitchen = new KitchenService(new MemoryCalendarStore(), () => now);
    expect((await kitchen.snapshot()).today).toBe("2026-10-27");
    now = new Date("2026-10-28T06:30:00Z");
    expect((await kitchen.snapshot()).today).toBe("2026-10-28");
  });

  it("Claude's estimate doesn't overwrite a person's correction from the last day unless it says override", async () => {
    let now = new Date("2026-10-28T15:00:00Z");
    const kitchen = new KitchenService(new MemoryCalendarStore(), () => now);
    const [eggs] = await kitchen.setPantry([{ name: "Eggs", status: "have", basis: "bought 10/26" }]);
    await kitchen.addCorrection({ kind: "out", itemId: eggs.item!.id }, "Pixel");
    now = new Date("2026-10-28T18:00:00Z");
    const [kept] = await kitchen.setPantry([{ name: "Eggs", status: "have", basis: "bought 10/26" }]);
    expect(kept.item).toMatchObject({ status: "out", basis: "Pixel said it's out" });
    expect(kept.keptPersonStatus).toBe(true);
    const [forced] = await kitchen.setPantry([{ name: "Eggs", status: "low", basis: "they found a carton", override: true }]);
    expect(forced.item).toMatchObject({ status: "low", basis: "they found a carton" });
    await kitchen.addCorrection({ kind: "have", itemId: eggs.item!.id }, "Pixel");
    now = new Date("2026-10-29T19:00:00Z");
    const [later] = await kitchen.setPantry([{ name: "Eggs", status: "low", basis: "used in Thu omelets" }]);
    expect(later.item?.status).toBe("low");
    expect(later.keptPersonStatus).toBeUndefined();
  });

  it("writes that change nothing don't bump the revision", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const [{ item }] = await kitchen.addItems([{ name: "Milk", qty: "1 gal" }], "device", "Pixel");
    await kitchen.setNote("2026-10-28", { cooked: "yes" });
    const before = (await kitchen.snapshot()).revision;
    await kitchen.updateItem(item.id, { qty: "1 gal" });
    await kitchen.addItems([{ name: "milk" }], "device", "Pixel");
    await kitchen.setNote("2026-10-28", { cooked: "yes" });
    expect(await kitchen.deleteItem("kmissing")).toBe(false);
    expect(await kitchen.withdrawCorrection("cmissing")).toBe(false);
    expect((await kitchen.snapshot()).revision).toBe(before);
    expect(await kitchen.revision()).toBe(before);
  });

  it("tells names in other scripts apart", () => {
    expect(normalizeName("Молоко")).toBe(normalizeName("молоко"));
    expect(normalizeName("молоко")).not.toBe(normalizeName("хлеб"));
    expect(normalizeName("豆腐")).not.toBe(normalizeName("味噌"));
    expect(normalizeName("Black Beans")).toBe("blackbean");
    expect(normalizeName("!!!")).not.toBe("");
  });

  it("refuses plans Firestore can't store", async () => {
    const kitchen = new KitchenService(new MemoryCalendarStore());
    const nested = { ...plan("2026-11-02", "2026-11-08", ["Tacos"]), grid: [[1, 2], [3]] };
    await expect(kitchen.publishWeek(nested)).rejects.toThrow(/list inside a list/);
    let deep: Record<string, unknown> = { leaf: true };
    for (let level = 0; level < 25; level++) deep = { next: deep };
    await expect(kitchen.publishWeek({ ...plan("2026-11-02", "2026-11-08", ["Tacos"]), deep })).rejects.toThrow(/nested/);
  });

  it("Undo puts a removed bought or ordered line back as it was", async () => {
    const app = testApp();
    const auth = await pair(app, "Pixel");
    const restored = await request(app).post("/v1/kitchen/items").set("Authorization", auth)
      .send({ name: "Foil", qty: "1", restore: true, status: "ordered", orderRef: "Order 7, Tue" }).expect(201);
    expect(restored.body.item).toMatchObject({ name: "Foil", status: "ordered", orderRef: "Order 7, Tue" });
    const got = await request(app).post("/v1/kitchen/items").set("Authorization", auth)
      .send({ name: "Coffee filters", restore: true, status: "got" }).expect(201);
    expect(got.body.item.status).toBe("got");
    expect(got.body.item.doneAt).toBeTruthy();
    await request(app).post("/v1/kitchen/items").set("Authorization", auth).send({ name: "X", restore: true, status: "lost" }).expect(400);
    const plain = await request(app).post("/v1/kitchen/items").set("Authorization", auth).send({ name: "Tea", status: "got" }).expect(201);
    expect(plain.body.item.status).toBe("need");
  });

  it("keeps dinner notes for about a year", async () => {
    let now = new Date("2026-10-28T18:00:00Z");
    const store = new MemoryCalendarStore();
    const kitchen = new KitchenService(store, () => now);
    await kitchen.setNote("2025-09-01", { rating: "loved" });
    await kitchen.setNote("2026-10-01", { rating: "ok" });
    now = new Date("2026-10-29T18:00:00Z");
    await kitchen.setNote("2026-10-28", { rating: "no" });
    const days = Object.keys(((await store.getKv("kitchen:notes")) as { days: Record<string, unknown> }).days).sort();
    expect(days).toEqual(["2026-10-01", "2026-10-28"]);
  });

  it("tells Claude to mark a ran-out staple out in the pantry", async () => {
    const app = testApp();
    const init = await mcp(app, "initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "t", version: "1" } });
    expect(init.result.instructions).toMatch(/ran out of[^.]*kitchen_pantry_set/);
    expect(init.result.instructions).toContain("09:00 America/Denver");
  });
});
