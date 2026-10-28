import request from "supertest";
import { describe, expect, it } from "vitest";
import { createApp } from "../src/app.js";
import { NoopNotifier } from "../src/notifier.js";
import { MemoryCalendarStore } from "../src/store.js";

const BASE = "https://calendar.example.test/api";
const DELETED_ID = "AQMkDeletedItems00=";

type Call = { method: string; url: string; body?: unknown; scope?: string | null };

function mailbox(opts: { grantMailboxSettings?: boolean; rejectFullScope?: boolean } = {}) {
  const calls: Call[] = [];
  const folders: Array<{ id: string; displayName: string; parentFolderId: string }> = [];
  const rules: Array<Record<string, unknown>> = [];
  const deleted: string[] = [];
  const extra = [
    { id: "OLD1==", displayName: "sign-ups", childFolderCount: 0, totalItemCount: 0 },
    { id: "OLD2==", displayName: "Steam", childFolderCount: 0, totalItemCount: 5 },
    { id: "OLD3==", displayName: "Parent", childFolderCount: 1, totalItemCount: 0 },
    { id: "WK-inbox", displayName: "Inbox", childFolderCount: 0, totalItemCount: 0 },
  ];
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  const fetcher = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = decodeURIComponent(String(input));
    const method = init?.method ?? "GET";
    const body = typeof init?.body === "string" && method !== "POST" || (typeof init?.body === "string" && !url.endsWith("/token")) ? JSON.parse(String(init?.body)) : undefined;
    if (url.endsWith("/oauth2/v2.0/token")) {
      const scope = new URLSearchParams(String(init?.body)).get("scope");
      calls.push({ method, url, scope });
      if (opts.rejectFullScope && scope?.includes("MailboxSettings")) return json({ error: "invalid_grant", error_description: "AADSTS65001: consent required" }, 400);
      const granted = opts.grantMailboxSettings === false ? "Mail.ReadWrite Mail.Send User.Read" : "Mail.ReadWrite Mail.Send User.Read MailboxSettings.ReadWrite";
      return json({ access_token: "at", refresh_token: "rt2", expires_in: 3600, scope: granted });
    }
    calls.push({ method, url, body });
    if (url.includes("/mailFolders/deleteditems?")) return json({ id: DELETED_ID });
    const wk = url.match(/\/mailFolders\/(msgfolderroot|inbox|sentitems|drafts|junkemail|archive|outbox)\?\$select=id$/);
    if (wk) return json({ id: `WK-${wk[1]}` });
    const one = url.match(/\/mailFolders\/([^/?]+)\?\$select=id,(displayName|parentFolderId)/);
    if (one && method === "GET") {
      // Every folder here sits under the Inbox or the mailbox root, never under Deleted Items.
      if (one[1].startsWith("WK-")) return json({ id: one[1], parentFolderId: "WK-msgfolderroot" });
      const f = extra.find((x) => x.id === one[1]) ?? folders.find((x) => x.id === one[1]);
      if (f) return json({ parentFolderId: "WK-inbox", ...f });
      // Ids the tests use without listing them first are ordinary Inbox subfolders.
      return one[2] === "parentFolderId" ? json({ id: one[1], parentFolderId: "WK-inbox" }) : json({ error: { code: "ErrorItemNotFound", message: "x" } }, 404);
    }
    if (method === "DELETE" && url.includes("/mailFolders/")) { deleted.push(url.split("/mailFolders/")[1]); return new Response(null, { status: 204 }); }
    if (url.includes("/mailFolders/inbox/childFolders") && method === "GET") return json({ value: folders });
    if (url.includes("/mailFolders/inbox/childFolders") && method === "POST") {
      const f = { id: `F${folders.length + 1}==`, displayName: (body as { displayName: string }).displayName, parentFolderId: "INBOX" };
      folders.push(f);
      return json(f, 201);
    }
    if (url.endsWith("/mailFolders/inbox/messageRules") && method === "GET") return json({ value: rules });
    if (url.endsWith("/mailFolders/inbox/messageRules") && method === "POST") {
      const r = { id: `R${rules.length + 1}`, ...(body as object) };
      rules.push(r);
      return json(r, 201);
    }
    if (url.includes("/messageRules/") && method === "PATCH") {
      const id = url.split("/messageRules/")[1];
      const r = rules.find((x) => x.id === id)!;
      Object.assign(r, body);
      return json(r);
    }
    if (url.includes("/messages?")) return json({ value: [] });
    if (url.endsWith("/$batch")) {
      const reqs = (body as { requests: Array<{ id: string; url: string; body: { destinationId: string } }> }).requests;
      return json({ responses: reqs.map((r) => r.url.includes("bad")
        ? { id: r.id, status: 404, body: { error: { code: "ErrorItemNotFound", message: "gone" } } }
        : { id: r.id, status: 201, body: { id: `new-${r.url.split("/")[3]}` } }) });
    }
    if (url.includes("/move")) return json({ id: "moved-1" });
    return json({ error: { code: "NotFound", message: url } }, 404);
  }) as typeof fetch;
  return { calls, folders, rules, fetcher, deleted };
}

