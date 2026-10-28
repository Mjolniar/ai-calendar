import request from "supertest";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { deleteApp, initializeApp, type App } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createApp } from "../../src/app.js";
import { NoopNotifier } from "../../src/notifier.js";
import { FirestoreCalendarStore } from "../../src/store.js";

/**
 * Every MCP tool, called over HTTP exactly as an AI client calls it, with Firestore in the emulator.
 * Microsoft Graph and Canvas are fakes (no network). Run with `npm run test:emulator`.
 */
const emulator = process.env.FIRESTORE_EMULATOR_HOST;

const CANVAS = "https://school.example.test";
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** Canvas: an assignment, a quiz, and a reflection that is submitted once `canvasState.reflectionDone`. */
const canvasState = { reflectionDone: false };
function canvasFake(url: string): Response {
  if (url.includes("/api/v1/courses?")) return json([{ id: 1, name: "Workplace Ethics", course_code: "ETH 110" }]);
  if (url.includes("/api/v1/courses/1/assignments/")) return json({ id: Number(url.split("/").at(-1)), name: "Essay", points_possible: 25, submission_types: ["online_upload"], due_at: "2099-03-10T23:59:00-07:00" });
  if (url.includes("/api/v1/courses/1/quizzes/")) return json({ id: 7, title: "Chapter 3 Quiz", points_possible: 10, due_at: "2099-03-12T23:59:00-06:00", allowed_attempts: 2 });
  if (url.includes("/api/v1/planner/items")) {
    const reflection = { course_id: 1, plannable_id: 43, plannable_type: "assignment", plannable_date: "2099-03-01T23:59:00-07:00", context_name: "ETH 110", plannable: { id: 43, title: "Reflection", due_at: "2099-03-01T23:59:00-07:00" } };
    if (url.includes("complete_items") && !url.includes("incomplete_items")) return json(canvasState.reflectionDone ? [reflection] : []);
    return json([
      { course_id: 1, plannable_id: 42, plannable_type: "assignment", plannable_date: "2099-03-10T23:59:00-07:00", context_name: "ETH 110", html_url: `${CANVAS}/courses/1/assignments/42`, plannable: { id: 42, title: "Essay", due_at: "2099-03-10T23:59:00-07:00" } },
      { course_id: 1, plannable_id: 7, plannable_type: "quiz", plannable_date: "2099-03-12T23:59:00-06:00", context_name: "ETH 110", html_url: `${CANVAS}/courses/1/quizzes/7`, plannable: { id: 7, title: "Chapter 3 Quiz", due_at: "2099-03-12T23:59:00-06:00" } },
      ...(canvasState.reflectionDone ? [] : [reflection]),
    ]);
  }
  return json({ errors: [{ message: "not found" }] }, 404);
}