async function setup(opts?: Parameters<typeof mailbox>[0]) {
  const store = new MemoryCalendarStore();
  await store.putKv("outlookAccount", { refreshToken: "rt", email: "user@example.com", connectedAt: "", updatedAt: "" });
  const mb = mailbox(opts);
  const app = createApp(store, new NoopNotifier(), {
    apiToken: "test-api-token",
    pairingCode: "654321",
    firebaseClientConfig: { projectId: "p", appId: "a", apiKey: "k", senderId: "s" },
    publicBaseUrl: BASE,
    outlook: { clientId: "c", clientSecret: "s" },
    fetcher: mb.fetcher,
  });
  const call = async (name: string, args: unknown) => {
    const res = await request(app).post("/mcp").set("Authorization", "Bearer test-api-token")
      .set("Accept", "application/json, text/event-stream")
      .send({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } });
    const line = res.text.split("\n").find((l) => l.startsWith("data: "));
    return JSON.parse(line ? line.slice(6) : res.text).result;
  };
  return { ...mb, call, app };
}

describe("Outlook folders", () => {
  it("creates folders idempotently by name and lists them", async () => {
    const t = await setup();
    const a = await t.call("outlook_create_folder", { displayName: "Amazon" });
    expect(a.structuredContent.created).toBe(true);
    const again = await t.call("outlook_create_folder", { displayName: "amazon" });
    expect(again.structuredContent.created).toBe(false);
    expect(again.structuredContent.folder.id).toBe(a.structuredContent.folder.id);
    expect(t.folders).toHaveLength(1);
    const list = await t.call("outlook_list_folders", {});
    expect(list.structuredContent.folders.map((f: { displayName: string }) => f.displayName)).toEqual(["Amazon"]);
  });

  it("lists and moves with custom folder ids but never into Deleted Items", async () => {
    const t = await setup();
    const listed = await t.call("outlook_list_messages", { folder: "F1==" });
    expect(listed.isError).toBeFalsy();
    expect(t.calls.some((c) => c.url.includes("/mailFolders/F1==/messages?"))).toBe(true);
    const moved = await t.call("outlook_move_message", { id: "m1", destination: "F1==" });
    expect(moved.structuredContent.newId).toBe("moved-1");
    const byId = await t.call("outlook_move_message", { id: "m1", destination: DELETED_ID });
    expect(byId.isError).toBe(true);
    const byName = await t.call("outlook_move_message", { id: "m1", destination: "deleteditems" });
    expect(byName.isError).toBe(true);
    const listDeleted = await t.call("outlook_list_messages", { folder: "recoverableitemsdeletions" });
    expect(listDeleted.isError).toBe(true);
    const bad = await t.call("outlook_move_message", { id: "m1", destination: "../../me/drive" });
    expect(bad.isError).toBe(true);
    expect(t.calls.filter((c) => c.url.includes("/move"))).toHaveLength(1);
  });
});