/** Microsoft Graph for one mailbox: messages, folders, rules, send. */
function graphFake(log: string[]) {
  const folders: Array<{ id: string; displayName: string; childFolderCount: number; totalItemCount: number }> = [
    { id: "EMPTY==", displayName: "old-empty", childFolderCount: 0, totalItemCount: 0 },
    { id: "FULL==", displayName: "Receipts", childFolderCount: 0, totalItemCount: 3 },
  ];
  const rules: Array<Record<string, unknown>> = [];
  return (url: string, method: string, body: string | undefined): Response => {
    log.push(`${method} ${url}`);
    if (url.endsWith("/oauth2/v2.0/token")) return json({ access_token: "at", refresh_token: "rt2", expires_in: 3600, scope: "Mail.ReadWrite Mail.Send User.Read MailboxSettings.ReadWrite" });
    if (url.includes("/me?$select")) return json({ mail: "user@example.com" });
    if (url.includes("/mailFolders/deleteditems?")) return json({ id: "DELETED==" });
    const wellKnown = url.match(/\/mailFolders\/(inbox|sentitems|drafts|junkemail|archive|outbox)\?\$select=id$/);
    if (wellKnown) return json({ id: `WK-${wellKnown[1]}` });
    if (/\/mailFolders\/WK-[a-z]+\?\$select=id,parentFolderId/.test(url)) return json({ id: "WK", parentFolderId: "ROOT" });
    if (url.includes("/mailFolders/msgfolderroot?")) return json({ id: "ROOT" });
    const one = url.match(/\/mailFolders\/([^/?]+)\?\$select=id,(displayName|parentFolderId)/);
    if (one && method === "GET") {
      const folder = folders.find((f) => f.id === one[1]);
      return folder ? json({ ...folder, parentFolderId: "WK-inbox" }) : json({ error: { code: "ErrorItemNotFound", message: "x" } }, 404);
    }
    if (method === "DELETE" && url.includes("/mailFolders/")) return new Response(null, { status: 204 });
    if (url.includes("/childFolders") && method === "GET") return json({ value: folders });
    if (url.includes("/childFolders") && method === "POST") {
      const folder = { id: `NEW${folders.length}==`, displayName: (JSON.parse(body!) as { displayName: string }).displayName, childFolderCount: 0, totalItemCount: 0 };
      folders.push(folder);
      return json(folder, 201);
    }
    if (url.endsWith("/mailFolders/inbox/messageRules") && method === "GET") return json({ value: rules });
    if (url.endsWith("/mailFolders/inbox/messageRules") && method === "POST") {
      const rule = { id: `R${rules.length + 1}`, ...(JSON.parse(body!) as object) };
      rules.push(rule);
      return json(rule, 201);
    }
    if (url.includes("/messageRules/") && method === "PATCH") {
      const rule = rules.find((r) => r.id === url.split("/messageRules/")[1])!;
      Object.assign(rule, JSON.parse(body!));
      return json(rule);
    }
    if (url.includes("/mailFolders/inbox/messages")) {
      return json({ value: [{ id: "m1", subject: "Rent receipt", from: { emailAddress: { address: "landlord@example.com" } }, receivedDateTime: "2026-10-20T10:00:00Z", bodyPreview: "x" }] });
    }
    if (url.includes("$select=internetMessageHeaders")) return json({ internetMessageHeaders: [{ name: "List-Unsubscribe", value: "<mailto:unsub@list.example.com?subject=remove>" }] });
    if (/\/me\/messages\/m1\?/.test(url)) {
      return json({ id: "m1", subject: "Rent receipt", from: { emailAddress: { address: "landlord@example.com", name: "Landlord" } }, receivedDateTime: "2026-10-20T10:00:00Z", body: { contentType: "text", content: "Paid. Thanks." }, hasAttachments: false, internetMessageHeaders: [] });
    }
    if (url.endsWith("/me/sendMail")) return new Response(null, { status: 202 });
    if (url.endsWith("/$batch")) {
      const requests = (JSON.parse(body!) as { requests: Array<{ id: string; url: string }> }).requests;
      return json({ responses: requests.map((r) => ({ id: r.id, status: 201, body: { id: `moved-${r.id}` } })) });
    }
    if (url.includes("/move")) return json({ id: "m1-moved" });
    return json({ error: { code: "NotFound", message: url } }, 404);
  };
}

describe.skipIf(!emulator)("every MCP tool against Firestore (emulator)", () => {
  let firebase: App;
  let app: ReturnType<typeof createApp>;
  let store: FirestoreCalendarStore;
  const graphLog: string[] = [];

  beforeAll(async () => {
    const projectId = `demo-mcp-${Date.now()}`;
    firebase = initializeApp({ projectId }, projectId);
    store = new FirestoreCalendarStore(getFirestore(firebase));
    const graph = graphFake(graphLog);
    const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith(CANVAS)) return canvasFake(url);
      return graph(decodeURIComponent(url), init?.method ?? "GET", typeof init?.body === "string" ? init.body : undefined);
    }) as typeof fetch;
    app = createApp(store, new NoopNotifier(), {
      apiToken: "test-api-token",
      pairingCode: "test-pairing-code-123",
      firebaseClientConfig: { projectId, appId: "a", apiKey: "k", senderId: "s" },
      publicBaseUrl: "https://calendar.example.test/api",
      canvas: { baseUrl: CANVAS, accessToken: "canvas-token" },
      outlook: { clientId: "client", clientSecret: "secret" },
      fetcher,
    });
    await store.putKv("outlookAccount", { refreshToken: "rt", email: "user@example.com", connectedAt: "", updatedAt: "" });
  });

  afterAll(async () => {
    if (firebase) await deleteApp(firebase);
  });

  async function rpc(method: string, params: unknown) {
    const res = await request(app).post("/mcp")
      .set("Authorization", "Bearer test-api-token")
      .set("Accept", "application/json, text/event-stream")
      .send({ jsonrpc: "2.0", id: 1, method, params });
    const line = res.text.split("\n").find((l) => l.startsWith("data: "));
    return JSON.parse(line ? line.slice(6) : res.text);
  }

  async function call(name: string, args: Record<string, unknown> = {}) {
    const answer = await rpc("tools/call", { name, arguments: args });
    expect(answer.error, `${name}: ${JSON.stringify(answer.error)}`).toBeUndefined();
    return answer.result as { isError?: boolean; structuredContent: Record<string, any>; content: Array<{ text: string }> };
  }

  it("lists exactly the 33 tools, each with an input schema", async () => {
    const answer = await rpc("tools/list", {});
    const names = (answer.result.tools as Array<{ name: string; inputSchema: unknown }>).map((t) => t.name).sort();
    expect(names).toEqual([
      "create_reminder", "create_series", "delete_reminder", "delete_series", "kitchen_corrections_resolve", "kitchen_get", "kitchen_list_add", "kitchen_list_update", "kitchen_pantry_set",
      "kitchen_week_publish", "list_canvas_assignments", "list_reminders", "list_series",
      "outlook_create_folder", "outlook_create_rule", "outlook_delete_folder", "outlook_get_message", "outlook_list_folders", "outlook_list_messages",
      "outlook_list_rules", "outlook_move_message", "outlook_move_messages", "outlook_send_mail", "outlook_status", "outlook_unsubscribe",
      "outlook_update_rule", "planner_state_get", "planner_state_list", "planner_state_put", "publish_task_reminders", "sync_canvas_assignments",
      "update_reminder", "update_series",
    ]);
  });

  it("create, list, update and delete a reminder, idempotently", async () => {
    const input = { title: "TEST dentist", startAt: "2099-05-04T09:00:00-06:00", timeZone: "America/Denver", critical: true, idempotencyKey: "it-dentist-0001" };
    const created = await call("create_reminder", input);
    expect(created.structuredContent.created).toBe(true);
    expect(created.structuredContent.event.reminderOffsetsMinutes).toEqual([1440, 0]);
    const again = await call("create_reminder", input);
    expect(again.structuredContent.created).toBe(false);
    expect(again.structuredContent.event.id).toBe(created.structuredContent.event.id);
    const id = created.structuredContent.event.id as string;

    const listed = await call("list_reminders");
    expect(listed.structuredContent.events.map((e: { id: string }) => e.id)).toContain(id);

    const updated = await call("update_reminder", { id, title: "TEST dentist (moved)", startAt: "2099-05-05T10:30:00-06:00" });
    expect(updated.structuredContent.event).toMatchObject({ title: "TEST dentist (moved)", startAt: "2099-05-05T16:30:00.000Z" });

    const done = await call("update_reminder", { id, done: true });
    expect(done.structuredContent.event.done).toBe(true);
    expect((await call("list_reminders")).structuredContent.events.map((e: { id: string }) => e.id)).not.toContain(id);
    expect((await call("list_reminders", { includeDone: true })).structuredContent.events.map((e: { id: string }) => e.id)).toContain(id);

    expect((await call("delete_reminder", { id })).structuredContent.deleted).toBe(true);
    expect((await call("delete_reminder", { id })).structuredContent.deleted).toBe(false);
  });

  it("publish_task_reminders updates instead of duplicating, and a deleted item stays deleted", async () => {
    const reminders = [
      { itemKey: "bill", title: "TEST power bill", startAt: "2099-06-01T09:00:00-06:00", timeZone: "America/Denver" },
      { itemKey: "parcel", title: "TEST lamp", startAt: "2099-06-02T09:00:00-06:00", timeZone: "America/Denver", domain: "delivery", reminderOffsetsMinutes: [60] },
    ];
    const first = await call("publish_task_reminders", { taskKey: "it-planner", runId: "r1", reminders });
    expect(first.structuredContent).toMatchObject({ created: 2, updated: 0, unchanged: 0 });
    const delivery = first.structuredContent.reminders.find((r: { itemKey: string }) => r.itemKey === "parcel").event;
    expect(delivery.reminderOffsetsMinutes).toEqual([]);

    const second = await call("publish_task_reminders", { taskKey: "it-planner", runId: "r2", reminders: [{ ...reminders[0], notes: "Autopay is off" }, reminders[1]] });
    expect(second.structuredContent).toMatchObject({ created: 0, updated: 1, unchanged: 1 });

    const billId = first.structuredContent.reminders[0].event.id as string;
    await call("delete_reminder", { id: billId });
    const third = await call("publish_task_reminders", { taskKey: "it-planner", reminders: [reminders[0]] });
    expect(third.structuredContent.suppressedItemKeys).toEqual(["bill"]);
    const restored = await call("publish_task_reminders", { taskKey: "it-planner", restoreDeleted: true, reminders: [reminders[0]] });
    expect(restored.structuredContent.created).toBe(1);
  });

  it("repeating reminders: create, list, change from a date, delete from a date, delete", async () => {
    const created = await call("create_series", {
      title: "TEST trash",
      timeZone: "America/Denver",
      reminderOffsetsMinutes: [900],
      rule: { freq: "weekly", weekdays: [5], startDate: "2099-10-30", time: "08:00", count: 6 },
      idempotencyKey: "it-series-0001",
    });
    expect(created.structuredContent.created).toBe(true);
    const occurrences = created.structuredContent.occurrences as Array<{ occurrenceDate: string; startAt: string; seriesId: string }>;
    expect(occurrences.length).toBeGreaterThanOrEqual(2);
    // Denver: 8:00 AM is 14:00 UTC in daylight time and 15:00 UTC after the change on November 1.
    // (Far in the future, only the next two are listed; they straddle the change.)
    const byDate = Object.fromEntries(occurrences.map((o) => [o.occurrenceDate, o.startAt]));
    expect(byDate["2099-10-30"]).toBe("2099-10-30T14:00:00.000Z");
    expect(byDate["2099-11-06"]).toBe("2099-11-06T15:00:00.000Z");
    const id = created.structuredContent.series.id as string;

    expect((await call("list_series")).structuredContent.series.map((s: { id: string }) => s.id)).toContain(id);
    const listed = (await call("list_reminders")).structuredContent.events.filter((e: { seriesId?: string }) => e.seriesId === id);
    expect(listed.length).toBe(occurrences.length);

    const split = await call("update_series", { id, from: "2099-11-06", title: "TEST trash and recycling", idempotencyKey: "it-split-0001" });
    expect(split.structuredContent.updated).toBe(true);
    const newId = split.structuredContent.series.id as string;
    expect(newId).not.toBe(id);
    expect(split.structuredContent.previous.rule.until).toBe("2099-11-05");

    const cut = await call("delete_series", { id: newId, from: "2099-11-20" });
    expect(cut.structuredContent.deleted).toBe(true);
    const whole = await call("delete_series", { id });
    expect(whole.structuredContent.deleted).toBe(true);
    await call("delete_series", { id: newId });
    expect((await call("list_series")).structuredContent.series.map((s: { id: string }) => s.id)).not.toContain(newId);
  });

  it("Canvas: sync imports assignments with the right warnings, archives submitted work, and is idempotent", async () => {
    const synced = await call("sync_canvas_assignments");
    expect(synced.structuredContent).toMatchObject({ created: 3, completed: 0, archived: 0 });
    const listed = await call("list_canvas_assignments");
    const quiz = listed.structuredContent.events.find((e: { title: string }) => e.title === "Chapter 3 Quiz");
    expect(quiz).toMatchObject({ critical: true, reminderOffsetsMinutes: [4320, 1440, 120, 60, 0], courseCode: "ETH 110", domain: "school" });
    const essay = listed.structuredContent.events.find((e: { title: string }) => e.title === "Essay");
    expect(essay).toMatchObject({ critical: false, reminderOffsetsMinutes: [1440] });
    // Submitting the reflection in Canvas files it under Archived at the next sync.
    canvasState.reflectionDone = true;
    const after = await call("sync_canvas_assignments");
    expect(after.structuredContent).toMatchObject({ created: 0, updated: 0, completed: 1, archived: 1 });
    expect((await call("list_canvas_assignments")).structuredContent.events.map((e: { title: string }) => e.title)).not.toContain("Reflection");
    const again = await call("sync_canvas_assignments");
    expect(again.structuredContent).toMatchObject({ created: 0, updated: 0, archived: 0 });
  });

  it("planner state: put, append, get, list", async () => {
    await call("planner_state_put", { key: "it-state.json", content: "{\"v\":1}" });
    await call("planner_state_put", { key: "it-log.log", content: "line 1" });
    await call("planner_state_put", { key: "it-log.log", content: "line 2", mode: "append" });
    expect((await call("planner_state_get", { key: "it-log.log" })).structuredContent.content).toBe("line 1\nline 2");
    expect((await call("planner_state_get", { key: "missing.json" })).structuredContent.exists).toBe(false);
    const files = (await call("planner_state_list")).structuredContent.files.map((f: { key: string }) => f.key);
    expect(files).toEqual(expect.arrayContaining(["it-log.log", "it-state.json"]));
    const bad = await rpc("tools/call", { name: "planner_state_get", arguments: { key: "../events/x" } });
    expect(bad.result?.isError ?? Boolean(bad.error)).toBe(true);
  });

  it("kitchen: list, pantry rules and a week, through Firestore transactions", async () => {
    const added = await Promise.all(["TEST foil", "TEST foil", "TEST milk"].map((name) => call("kitchen_list_add", { items: [{ name }] })));
    expect(added.filter((answer) => answer.structuredContent.items[0].created)).toHaveLength(2);
    const pantry = await call("kitchen_pantry_set", { items: [{ name: "TEST rice", section: "Grains", status: "out" }] });
    expect(pantry.structuredContent.results[0].listItem.name).toBe("TEST rice");
    const snapshot = await call("kitchen_get");
    const rice = snapshot.structuredContent.list.find((item: { name: string }) => item.name === "TEST rice");
    await call("kitchen_list_update", { updates: [{ id: rice.id, status: "got" }] });
    const after = await call("kitchen_get");
    expect(after.structuredContent.pantry.find((item: { name: string }) => item.name === "TEST rice").status).toBe("have");
    const week = await call("kitchen_week_publish", { plan: { week: { start: "2099-01-06", end: "2099-01-12" }, days: [{ date: "2099-01-06", dinner: "TEST salmon" }] } });
    expect(week.structuredContent).toMatchObject({ start: "2099-01-06", replaced: false });
    expect(after.structuredContent.list.filter((item: { name: string }) => item.name.startsWith("TEST")).length).toBe(3);
  });

  it("Outlook: status, messages, moves, folders, rules, send and unsubscribe", async () => {
    expect((await call("outlook_status")).structuredContent).toMatchObject({ connected: true, email: "user@example.com" });
    const messages = await call("outlook_list_messages", { folder: "inbox", top: 5 });
    expect(messages.structuredContent.messages[0]).toMatchObject({ id: "m1", subject: "Rent receipt" });
    expect((await call("outlook_get_message", { id: "m1" })).structuredContent.subject).toBe("Rent receipt");
    expect((await call("outlook_move_message", { id: "m1", destination: "archive" })).isError).toBeFalsy();
    expect((await call("outlook_move_messages", { ids: ["a1", "a2"], destination: "archive" })).structuredContent).toMatchObject({ moved: 2, failed: 0 });

    const folders = await call("outlook_list_folders");
    expect(folders.structuredContent.folders.map((f: { displayName: string }) => f.displayName)).toContain("Receipts");
    expect((await call("outlook_create_folder", { displayName: "Bills" })).structuredContent.created).toBe(true);
    expect((await call("outlook_create_folder", { displayName: "bills" })).structuredContent.created).toBe(false);
    expect((await call("outlook_delete_folder", { id: "EMPTY==", expectedName: "old-empty" })).isError).toBeFalsy();
    expect((await call("outlook_delete_folder", { id: "FULL==", expectedName: "Receipts" })).isError).toBe(true);

    const rule = await call("outlook_create_rule", { displayName: "Receipts", moveToFolder: "FULL==", conditions: { senderContains: ["landlord@example.com"] } });
    expect(rule.structuredContent.created).toBe(true);
    const ruleId = rule.structuredContent.rule.id as string;
    expect((await call("outlook_list_rules")).structuredContent.rules.map((r: { id: string }) => r.id)).toContain(ruleId);
    expect((await call("outlook_update_rule", { id: ruleId, isEnabled: false })).structuredContent.updated).toBe(true);
    const forward = await rpc("tools/call", { name: "outlook_create_rule", arguments: { displayName: "Fwd", moveToFolder: "FULL==", forwardTo: ["x@example.com"] } });
    expect(forward.result?.isError ?? Boolean(forward.error)).toBe(true);

    expect((await call("outlook_send_mail", { to: ["office@example.com"], subject: "Question", body: "What time?" })).isError).toBeFalsy();
    expect((await call("outlook_unsubscribe", { id: "m1" })).structuredContent.unsubscribed).toBe(true);
    expect(graphLog.some((l) => l.startsWith("DELETE") && l.includes("/messages/"))).toBe(false);
  });
});