describe("Outlook delete empty folder", () => {
  it("deletes only empty, name-matched, non-well-known folders", async () => {
    const t = await setup();
    const ok = await t.call("outlook_delete_folder", { id: "OLD1==", expectedName: "sign-ups" });
    expect(ok.structuredContent.deleted).toBe(true);
    expect(t.deleted).toEqual(["OLD1=="]);
    expect((await t.call("outlook_delete_folder", { id: "OLD2==", expectedName: "Steam" })).content[0].text).toContain("not empty");
    expect((await t.call("outlook_delete_folder", { id: "OLD3==", expectedName: "Parent" })).isError).toBe(true);
    expect((await t.call("outlook_delete_folder", { id: "OLD1==", expectedName: "Signups" })).content[0].text).toContain("mismatch");
    expect((await t.call("outlook_delete_folder", { id: "inbox", expectedName: "Inbox" })).isError).toBe(true);
    expect((await t.call("outlook_delete_folder", { id: "WK-inbox", expectedName: "Inbox" })).isError).toBe(true);
    expect(t.deleted).toEqual(["OLD1=="]);
  });
});

describe("Outlook batch move", () => {
  it("moves up to 20 ids in one batch and reports per-id failures", async () => {
    const t = await setup();
    const r = await t.call("outlook_move_messages", { ids: ["a1", "bad2", "a3"], destination: "F1==" });
    expect(r.structuredContent.moved).toBe(2);
    expect(r.structuredContent.failed).toBe(1);
    expect(r.structuredContent.results[1]).toMatchObject({ id: "bad2", ok: false });
    const batch = t.calls.find((c) => c.url.endsWith("/$batch"))!.body as { requests: Array<{ method: string; body: unknown }> };
    expect(batch.requests.every((q) => q.method === "POST" && JSON.stringify(q.body) === JSON.stringify({ destinationId: "F1==" }))).toBe(true);
    expect((await t.call("outlook_move_messages", { ids: ["a1"], destination: "deleteditems" })).isError).toBe(true);
    expect((await t.call("outlook_move_messages", { ids: ["a1"], destination: DELETED_ID })).isError).toBe(true);
    expect((await t.call("outlook_move_messages", { ids: Array.from({ length: 21 }, (_, i) => `x${i}`), destination: "F1==" })).isError).toBe(true);
  });
});

describe("Outlook pagination links", () => {
  it("accepts Graph's mailFolders('id') continuation links and refuses others", async () => {
    const t = await setup();
    const ok = await t.call("outlook_list_messages", { nextLink: "https://graph.microsoft.com/v1.0/me/mailFolders('inbox')/messages?%24top=50&%24skip=50" });
    expect(ok.isError).toBeFalsy();
    const custom = await t.call("outlook_list_messages", { nextLink: "https://graph.microsoft.com/v1.0/me/mailFolders('AQMkAD-x_y==')/messages?%24skip=50" });
    expect(custom.isError).toBeFalsy();
    for (const bad of [
      "https://graph.microsoft.com/v1.0/me/mailFolders('inbox')/../../drive/root/children?x=1",
      "https://graph.microsoft.com/v1.0/me/mailFolders('inbox')/messageRules?x=1",
      "https://graph.microsoft.com/v1.0/me/drive/root/children?x=1",
      "https://graph.microsoft.com.evil.example/v1.0/me/mailFolders('inbox')/messages?x=1",
    ]) {
      const r = await t.call("outlook_list_messages", { nextLink: bad });
      expect(r.isError).toBe(true);
    }
  });
});

describe("Outlook inbox rules", () => {
  const rule = {
    displayName: "Mailsort: Amazon",
    sequence: 2,
    conditions: { senderContains: ["@amazon.com", ".amazon.com"] },
    exceptions: { subjectContains: ["refund", "password"] },
    moveToFolder: "F1==",
  };

  it("creates moveToFolder-only rules idempotently with stopProcessingRules on", async () => {
    const t = await setup();
    const r = await t.call("outlook_create_rule", rule);
    expect(r.isError).toBeFalsy();
    expect(r.structuredContent.created).toBe(true);
    const posted = t.calls.find((c) => c.method === "POST" && c.url.endsWith("/messageRules"))!.body as Record<string, unknown>;
    expect(posted.actions).toEqual({ moveToFolder: "F1==", stopProcessingRules: true });
    expect(posted.isEnabled).toBe(true);
    expect(posted.exceptions).toEqual({ subjectContains: ["refund", "password"] });
    const again = await t.call("outlook_create_rule", { ...rule, displayName: "mailsort: amazon" });
    expect(again.structuredContent.created).toBe(false);
    expect(t.rules).toHaveLength(1);
    const list = await t.call("outlook_list_rules", {});
    expect(list.structuredContent.rules[0]).toMatchObject({ displayName: "Mailsort: Amazon", moveToFolder: "F1==", stopProcessingRules: true, hasOtherActions: false });
  });

  it("rejects delete, forward, and redirect actions and Deleted Items targets", async () => {
    const t = await setup();
    for (const extra of [{ delete: true }, { forwardTo: ["x@example.com"] }, { redirectTo: ["x@example.com"] }, { permanentDelete: true }, { actions: { delete: true } }]) {
      const r = await t.call("outlook_create_rule", { ...rule, ...extra });
      expect(r.isError).toBe(true);
    }
    const toDeleted = await t.call("outlook_create_rule", { ...rule, moveToFolder: DELETED_ID });
    expect(toDeleted.isError).toBe(true);
    const noConditions = await t.call("outlook_create_rule", { ...rule, conditions: {} });
    expect(noConditions.isError).toBe(true);
    expect(t.rules).toHaveLength(0);
  });

  it("updates a rule and refuses to edit rules with other actions", async () => {
    const t = await setup();
    await t.call("outlook_create_rule", rule);
    const up = await t.call("outlook_update_rule", { id: "R1", exceptions: { subjectContains: ["refund", "security"] } });
    expect(up.structuredContent.updated).toBe(true);
    expect(t.rules[0].exceptions).toEqual({ subjectContains: ["refund", "security"] });
    const forward = await t.call("outlook_update_rule", { id: "R1", forwardTo: ["x@example.com"] });
    expect(forward.isError).toBe(true);
    t.rules.push({ id: "R9", displayName: "Legacy", actions: { delete: true } });
    const legacy = await t.call("outlook_update_rule", { id: "R9", isEnabled: false });
    expect(legacy.isError).toBe(true);
    expect(legacy.content[0].text).toContain("other than moveToFolder");
  });

  it("falls back to the original scopes and asks for re-consent when MailboxSettings is not granted", async () => {
    const t = await setup({ rejectFullScope: true, grantMailboxSettings: false });
    const status = await t.call("outlook_status", {});
    expect(status.structuredContent.connected).toBe(true);
    expect(status.structuredContent.mailboxSettingsGranted).toBe(false);
    expect(status.structuredContent.reconsentUrl).toBe(`${BASE}/outlook/connect`);
    const rules = await t.call("outlook_list_rules", {});
    expect(rules.isError).toBe(true);
    expect(rules.content[0].text).toContain("Re-consent");
    const mail = await t.call("outlook_list_messages", {});
    expect(mail.isError).toBeFalsy();
  });

  it("refreshes once when a cached token predates re-consent", async () => {
    const t = await setup({ rejectFullScope: true, grantMailboxSettings: false });
    expect((await t.call("outlook_list_messages", {})).isError).toBeFalsy();
    const mb = t as unknown as { calls: Call[] };
    const before = mb.calls.filter((c) => c.url.endsWith("/token")).length;
    const rules = await t.call("outlook_list_rules", {});
    expect(rules.isError).toBe(true);
    expect(mb.calls.filter((c) => c.url.endsWith("/token")).length).toBeGreaterThan(before);
  });

  it("reports MailboxSettings granted after consent", async () => {
    const t = await setup();
    const status = await t.call("outlook_status", {});
    expect(status.structuredContent.mailboxSettingsGranted).toBe(true);
    expect(t.calls.find((c) => c.url.endsWith("/token"))!.scope).toContain("MailboxSettings.ReadWrite");
  });
});
